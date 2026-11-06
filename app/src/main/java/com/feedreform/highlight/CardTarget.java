package com.feedreform.highlight;

import android.graphics.Rect;

/**
 * 平台适配层的一张卡片：封面范围（屏幕坐标）与判断用的文字。
 *
 * 各平台拿到的东西不同（B站有 title/UP 主，小红书只有 content-desc 里的指纹），
 * 因此 {@link #fingerprint} 是判缓存与去重的唯一键，样式绘制只用 {@link #cover}。
 */
final class CardTarget {

    /** 平台内稳定标识：同一张卡滚动前后不变。 */
    final String fingerprint;
    final String title;
    final String author;
    /** 屏幕坐标的封面范围；半露/被裁的卡也有范围，但 {@link #fullyVisible} 为 false。 */
    final Rect cover;
    /** 封面完整露出、且不在底部导航栏下面时才为 true；这类卡才画。 */
    final boolean fullyVisible;

    CardTarget(String fingerprint, String title, String author, Rect cover, boolean fullyVisible) {
        this.fingerprint = fingerprint == null ? "" : fingerprint;
        this.title = title == null ? "" : title;
        this.author = author == null ? "" : author;
        this.cover = cover == null ? new Rect() : cover;
        this.fullyVisible = fullyVisible;
    }

    /** 判断输入：标题 + 作者，与列表侧口径一致。 */
    String judgeText() {
        String a = author == null || author.isEmpty() ? "" : "｜UP：" + author;
        return title + a;
    }
}
