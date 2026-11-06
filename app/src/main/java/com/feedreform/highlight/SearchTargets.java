package com.feedreform.highlight;

/**
 * 平台搜索的 scheme 拼装。keyword 传已经 URL 编码好的串（调用方用 Uri.encode），
 * 这样这个类不依赖 Android，能在 JVM 单元测试里直接验证。
 */
final class SearchTargets {

    private SearchTargets() {
    }

    static String uri(String source, String encodedKeyword) {
        String base = "bili".equals(source) ? "bilibili://search" : "xhsdiscover://search/result";
        boolean hasKeyword = encodedKeyword != null && !encodedKeyword.isEmpty();
        return hasKeyword ? base + "?keyword=" + encodedKeyword : base;
    }
}
