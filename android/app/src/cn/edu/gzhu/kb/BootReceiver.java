package cn.edu.gzhu.kb;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 开机/更新后重新排定小组件的刷新闹钟。
 * 系统重启会清掉 AlarmManager 里的闹钟，不重新排的话小组件就不再自动更新了。
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context ctx, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
            || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
            || "android.intent.action.QUICKBOOT_POWERON".equals(action)) {
            Context app = ctx.getApplicationContext();
            WidgetScheduler.schedule(app);
            KbWidgetProvider.updateAll(app);
        }
    }
}
