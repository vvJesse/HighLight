package com.feedreform.highlight;

import android.app.Activity;

/**
 * 从亮点主动打开目标 App 的统一入口：启动临时会话。
 *
 * 返回气泡由 {@link HighlightService} 自己的第二个 accessibility overlay 绘制，
 * 这里不再启动 ReturnBubbleService；只给「搜索页 / 收藏页 / 首页卡片 / 入口检查页」
 * 这类真正跳转目标 App 的路径用。打开失败必须 {@link #abort}，避免无目标会话。
 */
final class SessionLaunch {

    private SessionLaunch() {
    }

    /** 先开会话再跳转；platform = "bili" / "xhs"。 */
    static void start(Activity activity, String platform) {
        if (activity == null) return;
        String p = "xhs".equals(platform) ? "xhs" : "bili";
        HighlightSession.start(activity, p);
    }

    /** 目标 App 没打开：立即结束会话（服务会清掉气泡）。 */
    static void abort(Activity activity) {
        if (activity == null) return;
        HighlightSession.stop(activity);
    }
}
