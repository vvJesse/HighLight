package com.feedreform.highlight;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** 返回气泡计时文案：mm:ss，30 分钟上限内不需要小时位。 */
public class BubbleClockTest {

    @Test
    public void zeroMeansStart() {
        assertEquals("← 返回亮点 · 0:00", BubbleClock.text(0));
    }

    @Test
    public void secondsUnderOneMinute() {
        assertEquals("← 返回亮点 · 0:07", BubbleClock.text(7_000));
        assertEquals("← 返回亮点 · 0:59", BubbleClock.text(59_999));
    }

    @Test
    public void wholeMinutesRollOver() {
        assertEquals("← 返回亮点 · 1:00", BubbleClock.text(60_000));
        assertEquals("← 返回亮点 · 2:05", BubbleClock.text(125_000));
    }

    @Test
    public void nearAutoHideBoundary() {
        assertEquals("← 返回亮点 · 29:59", BubbleClock.text(29 * 60_000L + 59_000L));
    }

    @Test
    public void minutesAreNotClampedToTwoDigits() {
        assertEquals("← 返回亮点 · 59:59", BubbleClock.text(3_599_000L));
        assertEquals("← 返回亮点 · 62:03", BubbleClock.text(3_723_000L));
    }

    @Test
    public void negativeElapsedShowsZero() {
        assertEquals("← 返回亮点 · 0:00", BubbleClock.text(-5_000L));
    }
}
