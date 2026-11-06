package com.feedreform.highlight;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

/**
 * 会话悄悄结束时的低打扰通知：只告诉用户「覆盖层已经停了」，不弹窗、不响铃、不振动。
 *
 * 只在会话因「回桌面 / 切别的 App / 目标窗口消失 / 无障碍断开」结束时发；
 * 回亮点自己、锁屏、息屏、系统面板遮挡都算已知/临时状态，不发。
 * 通知权限没给或系统拒绝时静默跳过，不影响服务本身。
 */
final class SessionNotice {

    private static final String CHANNEL_ID = "session_state_v2";
    /** 固定 id，避开 ReturnBubbleService 的前台通知 id=1。 */
    private static final int NOTICE_ID = 2;

    private SessionNotice() {
    }

    /** 离开目标 App（回桌面 / 切别的 App / 目标窗口消失）。 */
    static void show(Context c, String platformLabel) {
        String where = platformLabel == null || platformLabel.trim().isEmpty()
                ? "目标 App" : platformLabel.trim();
        post(c, "你离开了 " + where + "；想再看见标记，从入口推开窗。");
    }

    /** 无障碍服务被系统断开：进程还活着时尽力发。 */
    static void showAccessibility(Context c) {
        post(c, "无障碍服务断开了；重新开启后，从入口推开窗。");
    }

    /** 回到亮点 / 新会话开始：清掉上一次的会话结束通知。 */
    static void cancel(Context c) {
        try {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            if (nm != null) nm.cancel(NOTICE_ID);
        } catch (Throwable ignored) {
        }
    }

    private static void post(Context c, String text) {
        try {
            Context app = c.getApplicationContext();
            NotificationManager nm = app.getSystemService(NotificationManager.class);
            // Android 13+ 未授予 POST_NOTIFICATIONS 时 areNotificationsEnabled() 为 false；
            // 这里静默跳过，不抛异常、不阻塞会话结束。
            if (nm == null || !nm.areNotificationsEnabled()) return;

            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "会话状态", NotificationManager.IMPORTANCE_DEFAULT);
            channel.setSound(null, null);
            channel.enableVibration(false);
            channel.setShowBadge(false);
            nm.createNotificationChannel(channel);

            Intent open = new Intent(app, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            PendingIntent pi = PendingIntent.getActivity(app, 0x5E55, open,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

            Notification notification = new Notification.Builder(app, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_menu_info_details)
                    .setContentTitle("亮点先合上窗")
                    .setContentText(text)
                    .setStyle(new Notification.BigTextStyle().bigText(text))
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .setOngoing(false)
                    .build();
            nm.notify(NOTICE_ID, notification);
        } catch (Throwable ignored) {
            // 权限/厂商 ROM 限制：通知是锦上添花，绝不因为发不出去影响会话收尾。
        }
    }
}
