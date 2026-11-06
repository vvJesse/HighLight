package com.feedreform.highlight;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 统一判断服务：GLOW=2（值得看）/ DIM=1（不想看）/ NORMAL=0。
 *
 * - 特别关注优先：命中「特别关注」关键词直接 GLOW，命中「不想看到」直接 DIM，不走网络；
 * - 其余交给 DeepSeek（deepseek-flash / temperature 0 / reasoning_effort none / max_tokens 200），
 *   批 ≤ 12、并发 1；
 * - 规则为空 → 默认三分类（初代 DeepSeekClassifier 的默认口径）：明确值得看的可以 GLOW、
 *   纯娱乐 DIM、信息不足保留普通；「特别关注/不想看到」会作为附加条件一起交给模型，
 *   不做精确关键词匹配时也能语义命中；
 * - 失败 / 超时 / 返回格式无效 → UNKNOWN（-1），**不当成 DIM**，也不写缓存，下次刷新自动重试；
 * - 缓存键含 平台 + 平台内标识 + 规则版本 + 提示词版本 + 模型名，规则/默认口径一变旧结论自动失效。
 */
final class Judge {

    static final String TAG = "HLBASE";
    static final String MODEL = "deepseek-flash";
    static final String API = "https://api.deepseek.com/chat/completions";
    private static final String CACHE_PREF = "hl_judge_cache";
    private static final int MAX_BATCH = 12;
    /**
     * 默认规则/提示词的版本。改默认口径时必须自增，否则旧结论（尤其旧默认只判 DIM 的结果）
     * 会继续命中缓存，用户升级后仍然看不到辉光。
     */
    private static final String PROMPT_VERSION = "p2";

    static final int UNKNOWN = -1;
    static final int NORMAL = 0;
    static final int DIM = 1;
    static final int GLOW = 2;

    /**
     * 规则为空时的默认三分类。初代 DeepSeekClassifier 的默认不是「禁止 GLOW」：
     * 明确值得看的内容照常判 GLOW，只有信息不足才保留普通；这里沿用同一口径，
     * 并保留「不把拿不准的内容硬判成 DIM」的保守部分。
     */
    private static final String CONSERVATIVE_RULE =
            "你是信息流筛选器。按标题（和 UP 主）做三分类：\n"
                    + "GLOW：明确属于知识讲解、教程、经验分享、深度分析、创作/制作过程等值得看的内容。\n"
                    + "DIM：明显属于纯娱乐消遣（综艺、明星八卦、搞笑段子、游戏实况、带货）。\n"
                    + "信息不足、拿不准、可能是知识或创作内容时，不列入任何一行（保留为普通）。";

    private static final String FORMAT =
            "\n\n输出格式（只输出下面两行，不要解释、不要标点以外的内容）：\n"
                    + "GLOW: 序号,序号（没有就写 GLOW: 无）\n"
                    + "DIM: 序号,序号（没有就写 DIM: 无）\n"
                    + "序号对应下面的标题列表；GLOW=值得看，DIM=不想看，没列出的算普通。";

    private static final ExecutorService POOL = Executors.newSingleThreadExecutor();

    interface Callback {
        void onJudged(int judged, int glow, int dim, int unknown, long ms, String error);
    }

    private Judge() {
    }

    static void judgeAsync(Context c, List<VideoStore.Video> videos, Callback cb) {
        final Context app = c.getApplicationContext();
        POOL.execute(() -> {
            long t0 = System.currentTimeMillis();
            int glow = 0, dim = 0, normal = 0, unknown = 0, judged = 0;
            String error = "";
            List<VideoStore.Video> pending = new ArrayList<>();
            for (VideoStore.Video v : videos) {
                int local = localRule(app, v);
                if (local != UNKNOWN) {
                    apply(app, v, local);
                    judged++;
                    if (local == GLOW) glow++;
                    else dim++;
                    continue;
                }
                int cached = cached(app, v);
                if (cached != UNKNOWN) {
                    apply(app, v, cached);
                    judged++;
                    if (cached == GLOW) glow++;
                    else if (cached == DIM) dim++;
                    else normal++;
                    continue;
                }
                pending.add(v);
            }
            String rule = Prefs.rule(app);
            // 分块、串行（并发 1）。
            for (int i = 0; i < pending.size(); i += MAX_BATCH) {
                List<VideoStore.Video> batch = pending.subList(i, Math.min(pending.size(), i + MAX_BATCH));
                List<String> lines = new ArrayList<>();
                for (VideoStore.Video v : batch) {
                    lines.add(text(v));
                }
                try {
                    Map<Integer, Integer> got = sendRule(app, rule, lines);
                    long now = System.currentTimeMillis();
                    for (int j = 0; j < batch.size(); j++) {
                        Integer r = got.get(j);
                        VideoStore.Video v = batch.get(j);
                        if (r == null) {
                            unknown++; // 模型没给这一条 → UNKNOWN，不写缓存
                            continue;
                        }
                        cachePut(app, v, r);
                        VideoStore.get(app).putResult(v.bvid, r, now);
                        v.result = r;
                        v.judgedAt = now;
                        judged++;
                        if (r.intValue() == GLOW) glow++;
                        else if (r.intValue() == DIM) dim++;
                        else normal++;
                        Log.i(TAG, "judge bvid=" + v.bvid + " result=" + r + " titleLen=" + v.title.length());
                    }
                } catch (Throwable t) {
                    error = shortError(t);
                    unknown += batch.size();
                    Log.w(TAG, "judge-batch-fail n=" + batch.size() + " err=" + error);
                }
            }
            long ms = System.currentTimeMillis() - t0;
            Log.i(TAG, "judge-done n=" + videos.size() + " judged=" + judged + " glow=" + glow
                    + " dim=" + dim + " normal=" + normal + " unknown=" + unknown
                    + " ruleLen=" + rule.length() + " ms=" + ms
                    + (error.isEmpty() ? "" : " err=" + error));
            final int fg = glow, fd = dim, fu = unknown, fj = judged;
            final String fe = error;
            if (cb != null) {
                new android.os.Handler(android.os.Looper.getMainLooper()).post(
                        () -> cb.onJudged(fj, fg, fd, fu, ms, fe));
            }
        });
    }

    /** 本地关键词优先：特别关注 → GLOW；不想看到 → DIM；否则 UNKNOWN（交给模型）。 */
    static int localRule(Context c, VideoStore.Video v) {
        return localRuleText(c, v.title + " " + v.author);
    }

    /** 与 {@link #localRule} 同一口径，但只看一段文本（原生 App 封面判断用）。 */
    static int localRuleText(Context c, String raw) {
        return localRuleText(raw, keywords(Prefs.focus(c)), keywords(Prefs.avoid(c)));
    }

    private static int localRuleText(String raw, List<String> focus, List<String> avoid) {
        String text = raw == null ? "" : raw.toLowerCase();
        for (String k : focus) {
            if (!k.isEmpty() && text.contains(k)) return GLOW;
        }
        for (String k : avoid) {
            if (!k.isEmpty() && text.contains(k)) return DIM;
        }
        return UNKNOWN;
    }

    private static List<String> keywords(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) return out;
        for (String part : raw.split("[,，、;；\\s]+")) {
            String k = part.trim().toLowerCase();
            if (!k.isEmpty()) out.add(k);
        }
        return out;
    }

    static int cached(Context c, VideoStore.Video v) {
        SharedPreferences p = c.getSharedPreferences(CACHE_PREF, Context.MODE_PRIVATE);
        return p.getInt(key(c, v), UNKNOWN);
    }

    private static void cachePut(Context c, VideoStore.Video v, int result) {
        c.getSharedPreferences(CACHE_PREF, Context.MODE_PRIVATE).edit()
                .putInt(key(c, v), result).apply();
    }

    /** 缓存键：平台 + 平台内标识 + 规则版本 + 提示词版本 + 模型名。平台目前只有 bili，标识用 bvid。 */
    static String key(Context c, VideoStore.Video v) {
        return "bili|" + v.bvid + "|v" + Prefs.ruleVersion(c) + "|" + PROMPT_VERSION
                + "|" + MODEL;
    }

    private static void apply(Context c, VideoStore.Video v, int result) {
        VideoStore.get(c).putResult(v.bvid, result, System.currentTimeMillis());
        v.result = result;
        v.judgedAt = System.currentTimeMillis();
    }

    private static String text(VideoStore.Video v) {
        String author = v.author == null || v.author.isEmpty() ? "" : "｜UP：" + v.author;
        return v.title + author;
    }

    /**
     * 组装一次判断的规则正文。规则为空时用默认三分类；如果用户填了「特别关注/不想看到」，
     * 把它们作为附加条件交给模型，让「特别关注」不只知道精确关键词，也能语义命中
     * （初代的流程是用这些偏好生成规则正文，这里在默认规则下达到同一效果）。
     */
    private static String buildRule(Context c, String rule) {
        String base = rule == null ? "" : rule.trim();
        if (!base.isEmpty()) return base;
        String focus = Prefs.focus(c);
        String avoid = Prefs.avoid(c);
        StringBuilder sb = new StringBuilder(CONSERVATIVE_RULE);
        if (focus != null && !focus.trim().isEmpty()) {
            sb.append("\n特别关注（明确相关的优先判 GLOW）：").append(focus.trim());
        }
        if (avoid != null && !avoid.trim().isEmpty()) {
            sb.append("\n不想看到（明确相关的判 DIM）：").append(avoid.trim());
        }
        if ((focus != null && !focus.trim().isEmpty())
                || (avoid != null && !avoid.trim().isEmpty())) {
            sb.append("\n判断时优先按上面的特别关注/不想看到匹配；信息不足仍然保留普通。");
        }
        return sb.toString();
    }

    /** 一次批量请求，返回「批内下标 → 结论」。格式无效直接抛异常（外层按 UNKNOWN 处理）。 */
    private static Map<Integer, Integer> sendRule(Context c, String rule, List<String> lines) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append(buildRule(c, rule));
        sb.append(FORMAT).append("\n\n标题：\n");
        for (int i = 0; i < lines.size(); i++) {
            sb.append(i + 1).append(". ").append(lines.get(i)).append('\n');
        }
        String content = complete(c, sb.toString(), 200);
        Map<Integer, Integer> map = new LinkedHashMap<>();
        // 约定是「没列出的算普通」：先全部填 NORMAL，GLOW/DIM 行再覆盖。
        for (int i = 0; i < lines.size(); i++) map.put(i, NORMAL);
        String glowPart = "";
        String dimPart = "";
        int gi = content.indexOf("GLOW:");
        int di = content.indexOf("DIM:");
        if (gi < 0 && di < 0) throw new IllegalStateException("invalid-format");
        if (gi >= 0) glowPart = content.substring(gi + 5, di > gi ? di : content.length());
        if (di >= 0) {
            dimPart = content.substring(di + 4);
            int end = dimPart.indexOf('\n');
            if (end > 0) dimPart = dimPart.substring(0, end);
        }
        for (int idx : parseIndexes(glowPart, lines.size())) map.put(idx, GLOW);
        for (int idx : parseIndexes(dimPart, lines.size())) {
            // 必须先判 null：GLOW 行没列到的序号不在 map 里，直接比较会拆箱 NPE。
            Integer prev = map.get(idx);
            if (prev == null || prev.intValue() != GLOW) map.put(idx, DIM);
        }
        return map;
    }

    private static List<Integer> parseIndexes(String part, int size) {
        List<Integer> out = new ArrayList<>();
        for (String p : part.split("[,，;；、\\s]+")) {
            try {
                int idx = Integer.parseInt(p.trim()) - 1;
                if (idx >= 0 && idx < size && !out.contains(idx)) out.add(idx);
            } catch (NumberFormatException ignored) {
                // 「无」等非数字直接跳过。
            }
        }
        return out;
    }

    /** 生成规则：把特别关注 / 不想看到加工成可编辑的规则正文。 */
    static String generateRule(Context c, String focus, String avoid) throws Exception {
        String prompt = "你在给一个个人信息流写筛选规则。\n"
                + "特别关注：" + (focus == null || focus.trim().isEmpty() ? "（没填）" : focus.trim()) + "\n"
                + "不想看到：" + (avoid == null || avoid.trim().isEmpty() ? "（没填）" : avoid.trim()) + "\n\n"
                + "写一段 120 字以内的中文筛选规则，供另一个模型对视频标题做三分类：GLOW（值得看）、DIM（不想看）、普通。\n"
                + "要求：优先按「特别关注」判 GLOW；「不想看到」判 DIM；信息不足时保留普通。\n"
                + "只输出规则正文，不要标题、不要解释。";
        String rule = complete(c, prompt, 500);
        String clean = rule == null ? "" : rule.trim();
        if (clean.isEmpty()) throw new IllegalStateException("模型返回空规则");
        Prefs.setRule(c, clean);
        Prefs.bumpRuleVersion(c);
        clearCache(c);
        return clean;
    }

    /**
     * 原生 App 封面判断：按指纹批量判标题，**不写 VideoStore**（卡片不是缓存里的视频）。
     *
     * 与列表侧同一套规则/缓存/批次；缓存键 = native|指纹|规则版本|模型，
     * 返回与输入同序的结论，UNKNOWN 表示这次没判出来（不写缓存，下次重试）。
     * 必须在后台线程调用。
     */
    static int[] classifyNative(Context c, List<String> fingerprints, List<String> texts) {
        int n = fingerprints == null ? 0 : fingerprints.size();
        int[] out = new int[n];
        for (int i = 0; i < n; i++) out[i] = UNKNOWN;
        if (n == 0) return out;
        SharedPreferences cache = c.getSharedPreferences(CACHE_PREF, Context.MODE_PRIVATE);
        int version = Prefs.ruleVersion(c);
        String rule = Prefs.rule(c);
        List<Integer> pending = new ArrayList<>();
        int cachedCount = 0;
        int localCount = 0;
        for (int i = 0; i < n; i++) {
            String text = texts == null || i >= texts.size() ? "" : texts.get(i);
            int local = localRuleText(c, text);
            if (local != UNKNOWN) {
                out[i] = local;
                localCount++;
                continue;
            }
            int cached = cache.getInt(nativeKey(fingerprints.get(i), version), UNKNOWN);
            if (cached != UNKNOWN) {
                out[i] = cached;
                cachedCount++;
                continue;
            }
            pending.add(i);
        }
        int failed = 0;
        boolean hasKey = SecureStore.getKey(c) != null;
        if (!hasKey) {
            // 没有 Key 也要先把本地「特别关注/不想看到」判出来（初代列表侧同样先走本地规则）；
            // 剩下的保持 UNKNOWN，不写缓存，配好 Key 后重试。
            failed = pending.size();
        } else {
            for (int start = 0; start < pending.size(); start += MAX_BATCH) {
                List<Integer> batch = pending.subList(start, Math.min(pending.size(), start + MAX_BATCH));
                List<String> lines = new ArrayList<>();
                for (int idx : batch) lines.add(texts == null ? "" : texts.get(idx));
                try {
                    Map<Integer, Integer> got = sendRule(c, rule, lines);
                    for (int j = 0; j < batch.size(); j++) {
                        Integer r = got.get(j);
                        int at = batch.get(j);
                        if (r == null) {
                            failed++;
                            continue;
                        }
                        out[at] = r.intValue();
                        cache.edit().putInt(nativeKey(fingerprints.get(at), version), r.intValue()).apply();
                    }
                } catch (Throwable t) {
                    failed += batch.size();
                    Log.w(TAG, "native-judge-fail n=" + batch.size() + " err=" + shortError(t));
                }
            }
        }
        int glow = 0, dim = 0, unknown = 0;
        for (int r : out) {
            if (r == GLOW) glow++;
            else if (r == DIM) dim++;
            else if (r == UNKNOWN) unknown++;
        }
        Log.i(TAG, "native-judge n=" + n + " local=" + localCount + " cached=" + cachedCount
                + " pending=" + pending.size() + " failed=" + failed + " glow=" + glow
                + " dim=" + dim + " unknown=" + unknown + (hasKey ? "" : " key=missing"));
        return out;
    }

    /**
     * 只查本地关键词 + 持久判断缓存，**不发网络**；供主线程在 publish 前同步填充。
     *
     * 对应初代 `DeepSeekClassifier.cachedThree(rule, titles)`：滚动到曾经判过的卡片时，
     * 先按持久缓存把已知结论画出来，别让整帧卡在 `overlay-defer` 等一次 native-judge。
     * 键与 {@link #classifyNative} 完全一致（含 ruleVersion + PROMPT_VERSION），
     * 返回与输入同序，UNKNOWN 表示这条还没判过。
     */
    static int[] cachedNative(Context c, List<String> fingerprints, List<String> texts) {
        int n = fingerprints == null ? 0 : fingerprints.size();
        int[] out = new int[n];
        for (int i = 0; i < n; i++) out[i] = UNKNOWN;
        if (n == 0) return out;
        SharedPreferences cache = c.getSharedPreferences(CACHE_PREF, Context.MODE_PRIVATE);
        int version = Prefs.ruleVersion(c);
        List<String> focus = keywords(Prefs.focus(c));
        List<String> avoid = keywords(Prefs.avoid(c));
        for (int i = 0; i < n; i++) {
            String text = texts == null || i >= texts.size() ? "" : texts.get(i);
            int local = localRuleText(text, focus, avoid);
            if (local != UNKNOWN) {
                out[i] = local;
                continue;
            }
            out[i] = cache.getInt(nativeKey(fingerprints.get(i), version), UNKNOWN);
        }
        return out;
    }

    private static String nativeKey(String fingerprint, int version) {
        return "native|" + (fingerprint == null ? "" : fingerprint) + "|v" + version
                + "|" + PROMPT_VERSION + "|" + MODEL;
    }

    /** 测试连接：一次极小的 complete，成功即 Key 可用。 */
    static boolean testKey(Context c) throws Exception {
        String out = complete(c, "只回复：OK", 8);
        return out != null && !out.trim().isEmpty();
    }

    /** 一次 DeepSeek 调用（模型/温度/reasoning/max_tokens 固定）。 */
    static String complete(Context c, String prompt, int maxTokens) throws Exception {
        String key = SecureStore.getKey(c);
        if (key == null || key.isEmpty()) throw new IllegalStateException("没有保存 Key");
        JSONObject body = new JSONObject();
        body.put("model", MODEL);
        body.put("temperature", 0);
        body.put("reasoning_effort", "none");
        body.put("max_tokens", maxTokens);
        JSONArray msgs = new JSONArray();
        JSONObject m = new JSONObject();
        m.put("role", "user");
        m.put("content", prompt);
        msgs.put(m);
        body.put("messages", msgs);

        HttpURLConnection conn = (HttpURLConnection) new URL(API).openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(15000);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Authorization", "Bearer " + key);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }
        int code = conn.getResponseCode();
        if (code != 200) throw new IllegalStateException("HTTP " + code);
        StringBuilder resp = new StringBuilder();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) resp.append(line);
        }
        conn.disconnect();
        JSONObject root = new JSONObject(resp.toString());
        return root.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content");
    }

    static void clearCache(Context c) {
        c.getSharedPreferences(CACHE_PREF, Context.MODE_PRIVATE).edit().clear().apply();
    }

    private static String shortError(Throwable t) {
        String m = t.getMessage();
        return m == null || m.trim().isEmpty() ? t.getClass().getSimpleName() : m;
    }
}
