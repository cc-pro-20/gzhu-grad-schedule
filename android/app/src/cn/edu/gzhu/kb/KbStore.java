package cn.edu.gzhu.kb;

import java.util.Map;

/**
 * 会话 cookie 的持久化接口。
 *
 * 抽成接口是为了让 Http / School 等网络逻辑完全不依赖 Android API，
 * 从而可以在桌面 JVM 上直接回归测试（见 test/AndroidLogicTest.java）。
 * Android 侧的实现在 FileKbStore。
 */
public interface KbStore {

    /** 读回已保存的 cookie，没有则返回空 Map */
    Map<String, String> load();

    /** 保存当前 cookie 快照 */
    void save(Map<String, String> cookies);

    /** 清空 */
    void clear();
}
