package cn.edu.gzhu.kb;

/**
 * 一次异步登录尝试的状态。
 *
 * 为什么需要它：WebView 的 shouldInterceptRequest **拿不到 POST 请求体**
 * （WebResourceRequest 根本没有 getInputStream()，Android 官方 issue 119844519
 * 也已确认不打算支持）。所以页面提交账号密码这条路走不到原生，
 * 只能通过 JavaScript 桥（AndroidBridge）把凭据传进来。
 *
 * 而 @JavascriptInterface 方法运行在 WebView 的 JavaBridge 线程上，如果在里面
 * 阻塞等网络（登录要几百毫秒到几十秒），会把页面 JS 一起卡住——所以要改成
 * "页面发起 + 页面轮询"：桥只负责启动，结果写在这里供页面查询。
 *
 * 这个类刻意不依赖任何 Android API，因此能在桌面 JVM 上被测试覆盖。
 */
public final class LoginState {

    public enum Phase {
        /** 还没发起过登录 */
        IDLE,
        /** 正在登录 */
        RUNNING,
        /** 登录成功 */
        OK,
        /** 登录失败，error 里有原因 */
        FAILED
    }

    private Phase phase = Phase.IDLE;
    private String name = "";
    private String error = "";
    private long startedAt;
    private long finishedAt;

    public synchronized void start() {
        phase = Phase.RUNNING;
        name = "";
        error = "";
        startedAt = System.currentTimeMillis();
        finishedAt = 0;
    }

    public synchronized void done(String userName) {
        phase = Phase.OK;
        name = userName == null ? "" : userName;
        error = "";
        finishedAt = System.currentTimeMillis();
    }

    public synchronized void fail(String message) {
        phase = Phase.FAILED;
        name = "";
        error = message == null || message.isEmpty() ? "登录失败" : message;
        finishedAt = System.currentTimeMillis();
    }

    public synchronized Phase phase() {
        return phase;
    }

    public synchronized String name() {
        return name;
    }

    public synchronized String error() {
        return error;
    }

    /** 是否正在登录（页面据此决定要不要继续轮询） */
    public synchronized boolean isRunning() {
        return phase == Phase.RUNNING;
    }

    /** 这次尝试是否已经出结果（成功或失败） */
    public synchronized boolean isSettled() {
        return phase == Phase.OK || phase == Phase.FAILED;
    }

    public synchronized long elapsedMs() {
        long end = finishedAt > 0 ? finishedAt : System.currentTimeMillis();
        return startedAt <= 0 ? 0 : end - startedAt;
    }

    public synchronized void reset() {
        phase = Phase.IDLE;
        name = "";
        error = "";
        startedAt = 0;
        finishedAt = 0;
    }

    /** 供页面轮询的 JSON，字段与 /api/login-result 一致 */
    public synchronized java.util.Map<String, Object> toMap() {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("phase", phase.name().toLowerCase(java.util.Locale.ROOT));
        m.put("running", phase == Phase.RUNNING);
        m.put("ok", phase == Phase.OK);
        m.put("name", name);
        m.put("error", error);
        m.put("elapsedMs", elapsedMs());
        return m;
    }
}
