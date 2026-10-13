# DSH for Android

把跑在 Termux 里的 **DeepSeek Harness** 包成一个正常的安卓 App —— 一个 WebView 外壳，
没有地址栏、不是浏览器标签页、在后台任务里是一个独立的图标。

```
  桌面图标          点开                后台
  ┌──────┐      ┌──────────────┐    ┌──────────────┐
  │  🐋  │  →   │  DSH 全屏界面 │    │  独立的 App  │
  │ DSH  │      │  没有地址栏   │    │  不是浏览器页 │
  └──────┘      └──────────────┘    └──────────────┘
```

约 2100 行 Java，六个文件，**没有任何第三方依赖**（刻意避开 AndroidX），
用一条手写的构建流水线打包出约 93 KB 的 APK。

---

## 它解决什么、不解决什么

**解决的**：DSH 自带 Web UI，但那是为宽屏设计的。在手机上直接打开，得到的是一个
浏览器标签页 —— 有地址栏和工具栏，在后台和几十个网页混在一起。而且那个界面在
360px 宽度下是**不能用的**（见下面的「手机端适配」）。

**不解决的**：DSH 本体。这个壳**不含运行时**，它只是把 Termux 里已经在跑的 DSH
显示出来。**DSH 必须先起来**，否则壳会显示一个带「重试」和「打开 Termux」的提示页。

### 为什么不用 PWA

本来可以给 DSH 加 manifest + Service Worker 让它「可安装」，这事也做了
（`dsh-pwa`）。但**这台设备上的 Edge 是厂商定制版，菜单里既没有「安装应用」
也没有「添加到主屏幕」** —— 入口根本不存在。

WebView 壳不依赖任何浏览器的配合，所以它是这里唯一走得通的路。

---

## 手机端适配：这是这个壳的主要工作量

DSH 的界面是**桌面前提**的：主布局 `AppFrame` 连一条窄屏媒体查询都没有。
所以适配不是「调几个像素」，而是要理解它原本的假设，然后在壳这一侧补上。
下面每一条都是真机实测出来的，注释都留在代码里。

### 1. 不能左右滑动

| 措施 | 为什么 |
|---|---|
| `setUseWideViewPort(true)` | **最关键的一条**。不开它，WebView 按 980px 视口排版再整体缩放 —— 又糊又要横向拖 |
| `setLoadWithOverviewMode(false)` | 不要为了「塞进屏幕」而缩放，那会让字体发虚 |
| `setSupportZoom(false)` 等 | 关掉缩放与双指手势，手机上误触比缩放本身更烦 |
| 注入 viewport + `overflow-x:hidden` | 兜底：把页面 viewport 钉死在设备宽度并裁掉横向溢出 |

注入的 CSS 刻意**没有**用 `*{max-width:100vw}` —— 它会连带把下拉菜单和悬浮层一起压扁。

### 2. 侧栏：从「常驻窄轨道」改成「手机式抽屉」

DSH 的「收起侧栏」是**桌面语义**：变成 56px 的图标轨道，依然静态占着 56px。
手机上这 56px 很贵。现在的行为：

| 状态 | 表现 |
|---|---|
| 平时 | 正文满宽（360px），左上角只有一个鲸鱼图标 |
| 点图标 | 310px 抽屉盖在内容上，背景压暗 |
| 点抽屉外面 | 收回，回到会话页满宽 |
| 按返回键 | 同上 |

**踩过的两个坑**（都写进注释了）：

- **不要跟 DSH 抢尺寸。** 第一版用 `!important` 写死了 `sidebarCol` 的宽度，
  结果把它的展开态一起按死了 —— 点开侧栏在视觉上完全没反应。
  现在只把「栅格第一列占多宽」归零，宽度计算还给 DSH。
- **不要用 `*:not(...){display:none}` 一刀切隐藏图标。** 那会把按钮**内部的
  svg 也隐藏掉**（实测变成 0×0，按钮成了个空圆）。要用 `:has()` 保留祖先链，
  只隐藏祖先的其它子节点。

### 3. 顶部 / 底部让位

壳是全屏的（`FLAG_LAYOUT_NO_LIMITS`），内容会画到状态栏和导航栏底下。
**不要写死像素** —— 第一版写 `padding-top:30px`，而真实状态栏是 40px（4 倍密度屏），
少了 10px 却因为「看着差不多」一直没被发现。现在把 `WindowInsets` 的真实值推给网页：

```
--dsh-safe-top     状态栏高度（CSS px）
--dsh-safe-bottom  导航栏高度（CSS px）
--dsh-inset-top-px 原始物理像素（仅排查用）
```

注意**单位**：`WindowInsets` 给的是物理像素，CSS 的 px 是逻辑像素，要除以
`devicePixelRatio`。第一版忘了除，顶部于是让位了 160 个 CSS px。

### 4. 键盘遮挡（这个坑最深）

有**两层**原因，第一版只处理了第一层：

1. Android 15+ 忽略 `android:windowSoftInputMode="adjustResize"`（强制 edge-to-edge）；
2. **`FLAG_LAYOUT_NO_LIMITS` 让窗口根本不缩** —— 这个标志的语义是「允许窗口超出屏幕
   且不随系统栏变化调整大小」，它把 `adjustResize` 彻底作废了。

第一版只在网页里用 `visualViewport` 做补偿，属于「窗口不变、让页面自己让位」——
是在给一个不该存在的问题打补丁。正确做法是读 `WindowInsets.Type.ime()`，
给 WebView 加等值的 `bottomMargin`，**让它真的被缩小**。页面自己的 flex 布局
就把输入框停在该在的位置了。

顺带还做了：键盘弹起时把对话滚到最新一条，打字期间保持贴底。

### 5. 返回键

按「由内到外」顺序，最后要连按两次才离开：

```
关浮层 → 收抽屉 → 退浏览器历史 → 第一次提示「再按一次退出」→ 两秒内再按才退到桌面
```

用 `moveTaskToBack` 而不是 `finish`：回桌面但保留现场，再点图标回来还是原来的会话。

两个坑：DSH 的设置面板与侧栏抽屉**都不进浏览器历史**，只判 `canGoBack()` 会让它们
没有出口；而 `evaluateJavascript` 是**异步**的，返回键等不起 —— 所以状态必须事先
算好挂在 `window.__dshOverlayOpen` / `window.__dshNavOpen` 上同步读取。
另外它的返回值是 JSON，字符串会带引号，拿 `"true".equals(v)` 比会永远为假。

### 6. 鉴权：为什么只需引导一次

| | 寿命 | 作用 |
|---|---|---|
| URL 上的 `?token=xxx` | **每次启动都变** | 一次性引导票据 |
| 换来的 Cookie | **30 天**，用持久化密钥签名 | 真正的会话凭证 |

所以壳的逻辑是：**用带 token 的地址引导一次，把 Cookie 落进 WebView 的 jar，
之后一直用不带 token 的裸地址。** 它记下的是**裸地址**，不是 token ——
token 存了下次也没用，反而多留一份凭证。

引导入口：

```bash
adb shell am start -a android.intent.action.VIEW \
  -d "http://127.0.0.1:8080/?token=xxxx" \
  -n com.dsh.mobile/.MainActivity
```

不带参数启动时，走 SharedPreferences 里存的裸地址。

---

## 手机端设置页

侧栏底部有一个「手机端设置」入口（手机图标），打开的是**壳自己的原生设置页**：

- **开机动画**开关（见下）
- **DSH 设置**入口 —— 壳的设置页无法嵌入 DSH 的网页设置，所以这一行负责把你送回去

> 侧栏底部原本有两个按钮（壳设置 + DSH 设置），而它们在 rail 布局里处在**同一个
> flex 行、只有 36px 宽**，两个按钮完全重叠在同一个位置。现在改成纵向两格。

---

## 开机动画

壳可以盖一层开机动画，素材来自 `dsh-opening-splash` 插件。

**`app/src/main/assets/splash.html` 不在这个仓库里** —— 它是那个插件的素材
（MIT），由使用者自行放入（见 `THIRD-PARTY-NOTICES.md`）：

```bash
# 从你安装的插件里复制
cp <插件目录>/assets/splash.html app/src/main/assets/splash.html
```

没有它也能构建，只是没有开机动画（构建脚本会给出提示）。

`rewire-splash.mjs` 在构建时把**副本**改成适配手机竖屏，原始插件文件不受影响。
它做四件事，每件都对应一个真机问题：

1. **完成信号改走原生桥。** 动画原本派发 `dsh:splash-complete` 事件，但覆盖层的
   WebView 不可见时**页面计时器会被冻结**，信号到不了。改成直接调原生方法。
2. **收场改成到点收尾。** 主路径是安卓侧的 `postDelayed`，不依赖页面事件。
3. **画幅按宽度铺满。** 原本是 `Math.min(innerWidth/1920, innerHeight/1080)`
   （contain），在 360×792 上舞台只占中间一条。改成按宽度等比缩放 ——
   **仍然不变形**，空出来的地方用背景渐变补上。
4. **背景接续。** 把 `html/body` 底色换成与舞台同一套径向渐变，消除那条突兀的横带。

跳过动画：**双击**。设置开关：手机图标 → 开机动画。

---

## 构建

**不用 Gradle。** 应用没有任何第三方依赖，Gradle 的依赖解析和 daemon 全是负担。
五条工具就够了：

```
aapt2 compile → aapt2 link → javac → d8 → zipalign → apksigner
```

```powershell
# 需要：JDK 17+、build-tools、platform（含 android.jar），布局见下
powershell -ExecutionPolicy Bypass -File build.ps1
powershell -ExecutionPolicy Bypass -File build.ps1 -Install          # 顺带装到手机
powershell -ExecutionPolicy Bypass -File build.ps1 -Install -Adb C:\path\to\adb.exe
```

产出 `DSH.apk`（约 93 KB）。`-Install` 找不到 adb 时会依次尝试 `-Adb` 参数、
`$env:ADB`、PATH，都没有就明确报错。

### 工具链布局

脚本预期 `../toolchain/` 下是这样：

```
toolchain/
├─ jdk/                      解压自 OpenJDK21U-jdk_x64_windows_hotspot_*.zip
├─ bt36-tmp/android-16/      解压自 build-tools_r36.1_windows.zip
└─ plat-tmp/android-35/      解压自 platform-35_r01.zip（取里面的 android.jar）
```

### 构建路上的坑

留在这里，省得下次重踩：

**1. `aapt2 link` 的 `-R` 是「覆盖层」，不是「主资源」。** 编译好的资源要作为
**位置参数**传。用 `-R` 会得到 `resource style/AppTheme does not override an
existing resource` —— 因为底下什么都没有，无从覆盖。

**2. `$ErrorActionPreference = 'Stop'` 会和原生命令打架。** 所有原生工具都会往
stderr 写进度，`Stop` 会把第一条提升成终止错误 —— javac 一句无害的「使用了过时的
API」就能中断整个构建。用 `Continue`，然后逐个检查 `$LASTEXITCODE`。

**3. `d8.bat` / `apksigner.bat` 是批处理包装，靠 `JAVA_HOME` 找 java。**
所以脚本显式导出 `JAVA_HOME` 和 `PATH`。

**4. build-tools 34 的 d8（8.2.2）读不了这个项目的匿名内部类。** 它在
`MainActivity$1.class` 上抛内部 `NullPointerException`，和字节码目标版本无关。
build-tools 36.1 的 d8（9.0.3）正常。

**5. `Sort-Object -Descending` 是区域文化敏感的。** 在本机 locale 下它把
`bt-tmp` 排在了 `bt36-tmp` 前面 —— 「选最新的 build-tools」这个意图被静默做反了。
改成按 `android-NN` 里的数字排序。

**6. `build.ps1` 必须保持 ASCII-only。** PowerShell 5.1 会把无 BOM 的 `.ps1`
按 ANSI 读取，非 ASCII 注释会**静默破坏脚本解析** —— 构建照常成功，但那段代码
根本不执行。

---

## 代码结构：为什么样式适配长成那样

`MobileSkin.java`（925 行）是最大的一块，它不写业务逻辑，只做「注入」。
每个注入脚本都是一个 Java 字符串常量：

| 常量 | 作用 |
|---|---|
| `PROBE` | 只读测量，把真实排版打到 logcat（`TAG=DSHProbe`） |
| `PATCH` | 常驻样式补丁（宽度、栅格、设置面板全屏化…） |
| `KEYBOARD` | 键盘遮挡补偿 + 对话贴底 |
| `SIDEBAR` | 侧栏抽屉 + 点外部收回 |
| `SETTINGS_ENTRY` | 侧栏底部的「手机端设置」入口 |
| `OPEN_WEB_SETTINGS` | 打开 DSH 自己的设置浮层 |
| `CLOSE_NAV` | 收起侧栏（供返回键调用） |
| `OVERLAY` / `DISMISS` | 浮层探测与关闭（供返回键调用） |

### 两个「构建期发现不了、运行时静默失效」的陷阱

这两个都踩过，而且**排查成本极高**，写在这里：

**① 常量里只能有字符串拼接。** 如果在 `PATCH`/`SIDEBAR` 这类常量中间插入
Java 注释，那些文字**不会进 JS**，剩下的 JS 就成了裸文本 → 整个 IIFE 解析失败 →
**脚本完全不执行**。而 javac 只检查 Java 语法，**`BUILD OK` 照常打印**。
真机表现不是报错，而是「布局没变」——极易误判成 CSS 写错了。

**② `onPageFinished` 时 SPA 还没渲染出目标节点。** 脚本注入那一刻
`document.querySelector('[class*=frame]')` 返回 null，早期版本直接 `return`，
之后再无重试。修法是轻量轮询（300–400ms，上限约 50 次）。

### 事后自查

建议配一个语法检查脚本：把每个 Java 字符串常量还原成 JS 再 `node --check`。
要点是**逐字符**读 Java 字符串字面量（正确处理 `\"` 与 `\\`）——
不能用 `/"(.*)"/` 这种简单正则，它遇到含转义引号的常量会切错位置，产出假的语法错误。

---

## 前置条件

**DSH 必须先在 Termux 里跑起来**：

```bash
cd ~/deepseek-harness-master
node --expose-internals --import tsx/esm apps/cli/src/bin.ts web --port 8080 --no-open
```

`--expose-internals` 是**必需**的：DSH 的模块解析要读 Node 内部模块，而
`node-addon-require-builtin` 没有 android-arm64 的预编译包。相关的兼容补丁
（flock、硬链接回退、纯 JS 替身）见
[dsh-termux-patches](https://github.com/jinyuxiang-sudoqq/dsh-termux-patches)。

---

## 文件

```
dsh-android-shell/
├─ build.ps1                        手工构建流水线（ASCII-only，别加中文注释）
├─ rewire-splash.mjs                开机动画补丁（改副本，不动原始素材）
├─ app/src/main/
│  ├─ AndroidManifest.xml           只要 INTERNET 权限
│  ├─ assets/splash.html            开机动画（不在仓库里，自行放入）
│  ├─ java/com/dsh/mobile/
│  │  ├─ MainActivity.java          壳：WebView 配置、insets、返回键、鉴权、错误页
│  │  ├─ MobileSkin.java            注入脚本集合（手机端适配都在这里）
│  │  ├─ SplashView.java            开机动画覆盖层（含双击跳过、长按设置）
│  │  ├─ SplashSettings.java        开机动画开关的读写
│  │  ├─ MobileSettingsActivity.java 壳自己的设置页
│  │  └─ DshWebView.java            WebView 子类（预留落点，当前无额外行为）
│  └─ res/
│     ├─ mipmap-*/ic_launcher.png   五个密度的图标（本项目原创）
│     ├─ values/styles.xml          深色无标题栏主题
│     ├─ values/strings.xml
│     └─ xml/network_security_config.xml  明文 HTTP 只对回环放行
└─ build/                           中间产物（不进版本库）
```

---

## 安全

- **只要 `INTERNET` 权限**，没有别的。
- **明文 HTTP 只对 `127.0.0.1` / `localhost` / `::1` 放行**。刻意不用
  `android:usesCleartextTraffic="true"` —— 那等于全网络放行明文，而这里只需要
  跟本机的一个回环服务说话。
- **禁用了 `file://` 与 `content://` 访问**，收紧攻击面。
- **外部链接交给系统浏览器**，不把任意网页塞进这个壳。只有回环地址才留在壳里。
- **网页 → 原生的桥只暴露一个方法**：打开壳设置页。无参数、无返回值，
  页面就算完全失控也做不了别的事。不要往里加接收参数的方法。
- DSH 本身只监听回环，局域网接口上打不通。

---

## 已知限制

- **只在 Android 16 / aarch64 / 一台 4 倍密度屏的机器上验证过。**
- 页面里的注入脚本**依赖 DSH 的 DOM 结构**（类名后缀如 `frame` / `sidebarCol` /
  `scrollBody`）。DSH 前端改结构时，最坏情况是「样式不生效」——
  所有脚本都是防御式的（全 `try/catch` + 空值判断），不会白屏。
- 侧栏抽屉的「点外部收回」靠一个全屏遮罩层实现，它只在抽屉展开时存在。

---

## 许可

MIT。第三方素材见 `THIRD-PARTY-NOTICES.md`。
本项目与 DeepSeek 官方没有关系。
