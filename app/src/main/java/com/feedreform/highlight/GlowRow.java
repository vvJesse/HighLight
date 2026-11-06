package com.feedreform.highlight;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.util.SparseArray;
import android.view.View;
import android.widget.LinearLayout;

/**
 * 行级外发光：在 children 之前、行自己的 canvas 上画软光位图，卡片随后用不透明背景盖住内侧，
 * 只露出卡片外缘的光晕。这样不会被卡片自身的 clipToOutline 裁成「内描边」。
 *
 * 网格卡（GlowRow 水平、两个 child）和关注行（GlowRow 垂直、一个 child）共用；
 * 入口卡、native overlay 不走这里。每个 child index 最多缓存一张位图，尺寸/强度不变时复用。
 */
public class GlowRow extends LinearLayout {

    private static final int AMBER = 0xFFE8A33D;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF dst = new RectF();
    private final SparseArray<Boolean> glowOn = new SparseArray<>();
    private final SparseArray<Bitmap> cache = new SparseArray<>();
    private final SparseArray<String> cacheKeys = new SparseArray<>();
    private final float density;
    private int strength = 60;

    public GlowRow(Context context) {
        this(context, null);
    }

    public GlowRow(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        // 光晕画在行 padding 里/卡片外，不能被子 View 或 padding 裁掉。
        setClipChildren(false);
        setClipToPadding(false);
    }

    /** 由调用方在 bind 时告诉行：这个 child 是否要外发光。不强制 orientation。 */
    void setGlow(int childIndex, boolean on, int strength) {
        if (childIndex < 0) return;
        int s = Math.max(0, Math.min(100, strength));
        boolean was = Boolean.TRUE.equals(glowOn.get(childIndex));
        boolean changed = was != on || this.strength != s;
        glowOn.put(childIndex, on);
        this.strength = s;
        if (changed) invalidate();
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        if (strength > 0) {
            for (int i = 0; i < glowOn.size(); i++) {
                int index = glowOn.keyAt(i);
                if (Boolean.TRUE.equals(glowOn.valueAt(i))) drawGlow(canvas, index);
            }
        }
        super.dispatchDraw(canvas);
    }

    private void drawGlow(Canvas canvas, int index) {
        if (index < 0 || index >= getChildCount()) return;
        View card = getChildAt(index);
        if (card == null || card.getVisibility() != VISIBLE) return;
        int w = card.getWidth();
        int h = card.getHeight();
        if (w <= 0 || h <= 0) return;
        float v = strength / 100f;
        float margin = (12f + 12f * v) * density;
        String key = w + "x" + h + "/" + strength + "/" + Math.round(density * 100);
        Bitmap bitmap = cache.get(index);
        if (!key.equals(cacheKeys.get(index)) || bitmap == null || bitmap.isRecycled()) {
            if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
            bitmap = build(w, h, margin, strength);
            cache.put(index, bitmap);
            cacheKeys.put(index, key);
        }
        if (bitmap == null || bitmap.isRecycled()) return;
        dst.set(card.getLeft() - margin, card.getTop() - margin,
                card.getRight() + margin, card.getBottom() + margin);
        canvas.drawBitmap(bitmap, null, dst, paint);
    }

    /**
     * 初代软光公式：圆角矩形距离场 + peak*exp(-d²/2σ²) + 外缘 smoothstep。
     * 半分辨率生成再放大（光晕是软的），中心（卡片区域）透明，由卡片自己盖住。
     */
    private Bitmap build(int w, int h, float margin, int strength) {
        float v = strength / 100f;
        float peak = 0.24f + 0.24f * v;
        float sigma = (5f + 6f * v) * density;
        float fullW = w + margin * 2f;
        float fullH = h + margin * 2f;
        int scale = 2;
        int bw = Math.max(1, Math.round(fullW / scale));
        int bh = Math.max(1, Math.round(fullH / scale));
        if (bw > 1600 || bh > 1600) return null;
        float kx = bw / fullW;
        float ky = bh / fullH;
        float k = (kx + ky) / 2f;
        float marginB = margin * k;
        float sigmaB = Math.max(1f, sigma * k);
        float hw = w * k / 2f;
        float hh = h * k / 2f;
        float rr = Math.min(16f * density * k, Math.min(hw, hh));
        if (marginB <= 0 || hw <= 0 || hh <= 0) return null;
        float cutoff = 0.75f * marginB;
        float cx = bw / 2f;
        float cy = bh / 2f;
        try {
            Bitmap bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
            int[] px = new int[bw * bh];
            for (int y = 0; y < bh; y++) {
                float py = y + 0.5f;
                for (int x = 0; x < bw; x++) {
                    float dx = Math.abs(x + 0.5f - cx) - (hw - rr);
                    float dy = Math.abs(py - cy) - (hh - rr);
                    float ax = Math.max(dx, 0), ay = Math.max(dy, 0);
                    float dist = (float) Math.sqrt(ax * ax + ay * ay)
                            + Math.min(Math.max(dx, dy), 0) - rr;
                    if (dist < 0 || dist >= marginB) continue;
                    float a = peak * (float) Math.exp(-(dist * dist) / (2 * sigmaB * sigmaB));
                    if (dist > cutoff) {
                        float t = (dist - cutoff) / (marginB - cutoff);
                        a *= (1 - t * t * (3 - 2 * t));
                    }
                    int al = (int) (a * 255);
                    if (al <= 0) continue;
                    px[y * bw + x] = (al << 24) | (AMBER & 0x00FFFFFF);
                }
            }
            bmp.setPixels(px, 0, bw, 0, 0, bw, bh);
            return bmp;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
