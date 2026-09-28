package cn.edu.gzhu.kb;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@link KbStore} 的 Android 实现：会话 cookie 落到 App 私有目录。
 *
 * 为什么要持久化：小组件在后台刷新时进程可能是全新的，内存里的会话没有。
 * 没有会话就得每次重新走一遍 CAS 登录（约 1 秒，还会触发学校限速）。
 * 存下来后，正常情况下小组件只需读缓存直接调接口。
 *
 * 位置是 /data/data/<pkg>/files，其他应用读不到。
 * 文件名故意用 .dat，并且应用已关掉 allowBackup，避免会话被备份出去。
 */
public final class FileKbStore implements KbStore {

    private static final String FILE = "session.dat";

    private final File file;

    public FileKbStore(Context ctx) {
        this.file = new File(ctx.getApplicationContext().getFilesDir(), FILE);
    }

    @Override
    public Map<String, String> load() {
        Map<String, String> out = new LinkedHashMap<>();
        if (!file.exists()) return out;
        FileInputStream in = null;
        try {
            in = new FileInputStream(file);
            byte[] buf = new byte[(int) Math.min(file.length(), 8192)];
            int n = in.read(buf);
            if (n <= 0) return out;
            String text = new String(buf, 0, n, StandardCharsets.UTF_8);
            for (String line : text.split("\n")) {
                if (line.isEmpty()) continue;
                int eq = line.indexOf('=');
                if (eq > 0) out.put(line.substring(0, eq), line.substring(eq + 1));
            }
        } catch (Exception ignored) {
        } finally {
            close(in);
        }
        return out;
    }

    @Override
    public void save(Map<String, String> cookies) {
        FileOutputStream out = null;
        try {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, String> e : cookies.entrySet()) {
                sb.append(e.getKey()).append('=').append(e.getValue()).append('\n');
            }
            out = new FileOutputStream(file);
            out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (Exception ignored) {
        } finally {
            close(out);
        }
    }

    @Override
    public void clear() {
        try {
            if (file.exists()) file.delete();
        } catch (Exception ignored) {
        }
    }

    private static void close(java.io.Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (Exception ignored) {
            }
        }
    }
}
