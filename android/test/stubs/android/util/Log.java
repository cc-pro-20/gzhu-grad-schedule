package android.util;

/** 测试桩：android.util.Log */
public final class Log {
    public static boolean verbose = false;

    public static int v(String tag, String msg) { return log("V", tag, msg); }
    public static int d(String tag, String msg) { return log("D", tag, msg); }
    public static int i(String tag, String msg) { return log("I", tag, msg); }
    public static int w(String tag, String msg) { return log("W", tag, msg); }
    public static int e(String tag, String msg) { return log("E", tag, msg); }
    public static int e(String tag, String msg, Throwable t) { return log("E", tag, msg + " / " + t); }

    private static int log(String level, String tag, String msg) {
        if (verbose) System.out.println("  [" + level + "/" + tag + "] " + msg);
        return 0;
    }
}
