package com.bluemind.app;

import android.Manifest;
import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Path;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import android.view.Display;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.Toast;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;

public final class MindAccessibilityService extends AccessibilityService {
    private static final Platform[] PLATFORMS = Platform.values();
    private static final String TAG = "Bluemind";
    private static final String REMINDER_CHANNEL = "viewing_reminders";
    private static final long MIN_SCAN_GAP_MS = 95;
    private static final long ACTIVE_SCAN_MS = 310;
    private static final long IDLE_SCAN_MS = 890;
    private static final long SLEEP_SCAN_MS = 3_200;
    private static final long COUNTER_REFRESH_MS = 600;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable scanner = this::runScan;
    private final BroadcastReceiver displayChanges = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            try {
                if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                    clearScreen();
                }
            } catch (RuntimeException exception) {
                scanFailed(exception);
            }
            requestScan(0);
        }
    };
    private MindStore store;
    private ViewingSession session;
    private OverlayViews overlays;
    private PlaybackProbe playbackProbe;
    private WindowManager windows;
    private PowerManager power;
    private KeyguardManager keyguard;
    private boolean connected;
    private boolean registeredDisplayReceiver;
    private long scanAt = Long.MAX_VALUE;
    private long lastScan;
    private long scanFailureLogAt = -60_000;
    private long widgetTimeRefresh;
    private boolean forwardingGesture;
    private int counterCount;
    private long counterTime = -1;
    private LocalDate counterDate;
    private String foregroundPackage = "";
    private int foregroundWindow = -1;
    private UiNode shortPager;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        if (connected) {
            stopWatching();
        }
        store = MindStore.get(this);
        windows = getSystemService(WindowManager.class);
        power = getSystemService(PowerManager.class);
        keyguard = getSystemService(KeyguardManager.class);
        overlays = new OverlayViews(this, new OverlayViews.Actions() {
            @Override
            public void choose(String key, float x, float y) {
                main.post(() -> chooseCover(key, x, y));
            }

            @Override
            public void scroll(String key, boolean down) {
                main.post(() -> scrollCoveredList(key, down));
            }
        });
        playbackProbe = new PlaybackProbe(this);
        session = new ViewingSession(new ViewingSession.Usage() {
            @Override
            public boolean blocked(long wallTime) {
                return store.isBlocked(wallTime);
            }

            @Override
            public long admit(Platform platform, ContentTarget.Kind kind, long wallTime) {
                MindStore.Admission admission = store.admit(platform, kind, wallTime);
                if (admission.visitId != 0) {
                    counterTime = -1;
                    BluemindWidget.refresh(MindAccessibilityService.this);
                    for (Rule rule : admission.reminders) {
                        showReminder(rule);
                    }
                }
                return admission.visitId;
            }

            @Override
            public void addDuration(long visitId, long duration) {
                store.addDuration(visitId, duration);
                long now = SystemClock.elapsedRealtime();
                if (now - widgetTimeRefresh >= 30_000) {
                    widgetTimeRefresh = now;
                    BluemindWidget.refresh(MindAccessibilityService.this);
                }
            }
        });
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        filter.addAction(Intent.ACTION_TIME_CHANGED);
        filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(displayChanges, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(displayChanges, filter);
        }
        registeredDisplayReceiver = true;
        connected = true;
        ServiceHealth.heartbeat(this);
        requestScan(0);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (!connected || event == null) {
            return;
        }
        String sourcePackage = string(event.getPackageName());
        int type = event.getEventType();
        if (sourcePackage.equals(getPackageName()) && type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            return;
        }
        try {
            if (sourcePackage.equals(foregroundPackage) && event.getWindowId() == foregroundWindow) {
                if (type == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
                    observePager(event, sourcePackage);
                } else if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                        && string(event.getClassName()).endsWith("Activity")) {
                    session.routeChanged();
                }
            }
        } catch (RuntimeException exception) {
            scanFailed(exception);
        }
        boolean windowChange = type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || type == AccessibilityEvent.TYPE_WINDOWS_CHANGED;
        if (windowChange || lookupPlatform(sourcePackage) != null) {
            requestScan(windowChange ? 0 : 35);
        }
    }

    @Override
    public void onInterrupt() {
        requestScan(0);
    }

    @Override
    public boolean onUnbind(Intent intent) {
        stopWatching();
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        stopWatching();
        super.onDestroy();
    }

    private void observePager(AccessibilityEvent event, String sourcePackage) {
        int first = event.getFromIndex();
        int last = event.getToIndex();
        int settledIndex = first == last ? first : -1;
        AccessibilityNodeInfo source = event.getSource();
        if (source == null) {
            return;
        }
        try {
            String id = string(source.getViewIdResourceName());
            String className = string(source.getClassName());
            if (ContentDetector.isShortPager(lookupPlatform(sourcePackage), id, className)
                    || matchesDetectedPager(source, id, className)) {
                boolean changedOffset = Build.VERSION.SDK_INT >= 28
                        && (Math.abs(event.getScrollDeltaY()) > 1 || Math.abs(event.getScrollDeltaX()) > 1);
                boolean moved = changedOffset || first >= 0 && last > first;
                session.onPagerEvent(sourcePackage, event.getWindowId(), settledIndex,
                        moved, SystemClock.elapsedRealtime());
            }
        } finally {
            ScreenSnapshot.recycle(source);
        }
    }

    private boolean matchesDetectedPager(AccessibilityNodeInfo source, String id, String className) {
        if (shortPager == null || !className.contains("ViewPager")
                || !shortPager.id.equals(id) || !shortPager.className.equals(className)) {
            return false;
        }
        Rect current = new Rect();
        source.getBoundsInScreen(current);
        return Rect.intersects(current, ScreenSnapshot.bounds(shortPager));
    }

    private void requestScan(long delay) {
        if (!connected) {
            return;
        }
        long when = Math.max(SystemClock.uptimeMillis() + delay, lastScan + MIN_SCAN_GAP_MS);
        if (when < scanAt) {
            main.removeCallbacks(scanner);
            scanAt = when;
            main.postAtTime(scanner, when);
        }
    }

    private void runScan() {
        scanAt = Long.MAX_VALUE;
        if (!connected) {
            return;
        }
        long interval = IDLE_SCAN_MS;
        try {
            ServiceHealth.heartbeat(this);
            if (!forwardingGesture) {
                try (Frame frame = readFrame()) {
                    if (frame != null) {
                        showFrame(frame);
                        interval = ACTIVE_SCAN_MS;
                    } else {
                        clearScreen();
                        if (power != null && !power.isInteractive()) {
                            interval = SLEEP_SCAN_MS;
                        }
                    }
                }
            } else {
                interval = MIN_SCAN_GAP_MS;
            }
        } catch (RuntimeException exception) {
            scanFailed(exception);
        } finally {
            lastScan = SystemClock.uptimeMillis();
            requestScan(interval);
        }
    }

    private Frame readFrame() {
        if (!store.isEnabled() || (power != null && !power.isInteractive())
                || (keyguard != null && keyguard.isKeyguardLocked())) {
            return null;
        }
        AccessibilityNodeInfo root = activeApplicationRoot();
        if (root == null) {
            return null;
        }
        try {
            String packageName = string(root.getPackageName());
            Platform platform = lookupPlatform(packageName);
            if (platform == null) {
                return null;
            }
            Rect display = displayBounds();
            Rect viewport = new Rect(display);
            AccessibilityWindowInfo window = root.getWindow();
            if (window != null) {
                try {
                    Rect region = new Rect();
                    window.getBoundsInScreen(region);
                    if (!region.isEmpty() && !viewport.intersect(region)) {
                        return null;
                    }
                } finally {
                    recycleWindow(window);
                }
            }
            Rect contentArea = aboveKeyboard(viewport);
            UiNode hierarchy = ScreenSnapshot.capture(root, contentArea);
            List<ContentTarget> targets = ContentDetector.detect(platform, hierarchy,
                    contentArea.left, contentArea.top, contentArea.right, contentArea.bottom);
            Frame frame = new Frame(root, platform, packageName, contentArea, viewport, targets);
            root = null;
            return frame;
        } finally {
            ScreenSnapshot.recycle(root);
        }
    }

    private AccessibilityNodeInfo activeApplicationRoot() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null && lookupPlatform(string(root.getPackageName())) != null) {
            return root;
        }
        int foregroundId = root == null ? -1 : root.getWindowId();
        ScreenSnapshot.recycle(root);
        List<AccessibilityWindowInfo> visibleWindows = getWindows();
        try {
            boolean checkFocusedApplication = foregroundId == -1;
            for (AccessibilityWindowInfo window : visibleWindows) {
                if (window.getId() == foregroundId) {
                    checkFocusedApplication = window.getType() == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY
                            || window.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD;
                    break;
                }
            }
            if (checkFocusedApplication) {
                for (AccessibilityWindowInfo window : visibleWindows) {
                    if (window.getType() == AccessibilityWindowInfo.TYPE_APPLICATION && window.isFocused()) {
                        return window.getRoot();
                    }
                }
            }
            return null;
        } finally {
            for (AccessibilityWindowInfo window : visibleWindows) {
                recycleWindow(window);
            }
        }
    }

    private Rect aboveKeyboard(Rect windowBounds) {
        Rect visible = new Rect(windowBounds);
        // Avoid overlaying the keyboard when search or a conversation field is active.
        List<AccessibilityWindowInfo> listedWindows = getWindows();
        try {
            for (AccessibilityWindowInfo window : listedWindows) {
                if (window.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                    Rect bounds = new Rect();
                    window.getBoundsInScreen(bounds);
                    if (bounds.top > visible.top && Rect.intersects(visible, bounds)) {
                        visible.bottom = Math.min(visible.bottom, bounds.top);
                    }
                }
            }
        } finally {
            for (AccessibilityWindowInfo window : listedWindows) {
                recycleWindow(window);
            }
        }
        return visible;
    }

    private List<ViewingSession.Cover> showFrame(Frame frame) {
        boolean sameWindow = frame.windowId == foregroundWindow && frame.packageName.equals(foregroundPackage);
        foregroundPackage = frame.packageName;
        foregroundWindow = frame.windowId;
        if (!sameWindow) {
            shortPager = null;
        }
        if (!frame.targets.isEmpty()) {
            ContentTarget first = frame.targets.get(0);
            if (first.surface == ContentTarget.Surface.SHORT_VIEWER) {
                shortPager = first.bounds;
            } else if (frame.platform != Platform.TIKTOK || first.surface != ContentTarget.Surface.POST_DETAIL) {
                shortPager = null;
            }
        }
        long elapsed = SystemClock.elapsedRealtime();
        long wallTime = System.currentTimeMillis();
        List<ViewingSession.Cover> covers = session.present(frame.platform, frame.packageName, frame.windowId,
                frame.targets, elapsed, wallTime);
        overlays.showMasks(covers, frame.viewport);
        playbackProbe.observe(session.probe(frame.targets), frame.windowId, frame.windowBounds, () -> requestScan(0));
        if (store.isCounterVisible()) {
            updateCounter(frame.viewport, elapsed, wallTime);
        } else {
            overlays.removeCounter();
        }
        return covers;
    }

    private void updateCounter(Rect viewport, long elapsed, long wallTime) {
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = Instant.ofEpochMilli(wallTime).atZone(zone).toLocalDate();
        if (counterTime < 0 || !today.equals(counterDate) || elapsed - counterTime >= COUNTER_REFRESH_MS) {
            long start = today.atStartOfDay(zone).toInstant().toEpochMilli();
            long end = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
            counterCount = store.visitCount(start, end);
            counterTime = elapsed;
            counterDate = today;
        }
        Rect badgeArea = new Rect(viewport);
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                Rect screen = displayBounds();
                int topInset = windows.getMaximumWindowMetrics().getWindowInsets()
                        .getInsetsIgnoringVisibility(WindowInsets.Type.statusBars() | WindowInsets.Type.displayCutout())
                        .top;
                badgeArea.top = Math.max(badgeArea.top, screen.top + topInset);
            } catch (UnsupportedOperationException ignored) {
                // A service-window context can lack window metrics on some devices.
            }
        }
        overlays.showCounter(badgeArea, counterCount, store.dailyLimit(), store.counterSize(), store.colorTheme());
    }

    private void chooseCover(String key, float x, float y) {
        if (!connected || forwardingGesture) {
            return;
        }
        try (Frame frame = readFrame()) {
            if (frame == null) {
                clearScreen();
                return;
            }
            ViewingSession.Cover cover = findCover(showFrame(frame), key);
            if (cover == null || !ScreenSnapshot.bounds(cover.target.bounds).contains((int) x, (int) y)) {
                return;
            }
            if (cover.locked || store.isBlocked(System.currentTimeMillis())) {
                return;
            }
            ContentTarget target = cover.target;
            if (target.kind == ContentTarget.Kind.POST || target.surface != ContentTarget.Surface.CARD) {
                if (session.reveal(cover, SystemClock.elapsedRealtime(), System.currentTimeMillis())) {
                    showFrame(frame);
                }
            } else {
                openNativeCard(frame, cover);
            }
        } catch (RuntimeException exception) {
            scanFailed(exception);
        } finally {
            requestScan(0);
        }
    }

    private void openNativeCard(Frame frame, ViewingSession.Cover cover) {
        UiNode target = cover.target.action != null ? cover.target.action : cover.target.bounds;
        AccessibilityNodeInfo node = ScreenSnapshot.resolve(frame.root, target.path);
        if (node == null) {
            return;
        }
        Rect button = new Rect();
        try {
            node.getBoundsInScreen(button);
            if (!node.isVisibleToUser() || !node.isEnabled() || !target.id.equals(string(node.getViewIdResourceName()))
                    || !button.intersect(frame.viewport) || !Rect.intersects(button, ScreenSnapshot.bounds(target))) {
                return;
            }
            if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                session.nativeEntryOpened(cover.target, frame.targets, SystemClock.elapsedRealtime());
                return;
            }
        } finally {
            ScreenSnapshot.recycle(node);
        }
        int x = button.centerX();
        int y = button.centerY();
        overlays.releaseAt(x, y);
        String packageName = frame.packageName;
        int windowId = frame.windowId;
        forwardingGesture = true;
        main.postDelayed(() -> forwardTap(packageName, windowId, cover, frame.targets, x, y), 45);
    }

    private void forwardTap(String packageName, int windowId, ViewingSession.Cover cover,
                            List<ContentTarget> targets, int x, int y) {
        try {
            if (!connected || !store.isEnabled() || store.isBlocked(System.currentTimeMillis())) {
                return;
            }
            AccessibilityNodeInfo root = activeApplicationRoot();
            boolean same;
            try {
                same = root != null && windowId == root.getWindowId()
                        && packageName.equals(string(root.getPackageName()));
            } finally {
                ScreenSnapshot.recycle(root);
            }
            if (same) {
                Path path = new Path();
                path.moveTo(x, y);
                GestureDescription gesture = new GestureDescription.Builder()
                        .addStroke(new GestureDescription.StrokeDescription(path, 0, 35)).build();
                if (dispatchGesture(gesture, null, null)) {
                    session.nativeEntryOpened(cover.target, targets, SystemClock.elapsedRealtime());
                }
            }
        } catch (RuntimeException exception) {
            scanFailed(exception);
        } finally {
            main.postDelayed(() -> {
                forwardingGesture = false;
                requestScan(0);
            }, 95);
        }
    }

    private void scrollCoveredList(String key, boolean down) {
        if (!connected || forwardingGesture) {
            return;
        }
        try (Frame frame = readFrame()) {
            if (frame == null) {
                return;
            }
            ViewingSession.Cover cover = findCover(showFrame(frame), key);
            if (cover == null || cover.target.surface == ContentTarget.Surface.SHORT_VIEWER) {
                return;
            }
            int[] path = cover.target.bounds.path;
            // Ask the containing list to scroll, keeping the underlying items masked.
            for (int length = path.length; length >= 0; length--) {
                AccessibilityNodeInfo node = ScreenSnapshot.resolve(frame.root, Arrays.copyOf(path, length));
                if (node == null) {
                    continue;
                }
                try {
                    if (node.isScrollable() && (node.performAction(down
                            ? AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.getId()
                            : AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.getId())
                            || node.performAction(down ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                            : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD))) {
                        break;
                    }
                } finally {
                    ScreenSnapshot.recycle(node);
                }
            }
        } catch (RuntimeException exception) {
            scanFailed(exception);
        } finally {
            requestScan(0);
        }
    }

    private Platform lookupPlatform(String packageName) {
        if (packageName.isEmpty() || packageName.equals(getPackageName())) {
            return null;
        }
        for (Platform candidate : PLATFORMS) {
            if (packageName.equals(store.customPackage(candidate))) {
                return candidate;
            }
        }
        Platform official = Platform.fromPackage(packageName);
        return official != null && store.customPackage(official).isEmpty() ? official : null;
    }

    private void showReminder(Rule rule) {
        String message = getString(R.string.reminder_rule, rule.hours, rule.count);
        try {
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null && manager.areNotificationsEnabled() && (Build.VERSION.SDK_INT < 33
                    || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)) {
                manager.createNotificationChannel(new NotificationChannel(REMINDER_CHANNEL,
                        getString(R.string.reminder_channel), NotificationManager.IMPORTANCE_HIGH));
                PendingIntent openApp = PendingIntent.getActivity(this, 0,
                        new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
                Notification notification = new Notification.Builder(this, REMINDER_CHANNEL)
                        .setSmallIcon(R.drawable.ic_raindrop)
                        .setContentTitle(getString(R.string.app_name))
                        .setContentText(message)
                        .setStyle(new Notification.BigTextStyle().bigText(message))
                        .setAutoCancel(true)
                        .setContentIntent(openApp)
                        .build();
                manager.notify(Long.hashCode(rule.id), notification);
            } else {
                Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            }
        } catch (RuntimeException exception) {
            Log.w(TAG, "The system could not show a reminder.", exception);
        }
    }

    @SuppressWarnings("deprecation")
    private Rect displayBounds() {
        Display display = windows.getDefaultDisplay();
        Point size = new Point();
        display.getRealSize(size);
        return new Rect(0, 0, size.x, size.y);
    }

    private void clearScreen() {
        foregroundPackage = "";
        foregroundWindow = -1;
        shortPager = null;
        if (overlays != null) {
            overlays.clear();
        }
        if (playbackProbe != null) {
            playbackProbe.reset();
        }
        if (session != null) {
            session.leave();
            if (widgetTimeRefresh != 0) {
                BluemindWidget.refresh(this);
                widgetTimeRefresh = 0;
            }
        }
    }

    private void scanFailed(RuntimeException exception) {
        long now = SystemClock.elapsedRealtime();
        ServiceHealth.scanError(this, exception);
        if (now - scanFailureLogAt >= 60_000) {
            scanFailureLogAt = now;
            Log.w(TAG, "Accessibility scan will retry.", exception);
        }
        try {
            clearScreen();
        } catch (RuntimeException cleanupFailure) {
            Log.w(TAG, "An accessibility window was already lost.", cleanupFailure);
        }
    }

    private void stopWatching() {
        connected = false;
        main.removeCallbacksAndMessages(null);
        scanAt = Long.MAX_VALUE;
        lastScan = 0;
        forwardingGesture = false;
        ServiceHealth.disconnected();
        if (playbackProbe != null) {
            playbackProbe.close();
            playbackProbe = null;
        }
        try {
            clearScreen();
        } catch (RuntimeException cleanupFailure) {
            Log.w(TAG, "The service disconnected during cleanup.", cleanupFailure);
        }
        if (registeredDisplayReceiver) {
            unregisterReceiver(displayChanges);
            registeredDisplayReceiver = false;
        }
    }

    private static ViewingSession.Cover findCover(List<ViewingSession.Cover> covers, String key) {
        for (ViewingSession.Cover cover : covers) {
            if (key.equals(cover.key)) {
                return cover;
            }
        }
        return null;
    }

    @SuppressWarnings("deprecation")
    private static void recycleWindow(AccessibilityWindowInfo window) {
        if (Build.VERSION.SDK_INT < 33) {
            window.recycle();
        }
    }

    private static String string(CharSequence value) {
        return value == null ? "" : value.toString();
    }

    private static final class Frame implements AutoCloseable {
        final AccessibilityNodeInfo root;
        final Platform platform;
        final String packageName;
        final int windowId;
        final Rect viewport;
        final Rect windowBounds;
        final List<ContentTarget> targets;

        Frame(AccessibilityNodeInfo root, Platform platform, String packageName, Rect viewport,
              Rect windowBounds, List<ContentTarget> targets) {
            this.root = root;
            this.platform = platform;
            this.packageName = packageName;
            windowId = root.getWindowId();
            this.viewport = viewport;
            this.windowBounds = windowBounds;
            this.targets = targets;
        }

        @Override
        public void close() {
            ScreenSnapshot.recycle(root);
        }
    }
}
