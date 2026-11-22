package com.bluemind.app;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.widget.RemoteViews;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class BluemindWidget extends AppWidgetProvider {
    private static final String ACTION_MIDNIGHT = "com.bluemind.app.action.WIDGET_MIDNIGHT";
    private static final ExecutorService UPDATES = Executors.newSingleThreadExecutor();

    public static boolean requestPin(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        return manager.isRequestPinAppWidgetSupported()
                && manager.requestPinAppWidget(provider(context), null, null);
    }

    public static void refresh(Context context) {
        enqueue(context, null);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (ACTION_MIDNIGHT.equals(action)
                || Intent.ACTION_TIME_CHANGED.equals(action)
                || Intent.ACTION_TIMEZONE_CHANGED.equals(action)
                || Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            enqueue(context, goAsync());
        } else {
            super.onReceive(context, intent);
        }
    }

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] appWidgetIds) {
        enqueue(context, goAsync());
    }

    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager manager,
                                          int appWidgetId, Bundle newOptions) {
        enqueue(context, goAsync());
    }

    @Override
    public void onDisabled(Context context) {
        enqueue(context, goAsync());
    }

    private static void enqueue(Context context, PendingResult result) {
        Context app = context.getApplicationContext();
        UPDATES.execute(() -> {
            try {
                updateWidgets(app);
            } catch (RuntimeException failure) {
                Log.w("BluemindWidget", "Widget update failed", failure);
            } finally {
                if (result != null) {
                    result.finish();
                }
            }
        });
    }

    private static void updateWidgets(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        int[] widgetIds = manager.getAppWidgetIds(provider(context));
        AlarmManager alarms = context.getSystemService(AlarmManager.class);
        PendingIntent midnight = PendingIntent.getBroadcast(context, 0,
                new Intent(context, BluemindWidget.class).setAction(ACTION_MIDNIGHT),
                PendingIntent.FLAG_IMMUTABLE);
        if (widgetIds.length == 0) {
            alarms.cancel(midnight);
            midnight.cancel();
            return;
        }

        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = LocalDate.now(zone);
        long start = today.atStartOfDay(zone).toInstant().toEpochMilli();
        long nextDay = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextDay, midnight);

        MindStore store = MindStore.get(context);
        MindStore.ColorTheme theme = store.colorTheme();
        BlueTheme colors = new BlueTheme(context, theme);
        int background = switch (theme) {
            case RAIN -> R.drawable.widget_rain_background;
            case DEEP_OCEAN -> R.drawable.widget_ocean_background;
            case MOONLIGHT -> R.drawable.widget_moon_background;
        };
        List<UsageVisit> visits = store.visits(start, nextDay);
        long durationMs = 0;
        for (UsageVisit visit : visits) {
            durationMs += visit.durationMs;
        }
        String count = context.getString(R.string.widget_today_count, visits.size());
        long minutes = durationMs / 60_000;
        PendingIntent open = PendingIntent.getActivity(context, 0,
                new Intent(context, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE);
        for (int id : widgetIds) {
            Bundle options = manager.getAppWidgetOptions(id);
            int width = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH);
            int height = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT);
            boolean compact = (width > 0 && width < 125) || (height > 0 && height < 70);
            String duration = minutes < 60 || compact || (width > 0 && width < 157)
                    ? context.getString(R.string.widget_duration_minutes, minutes)
                    : context.getString(R.string.widget_duration_hours, minutes / 60, minutes % 60);
            RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.bluemind_widget);
            views.setInt(R.id.widget_root, "setBackgroundResource", background);
            views.setTextColor(R.id.widget_brand, colors.muted);
            views.setTextColor(R.id.widget_count, colors.accent);
            views.setTextColor(R.id.widget_duration, colors.ink);
            views.setTextViewText(R.id.widget_count, count);
            views.setTextViewText(R.id.widget_duration, duration);
            views.setTextViewTextSize(R.id.widget_count, TypedValue.COMPLEX_UNIT_SP, compact ? 19 : 25);
            views.setViewVisibility(R.id.widget_brand, compact ? View.GONE : View.VISIBLE);
            views.setContentDescription(R.id.widget_root,
                    context.getString(R.string.widget_today_accessibility, count, duration));
            views.setOnClickPendingIntent(R.id.widget_root, open);
            manager.updateAppWidget(id, views);
        }
    }

    private static ComponentName provider(Context context) {
        return new ComponentName(context, BluemindWidget.class);
    }
}
