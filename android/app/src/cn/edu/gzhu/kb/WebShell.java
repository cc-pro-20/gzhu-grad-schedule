package cn.edu.gzhu.kb;

import android.content.Context;
import android.content.res.AssetManager;
import android.webkit.WebResourceResponse;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 网页外壳：用 WebViewClient.shouldInterceptRequest 直接接管页面里的所有请求。
 *
 * 为什么这样做（而不是在 App 里起一个本地 HTTP 服务）：
 *   课表页面里的 fetch('/api/...') 必须"同源"才能拿到数据，而学校接口没有开
 *   CORS 头。之前用 ServerSocket 起 127.0.0.1 服务来满足同源，但那样要占端口、
 *   要维护 socket 生命周期，还得额外处理线程与超时。
 *   改成请求拦截后：
 *     - 不需要端口，不依赖 socket
 *     - shouldInterceptRequest 本身就在后台线程回调，联网可以直接做
 *     - 页面与接口仍然对 WebView 表现为同一个源，CORS 彻底不参与
 *
 * 页面里的相对地址请求（/style.css、/app.js）从 assets/www 读取；
 * /api/* 交给原生的 KbRepository 处理。
 */
public final class WebShell {

    private static final String TAG = "WebShell";
    private static final String ASSET_ROOT = "www/";

    private final Context ctx;
    private final KbRepository repo;

    public WebShell(Context ctx, KbRepository repo) {
        this.ctx = ctx.getApplicationContext();
        this.repo = repo;
    }

    /**
     * 处理一次拦截。
     *
     * @return 要返回给 WebView 的响应；返回 null 表示"不拦截，交给系统正常加载"。
     */
    public WebResourceResponse intercept(String url, String method, InputStream requestBody) {
        return intercept(url, method, requestBody, null);
    }

    /**
     * 处理一次拦截（带请求体）。
     *
     * @param requestBody POST 请求体。由调用方负责读出来传进来：
     *        WebResourceRequest.getInputStream() 只有在 shouldInterceptRequest 里、
     *        且方法同步执行时才能读到内容，所以读取必须在调用方完成。
     */
    public WebResourceResponse intercept(String url, String method,
                                         InputStream requestBody, String bodyText) {
        try {
            WebRouter.Route route = WebRouter.route(url, method);
            String hint = bodyText == null || bodyText.isEmpty() ? "" : " body=" + bodyText.length() + "B";
            Diag.d("拦截 " + method + " " + url + "  => " + route.kind + " " + route.path + hint);
            switch (route.kind) {
                case API:
                    return handleApi(route.path, route.method, route.query, bodyText);
                case ASSET:
                    return serveAsset(route.path);
                default:
                    return text(404, "text/plain", "404 " + route.path);
            }
        } catch (Exception e) {
            Diag.e("拦截处理异常 " + url, e);
            return null;
        }
    }

    // ------------------------------------------------------------ 静态资源

    private WebResourceResponse serveAsset(String path) {
        String name = path.startsWith("/") ? path.substring(1) : path;
        if (name.contains("..")) return text(404, "text/plain", "404");
        // aapt2 打包时条目名可能带反斜杠；AssetManager 只认正斜杠
        name = name.replace('\\', '/');
        String mime = mimeOf(name);
        try {
            AssetManager am = ctx.getAssets();
            InputStream is = am.open(ASSET_ROOT + name);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            is.close();
            WebResourceResponse r = new WebResourceResponse(mime, "UTF-8",
                new ByteArrayInputStream(bos.toByteArray()));
            Map<String, String> h = new LinkedHashMap<>();
            h.put("Cache-Control", "no-store");
            h.put("Access-Control-Allow-Origin", "*");
            r.setResponseHeaders(h);
            r.setStatusCodeAndReasonPhrase(200, "OK");
            return r;
        } catch (IOException e) {
            // 失败时把"试过的路径"和 assets 里真实有什么一起报出来，
            // 这样下次出问题不用靠猜（之前排查 404 就吃过这个亏）
            String tried = ASSET_ROOT + name;
            String available = listAssets();
            Diag.e("读取 assets 失败: " + tried + " (" + e.getMessage() + ")  可用: " + available);
            return text(404, "text/plain", "404 Not Found: " + name
                + "\ntried: " + tried + "\navailable: " + available);
        }
    }

    /** 列出 assets 根与 www/ 下的实际条目，用于诊断 */
    private String listAssets() {
        try {
            AssetManager am = ctx.getAssets();
            String root = join(am.list(""));
            String www = join(am.list("www"));
            return "root=[" + root + "] www=[" + www + "]";
        } catch (Exception e) {
            return "(列举失败: " + e.getMessage() + ")";
        }
    }

    private static String join(String[] arr) {
        if (arr == null || arr.length == 0) return "";
        StringBuilder sb = new StringBuilder();
        for (String s : arr) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(s);
        }
        return sb.toString();
    }

    private static String mimeOf(String name) {
        if (name.endsWith(".html")) return "text/html";
        if (name.endsWith(".css")) return "text/css";
        if (name.endsWith(".js")) return "application/javascript";
        if (name.endsWith(".json")) return "application/json";
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".svg")) return "image/svg+xml";
        return "application/octet-stream";
    }

    // ------------------------------------------------------------ 接口

    private WebResourceResponse handleApi(String path, String method, Map<String, String> qs, String bodyText) {
        try {
            // ---- 免登录前就能用的接口 ----

            if ("/api/login".equals(path)) {
                // 说明：网页版由服务端在这条路径上完成 CAS 认证；安卓版页面不走这里，
                // 而是通过 AndroidBridge.startLogin 发起（原因见 LoginState 的注释：
                // WebView 的请求拦截拿不到 POST 请求体）。这里明确回一句，
                // 免得页面万一走到这条分支时拿到一个含糊的失败。
                return error(400, "安卓版请通过 App 内登录（页面表单提交后由原生完成认证）");
            }

            if ("/api/login-start".equals(path)) {
                // 页面把凭据交到这里（安卓版由桥转发）。凭据不写日志。
                Map<String, Object> in = Json.parseObject(bodyText);
                if (in == null || in.isEmpty()) return error(400, "登录请求格式不正确（没读到账号密码）");
                String user = in.get("username") == null ? "" : String.valueOf(in.get("username")).trim();
                String pass = in.get("password") == null ? "" : String.valueOf(in.get("password"));
                if (user.isEmpty() || pass.isEmpty()) return error(400, "请输入学号和密码");
                boolean remember = Json.bool(in, "remember", false);
                repo.startLogin(user, pass, remember);
                Diag.d("已发起异步登录: " + user + (remember ? "（记住密码）" : "（不保存密码）"));
                return json(200, repo.loginState().toMap());
            }

            if ("/api/login-result".equals(path)) {
                // 页面轮询这里取登录进度；不参与鉴权，未登录时也要能用
                return json(200, repo.loginState().toMap());
            }

            if ("/api/saved".equals(path)) {
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("username", repo.userName());
                out.put("hasPassword", repo.hasCredentials());
                out.put("platform", "android");
                return json(200, out);
            }

            if ("/api/logout".equals(path)) {
                repo.logout();
                repo.loginState().reset();
                return json(200, new LinkedHashMap<String, Object>());
            }
            if ("/api/state".equals(path)) {
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("loggedIn", repo.hasSession());
                out.put("name", repo.userName());
                return json(200, out);
            }

            // 以下都需要登录
            if (!repo.hasSession()) {
                return error(401, "未登录");
            }
            if ("/api/terms".equals(path)) {
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("terms", repo.terms());
                return json(200, out);
            }
            if ("/api/week".equals(path)) {
                return json(200, repo.weeks());
            }
            if ("/api/kb".equals(path)) {
                String term = qs.get("term");
                int week = 0;
                try {
                    week = Integer.parseInt(qs.getOrDefault("week", "0"));
                } catch (Exception ignored) {
                }
                Map<String, Object> out = repo.kb(term, week, "1".equals(qs.get("raw")));
                // 顺手缓存，供桌面小组件复用
                repo.saveCache(out);
                return json(200, out);
            }
            return error(404, "未知接口");
        } catch (School.Fail e) {
            return error(e.authFailed ? 401 : 400, e.getMessage());
        } catch (Exception e) {
            return error(400, e.getMessage() == null ? "请求失败" : e.getMessage());
        }
    }

    private static WebResourceResponse json(int code, Map<String, Object> body) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", code == 200);
        if (code == 200) out.putAll(body);
        else out.put("error", body.get("error"));
        return text(code, "application/json", Json.write(out));
    }

    private static WebResourceResponse error(int code, String msg) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", false);
        out.put("error", msg);
        return text(code, "application/json", Json.write(out));
    }

    private static WebResourceResponse text(int code, String mime, String body) {
        WebResourceResponse r = new WebResourceResponse(mime, "UTF-8",
            new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Cache-Control", "no-store");
        h.put("Access-Control-Allow-Origin", "*");
        r.setResponseHeaders(h);
        if (code != 200) r.setStatusCodeAndReasonPhrase(code, code == 401 ? "Unauthorized" : "Bad Request");
        return r;
    }
}
