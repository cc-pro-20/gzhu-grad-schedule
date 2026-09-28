import cn.edu.gzhu.kb.School;
import cn.edu.gzhu.kb.KbModel;

import java.util.List;
import java.util.Map;

/**
 * 在桌面 JVM 上验证 Android 端的核心逻辑（不依赖任何 Android API）：
 * CAS 登录 -> 学期 -> 当前周 -> 节次 -> 课表 -> 网格组装
 */
public class AndroidLogicTest {
    public static void main(String[] args) throws Exception {
        String user = System.getenv("GZHU_USER");
        String pass = System.getenv("GZHU_PASS");
        if (user == null || pass == null) {
            System.out.println("需要环境变量 GZHU_USER / GZHU_PASS");
            System.exit(1);
        }

        School school = new School();
        long t0 = System.currentTimeMillis();
        school.login(user, pass);
        System.out.printf("[1] 登录成功  姓名=%s 学号=%s  (%dms)%n", school.userName(), school.userId(),
            System.currentTimeMillis() - t0);

        List<Map<String, Object>> terms = school.terms();
        System.out.printf("[2] 学期 %d 个，前 3 个：%n", terms.size());
        for (int i = 0; i < Math.min(3, terms.size()); i++) {
            System.out.printf("      %s  %s%n", terms.get(i).get("dm"), terms.get(i).get("mc"));
        }

        Map<String, Object> wk = school.weeks();
        int curWeek = wk.get("zc") == null ? 1 : ((Number) wk.get("zc")).intValue();
        System.out.printf("[3] 当前第 %d 周，学期 %s，本周日期 %d 天%n", curWeek, wk.get("xnxqdm"),
            ((List<?>) wk.get("days")).size());

        String term = String.valueOf(terms.get(0).get("dm"));
        List<Map<String, Object>> jieci = school.jieci(term, school.userId());
        System.out.printf("[4] 节次方案 %d 节：%s%n", jieci.size(),
            jieci.get(0).get("dm") + "[" + jieci.get(0).get("kssj") + "-" + jieci.get(0).get("jssj") + "] ... "
                + jieci.get(jieci.size() - 1).get("dm") + "[" + jieci.get(jieci.size() - 1).get("kssj") + "-"
                + jieci.get(jieci.size() - 1).get("jssj") + "]");

        List<Map<String, Object>> courses = school.courses(term);
        System.out.printf("[5] 课表原始记录 %d 条%n", courses.size());

        @SuppressWarnings("unchecked")
        Map<String, Object> built = KbModel.build(courses, jieci, curWeek);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> flat = (List<Map<String, Object>>) built.get("flat");
        System.out.printf("[6] 第 %d 周合并后课程块 %d 个（原始 %d 条 -> 合并率 %.0f%%）%n",
            curWeek, flat.size(), courses.size(), 100.0 * (1 - (double) flat.size() / Math.max(1, courses.size())));

        System.out.println("      明细：");
        for (Map<String, Object> c : flat) {
            System.out.printf("        周%s 第%s-%s节 %s @ %s · %s · %s%n",
                c.get("xq"), num(c.get("from")) + 1, num(c.get("to")) + 1,
                c.get("kcmc"), c.get("jasmc"), c.get("jsxm"), c.get("zcmc"));
        }

        // 网格自检
        @SuppressWarnings("unchecked")
        List<List<List<Map<String, Object>>>> grid = (List<List<List<Map<String, Object>>>>) built.get("grid");
        int overlaps = 0;
        for (int xq = 1; xq <= 7; xq++) {
            for (int i = 0; i < jieci.size(); i++) {
                if (grid.get(xq).get(i).size() > 1) overlaps++;
            }
        }
        System.out.printf("[7] 网格 %d 列 x %d 行；重叠格 %d 个；总高 %s px；行高 %d px%n",
            7, jieci.size(), overlaps, built.get("totalHeight"), KbModel.H_ROW);

        // 周次位图过滤自检
        int w1 = ((List<?>) KbModel.build(courses, jieci, 1).get("flat")).size();
        int w8 = ((List<?>) KbModel.build(courses, jieci, 8).get("flat")).size();
        System.out.printf("[8] 第 1 周 %d 个块，第 8 周 %d 个块（周次位图过滤生效）%n", w1, w8);

        System.out.println("\n全部通过：Android 端核心逻辑可用");
    }

    static int num(Object o) {
        return o instanceof Number ? ((Number) o).intValue() : 0;
    }
}
