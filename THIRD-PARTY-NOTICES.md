# 第三方素材与许可

这个仓库包含一份来自第三方的文件。**它不在版本库里**（见 `.gitignore`），
需要你自己从本机安装的插件里取用。这里说明它的来源与许可。

## app/src/main/assets/splash.html

| | |
|---|---|
| 来源 | `dsh-opening-splash` 插件（v1.0.3）自带的 `assets/splash.html` |
| 许可 | MIT |
| 为什么不由本仓库分发 | 它是那个插件的素材，不是本项目的文件。你自己取用时，拿到的会是你实际安装的那个版本 |
| 本仓库对它做了什么 | 什么都不做。`rewire-splash.mjs` 在构建时把**副本**改成适配手机竖屏（见 README「开机动画」一节），原始文件不受影响 |

如果要在这个仓库里重新分发它，保留上面的来源与许可说明即可 —— MIT 允许再分发，
只要求保留版权声明。

## 图标

`app/src/main/res/mipmap-*/ic_launcher.png` 是**本项目原创**的几何图形
（深色圆底 + 鲸鱼剪影），不派生自任何既有形象，随本仓库以 MIT 分发。

## DSH 本体

本仓库**不包含** DeepSeek Harness 的任何代码。壳只是把跑在 Termux 里的 DSH
显示出来；DSH 本身需要你自行获取与安装。「DeepSeek」「DeepSeek Harness」
等名称与标识归其权利人所有，本项目与官方没有关系。
