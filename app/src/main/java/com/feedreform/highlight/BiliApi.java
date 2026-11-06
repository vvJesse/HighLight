package com.feedreform.highlight;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * B站 直连（不经任何自建服务器）。
 *
 * 固定手机 Chrome UA + Referer；Cookie 由 {@link SecureStore} 提供。
 * 推荐流与关注动态都要求本机 Cookie：没有 Cookie 直接报 needCookie，绝不用匿名接口冒充用户信息流。
 * 连接 8s / 读 15s，失败保留旧数据。日志只打条数、耗时、code、offset 长度——从不打 Cookie。
 */
final class BiliApi {

    static final String TAG = "HLBASE";
    static final String UA = "Mozilla/5.0 (Linux; Android 16; PLG110) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36";
    static final String REFERER = "https://www.bilibili.com/";

    static final String URL_NAV = "https://api.bilibili.com/x/web-interface/nav";
    static final String URL_RCMD =
            "https://api.bilibili.com/x/web-interface/wbi/index/top/feed/rcmd";
    static final String URL_FOLLOW = "https://api.bilibili.com/x/polymer/web-dynamic/v1/feed/all";

    private static final int CONNECT_MS = 8000;
    private static final int READ_MS = 15000;

    /** 标准 64 位 mixinKeyEncTab。 */
    private static final int[] MIXIN_KEY_ENC_TAB = {
            46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35,
            27, 43, 5, 49, 33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13,
            37, 48, 7, 16, 24, 55, 40, 61, 26, 17, 0, 1, 60, 51, 30, 4,
            22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36, 20, 34, 44, 52
    };

    private static volatile String mixinKey = "";
    private static volatile long mixinAt = 0;

    private BiliApi() {
    }

    static class BiliError extends Exception {
        final int code;
        final boolean needCookie;

        BiliError(int code, String message) {
            super(message);
            this.code = code;
            this.needCookie = code == -101;
        }
    }

    static final class Nav {
        int http = 0;
        int code = -1;
        boolean login = false;
        long ms = 0;
        String error = "";
    }

    static final class FollowPage {
        final List<VideoStore.Video> items = new ArrayList<>();
        boolean hasMore = false;
        String offset = "";
        int http = 0;
        int code = 0;
        long ms = 0;
    }

    /** 登录态：nav 的 isLogin；无 Cookie 直接算未登录，不发请求；Cookie 失效也不删本地缓存。 */
    static Nav nav(Context c) {
        Nav nav = new Nav();
        String cookie = SecureStore.getCookie(c);
        long t0 = System.currentTimeMillis();
        if (cookie == null || cookie.isEmpty()) {
            nav.code = -101;
            nav.error = "需要 B站 凭据";
            nav.ms = System.currentTimeMillis() - t0;
            Log.i(TAG, "bili-nav http=0 code=-101 login=false cookieLen=0 ms=" + nav.ms);
            return nav;
        }
        try {
            JSONObject root = getJson(URL_NAV, cookie);
            nav.http = 200;
            nav.code = root.optInt("code", -1);
            JSONObject data = root.optJSONObject("data");
            nav.login = data != null && data.optBoolean("isLogin", false);
            if (nav.code != 0 && nav.code != -101) {
                nav.error = root.optString("message", "");
            }
        } catch (BiliError e) {
            nav.code = e.code;
            nav.error = e.getMessage();
        } catch (Exception e) {
            nav.error = shortError(e);
        }
        nav.ms = System.currentTimeMillis() - t0;
        Log.i(TAG, "bili-nav http=" + nav.http + " code=" + nav.code + " login=" + nav.login
                + " cookieLen=" + (cookie == null ? 0 : cookie.length()) + " ms=" + nav.ms);
        return nav;
    }

    /** 推荐流：必须带本机 Cookie，30 条。freshIdx 每次下拉自增拿新一批。 */
    static List<VideoStore.Video> rcmd(Context c, int freshIdx) throws Exception {
        long t0 = System.currentTimeMillis();
        String cookie = SecureStore.getCookie(c);
        if (cookie == null || cookie.isEmpty()) {
            // 没有凭据就不发请求：匿名推荐是路人的信息流，不能当用户推荐展示。
            throw new BiliError(-101, "需要 B站 凭据");
        }
        String key = mixinKey(c);
        Map<String, String> params = new TreeMap<>();
        params.put("fresh_type", "3");
        params.put("ps", "30");
        params.put("fresh_idx", String.valueOf(Math.max(1, freshIdx)));
        params.put("feed_version", "V8");
        String query = sign(params, key);

        JSONObject root = getJson(URL_RCMD + "?" + query, cookie);
        int code = root.optInt("code", -1);
        if (code != 0) throw new BiliError(code, root.optString("message", "code=" + code));
        JSONObject data = root.optJSONObject("data");
        JSONArray arr = data == null ? null : data.optJSONArray("item");
        List<VideoStore.Video> out = new ArrayList<>();
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                VideoStore.Video v = new VideoStore.Video();
                v.bvid = o.optString("bvid", "");
                if (v.bvid.isEmpty()) {
                    String id = o.optString("id", "");
                    if (id.startsWith("BV")) v.bvid = id;
                }
                if (v.bvid.isEmpty()) continue;
                v.title = o.optString("title", "");
                v.cover = https(o.optString("pic", ""));
                v.duration = Times.duration(o.isNull("duration") ? null : o.opt("duration"));
                JSONObject owner = o.optJSONObject("owner");
                if (owner != null) {
                    v.author = owner.optString("name", "");
                    v.mid = Times.asSeconds(owner.isNull("mid") ? null : owner.opt("mid"));
                }
                v.pubTs = Times.asSeconds(o.isNull("pubdate") ? null : o.opt("pubdate"));
                v.source = "rcmd";
                out.add(v);
            }
        }
        long ms = System.currentTimeMillis() - t0;
        Log.i(TAG, "bili-rcmd freshIdx=" + freshIdx + " n=" + out.size() + " code=" + code
                + " cookieLen=" + cookie.length() + " ms=" + ms);
        if (out.isEmpty()) throw new BiliError(code, "推荐流为空");
        return out;
    }

    /**
     * 关注动态：每页 20 条，offset 原样回传。
     * 无 Cookie 时服务端回 code=-101，这里抛 BiliError（needCookie=true）。
     */
    static FollowPage follow(Context c, int page, String offset) throws Exception {
        long t0 = System.currentTimeMillis();
        String cookie = SecureStore.getCookie(c);
        if (cookie == null || cookie.isEmpty()) {
            throw new BiliError(-101, "需要更新 Cookie");
        }
        StringBuilder url = new StringBuilder(URL_FOLLOW)
                .append("?type=video&page=").append(Math.max(1, page))
                .append("&timezone_offset=-480");
        if (offset != null && !offset.isEmpty()) {
            url.append("&offset=").append(URLEncoder.encode(offset, "UTF-8"));
        }
        JSONObject root = getJson(url.toString(), cookie);
        int code = root.optInt("code", -1);
        if (code != 0) throw new BiliError(code, root.optString("message", "code=" + code));
        JSONObject data = root.optJSONObject("data");
        FollowPage out = new FollowPage();
        out.http = 200;
        out.code = code;
        if (data != null) {
            out.hasMore = data.optBoolean("has_more", false);
            out.offset = data.optString("offset", "");
            JSONArray items = data.optJSONArray("items");
            if (items != null) {
                for (int i = 0; i < items.length(); i++) {
                    JSONObject it = items.optJSONObject(i);
                    if (it == null) continue;
                    JSONObject mods = it.optJSONObject("modules");
                    if (mods == null) continue;
                    JSONObject dyn = mods.optJSONObject("module_dynamic");
                    JSONObject major = dyn == null ? null : dyn.optJSONObject("major");
                    JSONObject archive = major == null ? null : major.optJSONObject("archive");
                    if (archive == null) continue;
                    JSONObject author = mods.optJSONObject("module_author");
                    VideoStore.Video v = new VideoStore.Video();
                    v.bvid = archive.optString("bvid", "");
                    if (v.bvid.isEmpty()) continue;
                    v.title = archive.optString("title", "");
                    v.cover = https(archive.optString("cover", ""));
                    v.duration = Times.duration(archive.isNull("duration_text") ? null : archive.opt("duration_text"));
                    if (author != null) {
                        v.author = author.optString("name", "");
                        v.mid = Times.asSeconds(author.isNull("mid") ? null : author.opt("mid"));
                        // pub_ts 是字符串，不要当数字解析。
                        v.pubTs = Times.asSeconds(author.isNull("pub_ts") ? null : author.opt("pub_ts"));
                    }
                    v.source = "follow";
                    out.items.add(v);
                }
            }
        }
        out.ms = System.currentTimeMillis() - t0;
        Log.i(TAG, "bili-follow page=" + page + " n=" + out.items.size() + " hasMore=" + out.hasMore
                + " offsetLen=" + out.offset.length() + " code=" + code + " ms=" + out.ms);
        return out;
    }

    // ---------- WBI ----------

    private static String mixinKey(Context c) throws Exception {
        long now = System.currentTimeMillis();
        if (!mixinKey.isEmpty() && now - mixinAt < 30L * 60L * 1000L) return mixinKey;
        JSONObject root = getJson(URL_NAV, SecureStore.getCookie(c));
        JSONObject data = root.optJSONObject("data");
        JSONObject img = data == null ? null : data.optJSONObject("wbi_img");
        if (img == null) throw new BiliError(root.optInt("code", -1), "取不到 wbi key");
        String imgKey = fileKey(img.optString("img_url", ""));
        String subKey = fileKey(img.optString("sub_url", ""));
        mixinKey = mixin(imgKey, subKey);
        mixinAt = now;
        Log.i(TAG, "wbi-key imgLen=" + imgKey.length() + " subLen=" + subKey.length()
                + " mixinLen=" + mixinKey.length());
        return mixinKey;
    }

    private static String fileKey(String url) {
        String name = url.substring(url.lastIndexOf('/') + 1);
        int dot = name.indexOf('.');
        return dot >= 0 ? name.substring(0, dot) : name;
    }

    private static String mixin(String imgKey, String subKey) {
        String raw = imgKey + subKey;
        StringBuilder sb = new StringBuilder();
        for (int idx : MIXIN_KEY_ENC_TAB) {
            if (idx < raw.length()) sb.append(raw.charAt(idx));
        }
        String k = sb.toString();
        return k.length() > 32 ? k.substring(0, 32) : k;
    }

    /** 值过滤 !'()* → key 排序 → 追加 wts → w_rid = md5(query + mixinKey)。 */
    private static String sign(Map<String, String> params, String mixinKey) throws Exception {
        params.put("wts", String.valueOf(System.currentTimeMillis() / 1000));
        List<String> keys = new ArrayList<>(params.keySet());
        Collections.sort(keys);
        StringBuilder q = new StringBuilder();
        for (String k : keys) {
            String v = params.get(k);
            if (v == null) v = "";
            v = v.replaceAll("[!'()*]", "");
            if (q.length() > 0) q.append('&');
            q.append(URLEncoder.encode(k, "UTF-8")).append('=').append(URLEncoder.encode(v, "UTF-8"));
        }
        String wRid = md5Hex(q.toString() + mixinKey);
        return q + "&w_rid=" + wRid;
    }

    private static String md5Hex(String s) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format("%02x", b & 0xff));
        return sb.toString();
    }

    // ---------- HTTP ----------

    private static JSONObject getJson(String url, String cookie) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(CONNECT_MS);
        conn.setReadTimeout(READ_MS);
        conn.setRequestProperty("User-Agent", UA);
        conn.setRequestProperty("Referer", REFERER);
        conn.setRequestProperty("Accept", "application/json");
        if (cookie != null && !cookie.isEmpty()) conn.setRequestProperty("Cookie", cookie);
        try {
            int http = conn.getResponseCode();
            if (http < 200 || http >= 300) {
                Log.w(TAG, "bili-http code=" + http + " url=" + url);
                throw new BiliError(http, "HTTP " + http);
            }
            try (InputStream in = conn.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                return new JSONObject(out.toString(StandardCharsets.UTF_8.name()));
            }
        } finally {
            conn.disconnect();
        }
    }

    /** 封面统一升级成 https：接口给的是 http://，targetSdk 36 默认拒明文。 */
    static String https(String url) {
        if (url == null) return "";
        String clean = url.trim();
        if (clean.startsWith("//")) return "https:" + clean;
        if (clean.startsWith("http://")) return "https://" + clean.substring(7);
        return clean;
    }

    private static String shortError(Exception e) {
        String m = e.getMessage();
        return m == null || m.trim().isEmpty() ? e.getClass().getSimpleName() : m;
    }
}
