package cn.edu.gzhu.kb;

import android.content.Context;

/**
 * Android 侧的装配点：把 EncryptedPrefs / FileKbStore 这些依赖 android.jar 的实现
 * 组装成 KbRepository。
 *
 * 单独放一个类是为了让 KbRepository 本身保持"不依赖 Android 存储实现"，
 * 这样它能在桌面 JVM 上被回归测试直接复用（见 test/WebShellTest.java）。
 */
public final class AndroidRepo {

    private AndroidRepo() {}

    public static KbRepository create(Context ctx) {
        Context app = ctx.getApplicationContext();
        return new KbRepository(app, new EncryptedPrefs(app), new FileKbStore(app));
    }
}
