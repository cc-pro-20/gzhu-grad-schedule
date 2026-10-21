package cn.edu.gzhu.kb;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * 加密的偏好存储：学号明文存放（方便回填），密码用 Android Keystore 里的
 * AES-256-GCM 密钥加密后存 SharedPreferences。
 *
 * 为什么不用 androidx.security 的 EncryptedSharedPreferences：
 * 它内部依赖 Tink（com.google.crypto.tink:tink-android），而 tink-android
 * 只发布在 dl.google.com / maven.google.com，本项目的构建环境访问不到
 * （已实测超时/连接重置）。而 EncryptedSharedPreferences 本身做的事就是
 * "AndroidKeyStore 里的 AES-GCM 主密钥 + 加密后的 SharedPreferences"，
 * 所以这里用同样的机制自行实现，安全性等价，且不引入任何外部依赖。
 * 详见 README「关于 EncryptedSharedPreferences」。
 *
 * 密钥由系统 TEE/StrongBox 保管，App 卸载即销毁；即使备份文件被拿走，
 * 缺少 Keystore 密钥也无法解密。加密不可用时宁可不存密码，也不明文落盘。
 *
 * ⚠️ 关于"记住密码不生效"这个 bug（已修）：
 * 旧实现里保存/解密失败都是**静默**的——save() 失败直接 clearPassword()，
 * password() 只要抛异常也立刻 clearPassword() 并返回空串。结果是
 *   1) 用户勾了"记住密码"，实际什么都没存，界面上毫无提示；
 *   2) 一次瞬时失败（Keystore 短暂不可用、进程刚启动等）就会把**已经存好的**
 *      密码永久删掉，于是下次打开又要手动登录。
 * 现在：失败原因记录在 lastError 里并写日志；password() 不再因为一次失败就
 * 销毁数据（真要作废时，重新登录会用新密文覆盖旧值）。
 */
public final class EncryptedPrefs implements CredentialStore {

    private static final String PREF = "gzhu_kb";
    private static final String KEY_ALIAS = "gzhu_kb_cred_v1";
    private static final String K_ALIAS_PWD = "pwd_enc";
    private static final String K_ALIAS_IV = "pwd_iv";
    private static final String K_USER = "username";

    private final SharedPreferences sp;

    /** 上一次保存/读取失败的原因，成功时为空串（见 CredentialStore#lastError） */
    private volatile String lastError = "";

    public EncryptedPrefs(Context ctx) {
        sp = ctx.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    @Override
    public String username() {
        return sp.getString(K_USER, "");
    }

    @Override
    public String lastError() {
        return lastError;
    }

    @Override
    public void save(String username, String password) {
        lastError = "";
        // 学号是明文，先同步写下去：即使下面加密失败，也还能回填学号
        if (!sp.edit().putString(K_USER, username).commit()) {
            lastError = "学号写入本地存储失败";
            Diag.e(lastError);
            return;
        }
        try {
            SecretKey key = key();
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key);
            byte[] enc = c.doFinal(password.getBytes(StandardCharsets.UTF_8));
            // 用 commit() 而不是 apply()：凭据必须落盘后才算"记住了"，
            // 否则用户登录完立刻划掉 App，这次保存就可能丢。
            boolean ok = sp.edit()
                .putString(K_ALIAS_PWD, Base64.encodeToString(enc, Base64.NO_WRAP))
                .putString(K_ALIAS_IV, Base64.encodeToString(c.getIV(), Base64.NO_WRAP))
                .commit();
            if (!ok) {
                lastError = "密码写入本地存储失败";
                Diag.e(lastError);
                return;
            }
            // 读回校验：只有真的能解开，才敢说"已记住"
            String back = password();
            if (!password.equals(back)) {
                if (lastError.isEmpty()) lastError = "密码写入后校验失败";
                Diag.e("凭据保存校验不通过: " + lastError);
            } else {
                Diag.d("凭据已加密保存（密码 " + password.length() + " 字符）");
            }
        } catch (Exception e) {
            // 加密不可用时宁可不存密码，也不要明文落盘
            lastError = "无法加密保存密码（" + e.getClass().getSimpleName() + "）";
            Diag.e(lastError, e);
            clearPassword();
        }
    }

    @Override
    public String password() {
        String enc = sp.getString(K_ALIAS_PWD, null);
        String iv = sp.getString(K_ALIAS_IV, null);
        if (enc == null || iv == null) return "";
        try {
            SecretKey key = key();
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)));
            byte[] plain = c.doFinal(Base64.decode(enc, Base64.NO_WRAP));
            lastError = "";
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            // 关键改动：**不要**在这里 clearPassword()。
            // 一次瞬时失败就把已保存的密码删掉，正是"经常要重新手动登录"的来源。
            // 真解不开时用户重新登录会用新密文覆盖，没必要现在销毁。
            lastError = "无法解密已保存的密码（" + e.getClass().getSimpleName() + "）";
            Diag.e(lastError, e);
            return "";
        }
    }

    @Override
    public boolean hasPassword() {
        return sp.contains(K_ALIAS_PWD) && sp.contains(K_ALIAS_IV);
    }

    @Override
    public void clearPassword() {
        sp.edit().remove(K_ALIAS_PWD).remove(K_ALIAS_IV).commit();
    }

    public void clearAll() {
        sp.edit().clear().commit();
    }

    /**
     * 清掉 Keystore 里的主密钥。
     *
     * 仅在"密码确实解不开"时才需要（比如密钥被系统作废）。删掉后下次保存会
     * 重新生成一把新密钥，旧密文自然解不开也无所谓。
     */
    public void resetKey() {
        try {
            KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            if (ks.containsAlias(KEY_ALIAS)) ks.deleteEntry(KEY_ALIAS);
            Diag.d("已删除 Keystore 主密钥: " + KEY_ALIAS);
        } catch (Exception e) {
            Diag.e("删除 Keystore 主密钥失败", e);
        }
    }

    private static SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(KEY_ALIAS)) {
            return (SecretKey) ks.getKey(KEY_ALIAS, null);
        }
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        kg.init(new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return kg.generateKey();
    }
}
