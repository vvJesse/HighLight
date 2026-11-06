package com.feedreform.highlight;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 入口检查：四项都通过才能开启一次临时会话。
 *
 * ① 目标 App 已安装；② 无障碍服务已授权且已连接；③ DeepSeek Key 已配置且可用
 * （最近 10 分钟内成功过就不重复测）；④ 判断规则可用（空规则用默认规则）。
 * 每次回到本页都复查；缺项给对应入口，不改设置页本身。
 */
public class EntryCheckActivity extends Activity {

    private static final String EXTRA_PLATFORM = "platform";
    private static final String EXTRA_AUTO = "auto";
    private static final long KEY_OK_WINDOW_MS = 10 * 60 * 1000L;
    /** 自动检查动画：逐行点亮，约 0.9–1.1s 后判 canStart。 */
    private static final int AUTO_STEP_MS = 170;
    private static final int AUTO_ANIM_MS = 160;

    static void start(Activity from, String platform) {
        start(from, platform, false);
    }

    /** 从首页入口进入：四项逐项点亮，全部通过后自动打开目标 App。 */
    static void startAuto(Activity from, String platform) {
        start(from, platform, true);
    }

    private static void start(Activity from, String platform, boolean auto) {
        from.startActivity(new Intent(from, EntryCheckActivity.class)
                .putExtra(EXTRA_PLATFORM, platform)
                .putExtra(EXTRA_AUTO, auto));
        from.overridePendingTransition(0, 0);
    }

    private static final int ROW_APP = 0;
    private static final int ROW_A11Y = 1;
    private static final int ROW_KEY = 2;
    private static final int ROW_RULE = 3;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final View[] rows = new View[4];
    private final TextView[] titles = new TextView[4];
    private final TextView[] details = new TextView[4];
    private final TextView[] actions = new TextView[4];

    private String platform = "bili";
    private TextView title;
    private View warnBox;
    private TextView warnTitle;
    private TextView warnBody;
    private TextView action;
    private TextView hint;
    private TextView autoStatus;
    /** 首页入口进入时为 true：动画结束后自动打开，不再要求点按钮。 */
    private boolean auto;
    private boolean autoRunning;
    private boolean autoStartedOnce;
    private boolean autoLaunched;
    private boolean autoOpenScheduled;
    /** 页面是否在前台：后台收到的 Key 测试结果不能触发自动打开。 */
    private boolean resumed;
    private final Runnable autoStartRunnable = this::runAutoCheck;
    private final Runnable autoFinishRunnable = this::finishAutoCheck;
    private final Runnable autoOpenRunnable = this::openTarget;

    private boolean installed;
    private String versionName = "";
    private boolean serviceConnected;
    private boolean serviceEnabled;
    private boolean keyOk;
    private boolean keyTesting;
    private String keyError = "还没有填 Key";
    private long lastKeyTestAt;
    private boolean ruleEmpty = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_entry_check);
        PaperUi.insets(this);
        String extra = getIntent().getStringExtra(EXTRA_PLATFORM);
        if ("xhs".equals(extra)) platform = "xhs";
        auto = getIntent().getBooleanExtra(EXTRA_AUTO, false);

        title = findViewById(R.id.entryTitle);
        warnBox = findViewById(R.id.entryWarn);
        warnTitle = findViewById(R.id.warnTitle);
        warnBody = findViewById(R.id.warnBody);
        action = findViewById(R.id.entryAction);
        hint = findViewById(R.id.entryHint);
        autoStatus = findViewById(R.id.autoStatus);
        // 设置引导弹窗点「稍后」后，从这里可以重新打开；不判断/不重置 done，已完成的用户也能重看。
        findViewById(R.id.entryOnboarding).setOnClickListener(
                v -> OnboardingActivity.start(this, 0));

        boolean xhs = "xhs".equals(platform);
        title.setText(xhs ? "打开小红书 · 亮点" : "打开 B站 · 亮点");

        LinearLayout list = findViewById(R.id.checkList);
        LayoutInflater inflater = LayoutInflater.from(this);
        for (int i = 0; i < 4; i++) {
            View row = inflater.inflate(R.layout.item_entry_check, list, false);
            rows[i] = row;
            titles[i] = row.findViewById(R.id.checkTitle);
            details[i] = row.findViewById(R.id.checkDetail);
            actions[i] = row.findViewById(R.id.checkAction);
            list.addView(row);
        }
        titles[ROW_APP].setText(xhs ? "已安装小红书" : "已安装 B站");
        titles[ROW_A11Y].setText("无障碍服务");
        titles[ROW_KEY].setText("DeepSeek Key");
        titles[ROW_RULE].setText("判断规则");

        rows[ROW_APP].findViewById(R.id.checkAction).setOnClickListener(v -> openStore());
        rows[ROW_A11Y].findViewById(R.id.checkAction).setOnClickListener(v -> openA11ySettings());
        rows[ROW_A11Y].findViewById(R.id.checkTitle).setOnClickListener(v -> openA11ySettings());
        rows[ROW_KEY].findViewById(R.id.checkAction).setOnClickListener(v -> openKeySettings());
        rows[ROW_KEY].findViewById(R.id.checkTitle).setOnClickListener(v -> openKeySettings());

        action.setOnClickListener(v -> {
            if (canStart()) openTarget();
            else toast("先完成上面的检查");
        });

        BottomNav.bind(this, "home");
        if (auto) {
            // 动画完成前先压住 applyRows 里的自动打开，保证四项都亮过一遍再开。
            autoRunning = true;
            autoStatus.setVisibility(View.VISIBLE);
            autoStatus.setTextColor(getColor(R.color.brand));
            autoStatus.setText("正在检查 1/4…");
            setAutoRowsStartState();
        } else {
            autoStatus.setVisibility(View.GONE);
        }
        refreshChecks();
    }

    /** 自动模式的初始行状态：未点亮（alpha 0.35、左移 8dp），由 runAutoCheck 逐行点亮。 */
    private void setAutoRowsStartState() {
        float dx = -8f * getResources().getDisplayMetrics().density;
        for (View row : rows) {
            if (row == null) continue;
            row.setAlpha(0.35f);
            row.setTranslationX(dx);
        }
    }

    /** 面板可见时轻量复查：无障碍服务被系统连上/断开时，按钮状态不用等用户离开再回来。 */
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (isFinishing()) return;
            refreshChecks();
            main.postDelayed(this, 500);
        }
    };

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        // 自动模式：第一次进入先把四项压暗，随后 runAutoCheck 逐项点亮；
        // 从系统设置/Key 页回来时不再重播动画，canStart 为真就直接走「检查通过，正在打开…」。
        boolean firstRun = auto && !autoLaunched && !autoStartedOnce;
        if (firstRun) {
            autoRunning = true;
            autoStatus.setVisibility(View.VISIBLE);
            autoStatus.setTextColor(getColor(R.color.brand));
            autoStatus.setText("正在检查 1/4…");
            setAutoRowsStartState();
        }
        refreshChecks();
        main.removeCallbacks(tick);
        main.postDelayed(tick, 500);
        if (firstRun) {
            main.removeCallbacks(autoStartRunnable);
            main.postDelayed(autoStartRunnable, 150);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        main.removeCallbacks(tick);
        if (auto) {
            // 离开本页（系统设置/Key 页/被目标 App 覆盖）时取消还没执行的自动动作；
            // 回来后 onResume 会按当前检查结果重新决定是否自动打开。
            main.removeCallbacks(autoStartRunnable);
            main.removeCallbacks(autoFinishRunnable);
            main.removeCallbacks(autoOpenRunnable);
            autoOpenScheduled = false;
            autoRunning = false;
            // 行动画可能在暂停期间继续跑并改写 autoStatus；停掉并恢复成可读状态。
            for (View row : rows) {
                if (row == null) continue;
                row.animate().cancel();
                row.setAlpha(1f);
                row.setTranslationX(0f);
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        main.removeCallbacksAndMessages(null);
        io.shutdownNow();
    }

    /** 四项逐行点亮（约 0.9–1.1s），结束后再判 canStart。 */
    private void runAutoCheck() {
        if (!auto || autoLaunched || !resumed || isFinishing()) return;
        autoStartedOnce = true;
        autoRunning = true;
        autoStatus.setVisibility(View.VISIBLE);
        autoStatus.setTextColor(getColor(R.color.brand));
        autoStatus.setText("正在检查 1/4…");
        float dx = -8f * getResources().getDisplayMetrics().density;
        for (int i = 0; i < 4; i++) {
            final int idx = i;
            View row = rows[i];
            row.animate().cancel();
            row.setAlpha(0.35f);
            row.setTranslationX(dx);
            row.animate()
                    .alpha(1f)
                    .translationX(0f)
                    .setDuration(AUTO_ANIM_MS)
                    .setStartDelay((long) i * AUTO_STEP_MS)
                    .withEndAction(() -> {
                        if (!auto || autoLaunched || isFinishing()) return;
                        autoStatus.setText("正在检查 " + (idx + 1) + "/4…");
                    })
                    .start();
        }
        long total = 3L * AUTO_STEP_MS + AUTO_ANIM_MS + 100;
        main.removeCallbacks(autoFinishRunnable);
        main.postDelayed(autoFinishRunnable, total);
    }

    /** 动画结束/回到页面后判定：全通过就自动打开，缺项就停在页面显示 warnBox。 */
    private void finishAutoCheck() {
        if (!auto || autoLaunched || !resumed || isFinishing()) return;
        autoRunning = false;
        if (canStart()) {
            if (autoOpenScheduled) return;
            autoOpenScheduled = true;
            autoStatus.setTextColor(getColor(R.color.brand));
            autoStatus.setText("检查通过，正在打开…");
            main.removeCallbacks(autoOpenRunnable);
            main.postDelayed(autoOpenRunnable, 250);
        } else {
            showAutoNotReady();
            // 立即把缺项 UI 拉回来（不等下一次 500ms tick）；Key 测试中会保持等待态。
            applyRows();
        }
    }

    /** 未通过时的提示；Key 还在异步测试就先说「测试中」，避免先报错再自动打开。 */
    private void showAutoNotReady() {
        if (keyTesting) {
            autoStatus.setTextColor(getColor(R.color.brand));
            autoStatus.setText("正在测试 Key…");
        } else {
            autoStatus.setTextColor(getColor(R.color.muted));
            autoStatus.setText("还有项目未通过 · 修好后会自动打开");
        }
    }

    /**
     * 页面可见期间检查结果变化（Key 测试完成 / 无障碍连上）：自动模式不再要求用户点按钮。
     * 只在动画结束、且尚未安排打开时触发；缺项时只更新提示，由 warnBox 显示具体缺项。
     */
    private void maybeAutoFinish() {
        if (!auto || autoLaunched || autoOpenScheduled || autoRunning
                || !autoStartedOnce || !resumed || isFinishing()) {
            return;
        }
        if (canStart()) {
            finishAutoCheck();
        } else {
            showAutoNotReady();
        }
    }

    private String targetPackage() {
        return "xhs".equals(platform) ? "com.xingin.xhs" : "tv.danmaku.bili";
    }

    private String targetLabel() {
        return "xhs".equals(platform) ? "小红书" : "B站";
    }

    /** 按钮文案：「打开小红书」/「打开 B站」（设计稿里 B站 前有空格）。 */
    private String openLabel() {
        return "xhs".equals(platform) ? "打开小红书" : "打开 B站";
    }

    // ---------- 检查 ----------

    private void refreshChecks() {
        PackageManager pm = getPackageManager();
        installed = false;
        versionName = "";
        try {
            installed = pm.getPackageInfo(targetPackage(), 0) != null;
            if (installed) {
                String v = pm.getPackageInfo(targetPackage(), 0).versionName;
                versionName = v == null ? "" : v;
            }
        } catch (Throwable ignored) {
        }
        serviceConnected = HighlightService.isConnected();
        serviceEnabled = isServiceEnabled();
        ruleEmpty = Prefs.rule(this).trim().isEmpty();

        String key = SecureStore.getKey(this);
        long verifiedAt = Prefs.keyVerifiedAt(this);
        boolean recent = System.currentTimeMillis() - verifiedAt < KEY_OK_WINDOW_MS;
        if (key == null || key.isEmpty()) {
            keyOk = false;
            keyTesting = false;
            keyError = "还没有填 Key";
        } else if (recent) {
            keyOk = true;
            keyTesting = false;
        } else if (!keyTesting && System.currentTimeMillis() - lastKeyTestAt > 5000) {
            startKeyTest();
        }
        applyRows();
    }

    private void startKeyTest() {
        keyTesting = true;
        lastKeyTestAt = System.currentTimeMillis();
        final String key = SecureStore.getKey(this);
        if (key == null || key.isEmpty()) {
            keyTesting = false;
            keyOk = false;
            keyError = "还没有填 Key";
            applyRows();
            return;
        }
        applyRows();
        io.execute(() -> {
            boolean ok = false;
            String error = "";
            try {
                ok = Judge.testKey(this);
                if (!ok) error = "Key 验证没有通过";
            } catch (Throwable t) {
                error = t.getMessage() == null ? "Key 验证失败" : t.getMessage();
            }
            final boolean fOk = ok;
            final String fError = error;
            main.post(() -> {
                keyTesting = false;
                keyOk = fOk;
                keyError = fOk ? "" : fError;
                if (fOk) {
                    Prefs.get(this).edit()
                            .putLong(Prefs.KEY_VERIFIED, System.currentTimeMillis()).apply();
                }
                applyRows();
            });
        });
    }

    private void applyRows() {
        // ① 已安装
        if (installed) {
            okRow(ROW_APP, versionName.isEmpty() ? "已安装" : "版本已识别",
                    "已安装", false);
        } else {
            warnRow(ROW_APP, "没有找到" + targetLabel(), "去应用商店");
        }

        // ② 无障碍服务
        if (serviceConnected) {
            okRow(ROW_A11Y, "已连接，封面标记可用", "已连接", false);
        } else if (serviceEnabled) {
            warnRow(ROW_A11Y, "已授权，服务未连接，点右侧重试", "去开启");
        } else {
            warnRow(ROW_A11Y, "未授权，无法标记封面", "去开启");
        }

        // ③ DeepSeek Key
        if (keyTesting) {
            // 自动检查期间 Key 正在异步测试：这是流程的一部分，不是缺项；用白卡/灰点，
            // 不要橙边框，避免第三行和其他四项视觉不一致。非自动保持原来的 warnRow。
            if (auto) pendingRow(ROW_KEY, "正在测试 Key…", "稍等");
            else warnRow(ROW_KEY, "正在测试 Key…", "稍等");
        } else if (keyOk) {
            okRow(ROW_KEY, "Key 可用", "已就绪", false);
        } else {
            warnRow(ROW_KEY, keyError == null || keyError.isEmpty() ? "Key 无效，请重新填写" : keyError,
                    "去修改");
        }

        // ④ 判断规则（空规则不是错误）
        if (ruleEmpty) {
            okRow(ROW_RULE, "将使用默认规则", "默认", false);
        } else {
            okRow(ROW_RULE, "已填写自定义规则", "已填写", false);
        }

        int missing = 0;
        List<String> todos = new ArrayList<>();
        if (!installed) {
            missing++;
            todos.add("安装" + targetLabel());
        }
        if (!serviceConnected) {
            missing++;
            todos.add("开启无障碍服务");
        }
        // 自动模式下 Key 还在异步测试：它是流程的一部分，失败/超时前不算缺项。
        boolean autoKeyPending = auto && keyTesting && !keyOk;
        if (!keyOk && !autoKeyPending) {
            missing++;
            todos.add("更新有效的 Key");
        }
        // 自动流程进行中（动画中，或等 Key 测试）：只由 autoStatus 提示进度，绝不先亮红色缺项框。
        boolean autoWaiting = auto && !autoLaunched
                && (autoRunning || (keyTesting && !keyOk));
        if (autoWaiting) {
            warnBox.setVisibility(View.GONE);
            action.setEnabled(false);
            action.setAlpha(0.55f);
            action.setText(keyTesting ? "正在测试 Key…" : "正在检查…");
            action.setBackgroundTintList(ColorStateList.valueOf(getColor(R.color.brand)));
            action.setTextColor(getColor(R.color.paper));
            hint.setText(keyTesting ? "Key 测试完成后会自动打开" : "检查全部通过后会自动打开");
            return;
        }
        action.setEnabled(true);
        action.setAlpha(1f);
        boolean ready = canStart();
        if (missing > 0) {
            warnBox.setVisibility(View.VISIBLE);
            warnTitle.setText("还差 " + missing + " 项，就能点亮封面。");
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < todos.size(); i++) {
                if (i > 0) sb.append(i == todos.size() - 1 ? "，" : "、");
                sb.append(todos.get(i));
            }
            sb.append("。");
            warnBody.setText(sb.toString());
            action.setText("先完成上面 " + missing + " 项");
            action.setBackgroundTintList(ColorStateList.valueOf(0xFFECAA91));
            hint.setText("检查全部通过后，按钮变为「" + openLabel() + "」");
        } else {
            warnBox.setVisibility(View.GONE);
            action.setText(openLabel());
            action.setBackgroundTintList(ColorStateList.valueOf(getColor(R.color.brand)));
            hint.setText("打开后离开亮点；回到亮点、锁屏或切到别的应用都会停止标记。");
        }
        action.setTextColor(getColor(R.color.paper));
        // 自动模式：Key 测试/无障碍连接是异步的，结果一变就重新判定是否该自动打开。
        maybeAutoFinish();
    }

    private void okRow(int row, String detail, String actionText, boolean ignored) {
        rows[row].setBackgroundResource(R.drawable.bg_card);
        actions[row].setText(actionText);
        actions[row].setTextColor(getColor(R.color.muted));
        details[row].setTextColor(getColor(R.color.muted));
        details[row].setText(detail);
        rows[row].findViewById(R.id.checkDot)
                .setBackgroundResource(R.drawable.bg_dot_ink);
    }

    /** 自动检查的 Key 测试中：白卡 + 灰点，不出现警告边框（与其它行统一）。 */
    private void pendingRow(int row, String detail, String actionText) {
        rows[row].setBackgroundResource(R.drawable.bg_card);
        actions[row].setText(actionText);
        actions[row].setTextColor(getColor(R.color.muted));
        details[row].setTextColor(getColor(R.color.muted));
        details[row].setText(detail);
        rows[row].findViewById(R.id.checkDot)
                .setBackgroundResource(R.drawable.bg_dot_muted);
    }

    private void warnRow(int row, String detail, String actionText) {
        rows[row].setBackgroundResource(R.drawable.bg_warn_card);
        actions[row].setText(actionText);
        actions[row].setTextColor(getColor(R.color.brand));
        details[row].setTextColor(getColor(R.color.brand));
        details[row].setText(detail);
        rows[row].findViewById(R.id.checkDot)
                .setBackgroundResource(R.drawable.bg_dot_ring_brand);
    }

    private boolean canStart() {
        return installed && serviceConnected && keyOk;
    }

    private boolean isServiceEnabled() {
        try {
            String enabled = Settings.Secure.getString(getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (enabled == null) return false;
            ComponentName mine = new ComponentName(this, HighlightService.class);
            for (String part : enabled.split(":")) {
                if (part.trim().isEmpty()) continue;
                ComponentName cn = ComponentName.unflattenFromString(part);
                if (mine.equals(cn)) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    // ---------- 动作 ----------

    private void openTarget() {
        autoOpenScheduled = false;
        if (isFinishing()) return;
        Intent launch = getPackageManager().getLaunchIntentForPackage(targetPackage());
        if (launch == null) {
            autoLaunched = false;
            toast("没有找到" + targetLabel());
            return;
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        HighlightSession.start(this, platform);
        try {
            startActivity(launch);
            autoLaunched = true;
            overridePendingTransition(0, 0);
            finish();
            overridePendingTransition(0, 0);
        } catch (Throwable t) {
            autoLaunched = false;
            // 打开失败：立即结束会话（服务会清掉气泡）。
            HighlightSession.stop(this);
            toast("打不开" + targetLabel());
        }
    }

    private void openA11ySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        } catch (Throwable t) {
            toast("打不开系统设置");
        }
    }

    private void openKeySettings() {
        startActivity(new Intent(this, SettingsActivity.class));
    }

    private void openStore() {
        String pkg = targetPackage();
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + pkg)));
        } catch (Throwable t) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://www.bing.com/search?q=" + pkg)));
            } catch (Throwable ignored) {
                toast("打不开应用商店");
            }
        }
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }
}
