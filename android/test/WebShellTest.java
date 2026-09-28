import android.content.Context;
import android.webkit.WebResourceResponse;

import cn.edu.gzhu.kb.KbRepository;
import cn.edu.gzhu.kb.WebRouter;
import cn.edu.gzhu.kb.WebShell;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

/**
 * 请求拦截链路的验证：
 * 用测试桩件在桌面 JVM 上真正跑一遍 WebShell.intercept，
 * 确认页面资源能从 assets 取到、接口路由正确、前端与原生接口对得上。
 *
 * 这里替换掉的是 android.jar 里的 Log / Context / AssetManager / WebResourceResponse
 * （见 test/stubs），其余全部是 App 里的真实代码。
 */
public class WebShellTest {

    static int failures = 0;
    static final String PAGE = "http://appassets.androidplatform.net/www/index.html";

    public static void main(String[] args) throws Exception {
        File proj = new File("").getAbsoluteFile();
        File assetsWww = new File(proj, "app/assets/www");
        if (!assetsWww.isDirectory()) {
            System.out.println("找不到 " + assetsWww + "，请先执行一次 build-apk.js");
            System.exit(1);
        }
        File tmp = new File(proj, "build/shelltest-files");
        rmrf(tmp);
        tmp.mkdirs();

        Context ctx = new Context(tmp, new File(proj, "app/assets"));
        KbRepository repo = new KbRepository(null,
            new cn.edu.gzhu.kb.MemoryStores.Creds(),
            new cn.edu.gzhu.kb.MemoryStores.Cookies());
        WebShell shell = new WebShell(ctx, repo);

        System.out.println("=== 1) 页面与静态资源 ===");
        // 页面现在通过 http 虚拟源加载（不用 file://，否则子资源与 fetch 会被当跨域）
        checkAsset(shell, PAGE, "text/html", "index.html");
        checkAsset(shell, "http://appassets.androidplatform.net/www/style.css", "text/css", "style.css");
        checkAsset(shell, "http://appassets.androidplatform.net/www/app.js", "application/javascript", "app.js");
        checkAsset(shell, "http://appassets.androidplatform.net/", "text/html", "index.html");
        // 兼容旧的 file:// 形式（万一还在用它）
        checkAsset(shell, "file:///android_asset/www/style.css", "text/css", "style.css");

        System.out.println("\n=== 2) 接口路由 ===");
        // 未登录时 /api/state 应该报告未登录（而不是 500 或空响应）
        WebResourceResponse st = shell.intercept("http://appassets.androidplatform.net/api/state", "GET", null);
        check("GET /api/state 返回 200", st != null && st.getStatusCode() == 200);
        check("GET /api/state 是 JSON", st != null && "application/json".equals(st.getMimeType()));
        String stBody = st == null ? "" : st.bodyText();
        check("GET /api/state 内容含 loggedIn:false -> " + stBody, stBody.contains("\"loggedIn\":false"));

        // 未登录取课表应返回 401，页面据此回到登录界面
        WebResourceResponse kb = shell.intercept("http://appassets.androidplatform.net/api/kb?week=4", "GET", null);
        check("未登录 GET /api/kb 返回 401", kb != null && kb.getStatusCode() == 401);
        check("401 响应含错误说明", kb != null && kb.bodyText().contains("error"));

        // 登录：安卓版页面不走 POST（WebView 的 shouldInterceptRequest 拿不到
        // POST 请求体，WebResourceRequest 没有 getInputStream），必须给一句明确
        // 提示，而不是含糊的失败或 500。
        WebResourceResponse login = shell.intercept("http://appassets.androidplatform.net/api/login", "POST", null, null);
        check("POST /api/login 被明确拒绝", login != null && login.getStatusCode() == 400);
        String lb = login == null ? "" : login.bodyText();
        check("  是 JSON 错误而不是空响应 -> " + lb, lb.contains("\"ok\":false") && lb.contains("\"error\""));

        // 登录真正的入口：/api/login-start 发起 + /api/login-result 轮询
        WebResourceResponse ls0 = shell.intercept("http://appassets.androidplatform.net/api/login-start", "POST", null, null);
        check("POST /api/login-start（无 body）返回 400", ls0 != null && ls0.getStatusCode() == 400);
        // 注意 bodyText() 会读走流，只能读一次
        String ls0Body = ls0 == null ? "" : ls0.bodyText();
        check("  提示格式不正确 -> " + ls0Body, ls0Body.contains("格式不正确"));

        WebResourceResponse ls1 = shell.intercept("http://appassets.androidplatform.net/api/login-start", "POST",
                null, "{\"username\":\"2000000000\"}");
        check("POST /api/login-start（缺密码）返回 400", ls1 != null && ls1.getStatusCode() == 400);
        check("  提示请输入学号和密码", ls1 != null && ls1.bodyText().contains("请输入学号和密码"));

        // 轮询接口在未登录时必须可用，且初始状态是 idle（不能一上来就报成功）
        WebResourceResponse lr = shell.intercept("http://appassets.androidplatform.net/api/login-result", "GET", null, null);
        check("GET /api/login-result 返回 200", lr != null && lr.getStatusCode() == 200);
        String lrBody = lr == null ? "" : lr.bodyText();
        check("  初始 phase=idle -> " + lrBody, lrBody.contains("\"phase\":\"idle\""));
        check("  初始不是完成态 -> " + lrBody, lrBody.contains("\"running\":false") && lrBody.contains("\"ok\":false"));

        // /api/saved 是免登录的判断依据，未登录时也必须能用
        WebResourceResponse saved = shell.intercept("http://appassets.androidplatform.net/api/saved", "GET", null, null);
        check("GET /api/saved 返回 200", saved != null && saved.getStatusCode() == 200);
        String savedBody = saved == null ? "" : saved.bodyText();
        check("  /api/saved 含 hasPassword 字段 -> " + savedBody, savedBody.contains("\"hasPassword\":false"));

        // 未知接口也必须返回 JSON 错误（未登录时先被鉴权拦下返回 401，而不是空响应）
        WebResourceResponse unk = shell.intercept("http://appassets.androidplatform.net/api/nope", "GET", null);
        String unkBody = unk == null ? "" : unk.bodyText();
        check("未知接口返回 JSON", unk != null && "application/json".equals(unk.getMimeType()));
        check("未知接口不是 200", unk != null && unk.getStatusCode() != 200);
        check("未知接口有错误说明 -> " + unkBody, unkBody.contains("\"error\""));
        WebResourceResponse miss = shell.intercept("file:///android_asset/www/nope.js", "GET", null);
        check("不存在的静态资源 404", miss != null && miss.getStatusCode() == 404);

        System.out.println("\n=== 3) 路径穿越防护 ===");
        WebResourceResponse evil = shell.intercept("file:///android_asset/www/../../secret.js", "GET", null);
        check("含 .. 的路径被拒（404）", evil != null && evil.getStatusCode() == 404);
        WebResourceResponse evil2 = shell.intercept("file:///android_asset/www/..%2fsecret.js", "GET", null);
        check("编码穿越未读到文件", evil2 == null || evil2.getStatusCode() != 200);

        System.out.println("\n=== 4) 前端实际调用 与 原生支持 是否一致 ===");
        String appJs = new String(Files.readAllBytes(Paths.get(assetsWww.getPath(), "app.js")),
                StandardCharsets.UTF_8);
        List<String> used = WebRouter.extractApiPaths(appJs);
        List<String> supported = WebRouter.supportedApiPaths();
        System.out.println("  前端使用: " + used);
        System.out.println("  原生支持: " + supported);
        boolean allSupported = true;
        for (String u : used) {
            if (!supported.contains(u)) {
                allSupported = false;
                System.out.println("  !! 原生未处理: " + u);
            }
        }
        check("前端调用的每个接口原生都支持", allSupported);
        // 反向检查：原生支持的接口都应该真的能在前端调用点找到（login 例外，见下）
        for (String s : supported) {
            if (!used.contains(s)) System.out.println("  提示: 原生支持但前端未直接调用 -> " + s);
        }

        System.out.println("\n=== 5) 构建版本号已注入页面 ===");
        {
            String html = new String(Files.readAllBytes(Paths.get(assetsWww.getPath(), "index.html")),
                    StandardCharsets.UTF_8);
            check("index.html 不再含 %BUILD_VERSION% 占位符", !html.contains("%BUILD_VERSION%"));
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("<p class=\"build-id\">([^<]*)</p>").matcher(html);
            boolean found = m.find();
            // 版本号行放在两个界面之外（固定底部），登录与否都看得到
            check("页面带版本号行", found);
            if (found) {
                String text = m.group(1).trim();
                // 打包后形如 "构建 1.1.8（build8）"：必须带构建号，否则分不清是哪一次打包。
                // 测试自身会先把前端同步进 assets（见 run-shell-test.js），此时还没分配
                // 构建号，标签是"开发预览（未打包）"，这种状态也接受。
                boolean packaged = text.matches(".*build\\d+.*");
                boolean devPreview = text.contains("未打包");
                check("版本号含构建号或标明未打包 -> " + text, packaged || devPreview);
            }
        }

        System.out.println("\n=== 6) 首帧防闪与登录页清理 ===");
        {
            String html = new String(Files.readAllBytes(Paths.get(assetsWww.getPath(), "index.html")),
                    StandardCharsets.UTF_8);
            // 登录页与课表页默认都带 hidden，靠首次渲染前的 js 标记挡住中间那一帧，
            // 否则手机上会先看到"两套界面同框"（曾被误认为出现了两版登录窗口）
            check("head 里同步引入 boot.js", html.contains("src=\"/boot.js\""));
            check("登录页默认 hidden", html.contains("id=\"login\"") && html.contains("login-wrap hidden"));
            check("课表页默认 hidden", html.contains("<section id=\"app\" class=\"app hidden\">"));
            check("不再带 booting 类（改用 html.js 标记）", !html.contains("class=\"booting\""));

            String css = new String(Files.readAllBytes(Paths.get(assetsWww.getPath(), "style.css")),
                    StandardCharsets.UTF_8);
            check("css 有 html.js 的防闪规则", css.contains("html.js #login"));
            check("css 有已保存密码的提示样式", css.contains(".saved-hint"));

            String js = new String(Files.readAllBytes(Paths.get(assetsWww.getPath(), "app.js")),
                    StandardCharsets.UTF_8);
            check("页面通过桥发起登录", js.contains("AndroidBridge.startLogin"));
            check("登录结果靠轮询获取", js.contains("'/api/login-result'"));
            check("会查询本机是否已保存密码", js.contains("'/api/saved'"));
            check("网页版仍直接 POST /api/login", js.contains("'/api/login'"));
        }

        System.out.println("\n=== 7) 左栏显示上课/下课时间 ===");
        {
            String js = new String(Files.readAllBytes(Paths.get(assetsWww.getPath(), "app.js")),
                    StandardCharsets.UTF_8);
            // 左栏每格要有开始时间与结束时间；以前只有 kssj，答不出"几点下课"
            check("左栏用到 jssj（下课时间）", js.contains("j.jssj"));
            check("左栏同时用 kssj 与 jssj", js.contains("j.kssj") && js.contains("j.jssj"));
        }

        System.out.println("\n=== 8) 跨节次卡片必须填满它覆盖的节次 ===");
        {
            String css = new String(Files.readAllBytes(Paths.get(assetsWww.getPath(), "style.css")),
                    StandardCharsets.UTF_8);
            // 回归点：真机上出现过"第 5-6 节都有课，但只在第 5 节显示色块"。
            // 根因是 .slot 是 flex 容器、.blk 是 flex 子项，默认 flex-shrink:1
            // 会把卡片内联的 151px 高度压回单行 73px。必须显式关掉收缩。
            check(".blk 关掉了 flex 收缩（否则跨节次卡片会被压扁）",
                css.matches("(?s).*\\.blk\\s*\\{[^}]*flex:\\s*0\\s+0\\s+auto[^}]*\\}.*")
                    || css.matches("(?s).*\\.blk\\s*\\{[^}]*flex-shrink:\\s*0[^}]*\\}.*"));

            String js = new String(Files.readAllBytes(Paths.get(assetsWww.getPath(), "app.js")),
                    StandardCharsets.UTF_8);
            // 卡片高度要按覆盖的行累加，而不是固定单行高
            check("卡片高度按覆盖行累加", js.contains("rows[i].height"));
            check("卡片标出覆盖的节次（便于排障）",
                js.contains("dataset.from") && js.contains("dataset.to"));
        }

        System.out.println("\n=== 9) 表头星期必须与课程列对齐 ===");
        {
            String css = new String(Files.readAllBytes(Paths.get(assetsWww.getPath(), "style.css")),
                    StandardCharsets.UTF_8);

            // 回归点：真机上出现过"表头'一'对不上下面的课列"。
            // 根因是表头左角 .grid-head .g-time 没设宽度，被"第N周"三个字撑成 45px，
            // 而正文左栏 .grid-times 是 var(--time-w)=52px，于是表头整排左移，
            // 越靠左错得越多（周一差 15px，周日差 2px）。
            // 两处的宽度必须来自同一个变量。
            java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?s)\\.grid-head\\s*\\.g-time\\s*\\{([^}]*)\\}").matcher(css);
            boolean found = m.find();
            check("找到 .grid-head .g-time 规则", found);
            if (found) {
                String rule = m.group(1).replaceAll("\\s+", " ");
                check("  表头左角用了 --time-w（与正文左栏同宽） -> " + rule.trim(),
                    rule.contains("var(--time-w)"));
                check("  表头左角不会被内容撑宽（flex 不伸不缩）",
                    rule.contains("flex: 0 0") || rule.contains("flex-shrink: 0"));
            }

            // 正文左栏同样必须是 --time-w，两处一致才谈得上对齐
            check("正文左栏 .grid-times 也用 --time-w", css.contains(".grid-times { flex: 0 0 var(--time-w)"));
        }

        System.out.println("\n=== 10) 应用名与仓库地址 ===");
        {
            String html = new String(Files.readAllBytes(Paths.get(assetsWww.getPath(), "index.html")),
                    StandardCharsets.UTF_8);
            // 名字要统一：网页标题、登录页大字、安卓标签三处必须一致，
            // 以前出现过"图标叫一个名、进去又叫另一个名"。
            String APP = "gzhu课表";
            check("网页标题含 " + APP, html.contains("<title>" + APP));
            check("登录页大字是 " + APP, html.contains("<h1>" + APP + "</h1>"));

            String strings = new String(
                Files.readAllBytes(Paths.get(proj.getPath(), "app/res/values/strings.xml")),
                StandardCharsets.UTF_8);
            check("安卓 app_name 是 " + APP, strings.contains("<string name=\"app_name\">" + APP + "</string>"));
            check("小组件标题也是 " + APP,
                strings.contains("<string name=\"widget_title_default\">" + APP + "</string>"));

            // 底部必须给出仓库地址，方便装机后自己去更新
            String REPO = "https://github.com/cc-pro-20/gzhu-grad-schedule";
            check("页面含仓库地址链接", html.contains(REPO));
            check("仓库链接可点击（新窗口打开）",
                html.contains("class=\"repo-link\"") && html.contains("target=\"_blank\""));
            // 版本号行仍然要在（排障依据）
            check("底部仍有版本号行", html.contains("class=\"build-id\""));
            // 容器不能挡住课表点击，只有链接本身可点
            String css = new String(Files.readAllBytes(Paths.get(assetsWww.getPath(), "style.css")),
                    StandardCharsets.UTF_8);
            check("信息条容器不拦点击（pointer-events:none）",
                css.contains(".app-footer") && css.contains("pointer-events: none"));
            check("仓库链接本身可点（pointer-events:auto）", css.contains("pointer-events: auto"));

            // 信息条要随页面滚动、排在课表下方，不能固定吸在屏幕底部
            // （用户明确要求"划到底才看见"，固定定位会一直占着视线）
            java.util.regex.Matcher fm = java.util.regex.Pattern
                .compile("(?s)\\.app-footer\\s*\\{([^}]*)\\}").matcher(css);
            boolean footerRule = fm.find();
            check("找到 .app-footer 规则", footerRule);
            if (footerRule) {
                String rule = fm.group(1).replaceAll("\\s+", " ").trim();
                check("  信息条不是 position:fixed（否则会一直显示）",
                    !rule.contains("position: fixed") && !rule.contains("position:fixed"));
                check("  信息条不设 fixed 定位偏移（left/right/bottom）",
                    !rule.contains("position:"));
            }
            // 必须确实放在 #app 内部（课表下方），而不是两个界面之外
            int appIdx = html.indexOf("<section id=\"app\"");
            int footIdx = html.indexOf("class=\"app-footer\"");
            int appEnd = html.lastIndexOf("</section>");
            check("信息条位于 #app 内部（课表下方，随页面滚动）",
                appIdx >= 0 && footIdx > appIdx && footIdx < appEnd);
        }

        System.out.println();
        if (failures == 0) System.out.println("全部通过：请求拦截链路可用");
        else {
            System.out.println("有 " + failures + " 项失败");
            System.exit(1);
        }
    }

    static void checkAsset(WebShell shell, String url, String mime, String expectContainsInBody) {
        WebResourceResponse r = shell.intercept(url, "GET", null, null);
        check(url.replace("file:///android_asset/www", "") + " 返回 200", r != null && r.getStatusCode() == 200);
        check("  mime=" + (r == null ? "null" : r.getMimeType()), r != null && mime.equals(r.getMimeType()));
        if (r != null && expectContainsInBody != null) {
            String body = r.bodyText();
            boolean looks = body.length() > 20;
            check("  内容非空 (" + body.length() + " 字符)", looks);
            if ("index.html".equals(expectContainsInBody)) {
                check("  确实是课表页（含 login-form）", body.contains("login-form"));
            }
            if ("style.css".equals(expectContainsInBody)) {
                check("  确实是样式表（含 grid）", body.contains("grid"));
            }
            if ("app.js".equals(expectContainsInBody)) {
                check("  确实是脚本（含 doLogin）", body.contains("doLogin"));
            }
        }
    }

    static void rmrf(File f) {
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) rmrf(k);
        }
        f.delete();
    }

    static void check(String what, boolean ok) {
        if (!ok) failures++;
        System.out.println("  " + (ok ? "PASS  " : "FAIL  ") + what);
    }
}
