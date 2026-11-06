package com.feedreform.highlight;

import android.app.AppOpsManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

/**
 * 跳转到小红书/B站后，在屏幕上留一个「返回亮点」气泡，并显示已经过去多久。
 * 点一下回主界面；回主界面后由 MainActivity 停止本服务。
 * 30 分钟没回来就自动收掉（避免一直挂着）。
 */
public class ReturnBubbleService extends Service {

    private static volatile ReturnBubbleService instance;
    private WindowManager wm;
    private TextView bubble;
    private boolean bubbleShown;
    private WindowManager.LayoutParams lp;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable autoHide = this::stopSelf;
    /** 本次跳转（服务启动）的时刻，重复跳转会在 onStartCommand 里重置。 */
    private long startedAt;
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (bubble == null) return;
            bubble.setText(BubbleClock.text(SystemClock.elapsedRealtime() - startedAt));
            handler.postDelayed(this, 1000);
        }
    };
    private AppOpsManager appOps;
    private boolean sessionReceiverRegistered;
    /** 会话一结束就收气泡；没有 session 的卡片打开场景不会收到 false 广播，仍按 30 分钟兜底。 */
    private final BroadcastReceiver sessionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!HighlightSession.isActive()) stopSelf();
        }
    };
    private final AppOpsManager.OnOpChangedListener permissionListener = (op, packageName) ->
            handler.post(() -> {
                if (!Settings.canDrawOverlays(this)) stopSelf();
            });

    /** Activity-side check avoids requesting a service for optional, unavailable UI. */
    static void startIfAllowed(Context context) {
        if (!Settings.canDrawOverlays(context)) return;
        try {
            context.startForegroundService(new Intent(context, ReturnBubbleService.class));
        } catch (IllegalStateException | SecurityException ignored) {
            // Opening the content must still work if the optional service cannot start.
        }
    }

    /** 立即收气泡（真正结束会话）：离开目标 App/回亮点/锁屏/会话结束时调用；服务没在跑也安全。 */
    static void stop(Context context) {
        try {
            context.stopService(new Intent(context, ReturnBubbleService.class));
        } catch (Throwable ignored) {
        }
    }

    /** 只显示/隐藏气泡，不停服务、不停计时：离开目标 App 时隐藏，5s 内回来继续显示。 */
    static void setVisible(Context context, boolean visible) {
        ReturnBubbleService s = instance;
        if (s != null) s.setBubbleVisible(visible);
    }

    void setBubbleVisible(boolean visible) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post(() -> setBubbleVisible(visible));
            return;
        }
        if (!visible) {
            hideBubble();
            return;
        }
        if (bubbleShown) return;
        if (!Settings.canDrawOverlays(this)) return;
        if (!showBubble()) return;
        // 立即用当前 elapsed 更新文案；tick 继续每秒跑，计时不重置。
        handler.removeCallbacks(tick);
        tick.run();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        startedAt = SystemClock.elapsedRealtime();
        startForeground(1, buildNotification());
        appOps = getSystemService(AppOpsManager.class);
        appOps.startWatchingMode(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW,
                getPackageName(), permissionListener);
        try {
            IntentFilter filter = new IntentFilter(HighlightSession.ACTION_CHANGED);
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(sessionReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(sessionReceiver, filter);
            }
            sessionReceiverRegistered = true;
        } catch (Throwable ignored) {
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 每次跳转都重新计时：服务已在跑时再次 start，onStartCommand 会再进来一次。
        startedAt = SystemClock.elapsedRealtime();
        // Check again in the service: permission may change after the Activity's check.
        if (!Settings.canDrawOverlays(this) || !showBubble()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        handler.removeCallbacks(tick);
        tick.run();
        handler.removeCallbacks(autoHide);
        handler.postDelayed(autoHide, 30 * 60 * 1000L);
        // An optional return affordance must not restart after failure/process death.
        return START_NOT_STICKY;
    }

    private Notification buildNotification() {
        String ch = "return_bubble";
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                    new NotificationChannel(ch, "返回亮点", NotificationManager.IMPORTANCE_LOW));
        }
        Intent i = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        PendingIntent pi = PendingIntent.getActivity(this, 0, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, ch)
                : new Notification.Builder(this);
        return b.setContentTitle("亮点")
                .setContentText("点这里返回亮点")
                .setSmallIcon(android.R.drawable.ic_menu_revert)
                .setContentIntent(pi)
                .addAction(new Notification.Action.Builder(null, "返回亮点", pi).build())
                .setOngoing(true)
                .build();
    }

    private boolean showBubble() {
        if (bubbleShown) return true;
        if (wm == null) wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (bubble != null) {
            try {
                wm.addView(bubble, lp);
                bubbleShown = true;
                return true;
            } catch (SecurityException | WindowManager.BadTokenException
                     | WindowManager.InvalidDisplayException e) {
                return false;
            }
        }
        bubble = new TextView(this);
        bubble.setText(BubbleClock.text(0));
        bubble.setTextColor(Color.WHITE);
        bubble.setTextSize(12);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFFDF5129);
        bg.setCornerRadius(80);
        bubble.setBackground(bg);
        float density = getResources().getDisplayMetrics().density;
        // 加了计时后气泡变宽：左右内边距收窄一点，少挡内容。
        bubble.setPadding((int)(12*density), (int)(10*density), (int)(12*density), (int)(10*density));
        bubble.setElevation(8);

        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= 26
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.START | Gravity.CENTER_VERTICAL;
        lp.x = 0;
        lp.y = 0;

        bubble.setOnTouchListener(new View.OnTouchListener() {
            float downY;
            int startY;
            boolean moved;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downY = e.getRawY();
                        startY = lp.y;
                        moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        int dy = (int) (e.getRawY() - downY);
                        if (Math.abs(dy) > 12) moved = true;
                        lp.y = startY + dy;
                        try {
                            wm.updateViewLayout(bubble, lp);
                        } catch (IllegalArgumentException | SecurityException | WindowManager.BadTokenException failure) {
                            stopSelf();
                        }
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                        if (!moved) goBack();
                        return true;
                }
                return false;
            }
        });
        try {
            wm.addView(bubble, lp);
            bubbleShown = true;
            return true;
        } catch (SecurityException | WindowManager.BadTokenException | WindowManager.InvalidDisplayException e) {
            // Permission revocation between canDrawOverlays and addView is expected.
            return false;
        }
    }

    private void hideBubble() {
        if (bubble != null && bubbleShown && wm != null) {
            try {
                wm.removeView(bubble);
            } catch (Throwable ignored) {
            }
        }
        bubbleShown = false;
    }

    private void goBack() {
        Intent i = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        startActivity(i);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (sessionReceiverRegistered) {
            try {
                unregisterReceiver(sessionReceiver);
            } catch (Throwable ignored) {
            }
            sessionReceiverRegistered = false;
        }
        if (appOps != null) appOps.stopWatchingMode(permissionListener);
        if (bubble != null && wm != null) {
            try {
                wm.removeView(bubble);
            } catch (Exception ignored) {
            }
        }
        bubbleShown = false;
        bubble = null;
        if (instance == this) instance = null;
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
