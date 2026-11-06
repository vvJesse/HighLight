package com.feedreform.highlight;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 本机偏好与状态，全部走 SharedPreferences。
 *
 * 判断偏好：focusText / avoidText / rule / ruleVersion / advancedPrompt
 * 样式：glowEnabled / glowStrength(0–100)
 * 状态：keyLastVerifiedAt / cookieLastVerifiedAt / lastSyncAt（毫秒时间戳，0 = 从未）
 */
final class Prefs {

    static final String NAME = "hl_prefs";

    static final String FOCUS = "focusText";
    static final String AVOID = "avoidText";
    static final String RULE = "rule";
    /** 判断配置版本：规则正文、focus 或 avoid 变化时自增，旧判断缓存（含 native）随之失效。 */
    static final String RULE_VERSION = "ruleVersion";

    static final String GLOW_ON = "glowEnabled";
    static final String GLOW_STRENGTH = "glowStrength";
    static final String DIM_ON = "dimEnabled";
    static final String DIM_OPACITY = "dimOpacity";

    static final String KEY_VERIFIED = "keyLastVerifiedAt";
    static final String COOKIE_VERIFIED = "cookieLastVerifiedAt";
    static final String LAST_SYNC = "lastSyncAt";

    /** 推荐流 fresh_idx：每次下拉自增，拿到新一批。 */
    static final String RCMD_FRESH = "rcmdFreshIdx";

    static final String DEFAULT_RULE = "";

    private Prefs() {
    }

    static SharedPreferences get(Context c) {
        return c.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    static String focus(Context c) {
        return get(c).getString(FOCUS, "");
    }

    static String avoid(Context c) {
        return get(c).getString(AVOID, "");
    }

    static String rule(Context c) {
        return get(c).getString(RULE, DEFAULT_RULE);
    }

    static int ruleVersion(Context c) {
        return get(c).getInt(RULE_VERSION, 0);
    }

    static void setRule(Context c, String rule) {
        get(c).edit().putString(RULE, rule == null ? "" : rule.trim()).apply();
    }

    /** 判断配置（规则正文 / focus / avoid）真的变了才自增版本；旧结论因此不再复用。 */
    static void bumpRuleVersion(Context c) {
        get(c).edit().putInt(RULE_VERSION, ruleVersion(c) + 1).apply();
    }

    static boolean glowEnabled(Context c) {
        return get(c).getBoolean(GLOW_ON, true);
    }

    static int glowStrength(Context c) {
        return clamp(get(c).getInt(GLOW_STRENGTH, 60), 0, 100);
    }

    /** 原生 App 封面上的灰罩；列表里不用灰罩（判为不想看的条目直接不显示）。 */
    static boolean dimEnabled(Context c) {
        return get(c).getBoolean(DIM_ON, true);
    }

    static int dimOpacity(Context c) {
        return clamp(get(c).getInt(DIM_OPACITY, 55), 20, 90);
    }

    static long cookieVerifiedAt(Context c) {
        return get(c).getLong(COOKIE_VERIFIED, 0);
    }

    static long keyVerifiedAt(Context c) {
        return get(c).getLong(KEY_VERIFIED, 0);
    }

    static long lastSyncAt(Context c) {
        return get(c).getLong(LAST_SYNC, 0);
    }

    static int rcmdFreshIdx(Context c) {
        return Math.max(1, get(c).getInt(RCMD_FRESH, 1));
    }

    static void bumpRcmdFreshIdx(Context c) {
        get(c).edit().putInt(RCMD_FRESH, rcmdFreshIdx(c) + 1).apply();
    }

    /** 下拉刷新时取下一批：自增并返回新的 fresh_idx。 */
    static int nextRcmdFreshIdx(Context c) {
        int next = rcmdFreshIdx(c) + 1;
        get(c).edit().putInt(RCMD_FRESH, next).apply();
        return next;
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
