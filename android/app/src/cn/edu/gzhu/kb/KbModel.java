package cn.edu.gzhu.kb;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把上游"按节次逐条返回"的记录，合并成前端直接可渲染的课表网格。
 * 与 server/server.js 的 buildGrid() 保持同一套规则。
 */
public final class KbModel {

    private KbModel() {}

    /** 统一行高，跨节次课程块由前端按覆盖行累加高度 */
    public static final int H_ROW = 76;

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

    /** ZCBH 是 30 位周次位图，第 i 位为 '1' 表示第 i 周上课 */
    public static boolean weekOn(String zcbh, int week) {
        if (zcbh == null || zcbh.isEmpty()) return true;
        if (week < 1) return true;
        if (week > zcbh.length()) return true;
        return zcbh.charAt(week - 1) == '1';
    }

    static String[] colorOf(String key) {
        int h = 0;
        String s = key == null ? "" : key;
        for (int i = 0; i < s.length(); i++) h = (h * 31 + s.charAt(i)) & 0x7fffffff;
        return PALETTE[h % PALETTE.length];
    }

    /**
     * @param courses 原始课表记录
     * @param jieci   节次方案
     * @param week    要展示的周次
     * @return {flat, rows, totalHeight, grid}
     */
    public static Map<String, Object> build(List<Map<String, Object>> courses,
                                           List<Map<String, Object>> jieci,
                                           int week) {
        Map<String, Integer> cellIndex = new LinkedHashMap<>();
        for (int i = 0; i < jieci.size(); i++) cellIndex.put(String.valueOf(jieci.get(i).get("dm")), i);
        int n = jieci.size();

        // 1) 过滤本周有效记录并排序
        List<int[]> idx = new ArrayList<>();
        List<Map<String, Object>> kept = new ArrayList<>();
        for (Map<String, Object> c : courses) {
            if (!weekOn(Json.str(c, "zcbh"), week)) continue;
            Integer si = cellIndex.get(Json.str(c, "ksjcdm"));
            if (si == null) continue;
            int xq = School.num(c.get("xq"));
            if (xq < 1 || xq > 7) continue;
            Integer eiRaw = cellIndex.get(Json.str(c, "jsjcdm"));
            int ei = (eiRaw == null || eiRaw < si) ? si : eiRaw;
            kept.add(c);
            idx.add(new int[] { xq, si, ei });
        }
        // 按 星期/起始节次/结束节次 排序
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < kept.size(); i++) order.add(i);
        order.sort((a, b) -> {
            int[] x = idx.get(a), y = idx.get(b);
            if (x[0] != y[0]) return x[0] - y[0];
            if (x[1] != y[1]) return x[1] - y[1];
            return x[2] - y[2];
        });

        // 2) 合并"同天 + 同课程 + 节次首尾相接"的记录
        List<Map<String, Object>> flat = new ArrayList<>();
        for (int oi : order) {
            Map<String, Object> c = kept.get(oi);
            int[] p = idx.get(oi);
            int xq = p[0], si = p[1], ei = p[2];
            Map<String, Object> prev = flat.isEmpty() ? null : flat.get(flat.size() - 1);
            boolean same = prev != null
                && School.num(prev.get("xq")) == xq
                && eq(prev.get("kcdm"), Json.str(c, "kcdm"))
                && eq(prev.get("jasmc"), Json.str(c, "jasmc"))
                && School.num(prev.get("to")) == si - 1;
            if (same) {
                prev.put("to", ei);
                prev.put("span", ei - School.num(prev.get("from")) + 1);
                prev.put("jssj", Json.str(c, "jssj"));
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("kcmc", Json.str(c, "kcmc"));
            item.put("kcdm", Json.str(c, "kcdm"));
            item.put("jasmc", Json.str(c, "jasmc"));
            item.put("jsxm", Json.str(c, "jsxm"));
            item.put("zcmc", Json.str(c, "zcmc"));
            item.put("kclb", Json.str(c, "kclb"));
            item.put("bjmc", Json.str(c, "bjmc"));
            item.put("kssj", Json.str(c, "kssj"));
            item.put("jssj", Json.str(c, "jssj"));
            item.put("xq", xq);
            item.put("from", si);
            item.put("to", ei);
            item.put("span", ei - si + 1);
            String[] col = colorOf(Json.str(c, "kcdm", Json.str(c, "kcmc")));
            item.put("color", col);
            flat.add(item);
        }

        // 3) 行尺寸
        List<Object> rows = new ArrayList<>();
        int y = 0;
        for (int i = 0; i < n; i++) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("index", i);
            r.put("top", y);
            r.put("height", H_ROW);
            rows.add(r);
            y += H_ROW + 1; // +1 为行间分隔线
        }
        int totalHeight = n == 0 ? 0 : (n - 1) * (H_ROW + 1) + H_ROW;

        // 4) 网格（供自检与扩展）
        List<Object> grid = new ArrayList<>();
        for (int xq = 0; xq <= 7; xq++) {
            List<Object> col = new ArrayList<>();
            for (int i = 0; i < n; i++) col.add(new ArrayList<Object>());
            grid.add(col);
        }
        for (Map<String, Object> it : flat) {
            int xq = School.num(it.get("xq"));
            int from = School.num(it.get("from")), to = School.num(it.get("to"));
            if (xq < 1 || xq > 7) continue;
            @SuppressWarnings("unchecked")
            List<Object> col = (List<Object>) grid.get(xq);
            for (int k = from; k <= to && k < n; k++) {
                @SuppressWarnings("unchecked")
                List<Object> cell = (List<Object>) col.get(k);
                cell.add(it);
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("flat", flat);
        out.put("rows", rows);
        out.put("totalHeight", totalHeight);
        out.put("grid", grid);
        return out;
    }

    private static boolean eq(Object a, Object b) {
        String x = a == null ? "" : String.valueOf(a);
        String y = b == null ? "" : String.valueOf(b);
        return x.equals(y);
    }
}
