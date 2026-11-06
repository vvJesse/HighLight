package com.feedreform.highlight;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** 首页顶部状态行的数量文案，纯字符串逻辑，方便单元测试。 */
final class FeedCounts {

    private static final DateTimeFormatter CAPTURED_AT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private FeedCounts() {
    }

    /**
     * 服务器 captured_at → 状态行里的时间标签。
     * 今天只显示 HH:mm；非今天补日期（同年 M月d日，跨年带年份），
     * 免得隔夜批次显示成「更新于 22:38」被误读成今早。解析失败返回空串。
     */
    static String updateLabel(String capturedAt, LocalDateTime now) {
        if (capturedAt == null || capturedAt.trim().isEmpty()) return "";
        LocalDateTime at;
        try {
            at = LocalDateTime.parse(capturedAt.trim(), CAPTURED_AT);
        } catch (Exception e) {
            return "";
        }
        String hm = String.format(Locale.ROOT, "%02d:%02d", at.getHour(), at.getMinute());
        if (at.toLocalDate().equals(now.toLocalDate())) return hm;
        if (at.getYear() == now.getYear()) {
            return at.getMonthValue() + "月" + at.getDayOfMonth() + "日 " + hm;
        }
        return at.getYear() + "年" + at.getMonthValue() + "月" + at.getDayOfMonth() + "日 " + hm;
    }

    /** 「共 30 条 · 已过滤 19 条 · 更新于 16:14」；过滤数为 0 或时间为空时对应片段省略。 */
    static String statusLine(int total, int filtered, String time) {
        StringBuilder line = new StringBuilder("共 ").append(Math.max(0, total)).append(" 条");
        if (filtered > 0) line.append(" · 已过滤 ").append(filtered).append(" 条");
        if (time != null && !time.isEmpty()) line.append(" · 更新于 ").append(time);
        return line.toString();
    }
}
