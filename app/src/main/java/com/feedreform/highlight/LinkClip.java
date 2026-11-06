package com.feedreform.highlight;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 剪贴板分享文案里的链接识别（纯逻辑，不依赖 Android）。
 *
 * <p>支持小红书（xhslink.cn / xhslink.com / xiaohongshu.com）与 B站（b23.tv / bilibili.com）：
 * 先找带 scheme 的 http(s) 链接，没有再找文案里裸写的域名并补 https://。
 * 分享文案里经常还带一句「复制本条信息…」，这里只负责取链接，不改动用户剪贴板。</p>
 */
final class LinkClip {

    /** 分享文案里可能出现的空白、中文标点和成对括号，URL 尾部遇到它们就截断。 */
    private static final String URL_STOP = "\\s<>\"'，。！？；：、（）【】《》「」『』";

    private static final Pattern HTTP_URL = Pattern.compile("(?i)https?://[^" + URL_STOP + "]+");

    /** 没有 scheme 的裸域名；只认本项目支持的站点，避免把普通英文句子当链接。 */
    private static final Pattern BARE_URL = Pattern.compile(
            "(?i)(?<![\\w@])(?:[a-z0-9-]+\\.)*(?:xhslink\\.cn|xhslink\\.com|xiaohongshu\\.com|b23\\.tv|bilibili\\.com)"
                    + "(?::\\d+)?(?:/[^" + URL_STOP + "]*)?(?![\\w.-])");

    private static final String[] XHS_HOSTS = {"xhslink.cn", "xhslink.com", "xiaohongshu.com"};
    private static final String[] BILI_HOSTS = {"b23.tv", "bilibili.com"};

    private static final String TRIM_CHARS =
            " \t\r\n.,;:!?，。；：！？、…—·|/\\()（）【】[]{}<>《》「」『』\"'“”‘’";

    private LinkClip() {}

    /**
     * 从一段分享文案中取第一个可解析的受支持链接。
     *
     * @return 补全 scheme 后的 URL；没有受支持链接时返回 null。
     */
    static String findSupported(String text) {
        if (text == null) return null;

        Matcher http = HTTP_URL.matcher(text);
        while (http.find()) {
            String candidate = trimUrl(http.group());
            if (isSupported(candidate)) return normalize(candidate);
        }

        Matcher bare = BARE_URL.matcher(text);
        while (bare.find()) {
            String candidate = trimUrl(bare.group());
            if (isSupported(candidate)) return normalize(candidate);
        }
        return null;
    }

    /** 是否为本 App 能识别的站点。 */
    static boolean isSupported(String url) {
        String host = hostOf(url);
        return hostMatches(host, XHS_HOSTS) || hostMatches(host, BILI_HOSTS);
    }

    /** 按域名判断来源（bili / xhs）；不支持的域名返回空串。 */
    static String sourceOf(String url) {
        String host = hostOf(url);
        if (hostMatches(host, BILI_HOSTS)) return "bili";
        if (hostMatches(host, XHS_HOSTS)) return "xhs";
        return "";
    }

    /** 没有 scheme 的链接补 https://；已有 http(s) 的原样保留。 */
    static String normalize(String url) {
        String s = trimUrl(url);
        if (s == null) return "";
        String lower = s.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) s = "https://" + s;
        return s;
    }

    static String hostOf(String url) {
        String s = normalize(url);
        if (s.isEmpty()) return "";
        try {
            String host = new URI(s).getHost();
            if (host != null && !host.isEmpty()) return host.toLowerCase(Locale.ROOT);
        } catch (Exception ignored) {
        }
        // 分享链接里偶尔有 URI 不接受的字符；退回手工截 host，保证仍能判断来源。
        int start = s.indexOf("://");
        if (start < 0) return "";
        start += 3;
        int end = s.length();
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '/' || c == '?' || c == '#' || c == ':') {
                end = i;
                break;
            }
        }
        if (end <= start) return "";
        String host = s.substring(start, end);
        int at = host.lastIndexOf('@');
        if (at >= 0) host = host.substring(at + 1);
        return host.toLowerCase(Locale.ROOT);
    }

    /** 上次处理的 URL 指纹；同一链接再次切回前台时不重复弹层。 */
    static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) out.append(String.format(Locale.ROOT, "%02x", b));
            return out.toString();
        } catch (Exception e) {
            return Integer.toHexString(value == null ? 0 : value.hashCode());
        }
    }

    /**
     * 解析失败时仍允许保存，但不能伪造笔记 id：用 URL 哈希生成稳定临时 id，
     * 先解出真实 id 时不会与它冲突。
     */
    static String temporaryId(String url) {
        String hash = sha256(normalize(url));
        return "url_" + hash.substring(0, Math.min(16, hash.length()));
    }

    /**
     * 从分享文案里估一个标题，只用于「解析失败仍保存」的兜底；
     * 解析成功时标题以公开链路的 og:title / 视频接口为准。
     */
    static String guessTitle(String text, String fallbackUrl) {
        String source = text == null ? "" : text;

        Matcher bracket = Pattern.compile("【([^】]{1,100})】").matcher(source);
        while (bracket.find()) {
            String inner = bracket.group(1).trim();
            // 小红书的分享尾句是「打开【小红书】App…」，不是标题。
            if (inner.equals("小红书") || inner.equals("哔哩哔哩") || inner.equals("B站")) continue;
            String title = cleanTitle(inner.replaceFirst("\\s*[-—_]\\s*哔哩哔哩\\s*$", ""));
            if (!title.isEmpty()) return truncate(title, 60);
        }

        String withoutUrls = HTTP_URL.matcher(source).replaceAll(" ");
        withoutUrls = BARE_URL.matcher(withoutUrls).replaceAll(" ");
        for (String line : withoutUrls.split("\\r?\\n")) {
            String title = cleanTitle(line
                    .replaceAll("复制本条信息.*$", " ")
                    .replaceAll("复制后打开.*$", " ")
                    .replaceAll("(打开|直达)【?小红书】?.*$", " ")
                    .replaceAll("存好口令.*$", " ")
                    .replaceAll("发布了一篇小红书笔记.*$", " ")
                    .replace("快来看吧", " "));
            if (!title.isEmpty()) return truncate(title, 60);
        }
        return fallbackUrl == null ? "未解析链接" : fallbackUrl;
    }

    /** 未解析保存时把标记写进标题，收藏页里一眼能看出不是正常解析结果。 */
    static String markUnresolved(String title) {
        String clean = title == null ? "" : title.trim();
        if (clean.isEmpty()) clean = "未解析链接";
        if (!clean.contains("未解析")) clean = clean + "（未解析）";
        return truncate(clean, 80);
    }

    private static boolean hostMatches(String host, String[] domains) {
        if (host == null || host.isEmpty()) return false;
        for (String domain : domains) {
            if (host.equals(domain) || host.endsWith("." + domain)) return true;
        }
        return false;
    }

    /** 去掉分享文案里 URL 末尾跟着的中文标点、英文标点和括号。 */
    private static String trimUrl(String raw) {
        if (raw == null) return null;
        int start = 0;
        int end = raw.length();
        while (start < end && TRIM_CHARS.indexOf(raw.charAt(start)) >= 0) start++;
        while (end > start && TRIM_CHARS.indexOf(raw.charAt(end - 1)) >= 0) end--;
        return raw.substring(start, end);
    }

    private static String cleanTitle(String raw) {
        if (raw == null) return "";
        String s = raw.replace('\u00a0', ' ').replaceAll("\\s+", " ").trim();
        int start = 0;
        int end = s.length();
        while (start < end && TRIM_CHARS.indexOf(s.charAt(start)) >= 0) start++;
        while (end > start && TRIM_CHARS.indexOf(s.charAt(end - 1)) >= 0) end--;
        return s.substring(start, end).trim();
    }

    private static String truncate(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }
}
