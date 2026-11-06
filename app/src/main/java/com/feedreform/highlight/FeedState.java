package com.feedreform.highlight;

/** 主线程的布局/运动时钟：跟判断网络完全解耦，只用来决定「停手了没有」。 */
final class FeedState {

    private final long settleMs;
    private long epoch;
    private long stableAfter;
    private long unknownSince = -1;
    private String layout;

    FeedState(long settleMs) {
        this.settleMs = settleMs;
    }

    long epoch() {
        return epoch;
    }

    void invalidate() {
        epoch++;
        layout = null;
        unknownSince = -1;
        stableAfter = 0;
    }

    void motion(long now) {
        epoch++;
        stableAfter = now + settleMs;
    }

    boolean observe(String next, long now) {
        boolean changed = !next.equals(layout);
        if (changed) {
            epoch++;
            layout = next;
            stableAfter = now + settleMs;
        }
        if (unknownSince >= 0) stableAfter = Math.max(stableAfter, now + settleMs);
        unknownSince = -1;
        return changed;
    }

    boolean unknown(long now) {
        if (unknownSince < 0) {
            unknownSince = now;
            epoch++;
        }
        return now - unknownSince >= settleMs;
    }

    long remaining(long now) {
        return Math.max(0, stableAfter - now);
    }
}
