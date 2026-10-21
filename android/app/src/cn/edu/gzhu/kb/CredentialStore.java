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

    /**
     * 上一次保存/读取失败的原因；成功时为空串。
     *
     * 为什么要暴露这个：密码保存失败时"下次免登录"就无效，但用户只看到
     * "又要重输密码"，完全无从判断是 Keystore 不可用、存储写失败还是别的原因。
     * 把它报到页面（/api/saved）后，界面可以直接告诉用户到底哪一步出了错。
     */
    String lastError();
}
