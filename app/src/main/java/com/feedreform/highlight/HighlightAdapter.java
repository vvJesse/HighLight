package com.feedreform.highlight;

import android.view.accessibility.AccessibilityNodeInfo;

import java.util.List;

/**
 * 平台适配接口：从一个无障碍根节点里找出推荐流的卡片。
 *
 * 实现必须只读节点、不做网络、不碰 UI；返回的封面是屏幕坐标，供服务直接绘制。
 * 目前只有 {@link BiliAdapter}；小红书识别由 {@link XhsAdapter} 占位，等独立识别类落盘后接入。
 */
interface HighlightAdapter {

    /** "bili" / "xhs"。 */
    String platform();

    /** tv.danmaku.bili / com.xingin.xhs。 */
    String packageName();

    /** 找到当前屏的卡片；找不到列表时返回空列表，不要抛异常。 */
    List<CardTarget> findCards(AccessibilityNodeInfo root);
}
