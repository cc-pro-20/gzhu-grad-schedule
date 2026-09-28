package cn.edu.gzhu.kb;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 课表数据仓库：取数、缓存，以及"会话过期就用保存的凭据重新登录"。
 *
 * 前台（WebShell 处理页面请求）与后台（桌面小组件刷新）共用这一套逻辑。
 *
 * 关于依赖边界：Http / School / KbModel / Des / Json 都不依赖 Android API，
 * 因此可以在桌面 JVM 上直接回归测试（见 test/AndroidLogicTest.java）。
 * 只有 EncryptedPrefs / FileKbStore 这两个"存储实现"是 Android 相关的。
 */
public final class KbRepository {

    private static final String TAG = "KbRepo";
    private static final String CACHE_FILE = "kb.dat";

    private final Context ctx;
    private final CredentialStore cred;
    private final KbStore cookies;

    /** 本进程内复用的登录会话 */
    private School session;
    private List<Map<String, Object>> termsCache;

    /** 异步登录的进度与结果，供页面轮询 */
    private final LoginState loginState = new LoginState();

    public KbRepository(Context ctx, CredentialStore cred, KbStore cookies) {
        this.ctx = ctx == null ? null : ctx.getApplicationContext();
        this.cred = cred;
        this.cookies = cookies;
        // 让后续所有 Set-Cookie 自动落盘（后台刷新进程同样生效）
        Http.Jar.setStore(cookies);
    }

    public String userName() {
        return cred.username();
    }

    public boolean hasCredentials() {
        return !cred.username().isEmpty() && cred.hasPassword();
    }

    // ------------------------------------------------------------ 凭据

    public void saveCredentials(String user, String password) {
        cred.save(user, password);
    }

    public void clearCredentials() {
        cred.clearPassword();
    }

    // ------------------------------------------------------------ 会话

    /** 是否已有可用会话（内存里的，或从落盘 cookie 恢复出来的） */
    public synchronized boolean hasSession() {
        if (session != null && session.hasSession()) return true;
        School restored = new School(cred.username(), cred.username(), cookies.load());
        if (restored.hasSession()) {
            session = restored;
            return true;
        }
        return false;
    }

    /** 用给定凭据登录 */
    public synchronized Map<String, Object> login(String user, String pass) throws Exception {
        School s = new School();
        s.login(user, pass);
        session = s;
        termsCache = null;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", s.userName());
        out.put("username", user);
        return out;
    }

    /**
     * 登录成功后的收尾。
     *
     * 缓存里的课表属于上一个账号，必须清掉，否则桌面小组件会显示别人的课表。
     * 这里只做数据层的清理，不碰 Android 的 AppWidgetManager——WebShell 因此
     * 仍然能在桌面 JVM 上被测试覆盖（见 test/WebShellTest.java）。
     * 刷新小组件由 MainActivity 负责。
     */
    public void onLoginSuccess() {
        clearCache();
    }

    // ------------------------------------------------------------ 异步登录

    /**
     * 发起一次登录，立即返回，不阻塞调用方。
     *
     * 存在的原因：登录桥（@JavascriptInterface）运行在 WebView 的 JavaBridge 线程上，
     * 在那里等网络会把页面 JS 一起卡住；而且 WebView 的请求拦截拿不到 POST 请求体
     * （WebResourceRequest 没有 getInputStream），页面没法直接把密码 POST 给原生。
     * 所以约定：页面调 startLogin 发起，然后轮询 {@link #loginState()} 取结果。
     *
     * @param remember 是否把密码加密存到本机（决定下次能否免登录）
     */
    public synchronized void startLogin(final String user, final String pass, final boolean remember) {
        if (loginState.isRunning()) {
            Log.i(TAG, "已有登录进行中，忽略重复请求");
            return;
        }
        loginState.start();
        Thread t = new Thread(() -> {
            try {
                if (remember) saveCredentials(user, pass);
                else clearCredentials();
                Map<String, Object> r = login(user, pass);
                onLoginSuccess();
                loginState.done(String.valueOf(r.get("name")));
                Log.i(TAG, "登录成功: " + user);
            } catch (Exception e) {
                String m = e.getMessage();
                loginState.fail(m == null || m.isEmpty() ? "登录失败，请检查网络" : m);
                Log.w(TAG, "登录失败: " + m);
            }
        }, "kb-async-login");
        t.setDaemon(true);
        t.start();
    }

    public LoginState loginState() {
        return loginState;
    }

    /** 确保有可用会话：优先复用，否则用保存的凭据重登 */
    private synchronized School require() throws Exception {
        if (hasSession()) return session;
        String un = cred.username();
        String pd = cred.password();
        if (un.isEmpty() || pd.isEmpty()) {
            throw new School.Fail("尚未保存账号密码，请先登录", true);
        }
        School s = new School();
        s.login(un, pd);
        session = s;
        return s;
    }

    // ------------------------------------------------------------ 业务数据

    public synchronized List<Map<String, Object>> terms() throws Exception {
        if (termsCache != null && !termsCache.isEmpty()) return termsCache;
        termsCache = require().terms();
        return termsCache;
    }

    public synchronized Map<String, Object> weeks() throws Exception {
        return require().weeks();
    }

    /**
     * 取指定周课表（week&lt;=0 表示跟随服务端"当前周"）。
     * 返回结构与 server/server.js 的 /api/kb 完全一致，前端无需区分来源。
     */
    @SuppressWarnings("unchecked")
    public synchronized Map<String, Object> kb(String term, int week, boolean raw) throws Exception {
        School school = require();
        List<Map<String, Object>> terms = terms();

        String xnxqdm = term;
        if (xnxqdm == null || xnxqdm.isEmpty()) {
            xnxqdm = terms.isEmpty() ? "" : String.valueOf(terms.get(0).get("dm"));
        } else {
            boolean found = false;
            for (Map<String, Object> t : terms) {
                if (xnxqdm.equals(String.valueOf(t.get("dm")))) found = true;
            }
            if (!found && !terms.isEmpty()) xnxqdm = String.valueOf(terms.get(0).get("dm"));
        }
        String termName = xnxqdm;
        for (Map<String, Object> t : terms) {
            if (xnxqdm.equals(String.valueOf(t.get("dm")))) termName = String.valueOf(t.get("mc"));
        }

        Map<String, Object> wk = school.weeks();
        int curWeek = wk.get("zc") == null ? 1 : ((Number) wk.get("zc")).intValue();
        int useWeek = (week >= 1 && week <= 30) ? week : curWeek;

        List<Map<String, Object>> jieci = school.jieci(xnxqdm, school.userId());
        List<Map<String, Object>> courses = school.courses(xnxqdm);

        Map<String, Object> out = new LinkedHashMap<>();
        if (raw) {
            out.put("xnxqdm", xnxqdm);
            out.put("jieci", jieci);
            out.put("courses", courses);
            return out;
        }

        Map<String, Object> grid = KbModel.build(courses, jieci, useWeek);
        List<Object> days = (List<Object>) wk.get("days");

        out.put("xnxqdm", xnxqdm);
        out.put("termName", termName);
        out.put("week", useWeek);
        out.put("curWeek", curWeek);
        out.put("totalWeeks", 30);
        out.put("days", days);
        out.put("curDays", useWeek == curWeek ? days : new java.util.ArrayList<Object>());
        out.put("jieci", jieci);
        out.put("flat", grid.get("flat"));
        out.put("rows", grid.get("rows"));
        out.put("totalHeight", grid.get("totalHeight"));
        out.put("grid", grid.get("grid"));
        return out;
    }

    // ------------------------------------------------------------ 缓存

    public Map<String, Object> loadCache() {
        File f = new File(ctx.getFilesDir(), CACHE_FILE);
        if (!f.exists()) return null;
        FileInputStream in = null;
        try {
            in = new FileInputStream(f);
            byte[] buf = new byte[(int) Math.min(f.length(), 2 * 1024 * 1024)];
            int n = in.read(buf);
            if (n <= 0) return null;
            return Json.parseObject(new String(buf, 0, n, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        } finally {
            close(in);
        }
    }

    public Map<String, Object> loadCachedData() {
        Map<String, Object> c = loadCache();
        return c == null ? null : Json.obj(c.get("data"));
    }

    public void saveCache(Map<String, Object> data) {
        try {
            Map<String, Object> wrap = new LinkedHashMap<>();
            wrap.put("ts", System.currentTimeMillis());
            wrap.put("data", data);
            FileOutputStream out = new FileOutputStream(new File(ctx.getFilesDir(), CACHE_FILE));
            out.write(Json.write(wrap).getBytes(StandardCharsets.UTF_8));
            out.flush();
            close(out);
        } catch (Exception e) {
            Log.w(TAG, "写缓存失败: " + e.getMessage());
        }
    }

    /** 缓存是否属于今天（跨天就需要重取，因为"当前周"会变） */
    public boolean cacheIsFreshToday() {
        Map<String, Object> c = loadCache();
        if (c == null) return false;
        Object v = c.get("ts");
        long ts = v instanceof Number ? ((Number) v).longValue() : 0;
        if (ts <= 0) return false;
        return day(System.currentTimeMillis()).equals(day(ts));
    }

    private static String day(long millis) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(new Date(millis));
    }

    public void clearCache() {
        try {
            File f = new File(ctx.getFilesDir(), CACHE_FILE);
            if (f.exists()) f.delete();
        } catch (Exception ignored) {}
    }

    /** 后台刷新：缓存是今天的就跳过联网 */
    public boolean refreshIfStale() {
        if (cacheIsFreshToday()) {
            Log.i(TAG, "缓存是今天的，跳过联网");
            return true;
        }
        try {
            saveCache(kb(null, 0, false));
            Log.i(TAG, "后台刷新成功");
            return true;
        } catch (Exception e) {
            Log.w(TAG, "后台刷新失败: " + e.getMessage());
            return loadCache() != null;
        }
    }

    /** 退出登录：清凭据、会话、缓存 */
    public synchronized void logout() {
        School s = session;
        session = null;
        termsCache = null;
        cred.clearPassword();
        cookies.clear();
        clearCache();
        try {
            Http.getRetry(School.CAS_HOST + "/cas/logout",
                s == null ? new Http.Jar() : s.jar(), new LinkedHashMap<String, String>());
        } catch (Exception ignored) {}
    }

    private static void close(java.io.Closeable c) {
        if (c != null) try { c.close(); } catch (Exception ignored) {}
    }
}
