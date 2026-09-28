package cn.edu.gzhu.kb;

import android.util.Log;

/**
 * 原生侧统一日志。
 *
 * 真机出问题时往往拿不到堆栈，所以约定：诊断信息统一用 "GzhuKB" 这个 tag，
 * 连上电脑后 `adb logcat -s GzhuKB` 就能过滤出来。
 */
public final class Diag {

    public static final String TAG = "GzhuKB";

    private Diag() {}

    public static void d(String msg) {
        Log.d(TAG, msg);
    }

    public static void w(String msg) {
        Log.w(TAG, msg);
    }

    public static void e(String msg) {
        Log.e(TAG, msg);
    }

    public static void e(String msg, Throwable t) {
        Log.e(TAG, msg + " / " + t);
    }
}
