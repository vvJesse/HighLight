package com.feedreform.highlight;

import android.content.Context;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 收藏封面落本机：首次展示/保存时把封面下载到 filesDir/covers/，之后离线也能看。
 * 同一进程里同一张封面只尝试一次；失败保留网络 URL 作为回退，不删已有文件。
 */
final class CoverCache {

    private static final String TAG = "HLBASE";
    private static final ExecutorService POOL = Executors.newFixedThreadPool(2);
    private static final Set<String> ATTEMPTED = Collections.synchronizedSet(new HashSet<String>());

    private CoverCache() {
    }

    interface Done {
        void onCoverReady();
    }

    /** 已有本地文件就不做任何事；否则后台下载，成功后回调（主线程）。 */
    static void ensure(Context context, SavedStore.Item item, Done done) {
        if (context == null || item == null) return;
        String key = SavedStore.keyOf(item.source, item.id);
        java.io.File file = SavedStore.get(context).coverFile(item.source, item.id);
        if (file != null && file.isFile() && file.length() > 0) return;
        if (item.cover == null || item.cover.isEmpty()) return;
        synchronized (ATTEMPTED) {
            if (ATTEMPTED.contains(key)) return;
            ATTEMPTED.add(key);
        }
        final Context app = context.getApplicationContext();
        final String url = item.cover;
        POOL.execute(() -> {
            try {
                byte[] data = fetch(url);
                if (data.length == 0) return;
                SavedStore.get(app).writeCover(item.source, item.id, data);
                if (done != null) {
                    new android.os.Handler(android.os.Looper.getMainLooper()).post(done::onCoverReady);
                }
            } catch (Exception e) {
                Log.w(TAG, "cover-cache failed: " + e.getClass().getSimpleName());
            }
        });
    }

    static byte[] fetch(String rawUrl) throws Exception {
        String url = rawUrl == null ? "" : rawUrl.trim();
        if (url.startsWith("//")) url = "https:" + url;
        if (!url.startsWith("http")) return new byte[0];
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(15000);
        conn.setRequestProperty("User-Agent", BiliApi.UA);
        conn.setRequestProperty("Referer", BiliApi.REFERER);
        try (InputStream in = conn.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        } finally {
            conn.disconnect();
        }
    }
}
