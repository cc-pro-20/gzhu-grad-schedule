package cn.edu.gzhu.kb;

/**
 * 凭据存储接口。
 *
 * 与 {@link KbStore} 同样的用意：让 KbRepository 及以下的网络逻辑
 * 完全不依赖 Android API，从而可以在桌面 JVM 上回归测试。
 * Android 侧的实现在 EncryptedPrefs。
 */
public interface CredentialStore {

    String username();

    /** 保存学号与密码（密码必须加密后存放） */
    void save(String username, String password);

    /** 取回密码；没有或解不开时返回空串 */
    String password();

    boolean hasPassword();

    void clearPassword();
}
