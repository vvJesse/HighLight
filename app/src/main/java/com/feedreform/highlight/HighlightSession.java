package com.feedreform.highlight;

import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

/**
 * 临时会话：只在「从亮点入口检查通过」之后开启，纯内存，不写任何偏好、不会常驻。
 *
 * 退出条件（由 {@link HighlightService} 执行）：切到别的 App、回桌面、返回亮点、锁屏、
 * 无障碍服务断开或被撤销、进程重建（静态字段归零）。系统窗口（通知栏、权限面板、音量）
 * 只隐藏效果，不算退出。
 */
final class HighlightSession {

    /** 服务据此立即响应会话开始/结束；只在应用内广播。 */
    static final String ACTION_CHANGED = "com.feedreform.highlight.action.SESSION_CHANGED";

    private static volatile boolean active;
    private static volatile String platform = "";
    private static volatile long startedAt;

    private HighlightSession() {
    }

    static void start(Context context, String target) {
        platform = target == null ? "" : target;
        startedAt = SystemClock.uptimeMillis();
        active = true;
        notifyChanged(context);
    }

    static void stop(Context context) {
        boolean was = active;
        active = false;
        platform = "";
        if (was) notifyChanged(context);
    }

    static boolean isActive() {
        return active;
    }

    static String activePlatform() {
        return platform;
    }

    /** 会话开始的 uptime，用于忽略刚启动那一两秒内「前台还是亮点自己」的判定。 */
    static long startedAt() {
        return startedAt;
    }

    private static void notifyChanged(Context context) {
        if (context == null) return;
        try {
            Intent i = new Intent(ACTION_CHANGED);
            i.setPackage(context.getPackageName());
            context.sendBroadcast(i);
        } catch (Throwable ignored) {
        }
    }
}
