package com.dsh.mobile;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Space;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 应用设置页。
 *
 * 为什么要有这一页：开机动画的开关原来只能靠**长按**调出来，而长按被挂在整个
 * 界面上 —— 用户在任何一个页面按住屏幕都会弹出设置对话框，非常吵。这里把它
 * 收进一个专门的设置页，长按不再兼任开关入口。
 *
 * 为什么用代码搭界面而不是布局 XML：
 *   这个工程是手写的构建流程（aapt2 + javac + d8，没有 Gradle，也没有 AndroidX）。
 *   加 XML 布局意味着要同步维护资源引用与 R 类，收益不抵成本；界面本身不复杂，
 *   代码里搭出来反而更直观，配色也能直接和 DSH（#0b0b0d）对齐。
 *
 * 用 Android 原生的 Switch 而不是自绘：系统控件的无障碍、触摸反馈、深浅色
 * 行为都是现成的，自绘省不了多少事，却要自己处理这些。
 */
public class MobileSettingsActivity extends Activity {

    /** 与 DSH 界面对齐的深色配色。 */
    private static final int INK = 0xFF0B0B0D;
    private static final int CARD = 0xFF16161A;
    private static final int TEXT = 0xFFE8E8EA;
    private static final int MUTED = 0xFF8A8A93;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(INK);
        scroll.setFillViewport(true);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        // 内边距统一由外层 ScrollView 按 insets 设置（见文件末尾），
        // 这里再加一层就会和状态栏让位叠成两倍，顶部空一大块。
        col.setPadding(0, 0, 0, 0);
        scroll.addView(col, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        col.addView(title("手机端设置"));
        col.addView(hint("这些是外壳层面的选项，和 DSH 自己的设置（侧栏里的「设置」）互不影响。"));

        col.addView(sectionLabel("启动"));
        col.addView(toggleRow(
            "开机动画",
            "每次打开应用时播放 DSH 的开机动画。关掉后直接进入会话界面。",
            SplashSettings.isEnabled(this),
            (buttonView, isChecked) -> {
                getSharedPreferences(SplashSettings.PREFS, MODE_PRIVATE)
                    .edit().putBoolean(SplashSettings.KEY_ENABLED, isChecked).apply();
                Toast.makeText(this,
                    isChecked ? "已开启：下次启动会播放" : "已关闭：下次启动不再播放",
                    Toast.LENGTH_SHORT).show();
            }));

        col.addView(sectionLabel("关于"));
        col.addView(hint("改这里的开关只影响下次启动，不会打断正在播放的动画。"));

        /*
         * 进入 DSH 自己的设置。
         *
         * 为什么把两个设置合并到同一个入口：侧栏底部原本有两个按钮 ——
         * 外壳设置（手机图标）和 DSH 自己的设置（齿轮），而它们在 rail 布局里
         * 处在同一个 flex 行、只有 36px 宽，结果**两个按钮完全重叠**。
         * 与其挤在一起，不如合成一个入口：手机图标打开这一页，
         * 这一页再给出前往 DSH 设置的入口。
         *
         * DSH 的设置是它自己网页里的浮层，原生这边没法直接嵌进来，
         * 所以做法是：关掉本页 → 让网页替我们点一下它自己的设置按钮。
         */
        col.addView(linkRow(
            "DSH 设置",
            "打开 DeepSeek Harness 自己的设置（模型、插件、工作区等）。",
            () -> {
                // 只登记请求 + 关掉本页。不要动整个任务 ——
                // 早先在这里调过 moveTaskToBack，结果主界面还在后台、
                // 本页又 finish 了，用户直接看到桌面（表现像应用崩了）。
                // 本页一关，主界面自然回到前台，由它的 onResume 去打开设置。
                MainActivity.requestWebSettings();
                finish();
            }));

        setContentView(scroll);

        // 状态栏/导航栏让位。AppTheme 的 windowBackground 与界面同色，
        // 但内容仍会画到状态栏底下 —— 标题「手机端设置」会和时钟叠在一起。
        // 用 insets 加内边距，不写死数值（和主界面同一套思路）。
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            int top = 0;
            int bottom = 0;
            try {
                top = insets.getInsets(WindowInsets.Type.statusBars()).top;
                bottom = insets.getInsets(WindowInsets.Type.navigationBars()).bottom;
            } catch (Throwable ignored) { }
            int pad = dp(20);
            v.setPadding(pad, pad + top, pad, pad + bottom);
            return insets;
        });
        scroll.requestApplyInsets();
    }

    // ---------------------------------------------------------------- 界面零件

    private TextView title(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(TEXT);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        tv.setPadding(0, 0, 0, dp(8));
        return tv;
    }

    private TextView hint(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(MUTED);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setLineSpacing(dp(4), 1f);
        tv.setPadding(0, 0, 0, dp(20));
        return tv;
    }

    private TextView sectionLabel(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(MUTED);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tv.setPadding(dp(4), dp(4), 0, dp(8));
        return tv;
    }

    /**
     * 一行开关：左边标题 + 说明，右边一个 Switch。
     *
     * 整行可点（点标题也能切换），这是安卓设置页的通行做法 ——
     * 只让 Switch 那一小块可点，手指要瞄准，很难受。
     */
    private View toggleRow(String name, String desc, boolean checked,
                           CompoundButton.OnCheckedChangeListener onChange) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(rounded(CARD, dp(14)));
        int p = dp(16);
        row.setPadding(p, p, p, p);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        row.addView(texts, new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView t = new TextView(this);
        t.setText(name);
        t.setTextColor(TEXT);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        texts.addView(t);

        TextView d = new TextView(this);
        d.setText(desc);
        d.setTextColor(MUTED);
        d.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        d.setLineSpacing(dp(3), 1f);
        d.setPadding(0, dp(6), dp(10), 0);
        texts.addView(d);

        Switch sw = new Switch(this);
        sw.setChecked(checked);
        sw.setOnCheckedChangeListener(onChange);
        row.addView(sw);

        // 点整行 = 点开关（但不要反过来触发两次回调）
        row.setOnClickListener(v -> sw.toggle());

        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.addView(row, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        Space gap = new Space(this);
        gap.setLayoutParams(new LinearLayout.LayoutParams(1, dp(12)));
        wrap.addView(gap);
        return wrap;
    }

    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    /**
     * 一行「前往别处」的入口：左边标题 + 说明，右边一个箭头。
     *
     * 和 toggleRow 的区别只在右侧控件 —— 这里不表示状态，而是表示「会跳走」。
     * 整行可点，和开关行保持一致的手感。
     */
    private View linkRow(String name, String desc, Runnable onClick) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(rounded(CARD, dp(14)));
        int p = dp(16);
        row.setPadding(p, p, p, p);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        row.addView(texts, new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView t = new TextView(this);
        t.setText(name);
        t.setTextColor(TEXT);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        texts.addView(t);

        TextView d = new TextView(this);
        d.setText(desc);
        d.setTextColor(MUTED);
        d.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        d.setLineSpacing(dp(3), 1f);
        d.setPadding(0, dp(6), dp(10), 0);
        texts.addView(d);

        TextView arrow = new TextView(this);
        arrow.setText("›");
        arrow.setTextColor(MUTED);
        arrow.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        row.addView(arrow);

        row.setOnClickListener(v -> onClick.run());

        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.addView(row, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        Space gap = new Space(this);
        gap.setLayoutParams(new LinearLayout.LayoutParams(1, dp(12)));
        wrap.addView(gap);
        return wrap;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
