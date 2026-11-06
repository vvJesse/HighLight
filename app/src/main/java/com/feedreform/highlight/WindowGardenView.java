package com.feedreform.highlight;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;

/**
 * 首页/搜索/设置/收藏共用的一块小插画：奶油纸上的一扇窗边小景——
 * 日光、带把手的茶杯、叠放的书、一枚橘子。全部用基础形状现画，跟随 View 尺寸缩放。
 * 旧版是「蓝色窗格 + 盆栽橘树」，构图与参考稿几乎一样；这里换成不同的静物组合，调色板保持一致。
 */
public class WindowGardenView extends View {

    private static final int SAGE = 0xFFE9E8D5;
    private static final int CREAM = 0xFFF7F2E7;
    private static final int PAPER = 0xFFFFFCF4;
    private static final int INK = 0xFF173F36;
    private static final int BRAND = 0xFFDF5129;
    private static final int BLUE = 0xFF2C58AB;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();

    private String first = "让生活，";
    private String second = "再亮一点。";

    public WindowGardenView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    public void setCaption(String first, String second) {
        this.first = first;
        this.second = second;
        invalidate();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec);
        if (w <= 0) w = 320;
        setMeasuredDimension(w, Math.round(w * 126f / 320f));
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        float w = getWidth(), h = getHeight();
        float k = Math.min(w / 320f, h / 126f);
        c.save();
        c.translate((w - 320f * k) / 2f, (h - 126f * k) / 2f);
        c.scale(k, k);

        fill(SAGE);
        c.drawRoundRect(0, 0, 320, 126, 63, 63, p);

        // 窗光：右上一团奶油色晕圈 + 橘色日头
        fill(CREAM);
        c.drawCircle(260, 40, 22, p);
        fill(BRAND);
        c.drawCircle(260, 40, 11, p);

        // 墨绿窗台
        fill(INK);
        c.drawRoundRect(new RectF(162, 102, 278, 110), 4, 4, p);

        // 左边：细口瓶里的两片叶子
        fill(INK);
        path.reset();
        path.moveTo(175, 86);
        path.lineTo(185, 86);
        path.lineTo(182, 103);
        path.lineTo(178, 103);
        path.close();
        c.drawPath(path, p);

        stroke(INK, 1.8f);
        path.reset();
        path.moveTo(180, 86);
        path.lineTo(175, 74);
        path.lineTo(181, 62);
        c.drawPath(path, p);

        fill(INK);
        leaf(c, 178, 80, 168, 68, 165, 76, 173, 73);
        leaf(c, 181, 68, 191, 55, 194, 64, 187, 61);

        // 中间：带把手、冒热气的茶杯
        // 把手先画、左半边被杯身盖住；奶油色在奶油底上会隐形，改用墨绿描边
        stroke(INK, 2.5f);
        rect.set(206, 85, 222, 99);
        c.drawArc(rect, -90, 180, false, p);
        fill(BRAND);
        c.drawRoundRect(new RectF(184, 84, 214, 103), 4, 4, p);
        fill(CREAM);
        c.drawOval(new RectF(184, 80, 214, 88), p);
        stroke(CREAM, 1.8f);
        path.reset();
        path.moveTo(194, 78);
        path.quadTo(188, 71, 195, 63);
        c.drawPath(path, p);

        // 右边：两本叠起来的书 + 书上的一枚橘子
        fill(BLUE);
        c.drawRoundRect(new RectF(227, 97, 281, 105), 2, 2, p);
        fill(PAPER);
        c.drawRoundRect(new RectF(233, 89, 275, 97), 2, 2, p);
        stroke(INK, 1f);
        c.drawLine(233, 96, 275, 96, p);
        fill(BRAND);
        c.drawCircle(246, 84, 6.5f, p);
        fill(INK);
        leaf(c, 251, 80, 256, 75, 260, 76, 254, 82);

        // 文案：左边两行衬线
        p.setStyle(Paint.Style.FILL);
        p.setColor(INK);
        // 系统 serif 没有中文字形，中文会回退成无衬线；用工程内的宋体常规体做合成粗体
        p.setTypeface(Typeface.create(getResources().getFont(R.font.noto_serif_sc), Typeface.BOLD));
        p.setTextSize(22);
        c.drawText(first, 20, 51, p);
        c.drawText(second, 20, 82, p);

        c.restore();
    }

    private void fill(int color) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(color);
    }

    private void stroke(int color, float width) {
        p.setStyle(Paint.Style.STROKE);
        p.setColor(color);
        p.setStrokeWidth(width);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
    }

    /** 一片尖头叶子：叶柄、叶尖、外弧控制点、内弧控制点。 */
    private void leaf(Canvas c, float baseX, float baseY, float tipX, float tipY,
                      float bulgeX, float bulgeY, float backX, float backY) {
        path.reset();
        path.moveTo(baseX, baseY);
        path.quadTo(bulgeX, bulgeY, tipX, tipY);
        path.quadTo(backX, backY, baseX, baseY);
        path.close();
        c.drawPath(path, p);
    }
}
