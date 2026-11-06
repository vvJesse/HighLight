package com.feedreform.highlight;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.time.LocalDateTime;

/** 顶部状态行：「共 N 条 · 已过滤 M 条 · 更新于 HH:MM」。 */
public class FeedCountsTest {

    @Test
    public void fullLineWithCountsAndTime() {
        assertEquals("共 30 条 · 已过滤 19 条 · 更新于 16:14",
                FeedCounts.statusLine(30, 19, "16:14"));
    }

    @Test
    public void noFilteredCardsDropsFilterSegment() {
        assertEquals("共 30 条 · 更新于 16:14", FeedCounts.statusLine(30, 0, "16:14"));
    }

    @Test
    public void noTimeDropsUpdateSegment() {
        assertEquals("共 30 条 · 已过滤 19 条", FeedCounts.statusLine(30, 19, ""));
        assertEquals("共 30 条 · 已过滤 19 条", FeedCounts.statusLine(30, 19, null));
    }

    @Test
    public void emptyFeedStillReadable() {
        assertEquals("共 0 条", FeedCounts.statusLine(0, 0, ""));
    }

    @Test
    public void negativeTotalIsClamped() {
        assertEquals("共 0 条", FeedCounts.statusLine(-3, 0, ""));
    }

    @Test
    public void updateLabelTodayShowsOnlyTime() {
        assertEquals("16:14", FeedCounts.updateLabel("2026-10-22 16:14:09",
                LocalDateTime.of(2026, 10, 22, 20, 0)));
    }

    @Test
    public void updateLabelOtherDayKeepsDate() {
        assertEquals("10月21日 22:38", FeedCounts.updateLabel("2026-10-21 22:38:13",
                LocalDateTime.of(2026, 10, 22, 10, 58)));
    }

    @Test
    public void updateLabelOtherYearKeepsYear() {
        assertEquals("2025年12月31日 08:05", FeedCounts.updateLabel("2025-12-31 08:05:00",
                LocalDateTime.of(2026, 1, 1, 0, 30)));
    }

    @Test
    public void updateLabelUnparseableIsOmitted() {
        assertEquals("", FeedCounts.updateLabel("", LocalDateTime.of(2026, 10, 22, 10, 0)));
        assertEquals("", FeedCounts.updateLabel("昨晚", LocalDateTime.of(2026, 10, 22, 10, 0)));
        assertEquals("", FeedCounts.updateLabel(null, LocalDateTime.of(2026, 10, 22, 10, 0)));
    }
}
