package com.dsh.mobile;

import android.content.Context;
import android.util.AttributeSet;
import android.webkit.WebView;

/**
 * 主界面的 WebView。
 *
 * ── 曾经的用途，以及为什么取消了 ──────────────────────────────────────
 *
 * 这里原先挂着一个「长按打开开机动画开关」的手势。存在的理由是：那个开关最初
 * 只挂在动画自己身上，关掉之后就再没有入口能打开（真机测试时暴露的缺陷），
 * 所以需要一个「动画不播放时也能进」的入口。
 *
 * 但把长按挂在**整个界面**上代价太大：用户在任何一个页面按住屏幕都会弹出
 * 设置对话框 —— 下拉列表、选中文本、按住看图片，全都可能误触。
 *
 * 现在开关有了正式的家：外壳自己的设置页（MobileSettingsActivity），
 * 入口是侧栏底部那个按钮（由 MobileSkin.SETTINGS_ENTRY 注入）。
 * 于是长按手势整个去掉，这里只剩一个普通的 WebView。
 *
 * ── 为什么这个类还留着 ────────────────────────────────────────────────
 *
 * 它是 MainActivity 里 `new DshWebView(this)` 的类型，删掉要连着改调用点；
 * 更重要的是：外壳层面以后还会有别的「需要 WebView 子类才能做的事」
 * （比如拦下载、自定义滚动），留一个明确的落点比每次回来改 MainActivity 好。
 */
public class DshWebView extends WebView {

    public DshWebView(Context context) {
        super(context);
    }

    /**
     * 构建工具可能会用这个构造函数（从 XML 实例化时）。
     * 本工程不写布局 XML，但少一个构造函数会让将来加布局这件事莫名其妙地失败。
     */
    public DshWebView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public DshWebView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }
}
