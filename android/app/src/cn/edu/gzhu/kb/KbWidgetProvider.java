package cn.edu.gzhu.kb;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.widget.RemoteViews;

import java.util.Calendar;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 桌面小组件：整周课表缩略图。
 *
 * 取数顺序：今天的缓存 -> 联网（失败则退回旧缓存并提示）。
 * 所有联网都在后台线程做，绝不阻塞小组件的更新回调。
 */
public class KbWidgetProvider extends AppWidgetProvider {

    private static final String TAG = "KbWidget";
    private static final ExecutorService POOL = Executors.newSingleThreadExecutor();

    public static final String ACTION_REFRESH = "cn.edu.gzhu.kb.WIDGET_REFRESH";

    @Override
    public void onUpdate(Context ctx, AppWidgetManager mgr, int[] ids) {
        for (int id : ids) render(ctx, mgr, id);
    }

    @Override
    public void onAppWidgetOptionsChanged(Context ctx, AppWidgetManager mgr, int id, Bundle newOptions) {
        // 用户缩放/改尺寸时重新排版
        render(ctx, mgr, id);
    }

    @Override
    public void onReceive(Context ctx, Intent intent) {
        super.onReceive(ctx, intent);
        if (ACTION_REFRESH.equals(intent.getAction())) {
            AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
            int[] ids = mgr.getAppWidgetIds(new ComponentName(ctx, KbWidgetProvider.class));
            for (int id : ids) render(ctx, mgr, id);
        }
    }

    @Override
    public void onDeleted(Context ctx, int[] ids) {
        // 最后一个小组件被移除时，顺手取消定时刷新
        AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
        if (mgr.getAppWidgetIds(new ComponentName(ctx, KbWidgetProvider.class)).length == 0) {
            WidgetScheduler.cancel(ctx);
        }
    }

    /** 立即刷新所有小组件（登录后、退出登录后、打开 App 时调用） */
    public static void updateAll(Context ctx) {
        AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
        int[] ids = mgr.getAppWidgetIds(new ComponentName(ctx, KbWidgetProvider.class));
        for (int id : ids) render(ctx, mgr, id);
    }

    // ------------------------------------------------------------ 渲染

    private static void render(Context ctx, AppWidgetManager mgr, int id) {
        final Context app = ctx.getApplicationContext();
        POOL.execute(() -> {
            try {
                KbRepository repo = AndroidRepo.create(app);
                Map<String, Object> data = repo.loadCachedData();
                String note = null;

                if (data == null) {
                    if (!repo.hasCredentials()) {
                        note = "请先打开 App 登录";
                    } else {
                        try {
                            data = repo.kb(null, 0, false);
                            repo.saveCache(data);
                        } catch (Exception e) {
                            Log.w(TAG, "首次取数失败: " + e.getMessage());
                            note = "读取失败，点一下重试";
                        }
                    }
                } else if (!repo.cacheIsFreshToday() && repo.hasCredentials()) {
                    // 缓存是昨天的：先保留旧数据，再尝试更新
                    try {
                        Map<String, Object> fresh = repo.kb(null, 0, false);
                        repo.saveCache(fresh);
                        data = fresh;
                    } catch (Exception e) {
                        Log.w(TAG, "刷新失败，继续用旧缓存: " + e.getMessage());
                        note = "数据可能不是最新";
                    }
                }

                Bitmap bmp = null;
                if (data != null) {
                    int[] size = widgetSize(app, mgr, id);
                    bmp = WidgetRender.render(data, size[0], size[1], todayXq(data));
                }
                push(app, mgr, id, bmp, data, note);
            } catch (Exception e) {
                Log.e(TAG, "渲染异常: " + e.getMessage());
            }
        });
    }

    /** 取小组件当前实际像素尺寸，并按 RemoteViews 的 Bitmap 体积上限收敛 */
    private static int[] widgetSize(Context ctx, AppWidgetManager mgr, int id) {
        Bundle o = mgr.getAppWidgetOptions(id);
        int minW = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250);
        int maxH = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 200);
        float d = ctx.getResources().getDisplayMetrics().density;
        int w = Math.round(minW * d);
        int h = Math.round(maxH * d);
        w = Math.max(240, Math.min(w, 1200));
        h = Math.max(160, Math.min(h, 900));
        return new int[] { w, h };
    }

    /** 只有正在看"当前周"时才高亮今天 */
    private static int todayXq(Map<String, Object> data) {
        if (data == null) return 0;
        int week = School.num(data.get("week"));
        int cur = School.num(data.get("curWeek"));
        if (week != cur) return 0;
        // Calendar: 1=周日, 2=周一 ... 7=周六  ->  转成 1=周一 ... 7=周日
        int dow = Calendar.getInstance().get(Calendar.DAY_OF_WEEK);
        return (dow + 5) % 7 + 1;
    }

    private static void push(Context ctx, AppWidgetManager mgr, int id, Bitmap bmp,
                             Map<String, Object> data, String note) {
        RemoteViews v = new RemoteViews(ctx.getPackageName(), R.layout.widget_kb);

        if (bmp != null) {
            v.setImageViewBitmap(R.id.widget_image, bmp);
            v.setViewVisibility(R.id.widget_image, android.view.View.VISIBLE);
            v.setViewVisibility(R.id.widget_msg, android.view.View.GONE);
        } else {
            v.setViewVisibility(R.id.widget_image, android.view.View.GONE);
            v.setViewVisibility(R.id.widget_msg, android.view.View.VISIBLE);
            v.setTextViewText(R.id.widget_msg, note == null ? "暂无课表数据" : note);
        }

        String title = "研究生课表";
        if (data != null) {
            title = "第 " + School.num(data.get("week")) + " 周";
            if (note != null) title = title + " · " + note;
        }
        v.setTextViewText(R.id.widget_title, title);
        v.setViewVisibility(R.id.widget_title, android.view.View.VISIBLE);

        // 点整块 -> 打开 App
        Intent open = new Intent(ctx, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flag = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flag |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent piOpen = PendingIntent.getActivity(ctx, 0, open, flag);
        v.setOnClickPendingIntent(R.id.widget_root, piOpen);

        // 点"刷新" -> 重新取数
        Intent refresh = new Intent(ctx, KbWidgetProvider.class).setAction(ACTION_REFRESH);
        PendingIntent piRefresh = PendingIntent.getBroadcast(ctx, 1, refresh, flag);
        v.setOnClickPendingIntent(R.id.widget_refresh, piRefresh);

        mgr.updateAppWidget(id, v);
    }
}
