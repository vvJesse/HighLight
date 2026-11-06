package com.feedreform.highlight;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 设置 v2（2026-11-06）：设置总览 + 四个子页（凭据与 Key / 判断偏好 / 样式 / 高级）。
 *
 * 总览按「亮点 / 内容 / 数据 / 其他」四组行式排列，每行 56dp，左标题、右摘要 + ›；
 * 子页沿用同一行式布局，只有输入类才用白底卡片；根页与子页都不带底部导航。
 * 所有页面在同一个 Activity 内切换，页面进出没有转场动画。
 */
public class SettingsActivity extends Activity {

    private static final String TAG = "HLBASE";
    private static final String COOKIE_FILE = "bili-cookie.txt";
    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.ROOT);
    private static final DateTimeFormatter HM =
            DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);

    /** 「恢复默认」填进编辑器的保守规则；删空后由 {@link Judge} 内置默认兜底。 */
    private static final String DEFAULT_RULE_TEXT =
            "优先保留：知识、教程、技术、独立开发、科学、深度访谈。\n"
                    + "判为 GLOW：与「特别关注」直接相关的视频。\n"
                    + "判为 DIM：综艺、明星八卦、纯搞笑段子、游戏实况、带货。\n"
                    + "信息不足、拿不准时保留为普通。";

    private enum Page { HOME, CREDENTIAL, PREFERENCE, STYLE, ADVANCED }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService work = Executors.newSingleThreadExecutor();

    private LinearLayout host;
    private ScrollView scroll;
    private TextView topTitle;
    private TextView topAction;
    private View topBack;

    private Page page = Page.HOME;

    // 总览
    private TextView homeDot;
    private TextView homeStatus;
    private TextView rowCheck;
    private TextView rowSession;
    private TextView rowPreference;
    private TextView rowStyle;
    private TextView rowCookie;
    private TextView rowKey;

    // 凭据与 Key
    private EditText cookieInput;
    private TextView cookieState;
    private EditText keyInput;
    private TextView keyState;
    private TextView testResult;
    private View testDot;
    private TextView saveCookie;
    private TextView clearCookie;
    private TextView saveKey;
    private TextView testKeyBtn;
    private TextView clearKey;
    private boolean keyTesting;

    // 判断偏好
    private EditText focusInput;
    private EditText avoidInput;
    private EditText ruleInput;
    private TextView ruleState;
    private TextView generateRule;
    private boolean ruleDirty;

    // 样式
    private Switch glowEnabled;
    private SeekBar glowSeek;
    private TextView glowLabel;
    private Switch dimEnabled;
    private SeekBar dimSeek;
    private TextView dimLabel;

    // 高级（不含高级 Prompt：用户确认去掉，改它不会让旧结论失效）
    private Switch sanitizedToggle;
    private TextView lastJudgeClass;
    private TextView lastJudgeSummary;
    private TextView cacheState;

    private boolean credentialsLoaded;
    private String cachedCookie;
    private String cachedKey;
    private boolean sessionReceiverOn;

    private final Runnable statusTick = new Runnable() {
        @Override
        public void run() {
            if (isFinishing()) return;
            if (page == Page.HOME) refreshHome();
            main.postDelayed(this, 800);
        }
    };

    private final BroadcastReceiver sessionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (page == Page.HOME) refreshHome();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        PaperUi.insets(this);

        host = findViewById(R.id.settingsPageHost);
        scroll = findViewById(R.id.settingsScroll);
        topTitle = findViewById(R.id.topTitle);
        topAction = findViewById(R.id.topAction);
        topBack = findViewById(R.id.topBack);
        topBack.setOnClickListener(v -> goBack());
        topAction.setOnClickListener(v -> onTopAction());

        showPage(Page.HOME);

        if (!OnboardingActivity.isDone(this) && !OnboardingActivity.hasPrompted(this)) {
            main.postDelayed(this::showOnboardingPrompt, 450);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerSessionReceiver();
        refreshPage();
        main.removeCallbacks(statusTick);
        main.postDelayed(statusTick, 500);
    }

    @Override
    protected void onPause() {
        super.onPause();
        main.removeCallbacks(statusTick);
        if (sessionReceiverOn) {
            try {
                unregisterReceiver(sessionReceiver);
            } catch (Throwable ignored) {
            }
            sessionReceiverOn = false;
        }
        if (page == Page.PREFERENCE) persistPreferences();
    }

    @Override
    public void onBackPressed() {
        if (page != Page.HOME) {
            goBack();
        } else {
            super.onBackPressed();
            overridePendingTransition(0, 0);
        }
    }

    private void goBack() {
        if (page == Page.HOME) {
            finish();
            overridePendingTransition(0, 0);
            return;
        }
        if (page == Page.PREFERENCE && ruleDirty) {
            new AlertDialog.Builder(this)
                    .setTitle("规则还没保存")
                    .setMessage("生成的规则还没有生效。要保存后再离开吗？")
                    .setPositiveButton("保存", (d, w) -> {
                        saveRuleDraft();
                        showPage(Page.HOME);
                    })
                    .setNegativeButton("放弃", (d, w) -> showPage(Page.HOME))
                    .show();
            return;
        }
        showPage(Page.HOME);
    }

    private void onTopAction() {
        if (page == Page.PREFERENCE) saveRuleDraft();
    }

    // ---------- 页面切换 ----------

    private void showPage(Page next) {
        page = next;
        host.removeAllViews();
        scroll.scrollTo(0, 0);
        topAction.setVisibility(View.GONE);
        switch (next) {
            case HOME:
                topTitle.setText("设置");
                topTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
                topTitle.setTypeface(getResources().getFont(R.font.noto_serif_sc_bold));
                buildHome();
                break;
            case CREDENTIAL:
                topTitle.setText("凭据与 Key");
                topTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
                topTitle.setTypeface(getResources().getFont(R.font.noto_serif_sc_bold));
                LayoutInflater.from(this).inflate(R.layout.page_settings_credential, host, true);
                bindCredential();
                break;
            case PREFERENCE:
                topTitle.setText("判断偏好");
                topTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
                topTitle.setTypeface(getResources().getFont(R.font.noto_serif_sc_bold));
                LayoutInflater.from(this).inflate(R.layout.page_settings_preference, host, true);
                bindPreference();
                break;
            case STYLE:
                topTitle.setText("样式");
                topTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
                topTitle.setTypeface(getResources().getFont(R.font.noto_serif_sc_bold));
                LayoutInflater.from(this).inflate(R.layout.page_settings_style, host, true);
                bindStyle();
                break;
            case ADVANCED:
                topTitle.setText("高级");
                topTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
                topTitle.setTypeface(getResources().getFont(R.font.noto_serif_sc_bold));
                LayoutInflater.from(this).inflate(R.layout.page_settings_advanced, host, true);
                bindAdvanced();
                break;
        }
        refreshPage();
    }

    private void refreshPage() {
        switch (page) {
            case HOME:
                refreshHome();
                break;
            case CREDENTIAL:
                refreshCredential();
                break;
            case PREFERENCE:
                refreshPreferenceState();
                break;
            case STYLE:
                refreshStyleLabels();
                break;
            case ADVANCED:
                refreshAdvanced();
                break;
        }
    }

    // ---------- 总览 ----------

    private void buildHome() {
        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        statusRow.setPadding(0, dp(12), 0, dp(6));
        homeDot = new TextView(this);
        homeDot.setBackgroundResource(R.drawable.bg_dot_muted);
        statusRow.addView(homeDot, new LinearLayout.LayoutParams(dp(8), dp(8)));
        homeStatus = new TextView(this);
        homeStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        homeStatus.setTextColor(PaperUi.INK);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.leftMargin = dp(8);
        statusRow.addView(homeStatus, sp);
        host.addView(statusRow);

        addGroupTitle("亮点");
        rowCheck = addRow("检查与引导", "", true, v -> EntryCheckActivity.start(this, "bili"));
        rowSession = addRow("当前会话", "", false, null);
        addGroupTitle("内容");
        rowPreference = addRow("判断偏好", "", true, v -> showPage(Page.PREFERENCE));
        rowStyle = addRow("样式", "", true, v -> showPage(Page.STYLE));
        addGroupTitle("数据");
        rowCookie = addRow("B站凭据", "", true, v -> showPage(Page.CREDENTIAL));
        rowKey = addRow("DeepSeek Key", "", true, v -> showPage(Page.CREDENTIAL));
        addGroupTitle("其他");
        addRow("高级", "", true, v -> showPage(Page.ADVANCED));
        addRow("版本", BuildConfig.VERSION_NAME, false, null);
    }

    private void refreshHome() {
        if (homeStatus == null) return;
        if (!credentialsLoaded) {
            cachedCookie = SecureStore.getCookie(this);
            cachedKey = SecureStore.getKey(this);
            credentialsLoaded = true;
        }
        int missing = missingChecks();
        homeStatus.setText(missing == 0 ? "就绪" : "待配置 · 还差 " + missing + " 项");
        homeDot.setBackgroundResource(missing == 0 ? R.drawable.bg_dot_ink : R.drawable.bg_dot_brand);
        homeStatus.setTextColor(missing == 0 ? PaperUi.INK : PaperUi.BRAND);

        rowCheck.setText(missing == 0 ? "就绪" : "待配置 · 还差 " + missing + " 项");
        rowSession.setText(HighlightSession.isActive()
                ? "运行中 · " + platformLabel(HighlightSession.activePlatform())
                : "未运行");
        rowPreference.setText(Prefs.rule(this).isEmpty() ? "默认规则" : "已生成");
        rowStyle.setText(Prefs.glowEnabled(this)
                ? "发亮 " + Prefs.glowStrength(this) + "%" : "关闭");
        rowCookie.setText(cachedCookie == null ? "未保存" : "已保存");
        rowKey.setText(cachedKey == null ? "未保存" : "已保存");
    }

    /** 与检查页同口径：目标 App / 无障碍 / Key 可用 / 规则（空规则视为可用）。 */
    private int missingChecks() {
        int missing = 0;
        if (!isInstalled("tv.danmaku.bili") && !isInstalled("com.xingin.xhs")) missing++;
        if (!HighlightService.isConnected()) missing++;
        boolean keySaved = SecureStore.getKey(this) != null;
        boolean keyReady = keySaved && Prefs.keyVerifiedAt(this) > 0;
        if (!keyReady) missing++;
        return missing;
    }

    private void addGroupTitle(String text) {
        TextView title = new TextView(this);
        title.setText(text);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        title.setTextColor(getColor(R.color.muted));
        title.setIncludeFontPadding(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(24);
        lp.bottomMargin = dp(6);
        host.addView(title, lp);
    }

    private TextView addRow(String title, String value, boolean arrow, View.OnClickListener click) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(56));
        row.setPadding(dp(2), 0, dp(2), 0);
        if (click != null) {
            TypedValue tv = new TypedValue();
            getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
            Drawable bg = getDrawable(tv.resourceId);
            row.setBackground(bg);
            row.setClickable(true);
            row.setFocusable(true);
            row.setOnClickListener(click);
        }
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        t.setTextColor(PaperUi.INK);
        t.setIncludeFontPadding(false);
        row.addView(t, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        v.setTextColor(getColor(R.color.muted));
        v.setIncludeFontPadding(false);
        v.setSingleLine(true);
        v.setMaxWidth(dp(210));
        v.setEllipsize(android.text.TextUtils.TruncateAt.END);
        v.setGravity(Gravity.END);
        row.addView(v);
        if (arrow) {
            TextView a = new TextView(this);
            a.setText("›");
            a.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
            a.setTextColor(getColor(R.color.muted));
            a.setIncludeFontPadding(false);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.leftMargin = dp(8);
            row.addView(a, lp);
        }
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(56));
        host.addView(row, rowLp);
        // V-r2-05：每行下方都画 1px 分隔线（画板按行分界，不只组内相邻行）。
        View divider = new View(this);
        divider.setBackgroundColor(getColor(R.color.line));
        host.addView(divider, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1));
        return v;
    }

    // ---------- 凭据与 Key ----------

    private void bindCredential() {
        cookieInput = findViewById(R.id.cookieInput);
        cookieState = findViewById(R.id.cookieState);
        keyInput = findViewById(R.id.keyInput);
        keyState = findViewById(R.id.keyState);
        testResult = findViewById(R.id.testResult);
        testDot = findViewById(R.id.testDot);
        saveCookie = findViewById(R.id.saveCookie);
        clearCookie = findViewById(R.id.clearCookie);
        saveKey = findViewById(R.id.saveKey);
        testKeyBtn = findViewById(R.id.testKey);
        clearKey = findViewById(R.id.clearKey);

        saveCookie.setOnClickListener(v -> saveCookie());
        clearCookie.setOnClickListener(v -> confirmClearCookie());
        saveKey.setOnClickListener(v -> saveKey());
        testKeyBtn.setOnClickListener(v -> testKey());
        clearKey.setOnClickListener(v -> confirmClearKey());

        cookieInput.addTextChangedListener(simpleWatcher(() -> setEnabled(saveCookie,
                cookieInput.getText().toString().trim().length() > 0)));
        keyInput.addTextChangedListener(simpleWatcher(() -> setEnabled(saveKey,
                keyInput.getText().toString().trim().length() > 0)));

        View debugImport = findViewById(R.id.debugImport);
        if (debugImport != null) {
            debugImport.setVisibility(BuildConfig.DEBUG ? View.VISIBLE : View.GONE);
            debugImport.setOnClickListener(v -> importDebugCookie());
        }
    }

    private void refreshCredential() {
        boolean hasCookie = SecureStore.getCookie(this) != null;
        boolean hasKey = SecureStore.getKey(this) != null;
        cookieState.setText(hasCookie ? "已保存" : "未保存");
        keyState.setText(hasKey ? "已保存" : "未保存");
        setEnabled(clearCookie, hasCookie);
        setEnabled(clearKey, hasKey);
        setEnabled(saveCookie, cookieInput.getText().toString().trim().length() > 0);
        setEnabled(saveKey, keyInput.getText().toString().trim().length() > 0);
        setEnabled(testKeyBtn, hasKey && !keyTesting);
        if (hasKey) {
            String mask = SecureStore.mask(SecureStore.getKey(this));
            if (mask != null && !mask.isEmpty()) keyInput.setHint(mask);
        }
    }

    private void saveCookie() {
        String raw = cookieInput.getText().toString().trim();
        if (raw.isEmpty()) {
            toast("先粘贴 Cookie 再保存");
            return;
        }
        String cookie = normalizeCookie(raw);
        SecureStore.setCookie(this, cookie);
        if (!SecureStore.isAvailable()) {
            toast("本机加密存储不可用，凭据没有保存");
            return;
        }
        cookieInput.setText("");
        credentialsLoaded = false;
        refreshCredential();
        refreshHome();
        toast("凭据已保存");
        Log.i(TAG, "cookie-saved len=" + cookie.length());
    }

    private void confirmClearCookie() {
        new AlertDialog.Builder(this)
                .setTitle("清除 B站 凭据？")
                .setMessage("清除后关注动态需要重新填。推荐流的已有缓存不会被删除。")
                .setPositiveButton("清除", (d, w) -> {
                    SecureStore.clearCookie(this);
                    Prefs.get(this).edit().putLong(Prefs.COOKIE_VERIFIED, 0).apply();
                    credentialsLoaded = false;
                    refreshCredential();
                    refreshHome();
                    toast("已清除凭据");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void saveKey() {
        String raw = keyInput.getText().toString().trim();
        if (raw.isEmpty()) {
            toast("先粘贴 Key 再保存");
            return;
        }
        SecureStore.setKey(this, raw);
        if (!SecureStore.isAvailable()) {
            toast("本机加密存储不可用，Key 没有保存");
            return;
        }
        keyInput.setText("");
        Prefs.get(this).edit().putLong(Prefs.KEY_VERIFIED, 0).apply();
        credentialsLoaded = false;
        refreshCredential();
        showTestResult("", null);
        refreshHome();
        toast("Key 已保存");
    }

    private void confirmClearKey() {
        new AlertDialog.Builder(this)
                .setTitle("清除 DeepSeek Key？")
                .setMessage("清除后条目会停在「待判断」，直到重新填写并测试。")
                .setPositiveButton("清除", (d, w) -> {
                    SecureStore.clearKey(this);
                    Prefs.get(this).edit().putLong(Prefs.KEY_VERIFIED, 0).apply();
                    credentialsLoaded = false;
                    refreshCredential();
                    showTestResult("", null);
                    refreshHome();
                    toast("已清除 Key");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void testKey() {
        if (SecureStore.getKey(this) == null) {
            toast("先保存 Key 再测试");
            return;
        }
        if (keyTesting) return;
        keyTesting = true;
        setEnabled(testKeyBtn, false);
        showTestResult("测试中…", "muted");
        final long started = SystemClock();
        work.execute(() -> {
            boolean ok;
            String err = "";
            try {
                ok = Judge.testKey(this);
            } catch (Exception e) {
                ok = false;
                err = e.getMessage() == null ? "网络异常" : e.getMessage();
            }
            final boolean fOk = ok;
            final String fErr = err;
            main.post(() -> {
                keyTesting = false;
                if (page != Page.CREDENTIAL) return;
                if (fOk) {
                    long now = System.currentTimeMillis();
                    Prefs.get(this).edit().putLong(Prefs.KEY_VERIFIED, now).apply();
                    showTestResult("测试通过 · " + label(now), "ink");
                } else {
                    showTestResult("测试失败 · " + fErr, "brand");
                }
                refreshCredential();
                refreshHome();
                Log.i(TAG, "key-test ok=" + fOk + " ms=" + (SystemClock() - started));
            });
        });
    }

    private void showTestResult(String text, String tone) {
        boolean show = text != null && !text.isEmpty();
        testDot.setVisibility(show ? View.VISIBLE : View.INVISIBLE);
        testResult.setVisibility(show ? View.VISIBLE : View.INVISIBLE);
        testResult.setText(show ? text : "");
        if (!show) return;
        if ("ink".equals(tone)) {
            testDot.setBackgroundResource(R.drawable.bg_dot_sage);
            testResult.setTextColor(PaperUi.INK);
        } else if ("brand".equals(tone)) {
            testDot.setBackgroundResource(R.drawable.bg_dot_brand);
            testResult.setTextColor(PaperUi.BRAND);
        } else {
            testDot.setBackgroundResource(R.drawable.bg_dot_muted);
            testResult.setTextColor(getColor(R.color.muted));
        }
    }

    /** 接受完整 Cookie 串或单个 SESSDATA；Cookie: 前缀与换行都清掉。 */
    static String normalizeCookie(String raw) {
        String v = raw.replace("\r", " ").replace("\n", " ").trim();
        if (v.regionMatches(true, 0, "Cookie:", 0, 7)) v = v.substring(7).trim();
        if (!v.contains("=")) v = "SESSDATA=" + v;
        return v;
    }

    // ---------- 判断偏好 ----------

    private void bindPreference() {
        focusInput = findViewById(R.id.focusInput);
        avoidInput = findViewById(R.id.avoidInput);
        ruleInput = findViewById(R.id.ruleInput);
        ruleState = findViewById(R.id.ruleState);
        generateRule = findViewById(R.id.generateRule);

        focusInput.setText(Prefs.focus(this));
        avoidInput.setText(Prefs.avoid(this));
        ruleInput.setText(Prefs.rule(this));
        ruleDirty = false;

        focusInput.addTextChangedListener(simpleWatcher(this::persistPreferences));
        avoidInput.addTextChangedListener(simpleWatcher(this::persistPreferences));
        ruleInput.addTextChangedListener(simpleWatcher(() -> {
            ruleDirty = !ruleInput.getText().toString().trim()
                    .equals(Prefs.rule(this).trim());
            setEnabled(topAction, ruleDirty);
        }));

        generateRule.setOnClickListener(v -> generateRule());
        findViewById(R.id.restoreRule).setOnClickListener(v -> {
            ruleInput.setText(Prefs.rule(this));
            ruleState.setText(Prefs.rule(this).isEmpty()
                    ? "已填入当前规则（空，使用默认规则）" : "已填入上次生效的规则");
        });
        findViewById(R.id.clearRule).setOnClickListener(v -> confirmClearRule());

        ruleState.setText(Prefs.rule(this).isEmpty()
                ? "当前使用默认规则（未配置）"
                : "规则已保存 · v" + Prefs.ruleVersion(this));
        topAction.setText("保存");
        topAction.setVisibility(View.VISIBLE);
        setEnabled(topAction, false);
    }

    private void refreshPreferenceState() {
        persistPreferences();
    }

    private void persistPreferences() {
        if (focusInput == null || avoidInput == null) return;
        String focus = focusInput.getText().toString().trim();
        String avoid = avoidInput.getText().toString().trim();
        boolean focusChanged = !focus.equals(Prefs.focus(this));
        boolean avoidChanged = !avoid.equals(Prefs.avoid(this));
        if (!focusChanged && !avoidChanged) return;
        // focus/avoid 也是判断输入：改了就让旧结论失效（与规则正文共用 ruleVersion，
        // 服务端内存缓存和判断缓存都靠这个版本作废）。只在值真的变化时自增。
        SharedPreferences.Editor e = Prefs.get(this).edit();
        if (focusChanged) e.putString(Prefs.FOCUS, focus);
        if (avoidChanged) e.putString(Prefs.AVOID, avoid);
        e.putInt(Prefs.RULE_VERSION, Prefs.ruleVersion(this) + 1);
        e.apply();
    }

    private void generateRule() {
        if (SecureStore.getKey(this) == null) {
            toast("先保存 DeepSeek Key");
            return;
        }
        String focus = focusInput.getText().toString().trim();
        String avoid = avoidInput.getText().toString().trim();
        persistPreferences();
        generateRule.setText("生成中…");
        setEnabled(generateRule, false);
        ruleState.setText("正在生成规则…");
        work.execute(() -> {
            String rule;
            String err = "";
            try {
                rule = Judge.generateRule(this, focus, avoid);
            } catch (Exception e) {
                rule = null;
                err = e.getMessage() == null ? "网络异常" : e.getMessage();
            }
            final String fRule = rule;
            final String fErr = err;
            main.post(() -> {
                if (page != Page.PREFERENCE) return;
                generateRule.setText("生成判断规则");
                setEnabled(generateRule, true);
                if (fRule == null) {
                    ruleState.setText("生成失败，旧规则仍生效 · " + fErr);
                    return;
                }
                ruleInput.setText(fRule);
                ruleState.setText("已生成，待保存");
            });
        });
    }

    private void saveRuleDraft() {
        String next = ruleInput.getText().toString().trim();
        if (next.equals(Prefs.rule(this).trim())) {
            ruleDirty = false;
            setEnabled(topAction, false);
            toast("规则没有变化");
            return;
        }
        Prefs.setRule(this, next);
        Prefs.bumpRuleVersion(this);
        Judge.clearCache(this);
        VideoStore.get(this).clearResults();
        ruleDirty = false;
        setEnabled(topAction, false);
        ruleState.setText(next.isEmpty()
                ? "已恢复默认规则 · v" + Prefs.ruleVersion(this)
                : "规则已保存 · " + LocalDateTime.now().format(HM));
        toast("已保存规则，下次刷新重新判断");
    }

    private void confirmClearRule() {
        new AlertDialog.Builder(this)
                .setTitle("清空偏好与规则？")
                .setMessage("会清空「特别关注」「不想看到」和已保存的规则，回到默认规则，"
                        + "旧判断结论不再复用。")
                .setPositiveButton("清空", (d, w) -> {
                    Prefs.get(this).edit()
                            .putString(Prefs.FOCUS, "")
                            .putString(Prefs.AVOID, "")
                            .apply();
                    Prefs.setRule(this, "");
                    Prefs.bumpRuleVersion(this);
                    Judge.clearCache(this);
                    VideoStore.get(this).clearResults();
                    focusInput.setText("");
                    avoidInput.setText("");
                    ruleInput.setText("");
                    ruleDirty = false;
                    setEnabled(topAction, false);
                    ruleState.setText("已清空 · 回到默认规则");
                    toast("已清空偏好与规则");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ---------- 样式 ----------

    private void bindStyle() {
        glowEnabled = findViewById(R.id.glowEnabled);
        glowSeek = findViewById(R.id.glowSeek);
        glowLabel = findViewById(R.id.glowLabel);
        dimEnabled = findViewById(R.id.dimEnabled);
        dimSeek = findViewById(R.id.dimSeek);
        dimLabel = findViewById(R.id.dimLabel);

        tintSwitch(glowEnabled);
        tintSwitch(dimEnabled);
        glowEnabled.setChecked(Prefs.glowEnabled(this));
        glowSeek.setProgress(Prefs.glowStrength(this));
        dimEnabled.setChecked(Prefs.dimEnabled(this));
        dimSeek.setProgress(Prefs.dimOpacity(this) - 20);

        glowEnabled.setOnCheckedChangeListener((b, checked) -> {
            Prefs.get(this).edit().putBoolean(Prefs.GLOW_ON, checked).apply();
            refreshStyleLabels();
            notifyStyleChanged();
        });
        glowSeek.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) return;
                Prefs.get(SettingsActivity.this).edit()
                        .putInt(Prefs.GLOW_STRENGTH, progress).apply();
                refreshStyleLabels();
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                notifyStyleChanged();
                refreshHome();
            }
        });
        dimEnabled.setOnCheckedChangeListener((b, checked) -> {
            Prefs.get(this).edit().putBoolean(Prefs.DIM_ON, checked).apply();
            refreshStyleLabels();
            notifyStyleChanged();
        });
        dimSeek.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) return;
                Prefs.get(SettingsActivity.this).edit()
                        .putInt(Prefs.DIM_OPACITY, progress + 20).apply();
                refreshStyleLabels();
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                notifyStyleChanged();
                refreshHome();
            }
        });
        refreshStyleLabels();
    }

    private void refreshStyleLabels() {
        if (glowLabel == null) return;
        int glow = Prefs.glowStrength(this);
        glowLabel.setText(glow + "%");
        glowSeek.setEnabled(Prefs.glowEnabled(this));
        glowLabel.setAlpha(Prefs.glowEnabled(this) ? 1f : 0.38f);
        int dim = Prefs.dimOpacity(this);
        dimLabel.setText(dim + "%");
        dimSeek.setEnabled(Prefs.dimEnabled(this));
        dimLabel.setAlpha(Prefs.dimEnabled(this) ? 1f : 0.38f);
    }

    /** 让无障碍服务立即按新的发亮/灰罩参数重画；服务不在前台时，下次进目标 App 也会读到新值。 */
    private void notifyStyleChanged() {
        try {
            sendBroadcast(new Intent(HighlightService.ACTION_STYLE_CHANGED)
                    .setPackage(getPackageName()));
        } catch (Throwable t) {
            Log.w(TAG, "style-broadcast failed: " + t.getClass().getSimpleName());
        }
    }

    // ---------- 高级 ----------

    private void bindAdvanced() {
        // release 里整组「调试」不出现；页内其余控件仍绑定，refreshAdvanced 不会 NPE。
        View debugGroup = findViewById(R.id.debugGroup);
        if (debugGroup != null) {
            debugGroup.setVisibility(BuildConfig.DEBUG ? View.VISIBLE : View.GONE);
        }
        sanitizedToggle = findViewById(R.id.sanitizedToggle);
        lastJudgeClass = findViewById(R.id.lastJudgeClass);
        lastJudgeSummary = findViewById(R.id.lastJudgeSummary);
        cacheState = findViewById(R.id.cacheState);

        tintSwitch(sanitizedToggle);
        sanitizedToggle.setChecked(Prefs.get(this).getBoolean("sanitizedDebug", true));
        sanitizedToggle.setOnCheckedChangeListener((b, checked) ->
                Prefs.get(this).edit().putBoolean("sanitizedDebug", checked).apply());
        findViewById(R.id.clearCache).setOnClickListener(v -> confirmClearCache());

        View debugImport = findViewById(R.id.debugImport);
        if (debugImport != null) {
            debugImport.setVisibility(BuildConfig.DEBUG ? View.VISIBLE : View.GONE);
            debugImport.setOnClickListener(v -> importDebugCookie());
        }
        TextView version = findViewById(R.id.version);
        version.setText("亮点 · " + BuildConfig.VERSION_NAME
                + (BuildConfig.DEBUG ? " · debug" : ""));

        refreshAdvanced();
    }

    private void refreshAdvanced() {
        if (cacheState == null) return;
        VideoStore store = VideoStore.get(this);
        int count = store.count("rcmd") + store.count("follow");
        cacheState.setText(count + " 条 · " + cacheSizeLabel());

        long at = Prefs.get(this).getLong("lastJudgeAt", 0);
        String title = Prefs.get(this).getString("lastJudgeTitle", "");
        int result = Prefs.get(this).getInt("lastJudgeResult", -1);
        long ms = Prefs.get(this).getLong("lastJudgeMs", 0);
        if (at <= 0 || title.isEmpty()) {
            lastJudgeClass.setText("暂无记录");
            lastJudgeSummary.setText("暂无记录");
            return;
        }
        lastJudgeClass.setText(classLabel(result));
        StringBuilder sb = new StringBuilder(trim(title, 18));
        if (ms > 0) sb.append(" · ").append(ms).append(" ms");
        lastJudgeSummary.setText(sb.toString());
    }

    private String cacheSizeLabel() {
        long bytes = 0;
        File videos = new File(getFilesDir(), "videos.json");
        if (videos.isFile()) bytes += videos.length();
        File covers = new File(getFilesDir(), "covers");
        File[] files = covers.listFiles();
        if (files != null) {
            for (File f : files) bytes += f.length();
        }
        double mb = bytes / (1024.0 * 1024.0);
        if (mb < 0.1) return Math.max(0, Math.round(bytes / 1024.0)) + " KB";
        return String.format(Locale.ROOT, "%.1f MB", mb);
    }

    private void confirmClearCache() {
        new AlertDialog.Builder(this)
                .setTitle("清除本机缓存？")
                .setMessage("会删除推荐/关注缓存与判断缓存；Cookie、Key、偏好和规则都会保留。")
                .setPositiveButton("清除", (d, w) -> {
                    VideoStore.get(this).clearAll();
                    Judge.clearCache(this);
                    Prefs.get(this).edit().putLong(Prefs.LAST_SYNC, 0).apply();
                    refreshAdvanced();
                    toast("已清除本机缓存");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ---------- 运行状态 / 会话 ----------

    private void registerSessionReceiver() {
        if (sessionReceiverOn) return;
        try {
            IntentFilter filter = new IntentFilter(HighlightSession.ACTION_CHANGED);
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(sessionReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(sessionReceiver, filter);
            }
            sessionReceiverOn = true;
        } catch (Throwable t) {
            Log.w(TAG, "session-receiver failed: " + t.getClass().getSimpleName());
        }
    }

    private void showOnboardingPrompt() {
        if (isFinishing() || OnboardingActivity.isDone(this) || page != Page.HOME) return;
        OnboardingActivity.markPrompted(this);
        try {
            new AlertDialog.Builder(this)
                    .setTitle("先把这三步走完")
                    .setMessage("粘贴 B站 Cookie、填 DeepSeek Key、开启无障碍服务，"
                            + "亮点才能在你的推荐页上标记。也可以稍后从「检查与引导」重新打开。")
                    .setPositiveButton("开始配置",
                            (dialog, which) -> OnboardingActivity.start(this, firstMissingStep()))
                    .setNegativeButton("稍后", null)
                    .show();
        } catch (Throwable t) {
            Log.w(TAG, "onboarding-dialog failed: " + t.getClass().getSimpleName());
        }
    }

    /** 总状态按钮：直接落到第一处缺项。 */
    private int firstMissingStep() {
        if (SecureStore.getCookie(this) == null) return 0;
        String key = SecureStore.getKey(this);
        if (key == null || Prefs.keyVerifiedAt(this) == 0) return 1;
        if (!HighlightService.isConnected()) return 2;
        return 0;
    }

    private boolean isInstalled(String pkg) {
        try {
            return getPackageManager().getPackageInfo(pkg, 0) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    private static String platformLabel(String platform) {
        if ("xhs".equals(platform)) return "小红书";
        if ("bili".equals(platform)) return "B站";
        return platform == null || platform.isEmpty() ? "目标 App" : platform;
    }

    private static String classLabel(int result) {
        if (result == Judge.GLOW) return "特别关注";
        if (result == Judge.DIM) return "不想看";
        if (result == Judge.NORMAL) return "普通";
        return "待判断";
    }

    // ---------- 调试 ----------

    /** 仅 debug：读 getExternalFilesDir(null)/bili-cookie.txt 一整行写入 SecureStore。日志只打长度。 */
    private void importDebugCookie() {
        try {
            File dir = getExternalFilesDir(null);
            File f = dir == null ? null : new File(dir, COOKIE_FILE);
            if (f == null || !f.isFile() || f.length() == 0) {
                toast("没找到 " + COOKIE_FILE + "（先在电脑上 adb push）");
                return;
            }
            String raw = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).trim();
            if (raw.isEmpty()) {
                toast("文件是空的");
                return;
            }
            String cookie = normalizeCookie(raw);
            SecureStore.setCookie(this, cookie);
            credentialsLoaded = false;
            if (page == Page.CREDENTIAL) refreshCredential();
            refreshHome();
            Log.i(TAG, "cookie-imported len=" + cookie.length());
            toast("已导入测试凭据");
        } catch (Exception e) {
            toast("导入失败 · " + e.getClass().getSimpleName());
        }
    }

    // ---------- 小工具 ----------

    private static long SystemClock() {
        return android.os.SystemClock.uptimeMillis();
    }

    private TextWatcher simpleWatcher(Runnable after) {
        return new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                after.run();
            }
        };
    }

    /** 开关配色：开 = 品牌橘实底，关 = 鼠尾草；滑块统一纸白。 */
    private void tintSwitch(Switch sw) {
        if (sw == null) return;
        sw.setTrackTintList(new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{0xFFDF5129, 0xFFE9E8D5}));
        sw.setThumbTintList(new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{0xFFFFFCF4, 0xFFF7F2E7}));
    }

    private void setEnabled(View v, boolean enabled) {
        if (v == null) return;
        v.setEnabled(enabled);
        v.setAlpha(enabled ? 1f : 0.38f);
        v.setClickable(enabled);
    }

    private static String trim(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private static String label(long millis) {
        LocalDateTime at = LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault());
        return at.format(DATE_TIME);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    private abstract static class SimpleSeek implements SeekBar.OnSeekBarChangeListener {
        public void onStartTrackingTouch(SeekBar seekBar) {
        }

        public void onStopTrackingTouch(SeekBar seekBar) {
        }
    }
}
