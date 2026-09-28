package cn.edu.gzhu.kb;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 广州大学研究生系统客户端：CAS 登录 + 课表数据。
 * 逻辑与 server/server.js 保持一致（同一套接口、同一套字段映射）。
 */
public final class School {

    public static final String CAS_HOST = "https://newcas.gzhu.edu.cn";
    public static final String APP_HOST = "https://yjsyxt.gzhu.edu.cn";
    public static final String APP = "/gsapp/sys/yddwdkbapp";
    /** 注意：`*` 必须原样保留，编码成 %2A 会被服务端拒绝(403) */
    public static final String RAW_TARGET = APP + "/*default/index.do";

    private final Http.Jar jar = new Http.Jar();
    private String userId = "";
    private String userName = "";
    private volatile boolean warmed = false;

    public School() {}

    /** 用落盘恢复的 cookie 构造，复用已有会话（后台刷新时省一次登录） */
    public School(String userId, String userName, java.util.Map<String, String> cookies) {
        if (cookies != null) jar.restore(cookies);
        this.userId = userId == null ? "" : userId;
        this.userName = userName == null ? "" : userName;
    }

    public String userId() { return userId; }
    public String userName() { return userName; }
    public Http.Jar jar() { return jar; }

    /** 已有会话时跳过登录；否则返回 false 需要调用方先 login() */
    public boolean hasSession() {
        return jar.has("GS_SESSIONID");
    }

    public static final class Fail extends Exception {
        public final boolean authFailed;
        public Fail(String msg, boolean authFailed) {
            super(msg);
            this.authFailed = authFailed;
        }
    }

    /** CAS 账密登录，成功后 jar 内已有 GS_SESSIONID */
    public void login(String un, String pd) throws Exception {
        String svc = Http.enc(APP_HOST + RAW_TARGET);
        String loginUrl = CAS_HOST + "/cas/login?service=" + svc;

        // 校园系统偶发返回不含 lt 的页面（限速/抖动），这里自行重试几次
        String lt = null;
        String execution = null;
        Exception lastErr = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                Http.Resp page = Http.getRetry(loginUrl, jar, html());
                lt = first(page.body, "name=\"lt\"\\s+value=\"([^\"]+)\"");
                execution = first(page.body, "name=\"execution\"\\s+value=\"([^\"]+)\"");
                if (lt != null) break;
                lastErr = new Fail("认证服务未返回登录票据", false);
            } catch (Exception e) {
                lastErr = e;
            }
            if (attempt < 3) Thread.sleep(1500L * attempt);
        }
        if (lt == null) {
            throw lastErr != null ? lastErr : new Fail("无法获取 CAS 登录票据，认证服务可能已改版", false);
        }
        if (execution == null) execution = "e1s1";

        Map<String, String> form = new LinkedHashMap<>();
        form.put("un", un);
        form.put("pd", pd);
        form.put("lt", lt);
        form.put("execution", execution);
        form.put("_eventId", "submit");
        form.put("ul", String.valueOf(un.length()));
        form.put("pl", String.valueOf(pd.length()));
        form.put("rsa", Des.strEnc(un + pd + lt, "1", "2", "3"));

        Http.Resp posted = Http.postRetry(loginUrl, Http.form(form), jar, html());
        String loc = posted.header("location");
        if (loc == null) {
            String err = first(posted.body, "id=\"errmsg\"[^>]*>([^<]+)<");
            throw new Fail(err != null ? err.trim() : "账号或密码错误", true);
        }

        // 跟随 ticket 换取业务会话。
        // 关键：最后一跳（应用首页）必须在应用域上真正取到一次。
        // 前置的安全设备会在这一步下发长效的 _WEU 令牌，缺少它后续业务接口会被
        // 判为异常客户端并返回 403（一张 GIF）。已用对照实验验证。
        String url = loc;
        int hop = 0;
        Http.Resp last = null;
        while (hop++ < 6) {
            Http.Resp r = Http.getRetry(url, jar, html());
            last = r;
            if (r.status >= 300 && r.status < 400) {
                String next = Http.absolute(APP_HOST, r.header("location"));
                if (next == null) break;
                url = next;
                continue;
            }
            break;
        }
        warmed = false; // 交给 ensureWarm() 在首次业务调用前再取一次首页
        if (!jar.has("GS_SESSIONID")) throw new Fail("登录未建立业务会话，请稍后重试", true);

        String body = last == null ? "" : last.body;
        userName = orEmpty(first(body, "USERNAME='([^']*)'"));
        userId = orEmpty(first(body, "USERID='([^']*)'"));
        if (userId.isEmpty()) userId = un;
        if (userName.isEmpty()) userName = un;
    }

    private static Map<String, String> html() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
        return h;
    }

    private static Map<String, String> apiHeaders() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Accept", "application/json, text/javascript, */*; q=0.01");
        h.put("X-Requested-With", "XMLHttpRequest");
        h.put("Referer", APP_HOST + RAW_TARGET);
        h.put("Origin", APP_HOST);
        return h;
    }

    /**
     * 首次调用业务接口前先访问一次应用首页，刷新安全设备下发的 _WEU 令牌。
     * 不做这一步时业务接口会稳定返回 403。
     * 若这次访问本身被安全设备拒绝（403 GIF），说明时限已过，需要重试一轮。
     */
    private synchronized void ensureWarm() {
        if (warmed) return;
        for (int i = 0; i < 2 && !warmed; i++) {
            try {
                Http.Resp r = Http.getRetry(APP_HOST + RAW_TARGET, jar, html());
                if (r.status < 400) warmed = true;
            } catch (Exception ignored) {
                // 预热失败不阻断，让后续业务请求自己报错
            }
            if (!warmed) {
                try {
                    Thread.sleep(1000L);
                } catch (InterruptedException ignored) {
                }
            }
        }
    }

    /** 业务接口统一入口：返回 datas 部分 */
    private Object api(String name, Map<String, String> params) throws Exception {
        ensureWarm();
        String url = APP_HOST + APP + "/modules/wdkb/" + name + ".do";
        Http.Resp r = Http.postRetry(url, Http.form(params), jar, apiHeaders());
        if (r.status == 401) throw new Fail("登录状态已失效，请重新登录", true);
        Map<String, Object> j = Json.parseObject(r.body);
        if (j == null) throw new Fail("接口 " + name + " 返回异常(" + r.status + ")", false);
        String code = Json.str(j, "code");
        if (code != null && !"0".equals(code)) {
            throw new Fail(Json.str(j, "msg", "接口 " + name + " 返回 code=" + code), false);
        }
        return j.get("datas");
    }

    @SuppressWarnings("unchecked")
    private static List<Object> rowsOf(Object datas, String key) {
        Map<String, Object> d = Json.obj(datas);
        if (d == null) return new ArrayList<>();
        Map<String, Object> inner = Json.obj(d.get(key));
        if (inner == null) return new ArrayList<>();
        List<Object> rows = Json.arr(inner, "rows");
        return rows == null ? new ArrayList<Object>() : rows;
    }

    /** 可选学年学期 */
    public List<Map<String, Object>> terms() throws Exception {
        List<Object> rows = rowsOf(api("kfdxnxqcx", new LinkedHashMap<String, String>()), "kfdxnxqcx");
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : rows) {
            Map<String, Object> r = Json.obj(o);
            if (r == null) continue;
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("dm", Json.str(r, "XNXQDM", ""));
            t.put("mc", Json.str(r, "XNXQDM_DISPLAY", ""));
            out.add(t);
        }
        return out;
    }

    /** 当前周次与本周日期 */
    public Map<String, Object> weeks() throws Exception {
        ensureWarm();
        String url = APP_HOST + APP + "/modules/wdkb/getXnxqdyzc.do";
        Http.Resp r = Http.postRetry(url, "", jar, apiHeaders());
        Map<String, Object> out = new LinkedHashMap<>();
        List<Object> days = new ArrayList<>();
        Integer zc = null;
        String xnxqdm = null;
        Map<String, Object> j = Json.parseObject(r.body);
        if (j != null) {
            List<Object> rows = Json.arr(j, "rqData");
            if (rows != null) {
                for (Object o : rows) {
                    Map<String, Object> d = Json.obj(o);
                    if (d == null) continue;
                    if (zc == null) {
                        String z = Json.str(d, "ZC");
                        zc = z == null ? null : (int) Double.parseDouble(z);
                        xnxqdm = Json.str(d, "XNXQDM");
                    }
                    Map<String, Object> day = new LinkedHashMap<>();
                    day.put("xq", Json.i(d, "XQ", 0));
                    day.put("rq", Json.str(d, "RQ", ""));
                    day.put("jt", "1".equals(Json.str(d, "SFJT")));
                    days.add(day);
                }
            }
        }
        out.put("zc", zc);
        out.put("xnxqdm", xnxqdm);
        out.put("days", days);
        return out;
    }

    /** 节次方案 */
    public List<Map<String, Object>> jieci(String xnxqdm, String xh) throws Exception {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("XNXQDM", xnxqdm);
        p.put("XH", xh);
        List<Object> rows = rowsOf(api("xsskjccx", p), "xsskjccx");
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : rows) {
            Map<String, Object> r = Json.obj(o);
            if (r == null) continue;
            Map<String, Object> j = new LinkedHashMap<>();
            j.put("dm", Json.str(r, "DM", ""));
            j.put("jcfadm", Json.str(r, "JCFADM", ""));
            j.put("mc", Json.str(r, "MC", ""));
            j.put("jcfamc", Json.str(r, "JCFAMC", ""));
            j.put("kssj", fmtTime(Json.str(r, "KSSJ")));
            j.put("jssj", fmtTime(Json.str(r, "JSSJ")));
            out.add(j);
        }
        out.sort((a, b) -> num(a.get("dm")) - num(b.get("dm")));
        return out;
    }

    /** 课表原始记录 */
    public List<Map<String, Object>> courses(String xnxqdm) throws Exception {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("XNXQDM", xnxqdm);
        p.put("*order", "+KCDM,-ZCBH,+XQ,+KSJCDM");
        List<Object> rows = rowsOf(api("xspkjgcx", p), "xspkjgcx");
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : rows) {
            Map<String, Object> r = Json.obj(o);
            if (r == null) continue;
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("kcmc", Json.str(r, "KCMC", "未命名课程"));
            c.put("kcdm", Json.str(r, "KCDM", ""));
            c.put("jasmc", Json.str(r, "JASMC", ""));
            c.put("jsxm", Json.str(r, "JSXM", ""));
            c.put("xq", Json.i(r, "XQ", 0));
            c.put("ksjcdm", Json.str(r, "KSJCDM", ""));
            c.put("jsjcdm", Json.str(r, "JSJCDM", ""));
            c.put("jcfadm", Json.str(r, "JCFADM", ""));
            c.put("zcbh", Json.str(r, "ZCBH", ""));
            c.put("zcmc", Json.str(r, "ZCMC", ""));
            c.put("kssj", fmtTime(Json.str(r, "KSSJ")));
            c.put("jssj", fmtTime(Json.str(r, "JSSJ")));
            c.put("bjmc", Json.str(r, "BJMC", ""));
            c.put("kclb", Json.str(r, "KCLBDM_DISPLAY", ""));
            out.add(c);
        }
        return out;
    }

    // ---------------- 工具 ----------------

    /** 把 830 这类整数时间格式化为 08:30 */
    static String fmtTime(String v) {
        if (v == null || v.isEmpty() || "null".equals(v)) return "";
        String s = v.trim();
        if (s.contains(":")) return s;
        while (s.length() < 4) s = "0" + s;
        return s.substring(0, 2) + ":" + s.substring(2, 4);
    }

    static int num(Object o) {
        try {
            return (int) Double.parseDouble(String.valueOf(o));
        } catch (Exception e) {
            return 0;
        }
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    static String first(String src, String regex) {
        if (src == null) return null;
        Matcher m = Pattern.compile(regex).matcher(src);
        return m.find() ? m.group(1) : null;
    }
}
