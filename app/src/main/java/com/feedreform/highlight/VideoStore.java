package com.feedreform.highlight;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 本机视频缓存（单文件 JSON，无数据库依赖）。
 *
 * - 按 bvid 去重，同一 UP 的多条投稿全部保留；
 * - 推荐流与关注流各自维护一份 bvid 顺序，视频本体只有一份；
 * - 关注流额外保存 offset / hasMore / lastSyncAt / page，以及还有没取完时的续取位置；
 * - 分页失败不清空已有内容，读失败就当作空缓存继续。
 */
final class VideoStore {

    private static final String TAG = "HLBASE";
    private static final String FILE = "videos.json";
    private static final VideoStore INSTANCE = new VideoStore();

    static final class Video {
        String bvid = "";
        String title = "";
        String author = "";
        String cover = "";
        String duration = "";
        /** "rcmd" 或 "follow"。 */
        String source = "rcmd";
        long mid = 0;
        long pubTs = 0;
        long firstSeen = 0;
        long judgedAt = 0;
        long seq = 0;
        /** -1 = 未判断（UNKNOWN 也按未判断处理），0 = 普通，1 = 暗，2 = 发亮。 */
        int result = Judge.UNKNOWN;
        boolean read = false;

        boolean sameAs(String otherBvid) {
            return bvid.equals(otherBvid);
        }
    }

    private final Map<String, Video> byId = new LinkedHashMap<>();
    private final List<String> rcmdIds = new ArrayList<>();
    private final List<String> followIds = new ArrayList<>();

    private long seq = 0;
    private String followOffset = "";
    private boolean followHasMore = true;
    private int followPage = 0;
    private long followLastSyncAt = 0;
    private boolean loaded = false;
    private File file;

    private VideoStore() {
    }

    static VideoStore get(Context c) {
        synchronized (INSTANCE) {
            if (!INSTANCE.loaded) {
                INSTANCE.file = new File(c.getFilesDir(), FILE);
                INSTANCE.load();
            }
            return INSTANCE;
        }
    }

    // ---------- 读 ----------

    synchronized List<Video> rcmd() {
        List<Video> out = new ArrayList<>();
        for (String id : rcmdIds) {
            Video v = byId.get(id);
            if (v != null) out.add(v);
        }
        return out;
    }

    synchronized List<Video> follow() {
        List<Video> out = new ArrayList<>();
        for (String id : followIds) {
            Video v = byId.get(id);
            if (v != null) out.add(v);
        }
        Collections.sort(out, (a, b) -> Long.compare(b.pubTs, a.pubTs));
        return out;
    }

    synchronized boolean hasFollow() {
        return !followIds.isEmpty();
    }

    synchronized String followOffset() {
        return followOffset;
    }

    synchronized boolean followHasMore() {
        return followHasMore;
    }

    synchronized int followPage() {
        return followPage;
    }

    synchronized long followLastSyncAt() {
        return followLastSyncAt;
    }

    /** 还有下一页没取完：界面可据此提示「继续加载」。 */
    synchronized boolean followPending() {
        return followHasMore && !followOffset.isEmpty();
    }

    synchronized int count(String source) {
        int n = 0;
        for (String id : "follow".equals(source) ? followIds : rcmdIds) {
            if (byId.containsKey(id)) n++;
        }
        return n;
    }

    // ---------- 写 ----------

    /** 推荐流每次刷新替换整批（fresh_idx 换了就是新一批）。 */
    synchronized List<Video> replaceRcmd(List<Video> items) {
        rcmdIds.clear();
        putAll(items);
        for (Video v : items) {
            if (v.bvid.isEmpty()) continue;
            if (!rcmdIds.contains(v.bvid)) rcmdIds.add(v.bvid);
        }
        save();
        return rcmd();
    }

    /** 关注流追加一页：bvid 去重、保留首次出现顺序、记录翻页位置。 */
    synchronized void appendFollow(List<Video> items, String nextOffset, boolean hasMore) {
        putAll(items);
        for (Video v : items) {
            if (v.bvid.isEmpty()) continue;
            if (!followIds.contains(v.bvid)) followIds.add(v.bvid);
        }
        followOffset = nextOffset == null ? "" : nextOffset;
        followHasMore = hasMore;
        followPage++;
        followLastSyncAt = System.currentTimeMillis();
        save();
        Log.i(TAG, "store-follow total=" + followIds.size() + " page=" + followPage
                + " hasMore=" + hasMore + " offsetLen=" + followOffset.length());
    }

    /** 关注流刷新：把最新一页并进缓存，翻页位置重置到这一页（后续「加载更多」从这里继续）。 */
    synchronized void syncFollowHead(List<Video> items, String nextOffset, boolean hasMore) {
        putAll(items);
        for (Video v : items) {
            if (!v.bvid.isEmpty() && !followIds.contains(v.bvid)) followIds.add(v.bvid);
        }
        followOffset = nextOffset == null ? "" : nextOffset;
        followHasMore = hasMore;
        followPage = 1;
        followLastSyncAt = System.currentTimeMillis();
        save();
        Log.i(TAG, "store-follow-sync total=" + followIds.size() + " hasMore=" + hasMore
                + " offsetLen=" + followOffset.length());
    }

    /**
     * 刷新后把续取位置恢复到刷新前的游标（F4-02）：缓存比本次刷新的页更深时，
     * 「加载更多」不应该从刷新覆盖的那几页重新开始，否则会重复请求已经取过的 page。
     */
    synchronized void restoreFollowCursor(String offset, int page, boolean hasMore) {
        followOffset = offset == null ? "" : offset;
        followPage = page;
        followHasMore = hasMore;
        save();
        Log.i(TAG, "store-follow-cursor-restored page=" + page + " offsetLen=" + followOffset.length());
    }

    /** 首次拉取/清空后重来：先把翻页状态归零，再按页追加。 */
    synchronized void resetFollow() {
        followIds.clear();
        followOffset = "";
        followHasMore = true;
        followPage = 0;
        save();
    }

    synchronized void putResult(String bvid, int result, long judgedAt) {
        Video v = byId.get(bvid);
        if (v == null) return;
        v.result = result;
        v.judgedAt = judgedAt;
        save();
    }

    /** 规则变了：清掉已有结论，重新判断（缓存也由 Judge 按规则版本隔离）。 */
    synchronized void clearResults() {
        for (Video v : byId.values()) {
            v.result = Judge.UNKNOWN;
            v.judgedAt = 0;
        }
        save();
        Log.i(TAG, "store-results-cleared");
    }

    synchronized void clearAll() {
        byId.clear();
        rcmdIds.clear();
        followIds.clear();
        seq = 0;
        followOffset = "";
        followHasMore = true;
        followPage = 0;
        followLastSyncAt = 0;
        save();
        Log.i(TAG, "store-cleared");
    }

    private void putAll(List<Video> items) {
        long now = System.currentTimeMillis();
        for (Video v : items) {
            if (v == null || v.bvid.isEmpty()) continue;
            Video old = byId.get(v.bvid);
            if (old == null) {
                v.firstSeen = now;
                v.seq = ++seq;
                byId.put(v.bvid, v);
            } else {
                // 同一 bvid 只保留一份：更新可变展示字段，判断结论与首次时间不丢。
                old.title = v.title.isEmpty() ? old.title : v.title;
                old.author = v.author.isEmpty() ? old.author : v.author;
                old.cover = v.cover.isEmpty() ? old.cover : v.cover;
                old.duration = v.duration.isEmpty() ? old.duration : v.duration;
                if (v.mid > 0) old.mid = v.mid;
                if (v.pubTs > 0) old.pubTs = v.pubTs;
            }
        }
    }

    // ---------- 持久化 ----------

    private void load() {
        if (file == null || !file.isFile()) {
            loaded = true;
            return;
        }
        try (InputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            JSONObject root = new JSONObject(out.toString(StandardCharsets.UTF_8.name()));
            seq = root.optLong("seq", 0);
            followOffset = root.optString("followOffset", "");
            followHasMore = root.optBoolean("followHasMore", true);
            followPage = root.optInt("followPage", 0);
            followLastSyncAt = root.optLong("followLastSyncAt", 0);
            JSONObject items = root.optJSONObject("items");
            if (items != null) {
                java.util.Iterator<String> keys = items.keys();
                while (keys.hasNext()) {
                    String id = keys.next();
                    JSONObject o = items.optJSONObject(id);
                    if (o == null) continue;
                    Video v = new Video();
                    v.bvid = o.optString("bvid", id);
                    v.title = o.optString("title", "");
                    v.author = o.optString("author", "");
                    v.cover = o.optString("cover", "");
                    v.duration = o.optString("duration", "");
                    v.source = o.optString("source", "follow");
                    v.mid = o.optLong("mid", 0);
                    v.pubTs = o.optLong("pubTs", 0);
                    v.firstSeen = o.optLong("firstSeen", 0);
                    v.judgedAt = o.optLong("judgedAt", 0);
                    v.seq = o.optLong("seq", 0);
                    v.result = o.optInt("result", Judge.UNKNOWN);
                    v.read = o.optBoolean("read", false);
                    if (!v.bvid.isEmpty()) byId.put(v.bvid, v);
                }
            }
            readIds(root.optJSONArray("rcmdIds"), rcmdIds);
            readIds(root.optJSONArray("followIds"), followIds);
            Log.i(TAG, "store-loaded items=" + byId.size() + " rcmd=" + rcmdIds.size()
                    + " follow=" + followIds.size());
        } catch (Exception e) {
            Log.w(TAG, "store-load failed: " + e.getClass().getSimpleName());
        } finally {
            loaded = true;
        }
    }

    private static void readIds(JSONArray arr, List<String> out) {
        if (arr == null) return;
        for (int i = 0; i < arr.length(); i++) {
            String id = arr.optString(i, "");
            if (!id.isEmpty() && !out.contains(id)) out.add(id);
        }
    }

    private void save() {
        if (file == null) return;
        try {
            JSONObject root = new JSONObject();
            root.put("seq", seq);
            root.put("followOffset", followOffset);
            root.put("followHasMore", followHasMore);
            root.put("followPage", followPage);
            root.put("followLastSyncAt", followLastSyncAt);
            JSONObject items = new JSONObject();
            for (Map.Entry<String, Video> e : byId.entrySet()) {
                Video v = e.getValue();
                JSONObject o = new JSONObject();
                o.put("bvid", v.bvid);
                o.put("title", v.title);
                o.put("author", v.author);
                o.put("cover", v.cover);
                o.put("duration", v.duration);
                o.put("source", v.source);
                o.put("mid", v.mid);
                o.put("pubTs", v.pubTs);
                o.put("firstSeen", v.firstSeen);
                o.put("judgedAt", v.judgedAt);
                o.put("seq", v.seq);
                o.put("result", v.result);
                o.put("read", v.read);
                items.put(v.bvid, o);
            }
            root.put("items", items);
            root.put("rcmdIds", new JSONArray(rcmdIds));
            root.put("followIds", new JSONArray(followIds));
            File tmp = new File(file.getAbsolutePath() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(root.toString().getBytes(StandardCharsets.UTF_8));
            }
            if (!tmp.renameTo(file)) {
                // 少数机型 rename 覆盖失败：删除后重试一次。
                //noinspection ResultOfMethodCallIgnored
                file.delete();
                //noinspection ResultOfMethodCallIgnored
                tmp.renameTo(file);
            }
        } catch (Exception e) {
            Log.w(TAG, "store-save failed: " + e.getClass().getSimpleName());
        }
    }
}
