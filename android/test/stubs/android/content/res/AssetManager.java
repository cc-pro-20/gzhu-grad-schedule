package android.content.res;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * 测试桩：AssetManager。
 * 用一个真实目录当作 assets 根，这样能读取构建时同步过去的 www/ 资源，
 * 从而验证 shouldInterceptRequest 真的能取到页面文件。
 */
public class AssetManager {
    private final File base;

    public AssetManager(File base) {
        this.base = base;
    }

    public InputStream open(String name) throws IOException {
        // Android 只接受正斜杠；这里同样做一次规范化
        File f = new File(base, name.replace('\\', '/'));
        if (!f.isFile()) throw new IOException("asset not found: " + name + " (" + f + ")");
        return new FileInputStream(f);
    }

    /** 与 Android 行为一致：列出某个虚拟目录下的条目名 */
    public String[] list(String dir) throws IOException {
        File d = dir == null || dir.isEmpty() ? base : new File(base, dir.replace('\\', '/'));
        if (!d.isDirectory()) return new String[0];
        String[] names = d.list();
        return names == null ? new String[0] : names;
    }
}