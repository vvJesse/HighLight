package com.feedreform.highlight;

import android.graphics.Rect;
import android.util.Log;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 小红书首页推荐流适配：识别逻辑原样使用独立探针交付的 {@link XhsCards}（未改一行识别规则）。
 *
 * 与 B站 的区别：小红书封面很高，一屏 6–7 张卡里完整露出的往往只有 1 张，所以这里把
 * 「半露的卡」也交给绘制层，但先做裁剪：
 * 封面（拿不到时用卡片 bounds）× 卡片 bounds × [0, navTop] 求交，
 * 交集不足 40dp 高 / 80dp 宽就丢掉，避免一条细边或半张卡悬空。
 *
 * 只在小红书首页推荐流生效：优先看顶部页签栏第 2 个（index=1，中文「发现」/英文「Explore」）
 * 是否选中，其次看「唯一全宽 RecyclerView + 底部导航存在 + 无详情页特征」，中英文关键词只作兜底。
 */
final class XhsAdapter implements HighlightAdapter {

    private static final String TAG = "HLFLOW";
    private static final float MIN_VISIBLE_HEIGHT_DP = 40f;
    private static final float MIN_VISIBLE_WIDTH_DP = 80f;

    private final int screenWidth;
    private final int screenHeight;
    private final float density;

    XhsAdapter(int screenWidth, int screenHeight, int sysNavTop, float density) {
        this.screenWidth = screenWidth;
        this.screenHeight = screenHeight;
        this.density = density > 0 ? density : 3f;
    }

    @Override
    public String platform() {
        return "xhs";
    }

    @Override
    public String packageName() {
        return "com.xingin.xhs";
    }

    @Override
    public List<CardTarget> findCards(AccessibilityNodeInfo root) {
        List<CardTarget> out = new ArrayList<>();
        if (root == null) return out;
        if (!isHomeFeed(root)) {
            Log.i(TAG, "scan platform=xhs home=false");
            return out;
        }
        Log.i(TAG, "scan platform=xhs home=true");
        int navTop = XhsCards.detectNavTop(root, screenWidth, screenHeight);
        int safeBottom = navTop > 0 ? navTop : (int) (screenHeight * 0.8864f);
        List<XhsCard> cards = XhsCards.find(root, screenWidth, screenHeight, safeBottom);
        int drawn = 0;
        for (XhsCard card : cards) {
            if (card == null || card.ad) continue;
            Rect visible = new Rect();
            Rect base = card.cover != null && card.cover.width() > 0 ? card.cover : card.bounds;
            if (base == null || base.width() <= 0 || base.height() <= 0) continue;
            visible.set(base);
            if (!visible.intersect(card.bounds)) continue;
            if (!visible.intersect(0, 0, screenWidth, safeBottom)) continue;
            if (visible.height() < MIN_VISIBLE_HEIGHT_DP * density) continue;
            if (visible.width() < MIN_VISIBLE_WIDTH_DP * density) continue;
            drawn++;
            out.add(new CardTarget(card.fingerprint, card.title, card.author, visible, true));
        }
        Log.i(TAG, "scan platform=xhs cards=" + cards.size() + " drawable=" + drawn
                + " navTop=" + safeBottom);
        return out;
    }

    /**
     * 首页判定分三层，越靠前越可靠：
     * <ol>
     *   <li>顶部页签栏（class 含 {@code ActionBar$Tab}）：按 left 排序，第 2 个（index=1）selected
     *       → 推荐流。中文是「发现」、英文是「Explore」，关注/Following=0、广州/Nearby=2，
     *       全程不看文字；</li>
     *   <li>拿不到页签时：唯一全宽可滚动 RecyclerView + 底部导航容器存在 + 无详情页特征
     *       （没有「返回+分享」组合、不是单卡布局）；</li>
     *   <li>关键词兜底（首页/Home、发现/Explore、关注/Following、广州/Nearby），仅在前两层
     *       都拿不到结论时使用。</li>
     * </ol>
     */
    private boolean isHomeFeed(AccessibilityNodeInfo root) {
        int tabs = tabsVerdict(root);
        if (tabs >= 0) return tabs == 1;
        int structural = structuralVerdict(root);
        if (structural >= 0) return structural == 1;
        return keywordVerdict(root);
    }

    /** 1=推荐流页签选中；0=选中了别的页签；-1=拿不到页签，交给下一层。 */
    private int tabsVerdict(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> tabs = new ArrayList<>();
        collectByClass(root, tabs, "ActionBar$Tab", 0);
        if (tabs.size() < 2) return -1;
        List<AccessibilityNodeInfo> top = new ArrayList<>();
        for (AccessibilityNodeInfo tab : tabs) {
            Rect b = bounds(tab);
            if (b == null || b.width() <= 0) continue;
            if (b.top < screenHeight * 0.35f) top.add(tab);
        }
        if (top.size() < 2) return -1;
        Collections.sort(top, (a, b) -> {
            Rect ra = bounds(a);
            Rect rb = bounds(b);
            int la = ra == null ? 0 : ra.left;
            int lb = rb == null ? 0 : rb.left;
            if (la != lb) return la < lb ? -1 : 1;
            int ta = ra == null ? 0 : ra.top;
            int tb = rb == null ? 0 : rb.top;
            if (ta == tb) return 0;
            return ta < tb ? -1 : 1;
        });
        int selectedIndex = -1;
        for (int i = 0; i < top.size(); i++) {
            if (selectedDeep(top.get(i), 0)) {
                if (selectedIndex >= 0) return -1; // 多个选中：状态不明，交给下一层
                selectedIndex = i;
            }
        }
        if (selectedIndex < 0) return -1;
        return selectedIndex == 1 ? 1 : 0;
    }

    /** 1=首页；0=不是；-1=结构不完整，交给关键词兜底。 */
    private int structuralVerdict(AccessibilityNodeInfo root) {
        if (XhsCards.detectNavTop(root, screenWidth, screenHeight) <= 0) return -1;
        AccessibilityNodeInfo list = fullWidthFeedList(root);
        if (list == null) return -1;
        if (hasDetailChrome(root)) return 0;
        int children = 0;
        try {
            children = list.getChildCount();
        } catch (Throwable ignored) {
        }
        if (children < 2) return 0; // 单卡详情布局
        return 1;
    }

    /** 找面积最大的全宽可滚动 RecyclerView（首页双列流）；没有返回 null。 */
    private AccessibilityNodeInfo fullWidthFeedList(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> all = new ArrayList<>();
        collectAll(root, all, 0);
        AccessibilityNodeInfo best = null;
        int bestHeight = 0;
        for (AccessibilityNodeInfo node : all) {
            boolean scrollable;
            try {
                scrollable = node.isScrollable();
            } catch (Throwable t) {
                scrollable = false;
            }
            if (!scrollable) continue;
            String cls = className(node);
            if (cls == null || !cls.contains("RecyclerView")) continue;
            Rect b = bounds(node);
            if (b == null || b.width() < screenWidth * 0.95f) continue;
            if (b.height() < screenHeight * 0.3f) continue;
            if (b.height() > bestHeight) {
                bestHeight = b.height();
                best = node;
            }
        }
        return best;
    }

    /** 详情页特征：返回与分享同时出现。首页的双列流里不会有这两个入口。 */
    private boolean hasDetailChrome(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> all = new ArrayList<>();
        collectAll(root, all, 0);
        boolean back = false;
        boolean share = false;
        for (AccessibilityNodeInfo node : all) {
            String desc = contentDesc(node);
            String text = text(node);
            for (String s : new String[]{desc, text}) {
                if (s == null) continue;
                if ("返回".equals(s) || "Back".equalsIgnoreCase(s)) back = true;
                if ("分享".equals(s) || "Share".equalsIgnoreCase(s)) share = true;
            }
            if (back && share) return true;
        }
        return false;
    }

    /** 最后一层：中英文关键词兜底，保持旧行为（底部首页/Home 选中，且没选中关注/广州）。 */
    private boolean keywordVerdict(AccessibilityNodeInfo root) {
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        boolean homeSelected = false;
        boolean discoverSelected = false;
        boolean otherTabSelected = false;
        int guard = 0;
        while (!queue.isEmpty() && guard++ < 600) {
            AccessibilityNodeInfo node = queue.poll();
            String desc = contentDesc(node);
            String text = text(node);
            Rect b = bounds(node);
            if (b != null && b.top > screenHeight * 0.45f && selected(node)) {
                if ("首页".equals(desc) || "Home".equalsIgnoreCase(desc)
                        || "首页".equals(text) || "Home".equalsIgnoreCase(text)) {
                    homeSelected = true;
                }
            }
            if ("发现".equals(text) || "Explore".equalsIgnoreCase(text)
                    || "发现".equals(desc) || "Explore".equalsIgnoreCase(desc)) {
                if (selected(node)) discoverSelected = true;
            }
            if ("关注".equals(text) || "Following".equalsIgnoreCase(text)
                    || "广州".equals(text) || "Nearby".equalsIgnoreCase(text)
                    || "关注".equals(desc) || "Following".equalsIgnoreCase(desc)
                    || "广州".equals(desc) || "Nearby".equalsIgnoreCase(desc)) {
                if (selected(node)) otherTabSelected = true;
            }
            int count = 0;
            try {
                count = node.getChildCount();
            } catch (Throwable ignored) {
            }
            for (int i = 0; i < count; i++) {
                AccessibilityNodeInfo child = null;
                try {
                    child = node.getChild(i);
                } catch (Throwable ignored) {
                }
                if (child != null) queue.add(child);
            }
        }
        if (otherTabSelected && !discoverSelected) return false;
        return homeSelected || discoverSelected;
    }

    private static void collectAll(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> out, int depth) {
        if (node == null || depth > 60) return;
        out.add(node);
        int count = 0;
        try {
            count = node.getChildCount();
        } catch (Throwable ignored) {
        }
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo child = null;
            try {
                child = node.getChild(i);
            } catch (Throwable ignored) {
            }
            if (child != null) collectAll(child, out, depth + 1);
        }
    }

    private static void collectByClass(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> out,
                                       String needle, int depth) {
        if (node == null || depth > 60) return;
        String cls = className(node);
        if (cls != null && cls.contains(needle)) out.add(node);
        int count = 0;
        try {
            count = node.getChildCount();
        } catch (Throwable ignored) {
        }
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo child = null;
            try {
                child = node.getChild(i);
            } catch (Throwable ignored) {
            }
            if (child != null) collectByClass(child, out, needle, depth + 1);
        }
    }

    private static boolean selectedDeep(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > 3) return false;
        if (selected(node)) return true;
        int count = 0;
        try {
            count = node.getChildCount();
        } catch (Throwable ignored) {
        }
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo child = null;
            try {
                child = node.getChild(i);
            } catch (Throwable ignored) {
            }
            if (child != null && selectedDeep(child, depth + 1)) return true;
        }
        return false;
    }

    private static Rect bounds(AccessibilityNodeInfo node) {
        if (node == null) return null;
        Rect b = new Rect();
        try {
            node.getBoundsInScreen(b);
        } catch (Throwable t) {
            return null;
        }
        return b;
    }

    private static String className(AccessibilityNodeInfo node) {
        try {
            CharSequence cs = node.getClassName();
            return cs == null ? null : cs.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    private static String contentDesc(AccessibilityNodeInfo node) {
        try {
            CharSequence cs = node.getContentDescription();
            return cs == null ? "" : cs.toString().trim();
        } catch (Throwable t) {
            return "";
        }
    }

    private static String text(AccessibilityNodeInfo node) {
        try {
            CharSequence cs = node.getText();
            return cs == null ? null : cs.toString().trim();
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean selected(AccessibilityNodeInfo node) {
        try {
            return node.isSelected();
        } catch (Throwable t) {
            return false;
        }
    }
}
