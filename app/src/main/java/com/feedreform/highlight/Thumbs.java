package com.feedreform.highlight;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Shader;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 封面/头像加载：先读尺寸再用 inSampleSize 解码、RGB_565、缓存上限取堆内存 1/8（pitfalls #23）。
 * B站 CDN 的 http→https 在 {@link BiliApi#https} 里已经转过，这里只兜底 // 开头的协议相对地址。
 * 不经任何自建服务器，所以不带任何 Authorization。
 */
final class Thumbs {

    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(
            (int) Math.min(Runtime.getRuntime().maxMemory() / 8, 64L * 1024 * 1024)) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };
    private static final ExecutorService POOL = Executors.newFixedThreadPool(3);
    private static final java.util.regex.Pattern BILI_SIZE =
            java.util.regex.Pattern.compile("@[0-9]+w(\\.[A-Za-z0-9]+)?$");

    private Thumbs() {
    }

    static void cover(ImageView view, String url, int targetW, int targetH) {
        load(view, largeCover(url), false, targetW, targetH);
    }

    /** 大卡片按实际宽度取 960w，否则 B站 320w 缩略图在 16:9 全宽下会糊。 */
    static String largeCover(String url) {
        if (url == null) return "";
        String clean = url.trim();
        if (clean.isEmpty() || !clean.contains("hdslb.com")) return clean;
        return BILI_SIZE.matcher(clean).replaceFirst("@960w.jpg");
    }

    static void avatar(ImageView view, String url, int targetPx) {
        load(view, url, true, targetPx, targetPx);
    }

    /**
     * 收藏页封面：本地私有目录有文件就优先读文件（离线也能看），没有文件再回退网络 URL。
     * 缓存键带文件修改时间，重新下载后不会命中旧图。
     */
    static void coverWithFile(ImageView view, java.io.File file, String url, int targetW, int targetH) {
        if (file == null || !file.isFile() || file.length() == 0) {
            cover(view, url, targetW, targetH);
            return;
        }
        final int tw = Math.max(1, targetW);
        final int th = Math.max(1, targetH);
        final String key = "f:" + tw + "x" + th + ":" + file.getAbsolutePath() + ":" + file.lastModified();
        view.setTag(key);
        Bitmap cached;
        synchronized (CACHE) {
            cached = CACHE.get(key);
        }
        if (cached != null) {
            view.setImageBitmap(cached);
            return;
        }
        view.setImageDrawable(null);
        POOL.execute(() -> {
            try {
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
                int sample = 1;
                while (bounds.outWidth / (sample * 2) >= tw
                        && bounds.outHeight / (sample * 2) >= th) {
                    sample *= 2;
                }
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inSampleSize = sample;
                opts.inPreferredConfig = Bitmap.Config.RGB_565;
                Bitmap bmp = BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
                if (bmp == null) return;
                synchronized (CACHE) {
                    CACHE.put(key, bmp);
                }
                final Bitmap ready = bmp;
                view.post(() -> {
                    if (key.equals(view.getTag())) view.setImageBitmap(ready);
                });
            } catch (Exception ignored) {
            }
        });
    }

    static void clear(ImageView view) {
        view.setTag(null);
        view.setImageDrawable(null);
    }

    private static void load(ImageView view, String url, boolean circle, int targetW, int targetH) {
        String clean = url == null ? "" : url.trim();
        view.setTag(clean);
        if (clean.isEmpty()) {
            view.setImageDrawable(null);
            return;
        }
        final int tw = Math.max(1, targetW);
        final int th = Math.max(1, targetH);
        final String key = (circle ? "a:" : "c:") + tw + "x" + th + ":" + clean;
        Bitmap cached;
        synchronized (CACHE) {
            cached = CACHE.get(key);
        }
        if (cached != null) {
            view.setImageBitmap(cached);
            return;
        }
        view.setImageDrawable(null);

        final String source = clean;
        POOL.execute(() -> {
            try {
                byte[] data = fetch(source);
                if (data.length == 0) return;

                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
                int sample = 1;
                while (bounds.outWidth / (sample * 2) >= tw
                        && bounds.outHeight / (sample * 2) >= th) {
                    sample *= 2;
                }

                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inSampleSize = sample;
                opts.inPreferredConfig = Bitmap.Config.RGB_565;
                Bitmap bmp = BitmapFactory.decodeByteArray(data, 0, data.length, opts);
                if (bmp == null) return;
                if (circle) bmp = round(bmp);

                synchronized (CACHE) {
                    CACHE.put(key, bmp);
                }
                final Bitmap ready = bmp;
                view.post(() -> {
                    if (source.equals(view.getTag())) view.setImageBitmap(ready);
                });
            } catch (Exception ignored) {
            }
        });
    }

    private static byte[] fetch(String url) throws Exception {
        String full = url.startsWith("//") ? "https:" + url : url;
        if (!full.startsWith("http")) return new byte[0];
        HttpURLConnection conn = (HttpURLConnection) new URL(full).openConnection();
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(20000);
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

    /** 居中裁成正方形再画成圆；头像 URL 不保证是 1:1。 */
    private static Bitmap round(Bitmap src) {
        int size = Math.min(src.getWidth(), src.getHeight());
        if (size <= 0) return src;
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        BitmapShader shader = new BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        Matrix matrix = new Matrix();
        matrix.setTranslate(-(src.getWidth() - size) / 2f, -(src.getHeight() - size) / 2f);
        shader.setLocalMatrix(matrix);
        paint.setShader(shader);
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint);
        return out;
    }
}
