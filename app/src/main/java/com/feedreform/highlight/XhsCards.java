package com.feedreform.highlight;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * 小红书首页推荐流卡片识别（纯逻辑，不依赖 Activity / Service / AndroidX）。
 *
 * <p>只依赖 {@link AccessibilityNodeInfo} 与 {@link android.graphics.Rect}，主 App 可以直接复制
 * XhsCards.java + XhsCard.java 两个文件使用。
 *
 * <p>规则来源：2026-11-05 真机取样（小红书 9.52.1，PLG110），见
 * project/verification/2026-11-05-liangdian-v05/stage1-xhs-a11y.md：
 * <ul>
 *   <li>卡片 = 唯一 scrollable 的 androidx RecyclerView 的直接子节点，且 content-desc 非空；</li>
 *   <li>content-desc 形如 {@code 笔记␠␠<完整标题> 来自<作者> <n>赞}（类型：笔记/视频/直播，直播尾部
 *       {@code N人观看}）；</li>
 *   <li>封面 = 卡内第一个宽度等于卡片宽度的 ImageView（视频角标 60、头像 78、点赞 90 都更窄）；</li>
 *   <li>半露卡 bounds 被裁到 RecyclerView 边界、子节点可能缺失，content-desc 仍完整；</li>
 *   <li>底部导航容器 top=2087 挡住 RecyclerView 最后 132px，必须裁掉。</li>
 * </ul>
 */
public final class XhsCards {

    /** 卡片 content-desc 里的类型词，按长度/先后来匹配。 */
    private static final String[] TYPES = {"笔记", "视频", "直播"};

    private static final String[] VIEW_CLASSES = {"android.view.View"};
    private static final String IMAGE_VIEW = "ImageView";
    private static final String TEXT_VIEW = "TextView";
    private static final String RECYCLER = "RecyclerView";

    private XhsCards() {
    }

    /**
     * 找出当前屏幕上的小红书推荐流卡片。
     *
     * @param root         无障碍根节点（通常 getRootInActiveWindow()）
     * @param screenWidth  屏幕宽（像素），用于裁剪
     * @param screenHeight 屏幕高（像素），用于裁剪
     * @param navTopPx     底部导航容器 top（像素），该线以下不视为可见；<=0 时退化为 screenHeight
     * @return 识别到的卡片，按 RecyclerView 子节点顺序；不保证完全可见的卡也在内（看 fullyVisible）
     */
    public static List<XhsCard> find(AccessibilityNodeInfo root,
                                     int screenWidth, int screenHeight, int navTopPx) {
        List<XhsCard> out = new ArrayList<>();
        if (root == null) {
            return out;
        }
        AccessibilityNodeInfo list = findFeedList(root, screenWidth);
        if (list == null) {
            return out;
        }
        Rect listBounds = new Rect();
        list.getBoundsInScreen(listBounds);
        if (listBounds.width() <= 0 || listBounds.height() <= 0) {
            return out;
        }
        int navTop = navTopPx > 0 ? Math.min(navTopPx, screenHeight) : screenHeight;
        int visibleBottom = Math.min(listBounds.bottom, navTop);
        if (visibleBottom <= listBounds.top) {
            visibleBottom = listBounds.bottom;
        }
        int maxCardWidth = (int) (listBounds.width() * 0.78f);

        for (int i = 0; i < list.getChildCount(); i++) {
            AccessibilityNodeInfo child = list.getChild(i);
            if (child == null) {
                continue;
            }
            CharSequence descSeq = child.getContentDescription();
            if (descSeq == null || descSeq.length() == 0) {
                continue;
            }
            String desc = descSeq.toString().trim();
            if (desc.isEmpty()) {
                continue;
            }
            Rect cardBounds = new Rect();
            child.getBoundsInScreen(cardBounds);
            if (cardBounds.width() <= 0 || cardBounds.height() <= 0) {
                continue;
            }
            // 只接受双列卡片宽度；整宽 banner / 页脚之类不当作卡片。
            if (cardBounds.width() > maxCardWidth) {
                continue;
            }
            XhsCard card = new XhsCard();
            card.desc = desc;
            card.bounds = new Rect(cardBounds);
            if (!parseDesc(desc, card)) {
                continue;
            }
            applyAuthorFallback(child, card);
            card.cover = findCover(child, cardBounds);
            card.ad = hasAdBadge(child);

            boolean insideTop = cardBounds.top > listBounds.top + 2;
            boolean insideBottom = cardBounds.bottom < visibleBottom - 2;
            boolean insideX = cardBounds.left >= listBounds.left - 2
                    && cardBounds.right <= listBounds.right + 2;
            card.fullyVisible = insideTop && insideBottom && insideX;
            if (card.cover == null || card.cover.height() <= 0) {
                card.fullyVisible = false;
            }
            out.add(card);
        }
        return out;
    }

    /**
     * 底部导航容器 top 检测（不依赖文案）：
     * <ol>
     *   <li>结构化：屏幕下方约 15% 区域内、全宽、直接子节点里 ≥3 个可点击（且也落在底部区域）
     *       的容器，取容器 top；容器自身 top 不在底部区域时（自定义导航条整屏绘制）取可点击子节点
     *       的最小 top。英文 Home / 中文 首页 都适用；</li>
     *   <li>文案兜底：content-desc 恰为「首页」/「Home」且位于屏幕下方 45% 之后的节点；</li>
     *   <li>都拿不到返回 -1，由调用方用 {@code screenHeight*0.8864} 兜底。</li>
     * </ol>
     */
    public static int detectNavTop(AccessibilityNodeInfo root, int screenWidth, int screenHeight) {
        int structural = detectNavTopStructural(root, screenWidth, screenHeight);
        if (structural > 0) {
            return structural;
        }
        AccessibilityNodeInfo home = findBottomHome(root, screenHeight);
        if (home == null) {
            return -1;
        }
        Rect b = new Rect();
        home.getBoundsInScreen(b);
        return b.top;
    }

    /** 结构化底部导航 top：见 {@link #detectNavTop} 第 1 条。 */
    private static int detectNavTopStructural(AccessibilityNodeInfo root,
                                              int screenWidth, int screenHeight) {
        List<AccessibilityNodeInfo> all = new ArrayList<>();
        collectAll(root, all, 0);
        int best = Integer.MAX_VALUE;
        for (AccessibilityNodeInfo node : all) {
            Rect b = new Rect();
            try {
                node.getBoundsInScreen(b);
            } catch (Throwable t) {
                continue;
            }
            if (b.width() < screenWidth * 0.95f) {
                continue;
            }
            if (b.top < screenHeight * 0.80f || b.top >= screenHeight) {
                continue;
            }
            if (b.bottom > screenHeight + 4) {
                continue;
            }
            int count = 0;
            try {
                count = node.getChildCount();
            } catch (Throwable ignored) {
            }
            int clickable = 0;
            int childTop = Integer.MAX_VALUE;
            for (int i = 0; i < count; i++) {
                AccessibilityNodeInfo child = null;
                try {
                    child = node.getChild(i);
                } catch (Throwable ignored) {
                }
                if (child == null) {
                    continue;
                }
                boolean click;
                try {
                    click = child.isClickable();
                } catch (Throwable t) {
                    click = false;
                }
                if (!click) {
                    continue;
                }
                Rect cb = new Rect();
                try {
                    child.getBoundsInScreen(cb);
                } catch (Throwable t) {
                    continue;
                }
                if (cb.width() <= 0 || cb.top < screenHeight * 0.85f) {
                    continue;
                }
                clickable++;
                if (cb.top < childTop) {
                    childTop = cb.top;
                }
            }
            if (clickable < 3 || childTop == Integer.MAX_VALUE) {
                continue;
            }
            int top = b.top >= screenHeight * 0.85f ? b.top : childTop;
            if (top > 0 && top < best) {
                best = top;
            }
        }
        return best == Integer.MAX_VALUE ? -1 : best;
    }

    // ---------------------------------------------------------------- tree walk

    private static AccessibilityNodeInfo findFeedList(AccessibilityNodeInfo root, int screenWidth) {
        List<AccessibilityNodeInfo> all = new ArrayList<>();
        collectLists(root, all, 0);
        // 首页列表是全宽的；顶部页签条（For You/RED/…）是窄条，英文界面下它的子节点数（7）
        // 反而多于首屏卡片数（6），按子节点数评分会误选页签条，所以先按宽度筛一遍。
        List<AccessibilityNodeInfo> candidates = new ArrayList<>();
        for (AccessibilityNodeInfo n : all) {
            Rect b = new Rect();
            try {
                n.getBoundsInScreen(b);
            } catch (Throwable t) {
                continue;
            }
            if (b.width() >= screenWidth * 0.95f) {
                candidates.add(n);
            }
        }
        if (candidates.isEmpty()) {
            // 结构未知的机型/版本：退回原来的「全部 scrollable RecyclerView」。
            candidates = all;
        }
        AccessibilityNodeInfo best = null;
        int bestScore = 0;
        for (AccessibilityNodeInfo n : candidates) {
            int score = 0;
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c == null) {
                    continue;
                }
                CharSequence cd = c.getContentDescription();
                if (cd != null && cd.length() > 0) {
                    score++;
                }
            }
            if (score > bestScore) {
                bestScore = score;
                best = n;
            }
        }
        return best;
    }

    private static void collectLists(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> out, int depth) {
        if (node == null || depth > 40) {
            return;
        }
        CharSequence cls = node.getClassName();
        if (node.isScrollable() && cls != null && cls.toString().contains(RECYCLER)) {
            out.add(node);
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            collectLists(node.getChild(i), out, depth + 1);
        }
    }

    private static void collectAll(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> out, int depth) {
        if (node == null || depth > 60) {
            return;
        }
        out.add(node);
        for (int i = 0; i < node.getChildCount(); i++) {
            collectAll(node.getChild(i), out, depth + 1);
        }
    }

    private static AccessibilityNodeInfo findBottomHome(AccessibilityNodeInfo node, int screenHeight) {
        if (node == null) {
            return null;
        }
        CharSequence cd = node.getContentDescription();
        if (cd != null) {
            String s = cd.toString().trim();
            // 中文「首页」/ 英文 Home 都认；仅在结构化导航检测失败时用。
            if ("首页".equals(s) || "Home".equalsIgnoreCase(s)) {
                Rect b = new Rect();
                node.getBoundsInScreen(b);
                if (b.top > screenHeight * 0.45f) {
                    return node;
                }
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo r = findBottomHome(node.getChild(i), screenHeight);
            if (r != null) {
                return r;
            }
        }
        return null;
    }

    private static Rect findCover(AccessibilityNodeInfo card, Rect cardBounds) {
        Rect cover = new Rect();
        if (findCoverIn(card, cardBounds, cover, 0)) {
            return cover;
        }
        // 兜底：子节点缺失时用标题 View 的 top 反推封面底边（阶段 1 实测封面与标题间距 24px）。
        Rect title = new Rect();
        if (findTitleView(card, cardBounds, title, 0)) {
            int coverBottom = title.top - 24;
            if (coverBottom > cardBounds.top + 40) {
                return new Rect(cardBounds.left, cardBounds.top, cardBounds.right, coverBottom);
            }
        }
        return null;
    }

    private static boolean findCoverIn(AccessibilityNodeInfo node, Rect cardBounds, Rect out, int depth) {
        if (node == null || depth > 20) {
            return false;
        }
        CharSequence cls = node.getClassName();
        if (cls != null && cls.toString().contains(IMAGE_VIEW) && node != null) {
            Rect b = new Rect();
            node.getBoundsInScreen(b);
            if (Math.abs(b.width() - cardBounds.width()) <= 4
                    && Math.abs(b.left - cardBounds.left) <= 4
                    && b.top >= cardBounds.top - 4
                    && b.height() > 0) {
                out.set(b);
                return true;
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            if (findCoverIn(node.getChild(i), cardBounds, out, depth + 1)) {
                return true;
            }
        }
        return false;
    }

    private static boolean findTitleView(AccessibilityNodeInfo node, Rect cardBounds, Rect out, int depth) {
        if (node == null || depth > 20) {
            return false;
        }
        CharSequence cls = node.getClassName();
        if (cls != null && cls.toString().equals(VIEW_CLASSES[0])) {
            Rect b = new Rect();
            node.getBoundsInScreen(b);
            if (b.top > cardBounds.top && b.bottom < cardBounds.bottom && b.width() > 0) {
                out.set(b);
                return true;
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            if (findTitleView(node.getChild(i), cardBounds, out, depth + 1)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasAdBadge(AccessibilityNodeInfo card) {
        return containsText(card, "广告", 0);
    }

    private static boolean containsText(AccessibilityNodeInfo node, String text, int depth) {
        if (node == null || depth > 20) {
            return false;
        }
        CharSequence t = node.getText();
        if (t != null && text.equals(t.toString().trim())) {
            return true;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            if (containsText(node.getChild(i), text, depth + 1)) {
                return true;
            }
        }
        return false;
    }

    /** 作者行里有纯文本作者名，用于兜底校正 content-desc 里可能含「来自」的标题。 */
    private static void applyAuthorFallback(AccessibilityNodeInfo card, XhsCard out) {
        String author = findAuthorText(card, 0);
        if (author == null || author.isEmpty()) {
            return;
        }
        if (out.author == null || out.author.isEmpty()) {
            out.author = author;
            out.fingerprint = fingerprint(out.type, out.title, out.author);
            return;
        }
        if (out.author.equals(author)) {
            return;
        }
        // 作者名不是 content-desc 尾部解析出来的那个：按「来自+作者名」重新切分标题。
        String raw = out.desc;
        int idx = raw.lastIndexOf("来自" + author);
        if (idx > 0) {
            String title = raw.substring(0, idx).trim();
            out.title = normalize(title);
            out.author = normalize(author);
            out.fingerprint = fingerprint(out.type, out.title, out.author);
        }
    }

    private static String findAuthorText(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > 20) {
            return null;
        }
        CharSequence cls = node.getClassName();
        if (cls != null && cls.toString().contains(TEXT_VIEW)) {
            CharSequence t = node.getText();
            if (t != null) {
                String s = t.toString().trim();
                if (!s.isEmpty() && !s.equals("广告") && !s.equals("直播中")
                        && !s.matches("[0-9.,]+[万千wW]?") && !s.matches("[0-9.,]+赞")) {
                    return s;
                }
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            String r = findAuthorText(node.getChild(i), depth + 1);
            if (r != null) {
                return r;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- parsing

    /** 解析 content-desc，成功返回 true 并填 type/title/author/fingerprint。 */
    static boolean parseDesc(String desc, XhsCard out) {
        String s = desc.trim();
        String type = null;
        int pos = -1;
        for (String t : TYPES) {
            if (s.startsWith(t)) {
                type = t;
                pos = t.length();
                break;
            }
        }
        if (type == null) {
            return false;
        }
        String rest = s.substring(pos).trim();
        if (rest.isEmpty()) {
            return false;
        }
        // 尾部形态：直播 "… 来自X 503人观看"；笔记/视频 "… 来自X 305赞"，无赞时是 "… 来自X 赞"。
        rest = rest.replaceFirst("\\s*[0-9.,]*[万千wW]?\\+?人观看\\s*$", "");
        rest = rest.replaceFirst("\\s*[0-9.,]*[万千wW]?\\+?赞\\s*$", "");
        rest = rest.trim();
        int idx = rest.lastIndexOf("来自");
        String title;
        String author;
        if (idx > 0) {
            title = rest.substring(0, idx).trim();
            author = rest.substring(idx + 2).trim();
        } else {
            title = rest;
            author = "";
        }
        if (title.isEmpty()) {
            return false;
        }
        out.type = type;
        out.title = normalize(title);
        out.author = normalize(author);
        out.fingerprint = fingerprint(out.type, out.title, out.author);
        return true;
    }

    /** 折叠空白（含 \u00a0 \u2007 \u200b \u3164 \u3000）并去首尾空格，保证指纹稳定。 */
    static String normalize(String s) {
        if (s == null) {
            return "";
        }
        return s.replaceAll("[\\s\\u00a0\\u2007\\u200b\\u3164\\u3000]+", " ").trim();
    }

    static String fingerprint(String type, String title, String author) {
        String src = (type == null ? "" : type) + "|" + (title == null ? "" : title) + "|"
                + (author == null ? "" : author);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] d = md.digest(src.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder(40);
            for (byte b : d) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(src.hashCode());
        }
    }
}
