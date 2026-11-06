package com.feedreform.highlight;

import android.accessibilityservice.AccessibilityService;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 封面标记服务：只在 {@link HighlightSession} 激活时、在前台目标 App 的推荐流上绘制。
 *
 * 行为沿用 AX 灰层实验（project/experiments/ax-gray）已验证的时序：
 * - 滚动立即隐藏，停手 100ms 后重扫、重绘；
 * - 布局首卡 top 位移 >8px（重排/刷新/换一批卡）立即清旧帧，等 settle 后整帧重画，
 *   不让旧灰罩贴到新布局的标题/作者上（初代 receiveLayout 的 layout-change）；
 * - 静止期只按「首张完全可见卡的封面 top 位移 >8px」判运动，避免 B站 重排版误判（pitfalls #37）；
 * - B站 详情页只画「相关推荐」，150ms watchdog + 事件检查：切评论 tab / 列表不可见立即清帧；
 * - 离开目标 App / 锁屏 / 服务断开就结束会话；系统窗口只隐藏，回到目标 App 再重绘；
 * - 通知栏/系统面板展开时目标窗口可能从 getWindows() 消失：最近 10s 见过目标就不计消失，
 *   10s 消失兜底也不会在这段宽限内触发（F7-02）；
 * - 会话结束只看窗口类型与目标 App 是否还有可见窗口，不维护包名清单；非目标窗口先隐藏、约 1.2s 复核；
 * - 扫描与判断都在后台线程，主线程只做绘制，绘制内容预先算好（不在 onDraw 里遍历节点）。
 */
public class HighlightService extends AccessibilityService {

    private static final String TAG = "HLFLOW";

    /** 设置页改了发亮/灰罩参数后发这个包内广播，让服务立即重扫重画。 */
    static final String ACTION_STYLE_CHANGED = "com.feedreform.highlight.ACTION_STYLE_CHANGED";

    private static volatile boolean connected;

    /** 设置页/检查面板据此显示「服务未连接」；服务被系统撤销时变 false。 */
    static boolean isConnected() {
        return connected;
    }

    private static final int SETTLE_DEBOUNCE_MS = 100;
    private static final int FADE_MS = 100;
    /** 两次节点扫描之间的最小间隔：WCC/滚动事件密集时合并，避免一秒上百次 scan。 */
    private static final long MIN_SCAN_INTERVAL_MS = 120;
    /** 最后一次竖向滚动后至少安静这么久才重新画：与 100ms settle 对齐，缩短 hide→draw 空白。 */
    private static final long SCROLL_QUIET_MS = SETTLE_DEBOUNCE_MS;
    /** 目标 App 刚还是活动窗口时，短暂读到的系统窗口/过渡态不算「离开」，不乱藏。 */
    private static final long TARGET_TOP_GRACE_MS = 400;
    /** 窗口变化后延迟多久再判定一次（弹窗一闪不杀会话）。 */
    private static final int RECHECK_MS = 1200;
    /** 目标 App 的可见窗口持续消失多久才结束会话（通知栏停留不应算「离开」）。 */
    private static final int TARGET_ABSENT_END_MS = 10000;
    /** 通知栏/系统面板展开时目标窗口可能从 getWindows() 消失：最近这么久见过目标就不计消失。 */
    private static final int SYSTEM_SURFACE_GRACE_MS = 10000;
    /** 回桌面（launcher 在前台）停留多久才结束会话。 */
    private static final int HOME_END_MS = 5000;
    /** 另一个普通 App 在前台、且目标 App 已无可见窗口，持续多久才结束会话。 */
    private static final int FOREIGN_END_MS = 5000;
    /** getWindows() 拿不到时的兜底：最多等这么久，仍拿不到才结束。 */
    private static final int WINDOWS_MISSING_END_MS = 5000;
    /** 会话启动后的宽限期：此时前台可能还是亮点自己的检查页，不能当成「用户返回」。 */
    private static final long SESSION_START_GRACE_MS = 1500;
    private static final int GRAY_COLOR = 0x8C808080;
    private static final int AMBER = 0xFFE8A33D;
    private static final int MAX_UNKNOWN_RETRIES = 3;
    /** 详情页 watchdog 间隔：相关列表消失/切评论 tab 时 150ms 内清旧帧（初代同值）。 */
    private static final int DETAIL_WATCHDOG_MS = 150;

    private WindowManager windowManager;
    private OverlayView overlayView;
    /** 返回气泡：服务自己的第二个 accessibility overlay，后添加，位于主覆盖层之上。 */
    private TextView bubbleView;
    private WindowManager.LayoutParams bubbleLp;
    private boolean bubbleShown;
    private long bubbleStartedAt;
    private final Runnable bubbleTick = new Runnable() {
        @Override
        public void run() {
            if (bubbleView == null || mainHandler == null) return;
            bubbleView.setText(BubbleClock.text(SystemClock.elapsedRealtime() - bubbleStartedAt));
            mainHandler.postDelayed(this, 1000);
        }
    };
    private Handler mainHandler;
    private HandlerThread workerThread;
    private Handler workerHandler;
    private HandlerThread quickThread;
    private Handler quickHandler;
    private HandlerThread classifierThread;
    private Handler classifierHandler;

    private int screenW = 1080;
    private int screenH = 2354;
    private int sysNavTop = 2222;

    // 主线程状态
    private final FeedState feedState = new FeedState(SETTLE_DEBOUNCE_MS);
    private LayoutSnapshot currentLayout;
    private boolean probeRunning;
    private boolean probeAgain;
    private boolean destroyed;
    private long eventSerial;
    private long scanSerial;
    private boolean classificationRunning;
    private String classifiedKey = "";
    /** 上次判断使用的 ruleVersion：focus/avoid/规则正文变化时作废内存缓存并重判。 */
    private int lastRuleVersion = -1;
    private long nextClassificationAt;
    private int unknownRetries;
    private String drawnRenderFp = "";
    private int drawnFirstTop = Integer.MIN_VALUE;
    private long lastPublishAt;
    private long lastScanAt;
    /** 滚动中预取分类的节流：同一屏/单飞之外，最多约 3 次/秒。 */
    private long lastPrefetchAt;
    private boolean scanQueued;
    private long lastScrollAt;
    private long lastTargetTopAt;
    /** 目标 App 曾被系统面板/别的窗口遮挡：回到目标时只重新扫描，不恢复旧帧。 */
    private boolean targetObscured;
    /** 指纹 → 判断结论（只存已判出的，UNKNOWN 不入表）；换一批可见卡也能直接复用。 */
    private final Map<String, Integer> classByFp = new LinkedHashMap<>();
    private volatile boolean inTarget;
    private volatile boolean inFeed;
    /** B站 视频详情页：只画「相关推荐」列表，播放器/标题/评论区都不画。 */
    private volatile boolean inDetail;
    private long watchdogStart;
    private volatile boolean pageKnown;
    private int lastWindowId = -1;
    private boolean feedVisibleLogged;
    private boolean receiversRegistered;
    /** 仅调试用的测试控制通道；BuildConfig.DEBUG 为 false 时永远是 null（发行包不注册）。 */
    private TestControlReceiver testReceiver;
    private boolean quickCheckRunning;
    // 会话判定的计时（全部主线程读写）
    private long targetAbsentSince;
    private long windowsMissingSince;
    private long foreignTopSince;
    private long launcherTopSince;
    /** 本次会话里目标 App 是否已经露过面；没露面说明还在冷启动，别按「离开」处理。 */
    private boolean targetSeen;
    /** 最近一次确认目标 App 还在（窗口在列表里或前台是目标）的时刻，用于系统面板宽限。 */
    private long lastTargetSeenAt;
    private boolean recheckPending;
    private Set<String> launcherPackages;
    private final Map<String, int[]> classifications = new LinkedHashMap<>();

    private KeyguardManager keyguardManager;

    private final Runnable settleRunnable = this::requestProbe;
    private final Runnable initialSettleRunnable = this::requestProbe;
    private final Runnable recheckRunnable = () -> {
        recheckPending = false;
        checkActiveWindow();
    };

    private boolean overlayShown() {
        return overlayView != null && overlayView.hasVisible();
    }

    // ---------- 返回气泡（service 自己的 accessibility overlay，位于主覆盖层之上） ----------

    /** 创建气泡视图/窗口参数；不主动显示。 */
    private void ensureBubble() {
        if (bubbleView != null || mainHandler == null) return;
        bubbleView = new TextView(this);
        bubbleView.setText(BubbleClock.text(0));
        bubbleView.setTextColor(Color.WHITE);
        bubbleView.setTextSize(12);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFFDF5129);
        bg.setCornerRadius(80);
        bubbleView.setBackground(bg);
        float density = getResources().getDisplayMetrics().density;
        bubbleView.setPadding((int) (12 * density), (int) (10 * density),
                (int) (12 * density), (int) (10 * density));
        bubbleView.setElevation(8);
        bubbleView.setClickable(true);
        bubbleLp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        bubbleLp.gravity = Gravity.START | Gravity.CENTER_VERTICAL;
        bubbleLp.x = 0;
        bubbleLp.y = 0;
        bubbleLp.setTitle("highlight-bubble");
        bubbleView.setOnTouchListener(new View.OnTouchListener() {
            float downY;
            int startY;
            boolean moved;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downY = e.getRawY();
                        startY = bubbleLp == null ? 0 : bubbleLp.y;
                        moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (bubbleLp == null || windowManager == null) return true;
                        int dy = (int) (e.getRawY() - downY);
                        if (Math.abs(dy) > 12) moved = true;
                        bubbleLp.y = startY + dy;
                        try {
                            windowManager.updateViewLayout(bubbleView, bubbleLp);
                        } catch (Throwable ignored) {
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!moved) openMainActivity();
                        return true;
                    default:
                        return false;
                }
            }
        });
    }

    /** 会话 active 时显示；已显示则只刷新计时，不重置 startedAt。 */
    private void showBubbleIfNeeded() {
        if (destroyed || !HighlightSession.isActive() || windowManager == null) return;
        if (bubbleShown) return;
        if (bubbleStartedAt == 0) bubbleStartedAt = SystemClock.elapsedRealtime();
        ensureBubble();
        if (bubbleView == null || bubbleLp == null) return;
        try {
            // 主 overlay 在 onServiceConnected 先 add；这里后 add，层级更高。
            windowManager.addView(bubbleView, bubbleLp);
            bubbleShown = true;
        } catch (Throwable t) {
            bubbleShown = false;
            Log.w(TAG, "bubble-add-failed " + t);
            return;
        }
        mainHandler.removeCallbacks(bubbleTick);
        bubbleTick.run();
    }

    /** 离开目标 App 只隐藏；startedAt/计时继续；回到目标再 add 回来。 */
    private void setBubbleVisible(boolean visible) {
        if (visible) {
            showBubbleIfNeeded();
            return;
        }
        if (mainHandler != null) mainHandler.removeCallbacks(bubbleTick);
        if (bubbleShown && bubbleView != null && windowManager != null) {
            try {
                windowManager.removeView(bubbleView);
            } catch (Throwable ignored) {
            }
        }
        bubbleShown = false;
    }

    /** 真正结束会话/服务销毁：移除窗口并释放视图。 */
    private void stopBubble() {
        if (mainHandler != null) mainHandler.removeCallbacks(bubbleTick);
        if (bubbleShown && bubbleView != null && windowManager != null) {
            try {
                windowManager.removeView(bubbleView);
            } catch (Throwable ignored) {
            }
        }
        bubbleShown = false;
        bubbleView = null;
        bubbleLp = null;
        bubbleStartedAt = 0;
    }

    private void openMainActivity() {
        try {
            Intent i = new Intent(this, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            startActivity(i);
        } catch (Throwable ignored) {
        }
    }

    /** 桌面（HOME）包名：用 PackageManager 解析，不硬编码任何 ROM 的 launcher 包名。 */
    private boolean isLauncherPackage(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        if (launcherPackages == null) {
            Set<String> set = new HashSet<>();
            try {
                Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
                for (ResolveInfo ri : getPackageManager().queryIntentActivities(home, 0)) {
                    if (ri.activityInfo != null && ri.activityInfo.packageName != null) {
                        set.add(ri.activityInfo.packageName);
                    }
                }
            } catch (Throwable ignored) {
            }
            launcherPackages = set;
        }
        return launcherPackages.contains(pkg);
    }

    /**
     * 系统面板宿主：通知栏/快捷开关/音量面板都由 systemui 绘制。它们有时被报成
     * TYPE_APPLICATION，若当成「切到别的 App」会在 FOREIGN_END_MS 后结束会话（F7-02），
     * 所以按系统面板处理：只隐藏、不结束。
     */
    private static boolean isSystemPanelPackage(String pkg) {
        return "com.android.systemui".equals(pkg) || "com.miui.systemui".equals(pkg);
    }

    private String targetPackage() {
        String platform = HighlightSession.activePlatform();
        if ("bili".equals(platform)) return "tv.danmaku.bili";
        if ("xhs".equals(platform)) return "com.xingin.xhs";
        return null;
    }

    /** B站 详情页跨扫描状态（初代 cachedDetailRv 的移植）：服务持有，扫描与 watchdog 共用。 */
    private final BiliAdapter.DetailState biliDetailState = new BiliAdapter.DetailState();

    private HighlightAdapter adapterFor(String platform) {
        if ("bili".equals(platform)) {
            return new BiliAdapter(screenW, screenH, sysNavTop, inDetail, biliDetailState);
        }
        if ("xhs".equals(platform)) {
            return new XhsAdapter(screenW, screenH, sysNavTop,
                    getResources().getDisplayMetrics().density);
        }
        return null;
    }

    // ---------- 生命周期 ----------

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        connected = true;
        destroyed = false;
        keyguardManager = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        mainHandler = new Handler(getMainLooper());
        workerThread = new HandlerThread("hl-scan");
        workerThread.start();
        workerHandler = new Handler(workerThread.getLooper());
        quickThread = new HandlerThread("hl-quick");
        quickThread.start();
        quickHandler = new Handler(quickThread.getLooper());
        classifierThread = new HandlerThread("hl-judge");
        classifierThread.start();
        classifierHandler = new Handler(classifierThread.getLooper());

        windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        Rect bounds = windowManager.getCurrentWindowMetrics().getBounds();
        screenW = bounds.width();
        screenH = bounds.height();
        int navId = getResources().getIdentifier("navigation_bar_height", "dimen", "android");
        int navPx = navId > 0 ? getResources().getDimensionPixelSize(navId) : 0;
        sysNavTop = screenH - navPx;

        overlayView = new OverlayView(this);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                screenW, screenH,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = 0;
        lp.y = 0;
        lp.setTitle("highlight-overlay");
        try {
            windowManager.addView(overlayView, lp);
            Log.i(TAG, "overlay-added " + screenW + "x" + screenH + " sysNavTop=" + sysNavTop);
        } catch (Throwable t) {
            overlayView = null;
            Log.w(TAG, "overlay-failed " + t);
        }

        if (!receiversRegistered) {
            IntentFilter sessionFilter = new IntentFilter(HighlightSession.ACTION_CHANGED);
            IntentFilter screenFilter = new IntentFilter();
            screenFilter.addAction(Intent.ACTION_SCREEN_OFF);
            screenFilter.addAction(Intent.ACTION_USER_PRESENT);
            IntentFilter styleFilter = new IntentFilter(ACTION_STYLE_CHANGED);
            try {
                if (Build.VERSION.SDK_INT >= 33) {
                    registerReceiver(sessionReceiver, sessionFilter, Context.RECEIVER_NOT_EXPORTED);
                    registerReceiver(screenReceiver, screenFilter, Context.RECEIVER_NOT_EXPORTED);
                    registerReceiver(styleReceiver, styleFilter, Context.RECEIVER_NOT_EXPORTED);
                } else {
                    registerReceiver(sessionReceiver, sessionFilter);
                    registerReceiver(screenReceiver, screenFilter);
                    registerReceiver(styleReceiver, styleFilter);
                }
                receiversRegistered = true;
            } catch (Throwable t) {
                Log.w(TAG, "receiver-failed " + t);
            }
        }
        // 仅调试用，发行包不启用：用于在没有 adb input 权限的测试机上驱动 App。
        // 广播包内自带 exported 注册（API 33+ 需显式 RECEIVER_EXPORTED），调用方为 adb shell，
        // 因此不设 android:permission；release 里 BuildConfig.DEBUG=false，整段跳过。
        if (BuildConfig.DEBUG && testReceiver == null) {
            try {
                TestControlReceiver receiver = new TestControlReceiver(this);
                if (Build.VERSION.SDK_INT >= 33) {
                    registerReceiver(receiver, TestControlReceiver.filter(),
                            Context.RECEIVER_EXPORTED);
                } else {
                    registerReceiver(receiver, TestControlReceiver.filter());
                }
                testReceiver = receiver;
                Log.i("HLTEST", "test-channel registered");
            } catch (Throwable t) {
                testReceiver = null;
                Log.w("HLTEST", "test-channel failed " + t);
            }
        }
        mainHandler.postDelayed(windowPoll, 500);
        Log.i(TAG, "service-connected session=" + HighlightSession.isActive()
                + " platform=" + HighlightSession.activePlatform()
                + " key=" + (SecureStore.getKey(this) == null ? "missing" : "saved"));
        if (HighlightSession.isActive()) {
            startSession();
        }
    }

    @Override
    public boolean onUnbind(Intent intent) {
        connected = false;
        boolean wasActive = HighlightSession.isActive();
        if (wasActive) SessionNotice.showAccessibility(this);
        if (wasActive) HighlightSession.stop(this);
        teardown("unbind");
        return super.onUnbind(intent);
    }

    @Override
    public void onInterrupt() {
        Log.i(TAG, "service-interrupted");
    }

    @Override
    public void onDestroy() {
        connected = false;
        boolean wasActive = HighlightSession.isActive();
        if (wasActive) SessionNotice.showAccessibility(this);
        if (wasActive) HighlightSession.stop(this);
        destroyed = true;
        teardown("destroy");
        try {
            unregisterReceiver(sessionReceiver);
        } catch (Throwable ignored) {
        }
        try {
            unregisterReceiver(screenReceiver);
        } catch (Throwable ignored) {
        }
        try {
            unregisterReceiver(styleReceiver);
        } catch (Throwable ignored) {
        }
        // 仅调试用的测试通道；release 里 testReceiver 恒为 null。
        if (testReceiver != null) {
            try {
                unregisterReceiver(testReceiver);
            } catch (Throwable ignored) {
            }
            testReceiver = null;
        }
        receiversRegistered = false;
        feedState.invalidate();
        if (overlayView != null && windowManager != null) {
            try {
                windowManager.removeView(overlayView);
            } catch (Throwable ignored) {
            }
            overlayView = null;
        }
        if (mainHandler != null) mainHandler.removeCallbacksAndMessages(null);
        if (workerThread != null) workerThread.quitSafely();
        if (quickThread != null) quickThread.quitSafely();
        if (classifierThread != null) classifierThread.quitSafely();
        Log.i(TAG, "service-destroyed");
        super.onDestroy();
    }

    // ---------- 会话 ----------

    private void startSession() {
        SessionNotice.cancel(this);
        eventSerial++;
        cancelRecheck();
        resetPresenceTimers();
        targetSeen = false;
        targetObscured = false;
        lastTargetSeenAt = SystemClock.uptimeMillis();
        currentLayout = null;
        inTarget = false;
        inFeed = false;
        inDetail = false;
        pageKnown = false;
        stopDetailWatchdog();
        biliDetailState.rv = null;
        unknownRetries = 0;
        nextClassificationAt = 0;
        lastPrefetchAt = 0;
        feedState.invalidate();
        hideFor("session-start");
        stopBubble();
        bubbleStartedAt = SystemClock.elapsedRealtime();
        showBubbleIfNeeded();
        Log.i(TAG, "session-start platform=" + HighlightSession.activePlatform());
        mainHandler.removeCallbacks(initialSettleRunnable);
        mainHandler.postDelayed(this::checkActiveWindow, 150);
        mainHandler.postDelayed(this::checkActiveWindow, 700);
        mainHandler.postDelayed(this::checkActiveWindow, 1300);
        mainHandler.postDelayed(initialSettleRunnable, 900);
    }

    private void endSession(String reason) {
        Log.i(TAG, "session-end reason=" + reason);
        stopBubble();
        // 只要会话仍在、且属于用户离开目标 App 的结束原因就发低打扰通知；
        // 回亮点、锁屏、息屏、系统面板都算已知/临时状态，不发。
        if (shouldNotifyLeave(reason)) {
            String label = "xhs".equals(HighlightSession.activePlatform()) ? "小红书" : "B站";
            SessionNotice.show(this, label);
        }
        cancelRecheck();
        HighlightSession.stop(this);
        teardown(reason);
    }

    private static boolean shouldNotifyLeave(String reason) {
        return "home".equals(reason)
                || "target-gone".equals(reason)
                || "windows-unavailable".equals(reason)
                || (reason != null && reason.startsWith("active="));
    }

    private void teardown(String reason) {
        stopBubble();
        if (!inTarget && !overlayShown() && currentLayout == null && !inFeed) return;
        inTarget = false;
        inFeed = false;
        inDetail = false;
        pageKnown = false;
        currentLayout = null;
        targetObscured = false;
        stopDetailWatchdog();
        biliDetailState.rv = null;
        eventSerial++;
        feedState.invalidate();
        hideFor("teardown:" + reason);
        mainHandler.removeCallbacks(settleRunnable);
        mainHandler.removeCallbacks(initialSettleRunnable);
    }

    private final BroadcastReceiver sessionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (destroyed) return;
            if (HighlightSession.isActive()) startSession();
            else teardown("session-stop");
        }
    };

    /** 样式参数变了：丢掉当前帧，按新参数重扫重画；不在目标 App 时下个 tick 也会读到新值。 */
    private final BroadcastReceiver styleReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (destroyed) return;
            Log.i(TAG, "style-changed dim=" + Prefs.dimEnabled(HighlightService.this)
                    + " opacity=" + Prefs.dimOpacity(HighlightService.this)
                    + " glow=" + Prefs.glowEnabled(HighlightService.this)
                    + " strength=" + Prefs.glowStrength(HighlightService.this));
            if (!HighlightSession.isActive() || !inTarget) return;
            invalidatePage("style-changed");
            requestProbe();
        }
    };

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                if (HighlightSession.isActive()) endSession("screen-off");
            } else if (Intent.ACTION_USER_PRESENT.equals(action)) {
                if (HighlightSession.isActive()) checkActiveWindow();
            }
        }
    };

    // ---------- 前景核实 ----------

    private final Runnable windowPoll = new Runnable() {
        @Override
        public void run() {
            try {
                if (HighlightSession.isActive() && !destroyed) checkActiveWindow();
            } catch (Throwable ignored) {
            }
            if (mainHandler != null) mainHandler.postDelayed(this, 500);
        }
    };

    /**
     * 会话结束时机只看「窗口类型 + 目标 App 还有没有可见窗口」，不维护任何包名清单：
     * - 顶层是目标 App 的应用窗口 → 恢复绘制；
     * - 顶层不是应用窗口（通知栏 / 输入法 / 系统弹窗 / 过渡态 / 无障碍浮层）→ 只隐藏，等复核；
     *   最近 10s 见过目标时不计「目标消失」，通知栏收起后目标回来即恢复绘制；
     * - 回到亮点自己的应用窗口 → 用户明确返回，结束；启动宽限期内先放过；
     * - 另一个普通 App 在前台且目标 App 已没有可见窗口，持续 ~5s → 结束（离开到别的 App）；
     * - 回桌面停留 ~5s → 结束；
     * - 目标 App 的可见窗口持续消失 ~10s（且不在系统面板宽限内）→ 结束（兜底）；
     * - getWindows() 拿不到 → 先隐藏，最多等 5s，仍拿不到才结束。
     * MIUI/HyperOS 的启动确认/权限宿主会短暂变成活动窗口（窗口类型同样是 APPLICATION），
     * 所以非目标窗口一律先 hide + 延迟复核，不能一出现就 endSession。
     */
    private void checkActiveWindow() {
        if (destroyed) return;
        if (!HighlightSession.isActive()) {
            if (inTarget) teardown("inactive");
            return;
        }
        try {
            if (keyguardManager != null && keyguardManager.isKeyguardLocked()) {
                endSession("keyguard");
                return;
            }
        } catch (Throwable ignored) {
        }

        long now = SystemClock.uptimeMillis();
        List<AccessibilityWindowInfo> windows;
        try {
            windows = getWindows();
        } catch (Throwable t) {
            windows = null;
        }
        if (windows == null || windows.isEmpty()) {
            // 窗口列表暂时拿不到（过渡动画里也会发生）：先别结束，最多等 5s。
            hideFor("windows-unavailable");
            targetObscured = true;
            if (windowsMissingSince == 0) windowsMissingSince = now;
            if (now - windowsMissingSince >= WINDOWS_MISSING_END_MS && !inStartGrace(now)) {
                endSession("windows-unavailable");
                return;
            }
            scheduleRecheck();
            return;
        }
        windowsMissingSince = 0;

        String target = targetPackage();
        if (target == null) return;

        boolean targetPresent = hasTargetWindow(windows, target);
        if (targetPresent) {
            targetSeen = true;
            lastTargetSeenAt = now;
            targetAbsentSince = 0;
        } else if (targetSeen && targetAbsentSince == 0) {
            targetAbsentSince = now;
        }

        AccessibilityWindowInfo top = pickTopWindow(windows);
        int topType = -1;
        String topPkg = "";
        if (top != null) {
            try {
                topType = top.getType();
            } catch (Throwable ignored) {
            }
            if (topType == AccessibilityWindowInfo.TYPE_APPLICATION) topPkg = windowPackage(top);
        }

        if (topType == AccessibilityWindowInfo.TYPE_APPLICATION && target.equals(topPkg)) {
            // 目标 App 在前台：清计时、恢复绘制（从系统窗口/弹窗回来也走这里）。
            resetPresenceTimers();
            cancelRecheck();
            lastTargetTopAt = now;
            enterTarget(top);
            return;
        }

        if (topType == AccessibilityWindowInfo.TYPE_APPLICATION
                && getPackageName().equals(topPkg)) {
            // 回到亮点自己的窗口 = 用户明确返回，立刻结束（启动宽限期内例外）。
            hideFor("own-app");
            targetObscured = true;
            if (!inStartGrace(now)) {
                endSession("own-app");
                return;
            }
            scheduleRecheck();
            return;
        }

        if (topType == AccessibilityWindowInfo.TYPE_APPLICATION && !topPkg.isEmpty()
                && !isSystemPanelPackage(topPkg)) {
            // 另一个应用窗口到前台：只隐藏，按持续时间复核。
            if (isLauncherPackage(topPkg)) {
                if (launcherTopSince == 0) launcherTopSince = now;
                foreignTopSince = 0;
                hideFor("home");
                targetObscured = true;
            } else {
                if (foreignTopSince == 0) foreignTopSince = now;
                launcherTopSince = 0;
                hideFor("foreign-app");
                targetObscured = true;
            }
        } else {
            // 顶层不是应用窗口（通知栏 / 状态栏 / 系统面板 / 输入法），或 systemui 自己的窗口：
            // 只隐藏，不结束会话。但目标 App 刚还是活动窗口时，这类读数多半是过渡态，
            // 先不藏 —— 否则每 500ms 轮询里一次瞬时误读就会让灰罩「闪一下」。
            if (now - lastTargetTopAt >= TARGET_TOP_GRACE_MS) {
                hideFor("non-app-window");
                targetObscured = true;
            }
            foreignTopSince = 0;
            launcherTopSince = 0;
            // 通知栏展开时目标窗口会从 getWindows() 列表消失，这不是「真的离开」：
            // 最近 SYSTEM_SURFACE_GRACE_MS 内还见过目标，就把「消失计时」清零；
            // 收起后目标回来走上面的恢复分支；超过宽限仍没有目标才由 target-gone 兜底。
            if (targetSeen && lastTargetSeenAt != 0
                    && now - lastTargetSeenAt < SYSTEM_SURFACE_GRACE_MS) {
                targetAbsentSince = 0;
            }
        }

        if (!inStartGrace(now)) {
            // 目标 App 窗口还在时，另一个应用窗口压在上面多半是厂商弹窗/启动确认
            // （它也是 APPLICATION 窗口），只隐藏等复核，不据此结束会话。
            // 目标还没露过面时属于冷启动，同样只隐藏，交给「目标窗口消失 10s」兜底。
            if (targetSeen && !targetPresent && foreignTopSince != 0
                    && now - foreignTopSince >= FOREIGN_END_MS) {
                endSession("active=" + topPkg);
                return;
            }
            if (launcherTopSince != 0 && now - launcherTopSince >= HOME_END_MS) {
                endSession("home");
                return;
            }
            if (targetAbsentSince != 0 && now - targetAbsentSince >= TARGET_ABSENT_END_MS) {
                endSession("target-gone");
                return;
            }
        }
        scheduleRecheck();
    }

    private static boolean inStartGrace(long now) {
        return now - HighlightSession.startedAt() < SESSION_START_GRACE_MS;
    }

    private void resetPresenceTimers() {
        targetAbsentSince = 0;
        windowsMissingSince = 0;
        foreignTopSince = 0;
        launcherTopSince = 0;
    }

    /**
     * 目标 App 是否还有窗口。真机（小米 13 / Android 15）实测：切到桌面或别的 App 后，
     * 目标窗口会直接从 getWindows() 列表消失，只剩状态栏/导航栏/launcher；
     * 只有厂商弹窗压在上面时目标窗口仍在列表里。因此「在列表里」即可视为「还在」。
     */
    private boolean hasTargetWindow(List<AccessibilityWindowInfo> windows, String target) {
        for (AccessibilityWindowInfo w : windows) {
            try {
                if (w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                if (target.equals(windowPackage(w))) return true;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    /**
     * 顶层窗口 = 活动窗口（退化为聚焦窗口）。忽略无障碍浮层（自己的覆盖层）。
     * 不能按 layer 取最高层：状态栏/导航栏是常驻 TYPE_SYSTEM 且 layer 更高，
     * 但它们不接收输入、isActive 为 false，永远不该算「用户切到了别的窗口」。
     */
    private static AccessibilityWindowInfo pickTopWindow(List<AccessibilityWindowInfo> windows) {
        AccessibilityWindowInfo first = null;
        for (AccessibilityWindowInfo w : windows) {
            try {
                if (w.getType() == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue;
            } catch (Throwable t) {
                continue;
            }
            if (first == null) first = w;
            try {
                if (w.isActive()) return w;
            } catch (Throwable ignored) {
            }
        }
        for (AccessibilityWindowInfo w : windows) {
            try {
                if (w.getType() == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue;
                if (w.isFocused()) return w;
            } catch (Throwable ignored) {
            }
        }
        return first;
    }

    /** 窗口变化后延迟复核：给「目标 App 回来」留出恢复窗口，也避免弹窗一闪就结束会话。 */
    private void scheduleRecheck() {
        if (recheckPending || destroyed || mainHandler == null) return;
        recheckPending = true;
        mainHandler.removeCallbacks(recheckRunnable);
        mainHandler.postDelayed(recheckRunnable, RECHECK_MS);
    }

    private void cancelRecheck() {
        if (mainHandler != null) mainHandler.removeCallbacks(recheckRunnable);
        recheckPending = false;
    }

    private String windowPackage(AccessibilityWindowInfo w) {
        AccessibilityNodeInfo root = null;
        try {
            root = w.getRoot();
        } catch (Throwable ignored) {
        }
        if (root == null) return "";
        try {
            CharSequence cs = root.getPackageName();
            return cs == null ? "" : cs.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    /** B站 视频详情页的 Activity 类名：8.90 是 UnitedBizDetailsActivity，旧版是 VideoDetailsActivity。 */
    private static boolean isBiliDetail(String cls) {
        return cls.contains("DetailsActivity") || cls.contains("DetailActivity")
                || cls.contains("VideoDetail");
    }

    private void enterTarget(AccessibilityWindowInfo active) {
        lastTargetSeenAt = SystemClock.uptimeMillis();
        // 回到目标 App：会话还在就把气泡重新显示出来（沿用原 startedAt，计时不重置）。
        if (HighlightSession.isActive()) showBubbleIfNeeded();
        int wid = -1;
        try {
            wid = active.getId();
        } catch (Throwable ignored) {
        }
        boolean switched = wid != -1 && lastWindowId != -1 && wid != lastWindowId;
        lastWindowId = wid;
        if (!inTarget) {
            inTarget = true;
            targetObscured = false;
            Log.i(TAG, "target-active pkg=" + targetPackage() + " wid=" + wid);
            feedState.invalidate();
            requestProbe();
            mainHandler.postDelayed(initialSettleRunnable, 500);
            return;
        }
        if (switched) {
            targetObscured = false;
            invalidatePage("window-switch");
            requestProbe();
            return;
        }
        if (targetObscured) {
            // 从通知栏/系统面板/别的 App 回来：走一次全新扫描，绝不把隐藏前的旧帧原样画回。
            // 初代 GrayService.checkActiveWindow() 没有自动 restore 分支；500ms 轮询只负责
            // 确认目标在前台，绘制只由扫描/settle 触发（否则滚动/刷新刚 hide 的旧帧会被画回）。
            targetObscured = false;
            Log.i(TAG, "target-visible rescan reason=obscured");
            invalidatePage("target-return");
            requestProbe();
            mainHandler.postDelayed(initialSettleRunnable, 300);
        }
    }

    // ---------- 事件 ----------

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || destroyed) return;
        int type = event.getEventType();
        if (type == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            checkActiveWindow();
            return;
        }
        CharSequence own = event.getPackageName();
        if (own != null && getPackageName().contentEquals(own)) {
            // 覆盖层自己也会发事件：绝不能用它判断会话或清空绘制。
            return;
        }
        if (!HighlightSession.isActive()) return;
        String target = targetPackage();
        if (target == null) return;
        CharSequence pkgCs = event.getPackageName();
        String pkg = pkgCs == null ? "" : pkgCs.toString();
        if (!target.equals(pkg)) return;
        if (!inTarget) checkActiveWindow();
        if (!inTarget) return;
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            String cls = String.valueOf(event.getClassName());
            if ("xhs".equals(HighlightSession.activePlatform())) {
                // 小红书首页是 IndexActivityV2；详情/搜索页由 XhsAdapter 的「首页选中」判定兜底，
                // 这里不按类名一刀切，否则会把首页自己判成「其他页面」。
                boolean transition = !inFeed || !pageKnown;
                inFeed = true;
                pageKnown = true;
                if (transition) invalidatePage("xhs-page");
                Log.i(TAG, "page=xhs cls=" + shortName(cls));
                requestProbe();
            } else if (cls.contains("MainActivity")) {
                // 回首页：清掉详情页状态，重新按首页逻辑扫描，不沿用详情页的列表缓存。
                boolean transition = !inFeed || inDetail || !pageKnown;
                inFeed = true;
                inDetail = false;
                pageKnown = true;
                stopDetailWatchdog();
                biliDetailState.rv = null;
                if (transition) invalidatePage("feed-enter");
                Log.i(TAG, "page=feed cls=" + shortName(cls));
                requestProbe();
            } else if (isBiliDetail(cls)) {
                // B站 视频详情页：相关推荐也画；播放器/标题/评论不碰。
                boolean transition = !inDetail || !pageKnown;
                inDetail = true;
                inFeed = false;
                pageKnown = true;
                if (transition) {
                    // 换了视频/从首页进来：上一支视频的相关列表节点立即作废，重新发现。
                    biliDetailState.rv = null;
                    invalidatePage("detail");
                }
                startDetailWatchdog();
                Log.i(TAG, "page=detail cls=" + shortName(cls));
                requestProbe();
            } else if (cls.contains("Activity")) {
                // 搜索页 / 直播页 / 其他页：不画，但会话不结束，返回后重绘。
                inFeed = false;
                inDetail = false;
                pageKnown = true;
                stopDetailWatchdog();
                biliDetailState.rv = null;
                invalidatePage("other-page");
                Log.i(TAG, "page=other cls=" + shortName(cls));
            }
            return;
        }
        if (pageKnown && !inFeed && !inDetail) return;
        eventSerial++;
        if (type == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            int dx = event.getScrollDeltaX();
            int dy = event.getScrollDeltaY();
            boolean carousel = dx != 0 && dy == 0;
            if (!carousel) {
                lastScrollAt = SystemClock.uptimeMillis();
                feedState.motion(lastScrollAt);
                hideFor("scroll");
            }
        }
        if (overlayShown() && (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                || type == AccessibilityEvent.TYPE_VIEW_SCROLLED)) {
            scheduleQuickMotionCheck();
        }
        // 详情页：切「评论」tab / 相关列表消失时，事件一到就做完整可见性检查。
        if (inDetail && overlayShown()) detailHideIfNeeded("event");
        requestProbe();
    }

    /** 滚动后的残影：只看首张完全可见卡的封面 top，别用 bottom / 末卡（pitfalls #37）。 */
    private void scheduleQuickMotionCheck() {
        if (quickCheckRunning || quickHandler == null || drawnFirstTop == Integer.MIN_VALUE) return;
        quickCheckRunning = true;
        quickHandler.post(new Runnable() {
            @Override
            public void run() {
                int firstTop = Integer.MIN_VALUE;
                try {
                    if (inDetail) {
                        // 详情页只在缓存的「相关推荐」列表上读首卡 top，不重新做整树发现，
                        // 避免快速运动线程和扫描线程同时改 DetailState（初代 firstDetailCardTop 同口径）。
                        BiliAdapter a = new BiliAdapter(screenW, screenH, sysNavTop, true, biliDetailState);
                        firstTop = a.cachedDetailFirstTop();
                    } else {
                        AccessibilityNodeInfo root = getRootInActiveWindow();
                        HighlightAdapter adapter = adapterFor(HighlightSession.activePlatform());
                        if (root != null && adapter != null) {
                            List<CardTarget> cards = adapter.findCards(root);
                            for (CardTarget c : cards) {
                                if (c.fullyVisible) {
                                    firstTop = c.cover.top;
                                    break;
                                }
                            }
                        }
                    }
                } catch (Throwable ignored) {
                }
                final int ft = firstTop;
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        quickCheckRunning = false;
                        if (destroyed || !inTarget) return;
                        if (SystemClock.uptimeMillis() - lastPublishAt < 200) return;
                        if (ft != Integer.MIN_VALUE && drawnFirstTop != Integer.MIN_VALUE
                                && Math.abs(drawnFirstTop - ft) > 8) {
                            feedState.motion(SystemClock.uptimeMillis());
                            hideFor("quick-motion");
                        }
                    }
                });
            }
        });
    }

    private void hideFor(String reason) {
        if (overlayShown()) Log.i(TAG, "hide reason=" + reason);
        // 离开目标 App 的当帧只隐藏气泡（服务/计时继续）；5s 内回目标会重新显示并接着计时。
        if ("home".equals(reason) || "foreign-app".equals(reason) || "own-app".equals(reason)) {
            setBubbleVisible(false);
        }
        if (overlayView != null) overlayView.hideNow();
        drawnRenderFp = "";
        drawnFirstTop = Integer.MIN_VALUE;
    }

    private void invalidatePage(String reason) {
        feedState.invalidate();
        eventSerial++;
        currentLayout = null;
        hideFor(reason);
        if (mainHandler != null) mainHandler.removeCallbacks(settleRunnable);
    }

    // ---------- 详情页可见性（初代 detailWatchdog / detailHideIfNeeded 的移植） ----------

    /** 150ms 轮询：评论 tab 选中、相关列表移出屏幕/被别的列表替换时立即清旧帧。 */
    private final Runnable detailWatchdog = new Runnable() {
        @Override
        public void run() {
            if (destroyed || !inDetail) return;
            if (overlayShown()) {
                String reason = detailProblem(true);
                if (reason != null) {
                    Log.i(TAG, "detail-hide reason=" + reason + " src=watchdog ms="
                            + (SystemClock.uptimeMillis() - watchdogStart));
                    hideFor("detail-hide:" + reason);
                }
            }
            if (mainHandler != null) mainHandler.postDelayed(this, DETAIL_WATCHDOG_MS);
        }
    };

    private void startDetailWatchdog() {
        if (mainHandler == null) return;
        mainHandler.removeCallbacks(detailWatchdog);
        watchdogStart = SystemClock.uptimeMillis();
        mainHandler.postDelayed(detailWatchdog, DETAIL_WATCHDOG_MS);
    }

    private void stopDetailWatchdog() {
        if (mainHandler != null) mainHandler.removeCallbacks(detailWatchdog);
    }

    /**
     * 事件驱动的完整检查（跑在扫描线程）：切评论 tab / 换滚动容器时 WCC 一响就查，
     * 比 150ms 的 cheap watchdog 更早发现，也避免把两次重 BFS 放在主线程。
     */
    private void detailHideIfNeeded(final String src) {
        if (!inDetail || !overlayShown() || workerHandler == null) return;
        workerHandler.post(new Runnable() {
            @Override
            public void run() {
                final String reason = detailProblem(false);
                if (reason == null) return;
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (!inDetail || !overlayShown()) return;
                        Log.i(TAG, "detail-hide reason=" + reason + " src=" + src);
                        hideFor("detail-hide:" + reason);
                    }
                });
            }
        });
    }

    /** 用当前的 BiliAdapter 状态做一次详情页可见性检查；root 由调用线程现取，不缓存旧树。 */
    private String detailProblem(boolean cheap) {
        AccessibilityNodeInfo root = null;
        try {
            root = getRootInActiveWindow();
        } catch (Throwable ignored) {
        }
        BiliAdapter adapter = new BiliAdapter(screenW, screenH, sysNavTop, true, biliDetailState);
        return adapter.detailVisibilityProblem(root, cheap);
    }

    // ---------- 扫描 / 绘制 ----------

    private void requestProbe() {
        if (destroyed || !HighlightSession.isActive() || !inTarget) return;
        if (pageKnown && !inFeed && !inDetail) return;
        if (workerHandler == null) return;
        if (probeRunning) {
            probeAgain = true;
            return;
        }
        // 事件密集（WCC/滚动）时合并扫描：距上次扫描不足 120ms 就延后一次，避免一秒上百次 scan。
        long now = SystemClock.uptimeMillis();
        long wait = lastScanAt + MIN_SCAN_INTERVAL_MS - now;
        if (wait > 0) {
            if (!scanQueued) {
                scanQueued = true;
                mainHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        scanQueued = false;
                        requestProbe();
                    }
                }, wait);
            }
            return;
        }
        lastScanAt = now;
        probeRunning = true;
        final long pageEpoch = feedState.epoch();
        final long serial = eventSerial;
        final long scan = ++scanSerial;
        final boolean allowFallback = !pageKnown;
        final boolean detailAtScan = inDetail;
        workerHandler.post(new Runnable() {
            @Override
            public void run() {
                LayoutSnapshot snapshot = null;
                final boolean[] inferredDetail = {false};
                try {
                    AccessibilityNodeInfo root = getRootInActiveWindow();
                    HighlightAdapter adapter = adapterFor(HighlightSession.activePlatform());
                    if (root != null && adapter != null) {
                        snapshot = new LayoutSnapshot(adapter.findCards(root));
                        if (!detailAtScan && allowFallback && !snapshot.cards.isEmpty()
                                && adapter instanceof BiliAdapter
                                && ((BiliAdapter) adapter).usedDetailFallback()) {
                            // 会话开始时已经停在详情页、没收到页面切换事件：首页规则零卡，
                            // BiliAdapter 用详情页规则兜底找到了相关推荐（初代 walkDetailCache 回退）。
                            inferredDetail[0] = true;
                        }
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "scan-fail " + t.getClass().getSimpleName());
                }
                final LayoutSnapshot result = snapshot;
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        probeRunning = false;
                        if (destroyed) return;
                        if (pageEpoch == feedState.epoch() && inTarget && HighlightSession.isActive()) {
                            if (allowFallback && result != null && !result.cards.isEmpty()
                                    && !pageKnown) {
                                pageKnown = true;
                                if (inferredDetail[0]) {
                                    inDetail = true;
                                    inFeed = false;
                                    startDetailWatchdog();
                                    Log.i(TAG, "page-inferred detail");
                                } else {
                                    inFeed = true;
                                    Log.i(TAG, "page-inferred feed");
                                }
                            }
                            receiveLayout(result, serial, scan);
                        }
                        boolean again = probeAgain || serial != eventSerial;
                        probeAgain = false;
                        if (again) requestProbe();
                    }
                });
            }
        });
    }

    private void receiveLayout(LayoutSnapshot next, long serial, long scan) {
        long now = SystemClock.uptimeMillis();
        if (next == null) {
            boolean expired = feedState.unknown(now);
            if (expired) hideFor("layout-unavailable");
            mainHandler.removeCallbacks(settleRunnable);
            mainHandler.postDelayed(settleRunnable, SETTLE_DEBOUNCE_MS);
            return;
        }
        boolean changed = feedState.observe(next.key, now);
        LayoutSnapshot prev = currentLayout;
        currentLayout = next;
        if (changed && prev != null && !prev.cards.isEmpty() && !next.cards.isEmpty()) {
            int before = firstCardTop(prev);
            int after = firstCardTop(next);
            if (before != Integer.MIN_VALUE && after != Integer.MIN_VALUE
                    && Math.abs(after - before) > 8) {
                // 初代 receiveLayout：首卡 top 位移 >8px 说明列表重排/刷新/换了一批卡，
                // 旧帧的坐标已经贴到新布局上（日志里的 layout-moved before=450 after=630）。
                // 必须立即清掉旧帧，再走 settle 重画；不能只打日志等 300/400ms 后的新帧。
                Log.i(TAG, "layout-moved before=" + before + " after=" + after);
                hideFor("layout-change");
            }
        }
        if (next.cards.isEmpty()) {
            // 初代 receiveLayout：空布局立即清帧。加载/自动刷新/切评论 tab 时列表节点会先变空，
            // 旧帧坐标此时已经对不上新内容（标题/作者上残留灰罩），不能再等 400ms。
            // 目标 App 刚起来的 12s 内容易还在渲染，用 700ms 的重扫间隔，避免空转。
            hideFor("empty-layout");
            mainHandler.removeCallbacks(settleRunnable);
            long retry = now - HighlightSession.startedAt() < 12000
                    ? 700 : SETTLE_DEBOUNCE_MS;
            mainHandler.postDelayed(settleRunnable, retry);
            return;
        }
        // 滚动中预取：把模型延迟藏进 fling。只对 feed、非详情、非空布局；单飞由
        // classificationRunning/classifiedKey 保证，这里再加 ~300ms 节流。
        if (inFeed && !inDetail && now - lastPrefetchAt >= 300) {
            lastPrefetchAt = now;
            requestClassification(next);
        }
        long remaining = feedState.remaining(now);
        if (remaining > 0 || serial != eventSerial) {
            mainHandler.removeCallbacks(settleRunnable);
            mainHandler.postDelayed(settleRunnable, Math.max(1, remaining));
            return;
        }
        if (now - lastScrollAt < SCROLL_QUIET_MS) {
            // fling 尾段：等真正停手再画，避免「出现 100ms 又被下一次滚动藏掉」的闪。
            mainHandler.removeCallbacks(settleRunnable);
            mainHandler.postDelayed(settleRunnable,
                    Math.max(1, SCROLL_QUIET_MS - (now - lastScrollAt)));
            return;
        }
        if (!feedVisibleLogged) {
            feedVisibleLogged = true;
            Log.i(TAG, "feed-visible cards=" + next.cards.size());
        }
        // 详情页：相关列表已不可见（切评论 tab / 换滚动容器 / 被遮挡）就不画旧帧。
        if (inDetail && detailProblem(false) != null) {
            hideFor("detail-not-visible");
            return;
        }
        int[] statuses = classifications.get(next.classKey);
        String cacheSrc = statuses != null ? "memory" : "none";
        if (statuses == null) {
            // 初代 receiveLayout 在 publish 前先查 cachedThree；这里同步查本地规则 +
            // Judge 持久缓存，滚动到判过的卡立即画，不必等一轮 native-judge。
            List<String> fps = new ArrayList<>(next.cards.size());
            List<String> texts = new ArrayList<>(next.cards.size());
            for (CardTarget card : next.cards) {
                fps.add(card.fingerprint);
                texts.add(card.judgeText());
            }
            int[] cached = Judge.cachedNative(this, fps, texts);
            int known = 0;
            for (int i = 0; i < cached.length && i < next.cards.size(); i++) {
                if (cached[i] != Judge.UNKNOWN) {
                    known++;
                    classByFp.put(next.cards.get(i).fingerprint, cached[i]);
                }
            }
            if (known > 0) {
                statuses = cached;
                cacheSrc = "persistent";
            }
        }
        int known = 0;
        if (statuses != null) {
            for (int v : statuses) {
                if (v != Judge.UNKNOWN) known++;
            }
        }
        Log.i(TAG, "cache-hit n=" + known + " miss=" + (next.cards.size() - known)
                + " src=" + cacheSrc);
        // 判断还没回来时 publish 会自己保持上一帧（见 unresolved 分支），绝不先画 dim=0 的中间态。
        publish(next, statuses);
        requestClassification(next);
    }

    private void requestClassification(final LayoutSnapshot layout) {
        boolean glowOn = Prefs.glowEnabled(this);
        boolean dimOn = Prefs.dimEnabled(this);
        if (!glowOn && !dimOn) return;
        // focus/avoid 或规则正文变了：按布局/指纹的内存缓存全部作废，同一屏也要重判。
        int ruleVersion = Prefs.ruleVersion(this);
        if (ruleVersion != lastRuleVersion) {
            if (lastRuleVersion >= 0) {
                Log.i(TAG, "judge-invalidate rule-version " + lastRuleVersion + "->" + ruleVersion);
            }
            lastRuleVersion = ruleVersion;
            classifications.clear();
            classByFp.clear();
            classifiedKey = "";
            unknownRetries = 0;
            nextClassificationAt = 0;
        }
        if (classificationRunning || classifiedKey.equals(layout.classKey)) return;
        if (SystemClock.uptimeMillis() < nextClassificationAt) return;
        // 不在这里按 Key 缺失直接跳过：本地「特别关注/不想看到」不需要网络，
        // classifyNative 会先判本地命中，再决定是否发请求（初代列表侧也是本地优先）。
        if (unknownRetries >= MAX_UNKNOWN_RETRIES) return;
        final long epoch = feedState.epoch();
        final int taskVersion = ruleVersion;
        final List<String> fps = new ArrayList<>();
        final List<String> texts = new ArrayList<>();
        for (CardTarget c : layout.cards) {
            fps.add(c.fingerprint);
            texts.add(c.judgeText());
        }
        classificationRunning = true;
        classifierHandler.post(new Runnable() {
            @Override
            public void run() {
                final int[] result = Judge.classifyNative(HighlightService.this, fps, texts);
                boolean complete = true;
                for (int r : result) {
                    if (r == Judge.UNKNOWN) {
                        complete = false;
                        break;
                    }
                }
                final boolean done = complete;
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        classificationRunning = false;
                        if (destroyed) return;
                        boolean versionChanged = taskVersion != Prefs.ruleVersion(HighlightService.this);
                        if (!versionChanged && epoch == feedState.epoch() && currentLayout != null
                                && layout.classKey.equals(currentLayout.classKey)) {
                            if (classifications.size() >= 128) classifications.clear();
                            if (classByFp.size() >= 512) classByFp.clear();
                            for (int i = 0; i < result.length && i < layout.cards.size(); i++) {
                                if (result[i] != Judge.UNKNOWN) {
                                    classByFp.put(layout.cards.get(i).fingerprint, result[i]);
                                }
                            }
                            // 部分结果也先画出来：已知的发亮/灰罩立即生效，UNKNOWN 的卡先不画，
                            // 后台按 3 秒节奏重试（Unknown 不等于普通，但也不该拖住已知的部分）。
                            classifications.put(layout.classKey, result);
                            // 分类回来后不等下一次 probe：若目标屏已停稳，直接按结果重画一次。
                            // 仍在 fling/未停稳时不画，交给 settle 后的 receiveLayout 用缓存命中，
                            // 避免「画了又被下一次滚动 hide」。
                            if (inFeed && !inDetail && !targetObscured) {
                                long nowMs = SystemClock.uptimeMillis();
                                if (feedState.remaining(nowMs) <= 0
                                        && nowMs - lastScrollAt >= SCROLL_QUIET_MS) {
                                    publish(currentLayout, result);
                                }
                            }
                            if (done) {
                                classifiedKey = layout.classKey;
                                unknownRetries = 0;
                            } else {
                                unknownRetries++;
                                nextClassificationAt = SystemClock.uptimeMillis() + 3000;
                                Log.i(TAG, "judge-incomplete retry=" + unknownRetries
                                        + "/" + MAX_UNKNOWN_RETRIES);
                                mainHandler.postDelayed(initialSettleRunnable, 3000);
                            }
                            requestProbe();
                        } else {
                            Log.i(TAG, "judge-discard epoch=" + epoch
                                    + (versionChanged ? " version=" + taskVersion : ""));
                            requestProbe();
                        }
                    }
                });
            }
        });
    }

    /** 本次扫描的结论数组优先；缺失的按指纹复用之前已判出的结果。 */
    private int classOf(LayoutSnapshot layout, int[] classes, int idx) {
        int v = classes != null && idx < classes.length ? classes[idx] : Judge.UNKNOWN;
        if (v == Judge.UNKNOWN) {
            Integer fp = classByFp.get(layout.cards.get(idx).fingerprint);
            if (fp != null) v = fp;
        }
        return v;
    }

    private void publish(LayoutSnapshot layout, int[] classes) {
        if (!inTarget || destroyed) return;
        boolean glowOn = Prefs.glowEnabled(this);
        boolean dimOn = Prefs.dimEnabled(this);
        if (!glowOn && !dimOn) {
            hideFor("both-off");
            return;
        }
        int glowStrength = Prefs.glowStrength(this);
        int dimOpacity = Prefs.dimOpacity(this);
        List<RectF> dims = new ArrayList<>();
        List<RectF> glows = new ArrayList<>();
        List<RectF> glowCovers = new ArrayList<>();
        StringBuilder frame = new StringBuilder()
                .append(glowOn).append('/').append(dimOn).append('/')
                .append(glowStrength).append('/').append(dimOpacity).append('/').append(sysNavTop);
        int firstTop = Integer.MIN_VALUE;
        int unresolved = 0;
        for (int idx = 0; idx < layout.cards.size(); idx++) {
            CardTarget card = layout.cards.get(idx);
            if (!card.fullyVisible) continue;
            if (firstTop == Integer.MIN_VALUE) firstTop = card.cover.top;
            int cls = classOf(layout, classes, idx);
            if (cls == Judge.UNKNOWN) {
                unresolved++;
                continue;
            }
            if (cls == Judge.GLOW && glowOn) {
                // 初代 expandedGlow：软光位图需要「外扩后的 glow rect + 原封面 rect」。
                float w = (12f + 12f * glowStrength / 100f)
                        * getResources().getDisplayMetrics().density;
                RectF glow = expandedGlow(card.cover, w);
                if (glow == null) continue;
                RectF cover = new RectF(card.cover);
                glows.add(glow);
                glowCovers.add(cover);
                frame.append("|g").append(rectKey(glow)).append(':').append(rectKey(cover));
            } else if (cls == Judge.DIM && dimOn) {
                RectF cover = clip(card.cover);
                if (cover == null) continue;
                dims.add(cover);
                frame.append("|d").append(rectKey(cover));
            }
        }
        if (dims.isEmpty() && glows.isEmpty() && unresolved > 0) {
            // 判断结果还没回来：保持上一帧，不把覆盖层先画成空的（用户看到的「闪一下消失」）。
            Log.i(TAG, "overlay-defer n=" + layout.cards.size() + " unresolved=" + unresolved);
            return;
        }
        String fp = frame.toString();
        // 空帧连续出现算「相同」，不要在详情页/评论页把空的 show() 每 500ms 重放一遍。
        boolean same = fp.equals(drawnRenderFp)
                && (overlayShown() || (dims.isEmpty() && glows.isEmpty()));
        long now = SystemClock.uptimeMillis();
        long gap = lastPublishAt == 0 ? 0 : now - lastPublishAt;
        drawnFirstTop = firstTop;
        // 初代 publish 在 same 分支 return 之前就更新 lastPublishAt；quick-motion 的 200ms
        // 抑制依赖它，否则 same=true 时 lastPublishAt 会一直停在上一帧真正重画之前。
        lastPublishAt = now;
        if (same) {
            Log.i(TAG, "overlay-publish n=" + layout.cards.size() + " dim=" + dims.size()
                    + " glow=" + glows.size() + " same=true ms=" + gap + " from=scan");
            return;
        }
        drawnRenderFp = fp;
        if (!dims.isEmpty() || !glows.isEmpty()) {
            for (CardTarget card : layout.cards) {
                Log.i(TAG, "card fp=" + card.fingerprint + " title=" + trim(card.title, 40)
                        + " author=" + trim(card.author, 20) + " cover=" + card.cover.flattenToString()
                        + " fully=" + card.fullyVisible);
            }
        }
        if (overlayView != null) {
            // 一次到位：整帧替换，不先清空再逐块补。
            overlayView.show(dims, glows, glowCovers, glowStrength, dimOpacity, 0, sysNavTop, true);
        }
        Log.i(TAG, "overlay-publish n=" + layout.cards.size() + " dim=" + dims.size()
                + " glow=" + glows.size() + " same=false ms=" + gap + " from=scan");
    }

    private static String rectKey(RectF r) {
        return Math.round(r.left) + "," + Math.round(r.top) + ","
                + Math.round(r.right) + "," + Math.round(r.bottom);
    }

    /** 只画屏幕上、系统导航栏上方的部分；裁完太小就丢弃，别画半条。 */
    private RectF clip(Rect cover) {
        if (cover == null) return null;
        float l = Math.max(0, cover.left);
        float t = Math.max(0, cover.top);
        float r = Math.min(screenW, cover.right);
        float b = Math.min(sysNavTop, cover.bottom);
        if (r - l < 8 || b - t < 120) return null;
        if (b - t < 0.5f * cover.height()) return null;
        return new RectF(l, t, r, b);
    }

    /** 初代 expandedGlow：软光位图的外扩矩形；宽度 w=(12+12*strength/100)dp。 */
    private RectF expandedGlow(Rect cover, float wPx) {
        if (cover == null) return null;
        float density = getResources().getDisplayMetrics().density;
        if (wPx < 1.5f * density) return null;
        return new RectF(cover.left - wPx, cover.top - wPx,
                cover.right + wPx, cover.bottom + wPx);
    }

    /** 首卡封面 top：布局是否整体位移的锚点（初代用 cards.get(0).card.top，同口径）。 */
    private static int firstCardTop(LayoutSnapshot layout) {
        if (layout.cards.isEmpty()) return Integer.MIN_VALUE;
        return layout.cards.get(0).cover.top;
    }

    // ---------- 数据 ----------

    private static final class LayoutSnapshot {
        final List<CardTarget> cards;
        final String key;
        final String classKey;

        LayoutSnapshot(List<CardTarget> source) {
            cards = source == null
                    ? Collections.emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(source));
            StringBuilder signature = new StringBuilder();
            StringBuilder content = new StringBuilder();
            for (CardTarget c : cards) {
                signature.append(c.fingerprint).append(':')
                        .append(c.cover.flattenToString()).append(':')
                        .append(c.fullyVisible).append('|');
                content.append(c.fingerprint).append(':').append(c.title).append('|');
            }
            key = signature.toString();
            classKey = content.toString();
        }
    }

    private static String trim(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private static String shortName(String cls) {
        int dot = cls.lastIndexOf('.');
        return dot >= 0 ? cls.substring(dot + 1) : cls;
    }

    // ---------- 覆盖层 ----------

    /**
     * 覆盖层：灰罩 = 半透明灰圆角矩形；发亮 = 初代同款软光位图（圆角矩形距离场 + 高斯 + 外缘 smoothstep）。
     * 所有几何/位图在 publish 时算好，onDraw 只做固定次数的 drawRoundRect/drawBitmap。
     */
    private static final class OverlayView extends View {

        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint tintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint glowBmpPaint = new Paint(Paint.FILTER_BITMAP_FLAG);

        private volatile List<RectF> dims = Collections.emptyList();
        private volatile List<RectF> glows = Collections.emptyList();
        private volatile List<RectF> glowCovers = Collections.emptyList();
        private final List<Bitmap> glowBitmaps = new ArrayList<>();
        private String glowCacheKey = "";

        private final float density;
        private final float radius;
        private volatile int dimOpacity = 55;
        private volatile int glowStrength = 60;
        private int clipTop;
        private int clipBottom;
        private long showStart;
        private boolean drawLogged;

        OverlayView(Context context) {
            super(context);
            density = getResources().getDisplayMetrics().density;
            radius = 10 * density;
            fillPaint.setStyle(Paint.Style.FILL);
            tintPaint.setStyle(Paint.Style.FILL);
            tintPaint.setColor(AMBER);
            tintPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SCREEN));
        }

        boolean hasVisible() {
            return !dims.isEmpty() || !glows.isEmpty();
        }

        void show(List<RectF> newDims, List<RectF> newGlows, List<RectF> newGlowCovers,
                  int strength, int opacity, int top, int bottom, boolean instant) {
            if (Looper.myLooper() != Looper.getMainLooper()) {
                post(() -> show(newDims, newGlows, newGlowCovers, strength, opacity,
                        top, bottom, instant));
                return;
            }
            // 初代 showSettle：已经可见时只替换帧，不重置 showStart / drawLogged。
            // instant=true 时 alpha 恒为 1，但恢复 showStart 仍会多一次无谓重绘与 draw 日志。
            boolean already = hasVisible();
            dims = new ArrayList<>(newDims);
            glows = new ArrayList<>(newGlows);
            glowCovers = new ArrayList<>(newGlowCovers);
            glowStrength = strength;
            dimOpacity = opacity;
            clipTop = top;
            clipBottom = bottom;
            rebuildGlowBitmaps();
            // 整帧替换：默认立即以全不透明画出（publish 传 instant=true），避免淡入被看成「闪一下」。
            if (!already) {
                showStart = instant ? SystemClock.uptimeMillis() - FADE_MS
                        : SystemClock.uptimeMillis();
                drawLogged = false;
            }
            invalidate();
            if (!instant && !already) postInvalidateOnAnimation();
        }

        void hideNow() {
            if (Looper.myLooper() != Looper.getMainLooper()) {
                post(this::hideNow);
                return;
            }
            if (!dims.isEmpty() || !glows.isEmpty()) {
                Log.i(TAG, "overlay-hide dim=" + dims.size() + " glow=" + glows.size());
            }
            dims = Collections.emptyList();
            glows = Collections.emptyList();
            glowCovers = Collections.emptyList();
            recycleGlowBitmaps();
            glowCacheKey = "";
            showStart = 0;
            drawLogged = false;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            List<RectF> dimSnapshot = dims;
            List<RectF> glowSnapshot = glows;
            if (dimSnapshot.isEmpty() && glowSnapshot.isEmpty()) return;
            long now = SystemClock.uptimeMillis();
            float alpha = showStart <= 0 ? 1f
                    : Math.min(1f, (now - showStart) / (float) FADE_MS);
            if (!drawLogged) {
                drawLogged = true;
                Log.i(TAG, "overlay-draw dim=" + dimSnapshot.size() + " glow=" + glowSnapshot.size()
                        + " alpha=" + alpha);
            }

            if (!dimSnapshot.isEmpty()) {
                fillPaint.setColor(GRAY_COLOR);
                fillPaint.setAlpha((int) (0xFF * dimOpacity / 100f * alpha));
                for (RectF r : dimSnapshot) {
                    canvas.drawRoundRect(r, radius, radius, fillPaint);
                }
            }
            if (!glowSnapshot.isEmpty()) {
                List<RectF> coverSnapshot = glowCovers;
                canvas.save();
                canvas.clipRect(0, clipTop, getWidth(), clipBottom);
                float[] cfg = glowCfg();
                // 初代：一层很淡的暖色铺在封面上，让封面像被点亮。
                tintPaint.setColor(AMBER);
                tintPaint.setAlpha((int) (255 * cfg[3] * alpha));
                for (RectF r : coverSnapshot) {
                    canvas.drawRoundRect(r, radius, radius, tintPaint);
                }
                // 再贴软光位图（中心透明、外圈高斯衰减），不是几条硬描边。
                for (int i = 0; i < glowBitmaps.size() && i < glowSnapshot.size(); i++) {
                    Bitmap b = glowBitmaps.get(i);
                    if (b == null || b.isRecycled()) continue;
                    glowBmpPaint.setAlpha((int) (255 * alpha));
                    canvas.drawBitmap(b, glowSnapshot.get(i).left, glowSnapshot.get(i).top,
                            glowBmpPaint);
                }
                canvas.restore();
            }
            if (showStart > 0 && alpha < 1f) postInvalidateOnAnimation();
        }

        /** 初代 glowCfg：{外扩 dp, sigma dp, peak, cover 暖色 alpha}。 */
        private float[] glowCfg() {
            float v = Math.max(0, Math.min(100, glowStrength)) / 100f;
            return new float[]{12 + 12 * v, 5 + 6 * v, 0.24f + 0.24f * v, 0.04f + 0.12f * v};
        }

        private String glowKey() {
            StringBuilder sb = new StringBuilder()
                    .append(glowStrength).append('/').append(Math.round(radius)).append('/');
            for (int i = 0; i < glows.size(); i++) {
                sb.append(rectKey(glows.get(i))).append(':');
                if (i < glowCovers.size()) sb.append(rectKey(glowCovers.get(i)));
                sb.append('|');
            }
            return sb.toString();
        }

        private void recycleGlowBitmaps() {
            for (Bitmap b : glowBitmaps) {
                if (b != null && !b.isRecycled()) b.recycle();
            }
            glowBitmaps.clear();
        }

        /** 初代 rebuildGlowBitmaps 的移植：每个 glow 生成一张 ARGB 软光位图；key 不变时复用。 */
        private void rebuildGlowBitmaps() {
            String key = glowKey();
            if (key.equals(glowCacheKey)) return;
            glowCacheKey = key;
            recycleGlowBitmaps();
            if (glows.isEmpty() || glowCovers.isEmpty()) return;
            float[] cfg = glowCfg();
            float sigma = cfg[1] * density;
            float peak = cfg[2];
            for (int i = 0; i < glows.size() && i < glowCovers.size() && i < 6; i++) {
                RectF g = glows.get(i);
                RectF cover = glowCovers.get(i);
                if (g == null || cover == null || g.width() <= 0 || cover.width() <= 0) {
                    glowBitmaps.add(null);
                    continue;
                }
                float we = (g.width() - cover.width()) / 2f;
                int bw = (int) cover.width() + (int) (we * 2) + 2;
                int bh = (int) cover.height() + (int) (we * 2) + 2;
                if (bw <= 0 || bh <= 0 || bw > 1600 || bh > 1600) {
                    glowBitmaps.add(null);
                    continue;
                }
                float sig = sigma;
                if (we < cfg[0] * density) sig = sigma * Math.max(0.2f, we / (cfg[0] * density));
                float cutoff = 0.75f * we;
                float hw = cover.width() / 2f;
                float hh = cover.height() / 2f;
                float rr = radius;
                try {
                    Bitmap bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
                    int[] px = new int[bw * bh];
                    float cx = we + hw, cy = we + hh;
                    for (int y = 0; y < bh; y++) {
                        float py = y + 0.5f;
                        for (int x = 0; x < bw; x++) {
                            float dx = Math.abs(x + 0.5f - cx) - (hw - rr);
                            float dy = Math.abs(py - cy) - (hh - rr);
                            float ax = Math.max(dx, 0), ay = Math.max(dy, 0);
                            float dist = (float) Math.sqrt(ax * ax + ay * ay)
                                    + Math.min(Math.max(dx, dy), 0) - rr;
                            if (dist < 0 || dist >= we) continue;
                            float a = peak * (float) Math.exp(-(dist * dist) / (2 * sig * sig));
                            if (dist > cutoff) {
                                float t = (dist - cutoff) / (we - cutoff);
                                a *= (1 - t * t * (3 - 2 * t));
                            }
                            int al = (int) (a * 255);
                            if (al <= 0) continue;
                            px[y * bw + x] = (al << 24) | 0x00E8A33D;
                        }
                    }
                    bmp.setPixels(px, 0, bw, 0, 0, bw, bh);
                    glowBitmaps.add(bmp);
                } catch (Throwable ignored) {
                    glowBitmaps.add(null);
                }
            }
        }
    }
}
