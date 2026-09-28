package cn.edu.gzhu.kb;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.util.Calendar;

/**
 * 小组件的定时刷新。
 *
 * 不用 appwidget-provider 的 updatePeriodMillis：系统最短只允许 30 分钟，
 * 而且不保证准时。这里用 AlarmManager 自己排：
 *   - 每天 06:30 一次（覆盖"今天上什么课"的变化）
 *   - 之后每 3 小时一次（兜底，防止缓存跨天）
 * 另外 App 每次打开也会顺手刷新一次，所以实际几乎感觉不到延迟。
 */
public final class WidgetScheduler {

    private static final int REQ = 1001;

    private WidgetScheduler() {}

    public static void schedule(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        long at = nextTrigger();
        PendingIntent pi = pending(ctx);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setAndAllowWhileIdle(AlarmManager.RTC, at, pi);
            } else {
                am.set(AlarmManager.RTC, at, pi);
            }
        } catch (Exception ignored) {
        }
    }

    public static void cancel(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        try {
            am.cancel(pending(ctx));
        } catch (Exception ignored) {
        }
    }

    private static PendingIntent pending(Context ctx) {
        Intent i = new Intent(ctx, KbWidgetProvider.class).setAction(KbWidgetProvider.ACTION_REFRESH);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(ctx, REQ, i, flags);
    }

    /** 下一个触发时刻：06:30 / 09:30 / 12:30 … 每 3 小时一档 */
    private static long nextTrigger() {
        Calendar c = Calendar.getInstance();
        Calendar t = Calendar.getInstance();
        t.set(Calendar.SECOND, 0);
        t.set(Calendar.MILLISECOND, 0);
        int[][] slots = { { 6, 30 }, { 9, 30 }, { 12, 30 }, { 15, 30 }, { 18, 30 }, { 21, 30 } };
        for (int[] s : slots) {
            t.set(Calendar.HOUR_OF_DAY, s[0]);
            t.set(Calendar.MINUTE, s[1]);
            if (t.after(c)) return t.getTimeInMillis();
        }
        // 今天都过了，取明天 06:30
        t.add(Calendar.DAY_OF_MONTH, 1);
        t.set(Calendar.HOUR_OF_DAY, 6);
        t.set(Calendar.MINUTE, 30);
        return t.getTimeInMillis();
    }
}
