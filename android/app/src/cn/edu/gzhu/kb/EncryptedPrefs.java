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
 */
public final class EncryptedPrefs implements CredentialStore {

    private static final String PREF = "gzhu_kb";
    private static final String KEY_ALIAS = "gzhu_kb_cred_v1";
    private static final String K_ALIAS_PWD = "pwd_enc";
    private static final String K_ALIAS_IV = "pwd_iv";
    private static final String K_USER = "username";

    private final SharedPreferences sp;

    public EncryptedPrefs(Context ctx) {
        sp = ctx.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    @Override
    public String username() {
        return sp.getString(K_USER, "");
    }

    @Override
    public void save(String username, String password) {
        sp.edit().putString(K_USER, username).apply();
        try {
            SecretKey key = key();
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key);
            byte[] enc = c.doFinal(password.getBytes(StandardCharsets.UTF_8));
            sp.edit()
              .putString(K_ALIAS_PWD, Base64.encodeToString(enc, Base64.NO_WRAP))
              .putString(K_ALIAS_IV, Base64.encodeToString(c.getIV(), Base64.NO_WRAP))
              .apply();
        } catch (Exception e) {
            // 加密不可用时宁可不存密码，也不要明文落盘
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
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            clearPassword();
            return "";
        }
    }

    @Override
    public boolean hasPassword() {
        return sp.contains(K_ALIAS_PWD) && sp.contains(K_ALIAS_IV);
    }

    @Override
    public void clearPassword() {
        sp.edit().remove(K_ALIAS_PWD).remove(K_ALIAS_IV).apply();
    }

    public void clearAll() {
        sp.edit().clear().apply();
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
