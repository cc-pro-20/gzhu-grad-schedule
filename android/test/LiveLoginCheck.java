import cn.edu.gzhu.kb.KbRepository;
import cn.edu.gzhu.kb.LoginState;
import cn.edu.gzhu.kb.MemoryStores;

import java.util.List;
import java.util.Map;

/**
 * 用真实账号跑一遍"异步登录 + 取课表"的完整链路（需要外网与学校系统可用）。
 *
 * 为什么要单独有这个：
 *   其余测试都靠桩件，证明不了"这套逻辑真能连上学校系统拿到课表"。
 *   这里用的是 App 里真实的 KbRepository / School / Http 代码，
 *   只是把存储换成内存实现（MemoryStores），所以验证的是同一套逻辑。
 *
 * 用法：
 *   set GZHU_USER=xxx & set GZHU_PASS=xxx & java -cp ... LiveLoginCheck
 */
public class LiveLoginCheck {

    public static void main(String[] args) throws Exception {
        String user = System.getenv("GZHU_USER");
        String pass = System.getenv("GZHU_PASS");
        if (user == null || pass == null) {
            System.out.println("需要环境变量 GZHU_USER / GZHU_PASS");
            System.exit(1);
        }

        int failures = 0;
        MemoryStores.Creds creds = new MemoryStores.Creds();
        MemoryStores.Cookies cookies = new MemoryStores.Cookies();
        KbRepository repo = new KbRepository(null, creds, cookies);
        LoginState st = repo.loginState();

        System.out.println("[1] 初始状态应当是 idle（页面一进登录页不能显示成功）");
        failures += check("phase=idle", st.phase() == LoginState.Phase.IDLE);
        failures += check("!isRunning", !st.isRunning());
        failures += check("toMap 里 ok=false", Boolean.FALSE.equals(st.toMap().get("ok")));

        System.out.println("\n[2] 发起异步登录（remember=true）");
        long t0 = System.currentTimeMillis();
        repo.startLogin(user, pass, true);
        // 注意：startLogin 只是"发起"，凭据的加密保存发生在它开的线程里，
        // 所以这里只能断言"已进入 running"，hasPassword 要等登录落定后再查。
        failures += check("立即变为 running（桥不阻塞，页面靠轮询）", st.isRunning());

        // 轮询，最多 90 秒
        long deadline = System.currentTimeMillis() + 90000;
        while (!st.isSettled() && System.currentTimeMillis() < deadline) {
            Thread.sleep(400);
        }
        long ms = System.currentTimeMillis() - t0;
        System.out.printf("    轮询到结果: phase=%s 用时 %dms%n", st.phase(), ms);
        failures += check("最终 phase=ok", st.phase() == LoginState.Phase.OK);
        if (st.phase() != LoginState.Phase.OK) {
            System.out.println("    失败原因: " + st.error());
            System.exit(1);
        }
        System.out.println("    显示名: " + st.name());
        failures += check("解得姓名非空", st.name() != null && !st.name().isEmpty());
        failures += check("toMap 里 ok=true", Boolean.TRUE.equals(st.toMap().get("ok")));
        failures += check("toMap 里 running=false", Boolean.FALSE.equals(st.toMap().get("running")));
        // 记住密码最终要落到凭据存储里，否则"下次免登录"就是空话
        failures += check("登录后凭据已保存（免登录的前提）", creds.hasPassword());

        System.out.println("\n[3] 会话可用：取学期");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> terms = repo.terms();
        failures += check("学期数 > 0", terms.size() > 0);
        System.out.println("    第一个学期: " + terms.get(0).get("dm") + " " + terms.get(0).get("mc"));

        System.out.println("\n[4] 取课表（跟随当前周）");
        Map<String, Object> kb = repo.kb(null, 0, false);
        int week = num(kb.get("week"));
        int total = num(kb.get("totalWeeks"));
        Object flat = kb.get("flat");
        int blocks = flat instanceof List ? ((List<?>) flat).size() : -1;
        int jieciCount = kb.get("jieci") instanceof List ? ((List<?>) kb.get("jieci")).size() : -1;
        System.out.printf("    第 %d 周 / 共 %d 周，节次 %d 个，课程块 %d 个%n", week, total, jieciCount, blocks);
        failures += check("week >= 1", week >= 1);
        failures += check("总数 30 周", total == 30);
        failures += check("节次 > 0", jieciCount > 0);
        failures += check("课程块 >= 0", blocks >= 0);

        System.out.println("\n[5] 左栏要用的上课/下课时间必须齐全（这次需求的核心）");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> jieci = (List<Map<String, Object>>) kb.get("jieci");
        int missing = 0;
        for (Map<String, Object> j : jieci) {
            Object ks = j.get("kssj"), js = j.get("jssj");
            if (ks == null || js == null || String.valueOf(ks).isEmpty() || String.valueOf(js).isEmpty()) {
                missing++;
                System.out.println("    缺时间: 第 " + j.get("dm") + " 节 " + ks + "-" + js);
            }
        }
        failures += check("每节课都有 kssj 与 jssj", missing == 0);
        System.out.println("    首节 " + jieci.get(0).get("dm") + " [" + jieci.get(0).get("kssj")
            + "-" + jieci.get(0).get("jssj") + "]  末节 " + jieci.get(jieci.size() - 1).get("dm")
            + " [" + jieci.get(jieci.size() - 1).get("kssj") + "-" + jieci.get(jieci.size() - 1).get("jssj") + "]");

        System.out.println("\n[6] 免登录：换一个仓库，用保存的凭据直接取数");
        MemoryStores.Creds c2 = new MemoryStores.Creds();
        c2.save(user, pass);
        KbRepository repo2 = new KbRepository(null, c2, new MemoryStores.Cookies());
        failures += check("新仓库未登录时 hasSession()=false", !repo2.hasSession());
        Map<String, Object> kb2 = repo2.kb(null, 0, false);
        failures += check("用保存的凭据能直接取到课表", kb2.get("flat") instanceof List);
        System.out.println("    取到课程块 " + ((List<?>) kb2.get("flat")).size() + " 个");

        System.out.println("\n[7] 退出登录后凭据应被清掉");
        repo.logout();
        failures += check("密码已清除", !creds.hasPassword());
        repo.loginState().reset();
        failures += check("状态回到 idle", repo.loginState().phase() == LoginState.Phase.IDLE);

        System.out.println();
        if (failures == 0) System.out.println("全部通过：真实账号的异步登录与取课表链路可用");
        else {
            System.out.println("有 " + failures + " 项失败");
            System.exit(1);
        }
    }

    static int num(Object o) {
        return o instanceof Number ? ((Number) o).intValue() : -1;
    }

    static int check(String what, boolean ok) {
        System.out.println("  " + (ok ? "PASS  " : "FAIL  ") + what);
        return ok ? 0 : 1;
    }
}
