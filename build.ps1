# Manual APK build for the DSH Android shell.
#
# Why not Gradle:
#   This app has no third-party dependencies (AndroidX is deliberately avoided),
#   so Gradle's dependency resolution and daemon are pure overhead. Four tools
#   are enough: aapt2 / javac / d8 / apksigner. That skips a ~130 MB Gradle
#   download and turns a minute-long build into a couple of seconds.
#
# Pipeline:
#   1. aapt2 compile   resources -> compiled .flat archive
#   2. aapt2 link      resources + manifest -> APK with no dex
#   3. javac           sources -> .class (classpath = android.jar)
#   4. d8              .class -> classes.dex
#   5. zip             classes.dex into the APK
#   6. zipalign        4-byte alignment (Android refuses unaligned APKs)
#   7. apksigner       sign (must happen AFTER alignment)
#
# NOTE: this file is intentionally ASCII-only. Windows PowerShell 5.1 reads a
# .ps1 without a BOM as ANSI, which mangles non-ASCII text into parse errors.
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File build.ps1
#   powershell -ExecutionPolicy Bypass -File build.ps1 -Install

param(
    [switch]$Install,
    # Path to adb; only needed for -Install. Resolved from, in order: this
    # parameter, $env:ADB, then whatever 'adb' is on PATH. Kept blank by default
    # so the script carries no machine-specific path.
    [string]$Adb = ''
)

# 'Continue', not 'Stop': every native tool here writes progress and notes to
# stderr, and with 'Stop' PowerShell promotes the first one into a terminating
# error -- javac's harmless "uses a deprecated API" note would abort the build.
# Each external call below checks $LASTEXITCODE explicitly instead.
$ErrorActionPreference = 'Continue'

$Root  = $PSScriptRoot
$Tc    = Join-Path (Split-Path $Root -Parent) 'toolchain'
$Jdk   = Join-Path $Tc 'jdk'
$App   = Join-Path $Root 'app\src\main'
$Build = Join-Path $Root 'build'
$Out   = Join-Path $Root 'DSH.apk'
$Assets = Join-Path $App 'assets'

function Step($n, $msg) { Write-Host ''; Write-Host "[$n] $msg" -ForegroundColor Cyan }
function Ok($msg)       { Write-Host "    OK  $msg" -ForegroundColor Green }

# ------------------------------------------------------------------ toolchain
Step 1 'Locate toolchain'

$javac   = Join-Path $Jdk 'bin\javac.exe'
$keytool = Join-Path $Jdk 'bin\keytool.exe'
if (-not (Test-Path $javac)) { throw "javac not found; extract the JDK to $Jdk" }

# d8.bat and apksigner.bat are batch wrappers that shell out to java, and they
# look it up through JAVA_HOME (or PATH). Export both so they work even though
# this machine has no system-wide JDK installed.
$env:JAVA_HOME = $Jdk
$env:PATH = (Join-Path $Jdk 'bin') + ';' + $env:PATH

# Prefer the NEWEST unpacked build-tools. d8 8.2.2 (build-tools 34) aborts with
# an internal NullPointerException while reading this project's anonymous inner
# classes; d8 9.0.3 (build-tools 36.1) handles them fine.
#
# Each SDK zip unpacks to btXX-tmp/<codename>/, so descend into the codename.
# Sort on the numeric API level ("android-16" -> 16), not on the directory name:
# Sort-Object is culture-aware by default, and under this locale it orders
# "bt-tmp" before "bt36-tmp" -- which silently selected the broken d8.
$btDir = Get-ChildItem (Join-Path $Tc 'bt*-tmp') -Directory -ErrorAction SilentlyContinue |
    ForEach-Object { Get-ChildItem $_.FullName -Directory -ErrorAction SilentlyContinue } |
    Where-Object { Test-Path (Join-Path $_.FullName 'd8.bat') } |
    Sort-Object { [int]($_.Name -replace '\D', '') } -Descending |
    Select-Object -First 1
if (-not $btDir) { throw 'unpacked build-tools not found' }
$aapt2     = Join-Path $btDir.FullName 'aapt2.exe'
$d8        = Join-Path $btDir.FullName 'd8.bat'
$zipalign  = Join-Path $btDir.FullName 'zipalign.exe'
$apksigner = Join-Path $btDir.FullName 'apksigner.bat'
foreach ($t in @($aapt2, $d8, $zipalign, $apksigner)) {
    if (-not (Test-Path $t)) { throw "missing tool: $t" }
}

$jarHit = Get-ChildItem (Join-Path $Tc 'plat-tmp') -Recurse -Filter 'android.jar' -ErrorAction SilentlyContinue |
    Select-Object -First 1
if (-not $jarHit) { throw 'android.jar not found' }
$androidJar = $jarHit.FullName

Ok "javac       $javac"
Ok "aapt2       $($btDir.Name)\aapt2.exe"
Ok "d8          $($btDir.Name)\d8.bat"
Ok "android.jar $($androidJar.Replace($Tc, '<toolchain>'))"

# ------------------------------------------------------------------ clean
Step 2 'Clean previous output'
Remove-Item $Build -Recurse -Force -ErrorAction SilentlyContinue
Remove-Item $Out -Force -ErrorAction SilentlyContinue
foreach ($d in @('classes', 'dex')) {
    New-Item -ItemType Directory -Force -Path (Join-Path $Build $d) | Out-Null
}
Ok 'build/ recreated'

# ------------------------------------------------------------------ resources
Step 3 'aapt2 compile - resources'

# Rewire the splash completion signal before packaging.
#
# splash.html announces "the animation ended" by dispatching a dsh:splash-complete
# event, and the Android shell listens for it. That event was observed NOT
# arriving even though the animation had finished, which left the splash overlay
# covering the app until a safety timer fired. Rather than keep chasing the event
# timing, the signal is pinned inside the animation's own teardown function:
# the moment finish() runs, it calls the native bridge directly.
#
# The rewrite itself lives in rewire-splash.mjs, not inline here. The first
# attempt did it with PowerShell's String.Replace and silently never ran --
# the same reason this file is ASCII-only: PowerShell 5.1 reads a .ps1 without a
# BOM as ANSI, so non-ASCII comments corrupt parsing of the surrounding script.
#
# Only the copy under app/src/main/assets is touched; the original plugin file is
# left alone. When the upstream animation changes, copy it in again and rebuild.
$splashSrc = Join-Path $Assets 'splash.html'
if (Test-Path $splashSrc) {
    & node (Join-Path $Root 'rewire-splash.mjs') $splashSrc 2>&1 | ForEach-Object { '    ' + $_ }
    if ($LASTEXITCODE -ne 0) { throw 'rewire-splash.mjs failed' }
    Ok 'splash.html rewired'
} else {
    # Not shipped in this repo on purpose: splash.html is an asset of the
    # dsh-opening-splash plugin (MIT), not this project's own file. Copy it from
    # your own install so you always get the version you actually chose.
    Write-Host '    splash.html not present -- building without the opening animation' -ForegroundColor Yellow
    Write-Host '    To enable it: install the dsh-opening-splash plugin, then'
    Write-Host '      copy <plugin>/assets/splash.html to app/src/main/assets/splash.html'
    Write-Host '    and rebuild. See README, "Opening animation".'
}

$resZip = Join-Path $Build 'res.zip'
& $aapt2 compile --dir (Join-Path $App 'res') -o $resZip
if ($LASTEXITCODE -ne 0) { throw 'aapt2 compile failed' }
Ok ("res.zip  {0:N0} B" -f (Get-Item $resZip).Length)

Step 4 'aapt2 link - APK with resources and manifest only'
$baseApk = Join-Path $Build 'base.apk'
$aapt2Args = @(
    'link',
    '-o', $baseApk,
    '-I', $androidJar,
    '--manifest', (Join-Path $App 'AndroidManifest.xml'),
    '--min-sdk-version', '24',
    '--target-sdk-version', '35',
    '--version-code', '1',
    '--version-name', '1.0',
    # The compiled archive is the PRIMARY resource set, so it goes in as a
    # positional argument. Passing it via -R would mark it an overlay, and
    # aapt2 then rejects AppTheme/app_name as "does not override an existing
    # resource" because nothing is underneath them.
    $resZip
)
# -A bundles app/src/main/assets into the APK. That directory holds splash.html,
# the DSH opening animation (the same self-contained document the desktop
# dsh-opening-splash plugin plays). Without -A the file is silently left out and
# the app falls back to no splash screen.
if (Test-Path $Assets) {
    $aapt2Args += @('-A', $Assets)
}
& $aapt2 @aapt2Args
if ($LASTEXITCODE -ne 0) { throw 'aapt2 link failed' }
Ok ("base.apk  {0:N0} B" -f (Get-Item $baseApk).Length)

# ------------------------------------------------------------------ java
Step 5 'javac - compile sources'

$sources = @(Get-ChildItem (Join-Path $App 'java') -Recurse -Filter '*.java' | ForEach-Object { $_.FullName })
Write-Host ("    source files: {0}" -f $sources.Count)
if ($sources.Count -eq 0) { throw 'no .java sources found' }

# Pass sources through an @argfile: a long command line trips the Windows limit.
$argFile = Join-Path $Build 'sources.txt'
[System.IO.File]::WriteAllLines($argFile, $sources, (New-Object System.Text.UTF8Encoding($false)))

$classDir = Join-Path $Build 'classes'
$javacArgs = @(
    # Target Java 8 bytecode: d8 in build-tools 34 aborts with an internal
    # NullPointerException on class files that javac 21 emits for -target 11
    # (anonymous inner classes carry attributes its graph reader mishandles).
    # Java 8 is also the bytecode level every Android toolchain handles cleanly.
    '-source', '8', '-target', '8', '-nowarn', '-Xlint:none',
    '-encoding', 'UTF-8',
    '-classpath', $androidJar,
    '-d', $classDir,
    "@$argFile"
)
# Redirect to a file rather than piping: javac's "note:" lines are localized
# (this machine runs a Chinese locale), so filtering by English keywords is
# fragile. What matters is the exit code.
$javacLog = Join-Path $Build 'javac.log'
& $javac @javacArgs > $javacLog 2>&1
$javacCode = $LASTEXITCODE
if (Test-Path $javacLog) {
    Get-Content $javacLog | ForEach-Object { '    ' + $_ }
}
if ($javacCode -ne 0) { throw 'javac failed' }

$classCount = @(Get-ChildItem $classDir -Recurse -Filter '*.class').Count
Ok ("class files: {0}" -f $classCount)

# ------------------------------------------------------------------ dex
Step 6 'd8 - convert to classes.dex'

$classFiles = @(Get-ChildItem $classDir -Recurse -Filter '*.class' | ForEach-Object { $_.FullName })
$dexDir = Join-Path $Build 'dex'
& $d8 --release --lib $androidJar --min-api 24 --output $dexDir @classFiles
if ($LASTEXITCODE -ne 0) { throw 'd8 failed' }

$dexFile = Join-Path $dexDir 'classes.dex'
if (-not (Test-Path $dexFile)) { throw 'd8 produced no classes.dex' }
Ok ("classes.dex  {0:N0} B" -f (Get-Item $dexFile).Length)

# ------------------------------------------------------------------ assemble
Step 7 'Add classes.dex to the APK'

Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem

$unaligned = Join-Path $Build 'unaligned.apk'
Copy-Item $baseApk $unaligned -Force

$zip = [System.IO.Compression.ZipFile]::Open($unaligned, 'Update')
try {
    @($zip.Entries | Where-Object { $_.FullName -eq 'classes.dex' }) | ForEach-Object { $_.Delete() }
    [void][System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
        $zip, $dexFile, 'classes.dex',
        [System.IO.Compression.CompressionLevel]::Optimal)
} finally {
    $zip.Dispose()
}
Ok ("unaligned.apk  {0:N0} B" -f (Get-Item $unaligned).Length)

Step 8 'zipalign - 4 byte alignment'
$aligned = Join-Path $Build 'aligned.apk'
& $zipalign -f 4 $unaligned $aligned
if ($LASTEXITCODE -ne 0) { throw 'zipalign failed' }
Ok ("aligned.apk  {0:N0} B" -f (Get-Item $aligned).Length)

# ------------------------------------------------------------------ sign
Step 9 'apksigner - debug key'

# A fixed debug keystore, so reinstalling never trips "signatures do not match".
$ks = Join-Path $Root 'debug.keystore'
if (-not (Test-Path $ks)) {
    Write-Host '    generating debug keystore'
    & $keytool -genkeypair -v `
        -keystore $ks `
        -storepass android -keypass android `
        -alias androiddebugkey `
        -keyalg RSA -keysize 2048 -validity 10000 `
        -dname 'CN=Android Debug,O=Android,C=US' 2>&1 | Out-Null
}
Ok 'keystore ready'

$signArgs = @(
    'sign',
    '--ks', $ks,
    '--ks-pass', 'pass:android',
    '--key-pass', 'pass:android',
    '--ks-key-alias', 'androiddebugkey',
    '--min-sdk-version', '24',
    '--out', $Out,
    $aligned
)
& $apksigner @signArgs
if ($LASTEXITCODE -ne 0) { throw 'apksigner failed' }

Step 10 'Verify signature'
& $apksigner verify --print-certs $Out 2>&1 | Select-Object -First 4 | ForEach-Object { '    ' + $_ }

$size = (Get-Item $Out).Length
Write-Host ''
Write-Host ('=' * 64) -ForegroundColor Green
Write-Host ("  BUILD OK  ->  $Out") -ForegroundColor Green
Write-Host ("  size {0:N0} B ({1:N2} MB)" -f $size, ($size / 1MB)) -ForegroundColor Green
Write-Host ('=' * 64) -ForegroundColor Green

# ------------------------------------------------------------------ install
if ($Install) {
    Step 11 'Install on device'
    # Resolve adb: -Adb parameter, else $env:ADB, else PATH.
    if (-not $Adb) { $Adb = $env:ADB }
    if (-not $Adb) {
        $cmd = Get-Command adb -ErrorAction SilentlyContinue
        if ($cmd) { $Adb = $cmd.Source }
    }
    if (-not $Adb) {
        throw 'adb not found. Pass -Adb <path>, set $env:ADB, or put adb on PATH.'
    }
    if (-not (Test-Path $Adb)) { throw "adb not found: $Adb" }
    & $Adb install -r $Out 2>&1 | ForEach-Object { '    ' + $_ }
    if ($LASTEXITCODE -ne 0) { throw 'adb install failed' }
    Ok 'installed'
}
