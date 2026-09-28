package cn.edu.gzhu.kb;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 小组件「整周课表缩略图」的排版计算。
 *
 * 特意做成不依赖任何 Android API 的纯计算类，
 * 这样可以在桌面 JVM 上直接单元测试（见 test/WidgetLayoutTest.java），
 * 把重叠、越界这类问题在构建阶段就拦住。
 */
public final class WidgetLayout {

    /** 画布尺寸与配色 */
    public static final class Spec {
        public int width = 500;
        public int height = 420;
        public int headerH = 30;   // 顶部"周一到周日"栏
        public int timeW = 44;     // 左侧节次栏
        public int radius = 4;     // 课程块圆角
        public int pad = 1;        // 课程块内缩，避免相邻块贴死

        public int bg = 0xFFFFFFFF;
        public int headerBg = 0xFF0F6E56;
        public int headerText = 0xFFFFFFFF;
        public int gridLine = 0xFFE9ECEF;
        public int timeText = 0xFF8A9099;
        public int todayBg = 0xFFF1FAF7;
        public int textColor = 0xFF1F2328;
    }

    /** 一个课程块的矩形（单位 px） */
    public static final class Rect {
        public final int x, y, w, h;
        public final String name, room;
        public final int bg, fg;

        Rect(int x, int y, int w, int h, String name, String room, int bg, int fg) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.name = name;
            this.room = room;
            this.bg = bg;
            this.fg = fg;
        }

        /** 两个矩形是否重叠（留 1px 容差，避免相邻块被误判） */
        public boolean overlaps(Rect o) {
            return x < o.x + o.w - 1 && o.x < x + w - 1 && y < o.y + o.h - 1 && o.y < y + h - 1;
        }

        public int right() { return x + w; }
        public int bottom() { return y + h; }
    }

    public final List<Rect> blocks = new ArrayList<>();
    public final int[] rowTops;      // 每节次行的顶部 y
    public final int[] rowHeights;   // 每节次行的高度
    public final int width, height, headerH, timeW;
    public final int todayColumn;    // 1..7，0 表示不显示今日高亮

    private WidgetLayout(List<Rect> blocks, int[] tops, int[] heights, int w, int h, int headerH, int timeW, int today) {
        this.blocks.addAll(blocks);
        this.rowTops = tops;
        this.rowHeights = heights;
        this.width = w;
        this.height = h;
        this.headerH = headerH;
        this.timeW = timeW;
        this.todayColumn = today;
    }

    /** 第 xq 列（1..7）的左边界 */
    public int columnLeft(int xq) {
        int bodyW = width - timeW;
        int colW = bodyW / 7;
        return timeW + (xq - 1) * colW;
    }

    /** 第 xq 列的宽度 */
    public int columnWidth() {
        return (width - timeW) / 7;
    }

    /**
     * 从课表数据（KbRepository 产出的结构）计算排版。
     *
     * @param data    含 flat / jieci / week / curWeek 的课表数据
     * @param width   画布宽
     * @param height  画布高
     * @param todayXq 今天星期几（1=周一 … 7=周日）；0 表示不高亮
     */
    @SuppressWarnings("unchecked")
    public static WidgetLayout compute(Map<String, Object> data, int width, int height, int todayXq) {
        Spec sp = new Spec();
        sp.width = width;
        sp.height = height;

        List<Object> jieciRaw = data == null ? null : Json.arr(data, "jieci");
        int rows = jieciRaw == null ? 0 : jieciRaw.size();
        if (rows <= 0) rows = 1;

        int bodyH = Math.max(1, height - sp.headerH);
        int[] heights = new int[rows];
        int[] tops = new int[rows];
        // 行高取整分配，余数补给最后一行，保证刚好铺满不留缝
        int base = bodyH / rows;
        int used = 0;
        for (int i = 0; i < rows; i++) {
            heights[i] = (i == rows - 1) ? (bodyH - used) : base;
            tops[i] = used;
            used += heights[i];
        }

        int colW = (width - sp.timeW) / 7;
        List<Rect> out = new ArrayList<>();
        List<Object> flat = data == null ? null : Json.arr(data, "flat");
        if (flat != null) {
            for (Object o : flat) {
                Map<String, Object> c = Json.obj(o);
                if (c == null) continue;
                int xq = num(c.get("xq"));
                int from = num(c.get("from"));
                int to = num(c.get("to"));
                if (xq < 1 || xq > 7) continue;
                if (from < 0 || from >= rows) continue;
                if (to < from) to = from;
                if (to >= rows) to = rows - 1;

                int x = sp.timeW + (xq - 1) * colW + sp.pad;
                int y = sp.headerH + tops[from] + sp.pad;
                int w = colW - sp.pad * 2;
                int h = (tops[to] + heights[to]) - tops[from] - sp.pad * 2;
                if (w <= 2 || h <= 2) continue;

                String[] col = colorOf(Json.str(c, "kcdm", Json.str(c, "kcmc")));
                out.add(new Rect(
                    x, y, w, h,
                    Json.str(c, "kcmc", ""),
                    Json.str(c, "jasmc", ""),
                    col[0].isEmpty() ? sp.bg : parseColor(col[0]),
                    parseColor(col[1])
                ));
            }
        }
        return new WidgetLayout(out, tops, heights, width, height, sp.headerH, sp.timeW, todayXq);
    }

    /** 与网页端一致的配色（浅底 + 深字） */
    private static final String[][] PALETTE = {
        {"#e8f0fe", "#1a56db"},
        {"#fde8e8", "#c81e1e"},
        {"#e6f4ea", "#046c4e"},
        {"#fef3c7", "#92400e"},
        {"#ede9fe", "#5b21b6"},
        {"#e0f2fe", "#075985"},
        {"#fce7f3", "#9d174d"},
        {"#ecfccb", "#3f6212"},
        {"#ffe4e6", "#9f1239"},
        {"#dbeafe", "#1e40af"}
    };

    static String[] colorOf(String key) {
        int h = 0;
        String s = key == null ? "" : key;
        for (int i = 0; i < s.length(); i++) h = (h * 31 + s.charAt(i)) & 0x7fffffff;
        return PALETTE[h % PALETTE.length];
    }

    /** #RRGGBB -> ARGB */
    static int parseColor(String hex) {
        try {
            String s = hex.startsWith("#") ? hex.substring(1) : hex;
            return 0xFF000000 | Integer.parseInt(s, 16);
        } catch (Exception e) {
            return 0xFF888888;
        }
    }

    /** 宽松取整（JSON 解析出来的数字可能是 Double） */
    static int num(Object o) {
        if (o instanceof Number) return ((Number) o).intValue();
        try {
            return (int) Double.parseDouble(String.valueOf(o));
        } catch (Exception e) {
            return 0;
        }
    }
}
