package com.feedreform.highlight;

import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 只用公开链路解析分享链接（不经过任何服务器）：
 *
 * - B站：短链 GET 跟随 302 → 从最终 URL 取 bvid → `x/web-interface/view?bvid=` 拿标题/封面/作者；
 * - 小红书：短链 GET 跟随 302 → `/discovery/item/<id>` 或 `/explore/<id>`；
 *   再 GET 页面，从 og:title / og:image 取标题与封面。被登录墙/风控拦下时如实返回「只拿到链接」，
 *   不伪造标题、不伪造封面。
 *
 * 解析失败不抛给界面：返回带 note 的结果，保存动作照常完成。
 */
final class LinkResolver {

    private static final String TAG = "HLBASE";
    private static final String MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 14; PLG110) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/126.0.0.0 Mobile Safari/537.36";
    private static final Pattern BVID = Pattern.compile("BV[0-9A-Za-z]{10}");
    /** 老视频只有 av 号：b23.tv/av170001 会 302 到 /video/av170001。 */
    private static final Pattern AVID = Pattern.compile("(?i)(?<![0-9A-Za-z])av(\\d{1,12})");
    private static final Pattern XHS_ID = Pattern.compile(
            "(?:/discovery/item/|/explore/|/note/)([0-9a-fA-F]{16,32})");

    static final class Result {
        String source = "";
        String id = "";
        String title = "";
        String cover = "";
        String author = "";
        String duration = "";
        /** 最终落地链接（小红书原链接保留 xsec_token）。 */
        String url = "";
        /** 给界面看的实话：解析到了什么、被拦在哪儿。 */
        String note = "";
        boolean ok = false;
    }

    private LinkResolver() {
    }

    static Result resolve(String rawUrl) {
        Result r = new Result();
        String url = LinkClip.normalize(rawUrl);
        r.url = url;
        r.source = LinkClip.sourceOf(url);
        if (r.source.isEmpty()) {
            r.note = "不认识的链接";
            return r;
        }
        try {
            if (SavedStore.SOURCE_BILI.equals(r.source)) {
                resolveBili(r);
            } else {
                resolveXhs(r);
            }
        } catch (Exception e) {
            r.note = r.note.isEmpty() ? "解析失败：" + shortReason(e) : r.note;
            Log.w(TAG, "link-resolve failed source=" + r.source + " err=" + shortReason(e));
        }
        Log.i(TAG, "link-resolve source=" + r.source + " idLen=" + r.id.length()
                + " ok=" + r.ok + " titleLen=" + r.title.length()
                + " cover=" + (r.cover.isEmpty() ? 0 : 1) + " note=" + r.note);
        return r;
    }

    // ---------- B站 ----------

    private static void resolveBili(Result r) throws Exception {
        String finalUrl = r.url;
        String bvid = firstBvid(finalUrl);
        String avid = bvid.isEmpty() ? firstAvid(finalUrl) : "";
        if (bvid.isEmpty() && avid.isEmpty()) {
            finalUrl = follow(finalUrl, BiliApi.UA);
            r.url = finalUrl;
            bvid = firstBvid(finalUrl);
            avid = bvid.isEmpty() ? firstAvid(finalUrl) : "";
        }
        if (bvid.isEmpty() && avid.isEmpty()) {
            r.note = "链接里没找到视频号";
            return;
        }
        r.id = bvid.isEmpty() ? "av" + avid : bvid;
        String api = bvid.isEmpty()
                ? "https://api.bilibili.com/x/web-interface/view?aid=" + avid
                : "https://api.bilibili.com/x/web-interface/view?bvid=" + bvid;
        String body = get(api, BiliApi.UA, BiliApi.REFERER);
        JSONObject root = new JSONObject(body);
        if (root.optInt("code", -1) != 0) {
            r.note = "视频接口返回 code=" + root.optInt("code", -1);
            return;
        }
        JSONObject data = root.optJSONObject("data");
        if (data == null) {
            r.note = "视频接口没有数据";
            return;
        }
        String realBvid = data.optString("bvid", "");
        if (!realBvid.isEmpty()) r.id = realBvid;
        r.title = data.optString("title", "");
        r.cover = BiliApi.https(data.optString("pic", ""));
        JSONObject owner = data.optJSONObject("owner");
        if (owner != null) r.author = owner.optString("name", "");
        int seconds = data.optInt("duration", 0);
        if (seconds > 0) r.duration = Times.duration(seconds);
        r.ok = !r.title.isEmpty();
        r.note = r.ok ? "已读取标题、封面和作者" : "只拿到视频号";
        r.url = "https://www.bilibili.com/video/" + (realBvid.isEmpty() ? r.id : realBvid);
    }

    private static String firstBvid(String url) {
        if (url == null) return "";
        Matcher m = BVID.matcher(url);
        return m.find() ? m.group() : "";
    }

    private static String firstAvid(String url) {
        if (url == null) return "";
        Matcher m = AVID.matcher(url);
        return m.find() ? m.group(1) : "";
    }

    // ---------- 小红书 ----------

    private static void resolveXhs(Result r) throws Exception {
        String finalUrl = r.url;
        String id = xhsId(finalUrl);
        if (id.isEmpty()) {
            finalUrl = follow(finalUrl, MOBILE_UA);
            r.url = finalUrl;
            id = xhsId(finalUrl);
        }
        if (id.isEmpty()) {
            r.note = "跳转后没找到笔记号";
            return;
        }
        r.id = id;
        // 页面通常要登录态才给完整 meta；拿不到就如实标「只拿到链接」。
        try {
            String html = get(finalUrl, MOBILE_UA, "https://www.xiaohongshu.com/");
            String title = cleanXhsTitle(meta(html, "og:title"));
            String image = BiliApi.https(unescape(meta(html, "og:image")));
            if (!image.startsWith("http")) image = "";
            if (!title.isEmpty()) {
                r.title = title;
                r.cover = image;
                r.ok = true;
                r.note = image.isEmpty() ? "已读取标题" : "已读取标题和封面";
            } else {
                r.note = "被拦在登录墙/风控外，只拿到链接";
            }
        } catch (Exception e) {
            r.note = "页面打不开（" + shortReason(e) + "），只拿到链接";
        }
    }

    /**
     * 小红书被风控时返回的页面会给出「- 小红书」这类通用标题：去掉站点后缀后太短就当作没拿到，
     * 不把站名当笔记标题展示。
     */
    private static String cleanXhsTitle(String raw) {
        String t = unescape(raw == null ? "" : raw).trim();
        t = t.replaceAll("\\s*[-—_|]\\s*小红书\\s*$", "").trim();
        if (t.equals("小红书") || t.length() < 2) return "";
        return t;
    }

    private static String xhsId(String url) {
        if (url == null) return "";
        Matcher m = XHS_ID.matcher(url);
        return m.find() ? m.group(1) : "";
    }

    /** 只认 <meta property="og:title" content="…"> 这种写法，兼容属性顺序。 */
    private static String meta(String html, String key) {
        if (html == null) return "";
        Pattern p = Pattern.compile(
                "<meta[^>]+(?:property|name)=[\"']" + Pattern.quote(key) + "[\"'][^>]*>",
                Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(html);
        if (!m.find()) return "";
        String tag = m.group();
        Matcher c = Pattern.compile("content=[\"']([^\"']*)[\"']", Pattern.CASE_INSENSITIVE)
                .matcher(tag);
        return c.find() ? c.group(1) : "";
    }

    private static String unescape(String s) {
        return s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'");
    }

    // ---------- HTTP ----------

    /** GET 并跟随 302，返回最终 URL；HEAD 在 xhslink.cn 上会 404（见 pitfalls #34）。 */
    static String follow(String url, String ua) throws Exception {
        HttpURLConnection conn = open(url, ua, null);
        conn.setInstanceFollowRedirects(true);
        try {
            conn.getResponseCode();
            return conn.getURL().toString();
        } finally {
            conn.disconnect();
        }
    }

    private static String get(String url, String ua, String referer) throws Exception {
        HttpURLConnection conn = open(url, ua, referer);
        try {
            int code = conn.getResponseCode();
            InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            if (in == null) throw new IllegalStateException("HTTP " + code);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            int total = 0;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                total += n;
                if (total > 512 * 1024) break;
            }
            in.close();
            return out.toString(StandardCharsets.UTF_8.name());
        } finally {
            conn.disconnect();
        }
    }

    private static HttpURLConnection open(String url, String ua, String referer) throws Exception {
        String full = url == null ? "" : url.trim();
        if (full.startsWith("//")) full = "https:" + full;
        if (!full.toLowerCase(Locale.ROOT).startsWith("http")) {
            throw new IllegalArgumentException("不是 http 链接");
        }
        HttpURLConnection conn = (HttpURLConnection) new URL(full).openConnection();
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(15000);
        conn.setRequestProperty("User-Agent", ua);
        conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");
        if (referer != null) conn.setRequestProperty("Referer", referer);
        return conn;
    }

    private static String shortReason(Exception e) {
        String m = e.getMessage();
        if (m == null || m.isEmpty()) return e.getClass().getSimpleName();
        return m.length() > 60 ? m.substring(0, 60) : m;
    }
}
