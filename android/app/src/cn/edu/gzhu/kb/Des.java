package cn.edu.gzhu.kb;

/**
 * 正方 CAS 登录用的 DES 实现（与校方 des.js 的 strEnc 行为逐位一致）。
 * 用于生成登录表单的 rsa 字段：strEnc(un + pd + lt, "1", "2", "3")
 *
 * 说明：这不是安全意义上的加密（密钥硬编码在网页里），
 * 只是校方前端用来混淆表单字段的算法，服务端按同样规则解密校验。
 */
public final class Des {

    private Des() {}

    // 注意：这两张表是 0-based 索引，且与教科书上的经典 DES 表并非同一套写法，
    // 它们是从校方 des.js 的 pPermute / finallyPermute 逐项提取的，不能凭记忆替换。
    private static final int[] IP_1 = {
        39, 7, 47, 15, 55, 23, 63, 31, 38, 6, 46, 14, 54, 22, 62, 30,
        37, 5, 45, 13, 53, 21, 61, 29, 36, 4, 44, 12, 52, 20, 60, 28,
        35, 3, 43, 11, 51, 19, 59, 27, 34, 2, 42, 10, 50, 18, 58, 26,
        33, 1, 41, 9, 49, 17, 57, 25, 32, 0, 40, 8, 48, 16, 56, 24
    };
    private static final int[] PC_1 = {
        56, 48, 40, 32, 24, 16, 8, 0, 57, 49, 41, 33, 25, 17,
        9, 1, 58, 50, 42, 34, 26, 18, 10, 2, 59, 51, 43, 35,
        27, 19, 11, 3, 60, 52, 44, 36, 28, 20, 12, 4, 61, 53,
        45, 37, 29, 21, 13, 5, 62, 54, 46, 38, 30, 22, 14, 6
    };
    private static final int[] PC_2 = {
        13, 16, 10, 23, 0, 4, 2, 27, 14, 5, 20, 9,
        22, 18, 11, 3, 25, 7, 15, 6, 26, 19, 12, 1,
        40, 51, 30, 36, 46, 54, 29, 39, 50, 44, 32, 47,
        43, 48, 38, 55, 33, 52, 45, 41, 49, 35, 28, 31
    };
    private static final int[] E = {
        31, 0, 1, 2, 3, 4, 3, 4, 5, 6, 7, 8,
        7, 8, 9, 10, 11, 12, 11, 12, 13, 14, 15, 16,
        15, 16, 17, 18, 19, 20, 19, 20, 21, 22, 23, 24,
        23, 24, 25, 26, 27, 28, 27, 28, 29, 30, 31, 0
    };
    private static final int[] P = {
        15, 6, 19, 20, 28, 11, 27, 16, 0, 14, 22, 25, 4, 17, 30, 9,
        1, 7, 23, 13, 31, 26, 2, 8, 18, 12, 29, 5, 21, 10, 3, 24
    };
    private static final int[][][] S_BOX = {
        {
            {14, 4, 13, 1, 2, 15, 11, 8, 3, 10, 6, 12, 5, 9, 0, 7},
            {0, 15, 7, 4, 14, 2, 13, 1, 10, 6, 12, 11, 9, 5, 3, 8},
            {4, 1, 14, 8, 13, 6, 2, 11, 15, 12, 9, 7, 3, 10, 5, 0},
            {15, 12, 8, 2, 4, 9, 1, 7, 5, 11, 3, 14, 10, 0, 6, 13}
        },
        {
            {15, 1, 8, 14, 6, 11, 3, 4, 9, 7, 2, 13, 12, 0, 5, 10},
            {3, 13, 4, 7, 15, 2, 8, 14, 12, 0, 1, 10, 6, 9, 11, 5},
            {0, 14, 7, 11, 10, 4, 13, 1, 5, 8, 12, 6, 9, 3, 2, 15},
            {13, 8, 10, 1, 3, 15, 4, 2, 11, 6, 7, 12, 0, 5, 14, 9}
        },
        {
            {10, 0, 9, 14, 6, 3, 15, 5, 1, 13, 12, 7, 11, 4, 2, 8},
            {13, 7, 0, 9, 3, 4, 6, 10, 2, 8, 5, 14, 12, 11, 15, 1},
            {13, 6, 4, 9, 8, 15, 3, 0, 11, 1, 2, 12, 5, 10, 14, 7},
            {1, 10, 13, 0, 6, 9, 8, 7, 4, 15, 14, 3, 11, 5, 2, 12}
        },
        {
            {7, 13, 14, 3, 0, 6, 9, 10, 1, 2, 8, 5, 11, 12, 4, 15},
            {13, 8, 11, 5, 6, 15, 0, 3, 4, 7, 2, 12, 1, 10, 14, 9},
            {10, 6, 9, 0, 12, 11, 7, 13, 15, 1, 3, 14, 5, 2, 8, 4},
            {3, 15, 0, 6, 10, 1, 13, 8, 9, 4, 5, 11, 12, 7, 2, 14}
        },
        {
            {2, 12, 4, 1, 7, 10, 11, 6, 8, 5, 3, 15, 13, 0, 14, 9},
            {14, 11, 2, 12, 4, 7, 13, 1, 5, 0, 15, 10, 3, 9, 8, 6},
            {4, 2, 1, 11, 10, 13, 7, 8, 15, 9, 12, 5, 6, 3, 0, 14},
            {11, 8, 12, 7, 1, 14, 2, 13, 6, 15, 0, 9, 10, 4, 5, 3}
        },
        {
            {12, 1, 10, 15, 9, 2, 6, 8, 0, 13, 3, 4, 14, 7, 5, 11},
            {10, 15, 4, 2, 7, 12, 9, 5, 6, 1, 13, 14, 0, 11, 3, 8},
            {9, 14, 15, 5, 2, 8, 12, 3, 7, 0, 4, 10, 1, 13, 11, 6},
            {4, 3, 2, 12, 9, 5, 15, 10, 11, 14, 1, 7, 6, 0, 8, 13}
        },
        {
            {4, 11, 2, 14, 15, 0, 8, 13, 3, 12, 9, 7, 5, 10, 6, 1},
            {13, 0, 11, 7, 4, 9, 1, 10, 14, 3, 5, 12, 2, 15, 8, 6},
            {1, 4, 11, 13, 12, 3, 7, 14, 10, 15, 6, 8, 0, 5, 9, 2},
            {6, 11, 13, 8, 1, 4, 10, 7, 9, 5, 0, 15, 14, 2, 3, 12}
        },
        {
            {13, 2, 8, 4, 6, 15, 11, 1, 10, 9, 3, 14, 5, 0, 12, 7},
            {1, 15, 13, 8, 10, 3, 7, 4, 12, 5, 6, 11, 0, 14, 9, 2},
            {7, 11, 4, 1, 9, 12, 14, 2, 0, 6, 10, 13, 15, 3, 5, 8},
            {2, 1, 14, 7, 4, 10, 8, 13, 15, 12, 9, 0, 3, 5, 6, 11}
        }
    };

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    public static String strEnc(String data, String firstKey, String secondKey, String thirdKey) {
        int leng = data.length();
        if (leng == 0) return "";
        byte[][] firstKeyBt = getKeyBytes(firstKey);
        byte[][] secondKeyBt = getKeyBytes(secondKey);
        byte[][] thirdKeyBt = getKeyBytes(thirdKey);

        StringBuilder encData = new StringBuilder();
        if (leng < 4) {
            byte[] bt = strToBt(data);
            if (firstKeyBt != null && secondKeyBt != null && thirdKeyBt != null) {
                for (byte[] k1 : firstKeyBt)
                    for (byte[] k2 : secondKeyBt)
                        for (byte[] k3 : thirdKeyBt) {
                            byte[] tempBt = bt;
                            tempBt = enc(tempBt, k1);
                            tempBt = enc(tempBt, k2);
                            tempBt = enc(tempBt, k3);
                            encData.append(bt64ToHex(tempBt));
                        }
            }
        } else {
            int iterator = leng / 4;
            int remainder = leng % 4;
            for (int i = 0; i < iterator; i++) {
                String chunk = data.substring(i * 4, i * 4 + 4);
                byte[] tempBt = strToBt(chunk);
                for (byte[] k1 : firstKeyBt)
                    for (byte[] k2 : secondKeyBt)
                        for (byte[] k3 : thirdKeyBt) {
                            byte[] t = tempBt;
                            t = enc(t, k1);
                            t = enc(t, k2);
                            t = enc(t, k3);
                            encData.append(bt64ToHex(t));
                        }
            }
            if (remainder > 0) {
                String chunk = data.substring(iterator * 4);
                byte[] tempBt = strToBt(chunk);
                for (byte[] k1 : firstKeyBt)
                    for (byte[] k2 : secondKeyBt)
                        for (byte[] k3 : thirdKeyBt) {
                            byte[] t = tempBt;
                            t = enc(t, k1);
                            t = enc(t, k2);
                            t = enc(t, k3);
                            encData.append(bt64ToHex(t));
                        }
            }
        }
        return encData.toString();
    }

    /** 把密钥字符串切成 8 字节（64 位）一块 */
    private static byte[][] getKeyBytes(String key) {
        if (key == null || key.isEmpty()) return null;
        int leng = key.length();
        int iterator = leng / 4;
        int remainder = leng % 4;
        int total = iterator + (remainder > 0 ? 1 : 0);
        byte[][] keyBytes = new byte[total][];
        for (int i = 0; i < iterator; i++) {
            keyBytes[i] = strToBt(key.substring(i * 4, i * 4 + 4));
        }
        if (remainder > 0) {
            keyBytes[iterator] = strToBt(key.substring(iterator * 4));
        }
        return keyBytes;
    }

    /** 字符串 -> 64 位（不足补 0） */
    private static byte[] strToBt(String str) {
        int leng = str.length();
        byte[] bt = new byte[64];
        if (leng < 4) {
            for (int i = 0; i < leng; i++) {
                int k = str.charAt(i);
                for (int j = 0; j < 16; j++) {
                    int pow = 1;
                    for (int m = 15; m > j; m--) pow *= 2;
                    bt[16 * i + j] = (byte) (k / pow % 2);
                }
            }
        } else {
            for (int p = 0; p < 4; p++) {
                int k = str.charAt(p);
                for (int q = 0; q < 16; q++) {
                    int pow = 1;
                    for (int m = 15; m > q; m--) pow *= 2;
                    bt[16 * p + q] = (byte) (k / pow % 2);
                }
            }
        }
        return bt;
    }

    /**
     * 64 位 -> 16 个十六进制字符。
     * 注意：每 4 位对应 1 个十六进制字符，不是 2 个。
     */
    private static String bt64ToHex(byte[] byteData) {
        StringBuilder hex = new StringBuilder();
        for (int i = 0; i < 16; i++) {
            StringBuilder part = new StringBuilder();
            for (int j = 0; j < 4; j++) part.append(byteData[i * 4 + j]);
            int val = Integer.parseInt(part.toString(), 2);
            hex.append(HEX[val]);
        }
        return hex.toString();
    }

    /**
     * 初始置换 IP。des.js 的 initPermute 是手写循环而非位表，这里等价实现：
     *   ip[8i + k]      = data[8*(7-k) + (2i+1)]
     *   ip[8i + k + 32] = data[8*(7-k) + 2i]
     */
    private static byte[] initPermute(byte[] d) {
        byte[] out = new byte[64];
        int m = 1, n = 0;
        for (int i = 0; i < 4; i++, m += 2, n += 2) {
            for (int j = 7, k = 0; j >= 0; j--, k++) {
                out[i * 8 + k] = d[j * 8 + m];
                out[i * 8 + k + 32] = d[j * 8 + n];
            }
        }
        return out;
    }

    /**
     * 单块加密，严格对齐 des.js 的结构：
     *   ipLeft = L, ipRight = R
     *   每轮：tempLeft = L; L = R; R = P(S(E(R) ^ key)) ^ tempLeft
     *   最后：finalData = R || L，再做 finallyPermute
     */
    private static byte[] enc(byte[] data, byte[] key) {
        byte[][] keys = getKey(key);
        byte[] ip = initPermute(data);
        byte[] left = subArray(ip, 0, 32);
        byte[] right = subArray(ip, 32, 64);
        for (int i = 0; i < 16; i++) {
            byte[] tempLeft = left;
            left = right;
            byte[] tempRight = xor(f(right, keys[i]), tempLeft);
            right = tempRight;
        }
        return permutation0(concat(right, left), IP_1, 64);
    }

    /** 一轮的 f 函数 */
    private static byte[] f(byte[] a, byte[] key) {
        byte[] t = permutation0(a, E, 48);
        byte[] x = xor(t, key);
        byte[][] boxResult = new byte[8][4];
        for (int i = 0; i < 8; i++) {
            int row = Integer.parseInt("" + x[i * 6] + x[i * 6 + 5], 2);
            int col = Integer.parseInt("" + x[i * 6 + 1] + x[i * 6 + 2] + x[i * 6 + 3] + x[i * 6 + 4], 2);
            int val = S_BOX[i][row][col];
            boxResult[i][0] = (byte) (val / 8 % 2);
            boxResult[i][1] = (byte) (val / 4 % 2);
            boxResult[i][2] = (byte) (val / 2 % 2);
            boxResult[i][3] = (byte) (val % 2);
        }
        byte[] flatten = new byte[32];
        for (int i = 0; i < 8; i++) System.arraycopy(boxResult[i], 0, flatten, i * 4, 4);
        return permutation0(flatten, P, 32);
    }

    /** 由 64 位密钥生成 16 个 48 位子密钥 */
    private static byte[][] getKey(byte[] key) {
        byte[] k = permutation0(key, PC_1, 56);
        byte[] left = subArray(k, 0, 28);
        byte[] right = subArray(k, 28, 56);
        byte[][] keyArray = new byte[16][];
        for (int i = 0; i < 16; i++) {
            int shift = (i == 0 || i == 1 || i == 8 || i == 15) ? 1 : 2;
            left = rotate(left, shift);
            right = rotate(right, shift);
            keyArray[i] = permutation0(concat(left, right), PC_2, 48);
        }
        return keyArray;
    }

    private static byte[] rotate(byte[] arr, int n) {
        int len = arr.length;
        byte[] out = new byte[len];
        for (int i = 0; i < len; i++) out[i] = arr[(i + n) % len];
        return out;
    }

    private static byte[] permutation(byte[] src, int[] table, int outLen) {
        byte[] out = new byte[outLen];
        for (int i = 0; i < outLen; i++) out[i] = src[table[i] - 1];
        return out;
    }

    /** 表本身已是 0-based 索引时使用 */
    private static byte[] permutation0(byte[] src, int[] table, int outLen) {
        byte[] out = new byte[outLen];
        for (int i = 0; i < outLen; i++) out[i] = src[table[i]];
        return out;
    }

    private static byte[] xor(byte[] a, byte[] b) {
        int len = Math.min(a.length, b.length);
        byte[] out = new byte[len];
        for (int i = 0; i < len; i++) out[i] = (byte) (a[i] ^ b[i]);
        return out;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static byte[] subArray(byte[] src, int from, int to) {
        byte[] out = new byte[to - from];
        System.arraycopy(src, from, out, 0, to - from);
        return out;
    }
}
