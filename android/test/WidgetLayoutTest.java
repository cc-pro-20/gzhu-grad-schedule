import cn.edu.gzhu.kb.Json;
import cn.edu.gzhu.kb.WidgetLayout;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 小组件排版的单元测试：不依赖 Android API，直接校验几何计算。
 * 重点检查——块不重叠、不越界、行高刚好铺满、跨节次高度正确。
 */
public class WidgetLayoutTest {

    static int failures = 0;

    public static void main(String[] args) {
        testBasicGeometry();
        testSpanningBlocks();
        testNoOverlap();
        testTinyWidget();
        testEmptyData();
        testManyRows();

        System.out.println();
        if (failures == 0) System.out.println("全部通过：小组件排版计算正确");
        else {
            System.out.println("有 " + failures + " 项失败");
            System.exit(1);
        }
    }

    // ------------------------------------------------------------ 用例

    /** 基本几何：列宽均分、行高铺满、时间栏宽度正确 */
    static void testBasicGeometry() {
        Map<String, Object> data = sample(11, 2);
        int W = 500, H = 420;
        WidgetLayout L = WidgetLayout.compute(data, W, H, 4);

        check("时间栏宽度", L.timeW == 44);
        check("表头高度", L.headerH == 30);

        int colW = L.columnWidth();
        check("列宽合理(>50px)", colW > 50);
        check("第1列起点=timeW", L.columnLeft(1) == L.timeW);
        check("最后列右边界不超过画布", L.columnLeft(7) + colW <= W);

        int sum = 0;
        for (int h : L.rowHeights) sum += h;
        check("行高总和铺满正文区 (" + sum + "==" + (H - L.headerH) + ")", sum == H - L.headerH);

        int lastBottom = L.headerH + L.rowTops[L.rowTops.length - 1] + L.rowHeights[L.rowHeights.length - 1];
        check("最后一行贴底 (" + lastBottom + "==" + H + ")", lastBottom == H);

        System.out.println("[1] 基本几何 OK  (" + W + "x" + H + ", 列宽 " + colW + ")");
    }

    /** 跨节次课程块的高度应等于所覆盖行的高度之和 */
    static void testSpanningBlocks() {
        // 第 1 周周一 第1-3节 一块
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("jieci", jieci(11));
        data.put("week", 4);
        data.put("curWeek", 4);
        data.put("flat", flat(new Object[][] {
            { 1, 0, 2, "跨三节", "B2-101", "K1" },
            { 3, 5, 5, "单节", "B2-102", "K2" },
        }));

        WidgetLayout L = WidgetLayout.compute(data, 500, 420, 4);
        check("块数量", L.blocks.size() == 2);

        WidgetLayout.Rect span = L.blocks.get(0);
        int expectH = L.rowHeights[0] + L.rowHeights[1] + L.rowHeights[2] - 2; // 减上下内缩
        check("跨3节高度=" + expectH + " 实际=" + span.h, Math.abs(span.h - expectH) <= 2);

        WidgetLayout.Rect single = L.blocks.get(1);
        int expectSingle = L.rowHeights[5] - 2;
        check("单节高度=" + expectSingle + " 实际=" + single.h, Math.abs(single.h - expectSingle) <= 2);

        System.out.println("[2] 跨节次高度 OK  (跨3节=" + span.h + "px, 单节=" + single.h + "px)");
    }

    /** 同一格多门课是合法情况（冲突），但不同格的块不应互相重叠 */
    static void testNoOverlap() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("jieci", jieci(11));
        data.put("week", 4);
        data.put("curWeek", 4);
        // 覆盖整周的密集课表
        Object[][] rows = new Object[70][];
        int n = 0;
        for (int xq = 1; xq <= 7; xq++) {
            for (int from = 0; from < 11; from += 2) {
                rows[n++] = new Object[] { xq, from, Math.min(10, from + 1), "课" + xq + "-" + from, "教室", "KC" + (from % 5) };
            }
        }
        Object[][] trim = new Object[n][];
        System.arraycopy(rows, 0, trim, 0, n);
        data.put("flat", flat(trim));

        WidgetLayout L = WidgetLayout.compute(data, 500, 420, 4);
        int overlaps = 0;
        for (int i = 0; i < L.blocks.size(); i++) {
            for (int j = i + 1; j < L.blocks.size(); j++) {
                if (L.blocks.get(i).overlaps(L.blocks.get(j))) overlaps++;
            }
        }
        check("无重叠 (块数=" + L.blocks.size() + ", 重叠=" + overlaps + ")", overlaps == 0);

        // 越界检查
        int oob = 0;
        for (WidgetLayout.Rect r : L.blocks) {
            if (r.x < L.timeW || r.right() > L.width || r.y < L.headerH || r.bottom() > L.height) oob++;
        }
        check("无越界 (越界=" + oob + ")", oob == 0);

        // 每列都应有块
        int[] perCol = new int[8];
        for (WidgetLayout.Rect r : L.blocks) perCol[(r.x - L.timeW) / L.columnWidth() + 1]++;
        boolean allCols = true;
        for (int i = 1; i <= 7; i++) if (perCol[i] == 0) allCols = false;
        check("7 列都有课", allCols);

        System.out.println("[3] 密集课表无重叠无越界 OK  (" + L.blocks.size() + " 个块)");
    }

    /** 极小尺寸（用户把小组件缩到最小）不能崩，也不能出现负尺寸 */
    static void testTinyWidget() {
        Map<String, Object> data = sample(11, 3);
        for (int[] wh : new int[][] { { 240, 160 }, { 120, 90 }, { 60, 40 } }) {
            WidgetLayout L = WidgetLayout.compute(data, wh[0], wh[1], 4);
            int bad = 0;
            for (WidgetLayout.Rect r : L.blocks) {
                if (r.w <= 0 || r.h <= 0 || r.right() > L.width + 1 || r.bottom() > L.height + 1) bad++;
            }
            check(wh[0] + "x" + wh[1] + " 无非法块 (坏=" + bad + ")", bad == 0);
        }
        System.out.println("[4] 极小尺寸安全 OK");
    }

    /** 空数据不应抛异常 */
    static void testEmptyData() {
        WidgetLayout a = WidgetLayout.compute(null, 500, 420, 0);
        check("null 数据块数为 0", a.blocks.isEmpty());
        check("null 数据仍有一行", a.rowHeights.length >= 1);

        Map<String, Object> empty = new LinkedHashMap<>();
        empty.put("jieci", jieci(0));
        empty.put("flat", flat(new Object[0][]));
        WidgetLayout b = WidgetLayout.compute(empty, 500, 420, 0);
        check("空课表块数为 0", b.blocks.isEmpty());
        System.out.println("[5] 空数据安全 OK");
    }

    /** 节次数很多（比如 13 节）时行高仍要铺满且为正 */
    static void testManyRows() {
        Map<String, Object> data = sample(13, 2);
        WidgetLayout L = WidgetLayout.compute(data, 500, 420, 4);
        boolean positive = true;
        for (int h : L.rowHeights) if (h <= 0) positive = false;
        check("13 节每行高度为正", positive);
        int sum = 0;
        for (int h : L.rowHeights) sum += h;
        check("13 节行高总和铺满", sum == 420 - 30);
        System.out.println("[6] 13 节排版 OK  (行高 " + L.rowHeights[0] + "px)");
    }

    // ------------------------------------------------------------ 构造数据

    static java.util.List<Object> jieci(int n) {
        java.util.List<Object> l = new java.util.ArrayList<>();
        for (int i = 1; i <= n; i++) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("dm", String.valueOf(i));
            m.put("kssj", String.format("%02d:00", 7 + i));
            l.add(m);
        }
        return l;
    }

    static java.util.List<Object> flat(Object[][] rows) {
        java.util.List<Object> l = new java.util.ArrayList<>();
        for (Object[] r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("xq", r[0]);
            m.put("from", r[1]);
            m.put("to", r[2]);
            m.put("span", (Integer) r[2] - (Integer) r[1] + 1);
            m.put("kcmc", r[3]);
            m.put("jasmc", r[4]);
            m.put("kcdm", r[5]);
            m.put("jsxm", "教师");
            m.put("zcmc", "1-16周");
            m.put("kssj", "08:30");
            m.put("jssj", "09:15");
            l.add(m);
        }
        return l;
    }

    /** 每周 7 天、每天若干节课的样本 */
    static Map<String, Object> sample(int rows, int perDay) {
        Object[][] r = new Object[7 * perDay][];
        int n = 0;
        for (int xq = 1; xq <= 7; xq++) {
            for (int k = 0; k < perDay; k++) {
                int from = Math.min(rows - 1, k * 3);
                r[n++] = new Object[] { xq, from, from, "课程" + xq + k, "★B1栋203", "KC" + k };
            }
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("jieci", jieci(rows));
        data.put("flat", flat(r));
        data.put("week", 4);
        data.put("curWeek", 4);
        return data;
    }

    static void check(String what, boolean ok) {
        if (!ok) failures++;
        System.out.println("  " + (ok ? "PASS  " : "FAIL  ") + what);
    }
}
