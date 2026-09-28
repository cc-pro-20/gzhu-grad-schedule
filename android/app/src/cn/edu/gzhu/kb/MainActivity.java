package cn.edu.gzhu.kb;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 单 Activity 应用。
 *
 * 关键设计：用 WebViewClient.shouldInterceptRequest 接管页面发出的所有请求——
 *   - /style.css、/app.js 等静态资源从 assets/www 读取
 *   - /api/* 交给原生 KbRepository（内部用 Http/School 直接请求学校系统）
 * 页面与接口对 WebView 表现为同一个源，因此彻底绕开 CORS
 * （学校接口没有开放 CORS 头，直接从前端调是拿不到数据的）。
 *
 * 登录只有一套界面：课表页面自己的表单。
 * 早先的做法是页面表单再叠一层原生登录界面，结果是同一个操作出现两版登录窗口
 * （截图确认过）。既然 shouldInterceptRequest 能通过 WebResourceRequest.getInputStream()
 * 读到 POST body，就完全不需要两套界面了：页面把账号密码 POST 到 /api/login，
 * 拦截层读出来交给仓库认证即可。
 *
 * App 启动时如果本机存过密码，会先在后台静默登录，页面一加载就是已登录状态，
 * 也就是"免登录"。
 */
public class MainActivity extends Activity {

    private static final int GREEN = 0xFF0F6E56;
    private static final int BG = 0xFFF5F6F8;

    /** POST body 读多少字节封顶。登录请求只有几百字节，1MB 已远远够用 */
    private static final int MAX_BODY_BYTES = 1024 * 1024;

    /**
     * 装载页面用的虚拟源。
     *
     * 为什么不用 file:///android_asset/...：现代 WebView 把 file:// 当作不透明来源，
     * 该来源下的子资源（style.css / app.js）与 fetch('/api/...') 会被当跨域拒绝，
     * 表现为"页面没样式、登录请求也发不出去"——这两个问题在实际设备上都出现过。
     *
     * 改用一个 HTTP 虚拟源后，页面就是一个正常的 Web 来源：同源子资源与 fetch 都正常，
     * 而所有请求仍然会被 shouldInterceptRequest 拦下来由本地处理（不会真的联网）。
     * 用 http 而不是 https：虚拟域名没有真实证书，走 http 可以完全避开证书校验分支；
     * 这里也不需要 secure-context 相关的 API（ServiceWorker 等）。
     * 思路与 AndroidX 的 WebViewAssetLoader 一致，只是不引入依赖、自行实现。
     */
    public static final String VIRTUAL_HOST = "appassets.androidplatform.net";
    public static final String PAGE_URL = "http://" + VIRTUAL_HOST + "/www/index.html";

    private FrameLayout root;
    private WebView web;
    private ProgressBar bar;

    private WebShell shell;
    private EncryptedPrefs cred;
    private KbRepository repo;
    private volatile String displayName = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        cred = new EncryptedPrefs(this);
        // 装配仓库：同时把 cookie 落盘位置注入 Http.Jar，
        // 之后（含后台刷新的小组件进程）会话都会自动持久化
        repo = AndroidRepo.create(this);
        shell = new WebShell(this, repo);
        buildUi();

        // 打开 App 时顺手刷新桌面小组件并重排定时刷新
        WidgetScheduler.schedule(this);
        KbWidgetProvider.updateAll(this);

        if (savedInstanceState == null) {
            // 有保存的会话/密码就先恢复登录态，再装载页面：
            // 页面启动时会查 /api/state，此时必须已经是"已登录"，否则会闪一下登录页
            autoLoginThenLoad();
        } else {
            web.restoreState(savedInstanceState);
            bar.setVisibility(View.GONE);
        }
    }

    /**
     * 启动时的免登录：
     *   1. 有 cookie 会话 —— 直接用，页面立即进课表
     *   2. 存过密码 —— 静默登录（不打扰用户），成功后页面也是已登录状态
     *   3. 都没有 / 登录失败 —— 照常装载页面，由页面显示登录表单
     *
     * 无论哪条分支页面都会装载，所以不会出现"卡在空白页"的情况。
     */
    private void autoLoginThenLoad() {
        final String u = cred.username();
        final boolean canAuto = !u.isEmpty() && cred.hasPassword();
        if (!repo.hasSession() && !canAuto) {
            Diag.d("没有可用会话与凭据，直接显示登录页");
            loadPage();
            return;
        }
        bar.setVisibility(View.VISIBLE);
        new Thread(() -> {
            String note = null;
            try {
                if (!repo.hasSession()) {
                    long t0 = System.currentTimeMillis();
                    repo.login(u, cred.password());
                    note = "免登录成功（" + (System.currentTimeMillis() - t0) + "ms）";
                } else {
                    // 已有会话也要确认它还有效，否则页面加载后接口会 401，
                    // 用户看到的是"登录页突然跳出来"，不如现在就弄清楚
                    try {
                        repo.terms();
                        note = "复用已保存会话";
                    } catch (Exception e) {
                        repo.login(u, cred.password());
                        note = "旧会话失效，已用保存的密码重登";
                    }
                }
                displayName = repo.userName();
            } catch (Exception e) {
                note = "免登录失败，改为手动登录: " + msg(e);
            }
            final String n = note;
            runOnUiThread(() -> {
                Diag.d(n == null ? "自动登录结束" : n);
                loadPage();
            });
        }, "kb-auto-login").start();
    }

    private void loadPage() {
        if (web == null) return;
        web.loadUrl(PAGE_URL);
    }

    private void hideBar() {
        runOnUiThread(() -> {
            if (bar != null) bar.setVisibility(View.GONE);
        });
    }

    /**
     * 把外部链接交给系统浏览器打开。
     *
     * 判断依据是"不是我们的虚拟源"：页面内部所有请求（页面本身、/api/*）
     * 都用 VIRTUAL_HOST，凡是不属于它的 http(s) 地址一律视为外部链接。
     *
     * @return true 表示已接管（WebView 不再自己导航）
     */
    private boolean openExternally(String url) {
        if (url == null || url.isEmpty()) return false;
        if (!url.startsWith("http://") && !url.startsWith("https://")) return false;
        if (url.contains(VIRTUAL_HOST)) return false;
        try {
            Diag.d("外部链接交给浏览器: " + url);
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            Diag.e("打不开外部链接 " + url, e);
            toast("没有可用的浏览器");
        }
        return true;
    }

    // ---------------------------------------------------------------- UI 构建

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(BG);

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        // 兜底：万一虚拟源方案在个别机型上仍被当跨域，允许 file:// 页面读取同目录资源
        s.setAllowFileAccess(true);
        try {
            s.setAllowFileAccessFromFileURLs(true);
            s.setAllowUniversalAccessFromFileURLs(true);
        } catch (Exception ignored) {
        }
        // 打开 WebView 调试：连上电脑后可在 chrome://inspect 里看页面与网络请求，便于排查
        try {
            WebView.setWebContentsDebuggingEnabled(true);
        } catch (Exception ignored) {
        }

        web.setWebViewClient(new WebViewClient() {
            /**
             * 页面里指向外部的链接（目前只有底部的仓库地址）交给系统浏览器打开。
             *
             * 不这么做的话，点它会在这个 WebView 里导航过去——而我们的
             * shouldInterceptRequest 会把它当普通请求处理，最后变成 404，
             * 用户会觉得"链接是坏的"。
             */
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                String url = r == null || r.getUrl() == null ? "" : r.getUrl().toString();
                return openExternally(url);
            }

            @Override
            @SuppressWarnings("deprecation")
            public boolean shouldOverrideUrlLoading(WebView v, String url) {
                return openExternally(url);
            }

            /**
             * 接管所有请求。返回非 null 时 WebView 不会发起真实网络请求，
             * 也就不会做 CORS 检查，页面里的 fetch('/api/...') 于是能拿到原生数据。
             */
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest request) {
                try {
                    String url = request.getUrl() == null ? "" : request.getUrl().toString();
                    String method = request.getMethod() == null ? "GET" : request.getMethod();
                    return shell.intercept(url, method, null, null);
                } catch (Exception e) {
                    Diag.e("拦截失败 " + request.getUrl(), e);
                    return null;
                }
            }

            @Override
            @SuppressWarnings("deprecation")
            public WebResourceResponse shouldInterceptRequest(WebView v, String url) {
                try {
                    return shell.intercept(url, "GET", null, null);
                } catch (Exception e) {
                    return null;
                }
            }

            @Override
            public void onReceivedError(WebView v, WebResourceRequest request,
                                        android.webkit.WebResourceError error) {
                boolean main = request != null && request.isForMainFrame();
                String what = request == null ? "" : String.valueOf(request.getUrl());
                Diag.e("资源加载失败: " + what + " -> "
                    + (error == null ? "?" : error.getDescription()));
                if (main) toast("页面加载失败: " + (error == null ? "未知" : error.getDescription()));
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                Diag.d("页面加载完成: " + url);
                // 页面已经在显示自己的加载/登录状态了，原生这层转圈可以撤掉
                hideBar();
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage m) {
                // 页面的 console 输出会走到这里。CSS/JS 加载失败、接口异常等
                // 都靠它暴露到 logcat（`adb logcat -s GzhuKB`）
                Diag.d("[page] " + m.message()
                    + " @" + m.sourceId() + ":" + m.lineNumber());
                return true;
            }

            /**
             * 外部链接带 target="_blank" 时会走这里。
             * 返回 false 表示"不创建新窗口"，同时让系统接住这次导航，
             * 于是仓库地址就会在浏览器里打开，而不是在 App 里打不开。
             */
            @Override
            public boolean onCreateWindow(WebView v, boolean isDialog, boolean isUserGesture,
                                          android.os.Message resultMsg) {
                return false;
            }
        });

        // 仅用于登录：拦截方案拿不到 POST 请求体，登录改由原生界面完成
        web.addJavascriptInterface(new Bridge(), "AndroidBridge");

        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        bar = new ProgressBar(this);
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(dp(36), dp(36));
        bp.gravity = Gravity.CENTER;
        bar.setVisibility(View.GONE);
        root.addView(bar, bp);

        setContentView(root);
    }

    /**
     * 读取请求体的工具（当前没有使用）。
     *
     * 保留在这里是为了记下结论：WebView 的 shouldInterceptRequest **拿不到
     * POST 请求体**——WebResourceRequest 接口里根本没有 getInputStream()
     * （android.jar 里只有 getUrl/isForMainFrame/isRedirect/hasGesture/getMethod/
     * getRequestHeaders）。Android 官方 issue 119844519 也确认不打算支持。
     *
     * 所以"页面把账号密码 POST 给原生"这条路是走不通的，登录只能经
     * JavaScript 桥传凭据（见 Bridge.startLogin）。这段读取代码留着，
     * 是为了以后如果要支持 PUT 之类的小请求有个起点。
     */
    @SuppressWarnings("unused")
    private String readRequestBody(java.io.InputStream in) {
        if (in == null) return null;
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
                if (bos.size() > MAX_BODY_BYTES) break;
            }
            String s = new String(bos.toByteArray(), StandardCharsets.UTF_8).trim();
            return s.isEmpty() ? null : s;
        } catch (Exception e) {
            Diag.e("读取请求体失败: " + e.getMessage());
            return null;
        }
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    private static String msg(Exception e) {
        String m = e.getMessage();
        return m == null || m.isEmpty() ? "登录失败，请检查网络" : m;
    }

    // ---------------------------------------------------------------- 登录桥

    /**
     * 页面与原生之间的登录通道。
     *
     * 为什么必须用桥而不是让页面 POST：WebView 的 shouldInterceptRequest 拿不到
     * POST 请求体（WebResourceRequest 没有 getInputStream），页面提交的账号密码
     * 到不了原生。
     *
     * 为什么桥方法必须"立即返回"：@JavascriptInterface 方法运行在 WebView 的
     * JavaBridge 线程上，在里面阻塞等网络会把页面 JS 一并卡死。所以这里只负责
     * 把凭据交给仓库发起登录，页面随后轮询 /api/login-result 取结果。
     *
     * 凭据只走这条内存通道，不写日志、不进 URL。
     */
    private class Bridge {

        /** 发起登录，立即返回。结果由页面轮询 /api/login-result 获取。 */
        @JavascriptInterface
        public String startLogin(String json) {
            Map<String, Object> in = Json.parseObject(json);
            String user = in == null || in.get("username") == null
                ? "" : String.valueOf(in.get("username")).trim();
            String pass = in == null || in.get("password") == null
                ? "" : String.valueOf(in.get("password"));
            if (user.isEmpty() || pass.isEmpty()) {
                return "{\"ok\":false,\"error\":\"请输入学号和密码\"}";
            }
            boolean remember = Json.bool(in, "remember", true);
            Diag.d("桥：发起登录 " + user + (remember ? "（记住密码）" : "（不保存密码）"));
            repo.startLogin(user, pass, remember);
            return "{\"ok\":true}";
        }

        /** 登录完成后由页面调用，用来刷新桌面小组件（必须在主线程碰 AppWidgetManager） */
        @JavascriptInterface
        public void onLoginSettled() {
            runOnUiThread(() -> {
                KbWidgetProvider.updateAll(MainActivity.this);
                hideBar();
            });
        }

        /** 页面需要显示原生转圈时的钩子；失败/成功都会由页面自己收尾 */
        @JavascriptInterface
        public void setBusy(final boolean busy) {
            runOnUiThread(() -> {
                if (bar != null) bar.setVisibility(busy ? View.VISIBLE : View.GONE);
            });
        }
    }

    // ---------------------------------------------------------------- 生命周期

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (web != null) web.saveState(out);
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }
}
