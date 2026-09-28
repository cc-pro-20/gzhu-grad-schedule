package cn.edu.gzhu.kb;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * 极简 HTTP 客户端，服务端是校园系统，有两个必须处理的点：
 *
 * 1. 业务路径里的 `*` 必须原样发送。Java 的 String→URL 会把 `*` 当合法字符保留，
 *    但一旦被编码成 %2A，服务端就会返回 403（一张 GIF），所以这里从不做编码。
 * 2. 校园站点在 IPv6 与 IPv4 下都存在，HttpURLConnection 会自行择优。
 */
public final class Http {

    public static final class Resp {
        public final int status;
        public final String body;
        public final Map<String, String> headers;

        Resp(int status, String body, Map<String, String> headers) {
            this.status = status;
            this.body = body;
            this.headers = headers;
        }

        public String header(String name) {
            return headers.get(name.toLowerCase());
        }
    }

    /** 会话 cookie：name=value 的简单集合 */
    public static final class Jar {
        private final Map<String, String> map = new LinkedHashMap<>();

        /**
         * 全局的落盘位置。App 启动时由 KbRepository 注入一次即可，
         * 之后任何 Set-Cookie 都会自动持久化（含运行在后台刷新进程里的小组件）。
         * 类型是接口，所以本类不依赖任何 Android API。
         */
        private static volatile KbStore store;

        public static void setStore(KbStore s) {
            store = s;
        }

        public synchronized void add(Map<String, List<String>> headers) {
            if (headers == null) return;
            boolean changed = false;
            for (Map.Entry<String, List<String>> e : headers.entrySet()) {
                if (e.getKey() == null || !e.getKey().equalsIgnoreCase("Set-Cookie")) continue;
                for (String raw : e.getValue()) {
                    int semi = raw.indexOf(';');
                    String pair = semi >= 0 ? raw.substring(0, semi) : raw;
                    int eq = pair.indexOf('=');
                    if (eq > 0) {
                        map.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
                        changed = true;
                    }
                }
            }
            if (changed) persist();
        }

        public synchronized String header() {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, String> e : map.entrySet()) {
                if (sb.length() > 0) sb.append("; ");
                sb.append(e.getKey()).append('=').append(e.getValue());
            }
            return sb.toString();
        }

        public synchronized boolean has(String name) {
            return map.containsKey(name);
        }

        public synchronized void clear() {
            map.clear();
            persist();
        }

        /** 导出快照，便于落盘 */
        public synchronized Map<String, String> snapshot() {
            return new LinkedHashMap<>(map);
        }

        /** 从落盘快照恢复（用于后台刷新时复用会话） */
        public synchronized void restore(Map<String, String> saved) {
            if (saved == null || saved.isEmpty()) return;
            map.putAll(saved);
        }

        /** 把当前 cookie 交给 store 落盘；store 为空时不做任何事 */
        private void persist() {
            KbStore s = store;
            if (s != null) s.save(snapshot());
        }
    }

    public static final String UA =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36";

    /** 设为 true 时把发出的请求头打到 stderr（排查用） */
    public static boolean DEBUG = "1".equals(System.getenv("KB_HTTP_DEBUG"));

    private Http() {}

    public static Resp get(String url, Jar jar, Map<String, String> extraHeaders) throws Exception {
        return request("GET", url, null, jar, extraHeaders);
    }

    public static Resp post(String url, String formBody, Jar jar, Map<String, String> extraHeaders) throws Exception {
        return request("POST", url, formBody, jar, extraHeaders);
    }

    /** 网络层抖动（超时/连接被重置）时自动重试；HTTP 状态码错误不重试 */
    public static Resp getRetry(String url, Jar jar, Map<String, String> extraHeaders) throws Exception {
        return withRetry(() -> request("GET", url, null, jar, extraHeaders));
    }

    public static Resp postRetry(String url, String body, Jar jar, Map<String, String> extraHeaders) throws Exception {
        return withRetry(() -> request("POST", url, body, jar, extraHeaders));
    }

    private interface Call {
        Resp run() throws Exception;
    }

    private static Resp withRetry(Call call) throws Exception {
        Exception last = null;
        for (int i = 0; i < 3; i++) {
            try {
                return call.run();
            } catch (Exception e) {
                last = e;
                if (i < 2) {
                    try {
                        Thread.sleep(1200L * (i + 1));
                    } catch (InterruptedException ignored) {
                    }
                }
            }
        }
        throw last;
    }

    private static Resp request(String method, String url, String body, Jar jar, Map<String, String> extra)
            throws Exception {
        URL u = new URL(url);
        // 不做 socket 工厂替换：学校用的是 DigiCert 标准证书，走系统信任链校验即可，
        // 这样能真正防中间人，而不是无脑放行。
        HttpURLConnection conn = (HttpURLConnection) u.openConnection();
        conn.setRequestMethod(method);
        conn.setInstanceFollowRedirects(false); // 手工跟随，便于逐跳收集 cookie
        conn.setConnectTimeout(20000);
        conn.setReadTimeout(45000);
        conn.setRequestProperty("User-Agent", UA);
        conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");
        // 刻意不声明 Accept-Encoding: gzip。
        // 服务端前置的防护设备会按请求头做指纹校验，多出这一项会被判定为异常客户端
        // 并返回 403（一张 GIF）。身份是 identity 时也无需解压。
        conn.setRequestProperty("Accept-Encoding", "identity");
        if (jar != null) {
            String ck = jar.header();
            if (!ck.isEmpty()) conn.setRequestProperty("Cookie", ck);
        }
        if (extra != null) for (Map.Entry<String, String> e : extra.entrySet()) conn.setRequestProperty(e.getKey(), e.getValue());
        // 必须在任何会"建立连接"的操作（getOutputStream/getResponseCode）之前取，
        // 否则 getRequestProperties() 会抛 IllegalStateException: Already connected
        if (DEBUG) {
            System.err.println("[HTTP] " + method + " " + url);
            for (Map.Entry<String, java.util.List<String>> e : conn.getRequestProperties().entrySet()) {
                if (e.getKey() == null) continue;
                System.err.println("       " + e.getKey() + ": " + e.getValue());
            }
        }
        if (body != null) {
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(bytes);
            }
        }
        int status = conn.getResponseCode();
        Map<String, String> headers = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : conn.getHeaderFields().entrySet()) {
            if (e.getKey() == null) continue;
            List<String> v = e.getValue();
            if (v != null && !v.isEmpty()) headers.put(e.getKey().toLowerCase(), v.get(v.size() - 1));
        }
        if (jar != null) jar.add(conn.getHeaderFields());
        String text = readBody(conn);
        conn.disconnect();
        return new Resp(status, text, headers);
    }

    private static String readBody(HttpURLConnection conn) {
        try {
            InputStream in = conn.getResponseCode() >= 400 ? conn.getErrorStream() : conn.getInputStream();
            if (in == null) return "";
            if ("gzip".equalsIgnoreCase(conn.getContentEncoding())) in = new GZIPInputStream(in);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    /** 表单编码 */
    public static String form(Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (sb.length() > 0) sb.append('&');
            sb.append(enc(e.getKey())).append('=').append(enc(e.getValue() == null ? "" : e.getValue()));
        }
        return sb.toString();
    }

    public static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    /** 把相对地址补成绝对地址 */
    public static String absolute(String base, String loc) {
        if (loc == null || loc.isEmpty()) return null;
        if (loc.startsWith("http://") || loc.startsWith("https://")) return loc;
        if (loc.startsWith("/")) return base + loc;
        return base + "/" + loc;
    }
}
