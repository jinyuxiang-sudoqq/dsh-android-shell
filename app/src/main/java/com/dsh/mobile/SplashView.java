package com.dsh.mobile;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * 开机动画覆盖层：在本地资产里播放 DSH 的开场动画，放完（或双击）就淡出。
 *
 * 为什么是「本地资产里的一个 WebView」而不是别的东西：
 *   电脑上那套开机动画本来就是 dsh-opening-splash 插件里的一个自包含 HTML
 *   （内联样式 + 内联 SVG + 内联脚本，零外部引用）。手机端要的是同一份动画，
 *   那就用同一个文件 —— 而不是拿截图或视频去翻拍一遍。这样以后动画更新，
 *   只要把这个 html 换掉即可，不需要动任何代码。
 *
 * 它和主界面的关系：
 *   覆盖层只是盖在上面，底层那个 DSH 的 WebView 照常加载自己的页面。
 *   等动画放完，底下的页面往往已经就绪，淡出后直接就是可用的界面 ——
 *   这正是桌面端「动画播放期间应用在后台启动」的时序。
 *
 * 与动画文档的约定：
 *   · 播完会派发 `dsh:splash-complete`（构建时另被改写成直呼原生桥）；
 *   · 按 Esc 也会结束（文档自带）。
 *   这两条都留着当「提前收场」的快路，但**收场的准时性不依赖它们** ——
 *   覆盖层在不可见状态下会被 WebView 冻结计时器，实测那两条都到不了，
 *   所以真正的交接时刻由安卓侧的 postDelayed 决定（见 armHandoff）。
 */
final class SplashView extends FrameLayout {

    /** 动画结束（自然播完或用户跳过）后回调，只会调用一次。 */
    interface Listener {
        void onSplashFinished();
    }

    private static final String ASSET = "file:///android_asset/splash.html";

    private static final String TAG = "DSHSplash";

    /** 双击判定：两次按下间隔小于 400ms、位移小于 120px。 */
    private static final long DOUBLE_TAP_MS = 400L;
    private static final float DOUBLE_TAP_SLOP_PX = 120f;

    /** 长按判定：按住 900ms 且位移不超过 30px。 */
    private static final long LONG_PRESS_MS = 900L;
    private static final float LONG_PRESS_SLOP_PX = 30f;

    /** 底部提示（长按可设置）停留时长。 */
    private static final long HINT_MS = 5000L;

    private long lastDownAt;
    private float lastDownX, lastDownY;
    private boolean longPressPending;

    /**
     * 交回界面的时刻。
     *
     * 这个值不是「兜底超时」，它就是**正常的收场路径** —— 原因见类注释里
     * 关于「不可见 WebView 会被冻结」的那一段：动画文档自己的定时器与
     * requestAnimationFrame 都会停摆，唯一可靠的时钟只有安卓侧的 postDelayed。
     *
     * 取值：动画总长 26s + 收尾 HOLD 900ms + 余量 350ms ≈ 27.25s，
     * 这里给到 27.6s —— 落在动画已经变黑、但还不至于让人等的位置。
     * 再长就是在黑屏上干等，再短会切在动画还在放的时候。
     */
    private static final long HANDOFF_MS = 27_600L;

    /**
     * 注入到动画文档里的小补丁。
     *
     * 只做两件事：
     *   1. 播完通知安卓侧 —— 监听它已有的 dsh:splash-complete 事件；
     *   2. 双击 = 跳过 —— 合成一次 Escape 按键事件，走它自己的跳过路径，
     *      这样收尾动画、状态清理都由原文档负责，行为跟按 Esc 完全一致。
     *
     * 之所以要把 window.__dshSplashDone 包在 try/catch 里：这个脚本在
     * onPageFinished 之后注入，而动画文档可能在极快的情况下已经播完，
     * 那时 JS 接口仍应存在；但若注入时机异常，也绝不能因此抛错把页面弄死。
     */
    private static final String BIND =
        "(function(){"
      + "  function notify(reason){"
      + "    try{ window.DSHSplash && window.DSHSplash.done(String(reason||'complete')); }"
      + "    catch(e){}"
      + "  }"
      + "  try{"
      + "    if(window.__dshSplashBound){ return; }"
      + "    window.__dshSplashBound = true;"
      + "    /*"
      + "     * 提前收场的快路：动画若真能报告自己放完了，就立刻交回界面，"
      + "     * 不必等 HANDOFF_MS。构建时已把动画的完成通知改写成直呼原生桥"
      + "     * （见 rewire-splash.mjs），这里再挂一层事件监听做冗余。"
      + "     *"
      + "     * 但实际验证下来这条路到不了 —— 覆盖层不可见时页面计时器被冻结，"
      + "     * 动画自己的收尾函数也不会按时跑到。所以它只是「万一能用」的加速项，"
      + "     * 真正的收场时刻由安卓侧的 armHandoff() 决定。"
      + "     */"
      + "    addEventListener('dsh:splash-complete', function(ev){"
      + "      var r = (ev && ev.detail && ev.detail.reason) || 'complete';"
      + "      notify(r);"
      + "    });"
      + "    /* 双击 = 跳过（合成 Esc，走它自己的收尾逻辑） */"
      + "    addEventListener('dblclick', function(){"
      + "      try{"
      + "        dispatchEvent(new KeyboardEvent('keydown',"
      + "          {key:'Escape', code:'Escape', keyCode:27, which:27, bubbles:true}));"
      + "      }catch(e){ notify('dblclick'); }"
      + "    }, true);"
      + "  }catch(e){}"
      + "})()";

    private final Listener listener;
    private boolean finished;

    @SuppressLint("SetJavaScriptEnabled")
    SplashView(Context context, Listener listener) {
        super(context);
        this.listener = listener;

        setBackgroundColor(Color.parseColor("#000000"));

        WebView web = new WebView(context);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);          // 动画本身就是一段脚本
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);            // 需要读 file:///android_asset/

        // 与主界面同样的视口口径：尊重文档自己的 width=device-width，
        // 不做整体缩放、不给双指缩放。动画是 SVG，按设备宽度排版即可。
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(false);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setTextZoom(100);

        web.setBackgroundColor(Color.parseColor("#000000"));
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setHorizontalScrollBarEnabled(false);
        web.setVerticalScrollBarEnabled(false);

        // 动画是持续的 SVG 描边 + 透明度变化，交给硬件层合成，避免掉帧。
        web.setLayerType(View.LAYER_TYPE_HARDWARE, null);

        web.addJavascriptInterface(new Bridge(), "DSHSplash");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView v, String url) {
                Log.i(TAG, "splash page finished: " + url);
                v.evaluateJavascript(BIND, null);
                armHandoff();
            }

            @Override
            public void onReceivedError(WebView v, WebResourceRequest request,
                                        WebResourceError error) {
                if (request.isForMainFrame()) {
                    // 连本地动画都读不出来（资产缺失 / 构建时漏了 -A），
                    // 那就别让用户对着黑屏等 —— 直接收场，把界面交回去。
                    Log.w(TAG, "splash failed to load: " + error.getDescription());
                    finish();
                }
            }
        });

        addView(web, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        addView(buildHint());

        web.loadUrl(ASSET);
    }

    /**
     * 底部提示。
     *
     * 存在的理由：设置入口是长按，而长按是看不见的手势 —— 不说一句没人会知道。
     * 但它也不能碍事：动画是要给人看的，所以只停 5 秒就淡出。
     *
     * 只在第一次启动时显示（用偏好记一笔）：提示的价值在于「第一次知道」，
     * 之后每次冷启动都飘一句就变成噪音了。
     */
    private TextView buildHint() {
        TextView hint = new TextView(getContext());
        hint.setText("长按可设置开机动画 · 双击跳过");
        hint.setTextColor(0x66000000);
        hint.setTextSize(11f);
        hint.setGravity(android.view.Gravity.CENTER);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = android.view.Gravity.BOTTOM;
        lp.bottomMargin = dp(38);
        hint.setLayoutParams(lp);

        boolean shown = getContext().getSharedPreferences("dsh", Context.MODE_PRIVATE)
            .getBoolean("splash_hint_shown", false);
        if (shown) {
            hint.setVisibility(View.GONE);
        } else {
            getContext().getSharedPreferences("dsh", Context.MODE_PRIVATE)
                .edit().putBoolean("splash_hint_shown", true).apply();
            hint.animate().alpha(0f).setStartDelay(HINT_MS).setDuration(700)
                .withEndAction(() -> hint.setVisibility(View.GONE)).start();
        }
        return hint;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /**
     * 到点交回界面。
     *
     * ── 为什么这里不能只等动画自己报告「放完了」 ──────────────────────────
     *
     * 覆盖层是盖在主 WebView 上面的一层，也就是说**它自己并不可见**。
     * Android WebView 对不可见视图会冻结渲染管线：动画文档内部的
     * requestAnimationFrame 循环和 setTimeout 都不会按时走完。
     *
     * 实测的日志长这样：
     *     splash page finished          ← 文档加载完成，一切正常
     *     （此后 27 秒内，页面里没有任何东西推进）
     *     splash safety timeout fired   ← 只有安卓侧 postDelayed 准时醒了
     *
     * 动画文档里确实有一套自报完成的机制（dsh:splash-complete 事件，以及
     * 构建时改写成的原生桥直呼），BIND 里也挂了监听。它们留着 —— 万一
     * 某个版本 WebView 不再冻结不可见页面，就能提前、精确地收场。
     * 但**不能依赖**它们：这个定时器才是主路径。
     *
     * 副产物：动画本身也被冻结了么？没有。动画确实播完了（真机截图能看到
     * 中段画面），只是它的收尾通知没能送出来。所以画面与收场要分别对待 ——
     * 画面靠 WebView 自己，收场时刻必须由安卓侧说了算。
     *
     * finish() 是幂等的：真收到动画的完成信号时，这个方法醒来会发现无事可做。
     */
    private void armHandoff() {
        postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!finished) {
                    Log.i(TAG, "handoff deadline reached; handing back to the app");
                }
                finish();
            }
        }, HANDOFF_MS);
    }

    /**
     * 长按 → 打开开机动画的设置。
     *
     * 为什么开关放在动画自己身上：动画要放 27 秒，中途被误触取消的体验很糟，
     * 所以没有把开关做成画面上的常驻按钮。长按是一个「刻意为之」的动作，
     * 不会误触；而打开动画的这一刻，正是用户最可能想「以后别放了」的时刻。
     *
     * 主界面也挂了同一个入口（见 MainActivity）—— 否则关掉动画之后就再也没有
     * 地方能把它打开。
     */
    private void showSplashSettings() {
        SplashSettings.show(getContext());
    }

    /**
     * 触摸处理：识别双击跳过，以及长按打开设置。
     *
     * 为什么双击要自己算而不用 GestureDetector：见下面常量处的说明。
     *
     * 为什么长按也自己算：GestureDetector 已经因为判定策略否掉过一次合成事件
     * （双击那次），不再往它身上压第二个手势。自己算的规则简单到能对着日志推理。
     *
     * 只读不消费：永远返回 false，事件继续下发给动画文档，检测纯属旁路。
     */
    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        try {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    handleDown(ev);
                    break;
                case MotionEvent.ACTION_MOVE:
                    // 移动超过阈值就不算长按（用户可能只是在滑动）
                    if (longPressPending
                        && distanceFromDown(ev) > LONG_PRESS_SLOP_PX) {
                        longPressPending = false;
                    }
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    longPressPending = false;
                    break;
                default:
                    break;
            }
        } catch (Exception ignored) { }
        return false;
    }

    private void handleDown(MotionEvent ev) {
        long now = ev.getEventTime();
        float dx = Math.abs(ev.getX() - lastDownX);
        float dy = Math.abs(ev.getY() - lastDownY);
        boolean hadPrev = lastDownAt != 0L;
        boolean quick = hadPrev && (now - lastDownAt) < DOUBLE_TAP_MS;
        boolean near = dx < DOUBLE_TAP_SLOP_PX && dy < DOUBLE_TAP_SLOP_PX;

        // 日志留在这里是有意的：这两条手势路径只能靠真机操作验证，
        // 打一行就能分清「事件没到」和「判定没过」。
        Log.i(TAG, "tap: " + (hadPrev ? ((now - lastDownAt) + "ms") : "first")
            + " dx=" + (int) dx + " dy=" + (int) dy
            + ((quick && near) ? " -> DOUBLE TAP" : ""));

        if (quick && near) {
            lastDownAt = 0L;
            finish();
            return;
        }

        lastDownAt = now;
        lastDownX = ev.getX();
        lastDownY = ev.getY();

        // 长按计时：只挂一次，抬手或移动越界即取消。
        longPressPending = true;
        postDelayed(() -> {
            if (longPressPending && !finished) {
                longPressPending = false;
                Log.i(TAG, "long press -> splash settings");
                showSplashSettings();
            }
        }, LONG_PRESS_MS);
    }

    private float distanceFromDown(MotionEvent ev) {
        return (float) Math.hypot(ev.getX() - lastDownX, ev.getY() - lastDownY);
    }

    /** 动画文档回调进来的入口。 */
    private final class Bridge {
        @JavascriptInterface
        public void done(String reason) {
            Log.i(TAG, "splash reported done: " + reason);
            finish();
        }
    }

    /**
     * 结束并淡出。
     *
     * 幂等：动画自然播完和用户双击可能几乎同时到达，重复调用只会走一次。
     * 淡出用 ViewPropertyAnimator，结束后把覆盖层从视图树里摘掉并销毁，
     * 不在内存里留一个 44KB 文档的 WebView。
     */
    void finish() {
        if (finished) return;
        finished = true;
        Log.i(TAG, "splash finishing");

        animate()
            .alpha(0f)
            .setDuration(420)
            .withEndAction(new Runnable() {
                @Override
                public void run() {
                    ViewGroup parent = (ViewGroup) getParent();
                    if (parent != null) {
                        parent.removeView(SplashView.this);
                    }
                    removeAllViews();
                    Log.i(TAG, "splash removed from view tree");
                    if (listener != null) {
                        listener.onSplashFinished();
                    }
                }
            })
            .start();
    }
}
