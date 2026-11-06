package com.feedreform.highlight;

import android.graphics.Rect;

/**
 * 小红书首页推荐流的一张卡片（纯数据，无 Android 组件依赖）。
 *
 * 由 {@link XhsCards#find} 产出。除 brief 要求的五个字段外，附带 type / ad 两个辅助字段，
 * 主 App 可忽略。
 */
public final class XhsCard {

    /** sha1(type + "|" + title + "|" + author)，40 位小写 hex。 */
    public String fingerprint;
    /** content-desc 中的完整标题（不是屏幕上被截断的文本）。 */
    public String title;
    /** content-desc 中的作者昵称。 */
    public String author;
    /** 封面矩形的屏幕坐标；左/右边界 = 卡片左右边界。 */
    public Rect cover;
    /** 卡片外框（RecyclerView 直接子节点的 bounds；半露时会被裁到列表边界）。 */
    public Rect bounds;
    /** true = 整卡完整落在 RecyclerView 可见区且没被底部导航挡住。 */
    public boolean fullyVisible;
    /** 类型词：笔记 / 视频 / 直播。 */
    public String type;
    /** 广告卡：卡内存在 text="广告" 的 TextView。半露时该标记会消失，见文档。 */
    public boolean ad;
    /** content-desc 原文，排查问题时用。 */
    public String desc;

    @Override
    public String toString() {
        return "fp=" + (fingerprint == null ? "" : fingerprint.substring(0, 6))
                + " type=" + type
                + " title=" + title
                + " author=" + author
                + " card=" + (bounds == null ? "null" : bounds.toShortString())
                + " cover=" + (cover == null ? "null" : cover.toShortString())
                + " fully=" + fullyVisible
                + " ad=" + ad;
    }
}
