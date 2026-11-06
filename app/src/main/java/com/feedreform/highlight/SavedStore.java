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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 收藏夹的本机存储：单文件 JSON（filesDir/saved.json），写入用 .tmp + rename，
 * 失败时保留旧文件，不丢已有数据。
 *
 * 结构：
 * {
 *   "seq": 2,
 *   "lastFolder": "f1",
 *   "folders": [{"id":"f1","name":"收藏","createdAt":1793…}],
 *   "items": [{"key":"bili|BV1xx","source":"bili","id":"BV1xx","title":"…","author":"…",
 *              "cover":"https://…","duration":"12:03","url":"","savedAt":1793…,
 *              "lastOpenAt":0,"opens":0,"folders":["f1"],"pinned":[]}]
 * }
 *
 * 封面首次保存/展示时下载到 filesDir/covers/&lt;key&gt;.jpg，之后优先读本地文件。
 */
final class SavedStore {

    private static final String TAG = "HLBASE";
    private static final String FILE = "saved.json";
    private static final SavedStore INSTANCE = new SavedStore();

    static final String SOURCE_BILI = "bili";
    static final String SOURCE_XHS = "xhs";

    static final class Folder {
        String id = "";
        String name = "";
        long createdAt = 0;

        int count(SavedStore store) {
            int n = 0;
            for (Item it : store.items()) {
                if (it.folders.contains(id)) n++;
            }
            return n;
        }
    }

    static final class Item {
        String key = "";
        String source = SOURCE_BILI;
        String id = "";
        String title = "";
        String author = "";
        String cover = "";
        String duration = "";
        String url = "";
        long savedAt = 0;
        long lastOpenAt = 0;
        int opens = 0;
        List<String> folders = new ArrayList<>();
        List<String> pinned = new ArrayList<>();

        boolean isVideo() {
            return SOURCE_BILI.equals(source);
        }
    }

    private final Map<String, Item> items = new LinkedHashMap<>();
    private final List<Folder> folders = new ArrayList<>();
    private long seq = 0;
    private String lastFolder = "";
    private File file;
    private File coverDir;
    private boolean loaded = false;

    private SavedStore() {
    }

    static SavedStore get(Context c) {
        synchronized (INSTANCE) {
            if (!INSTANCE.loaded) {
                INSTANCE.file = new File(c.getFilesDir(), FILE);
                INSTANCE.coverDir = new File(c.getFilesDir(), "covers");
                INSTANCE.load();
            }
            return INSTANCE;
        }
    }

    static String keyOf(String source, String id) {
        return (source == null ? "" : source) + "|" + (id == null ? "" : id);
    }

    // ---------- 读 ----------

    synchronized List<Folder> folders() {
        if (folders.isEmpty()) ensureDefault();
        return new ArrayList<>(folders);
    }

    synchronized Folder folder(String id) {
        for (Folder f : folders) if (f.id.equals(id)) return f;
        return null;
    }

    synchronized List<Item> items() {
        return new ArrayList<>(items.values());
    }

    synchronized Item find(String source, String id) {
        return items.get(keyOf(source, id));
    }

    synchronized boolean has(String source, String id) {
        return items.containsKey(keyOf(source, id));
    }

    /** 已收藏时返回它所在的夹 id 列表，否则空。 */
    synchronized List<String> foldersOf(String source, String id) {
        Item it = items.get(keyOf(source, id));
        return it == null ? new ArrayList<String>() : new ArrayList<>(it.folders);
    }

    synchronized String lastFolder() {
        return lastFolder;
    }

    synchronized int countIn(String folderId) {
        int n = 0;
        for (Item it : items.values()) if (it.folders.contains(folderId)) n++;
        return n;
    }

    // ---------- 写 ----------

    /** 新建收藏夹；名字重复时直接复用已有的，返回夹 id。 */
    synchronized String newFolder(String name) {
        String clean = name == null ? "" : name.trim().replace("\n", " ");
        if (clean.isEmpty()) clean = "收藏";
        if (clean.length() > 16) clean = clean.substring(0, 16);
        for (Folder f : folders) if (f.name.equals(clean)) return f.id;
        Folder f = new Folder();
        f.id = "f" + (++seq);
        f.name = clean;
        f.createdAt = System.currentTimeMillis();
        folders.add(f);
        save();
        Log.i(TAG, "saved-new-folder id=" + f.id + " name=" + clean);
        return f.id;
    }

    /**
     * 收藏一条内容：同一 source+id 只存一份，已存在时把缺的夹补上（不覆盖打开计数）。
     *
     * @return true 表示新增，false 表示只是补了夹。
     */
    synchronized boolean save(Item in, List<String> folderIds) {
        if (in == null || (in.id == null || in.id.isEmpty())) return false;
        if (folders.isEmpty()) ensureDefault();
        String key = keyOf(in.source, in.id);
        Item old = items.get(key);
        boolean added = false;
        if (old == null) {
            in.key = key;
            in.savedAt = System.currentTimeMillis();
            in.opens = 0;
            in.lastOpenAt = 0;
            in.folders = new ArrayList<>();
            in.pinned = new ArrayList<>();
            items.put(key, in);
            old = in;
            added = true;
        } else {
            if (old.title.isEmpty() && in.title != null) old.title = in.title;
            if (old.cover.isEmpty() && in.cover != null) old.cover = in.cover;
            if (old.author.isEmpty() && in.author != null) old.author = in.author;
            if (old.url.isEmpty() && in.url != null) old.url = in.url;
            if (old.duration.isEmpty() && in.duration != null) old.duration = in.duration;
        }
        List<String> target = folderIds == null || folderIds.isEmpty()
                ? defaultFolderIds() : folderIds;
        for (String fid : target) {
            if (fid == null || fid.isEmpty()) continue;
            if (!old.folders.contains(fid)) old.folders.add(fid);
        }
        save();
        Log.i(TAG, "saved-item key=" + key + " added=" + added + " folders=" + old.folders.size());
        return added;
    }

    /** 后台解析完成：把标题/封面/作者补进已存在的条目（不新建）。 */
    synchronized void enrich(String source, String id, String title, String cover,
                             String author, String url) {
        Item it = items.get(keyOf(source, id));
        if (it == null) return;
        if (title != null && !title.isEmpty()) it.title = title;
        if (cover != null && !cover.isEmpty()) it.cover = cover;
        if (author != null && !author.isEmpty()) it.author = author;
        if (url != null && !url.isEmpty()) it.url = url;
        save();
    }

    /**
     * 先用临时 id 保存、之后解析出真实笔记号：把条目搬到新 key，保留夹/置顶/打开计数。
     * 新 key 已存在时只把夹并过去。
     */
    synchronized void migrate(String source, String oldId, String newId) {
        if (newId == null || newId.isEmpty() || newId.equals(oldId)) return;
        String oldKey = keyOf(source, oldId);
        String newKey = keyOf(source, newId);
        Item old = items.remove(oldKey);
        if (old == null) return;
        Item exist = items.get(newKey);
        if (exist == null) {
            old.key = newKey;
            old.id = newId;
            items.put(newKey, old);
        } else {
            for (String fid : old.folders) {
                if (!exist.folders.contains(fid)) exist.folders.add(fid);
            }
            exist.opens = Math.max(exist.opens, old.opens);
        }
        save();
        deleteCover(oldKey);
        Log.i(TAG, "saved-migrated old=" + oldKey + " new=" + newKey);
    }

    synchronized void remove(String source, String id) {
        Item it = items.remove(keyOf(source, id));
        if (it == null) return;
        save();
        deleteCover(it.key);
        Log.i(TAG, "saved-removed key=" + it.key);
    }

    synchronized void setPinned(String source, String id, String folderId, boolean pinned) {
        Item it = items.get(keyOf(source, id));
        if (it == null || folderId == null || folderId.isEmpty()) return;
        if (pinned) {
            if (!it.pinned.contains(folderId)) it.pinned.add(folderId);
        } else {
            it.pinned.remove(folderId);
        }
        save();
    }

    /** 打开一次：累加次数、记最近打开时间。 */
    synchronized void touchOpen(String source, String id) {
        Item it = items.get(keyOf(source, id));
        if (it == null) return;
        it.opens++;
        it.lastOpenAt = System.currentTimeMillis();
        save();
        Log.i(TAG, "saved-open key=" + it.key + " opens=" + it.opens);
    }

    synchronized void setLastFolder(String folderId) {
        if (folderId == null || folderId.isEmpty()) return;
        lastFolder = folderId;
        save();
    }

    synchronized void clearAll() {
        items.clear();
        folders.clear();
        seq = 0;
        lastFolder = "";
        ensureDefault();
        save();
        Log.i(TAG, "saved-cleared");
    }

    // ---------- 封面文件 ----------

    synchronized File coverFile(String source, String id) {
        return coverFileByKey(keyOf(source, id));
    }

    private File coverFileByKey(String key) {
        if (coverDir == null) return null;
        String safe = key.replaceAll("[^A-Za-z0-9._-]", "_");
        return new File(coverDir, safe + ".jpg");
    }

    /** 写封面到私有目录；下载失败时保留原状，展示回退到网络 URL。 */
    void writeCover(String source, String id, byte[] data) {
        File f = coverFile(source, id);
        if (f == null || data == null || data.length == 0) return;
        try {
            if (!f.getParentFile().exists() && !f.getParentFile().mkdirs()) return;
            File tmp = new File(f.getAbsolutePath() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(data);
            }
            if (!tmp.renameTo(f)) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
                //noinspection ResultOfMethodCallIgnored
                tmp.renameTo(f);
            }
        } catch (Exception e) {
            Log.w(TAG, "saved-cover-write failed: " + e.getClass().getSimpleName());
        }
    }

    private void deleteCover(String key) {
        File f = coverFileByKey(key);
        if (f != null && f.isFile()) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    // ---------- 持久化 ----------

    private void ensureDefault() {
        for (Folder f : folders) if (!f.id.isEmpty()) return;
        Folder f = new Folder();
        f.id = "f" + (++seq);
        f.name = "收藏";
        f.createdAt = System.currentTimeMillis();
        folders.add(f);
        lastFolder = f.id;
    }

    private List<String> defaultFolderIds() {
        List<String> out = new ArrayList<>();
        if (!lastFolder.isEmpty() && folder(lastFolder) != null) out.add(lastFolder);
        else if (!folders.isEmpty()) out.add(folders.get(0).id);
        return out;
    }

    private void load() {
        loaded = true;
        if (file == null || !file.isFile()) return;
        try (InputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            JSONObject root = new JSONObject(out.toString(StandardCharsets.UTF_8.name()));
            seq = root.optLong("seq", 0);
            lastFolder = root.optString("lastFolder", "");
            JSONArray fa = root.optJSONArray("folders");
            if (fa != null) {
                for (int i = 0; i < fa.length(); i++) {
                    JSONObject o = fa.optJSONObject(i);
                    if (o == null) continue;
                    Folder f = new Folder();
                    f.id = o.optString("id", "");
                    f.name = o.optString("name", "收藏");
                    f.createdAt = o.optLong("createdAt", 0);
                    if (!f.id.isEmpty()) folders.add(f);
                }
            }
            JSONArray ia = root.optJSONArray("items");
            if (ia != null) {
                for (int i = 0; i < ia.length(); i++) {
                    JSONObject o = ia.optJSONObject(i);
                    if (o == null) continue;
                    Item it = new Item();
                    it.key = o.optString("key", "");
                    it.source = o.optString("source", SOURCE_BILI);
                    it.id = o.optString("id", "");
                    it.title = o.optString("title", "");
                    it.author = o.optString("author", "");
                    it.cover = o.optString("cover", "");
                    it.duration = o.optString("duration", "");
                    it.url = o.optString("url", "");
                    it.savedAt = o.optLong("savedAt", 0);
                    it.lastOpenAt = o.optLong("lastOpenAt", 0);
                    it.opens = o.optInt("opens", 0);
                    it.folders = readList(o.optJSONArray("folders"));
                    it.pinned = readList(o.optJSONArray("pinned"));
                    if (it.key.isEmpty()) it.key = keyOf(it.source, it.id);
                    if (!it.id.isEmpty()) items.put(it.key, it);
                }
            }
            Log.i(TAG, "saved-loaded items=" + items.size() + " folders=" + folders.size());
        } catch (Exception e) {
            Log.w(TAG, "saved-load failed: " + e.getClass().getSimpleName());
        }
    }

    private static List<String> readList(JSONArray arr) {
        List<String> out = new ArrayList<>();
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            String v = arr.optString(i, "");
            if (!v.isEmpty() && !out.contains(v)) out.add(v);
        }
        return out;
    }

    private void save() {
        if (file == null) return;
        try {
            JSONObject root = new JSONObject();
            root.put("seq", seq);
            root.put("lastFolder", lastFolder);
            JSONArray fa = new JSONArray();
            for (Folder f : folders) {
                fa.put(new JSONObject().put("id", f.id).put("name", f.name)
                        .put("createdAt", f.createdAt));
            }
            root.put("folders", fa);
            JSONArray ia = new JSONArray();
            for (Item it : items.values()) {
                ia.put(new JSONObject()
                        .put("key", it.key)
                        .put("source", it.source)
                        .put("id", it.id)
                        .put("title", it.title)
                        .put("author", it.author)
                        .put("cover", it.cover)
                        .put("duration", it.duration)
                        .put("url", it.url)
                        .put("savedAt", it.savedAt)
                        .put("lastOpenAt", it.lastOpenAt)
                        .put("opens", it.opens)
                        .put("folders", new JSONArray(it.folders))
                        .put("pinned", new JSONArray(it.pinned)));
            }
            root.put("items", ia);
            File tmp = new File(file.getAbsolutePath() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(root.toString().getBytes(StandardCharsets.UTF_8));
            }
            if (!tmp.renameTo(file)) {
                // 少数机型 rename 覆盖失败：删除后重试一次（旧文件已确认读入内存）。
                //noinspection ResultOfMethodCallIgnored
                file.delete();
                //noinspection ResultOfMethodCallIgnored
                tmp.renameTo(file);
            }
        } catch (Exception e) {
            Log.w(TAG, "saved-save failed: " + e.getClass().getSimpleName());
        }
    }
}
