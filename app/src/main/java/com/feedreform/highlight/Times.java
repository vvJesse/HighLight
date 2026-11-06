package com.feedreform.highlight;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** 关注流的时间/时长文案。只做展示，解析失败一律返回空串而不是抛异常。 */
final class Times {

    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);
    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT);
    private static final DateTimeFormatter DATE_TIME_SHORT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT);

    private Times() {}

    /** 视频发布时间：今天 10:20 / 昨天 21:48 / 9月30日 / 2025年12月3日。 */
    static String label(Object pubdate, LocalDateTime now) {
        long sec = asSeconds(pubdate);
        if (sec <= 0 || now == null) return "";
        LocalDateTime at = LocalDateTime.ofInstant(Instant.ofEpochSecond(sec), ZoneId.systemDefault());
        if (at.toLocalDate().equals(now.toLocalDate())) return "今天 " + at.format(HHMM);
        if (at.toLocalDate().equals(now.toLocalDate().minusDays(1))) return "昨天 " + at.format(HHMM);
        if (at.getYear() == now.getYear()) return at.getMonthValue() + "月" + at.getDayOfMonth() + "日";
        return at.getYear() + "年" + at.getMonthValue() + "月" + at.getDayOfMonth() + "日";
    }

    /** 顶部「更新于」；服务端可能给 "yyyy-MM-dd HH:mm:ss"、ISO 或 Unix 秒，都尽量认。 */
    static String updatedLabel(String raw, LocalDateTime now) {
        if (raw == null || raw.trim().isEmpty() || now == null) return "";
        String s = raw.trim();
        LocalDateTime at = null;
        try {
            at = LocalDateTime.parse(s, DATE_TIME);
        } catch (Exception ignored) {
        }
        if (at == null) {
            try {
                at = LocalDateTime.parse(s, DATE_TIME_SHORT);
            } catch (Exception ignored) {
            }
        }
        if (at == null) {
            try {
                at = LocalDateTime.parse(s);
            } catch (Exception ignored) {
            }
        }
        if (at == null) {
            try {
                at = java.time.OffsetDateTime.parse(s).toLocalDateTime();
            } catch (Exception ignored) {
            }
        }
        if (at == null) {
            try {
                at = java.time.Instant.parse(s)
                        .atZone(ZoneId.systemDefault()).toLocalDateTime();
            } catch (Exception ignored) {
            }
        }
        if (at == null) {
            long sec = asSeconds(s);
            if (sec > 0) at = LocalDateTime.ofInstant(Instant.ofEpochSecond(sec), ZoneId.systemDefault());
        }
        if (at == null) return "";
        String hm = at.format(HHMM);
        if (at.toLocalDate().equals(now.toLocalDate())) return hm;
        if (at.getYear() == now.getYear()) return at.getMonthValue() + "月" + at.getDayOfMonth() + "日 " + hm;
        return at.getYear() + "年" + at.getMonthValue() + "月" + at.getDayOfMonth() + "日 " + hm;
    }

    /** 时长角标：支持 763（秒）和 "12:48" 两种字段形态。 */
    static String duration(Object raw) {
        if (raw == null) return "";
        if (raw instanceof Number) return clock(((Number) raw).longValue());
        String s = String.valueOf(raw).trim();
        if (s.isEmpty()) return "";
        if (s.matches("\\d+")) {
            try {
                return clock(Long.parseLong(s));
            } catch (Exception e) {
                return "";
            }
        }
        String[] parts = s.split(":");
        try {
            if (parts.length == 2) {
                return pad2(Long.parseLong(parts[0])) + ":" + pad2(Long.parseLong(parts[1]));
            }
            if (parts.length == 3) {
                return parts[0] + ":" + pad2(Long.parseLong(parts[1])) + ":" + pad2(Long.parseLong(parts[2]));
            }
        } catch (Exception ignored) {
        }
        return s;
    }

    static long asSeconds(Object v) {
        if (v instanceof Number) return ((Number) v).longValue();
        if (v instanceof String) {
            String s = ((String) v).trim();
            if (s.matches("\\d+")) {
                try {
                    return Long.parseLong(s);
                } catch (Exception ignored) {
                }
            }
        }
        return 0;
    }

    private static String clock(long sec) {
        if (sec < 0) return "";
        if (sec >= 3600) {
            return String.format(Locale.ROOT, "%d:%02d:%02d", sec / 3600, (sec % 3600) / 60, sec % 60);
        }
        return String.format(Locale.ROOT, "%02d:%02d", sec / 60, sec % 60);
    }

    private static String pad2(long v) {
        return String.format(Locale.ROOT, "%02d", v);
    }
}
