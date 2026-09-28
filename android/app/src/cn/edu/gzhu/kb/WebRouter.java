package cn.edu.gzhu.kb;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 页面请求的路由决策（纯逻辑，不依赖 Android API，便于单测）。
 *
 * WebShell 只负责把这里算出来的结果包成 WebResourceResponse，
 * 真正的"该走 assets 还是该走 /api"判断全在这里。
 */
public final class WebRouter {

    public enum Kind {
        /** 从 assets/www 读静态资源 */
        ASSET,
        /** 交给原生接口处理 */
        API,
        /** 不认识的路径，直接 404 */
        NOT_FOUND
    }

    public static final class Route {
        public final Kind kind;
        /** ASSET 时是 assets 里的相对路径；API 时是 /api/xxx */
        public final String path;
        public final Map<String, String> query;
        public final String method;

        Route(Kind kind, String path, Map<String, String> query, String method) {
            this.kind = kind;
            this.path = path;
            this.query = query;
            this.method = method;
        }

        public boolean isPost() {
            return "POST".equalsIgnoreCase(method);
        }

        @Override
        public String toString() {
            return kind + " " + path + (query.isEmpty() ? "" : " " + query);
        }
    }

    private WebRouter() {}

    /**
     * 解析一个请求。
     *
     * 需要处理的几种形态：
     *   file:///android_asset/www/index.html   （初始页）
     *   file:///android_asset/www/style.css    （相对资源）
     *   file:///android_asset/www/api/state    （页面里的 fetch('/api/state')）
     *   http://127.0.0.1/api/kb?week=4         （兼容绝对地址写法）
     */
    public static Route route(String url, String method) {
        String m = method == null || method.isEmpty() ? "GET" : method;
        String raw = url == null ? "" : url;

        // 分离 query 与 fragment
        String query = "";
        int q = raw.indexOf('?');
        if (q >= 0) {
            query = raw.substring(q + 1);
            raw = raw.substring(0, q);
        }
        int hash = raw.indexOf('#');
        if (hash >= 0) raw = raw.substring(0, hash);
        int hq = query.indexOf('#');
        if (hq >= 0) query = query.substring(0, hq);
        Map<String, String> qs = parseQuery(query);

        // 取出路径部分
        String path = raw;
        if (raw.startsWith("file://")) {
            // file:///android_asset/www/xxx  ->  /xxx
            int idx = raw.indexOf("/android_asset/");
            if (idx >= 0) {
                path = raw.substring(idx + "/android_asset".length());
            } else {
                path = raw.substring("file://".length());
            }
        } else if (raw.startsWith("http://") || raw.startsWith("https://")) {
            int slash = raw.indexOf('/', raw.indexOf("//") + 2);
            path = slash >= 0 ? raw.substring(slash) : "/";
        }

        // 去掉 assets 根前缀
        if (path.startsWith("/www/")) path = path.substring("/www".length());
        if (path.isEmpty()) path = "/";

        // /api/* 走原生接口
        if (path.startsWith("/api/")) {
            return new Route(Kind.API, path, qs, m);
        }

        // 根路径给 index.html
        if ("/".equals(path)) path = "/index.html";
        String name = path.startsWith("/") ? path.substring(1) : path;
        if (name.isEmpty()) name = "index.html";
        if (name.contains("..")) return new Route(Kind.NOT_FOUND, name, qs, m);

        // 只放行已知的静态资源类型，避免把任意路径当文件读
        if (!isAssetName(name)) return new Route(Kind.NOT_FOUND, name, qs, m);
        return new Route(Kind.ASSET, name, qs, m);
    }

    static boolean isAssetName(String name) {
        return name.endsWith(".html") || name.endsWith(".css") || name.endsWith(".js")
            || name.endsWith(".json") || name.endsWith(".png") || name.endsWith(".svg")
            || name.endsWith(".ico") || name.endsWith(".woff2") || name.endsWith(".ttf");
    }

    public static Map<String, String> parseQuery(String q) {
        Map<String, String> map = new LinkedHashMap<>();
        if (q == null || q.isEmpty()) return map;
        for (String pair : q.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            String k = eq >= 0 ? pair.substring(0, eq) : pair;
            String v = eq >= 0 ? pair.substring(eq + 1) : "";
            try {
                map.put(java.net.URLDecoder.decode(k, "UTF-8"),
                        java.net.URLDecoder.decode(v, "UTF-8"));
            } catch (Exception e) {
                map.put(k, v);
            }
        }
        return map;
    }

    /**
     * 从页面脚本里把用到的 /api 路径抓出来。
     *
     * 用途：把"前端实际请求什么"与"原生能不能处理"做成可自动校验的一致性检查，
     * 防止以后改了前端却忘了改原生。纯字符串处理，不依赖 Android。
     */
    public static java.util.List<String> extractApiPaths(String js) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (js == null) return out;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("[" + "'" + "\"]((?:/api/)[A-Za-z0-9_\\-./]*)")
                .matcher(js);
        while (m.find()) {
            String p = m.group(1);
            // 去掉可能的尾部斜杠
            if (p.length() > 1 && p.endsWith("/")) p = p.substring(0, p.length() - 1);
            if (!out.contains(p)) out.add(p);
        }
        return out;
    }

    /** 原生侧当前支持处理的接口 */
    public static java.util.List<String> supportedApiPaths() {
        java.util.List<String> l = new java.util.ArrayList<>();
        l.add("/api/login");
        l.add("/api/login-start");
        l.add("/api/login-result");
        l.add("/api/saved");
        l.add("/api/logout");
        l.add("/api/state");
        l.add("/api/terms");
        l.add("/api/week");
        l.add("/api/kb");
        return l;
    }
}
