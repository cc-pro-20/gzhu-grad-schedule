import cn.edu.gzhu.kb.Des;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 校验 Java 版 DES 与站点 des.js 的输出是否逐位一致 */
public class DesCheck {
    public static void main(String[] args) throws Exception {
        String json = new String(Files.readAllBytes(Paths.get(args[0])), StandardCharsets.UTF_8);
        // 极简解析：抓出每组的 un / pd / lt / rsa
        Pattern p = Pattern.compile(
            "\\{\\s*\"un\":\\s*\"((?:[^\"\\\\]|\\\\.)*)\",\\s*\"pd\":\\s*\"((?:[^\"\\\\]|\\\\.)*)\",\\s*\"lt\":\\s*\"((?:[^\"\\\\]|\\\\.)*)\",\\s*\"plain\":\\s*\"(?:[^\"\\\\]|\\\\.)*\",\\s*\"rsa\":\\s*\"([0-9A-F]+)\"",
            Pattern.DOTALL);
        Matcher m = p.matcher(json);
        int total = 0, pass = 0;
        while (m.find()) {
            total++;
            String un = unescape(m.group(1));
            String pd = unescape(m.group(2));
            String lt = unescape(m.group(3));
            String expect = m.group(4);
            String actual = Des.strEnc(un + pd + lt, "1", "2", "3");
            boolean ok = expect.equals(actual);
            if (ok) pass++;
            System.out.println((ok ? "  PASS " : "  FAIL ") + "[" + total + "] un=" + un + " pd=" + pd);
            if (!ok) {
                System.out.println("        expect=" + expect);
                System.out.println("        actual=" + actual);
            }
        }
        System.out.println("\n结果: " + pass + "/" + total + " 通过");
        if (pass != total || total == 0) System.exit(1);
    }

    private static String unescape(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char e = s.charAt(++i);
                switch (e) {
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/'); break;
                    case 'u':
                        if (i + 4 < s.length()) {
                            sb.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                            i += 4;
                        }
                        break;
                    default: sb.append(e);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
