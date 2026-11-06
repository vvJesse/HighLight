package com.feedreform.highlight;

import java.util.Locale;

/**
 * 返回气泡上的计时文案：mm:ss（气泡 30 分钟自动收起，所以没有小时位）。
 * 纯 Java，方便单元测试；{@link ReturnBubbleService} 只负责每秒调用。
 */
final class BubbleClock {

    private BubbleClock() {
    }

    static String text(long elapsedMillis) {
        long total = Math.max(0L, elapsedMillis / 1000L);
        return String.format(Locale.US, "← 返回亮点 · %d:%02d", total / 60L, total % 60L);
    }
}
