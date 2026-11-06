package com.feedreform.highlight;

import android.graphics.Rect;
import android.util.Log;
import android.view.accessibility.AccessibilityNodeInfo;

import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

/**
 * B站推荐 / 关注流的卡片定位。逻辑从 AX 灰层实验（project/experiments/ax-gray）迁入：
 *
 * - 全树找面积最大的可滚动列表（RecyclerView / GridView / ListView，或全宽高可滚动容器），
 *   向下找到「直接子节点就是卡片」的那一层；
 * - 卡片 = content-desc 以 视频/竖版视频/直播/创作推广/广告 开头的节点；
 * - 封面 = 卡片内宽度不小于卡片宽度一半、高度 ≥60 的 ImageView；找不到就跳过，不画兜底方块；
 * - 半露、被裁到列表边界、或压到底部页签栏下面的卡一律 fullyVisible=false。
 */
final class BiliAdapter implements HighlightAdapter {

    private static final String TAG = "HLFLOW";
    private static final String[] CARD_PREFIXES =
            {"视频,", "竖版视频,", "直播,", "创作推广,", "广告,"};

    /** B站 8.90.2 卡片内的固定 view id，仅在 content-desc 全丢时兜底用。 */
    private static final String ID_TITLE = "tv.danmaku.bili:id/title";
    private static final String ID_COVER = "tv.danmaku.bili:id/cover";
    private static final String ID_REAL_DESC = "tv.danmaku.bili:id/real_desc";
    private static final String ID_AUTHOR = "tv.danmaku.bili:id/author";

    /**
     * 详情页跨扫描状态：上次找到的「相关推荐」列表节点。
     *
     * 初代 GrayService 把 cachedDetailRv 放在服务里，每次扫描都先 refresh 它，
     * 失效才重新找；v0.5 的适配器每次扫描都新建，所以把节点挂在服务持有的
     * 这个状态对象上，扫描和 watchdog 共用（与初代同一口径）。
     */
    static final class DetailState {
        volatile AccessibilityNodeInfo rv;
    }

    private final int screenWidth;
    private final int screenHeight;
    private final int sysNavTop;
    /** 详情页模式：只认「相关推荐」列表，封面允许只占卡宽 1/4（横向小卡）。 */
    private final boolean detailMode;
    private final DetailState detailState;
    /** 本次 findCards 是否靠「详情页相关推荐」兜底才发现卡片：服务据此把页面推成详情。 */
    private boolean detailFallbackUsed;

    private int navTop;
    private boolean navSearchDone;
    private int rvTop;
    private int rvBottom;
    private int rvLeft;
    private int rvRight;

    BiliAdapter(int screenWidth, int screenHeight, int sysNavTop) {
        this(screenWidth, screenHeight, sysNavTop, false, null);
    }

    BiliAdapter(int screenWidth, int screenHeight, int sysNavTop, boolean detailMode) {
        this(screenWidth, screenHeight, sysNavTop, detailMode, null);
    }

    BiliAdapter(int screenWidth, int screenHeight, int sysNavTop, boolean detailMode,
                DetailState detailState) {
        this.screenWidth = screenWidth;
        this.screenHeight = screenHeight;
        this.sysNavTop = sysNavTop;
        this.detailMode = detailMode;
        this.detailState = detailState;
    }

    @Override
    public String platform() {
        return "bili";
    }

    /** 本次扫描的卡片是否来自详情页兜底（服务用来推断「其实在详情页」）。 */
    boolean usedDetailFallback() {
        return detailFallbackUsed;
    }

    @Override
    public String packageName() {
        return "tv.danmaku.bili";
    }

    @Override
    public List<CardTarget> findCards(AccessibilityNodeInfo root) {
        List<CardTarget> out = new ArrayList<>();
        detailFallbackUsed = false;
        if (root == null) return out;
        if (detailMode) {
            // 详情页：只画相关推荐。找不到就返回空（宁可少画，也不把播放器/评论区当卡片）。
            findDetailCards(root, out);
            return out;
        }
        AccessibilityNodeInfo list = findScrollableList(root);
        if (list == null) {
            findDetailCards(root, out);
            detailFallbackUsed = !out.isEmpty() && looksLikeDetailPage(root);
            Log.i(TAG, "scan platform=bili cards=" + out.size()
                    + " src=" + (out.isEmpty() ? "none" : "detail-fallback") + " list=null");
            return out;
        }
        AccessibilityNodeInfo container = findCardContainer(list);
        if (container == null) container = list;
        if (!navSearchDone) detectNavTop(root);
        applyBounds(container);

        int n = safeCount(container);
        for (int i = 0; i < n; i++) {
            addCard(safeChild(container, i), out);
        }
        if (out.isEmpty()) {
            // 兜底：只在列表容器子树里做有界 BFS，别把整棵树都扫一遍。
            ArrayDeque<AccessibilityNodeInfo> q = new ArrayDeque<>();
            q.add(container);
            int guard = 0;
            while (!q.isEmpty() && guard++ < 120) {
                AccessibilityNodeInfo node = q.poll();
                if (isCardNode(node)) {
                    addCard(node, out);
                    continue;
                }
                int cnt = safeCount(node);
                for (int i = 0; i < cnt; i++) {
                    AccessibilityNodeInfo ch = safeChild(node, i);
                    if (ch != null) q.add(ch);
                }
            }
        }
        String src = "desc";
        if (out.isEmpty()) {
            // 首页扫描零卡：可能是「会话开始时已经停在详情页、没收到页面切换事件」。
            // 再按详情页相关推荐扫一次（只认带真实封面的横向小卡）。
            findDetailCards(root, out);
            if (!out.isEmpty()) {
                src = "detail-fallback";
                detailFallbackUsed = looksLikeDetailPage(root);
            }
        }
        if (out.isEmpty()) {
            // content-desc 一个都拿不到时（B站改版可能丢描述），再用 view id 兜底。
            addCardsByViewId(root, out);
            src = out.isEmpty() ? "none" : "viewid";
        }
        Log.i(TAG, "scan platform=bili cards=" + out.size() + " src=" + src
                + " rv=" + rvLeft + "," + rvTop + "," + rvRight + "," + rvBottom
                + " navTop=" + navTop);
        return out;
    }

    private void applyBounds(AccessibilityNodeInfo node) {
        Rect r = new Rect();
        safeBounds(node, r);
        if (r.width() > 0 && r.height() > 0) {
            rvLeft = r.left;
            rvRight = r.right;
            rvTop = r.top;
            rvBottom = r.bottom;
        } else {
            rvLeft = 0;
            rvRight = screenWidth;
            rvTop = 0;
            rvBottom = screenHeight;
        }
    }

    private int safeBottom() {
        int bottom = Math.min(rvBottom, sysNavTop);
        if (navTop > 0) bottom = Math.min(bottom, navTop);
        return bottom;
    }

    /**
     * 详情页相关推荐：沿用初代 walkDetailCache 的顺序——
     * 先 refresh 上次的 cachedDetailRv，失效才重新 findDetailRv；
     * 缓存列表里一张相关卡都找不到时清掉缓存、重新发现一次（防止缓存到外层页面列表）。
     *
     * 真机节点树（2026-11-06，B站 9.17 详情页 dump）显示相关推荐是 ViewPager 里的
     * `RecyclerView id=recycler`，它的直接子节点既有作者栏/互动条/合集条，也有相关卡：
     * - 第一张相关卡 ViewGroup 的 **content-desc 为空**，只有 `id=cover` ImageView +
     *   `id=title` TextView + `id=author` TextView；
     * - 第二张卡 desc 是「标题…up主作者」，没有「视频,」前缀。
     * 所以不能再用「desc 非空 + 至少 2 张」作为门槛（旧实现因此持续 detail-rv none），
     * 改为结构化识别：卡片矩形 + 左侧真实封面 + 标题（desc 或 id=title），
     * 并显式排除作者栏、互动条、合集条、广告、互动视频。
     */
    private void findDetailCards(AccessibilityNodeInfo root, List<CardTarget> out) {
        findDetailCards(root, out, true);
    }

    private void findDetailCards(AccessibilityNodeInfo root, List<CardTarget> out, boolean rediscover) {
        AccessibilityNodeInfo rv = null;
        if (detailState != null && detailState.rv != null) {
            try {
                if (detailState.rv.refresh() && detailState.rv.isVisibleToUser()
                        && safeCount(detailState.rv) > 0) {
                    rv = detailState.rv;
                }
            } catch (Throwable ignored) {
            }
            if (rv == null) detailState.rv = null;
        }
        if (rv == null) {
            rv = findDetailRv(root);
            if (detailState != null) detailState.rv = rv;
        }
        if (rv == null) {
            Log.i(TAG, "scan platform=bili detail-rv none");
            return;
        }
        applyBounds(rv);
        List<CardTarget> found = new ArrayList<>();
        int n = safeCount(rv);
        for (int i = 0; i < n; i++) {
            addDetailCard(safeChild(rv, i), found);
        }
        if (found.isEmpty() && rediscover && detailState != null && detailState.rv == rv) {
            // 缓存到的可能是外层页面列表；清掉再找一次，最多一次，避免递归。
            detailState.rv = null;
            findDetailCards(root, out, false);
            return;
        }
        if (found.isEmpty()) {
            Log.i(TAG, "scan platform=bili detail-rv none cards=0");
            return;
        }
        out.addAll(found);
        Log.i(TAG, "scan platform=bili cards=" + out.size() + " src=detail score=" + found.size()
                + " rv=" + rvLeft + "," + rvTop + "," + rvRight + "," + rvBottom
                + " navTop=" + navTop);
    }

    /**
     * 找相关推荐列表：类名列表节点里，按「结构化相关卡数量」打分，取最高分的。
     * 初代 findDetailRv 用 desc 里的「up主」「轻点两下查看详情」打 3 分；这里保留该信号，
     * 但主信号换成结构化卡片，因为现网相关卡可能没有 content-desc。
     */
    private AccessibilityNodeInfo findDetailRv(AccessibilityNodeInfo root) {
        if (root == null) return null;
        AccessibilityNodeInfo best = null;
        int bestScore = 0;
        int bestArea = 0;
        ArrayDeque<AccessibilityNodeInfo> q = new ArrayDeque<>();
        q.push(root);
        int guard = 0;
        while (!q.isEmpty() && guard++ < 900) {
            AccessibilityNodeInfo n = q.pop();
            if (isListClass(n)) {
                int cnt = safeCount(n);
                int cards = 0;
                int descHits = 0;
                for (int i = 0; i < cnt && i < 16; i++) {
                    AccessibilityNodeInfo ch = safeChild(n, i);
                    if (isDetailCard(ch)) cards++;
                    String d = contentDesc(ch);
                    if (d != null) {
                        if (d.contains("up主")) descHits += 3;
                        else if ((d.startsWith(",") || d.startsWith("，"))
                                && d.contains("轻点两下查看详情")) descHits += 3;
                    }
                }
                Rect r = new Rect();
                safeBounds(n, r);
                int area = r.width() * r.height();
                int score = cards * 2 + descHits;
                if (cards > 0 && (score > bestScore || (score == bestScore && area > bestArea))) {
                    bestScore = score;
                    bestArea = area;
                    best = n;
                }
            }
            int cnt = safeCount(n);
            for (int i = cnt - 1; i >= 0; i--) {
                AccessibilityNodeInfo ch = safeChild(n, i);
                if (ch != null) q.push(ch);
            }
        }
        if (best != null) {
            Log.i(TAG, "detail-rv class=" + className(best) + " score=" + bestScore);
        }
        return best;
    }

    /**
     * 一张相关推荐卡：占大半屏宽、高度像一行卡片、左侧有真实封面、有标题；
     * 排除作者栏（头像太窄）、互动条（按钮/图标太窄）、合集条、广告、互动视频。
     */
    private boolean isDetailCard(AccessibilityNodeInfo card) {
        if (card == null) return false;
        Rect cardRect = new Rect();
        safeBounds(card, cardRect);
        if (cardRect.width() < screenWidth * 0.5f || cardRect.height() < 100) return false;
        if (cardRect.height() > screenHeight * 0.4f) return false;
        String desc = contentDesc(card);
        if (isNonRelatedNode(card, desc)) return false;
        Rect cover = findCover(card, cardRect, 0.25f);
        if (cover == null || cover.width() < 80 || cover.height() < 60) return false;
        if (cover.left > screenWidth * 0.6f) return false;
        return !detailTitleOf(card, desc).isEmpty();
    }

    /** 合集条 / 广告 / 互动视频 / 作者操作栏：都不是相关推荐，别画。 */
    private boolean isNonRelatedNode(AccessibilityNodeInfo card, String desc) {
        if (desc != null) {
            String d = desc.trim();
            if (d.startsWith("互动视频") || d.startsWith("广告") || d.startsWith("合集")) return true;
        }
        ArrayDeque<AccessibilityNodeInfo> q = new ArrayDeque<>();
        q.add(card);
        int guard = 0;
        while (!q.isEmpty() && guard++ < 40) {
            AccessibilityNodeInfo n = q.poll();
            String d = contentDesc(n);
            String t = textOf(n);
            if (d != null && (d.startsWith("合集") || d.startsWith("广告,")
                    || d.startsWith("互动视频"))) {
                return true;
            }
            if (t != null && (t.startsWith("合集 ·") || t.startsWith("合集·"))) return true;
            String cls = className(n);
            if (cls != null && cls.contains("Button") && d != null
                    && (d.contains("点赞") || d.contains("投币")
                    || d.contains("收藏") || d.contains("分享"))) {
                return true;
            }
            int cnt = safeCount(n);
            for (int i = 0; i < cnt; i++) {
                AccessibilityNodeInfo ch = safeChild(n, i);
                if (ch != null) q.add(ch);
            }
        }
        return false;
    }

    /** 标题：优先 desc（首页/详情页两种格式）；desc 为空时用卡片里的 id=title 文本。 */
    private static String detailTitleOf(AccessibilityNodeInfo card, String desc) {
        String fromDesc = desc == null ? "" : extractTitle(desc);
        if (!fromDesc.isEmpty() && !fromDesc.startsWith("互动视频") && !fromDesc.startsWith("广告")) {
            return fromDesc;
        }
        String byId = findTextByViewId(card, ID_TITLE);
        if (!byId.isEmpty()) return byId;
        return "";
    }

    private static String detailAuthorOf(AccessibilityNodeInfo card, String desc) {
        String fromDesc = desc == null ? "" : extractAuthor(desc);
        if (!fromDesc.isEmpty()) return fromDesc;
        return findTextByViewId(card, ID_AUTHOR);
    }

    /**
     * 详情页首张相关卡的封面 top（quick-motion 用）。只读缓存的列表节点，
     * 不在快速运动线程里重新发现列表，避免和扫描线程抢 DetailState。
     */
    int cachedDetailFirstTop() {
        AccessibilityNodeInfo rv = detailState == null ? null : detailState.rv;
        if (rv == null) return Integer.MIN_VALUE;
        try {
            if (!rv.refresh()) return Integer.MIN_VALUE;
        } catch (Throwable t) {
            return Integer.MIN_VALUE;
        }
        int n = safeCount(rv);
        for (int i = 0; i < n; i++) {
            AccessibilityNodeInfo ch = safeChild(rv, i);
            if (!isDetailCard(ch)) continue;
            Rect cardRect = new Rect();
            safeBounds(ch, cardRect);
            Rect cover = findCover(ch, cardRect, 0.25f);
            if (cover != null) return cover.top;
            return cardRect.top;
        }
        return Integer.MIN_VALUE;
    }

    /** 详情页证据：存在「简介/评论」tab_title。避免把首页的兜底识别误判成详情页。 */
    private static boolean looksLikeDetailPage(AccessibilityNodeInfo root) {
        if (root == null) return false;
        ArrayDeque<AccessibilityNodeInfo> q = new ArrayDeque<>();
        q.add(root);
        int guard = 0;
        while (!q.isEmpty() && guard++ < 300) {
            AccessibilityNodeInfo n = q.poll();
            String id = null;
            try {
                id = n.getViewIdResourceName();
            } catch (Throwable ignored) {
            }
            if (id != null && id.endsWith(":id/tab_title")) {
                String t = textOf(n);
                if ("简介".equals(t) || "评论".equals(t)) return true;
            }
            int cnt = safeCount(n);
            for (int i = 0; i < cnt; i++) {
                AccessibilityNodeInfo ch = safeChild(n, i);
                if (ch != null) q.add(ch);
            }
        }
        return false;
    }

    /**
     * 详情页可见性检查（初代 detailVisibilityProblem 的移植）：
     * 返回 null 表示相关推荐仍在；否则返回原因（invisible / tab / scroll-container / occluded）。
     *
     * cheap=true 跳过两次较重的 BFS（评论 tab、最大的其它 RecyclerView），供 150ms watchdog
     * 在主线程用；事件驱动的检查用 cheap=false，能立刻发现「切到评论 tab」「换了滚动容器」。
     */
    String detailVisibilityProblem(AccessibilityNodeInfo freshRoot, boolean cheap) {
        if (freshRoot == null) return "invisible";
        if (!cheap) {
            String tab = selectedCommentTab(freshRoot);
            if (tab != null) return tab;
        }
        AccessibilityNodeInfo rv = detailState == null ? null : detailState.rv;
        if (rv == null) return "invisible";
        try {
            if (!rv.refresh() || !rv.isVisibleToUser()) return "invisible";
            Rect r = new Rect();
            safeBounds(rv, r);
            int top = Math.max(r.top, 0);
            int bottom = Math.min(r.bottom, sysNavTop);
            int visH = bottom - top;
            if (visH < 300) return "invisible";
            int cnt = safeCount(rv);
            boolean childVisible = false;
            for (int i = 0; i < cnt; i++) {
                AccessibilityNodeInfo ch = safeChild(rv, i);
                if (ch != null && ch.isVisibleToUser()) {
                    childVisible = true;
                    break;
                }
            }
            if (!childVisible) return "invisible";
            if (!cheap) {
                // 页面换了滚动容器（例如从相关推荐切到评论区自己的列表）。
                long relatedArea = (long) visH * Math.max(1, r.width());
                long[] best = new long[1];
                AccessibilityNodeInfo bestRv = largestVisibleRv(freshRoot, rv, best);
                if (bestRv != null && best[0] > relatedArea * 12 / 10) return "scroll-container";
            }
            Rect win = new Rect(0, 0, screenWidth, sysNavTop);
            if (!Rect.intersects(r, win)) return "occluded";
        } catch (Throwable t) {
            return "invisible";
        }
        return null;
    }

    /** 评论 tab 被选中时返回 "tab"（B站 tab_title 的 selected 状态，不依赖语言之外的文案两处都认）。 */
    private static String selectedCommentTab(AccessibilityNodeInfo root) {
        ArrayDeque<AccessibilityNodeInfo> q = new ArrayDeque<>();
        q.add(root);
        int guard = 0;
        while (!q.isEmpty() && guard++ < 500) {
            AccessibilityNodeInfo n = q.poll();
            String t = null;
            String id = null;
            try {
                CharSequence cs = n.getText();
                t = cs == null ? null : cs.toString().trim();
                id = n.getViewIdResourceName();
            } catch (Throwable ignored) {
            }
            boolean isTab = (id != null && id.endsWith(":id/tab_title"))
                    || "评论".equals(t) || "简介".equals(t);
            if (isTab) {
                boolean sel = false;
                try {
                    sel = n.isSelected();
                } catch (Throwable ignored) {
                }
                if ("评论".equals(t) && sel) return "tab";
            }
            int cnt = safeCount(n);
            for (int i = 0; i < cnt; i++) {
                AccessibilityNodeInfo ch = safeChild(n, i);
                if (ch != null) q.add(ch);
            }
        }
        return null;
    }

    /** 除 exclude 之外、当前可见面积最大的列表；面积明显更大说明相关推荐已被替换。 */
    private AccessibilityNodeInfo largestVisibleRv(AccessibilityNodeInfo root,
                                                   AccessibilityNodeInfo exclude, long[] areaOut) {
        ArrayDeque<AccessibilityNodeInfo> q = new ArrayDeque<>();
        q.push(root);
        AccessibilityNodeInfo best = null;
        int guard = 0;
        while (!q.isEmpty() && guard++ < 300) {
            AccessibilityNodeInfo n = q.pop();
            String cls = className(n);
            if (cls != null && cls.contains("RecyclerView") && n != exclude) {
                try {
                    if (n.isVisibleToUser()) {
                        Rect r = new Rect();
                        safeBounds(n, r);
                        int top = Math.max(r.top, 0);
                        int bottom = Math.min(r.bottom, sysNavTop);
                        long area = Math.max(0, bottom - top) * (long) Math.max(1, r.width());
                        if (area > areaOut[0]) {
                            areaOut[0] = area;
                            best = n;
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
            int cnt = safeCount(n);
            for (int i = cnt - 1; i >= 0; i--) {
                AccessibilityNodeInfo ch = safeChild(n, i);
                if (ch != null) q.push(ch);
            }
        }
        return best;
    }

    /** 详情页的一张相关推荐卡：横向卡片，封面只占左侧一小块；只画屏幕内、导航栏上方。 */
    private void addDetailCard(AccessibilityNodeInfo card, List<CardTarget> out) {
        if (!isDetailCard(card)) return;
        Rect cardRect = new Rect();
        safeBounds(card, cardRect);
        String desc = contentDesc(card);
        Rect cover = findCover(card, cardRect, 0.25f);
        if (cover == null || cover.width() < 80 || cover.height() < 60) return;
        int safeBottom = safeBottom();
        int visTop = Math.max(cover.top, Math.max(rvTop, 0));
        int visBottom = Math.min(cover.bottom, safeBottom);
        if (visBottom - visTop < cover.height() * 0.6f) return;
        String title = detailTitleOf(card, desc);
        if (title == null || title.isEmpty()) return;   // 标题都没解析出来就别画（也避免空指纹互相覆盖）
        String author = detailAuthorOf(card, desc);
        boolean fully = cover.top >= Math.max(rvTop, 0) + 2
                && cover.bottom <= safeBottom - 2
                && cover.left >= 0
                && cover.right <= screenWidth
                && cover.width() >= 60
                && cover.height() >= 45;
        out.add(new CardTarget(fingerprint(title, author), title, author, cover, fully));
    }

    private void addCard(AccessibilityNodeInfo card, List<CardTarget> out) {
        if (card == null) return;
        String desc = contentDesc(card);
        if (desc == null) return;
        boolean isCard = false;
        for (String pre : CARD_PREFIXES) {
            if (desc.startsWith(pre)) {
                isCard = true;
                break;
            }
        }
        if (!isCard) return;
        Rect cardRect = new Rect();
        safeBounds(card, cardRect);
        if (cardRect.width() <= 0 || cardRect.height() <= 0) return;
        Rect cover = findCover(card, cardRect);
        if (cover == null) return;   // 没有真实封面图就不画，绝不画兜底方块
        String title = extractTitle(desc);
        String author = extractAuthor(desc);
        boolean fully = isFullyVisible(cover);
        out.add(new CardTarget(fingerprint(title, author), title, author, cover, fully));
    }

    private boolean isFullyVisible(Rect cover) {
        return cover.top >= Math.max(rvTop, 0) + 2
                && cover.bottom <= safeBottom() - 2
                && cover.left >= 0
                && cover.right <= screenWidth
                && cover.width() >= 60
                && cover.height() >= 60;
    }

    private static String contentDesc(AccessibilityNodeInfo node) {
        if (node == null) return null;
        try {
            CharSequence cd = node.getContentDescription();
            return cd == null ? null : cd.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean isCardNode(AccessibilityNodeInfo node) {
        String d = contentDesc(node);
        if (d == null) return false;
        for (String pre : CARD_PREFIXES) {
            if (d.startsWith(pre)) return true;
        }
        return false;
    }

    /** 卡片里最大的、宽度达标的 ImageView；没有就返回 null。 */
    private static Rect findCover(AccessibilityNodeInfo card, Rect cardRect) {
        return findCover(card, cardRect, 0.5f);
    }

    private static Rect findCover(AccessibilityNodeInfo card, Rect cardRect, float minWidthRatio) {
        Rect best = new Rect();
        int bestArea = 0;
        int n = safeCount(card);
        for (int i = 0; i < n; i++) {
            AccessibilityNodeInfo ch = safeChild(card, i);
            String cls = className(ch);
            if (cls != null && cls.contains("ImageView")) {
                Rect r = new Rect();
                safeBounds(ch, r);
                int area = r.width() * r.height();
                if (area > bestArea && r.width() >= cardRect.width() * minWidthRatio
                        && r.height() >= 60) {
                    bestArea = area;
                    best.set(r);
                }
            }
        }
        if (bestArea == 0) {
            // 有的卡封面在孙节点里：有界下钻两层，避免整树遍历。
            int guard = 0;
            for (int i = 0; i < n && guard < 12; i++) {
                AccessibilityNodeInfo ch = safeChild(card, i);
                if (ch == null) continue;
                int m = safeCount(ch);
                for (int j = 0; j < m && guard < 12; j++) {
                    guard++;
                    AccessibilityNodeInfo g = safeChild(ch, j);
                    String cls = className(g);
                    if (cls != null && cls.contains("ImageView")) {
                        Rect r = new Rect();
                        safeBounds(g, r);
                        int area = r.width() * r.height();
                        if (area > bestArea && r.width() >= cardRect.width() * minWidthRatio
                                && r.height() >= 60) {
                            bestArea = area;
                            best.set(r);
                        }
                    }
                }
            }
        }
        if (bestArea == 0) return null;
        return best;
    }

    private static String className(AccessibilityNodeInfo node) {
        if (node == null) return null;
        try {
            CharSequence cls = node.getClassName();
            return cls == null ? null : cls.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 找当前屏的列表容器。B站 8.90.2 的推荐流容器是 `android.widget.GridView`
     * （id=tv.danmaku.bili:id/recycler_view），只按类名找 RecyclerView 会返回 null，
     * 整屏零卡（2026-11-06 实测）。所以这里接受 RecyclerView / GridView / ListView，
     * 或者任何「全宽 + 至少 30% 屏高 + scrollable」的容器，多个候选取面积最大的。
     *
     * 为什么可以接受 GridView：B站只是把卡片列表从 RecyclerView 换成了 GridView，
     * 卡片节点仍是带 content-desc 的 ViewGroup，封面仍是卡片内的 ImageView；
     * 容器只用来缩小扫描范围，卡片还要过 content-desc / 封面校验，不会因为放宽
     * 容器识别而多画。首页外层 ViewPager、外层 RecyclerView 与内层 GridView 的
     * bounds 常常完全一样，面积并列时优先类名命中的列表，并让后访问到的（更深的）
     * 节点覆盖前一个，最终落点就是真正的卡片列表。
     *
     * 类名命中的列表优先于泛化可滚动容器：整页 ScrollView（B站
     * id=coordinatorLayout）也全宽、可滚动且面积比 GridView 更大，若一起按面积比，
     * 会选到 ScrollView，往下追 8 层也到不了卡片层。泛化规则只在没有任何类名命中时
     * 才使用，对应「换了个自定义容器类名」的情况。
     */
    private AccessibilityNodeInfo findScrollableList(AccessibilityNodeInfo root) {
        AccessibilityNodeInfo best = null;
        int bestArea = 0;
        AccessibilityNodeInfo bestGeneric = null;
        int bestGenericArea = 0;
        ArrayDeque<AccessibilityNodeInfo> q = new ArrayDeque<>();
        q.push(root);
        int guard = 0;
        while (!q.isEmpty() && guard++ < 900) {
            AccessibilityNodeInfo n = q.pop();
            boolean named = isListClass(n);
            Rect r = new Rect();
            safeBounds(n, r);
            int area = r.width() * r.height();
            if (area > 0 && named) {
                if (area >= bestArea) {   // 面积并列时取更深的（后访问到的）节点
                    best = n;
                    bestArea = area;
                }
            } else if (area > 0
                    && r.width() >= screenWidth * 0.9f
                    && r.height() >= screenHeight * 0.3f
                    && scrollable(n)) {
                if (area > bestGenericArea) {
                    bestGeneric = n;
                    bestGenericArea = area;
                }
            }
            int cnt = safeCount(n);
            for (int i = cnt - 1; i >= 0; i--) {
                AccessibilityNodeInfo ch = safeChild(n, i);
                if (ch != null) q.push(ch);
            }
        }
        AccessibilityNodeInfo found = best != null ? best : bestGeneric;
        if (found != null) {
            Log.i(TAG, "bili list class=" + className(found)
                    + " area=" + (best != null ? bestArea : bestGenericArea));
        }
        return found;
    }

    private static boolean scrollable(AccessibilityNodeInfo n) {
        if (n == null) return false;
        try {
            return n.isScrollable();
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean isListClass(AccessibilityNodeInfo n) {
        String cls = className(n);
        return cls != null
                && (cls.contains("RecyclerView")
                || cls.contains("GridView")
                || cls.contains("ListView"));
    }

    /**
     * 兜底：content-desc 全部缺失时，用卡片内的 title / cover / real_desc view id
     * 反推卡片根。只在 desc 扫描零卡时执行；desc 正常时不走这里，现网行为不变。
     */
    private void addCardsByViewId(AccessibilityNodeInfo root, List<CardTarget> out) {
        List<AccessibilityNodeInfo> titles = null;
        try {
            titles = root.findAccessibilityNodeInfosByViewId(ID_TITLE);
        } catch (Throwable ignored) {
        }
        if (titles == null || titles.isEmpty()) return;
        HashSet<String> seen = new HashSet<>();
        for (AccessibilityNodeInfo t : titles) {
            String title = textOf(t);
            if (title.isEmpty()) continue;
            AccessibilityNodeInfo card = findCardWrapper(t);
            if (card == null) continue;
            Rect cardRect = new Rect();
            safeBounds(card, cardRect);
            if (cardRect.width() < screenWidth * 0.3f || cardRect.height() < 120) continue;
            Rect cover = findCover(card, cardRect);
            if (cover == null) continue;
            String author = findAuthor(card);
            String fp = fingerprint(title, author);
            if (!seen.add(fp)) continue;
            out.add(new CardTarget(fp, title, author, cover, isFullyVisible(cover)));
        }
        if (!out.isEmpty()) Log.i(TAG, "bili viewid cards=" + out.size());
    }

    /**
     * 从标题节点往上找卡片根：最近一个带 cover / real_desc view id（或 content-desc
     * 卡片前缀）的祖先。整屏高的列表容器不算卡片，遇到就停，避免把容器当卡。
     */
    private AccessibilityNodeInfo findCardWrapper(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo cur = node;
        for (int depth = 0; depth < 7; depth++) {
            AccessibilityNodeInfo p = parentOf(cur);
            if (p == null) return null;
            Rect r = new Rect();
            safeBounds(p, r);
            if (r.width() <= 0 || r.height() <= 0) return null;
            if (r.height() > screenHeight * 0.7f) return null;
            if (isListClass(p)) return null;   // 列表容器本身不是卡片
            if (isCardNode(p) || hasViewId(p, ID_COVER) || hasViewId(p, ID_REAL_DESC)) {
                return p;
            }
            cur = p;
        }
        return null;
    }

    private static String textOf(AccessibilityNodeInfo node) {
        if (node == null) return "";
        try {
            CharSequence t = node.getText();
            return t == null ? "" : t.toString().trim();
        } catch (Throwable t) {
            return "";
        }
    }

    private static String findAuthor(AccessibilityNodeInfo card) {
        List<AccessibilityNodeInfo> hits = null;
        try {
            hits = card.findAccessibilityNodeInfosByViewId(ID_REAL_DESC);
        } catch (Throwable ignored) {
        }
        if (hits == null) return "";
        for (AccessibilityNodeInfo h : hits) {
            String s = textOf(h);
            if (!s.isEmpty()) return s;
        }
        return "";
    }

    private static boolean hasViewId(AccessibilityNodeInfo node, String id) {
        try {
            List<AccessibilityNodeInfo> hits = node.findAccessibilityNodeInfosByViewId(id);
            return hits != null && !hits.isEmpty();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 卡片子树里按 view id 取第一段非空文本（详情页相关卡的结构化兜底）。 */
    private static String findTextByViewId(AccessibilityNodeInfo card, String id) {
        List<AccessibilityNodeInfo> hits = null;
        try {
            hits = card.findAccessibilityNodeInfosByViewId(id);
        } catch (Throwable ignored) {
        }
        if (hits == null) return "";
        for (AccessibilityNodeInfo h : hits) {
            String s = textOf(h);
            if (!s.isEmpty()) return s.length() > 80 ? s.substring(0, 80) : s;
        }
        return "";
    }

    private static AccessibilityNodeInfo parentOf(AccessibilityNodeInfo n) {
        if (n == null) return null;
        try {
            return n.getParent();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 从列表容器往下追到「直接子节点就是卡片」的容器。 */
    private AccessibilityNodeInfo findCardContainer(AccessibilityNodeInfo rv) {
        AccessibilityNodeInfo cur = rv;
        for (int depth = 0; depth < 8; depth++) {
            int n = safeCount(cur);
            if (n == 0) return null;
            int matches = 0;
            AccessibilityNodeInfo widest = null;
            int widestArea = -1;
            for (int i = 0; i < n; i++) {
                AccessibilityNodeInfo ch = safeChild(cur, i);
                if (ch == null) continue;
                if (isCardNode(ch)) matches++;
                Rect r = new Rect();
                safeBounds(ch, r);
                int area = r.width() * r.height();
                if (area > widestArea) {
                    widestArea = area;
                    widest = ch;
                }
            }
            if (matches >= 3) return cur;
            if (widest == null) return null;
            cur = widest;
        }
        return null;
    }

    /**
     * 找B站底部页签栏（首页/关注/会员购/我的）的上沿，作为绘制下边界；
     * 找不到时按保守值 screenH-300 收，宁可少画也不压导航栏。
     */
    private void detectNavTop(AccessibilityNodeInfo root) {
        navSearchDone = true;
        int best = 0;
        String[] tabs = {"首页", "关注", "会员购", "我的"};
        for (String tab : tabs) {
            List<AccessibilityNodeInfo> hits = null;
            try {
                hits = root.findAccessibilityNodeInfosByText(tab);
            } catch (Throwable ignored) {
            }
            if (hits == null) continue;
            for (AccessibilityNodeInfo node : hits) {
                Rect r = new Rect();
                safeBounds(node, r);
                if (r.top > screenHeight * 0.85f && r.top <= screenHeight) {
                    if (best == 0 || r.top < best) best = r.top;
                }
                AccessibilityNodeInfo p = node;
                for (int k = 0; k < 3; k++) {
                    try {
                        p = p.getParent();
                    } catch (Throwable t) {
                        break;
                    }
                    if (p == null) break;
                    Rect pr = new Rect();
                    safeBounds(p, pr);
                    if (pr.width() >= screenWidth * 0.8f && pr.top > screenHeight * 0.75f
                            && pr.top <= screenHeight) {
                        if (best == 0 || pr.top < best) best = pr.top;
                    }
                }
            }
        }
        if (best > 0) {
            navTop = Math.min(best - 8, screenHeight - 300);
        } else {
            navTop = Math.min(rvBottom > 0 ? rvBottom : screenHeight, screenHeight - 300);
        }
        Log.i(TAG, "nav-top=" + navTop + " src=" + (best > 0 ? "tab" : "fallback"));
    }

    /** content-desc = "视频,<标题>,<UP主…>,…"；详情页是「标题，up主…」。两种都要取到干净的标题。 */
    private static String extractTitle(String desc) {
        String d = desc;
        for (String pre : new String[]{"视频，", "视频,", "竖版视频，", "竖版视频,",
                "直播，", "直播,", "创作推广，", "创作推广,", "广告，", "广告,"}) {
            if (d.startsWith(pre)) {
                d = d.substring(pre.length());
                break;
            }
        }
        int up = indexOfUp(d);
        int cut = d.length();
        if (up > 0 && up < cut) cut = up;
        int comma = d.indexOf(',');
        if (comma >= 0 && comma < cut) cut = comma;
        int commaCn = d.indexOf('，');
        if (commaCn >= 0 && commaCn < cut) cut = commaCn;
        String t = d.substring(0, cut).trim();
        return t.length() > 80 ? t.substring(0, 80) : t;
    }

    /** 作者：desc 里 "UP主：" / "UP主:" / "UP主 xxx" 之后、下一个逗号之前；拿不到就留空。 */
    private static String extractAuthor(String desc) {
        int idx = indexOfUp(desc);
        if (idx < 0) return "";
        int p = idx + 3;
        if (p < desc.length() && (desc.charAt(p) == ':' || desc.charAt(p) == '：')) p++;
        int end = desc.length();
        int comma = desc.indexOf(',', p);
        if (comma >= 0 && comma < end) end = comma;
        int commaCn = desc.indexOf('，', p);
        if (commaCn >= 0 && commaCn < end) end = commaCn;
        return desc.substring(p, end).trim();
    }

    private static int indexOfUp(String s) {
        int a = s.indexOf("UP主");
        int b = s.indexOf("up主");
        if (a < 0) return b;
        if (b < 0) return a;
        return Math.min(a, b);
    }

    /** 指纹：标题 + 作者。B站的无障碍树里没有 bvid，只能这样在会话内稳定对齐。 */
    private static String fingerprint(String title, String author) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] d = md.digest((title + "\u0001" + author).getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) sb.append(String.format(Locale.ROOT, "%02x", d[i]));
            return sb.toString();
        } catch (Throwable t) {
            return Integer.toHexString((title + "\u0001" + author).hashCode());
        }
    }

    private static int safeCount(AccessibilityNodeInfo n) {
        if (n == null) return 0;
        try {
            return n.getChildCount();
        } catch (Throwable t) {
            return 0;
        }
    }

    private static AccessibilityNodeInfo safeChild(AccessibilityNodeInfo n, int i) {
        if (n == null) return null;
        try {
            return n.getChild(i);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void safeBounds(AccessibilityNodeInfo n, Rect r) {
        if (n == null || r == null) return;
        try {
            n.getBoundsInScreen(r);
        } catch (Throwable ignored) {
        }
    }
}
