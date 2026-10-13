// 给开机动画打补丁 —— 都为了让它在安卓壳里「铺满、可靠收场」。
//
// 背景：splash.html 是桌面 dsh-opening-splash 插件的资产，原样适合 16:9 桌面窗口，
// 直接搬到竖屏手机上会有三个问题，这个脚本就是逐条修掉它们。
//
//   一、完成信号：动画播完会派发 dsh:splash-complete，安卓壳监听它来收场。
//       实测信号到不了（覆盖层不可见时页面计时器被冻结，见 SplashView 注释）。
//       这里把派发改成直呼原生桥 —— 不修事件时序，而是换一条不依赖它的路。
//
//   二、手机端画幅：原逻辑 Math.min(innerWidth/1920, innerHeight/1080) 是
//       desktop 的 contain，在 360x792 的手机上舞台只占中间 360x202，上下全是黑边。
//       改成按宽度铺满（innerWidth / 1920，仍然等比，**不变形**），
//       空出来的地方由补丁三补上背景。
//
//   三、背景接续：把 html/body 的底色换成与 #stage **同一套色标、同一几何**的
//       径向渐变（--paper-hi #efeeea / --paper #e6e4e0 / --paper-lo #d8d5d0，
//       位置同为 46% 34%），并把 #splash 自身底色改为透明。
//       这样舞台那一层叠上去时上下无接缝，原来那条突兀的横带消失。
//
// 为什么不做裁切式「放大铺满」：这个动画的构图横跨整个 16:9 画幅（左上标题、
// 右侧状态文字、右下署名都在最外侧），任何 cover 裁切都会砍掉内容 ——
// 实测按高度铺满会把中央鲸鱼切到只剩尾巴。铺满的应当是画面，不是内容本身。
//
// 为什么要动上游文件：这是唯一能改动画内部缩放与配色的地方。改动只落在副本上
// （app/src/main/assets/splash.html），原始插件文件保持不动。
//
// 为什么是 Node 脚本而不是写在 build.ps1 里：PowerShell 单双引号与转义在这种
// 「把一段 JS/CSS 替换成另一段」的场景里极易出错（第一版写了却静默没生效），
// 而且 build.ps1 必须保持 ASCII-only。Node 里这就是普通的字符串替换，
// 能单独运行、单独验证。
//
// 用法：node rewire-splash.mjs <splash.html 路径>
//
// 幂等：靠每个补丁自己的 verify 判断，不靠「标记串是否出现」——
// 标记串有可能在原文里本来就存在（补丁三踩过这个坑，导致误判为已打过）。
// 找不到锚点则退出码非 0：宁可构建失败，也不要静默产出一个画幅错乱的启动页。

import { readFileSync, writeFileSync } from 'node:fs'

const COMPLETION_NEEDLE =
  "dispatchEvent(new CustomEvent('dsh:splash-complete', { detail: { reason } }));"
const COMPLETION_PATCH =
  "try{ window.DSHSplash && window.DSHSplash.done(String(reason||'complete')); }catch(e){}"

const DEADLINE_NEEDLE = 'window.__splashReady = true;'
const DEADLINE_PATCH =
  'window.__splashReady = true;\n'
  + '/* DSH android shell: hand off on a deterministic deadline, not on an event. */\n'
  + 'try {\n'
  + '  var __dshDur = (window.DSHSplash && window.DSHSplash.duration) || 27250;\n'
  + '  setTimeout(function () {\n'
  + '    try { window.DSHSplash && window.DSHSplash.skip(); } catch (e) {}\n'
  + '  }, __dshDur + 300);\n'
  + '} catch (e) {}\n'

const FIT_NEEDLE = 'Math.min(innerWidth / 1920, innerHeight / 1080)'
const FIT_PATCH = 'innerWidth / 1920'

/*
 * body 自身的底色。
 *
 * 原文件里 body 有一条 `background: #0b0b0b`，而且它排在（我们注入的）
 * html,body 规则之后 —— 同优先级下后者生效，所以黑底会把渐变整个盖掉。
 * 这里直接改掉这一条，顺带把渐变存成自定义属性，避免三处重复写字面量。
 */
const BODY_BG_NEEDLE = /body \{\r?\n    width: 100%; height: 100%;\r?\n    background: #0b0b0b;/
const BODY_BG_PATCH =
  'body {\r\n'
  + '    /* DSH android shell: the stage cannot fill a tall phone by itself, so the */\r\n'
  + '    /* page background becomes the same gradient as #stage (same stops and     */\r\n'
  + '    /* geometry), letting the letterbox area blend into the picture.           */\r\n'
  + '    --dsh-stage-bg: radial-gradient(115% 95% at 46% 34%,'
  + ' #efeeea 0%, #e6e4e0 46%, #d8d5d0 100%);\r\n'
  + '    width: 100%; height: 100%;\r\n'
  + '    background: var(--dsh-stage-bg);'

// 跨行锚点必须容忍 CRLF（这一版踩过一次：写死 '\n' 匹配不到 CRLF 文件）。
//
// 注入位置也要挑对：#splash 规则里原本还有一行 `background: #0b0b0b`，
// 排在 `position: fixed; inset: 0;` **之后** —— 如果只在那里插 transparent，
// 会被后面那行同优先级的声明盖掉（第二次踩的坑）。所以锚点取整条规则，
// 连同它那行黑底一起替换掉。
const BACKDROP_NEEDLE = /#splash \{\r?\n(?:[^\n]*\r?\n)*?\s*\}\r?\n/
const BACKDROP_PATCH =
  '/* DSH android shell: the animation is a 1920x1080 stage scaled to the device    */\r\n'
  + '  /* width, so on a tall phone it cannot cover the viewport by itself. Paint the  */\r\n'
  + '  /* uncovered area with the stage\'s own gradient (same stops, same geometry), so */\r\n'
  + '  /* the layer above blends into it instead of showing as a band.                 */\r\n'
  + '  #splash {\r\n'
  + '    position: fixed; inset: 0;\r\n'
  + '    display: grid; place-items: center;\r\n'
  + '    cursor: default;\r\n'
  + '  }\r\n'

const PATCHES = [
  {
    name: '完成信号接到原生桥',
    needle: COMPLETION_NEEDLE,
    patch: COMPLETION_PATCH,
    isApplied: (h) => h.includes('DSHSplash.done'),
  },
  {
    name: '到点收尾的定时器',
    needle: DEADLINE_NEEDLE,
    patch: DEADLINE_PATCH,
    isApplied: (h) => h.includes('__dshDur'),
  },
  {
    name: '手机端画幅按宽度铺满',
    needle: FIT_NEEDLE,
    patch: FIT_PATCH,
    // 注意：不能拿 'innerWidth / 1920' 当标记 —— 它本来就在原文里（Math.min 的参数）。
    // 判据必须是「已经不再用 min 了」。
    isApplied: (h) => !h.includes('Math.min(innerWidth'),
  },
  {
    name: '全屏背景渐变接续',
    needle: BACKDROP_NEEDLE,
    patch: BACKDROP_PATCH,
    isApplied: (h) => h.includes('blends into it instead of showing as a band'),
  },
  {
    name: 'body 底色换成同一渐变',
    needle: BODY_BG_NEEDLE,
    patch: BODY_BG_PATCH,
    isApplied: (h) => h.includes('--dsh-stage-bg'),
  },
]

const target = process.argv[2]
if (!target) {
  console.error('用法: node rewire-splash.mjs <splash.html 路径>')
  process.exit(2)
}

let html = readFileSync(target, 'utf8')
const applied = []

/** 锚点可以是字符串或正则（跨行锚点用正则，以容忍 CRLF）。 */
const hasAnchor = (h, needle) =>
  needle instanceof RegExp ? needle.test(h) : h.includes(needle)

for (const p of PATCHES) {
  if (p.isApplied(html)) {
    console.log(`已打过：${p.name}`)
    continue
  }
  if (!hasAnchor(html, p.needle)) {
    console.error(`未找到锚点，上游 splash.html 可能已改动 —— 需要重新对齐：${p.name}`)
    console.error(`  锚点: ${p.needle}`)
    process.exit(1)
  }
  // 函数式替换：patch 里含 $ 等字符时，字符串形式的 replace 会把
  // $& / $1 之类当成替换模式解释掉。
  html = html.replace(p.needle, () => p.patch)
  applied.push(p.name)
}

if (applied.length > 0) {
  writeFileSync(target, html, 'utf8')
  // 回读校验：写失败、编码出错或替换没落地时都不能静默通过。
  const after = readFileSync(target, 'utf8')
  const missing = PATCHES.filter((p) => !p.isApplied(after))
  if (missing.length > 0) {
    console.error(`回读校验失败：${missing.map((p) => p.name).join('、')}`)
    process.exit(1)
  }
  console.log(`已打补丁：${applied.join('、')}`)
} else {
  console.log('全部补丁都已就位，无需改动')
}
