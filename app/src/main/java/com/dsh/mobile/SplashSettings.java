package com.dsh.mobile;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import android.widget.Toast;

/**
 * 开机动画的开关界面。
 *
 * 为什么单独一个类：这个开关有两个入口 —— 动画播放时长按，以及主界面上长按。
 * 两处必须弹出完全一样的界面、读写同一份偏好，所以实现只留一份。
 *
 * 为什么主界面也必须有入口：开关最初只挂在动画自己身上，结果是**关掉之后就再也没有
 * 入口能打开它**（动画不播了，自然也没地方长按）。这是个真实的设计缺陷，
 * 在真机上把开关关掉后才暴露出来。
 */
final class SplashSettings {

    private SplashSettings() { }

    /** 与 MainActivity 共用的偏好文件与键名。 */
    static final String PREFS = "dsh";
    static final String KEY_ENABLED = "opening_splash";

    private static final String TAG = "DSHSplashCfg";

    static boolean isEnabled(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, true);
    }

    /**
     * 弹出设置。改动在点「保存」时落盘。
     *
     * 只改「下次启动还播不播」，不会打断正在播放的这一次 —— 动画中途被撤销的
     * 体验比多等一会儿更糟。
     */
    static void show(Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        final boolean[] enabled = { prefs.getBoolean(KEY_ENABLED, true) };

        new AlertDialog.Builder(ctx)
            .setTitle("开机动画")
            .setMultiChoiceItems(
                new CharSequence[] { "每次启动时播放开机动画" },
                new boolean[] { enabled[0] },
                (d, which, isChecked) -> enabled[0] = isChecked)
            .setPositiveButton("保存", (d, w) -> {
                prefs.edit().putBoolean(KEY_ENABLED, enabled[0]).apply();
                Toast.makeText(ctx,
                    enabled[0] ? "已开启：下次启动会播放" : "已关闭：下次启动不再播放",
                    Toast.LENGTH_SHORT).show();
                Log.i(TAG, "opening_splash = " + enabled[0]);
            })
            .setNegativeButton("取消", null)
            .show();
    }
}
