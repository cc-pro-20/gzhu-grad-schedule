package cn.edu.gzhu.kb;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;

import java.util.List;
import java.util.Map;

/**
 * 把课表画成一张位图，交给桌面小组件显示。
 *
 * 为什么画图而不是堆控件：小组件只能用 RemoteViews，
 * 既塞不进 WebView，也不适合放几十个 TextView（跨进程开销大）。
 * 画成一张图后，小组件里只有一个 ImageView，又轻又稳。
 */
public final class WidgetRender {

    private WidgetRender() {}

    private static final String[] WEEK_CN = { "", "一", "二", "三", "四", "五", "六", "日" };

    public static Bitmap render(Map<String, Object> data, int width, int height, int todayXq) {
        WidgetLayout L = WidgetLayout.compute(data, width, height, todayXq);

        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTypeface(Typeface.DEFAULT);

        Bitmap bmp = Bitmap.createBitmap(L.width, L.height, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);

        // 背景
        c.drawColor(0xFFFFFFFF);

        // 顶部条：显示第几周 + 星期表头
        p.setColor(0xFF0F6E56);
        c.drawRect(0, 0, L.width, L.headerH, p);

        int colW = L.columnWidth();
        p.setColor(0xFFFFFFFF);
        p.setTextSize(Math.max(8f, L.headerH * 0.42f));
        p.setTextAlign(Paint.Align.CENTER);
        for (int xq = 1; xq <= 7; xq++) {
            float cx = L.columnLeft(xq) + colW / 2f;
            c.drawText(WEEK_CN[xq], cx, L.headerH * 0.68f, p);
        }

        // 左侧节次数字
        p.setColor(0xFF8A9099);
        p.setTextSize(Math.max(7f, Math.min(11f, L.rowHeights.length > 0 ? L.rowHeights[0] * 0.34f : 10f)));
        p.setTextAlign(Paint.Align.CENTER);
        for (int i = 0; i < L.rowHeights.length; i++) {
            float cy = L.headerH + L.rowTops[i] + L.rowHeights[i] / 2f + p.getTextSize() * 0.35f;
            c.drawText(String.valueOf(i + 1), L.timeW / 2f, cy, p);
        }

        // 今日列淡色底
        if (L.todayColumn >= 1 && L.todayColumn <= 7) {
            p.setColor(0xFFF1FAF7);
            c.drawRect(L.columnLeft(L.todayColumn), L.headerH,
                L.columnLeft(L.todayColumn) + colW, L.height, p);
        }

        // 网格线
        p.setColor(0xFFE9ECEF);
        p.setStrokeWidth(1f);
        for (int i = 0; i < L.rowHeights.length; i++) {
            float y = L.headerH + L.rowTops[i];
            c.drawLine(0, y, L.width, y, p);
        }
        for (int xq = 2; xq <= 7; xq++) {
            float x = L.columnLeft(xq);
            c.drawLine(x, L.headerH, x, L.height, p);
        }
        c.drawLine(L.timeW, L.headerH, L.timeW, L.height, p);

        // 课程块
        for (WidgetLayout.Rect r : L.blocks) {
            p.setColor(r.bg);
            c.drawRoundRect(new RectF(r.x, r.y, r.right(), r.bottom()), 4f, 4f, p);

            p.setColor(r.fg);
            p.setTextAlign(Paint.Align.CENTER);
            drawFitText(c, p, r);
        }
        return bmp;
    }

    /**
     * 课程名可能很长而小块只有十几像素高，
     * 这里按可用高度/宽度自动缩放字号，并做两行换行，超出的用省略号。
     */
    private static void drawFitText(Canvas c, Paint p, WidgetLayout.Rect r) {
        float padX = 2f;
        float availW = r.w - padX * 2;
        if (availW <= 2) return;

        boolean showRoom = r.h >= 26 && r.room != null && !r.room.isEmpty();
        float maxNameH = showRoom ? r.h * 0.62f : r.h;

        // 找能放下两行的最大字号
        float size = Math.min(11f, Math.max(6f, maxNameH / 2.1f));
        List<String> lines = null;
        for (; size >= 6f; size -= 0.5f) {
            p.setTextSize(size);
            lines = wrap(p, r.name, availW, 2);
            float need = lines.size() * (size + 1.5f);
            if (need <= maxNameH) break;
        }
        if (lines == null) return;
        p.setTextSize(size);

        float lineH = size + 1.5f;
        float totalH = lines.size() * lineH + (showRoom ? size * 0.9f : 0);
        float y = r.y + (r.h - totalH) / 2f + size;
        float cx = r.x + r.w / 2f;

        for (String line : lines) {
            c.drawText(line, cx, y, p);
            y += lineH;
        }
        if (showRoom) {
            p.setTextSize(Math.max(5.5f, size * 0.82f));
            p.setColor(withAlpha(r.fg, 200));
            c.drawText(ellipsize(p, r.room, availW), cx, y + size * 0.2f, p);
        }
    }

    /** 按宽度折行，最多 maxLines 行，最后一行加省略号 */
    private static List<String> wrap(Paint p, String text, float maxW, int maxLines) {
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        if (text == null || text.isEmpty()) return out;
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            String next = cur.toString() + ch;
            if (p.measureText(next) > maxW && cur.length() > 0) {
                if (out.size() == maxLines - 1) {
                    // 最后一行：把剩下的都塞进来并截断
                    String rest = text.substring(i);
                    out.add(ellipsize(p, cur + rest, maxW));
                    return out;
                }
                out.add(cur.toString());
                cur.setLength(0);
            }
            cur.append(ch);
        }
        if (cur.length() > 0 && out.size() < maxLines) out.add(cur.toString());
        return out;
    }

    private static String ellipsize(Paint p, String text, float maxW) {
        if (text == null) return "";
        if (p.measureText(text) <= maxW) return text;
        String ell = "…";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            if (p.measureText(sb.toString() + text.charAt(i) + ell) > maxW) break;
            sb.append(text.charAt(i));
        }
        return sb.length() == 0 ? ell : sb + ell;
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | ((alpha & 0xFF) << 24);
    }
}
