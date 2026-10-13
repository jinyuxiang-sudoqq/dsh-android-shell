package com.dsh.mobile;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.util.Log;
import android.webkit.ConsoleMessage;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * DSH 的手机外壳：一个全屏 WebView，指向本机的 DSH 服务。
 *
 * 为什么是 WebView 而不是 PWA：
 *   设备上的 Edge 是 ColorOS 定制版，菜单里没有「安装应用」也没有「添加到主屏幕」，
 *   PWA 那条路在这台机器上走不通。WebView 外壳不依赖任何浏览器的支持。
 *
 * 关于「不要左右滑动」：
 *   DSH 的界面是给宽屏设计的。WebView 的默认视口是 980px —— 不显式打开
 *   useWideViewPort 的话，页面会按 980px 排版再整体缩小，于是又糊又要横向拖。
 *   这里做四件事：
 *     1. useWideViewPort(true)       —— 尊重页面自己的 width=device-width
 *     2. loadWithOverviewMode(false) —— 不要为了塞下而整体缩放
 *     3. 关掉缩放与双指手势          —— 手机上不需要，误触反而恼人
 *     4. 注入 CSS 把横向溢出裁掉      —— 兜底，防止个别浮层把页面撑宽
 *
 * 不依赖 AndroidX：构建时无需解析任何依赖，aapt2 + d8 + apksigner 手工打包即可。
 */
public class MainActivity extends Activity {

    /** DSH 只监听回环，所以这里永远是 127.0.0.1。 */
    private static final String DEFAULT_URL = "http://127.0.0.1:8080/";

    private static final String PREFS = "dsh";
    private static final String KEY_URL = "start_url";

    /** 只在本进程内有效：同一个进程里重建 Activity 不重播。 */
    private static boolean splashPlayedThisProcess;

    /**
     * 解析本次要加载的地址。
     *
     * DSH 的鉴权分两层，理解这一点是这里全部逻辑的由来：
     *
     *   · URL 上的 ?token=xxx 是**每次启动都会变**的一次性引导票据；
     *   · 交换成功后 DSH 下发一个 30 天有效、用持久化密钥签名的 Cookie。
     *
     * 所以只要用带 token 的地址引导**一次**，Cookie 就落进 WebView 的 jar，
     * 之后不带 token 的裸地址也能进。这里因此把不含 token 的地址记下来复用，
     * 而不是把 token 存起来 —— token 存了下次也没用，反而多留一份凭证。
     *
     * 引导入口有两个：`am start -d <url>` 的 data，或 `--es url <url>`。
     */
    private String resolveStartUrl() {
        String fromIntent = null;
        Intent intent = getIntent();
        if (intent != null) {
            if (intent.getData() != null) {
                fromIntent = intent.getData().toString();
            } else if (intent.hasExtra("url")) {
                fromIntent = intent.getStringExtra("url");
            }
        }

        android.content.SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        if (fromIntent != null && fromIntent.startsWith("http")) {
            // 记下不带 token 的部分，供以后每次启动使用
            int cut = fromIntent.indexOf('?');
            String bare = cut >= 0 ? fromIntent.substring(0, cut) : fromIntent;
            prefs.edit().putString(KEY_URL, bare).apply();
            return fromIntent;
        }

        String stored = prefs.getString(KEY_URL, null);
        return (stored != null && stored.startsWith("http")) ? stored : DEFAULT_URL;
    }

    /** 连不上时给用户看的地址（不含 token）。 */
    private String currentBareUrl() {
        String stored = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_URL, null);
        return (stored != null && stored.startsWith("http")) ? stored : DEFAULT_URL;
    }

    /**
     * 诊断开关：`adb shell am start -n com.dsh.mobile/.MainActivity --ez inspect true`
     * 会额外跑一遍 MobileSkin.PROBE，把页面真实排版打到 logcat（TAG=DSHProbe）。
     * 平时为 false，不影响正常启动。
     */
    private boolean inspectMode;

    /**
     * 「再按一次退出」的确认窗口。
     *
     * 两秒是主流取值：短到不会让人等，长到手来不及离开返回键。
     */
    private static final long EXIT_CONFIRM_MS = 2000L;

    /** 上一次按返回键的时间戳，用于判断是不是「连按两次」。 */
    private long lastBackPressAt;

    /**
     * 最近一次量到的系统栏高度。
     *
     * 必须记下来：insets 只在窗口变化时回调，而页面会多次加载
     * （SPA 导航、重试）。每次页面就绪都得把这两个值重新推给它，
     * 否则页面拿不到、只能吃 CSS 里的兜底值。
     */
    private int insetTop;
    private int insetBottom;

    private WebView web;
    private LinearLayout errorPanel;
    private TextView errorText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        current = new java.lang.ref.WeakReference<>(this);

        Intent intent = getIntent();
        inspectMode = intent != null && intent.getBooleanExtra("inspect", false);

        /*
         * 诊断模式顺带打开 WebView 远程调试。
         *
         * 为什么需要它：`adb logcat` 只能看到我们主动打的东西，看不到页面内部的
         * 网络请求与报错。打开调试后可以用 DevTools 协议连进来看真实 DOM 与
         * 失败原因（`adb forward tcp:9222 localabstract:webview_devtools_remote_<pid>`
         * 之后访问 http://127.0.0.1:9222/json）。
         * 只在 inspect 模式下开，正常启动不暴露调试端口。
         */
        if (inspectMode) {
            WebView.setWebContentsDebuggingEnabled(true);
        }

        // 全屏：不显示标题栏，内容延伸到状态栏后面。
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#0b0b0d"));

        web = new DshWebView(this);
        configureWebView(web);
        // 侧栏入口按钮在网页里，点它要能打开外壳自己的设置页 —— 所以给页面
        // 挂一个原生桥。名字带前缀避免和 DSH 自己的全局对象撞名。
        web.addJavascriptInterface(new SettingsBridge(), "DSHShell");
        root.addView(web, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        errorPanel = buildErrorPanel();
        errorPanel.setVisibility(View.GONE);
        root.addView(errorPanel, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(root);
        web.loadUrl(resolveStartUrl());

        installInsetBridge(root);

        maybeShowSplash(root, savedInstanceState);
    }

    /**
     * 把状态栏 / 导航栏的真实高度交给网页。
     *
     * 为什么需要：壳是全屏（FLAG_LAYOUT_NO_LIMITS），页面内容会画到状态栏底下，
     * 侧栏最上面的图标正好和系统时钟叠在同一条线上；底部同理，导航栏会压住输入框。
     * 之前是在 CSS 里写死 padding-top:30px 让位 —— 那是照着一台机器量的数，
     * 换设备、换字号、横屏都不对，而且状态栏高度本来就只有系统知道。
     *
     * 做法：把真实高度写成 CSS 变量，页面自己按需引用：
     *   --dsh-safe-top    状态栏高度（顶部让位）
     *   --dsh-safe-bottom 导航栏高度（底部让位）
     *
     * 用 WindowInsets 而不是读资源里的状态栏高度：insets 是「本窗口实际被系统
     * 占用多少」，刘海、手势条、横屏都算得对，资源值只反映静态配置。
     *
     * 踩过的坑：第一版只在 onCreate 里推送一次，实测两个变量都是空的 ——
     * 那一刻 WebView 还在加载初始 about:blank，脚本注进去随即被导航冲掉。
     * 所以这里把值记下来，除了变化时推，**每次 onPageFinished 也要重推一次**。
     */
    private void installInsetBridge(View root) {
        /*
         * ── 键盘为什么一直解决不好：这一层才是真正的拦路虎 ──────────────
         *
         * 壳用了 FLAG_LAYOUT_NO_LIMITS（见 onCreate）。它的官方语义是
         * 「允许窗口超出屏幕、且**不随系统栏变化调整大小**」——
         * 也就是它把 adjustResize 彻底作废了。
         *
         * 所以键盘遮挡其实有**两层**原因，我先前只处理了第一层：
         *   1. Android 15+ 忽略 windowSoftInputMode="adjustResize"（强制 edge-to-edge）
         *   2. FLAG_LAYOUT_NO_LIMITS 让窗口根本不因 IME 而尺寸变化 ← 更硬的一层
         *
         * 之前只在网页里用 visualViewport 做补偿，那是「窗口不变、页面自己让位」，
         * 属于绕路。正确做法是**让 WebView 真的被缩小**：窗口不变没关系，
         * 我们直接把 WebView 的高度减掉键盘那一段，页面布局自然跟着缩，
         * 输入框自动停在键盘上方，也不需要任何 visualViewport 花招。
         *
         * 保留 FLAG_LAYOUT_NO_LIMITS 不动：开机动画需要内容铺到状态栏底下，
         * 去掉它会连带影响那个已经调好的效果。这里只补键盘这一段。
         */
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int ime = 0;
            try {
                ime = insets.getInsets(WindowInsets.Type.ime()).bottom;
                insetTop = insets.getInsets(WindowInsets.Type.statusBars()).top;
                insetBottom = insets.getInsets(WindowInsets.Type.navigationBars()).bottom;
            } catch (Throwable ignored) { }

            applyImeInset(ime);
            pushInsets();
            return insets;
        });
        root.requestApplyInsets();
    }

    /**
     * 按键盘高度收窄 WebView。
     *
     * 用 bottomMargin 而不是直接改高度：WebView 由 FrameLayout 以 MATCH_PARENT
     * 承载，加下边距就等价于「可用高度减掉键盘」，而且布局参数是幂等的 ——
     * 重复设同一个值不会触发无谓的重排。
     */
    private void applyImeInset(int imePx) {
        if (web == null) return;
        ViewGroup.LayoutParams lp = web.getLayoutParams();
        if (!(lp instanceof ViewGroup.MarginLayoutParams)) return;
        ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) lp;
        if (mlp.bottomMargin == imePx) return;
        mlp.bottomMargin = imePx;
        web.setLayoutParams(mlp);
        Log.i("DSHKeyboard", "ime inset -> bottomMargin " + imePx + "px");
    }

    /**
     * 把最近一次量到的 insets 推给当前页面。
     *
     * ── 单位陷阱（这个 bug 值得留档）────────────────────────────────────
     *
     * WindowInsets 给的是**物理像素**，而 CSS 的 px 是**逻辑像素**。
     * 第一版直接把 insetTop 拼成 "160px" 写进 CSS —— 结果四倍密度屏上
     * 顶部真的让位了 160 个 CSS px（= 640 物理像素），比需要的一整块还高，
     * 看起来就是「顶部莫名空出一大截」。
     *
     * 正确做法是除以 density。放在页面侧做（用 window.devicePixelRatio）
     * 而不是在 Java 里除：这样页面自己就能把「物理像素」换算成它要用的单位，
     * 前端换缩放、改 viewport 设置时也不会错位。
     */
    private void pushInsets() {
        if (web == null) return;
        // 传原始物理像素；换算交给页面（见上面注释）。
        final String js = "(function(){try{"
            + "var r=document.documentElement.style;"
            + "r.setProperty('--dsh-inset-top-px','" + insetTop + "px');"
            + "r.setProperty('--dsh-inset-bottom-px','" + insetBottom + "px');"
            + "var d=window.devicePixelRatio||1;"
            + "r.setProperty('--dsh-safe-top',(" + insetTop + "/d).toFixed(2)+'px');"
            + "r.setProperty('--dsh-safe-bottom',(" + insetBottom + "/d).toFixed(2)+'px');"
            + "}catch(e){}})()";
        web.post(() -> {
            try { web.evaluateJavascript(js, null); } catch (Throwable ignored) { }
        });
    }

    /**
     * 冷启动时盖上开机动画。
     *
     * 两个条件下才播：
     *   · 不是因配置变更/被系统回收后重建（savedInstanceState == null），
     *     否则转屏也会重播一遍；
     *   · 用户没有关掉它（开关见 SplashSettings；长按动画或主界面都能打开）。
     *     每次冷启动都播 —— 与电脑端 oncePerSession: false 一致。
     *
     * 注意加载顺序：动画覆盖层是在 web.loadUrl() 之后才加进视图树的，
     * 底下的 DSH 页面因此已经在后台开始加载 —— 动画放完时界面通常已经就绪，
     * 淡出后直接可用。这是桌面端「动画期间应用在后台启动」的同一套时序。
     */
    private void maybeShowSplash(FrameLayout root, Bundle savedInstanceState) {
        if (savedInstanceState != null) return;
        if (splashPlayedThisProcess) return;
        if (!SplashSettings.isEnabled(this)) return;

        splashPlayedThisProcess = true;
        // 回调留空：动画结束后没有任何事要做 —— 覆盖层自己会淡出并摘除。
        SplashView splash = new SplashView(this, () -> { });
        root.addView(splash, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    /** WebView 的全部设置都集中在这里，方便对照上面注释里的四条。 */
    private void configureWebView(WebView view) {
        WebSettings s = view.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);          // DSH 的界面依赖 localStorage

        // ① 尊重页面自己的 width=device-width，而不是按 980px 排版
        s.setUseWideViewPort(true);
        // ② 不要为了「塞进屏幕」而整体缩放
        s.setLoadWithOverviewMode(false);

        // ③ 关掉缩放：手机上双指误触比缩放本身更烦
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setTextZoom(100);                    // 不跟随系统字体缩放，避免布局被撑破

        s.setAllowFileAccess(false);           // 不给 file:// 访问，收紧攻击面
        s.setAllowContentAccess(false);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);

        view.setBackgroundColor(Color.parseColor("#0b0b0d"));
        view.setOverScrollMode(View.OVER_SCROLL_NEVER);
        view.setHorizontalScrollBarEnabled(false);   // 横向滚动条永不出现
        view.setVerticalScrollBarEnabled(false);

        view.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest request) {
                Uri u = request.getUrl();
                String host = u.getHost();
                // 回环地址留在壳里；别的链接交给系统浏览器，别把外部页面塞进这个壳
                if (host != null && (host.equals("127.0.0.1") || host.equals("localhost"))) {
                    return false;
                }
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (ActivityNotFoundException ignored) { }
                return true;
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                v.evaluateJavascript(MobileSkin.PATCH, null);
                // 常驻监测「有没有浮层开着」，供返回键同步读取。
                v.evaluateJavascript(MobileSkin.OVERLAY, null);
                // 键盘遮挡与侧栏抽屉都需要在页面就绪后常驻，
                // 而且 SPA 每次导航都会重新触发 onPageFinished，
                // 两段脚本内部各自做了幂等判断，重复注入不会叠加监听器。
                v.evaluateJavascript(MobileSkin.KEYBOARD, null);
                v.evaluateJavascript(MobileSkin.SIDEBAR, null);
                // 侧栏底部的「手机端设置」入口（开机动画开关的家）。
                v.evaluateJavascript(MobileSkin.SETTINGS_ENTRY, null);
                // 系统栏高度必须在页面就绪后重推：insets 回调只在窗口变化时触发，
                // 而那可能早于本次页面加载，值会被导航冲掉。
                pushInsets();
                if (inspectMode) {
                    // SPA 首帧之后还会再渲染几轮，等一拍再量，否则量到的是空壳
                    v.postDelayed(() -> v.evaluateJavascript(MobileSkin.PROBE, null), 1500);
                }
            }

            @Override
            public void onReceivedError(WebView v, WebResourceRequest request, WebResourceError error) {
                // 只有主文档失败才当作「服务没起来」，子资源失败照常忽略
                if (request.isForMainFrame()) {
                    showError("连不上 DSH 服务。\n\n请先在 Termux 里把它启动起来，再点下面的按钮重试。");
                }
            }
        });

        view.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView v, int progress) {
                if (progress > 60 && errorPanel.getVisibility() == View.VISIBLE) {
                    // 已经开始出内容了，说明服务其实活着
                    errorPanel.setVisibility(View.GONE);
                }
            }

            /**
             * 把网页里的 console.log 接到 logcat。
             *
             * 这条通道是「手机端 UI 调试」的关键：页面跑在手机里，改样式只能靠
             * 注入脚本，而注入脚本的产出必须有地方可读 —— adb logcat -s DSHProbe
             * 就是那个地方。注入脚本里全部用 try/catch 包着，量不到也只是少几行日志，
             * 不会把页面弄坏。
             */
            @Override
            public boolean onConsoleMessage(ConsoleMessage msg) {
                Log.i("DSHProbe", msg.message()
                    + "  @" + msg.sourceId() + ":" + msg.lineNumber());
                return true;
            }
        });
    }

    /** 服务没起来时的兜底界面：说清楚原因，给一个重试和一个去 Termux 的出口。 */
    private LinearLayout buildErrorPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER);
        panel.setBackgroundColor(Color.parseColor("#0b0b0d"));
        panel.setPadding(dp(32), dp(32), dp(32), dp(32));

        errorText = new TextView(this);
        errorText.setTextColor(Color.parseColor("#c9d1e0"));
        errorText.setTextSize(15f);
        errorText.setGravity(Gravity.CENTER);
        errorText.setLineSpacing(dp(6), 1f);
        panel.addView(errorText);

        Button retry = new Button(this);
        retry.setText("重试");
        retry.setOnClickListener(v -> {
            errorPanel.setVisibility(View.GONE);
            // 重试走不带 token 的地址：首次引导已经拿到 Cookie，之后就靠它。
            web.loadUrl(currentBareUrl());
        });
        panel.addView(retry, buttonParams());

        Button termux = new Button(this);
        termux.setText("打开 Termux");
        termux.setOnClickListener(v -> {
            Intent i = getPackageManager().getLaunchIntentForPackage("com.termux");
            if (i != null) {
                startActivity(i);
            } else {
                showError("这台设备上没有装 Termux。\n\nDSH 需要它来提供运行时。");
            }
        });
        panel.addView(termux, buttonParams());

        return panel;
    }

    private LinearLayout.LayoutParams buttonParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(14);
        return p;
    }

    private void showError(String message) {
        errorText.setText(message);
        errorPanel.setVisibility(View.VISIBLE);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /**
     * 返回键：按「由内到外」的顺序退，最后要连按两次才离开应用。
     *
     * 顺序（和安卓上主流应用的直觉一致）：
     *   1. 有网页浮层（设置、插件面板…）→ 关掉浮层
     *   2. 侧栏抽屉开着              → 收起抽屉
     *   3. 浏览器历史能退            → 退一步
     *   4. 都不行                    → 第一次按提示「再按一次退出」，两秒内再按才退到桌面
     *
     * 三条都是真机上踩出来的：
     *
     * · 浮层那条修过一个真 bug：DSH 的设置面板是网页浮层、**不进浏览器历史**，
     *   而原先只判 canGoBack()，于是打开设置按返回会直接退到桌面。
     *
     * · 抽屉那条：抽屉是我们自己改成 fixed 覆盖层的，同样不进历史，
     *   不显式处理的话在抽屉里按返回会穿透到第 3/4 步。
     *
     * · 双击退出那条：单按就退到桌面太容易误触（尤其抽屉/浮层刚关掉时
     *   手指还在返回键附近）。加一次确认，符合国内应用的普遍预期。
     *
     * 两个同步标志（__dshOverlayOpen / __dshNavOpen）由 MobileSkin 的常驻脚本
     * 事先算好挂在 window 上：evaluateJavascript 是异步的，返回键等不起 ——
     * 先把按键消化掉，再异步去关。
     */
    @Override
    public void onBackPressed() {
        if (web == null) {
            super.onBackPressed();
            return;
        }

        web.evaluateJavascript(
            "(function(){ try{"
                + " return (window.__dshOverlayOpen ? 1 : 0) + (window.__dshNavOpen ? 2 : 0);"
                + " } catch(e){ return 0; } })()",
            value -> {
                // 踩过的坑：evaluateJavascript 的返回值是 JSON。
                // 字符串会带引号（'"true"'），拿 "true".equals(value) 比会永远为假。
                // 所以这里返回数字，再去引号后判定。
                String v = value == null ? "" : value.replace("\"", "").trim();
                int flags;
                try {
                    flags = Integer.parseInt(v);
                } catch (NumberFormatException e) {
                    flags = 0;
                }
                boolean overlay = (flags & 1) != 0;
                boolean navOpen = (flags & 2) != 0;

                /*
                 * 顺序有讲究：**先收抽屉，再关浮层**。
                 *
                 * 踩过的坑：抽屉是 position:fixed 且盖满视口的，正好满足
                 * OVERLAY 里「浮层」的几何判定（fixed + 尺寸够大 + 可见），
                 * 于是打开抽屉时两个标志同时为真。原先先判浮层，就跑去派发
                 * Escape —— 而抽屉不认 Escape，结果就是「按返回毫无反应」。
                 *
                 * 从视觉层级看，抽屉盖在页面上、浮层盖在页面里，抽屉更靠外，
                 * 所以它优先也符合直觉：返回先退掉最外层那个。
                 */
                if (navOpen) {
                    web.evaluateJavascript(MobileSkin.CLOSE_NAV, null);
                } else if (overlay) {
                    web.evaluateJavascript(MobileSkin.DISMISS, null);
                } else if (web.canGoBack()) {
                    web.goBack();
                } else {
                    confirmExit();
                }
            });
    }

    /**
     * 网页 → 原生 的桥。目前只有一个方法：打开外壳设置页。
     *
     * 为什么需要桥：入口按钮画在 DSH 的侧栏里（那里才有稳定的位置），
     * 但落地页是原生的 Activity。两个世界之间只能靠 addJavascriptInterface。
     *
     * 安全性：这个桥只暴露一个无参、无返回值、打开固定界面的方法 ——
     * 页面就算完全失控也做不了别的事。不要在里加任何接收参数的方法
     * （那是 WebView 注入的经典入口）。
     */
    private final class SettingsBridge {
        @android.webkit.JavascriptInterface
        public void openMobileSettings() {
            runOnUiThread(() -> {
                try {
                    startActivity(new Intent(MainActivity.this, MobileSettingsActivity.class));
                } catch (Throwable ignored) { }
            });
        }
    }

    /**
     * 供设置页回调：切回主界面并打开 DSH 自己的设置浮层。
     *
     * ── 踩过的坑（这个 bug 的成因值得留档）──────────────────────────────
     *
     * 第一版写的是 moveTaskToBack(false) + finish()，结果用户点「DSH 设置」
     * 之后**直接看到桌面**，表现像"强制退出应用"。
     *
     * 原因：moveTaskToBack 作用于**整个任务**，而主界面此刻正被设置页盖住、
     * 处于 pause —— 它不会自动回到前台。于是任务去了后台、设置页又 finish 了，
     * 屏幕上什么都没剩下。
     *
     * 正确做法是**只 finish 设置页**：设置页一关，下面的主界面自然回到前台。
     *
     * 另外脚本不能立刻派发：设置页还没关，主界面仍处于 pause，此时 evaluateJavascript
     * 可能落空。所以只记一个待办标志，等 onResume（真的回到前台）再执行。
     */
    private static java.lang.ref.WeakReference<MainActivity> current;

    /** 待执行的「打开网页设置」请求；由 onResume 消费，见 requestWebSettings 的注释。 */
    private boolean pendingOpenWebSettings;

    static void requestWebSettings() {
        MainActivity activity = current == null ? null : current.get();
        if (activity == null) return;
        activity.runOnUiThread(() -> activity.pendingOpenWebSettings = true);
    }

    /**
     * 回到前台时消费「打开网页设置」的待办。
     *
     * 放在 onResume 而不是 requestWebSettings 里立刻执行：调用方
     * （设置页）此刻还盖在上面，主界面处于 pause，此时注入脚本可能落空。
     * 而且设置页 finish 之后必然走到这里，时机是确定的。
     */
    @Override
    protected void onResume() {
        super.onResume();
        if (!pendingOpenWebSettings) return;
        pendingOpenWebSettings = false;
        if (web == null) return;
        // 等一帧再注入：onResume 时 WebView 可能还没完成这次可见性切换。
        web.postDelayed(() -> {
            try { web.evaluateJavascript(MobileSkin.OPEN_WEB_SETTINGS, null); }
            catch (Throwable ignored) { }
        }, 250);
    }

    /**
     * 「再按一次退出」。
     *
     * 用 moveTaskToBack 而不是 finish：回到桌面但保留现场，
     * 再点图标回来还是原来的会话 —— 符合安卓上对主界面应用的通常预期。
     */
    private void confirmExit() {
        long now = System.currentTimeMillis();
        if (now - lastBackPressAt <= EXIT_CONFIRM_MS) {
            moveTaskToBack(true);
            return;
        }
        lastBackPressAt = now;
        Toast.makeText(this, "再按一次返回退出", Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
