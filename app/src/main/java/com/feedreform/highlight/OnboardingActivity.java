package com.feedreform.highlight;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 首次配置引导（设计稿 05）：1 粘贴 B站 Cookie → 2 填写 DeepSeek Key → 3 开启无障碍。
 *
 * 只是把设置页里已有的三件事串成一条动线；不改凭据格式、不碰判断与数据层。
 * 完成后写「已看过引导」标记，之后设置页不再自动提示（顶部按钮仍然保留）。
 * 不强制全屏拦截：用户随时可以返回设置页，从顶部按钮重新进入。
 */
public class OnboardingActivity extends Activity {

    private static final String TAG = "HLONBOARD";
    private static final String PREF = "hl_onboarding";
    private static final String KEY_DONE = "done";
    private static final String KEY_PROMPTED = "prompted";
    private static final String EXTRA_STEP = "step";
    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.ROOT);

    /** 从设置页进入；step 用来直接落到缺项那一步（0 凭据 / 1 Key / 2 无障碍）。 */
    static void start(Activity from, int step) {
        from.startActivity(new Intent(from, OnboardingActivity.class)
                .putExtra(EXTRA_STEP, Math.max(0, Math.min(2, step))));
        from.overridePendingTransition(0, 0);
    }

    static boolean isDone(Context c) {
        return prefs(c).getBoolean(KEY_DONE, false);
    }

    static boolean hasPrompted(Context c) {
        return prefs(c).getBoolean(KEY_PROMPTED, false);
    }

    static void markPrompted(Context c) {
        prefs(c).edit().putBoolean(KEY_PROMPTED, true).apply();
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService work = Executors.newSingleThreadExecutor();

    private int step;

    private TextView eyebrow;
    private TextView stepTitle;
    private TextView stepBody;
    private TextView[] stepLabels;
    private View stepCookie;
    private View stepKey;
    private View stepA11y;
    private View cookieActions;
    private View keyActions;
    private View a11yActions;
    private EditText cookieInput;
    private EditText keyInput;
    private TextView cookieStatus;
    private TextView keyStatus;
    private TextView a11yStatus;
    private View cookieDot;
    private View keyDot;
    private View a11yDot;
    private Button back;
    private Button next;

    private boolean keyTesting;
    private String keyError = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_onboarding);
        PaperUi.insets(this);
        BottomNav.bind(this, "settings");

        step = Math.max(0, Math.min(2, getIntent().getIntExtra(EXTRA_STEP, 0)));

        eyebrow = findViewById(R.id.onboardEyebrow);
        stepTitle = findViewById(R.id.stepTitle);
        stepBody = findViewById(R.id.stepBody);
        stepLabels = new TextView[]{
                findViewById(R.id.stepLabel0),
                findViewById(R.id.stepLabel1),
                findViewById(R.id.stepLabel2)
        };
        stepCookie = findViewById(R.id.stepCookie);
        stepKey = findViewById(R.id.stepKey);
        stepA11y = findViewById(R.id.stepA11y);
        cookieActions = findViewById(R.id.cookieActions);
        keyActions = findViewById(R.id.keyActions);
        a11yActions = findViewById(R.id.a11yActions);
        cookieInput = findViewById(R.id.onboardCookie);
        keyInput = findViewById(R.id.onboardKey);
        cookieStatus = findViewById(R.id.cookieStatus);
        keyStatus = findViewById(R.id.keyStatus);
        a11yStatus = findViewById(R.id.a11yStatus);
        cookieDot = findViewById(R.id.cookieDot);
        keyDot = findViewById(R.id.keyDot);
        a11yDot = findViewById(R.id.a11yDot);
        back = findViewById(R.id.onboardBack);
        next = findViewById(R.id.onboardNext);

        ((Button) findViewById(R.id.saveCookieStep)).setOnClickListener(v -> saveCookie());
        ((Button) findViewById(R.id.saveKeyStep)).setOnClickListener(v -> saveKey());
        ((Button) findViewById(R.id.testKeyStep)).setOnClickListener(v -> testKey());
        ((Button) findViewById(R.id.openA11yStep)).setOnClickListener(v -> openA11ySettings());

        back.setOnClickListener(v -> {
            if (step > 0) {
                step--;
                showStep();
                refreshStatuses();
            }
        });
        next.setOnClickListener(v -> {
            if (step < 2) {
                step++;
                showStep();
                refreshStatuses();
            } else {
                complete();
            }
        });

        showStep();
        refreshStatuses();
        Log.i(TAG, "open step=" + step);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatuses();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        work.shutdownNow();
    }

    // ---------- 步骤 ----------

    private void showStep() {
        eyebrow.setText("WELCOME / " + (step + 1) + " / 3");
        stepCookie.setVisibility(step == 0 ? View.VISIBLE : View.GONE);
        stepKey.setVisibility(step == 1 ? View.VISIBLE : View.GONE);
        stepA11y.setVisibility(step == 2 ? View.VISIBLE : View.GONE);
        cookieActions.setVisibility(step == 0 ? View.VISIBLE : View.GONE);
        keyActions.setVisibility(step == 1 ? View.VISIBLE : View.GONE);
        a11yActions.setVisibility(step == 2 ? View.VISIBLE : View.GONE);

        for (int i = 0; i < stepLabels.length; i++) {
            stepLabels[i].setTextColor(getColor(i == step ? R.color.brand : R.color.muted));
        }

        if (step == 0) {
            stepTitle.setText("粘贴 B站 Cookie");
            stepBody.setText("从浏览器复制 Cookie 或 SESSDATA，粘贴进来；只保存在本机。");
        } else if (step == 1) {
            stepTitle.setText("填写 DeepSeek Key");
            stepBody.setText("判断哪些内容发亮、哪些过滤。Key 通过 Android Keystore 加密后只存本机。");
        } else {
            stepTitle.setText("开启无障碍");
            stepBody.setText("打开「亮点 · 封面标记」，允许后返回这里。");
        }

        back.setEnabled(step > 0);
        back.setAlpha(step > 0 ? 1f : 0.45f);
        next.setText(step == 2 ? "完成" : "下一步");
    }

    private void complete() {
        prefs(this).edit().putBoolean(KEY_DONE, true).apply();
        Log.i(TAG, "done");
        toast("引导已完成，可以从首页入口打开亮点");
        finish();
    }

    // ---------- 第 1 步 ----------

    private void saveCookie() {
        String raw = cookieInput.getText().toString().trim();
        if (raw.isEmpty()) {
            toast("先粘贴 Cookie 再保存");
            return;
        }
        String cookie = SettingsActivity.normalizeCookie(raw);
        SecureStore.setCookie(this, cookie);
        if (!SecureStore.isAvailable()) {
            toast("本机加密存储不可用，凭据没有保存");
            refreshStatuses();
            return;
        }
        cookieInput.setText("");
        refreshStatuses();
        toast("凭据已保存");
        Log.i(TAG, "cookie-saved len=" + cookie.length());
    }

    // ---------- 第 2 步 ----------

    private void saveKey() {
        String raw = keyInput.getText().toString().trim();
        if (raw.isEmpty()) {
            toast("先粘贴 Key 再保存");
            return;
        }
        SecureStore.setKey(this, raw);
        if (!SecureStore.isAvailable()) {
            toast("本机加密存储不可用，Key 没有保存");
            refreshStatuses();
            return;
        }
        keyInput.setText("");
        Prefs.get(this).edit().putLong(Prefs.KEY_VERIFIED, 0).apply();
        keyError = "";
        refreshStatuses();
        toast("Key 已保存，正在测试…");
        testKey();
    }

    private void testKey() {
        if (SecureStore.getKey(this) == null) {
            toast("先保存 Key 再测试");
            return;
        }
        if (keyTesting) return;
        keyTesting = true;
        keyError = "";
        refreshStatuses();
        work.execute(() -> {
            boolean ok;
            String err = "";
            try {
                ok = Judge.testKey(this);
            } catch (Throwable t) {
                ok = false;
                err = t.getMessage() == null ? "网络异常" : t.getMessage();
            }
            final boolean fOk = ok;
            final String fErr = err;
            main.post(() -> {
                keyTesting = false;
                if (fOk) {
                    Prefs.get(this).edit()
                            .putLong(Prefs.KEY_VERIFIED, System.currentTimeMillis()).apply();
                    keyError = "";
                } else {
                    keyError = fErr;
                }
                refreshStatuses();
                toast(fOk ? "Key 可用" : "Key 不可用");
            });
        });
    }

    // ---------- 第 3 步 ----------

    private void openA11ySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        } catch (Throwable t) {
            toast("打不开系统设置");
        }
    }

    // ---------- 状态 ----------

    private void refreshStatuses() {
        String cookie = SecureStore.getCookie(this);
        if (cookie == null) {
            setDot(cookieDot, R.drawable.bg_dot_muted);
            cookieStatus.setTextColor(getColor(R.color.muted));
            cookieStatus.setText("还没有保存凭据");
        } else {
            setDot(cookieDot, R.drawable.bg_dot_ink);
            cookieStatus.setTextColor(getColor(R.color.ink));
            cookieStatus.setText("凭据已保存");
        }

        String key = SecureStore.getKey(this);
        long verifiedAt = Prefs.keyVerifiedAt(this);
        if (key == null) {
            setDot(keyDot, R.drawable.bg_dot_muted);
            keyStatus.setTextColor(getColor(R.color.muted));
            keyStatus.setText("还没有保存 Key");
        } else if (keyTesting) {
            setDot(keyDot, R.drawable.bg_dot_brand);
            keyStatus.setTextColor(getColor(R.color.muted));
            keyStatus.setText("正在测试连接…");
        } else if (!keyError.isEmpty()) {
            setDot(keyDot, R.drawable.bg_dot_brand);
            keyStatus.setTextColor(getColor(R.color.brand));
            keyStatus.setText("Key 不可用 · " + keyError);
        } else if (verifiedAt > 0) {
            setDot(keyDot, R.drawable.bg_dot_ink);
            keyStatus.setTextColor(getColor(R.color.ink));
            keyStatus.setText("Key 可用 · 最近验证 " + label(verifiedAt));
        } else {
            setDot(keyDot, R.drawable.bg_dot_brand);
            keyStatus.setTextColor(getColor(R.color.brand));
            keyStatus.setText("已保存 · 还需要点「测试连接」");
        }

        boolean connected = HighlightService.isConnected();
        boolean enabled = isServiceEnabled();
        if (connected) {
            setDot(a11yDot, R.drawable.bg_dot_ink);
            a11yStatus.setTextColor(getColor(R.color.ink));
            a11yStatus.setText("已连接，封面标记可用");
        } else if (enabled) {
            setDot(a11yDot, R.drawable.bg_dot_brand);
            a11yStatus.setTextColor(getColor(R.color.brand));
            a11yStatus.setText("已授权，服务还没连上 · 回系统设置再看一眼");
        } else {
            setDot(a11yDot, R.drawable.bg_dot_muted);
            a11yStatus.setTextColor(getColor(R.color.muted));
            a11yStatus.setText("未开启");
        }
    }

    private void setDot(View dot, int drawable) {
        if (dot.getBackground() == null || dot.getTag() == null
                || !dot.getTag().equals(drawable)) {
            dot.setBackgroundResource(drawable);
            dot.setTag(drawable);
        }
    }

    private boolean isServiceEnabled() {
        try {
            String enabled = Settings.Secure.getString(getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (enabled == null) return false;
            android.content.ComponentName mine =
                    new android.content.ComponentName(this, HighlightService.class);
            for (String part : enabled.split(":")) {
                if (part.trim().isEmpty()) continue;
                android.content.ComponentName cn = android.content.ComponentName.unflattenFromString(part);
                if (mine.equals(cn)) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static String label(long millis) {
        LocalDateTime at = LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault());
        return at.format(DATE_TIME);
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }
}
