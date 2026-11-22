package com.bluemind.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;

final class ServiceHealth {
    private static final long HEARTBEAT_INTERVAL_MS = 10_000;
    private static volatile long lastBeatElapsed = -1;

    private ServiceHealth() {
    }

    static boolean isConnected(Context context) {
        long beat = lastBeatElapsed;
        return beat >= 0 && SystemClock.elapsedRealtime() - beat < HEARTBEAT_INTERVAL_MS * 3;
    }

    static long lastHeartbeat(Context context) {
        return preferences(context).getLong("heartbeat", 0);
    }

    static void heartbeat(Context context) {
        long elapsed = SystemClock.elapsedRealtime();
        if (lastBeatElapsed < 0 || elapsed - lastBeatElapsed >= HEARTBEAT_INTERVAL_MS) {
            preferences(context).edit().putLong("heartbeat", System.currentTimeMillis()).apply();
            lastBeatElapsed = elapsed;
        }
    }

    static void scanError(Context context, RuntimeException error) {
        SharedPreferences prefs = preferences(context);
        long now = System.currentTimeMillis();
        String kind = error.getClass().getSimpleName();
        if (!kind.equals(prefs.getString("scan_error_type", ""))
                || now - prefs.getLong("scan_error_at", 0) >= 60_000) {
            prefs.edit().putLong("scan_error_at", now).putString("scan_error_type", kind).apply();
        }
    }

    static long lastScanErrorAt(Context context) {
        return preferences(context).getLong("scan_error_at", 0);
    }

    static String lastScanErrorType(Context context) {
        return preferences(context).getString("scan_error_type", "");
    }

    static void disconnected() {
        lastBeatElapsed = -1;
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences("service_health", Context.MODE_PRIVATE);
    }
}
