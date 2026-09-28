package android.webkit;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Map;

/**
 * 测试桩：WebResourceResponse。
 * 只保留本项目真正用到的部分（mime/encoding/数据流/状态码/响应头），
 * 并且能读出内容，方便断言"返回的到底是不是 index.html"。
 */
public class WebResourceResponse {

    private final String mimeType;
    private final String encoding;
    private final InputStream data;
    private Map<String, String> headers;
    private int statusCode = 200;
    private String reason = "OK";

    public WebResourceResponse(String mimeType, String encoding, InputStream data) {
        this.mimeType = mimeType;
        this.encoding = encoding;
        this.data = data;
    }

    public String getMimeType() { return mimeType; }
    public String getEncoding() { return encoding; }
    public InputStream getData() { return data; }

    public void setResponseHeaders(Map<String, String> h) { this.headers = h; }
    public Map<String, String> getResponseHeaders() { return headers; }

    public void setStatusCodeAndReasonPhrase(int code, String reason) {
        this.statusCode = code;
        this.reason = reason;
    }

    public int getStatusCode() { return statusCode; }
    public String getReasonPhrase() { return reason; }

    /** 便捷方法：把响应体读成字符串 */
    public String bodyText() {
        if (data == null) return "";
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = data.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), encoding == null ? "UTF-8" : encoding);
        } catch (Exception e) {
            return "";
        }
    }
}
