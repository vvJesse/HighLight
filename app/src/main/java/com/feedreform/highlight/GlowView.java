package com.feedreform.highlight;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * 封面外圈的琥珀辉光。渲染方式与初代 GrayService 的软光一致：圆角矩形距离场 +
 * 高斯衰减 + 外缘 smoothstep，而不是几条不同 alpha 的硬描边。
 *
 * 列表里的 view 不能画出边界，所以这里生成一张与 view 等大小的软边位图（边缘向内衰减），
 * 叠在封面上；强度滑杆同时改变外扩/σ/峰值。位图按「尺寸 + 强度」缓存，滑杆不动时不重建。
 *
 * 只画在封面范围内，不改变卡片布局、不遮标题/作者/时长/收藏按钮；不可点击，点击照旧落到卡片上。
 */
public class GlowView extends View {

    private static final int AMBER = 0xFFE8A33D;

    private final Paint bmpPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint tintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF dst = new RectF();

    private Bitmap glowBitmap;
    private String cacheKey = "";
    private int strength = 60;
    private float density = 3f;
    private float radius;

    public GlowView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        tintPaint.setStyle(Paint.Style.FILL);
        tintPaint.setColor(AMBER);
        setClickable(false);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /** 0 = 不画；100 = 最宽最亮。 */
    void setStrength(int value) {
        int s = Math.max(0, Math.min(100, value));
        if (s == strength) return;
        strength = s;
        rebuild();
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        radius = Math.min(10 * density, Math.min(w, h) / 2f);
        rebuild();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0 || strength <= 0) return;
        float v = strength / 100f;
        // 覆盖层内的一层很淡暖色（初代 cover tint）。
        tintPaint.setAlpha((int) (255 * (0.04f + 0.12f * v)));
        canvas.drawRoundRect(0, 0, w, h, radius, radius, tintPaint);
        if (glowBitmap != null && !glowBitmap.isRecycled()) {
            dst.set(0, 0, w, h);
            canvas.drawBitmap(glowBitmap, null, dst, bmpPaint);
        }
    }

    /**
     * 生成「边缘向内高斯衰减」的软光位图。用 1/2 分辨率生成再放大（光晕本来就是软的），
     * 避免每张卡片在 bind 时做整张全分辨率像素循环。
     */
    private void rebuild() {
        int w = getWidth();
        int h = getHeight();
        String key = w + "x" + h + "/" + strength + "/" + Math.round(density * 100);
        if (key.equals(cacheKey) && glowBitmap != null) return;
        cacheKey = key;
        recycle();
        if (w <= 0 || h <= 0 || strength <= 0) return;

        final int scale = 2;
        int bw = Math.max(1, w / scale);
        int bh = Math.max(1, h / scale);
        float v = strength / 100f;
        float margin = Math.min((12f + 12f * v) * density,
                Math.min(w, h) * 0.35f) * (bw / (float) w);
        if (margin <= 0) return;
        float sigma = Math.max(1f, (5f + 6f * v) * density * (bw / (float) w));
        float peak = 0.24f + 0.24f * v;
        float cutoff = 0.75f * margin;
        float hw = bw / 2f;
        float hh = bh / 2f;
        float rr = Math.min(radius * (bw / (float) w), Math.min(hw, hh));
        try {
            Bitmap bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
            int[] px = new int[bw * bh];
            for (int y = 0; y < bh; y++) {
                float py = y + 0.5f;
                for (int x = 0; x < bw; x++) {
                    float dx = Math.abs(x + 0.5f - hw) - (hw - rr);
                    float dy = Math.abs(py - hh) - (hh - rr);
                    float ax = Math.max(dx, 0), ay = Math.max(dy, 0);
                    float sdf = (float) Math.sqrt(ax * ax + ay * ay)
                            + Math.min(Math.max(dx, dy), 0) - rr;
                    if (sdf > 0) continue;               // 圆角外
                    float d = -sdf;                      // 从边缘向内的距离
                    if (d >= margin) continue;
                    float a = peak * (float) Math.exp(-(d * d) / (2 * sigma * sigma));
                    if (d > cutoff) {
                        float t = (d - cutoff) / (margin - cutoff);
                        a *= (1 - t * t * (3 - 2 * t));
                    }
                    int al = (int) (a * 255);
                    if (al <= 0) continue;
                    px[y * bw + x] = (al << 24) | 0x00E8A33D;
                }
            }
            bmp.setPixels(px, 0, bw, 0, 0, bw, bh);
            glowBitmap = bmp;
        } catch (Throwable ignored) {
            glowBitmap = null;
        }
    }

    private void recycle() {
        if (glowBitmap != null && !glowBitmap.isRecycled()) glowBitmap.recycle();
        glowBitmap = null;
    }
}
