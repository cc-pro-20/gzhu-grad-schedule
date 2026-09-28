import cn.edu.gzhu.kb.Json;
import cn.edu.gzhu.kb.KbModel;
import cn.edu.gzhu.kb.School;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 课表数据诊断：把某一天的原始记录逐条列出来，并对照合并后的结果。
 *
 * 起因：真机截图里"周一第 5-6 节都有课，但格子上只显示第 5 节"。
 * 要判断这到底是
 *   (a) 一条跨 5-6 节的课被正确合并成一个块（正常），
 *   (b) 第 6 节那条记录被合并逻辑吃掉了（bug），还是
 *   (c) 第 6 节单独有课但没渲染出来（bug），
 * 只能看真实数据，不能靠猜。
 *
 * 用法：
 *   java -cp <classes> DayProbe 1 5 6
 *       参数：星期 起始节 结束节（默认 1 5 6，即周一 5-6 节）
 *   需要环境变量 GZHU_USER / GZHU_PASS
 */
public class DayProbe {

    public static void main(String[] args) throws Exception {
        // 支持两种用法：
        //   DayProbe [星期 起节 止节]        诊断
        //   DayProbe --dump <文件>          导出真实数据
        boolean dumpMode = args.length >= 2 && "--dump".equals(args[0]);
        int n = dumpMode ? 0 : args.length;
        int wantXq = n > 0 ? Integer.parseInt(args[0]) : 1;
        int wantFrom = n > 1 ? Integer.parseInt(args[1]) : 5;
        int wantTo = n > 2 ? Integer.parseInt(args[2]) : 6;

        String user = System.getenv("GZHU_USER");
        String pass = System.getenv("GZHU_PASS");
        if (user == null || pass == null) {
            System.out.println("需要环境变量 GZHU_USER / GZHU_PASS");
            System.exit(1);
        }

        School school = new School();
        school.login(user, pass);

        List<Map<String, Object>> terms = school.terms();
        String term = String.valueOf(terms.get(0).get("dm"));
        Map<String, Object> wk = school.weeks();
        int curWeek = wk.get("zc") == null ? 1 : ((Number) wk.get("zc")).intValue();

        List<Map<String, Object>> jieci = school.jieci(term, school.userId());
        List<Map<String, Object>> courses = school.courses(term);

        // --dump <文件>：把真实数据按前端 /api/kb 的结构导出，
        // 供 preview.js 用真数据渲染并量像素（排查显示问题时用得上）。
        if (dumpMode) {
            String outFile = args[1];
            List<Map<String, Object>> days = castList(wk.get("days"));
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("ok", true);
            payload.put("xnxqdm", term);
            payload.put("termName", String.valueOf(terms.get(0).get("mc")));
            payload.put("week", curWeek);
            payload.put("curWeek", curWeek);
            payload.put("totalWeeks", 30);
            payload.put("days", days);
            payload.put("curDays", days);
            payload.put("jieci", jieci);
            Map<String, Object> built = KbModel.build(courses, jieci, curWeek);
            payload.putAll(built);
            java.nio.file.Files.write(java.nio.file.Paths.get(outFile),
                Json.write(payload).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            System.out.println("已导出真实课表数据 -> " + outFile);
            System.out.println("  周次=" + curWeek + "  节次=" + jieci.size()
                + "  课程块=" + castList(built.get("flat")).size());
            return;
        }

        System.out.printf("学期=%s  当前第 %d 周%n", term, curWeek);
        System.out.println("\n--- 节次方案（节次码 -> 行号 / 时间）---");
        for (int i = 0; i < jieci.size(); i++) {
            Map<String, Object> j = jieci.get(i);
            System.out.printf("  行%-3d dm=%-4s %s-%s%n", i, j.get("dm"), j.get("kssj"), j.get("jssj"));
        }

        // 原始记录：只看目标星期
        System.out.printf("%n--- 原始记录（星期 %d，全部周次）---%n", wantXq);
        int shown = 0;
        for (Map<String, Object> c : courses) {
            if (num(c.get("xq")) != wantXq) continue;
            shown++;
            System.out.printf("  ksjcdm=%-4s jsjcdm=%-4s 周次=%-32s %s @ %s%n",
                c.get("ksjcdm"), c.get("jsjcdm"),
                String.valueOf(c.get("zcmc")), c.get("kcmc"), c.get("jasmc"));
        }
        if (shown == 0) System.out.println("  （该星期没有任何记录）");

        // 目标区间里、当前周有效的记录
        System.out.printf("%n--- 星期 %d 第 %d-%d 节，本周（第 %d 周）有效记录 ---%n",
            wantXq, wantFrom, wantTo, curWeek);
        int hits = 0;
        for (Map<String, Object> c : courses) {
            if (num(c.get("xq")) != wantXq) continue;
            if (!KbModel.weekOn(str(c.get("zcbh")), curWeek)) continue;
            int ks = dig(c.get("ksjcdm")), js = dig(c.get("jsjcdm"));
            if (js < wantFrom || ks > wantTo) continue;
            hits++;
            System.out.printf("  节次 %s-%s（行 %d-%d）  %s @ %s  周次=%s%n",
                c.get("ksjcdm"), c.get("jsjcdm"), ks - 1, js - 1,
                c.get("kcmc"), c.get("jasmc"), c.get("zcmc"));
        }
        if (hits == 0) System.out.println("  （无）");

        // 合并结果
        Map<String, Object> built = KbModel.build(courses, jieci, curWeek);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> flat = (List<Map<String, Object>>) built.get("flat");

        System.out.printf("%n--- 合并后落在星期 %d 第 %d-%d 节的课程块 ---%n", wantXq, wantFrom, wantTo);        int blocks = 0;
        for (Map<String, Object> it : flat) {
            if (num(it.get("xq")) != wantXq) continue;
            int from = num(it.get("from")), to = num(it.get("to"));
            if (to < wantFrom || from > wantTo) continue;
            blocks++;
            System.out.printf("  from=%d to=%d span=%d  %s @ %s  %s-%s%n",
                from, to, num(it.get("span")),
                it.get("kcmc"), it.get("jasmc"), it.get("kssj"), it.get("jssj"));
        }
        if (blocks == 0) System.out.println("  （无）");

        // 判定：目标区间里每个节次是否都被某个块覆盖
        //
        // ⚠️ 行号 0-based、节次码 1-based —— 排查这个问题时差点在这里看错：
        //    节次 5 对应行 4，节次 6 对应行 5。两种编号都打印出来，避免再混。
        System.out.println("\n--- 逐节覆盖检查（这是「第 6 节没显示」的核心判据）---");
        for (int row = wantFrom; row <= wantTo; row++) {
            Map<String, Object> cover = null;
            for (Map<String, Object> it : flat) {
                if (num(it.get("xq")) != wantXq) continue;
                if (row >= num(it.get("from")) && row <= num(it.get("to"))) { cover = it; break; }
            }
            if (cover == null) {
                System.out.printf("  节次 %d（行 %d）: 没有被任何课程块覆盖%n", row + 1, row);
            } else {
                System.out.printf("  节次 %d（行 %d）: 落在块 节次%d-%d（行%d-%d）  %s%n",
                    row + 1, row,
                    num(cover.get("from")) + 1, num(cover.get("to")) + 1,
                    num(cover.get("from")), num(cover.get("to")),
                    cover.get("kcmc"));
            }
        }

        // 网格（KbModel 自己算的，前端应与之完全一致）
        @SuppressWarnings("unchecked")
        List<List<List<Map<String, Object>>>> grid =
            (List<List<List<Map<String, Object>>>>) built.get("grid");
        System.out.println("\n--- KbModel.grid 里这些格子装了什么 ---");
        for (int row = wantFrom; row <= wantTo; row++) {
            List<Map<String, Object>> cell = grid.get(wantXq).get(row);
            StringBuilder sb = new StringBuilder();
            for (Map<String, Object> e : cell) sb.append(e.get("kcmc")).append("[节次")
                .append(num(e.get("from")) + 1).append("-").append(num(e.get("to")) + 1).append("] ");
            System.out.printf("  节次 %d（行 %d）: %s%n", row + 1, row,
                sb.length() == 0 ? "（空）" : sb.toString());
        }

        System.out.println();
        if (hits > 0 && blocks == 0) {
            System.out.println("结论：原始记录有课，但合并后没有块 —— 合并逻辑有 bug");
            System.exit(2);
        }
        System.out.println("诊断完成（把上面输出发给我即可判断）");
    }

    static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> castList(Object o) {
        return o instanceof List ? (List<Map<String, Object>>) o : new java.util.ArrayList<Map<String, Object>>();
    }

    static int num(Object o) {
        return o instanceof Number ? ((Number) o).intValue() : -1;
    }

    static int dig(Object o) {
        try {
            return Integer.parseInt(String.valueOf(o).trim());
        } catch (Exception e) {
            return -1;
        }
    }
}
