package cn.edu.gzhu.kb;

import java.util.HashMap;
import java.util.Map;

/**
 * CredentialStore / KbStore 的内存实现，仅供测试使用。
 *
 * 放在 app/src 下（而不是 test/）是为了让桌面回归测试可以直接复用，
 * 不引入任何 Android 依赖。生产代码不会用到它。
 */
public final class MemoryStores {

    private MemoryStores() {}

    /** 内存版凭据存储：密码明文放在内存里，仅用于测试 */
    public static final class Creds implements CredentialStore {
        private String user = "";
        private String pass = "";

        @Override
        public String username() { return user; }

        @Override
        public void save(String username, String password) {
            this.user = username == null ? "" : username;
            this.pass = password == null ? "" : password;
        }

        @Override
        public String password() { return pass; }

        @Override
        public boolean hasPassword() { return !pass.isEmpty(); }

        @Override
        public void clearPassword() { pass = ""; }

        /** 内存实现不会失败；测试里可以手动设置它来模拟"存不住"的场景 */
        @Override
        public String lastError() { return err; }

        public void setLastError(String e) { err = e == null ? "" : e; }

        private String err = "";
    }

    /** 内存版 cookie 存储 */
    public static final class Cookies implements KbStore {
        private final Map<String, String> map = new HashMap<>();

        @Override
        public Map<String, String> load() { return new HashMap<>(map); }

        @Override
        public void save(Map<String, String> cookies) {
            map.clear();
            if (cookies != null) map.putAll(cookies);
        }

        @Override
        public void clear() { map.clear(); }
    }
}
