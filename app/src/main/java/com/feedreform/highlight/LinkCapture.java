package com.feedreform.highlight;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import java.util.List;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 剪贴板入口：回到亮点时读一次剪贴板，认出小红书 / B站 链接就弹预览层。
 *
 * - 同一链接只处理一次（记 URL 指纹），反复切前后台不会重复弹；
 * - 预览先出，解析在后台跑：解析成功补全标题/封面/作者，被登录墙拦住就如实标注「只拿到链接」；
 * - 解析失败不阻塞保存，用户可以先存「链接 + 剪贴板里的文字」，解析成功后再补全。
 */
final class LinkCapture {

    private static final String TAG = "HLBASE";
    private static final String PREFS = "link_capture";
    private static final String KEY_LAST = "last_url_hash";
    private static final Pattern XHS_ID = Pattern.compile(
            "(?:/discovery/item/|/explore/|/note/)([0-9a-fA-F]{16,32})");
    private static final Pattern BVID = Pattern.compile("BV[0-9A-Za-z]{10}");

    private static final ExecutorService NET = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final WeakHashMap<Activity, Boolean> PENDING = new WeakHashMap<>();

    private LinkCapture() {
    }

    /** 页面 onResume 调用；窗口还没拿到焦点时先记下，等 onWindowFocusChanged 再读。 */
    static void onResume(Activity activity) {
        if (activity == null) return;
        synchronized (PENDING) {
            PENDING.put(activity, Boolean.TRUE);
        }
        check(activity);
    }

    static void onPause(Activity activity) {
        if (activity == null) return;
        synchronized (PENDING) {
            PENDING.remove(activity);
        }
    }

    static void onWindowFocusChanged(Activity activity, boolean hasFocus) {
        if (activity == null || !hasFocus) return;
        boolean pending;
        synchronized (PENDING) {
            pending = Boolean.TRUE.equals(PENDING.get(activity));
        }
        if (pending) check(activity);
    }

    private static void check(Activity activity) {
        if (activity.isFinishing() || !activity.hasWindowFocus()) return;
        synchronized (PENDING) {
            if (!Boolean.TRUE.equals(PENDING.get(activity))) return;
            PENDING.put(activity, Boolean.FALSE);
        }

        String text = clipboardText(activity);
        if (text == null || text.trim().isEmpty()) return;
        String url = LinkClip.findSupported(text);
        if (url == null) return;

        SharedPreferences prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String hash = LinkClip.sha256(url);
        if (hash.equals(prefs.getString(KEY_LAST, ""))) return;
        // 先记指纹：保存/取消后同一链接不再弹。
        prefs.edit().putString(KEY_LAST, hash).apply();

        Log.i(TAG, "link-capture source=" + LinkClip.sourceOf(url)
                + " urlLen=" + url.length() + " textLen=" + text.length());
        preview(activity, url, text);
    }

    private static void preview(final Activity activity, final String url, final String text) {
        final String source = LinkClip.sourceOf(url);
        if (source.isEmpty()) return;

        final SavedStore.Item item = new SavedStore.Item();
        item.source = source;
        item.url = url;
        item.id = guessId(source, url);
        item.title = LinkClip.guessTitle(text, "");
        if (item.title.trim().isEmpty() || item.title.equals(url)) {
            item.title = LinkClip.markUnresolved(
                    SavedStore.SOURCE_XHS.equals(source) ? "小红书链接" : "B站链接");
        }
        item.cover = "";

        final boolean[] saved = {false};
        final SaveSheet.Sheet[] sheetRef = new SaveSheet.Sheet[1];
        SaveSheet.Sheet sheet = SaveSheet.show(activity, item, true, folderIds -> {
            SavedStore store = SavedStore.get(activity);
            store.save(item, folderIds);
            store.setLastFolder(folderIds.get(0));
            CoverCache.ensure(activity, item, null);
            saved[0] = true;
            Toast.makeText(activity, "已收藏", Toast.LENGTH_SHORT).show();
            Log.i(TAG, "link-saved source=" + source + " idLen=" + item.id.length()
                    + " folders=" + folderIds.size());
        });
        sheetRef[0] = sheet;
        NET.execute(() -> {
            final LinkResolver.Result result = LinkResolver.resolve(url);
            MAIN.post(() -> {
                SaveSheet.Sheet open = sheetRef[0];
                if (open != null) {
                    open.setPreview(result.title, result.author, result.cover, result.note);
                }
                SavedStore store = SavedStore.get(activity);
                boolean resolved = result.ok && !result.id.isEmpty();
                // 先补到内存里的 item：解析早于保存时，用户点「保存」拿到的就是补全后的字段。
                if (!result.title.isEmpty()) item.title = result.title;
                if (!result.cover.isEmpty()) item.cover = result.cover;
                if (!result.author.isEmpty()) item.author = result.author;
                if (!result.duration.isEmpty()) item.duration = result.duration;
                if (!result.url.isEmpty()) item.url = result.url;
                if (resolved && !result.id.equals(item.id)) {
                    if (saved[0]) {
                        store.migrate(source, item.id, result.id);
                    }
                    item.id = result.id;
                }
                if (saved[0]) {
                    store.enrich(item.source, item.id,
                            item.title, item.cover, item.author, item.url);
                    SavedStore.Item savedItem = store.find(item.source, item.id);
                    if (savedItem != null) CoverCache.ensure(activity, savedItem, null);
                }
                Log.i(TAG, "link-preview-updated ok=" + result.ok + " note=" + result.note);
            });
        });
    }

    /** 链接本身能直接看出的 id；看不出来先给一个稳定的临时 id，解析成功后迁移。 */
    private static String guessId(String source, String url) {
        String raw = url == null ? "" : url;
        if (SavedStore.SOURCE_BILI.equals(source)) {
            Matcher b = BVID.matcher(raw);
            if (b.find()) return b.group();
            return LinkClip.temporaryId(raw);
        }
        Matcher x = XHS_ID.matcher(raw);
        if (x.find()) return x.group(1);
        return LinkClip.temporaryId(raw);
    }

    private static String clipboardText(Activity activity) {
        try {
            ClipboardManager cm = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip()) return null;
            ClipData clip = cm.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) return null;
            CharSequence text = clip.getItemAt(0).coerceToText(activity);
            if (text == null) return null;
            String s = text.toString();
            return s.length() > 2000 ? s.substring(0, 2000) : s;
        } catch (Exception e) {
            return null;
        }
    }
}
