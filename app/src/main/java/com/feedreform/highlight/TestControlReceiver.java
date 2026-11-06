package com.feedreform.highlight;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Path;
import android.os.Bundle;
import android.util.Log;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * 仅调试用（{@link BuildConfig#DEBUG}）的测试控制通道接收器。发行包不启用。
 *
 * <p>实际生效的实例由 {@link HighlightService#onServiceConnected()} 在 {@code BuildConfig.DEBUG}
 * 为 true 时动态注册（exported），所以无需 adb input 权限、也无需抢焦点：
 * <pre>
 *   adb shell am broadcast -a com.feedreform.highlight.TEST_START_SESSION --es platform bili
 * </pre>
 *
 * <p>清单里同名 receiver 只是把 action 列表登记到包管理器，方便 {@code pm dump} 查看；
 * 它用 manifestPlaceholders {@code testControlEnabled} 控制，release 包里是 disabled。
 * 该实例不处理任何广播（{@link #service} 为 null），避免与动态注册的实例重复执行动作。
 *
 * <p>这是个人调试通道，故意不要求调用方权限；随正式使用结束应在发行包确认不可用。
 */
public class TestControlReceiver extends BroadcastReceiver {

    private static final String TAG = "HLTEST";

    static final String ACTION_CLICK_TEXT = "com.feedreform.highlight.TEST_CLICK_TEXT";
    static final String ACTION_CLICK_XY = "com.feedreform.highlight.TEST_CLICK_XY";
    static final String ACTION_SWIPE = "com.feedreform.highlight.TEST_SWIPE";
    static final String ACTION_BACK = "com.feedreform.highlight.TEST_BACK";
    static final String ACTION_START_SESSION = "com.feedreform.highlight.TEST_START_SESSION";
    static final String ACTION_SET_CLIPBOARD = "com.feedreform.highlight.TEST_SET_CLIPBOARD";
    static final String ACTION_SET_TEXT = "com.feedreform.highlight.TEST_SET_TEXT";

    private static final String EXTRA_TEXT = "text";
    private static final String EXTRA_HINT = "hint";
    private static final String EXTRA_ID = "id";
    private static final String EXTRA_INDEX = "index";
    private static final String EXTRA_X = "x";
    private static final String EXTRA_Y = "y";
    private static final String EXTRA_X1 = "x1";
    private static final String EXTRA_Y1 = "y1";
    private static final String EXTRA_X2 = "x2";
    private static final String EXTRA_Y2 = "y2";
    private static final String EXTRA_DURATION = "duration";
    private static final String EXTRA_PLATFORM = "platform";

    /** 服务实例；为 null 表示清单里登记 action 用的空壳实例。 */
    private final HighlightService service;

    /** 动态注册用（服务存活期间）。 */
    TestControlReceiver(HighlightService service) {
        this.service = service;
    }

    /** 清单声明用的构造函数：不处理广播，只让 action 出现在包清单里。 */
    public TestControlReceiver() {
        this(null);
    }

    static IntentFilter filter() {
        IntentFilter f = new IntentFilter();
        f.addAction(ACTION_CLICK_TEXT);
        f.addAction(ACTION_CLICK_XY);
        f.addAction(ACTION_SWIPE);
        f.addAction(ACTION_BACK);
        f.addAction(ACTION_START_SESSION);
        f.addAction(ACTION_SET_CLIPBOARD);
        f.addAction(ACTION_SET_TEXT);
        return f;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!BuildConfig.DEBUG) return; // 仅调试用，发行包不启用
        String action = intent == null ? "null" : String.valueOf(intent.getAction());
        if (service == null) {
            // 清单里的空壳：真正处理在服务里动态注册的实例，这里绝不能重复执行动作。
            Log.i(TAG, "shell-skip action=" + action);
            return;
        }
        try {
            dispatch(intent, action);
        } catch (Throwable t) {
            Log.w(TAG, "failed action=" + action + " " + t);
        }
    }

    private void dispatch(Intent intent, String action) {
        HighlightService svc = service;
        if (svc == null) return;
        if (ACTION_CLICK_TEXT.equals(action)) {
            String text = safe(intent.getStringExtra(EXTRA_TEXT));
            boolean ok = clickText(svc, text);
            Log.i(TAG, "click ok=" + ok + " text=" + text);
        } else if (ACTION_CLICK_XY.equals(action)) {
            int x = intent.getIntExtra(EXTRA_X, -1);
            int y = intent.getIntExtra(EXTRA_Y, -1);
            boolean ok = tap(svc, x, y);
            Log.i(TAG, "tap x=" + x + " y=" + y + " ok=" + ok);
        } else if (ACTION_SWIPE.equals(action)) {
            int x1 = intent.getIntExtra(EXTRA_X1, -1);
            int y1 = intent.getIntExtra(EXTRA_Y1, -1);
            int x2 = intent.getIntExtra(EXTRA_X2, -1);
            int y2 = intent.getIntExtra(EXTRA_Y2, -1);
            int duration = intent.getIntExtra(EXTRA_DURATION, 300);
            boolean ok = swipe(svc, x1, y1, x2, y2, duration);
            Log.i(TAG, "swipe x1=" + x1 + " y1=" + y1 + " x2=" + x2 + " y2=" + y2
                    + " duration=" + duration + " ok=" + ok);
        } else if (ACTION_BACK.equals(action)) {
            boolean ok = svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
            Log.i(TAG, "back ok=" + ok);
        } else if (ACTION_START_SESSION.equals(action)) {
            startSession(svc, safe(intent.getStringExtra(EXTRA_PLATFORM)));
        } else if (ACTION_SET_CLIPBOARD.equals(action)) {
            String text = intent.getStringExtra(EXTRA_TEXT);
            boolean ok = setClipboard(svc, text);
            Log.i(TAG, "setclip ok=" + ok + " len=" + (text == null ? 0 : text.length()));
        } else if (ACTION_SET_TEXT.equals(action)) {
            String text = intent.getStringExtra(EXTRA_TEXT);
            String hint = safe(intent.getStringExtra(EXTRA_HINT));
            String id = safe(intent.getStringExtra(EXTRA_ID));
            int index = intent.getIntExtra(EXTRA_INDEX, 0);
            setText(svc, text, hint, id, index);
        } else {
            Log.i(TAG, "unknown action=" + action);
        }
    }

    /** 等价于入口检查通过后的动作：开启会话 + 拉起目标 App。 */
    private void startSession(HighlightService svc, String platform) {
        String pkg = "bili".equals(platform) ? "tv.danmaku.bili"
                : "xhs".equals(platform) ? "com.xingin.xhs" : null;
        if (pkg == null) {
            Log.i(TAG, "session platform=" + platform + " ok=false reason=platform");
            return;
        }
        PackageManager pm = svc.getPackageManager();
        Intent launch = pm == null ? null : pm.getLaunchIntentForPackage(pkg);
        if (launch == null) {
            Log.i(TAG, "session platform=" + platform + " ok=false reason=no-launch-intent pkg=" + pkg);
            return;
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        HighlightSession.start(svc, platform);
        try {
            svc.startActivity(launch);
            Log.i(TAG, "session platform=" + platform + " ok=true pkg=" + pkg);
        } catch (Throwable t) {
            HighlightSession.stop(svc);
            Log.w(TAG, "session platform=" + platform + " ok=false pkg=" + pkg
                    + " err=" + t);
        }
    }

    /**
     * 把 text 写进系统剪贴板。用于替代测试机上不可用的 {@code cmd clipboard}；
     * 广播在服务主线程分发，不需要再 post。日志只记长度，不记内容。
     */
    private static boolean setClipboard(HighlightService svc, String text) {
        if (text == null || text.isEmpty()) return false;
        try {
            ClipboardManager cm = (ClipboardManager) svc.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null) return false;
            cm.setPrimaryClip(ClipData.newPlainText("highlight-test", text));
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "setclip-failed " + t);
            return false;
        }
    }

    /**
     * 在 getWindows()/getRootInActiveWindow() 里找可见、可编辑的节点，按 hint（getHintText()
     * 或 text 包含）与 id（getViewIdResourceName() 包含）过滤后取第 index 个，
     * 先 ACTION_FOCUS 再 ACTION_SET_TEXT。日志只记结果、数量和节点 id，不记写入内容。
     */
    private static void setText(HighlightService svc, String text, String hint, String id, int index) {
        if (index < 0) index = 0;
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        try {
            List<AccessibilityWindowInfo> windows = svc.getWindows();
            if (windows != null) {
                for (AccessibilityWindowInfo w : windows) {
                    if (w == null) continue;
                    AccessibilityNodeInfo root = w.getRoot();
                    if (root != null) queue.add(root);
                }
            }
            AccessibilityNodeInfo active = svc.getRootInActiveWindow();
            if (active != null && !queue.contains(active)) queue.add(active);
        } catch (Throwable t) {
            Log.w(TAG, "settext-tree " + t);
            Log.i(TAG, "settext ok=false index=" + index + " candidates=0 id=" + idOrDash(id) + " len=0");
            return;
        }
        List<AccessibilityNodeInfo> candidates = new ArrayList<>();
        int visited = 0;
        while (!queue.isEmpty() && visited++ < 5000) {
            AccessibilityNodeInfo node = queue.poll();
            if (node == null) continue;
            try {
                if (node.isVisibleToUser() && node.isEditable() && matches(node, hint, id)) {
                    candidates.add(node);
                }
                int count = node.getChildCount();
                for (int i = 0; i < count; i++) {
                    AccessibilityNodeInfo child = node.getChild(i);
                    if (child != null) queue.add(child);
                }
            } catch (Throwable ignored) {
            }
        }
        int count = candidates.size();
        if (text == null || index >= count) {
            Log.i(TAG, "settext ok=false index=" + index + " candidates=" + count
                    + " id=" + idOrDash(id) + " len=0");
            return;
        }
        AccessibilityNodeInfo node = candidates.get(index);
        boolean ok = false;
        String viewId = null;
        try {
            // 部分输入框不报告可聚焦，聚焦失败也继续尝试 SET_TEXT。
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            Bundle args = new Bundle();
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
            ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
            viewId = node.getViewIdResourceName();
        } catch (Throwable t) {
            Log.w(TAG, "settext-failed " + t);
        }
        Log.i(TAG, "settext ok=" + ok + " index=" + index + " candidates=" + count
                + " id=" + (viewId == null || viewId.isEmpty() ? idOrDash(id) : viewId)
                + " len=" + text.length());
    }

    /** hint 与 id 都为空时匹配全部可编辑节点；两者同时给出时需同时满足。 */
    private static boolean matches(AccessibilityNodeInfo node, String hint, String id) {
        if (!hint.isEmpty()
                && !contains(node.getHintText(), hint)
                && !contains(node.getText(), hint)) {
            return false;
        }
        return id.isEmpty() || contains(node.getViewIdResourceName(), id);
    }

    private static String idOrDash(String id) {
        return id == null || id.isEmpty() ? "-" : id;
    }

    /** 在 getWindows()/getRootInActiveWindow() 里找第一个 text 或 content-desc 包含 needle
     * 且自身或祖先可点击的节点，点它。
     */
    private static boolean clickText(HighlightService svc, String needle) {
        if (needle == null || needle.isEmpty()) return false;
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        try {
            List<AccessibilityWindowInfo> windows = svc.getWindows();
            if (windows != null) {
                for (AccessibilityWindowInfo w : windows) {
                    if (w == null) continue;
                    AccessibilityNodeInfo root = w.getRoot();
                    if (root != null) queue.add(root);
                }
            }
            AccessibilityNodeInfo active = svc.getRootInActiveWindow();
            if (active != null) queue.add(active);
        } catch (Throwable t) {
            Log.w(TAG, "click-tree " + t);
            return false;
        }
        int visited = 0;
        while (!queue.isEmpty() && visited++ < 5000) {
            AccessibilityNodeInfo node = queue.poll();
            if (node == null) continue;
            try {
                if (contains(node.getText(), needle) || contains(node.getContentDescription(), needle)) {
                    AccessibilityNodeInfo clickable = node;
                    for (int depth = 0; depth < 12 && clickable != null; depth++) {
                        if (clickable.isClickable()) break;
                        clickable = clickable.getParent();
                    }
                    if (clickable != null && clickable.isClickable()
                            && clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        return true;
                    }
                }
                int count = node.getChildCount();
                for (int i = 0; i < count; i++) {
                    AccessibilityNodeInfo child = node.getChild(i);
                    if (child != null) queue.add(child);
                }
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    private static boolean contains(CharSequence haystack, String needle) {
        return haystack != null && haystack.toString().contains(needle);
    }

    private static boolean tap(HighlightService svc, int x, int y) {
        if (x < 0 || y < 0) return false;
        Path path = new Path();
        path.moveTo(x, y);
        return dispatch(svc, path, 0, 50);
    }

    private static boolean swipe(HighlightService svc, int x1, int y1, int x2, int y2, int duration) {
        if (x1 < 0 || y1 < 0 || x2 < 0 || y2 < 0) return false;
        Path path = new Path();
        path.moveTo(x1, y1);
        path.lineTo(x2, y2);
        return dispatch(svc, path, 0, Math.max(50, duration));
    }

    private static boolean dispatch(HighlightService svc, Path path, long start, long duration) {
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, start, duration))
                .build();
        return svc.dispatchGesture(gesture, null, null);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
