package com.bluemind.app;

import static com.bluemind.app.BlueTheme.updateText;

import android.Manifest;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.ApplicationExitInfo;
import android.app.NotificationManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.view.accessibility.AccessibilityManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class BluemindSettings {
    private final Activity activity;
    private final BlueTheme style;
    private final MindStore store;
    private final EnumMap<Platform, TextView> packageLabels = new EnumMap<>(Platform.class);
    private final SharedPreferences setupState;

    private AlertDialog sheet;
    private AlertDialog popup;
    private boolean setupSheet;
    private TextView counterSize;
    private TextView accessibilityState;
    private TextView heartbeatState;
    private TextView batteryState;
    private TextView notificationState;
    private TextView processExit;

    BluemindSettings(Activity activity, BlueTheme style, MindStore store) {
        this.activity = activity;
        this.style = style;
        this.store = store;
        setupState = activity.getSharedPreferences("permission_setup", Activity.MODE_PRIVATE);
    }

    boolean isShowing() {
        return sheet != null && sheet.isShowing();
    }

    boolean isShowingSetup() {
        return isShowing() && setupSheet;
    }

    boolean needsFirstSetup() {
        return !setupState.getBoolean("opened", false);
    }

    void close() {
        if (popup != null) {
            popup.dismiss();
        }
        if (sheet != null) {
            sheet.dismiss();
        }
    }

    private void showPopup(AlertDialog dialog) {
        if (popup != null) {
            popup.dismiss();
        }
        popup = dialog;
        dialog.show();
    }

    void show() {
        showSheet(false);
    }

    void showSetup() {
        showSheet(true);
    }

    private void showSheet(boolean setup) {
        if (sheet != null) {
            sheet.dismiss();
        }
        setupSheet = setup;
        packageLabels.clear();
        counterSize = null;
        processExit = null;
        ScrollView scroll = new ScrollView(activity);
        LinearLayout content = style.column();
        style.pad(content, 20, 12);
        scroll.addView(content);
        if (setup) {
            setupState.edit().putBoolean("opened", true).apply();
            TextView intro = style.text("선택한 앱의 화면 구조를 읽어 가릴 콘텐츠와 재생 상태를 찾습니다. "
                    + "가림 화면을 누르면 원래 항목의 동작을 실행할 수 있어요. "
                    + "Android 14 이상에서 재생 상태를 알 수 없으면 이미 연 영상의 움직임을 잠시 확인합니다. "
                    + "화면 이미지와 본문은 감지 과정에서만 쓰고, 기록과 설정은 이 기기에 보관합니다.",
                    14, style.muted);
            style.add(content, intro, 0);
            addPermissions(content, false);
        } else {
            addAppearance(content);
            addPackages(content);
            addPermissions(content, true);
        }
        sheet = new AlertDialog.Builder(activity)
                .setTitle(setup ? "Bluemind 권한 설정" : "Bluemind 설정")
                .setView(scroll)
                .setPositiveButton(setup ? "완료" : "닫기", null)
                .create();
        sheet.show();
        refresh();
    }

    private void addAppearance(LinearLayout content) {
        style.add(content, style.title("화면", 18, style.ink), 0);
        style.add(content, style.action("테마 · " + themeName(store.colorTheme()), false, this::pickTheme), 6);

        Switch floating = new Switch(activity);
        floating.setText("앱 위에 오늘 횟수 표시");
        floating.setChecked(store.isCounterVisible());
        floating.setMinHeight(style.dp(48));
        floating.setOnCheckedChangeListener((button, enabled) -> {
            store.setCounterVisible(enabled);
            counterSize.setEnabled(enabled);
            counterSize.setAlpha(enabled ? 1f : .5f);
        });
        style.add(content, floating, 8);

        counterSize = style.action("횟수 표시 크기 · " + sizeName(store.counterSize()), false, this::pickSize);
        counterSize.setEnabled(store.isCounterVisible());
        counterSize.setAlpha(store.isCounterVisible() ? 1f : .5f);
        style.add(content, counterSize, 4);
        style.add(content, style.action("홈 화면 위젯 추가", false, () -> {
            if (!BluemindWidget.requestPin(activity)) {
                Toast.makeText(activity, "홈 화면을 길게 누르고 위젯에서 Bluemind를 선택하세요.",
                        Toast.LENGTH_LONG).show();
            }
        }), 6);
    }

    private void pickTheme() {
        MindStore.ColorTheme[] options = MindStore.ColorTheme.values();
        String[] labels = new String[options.length];
        for (int index = 0; index < options.length; index++) {
            labels[index] = themeName(options[index]);
        }
        showPopup(new AlertDialog.Builder(activity)
                .setTitle("테마")
                .setSingleChoiceItems(labels, store.colorTheme().ordinal(), (dialog, index) -> {
                    dialog.dismiss();
                    if (options[index] != store.colorTheme()) {
                        store.setColorTheme(options[index]);
                        BluemindWidget.refresh(activity);
                        activity.recreate();
                    }
                })
                .setNegativeButton("닫기", null)
                .create());
    }

    private void pickSize() {
        MindStore.CounterSize[] options = MindStore.CounterSize.values();
        String[] labels = new String[options.length];
        for (int index = 0; index < options.length; index++) {
            labels[index] = sizeName(options[index]);
        }
        showPopup(new AlertDialog.Builder(activity)
                .setTitle("횟수 표시 크기")
                .setSingleChoiceItems(labels, store.counterSize().ordinal(), (dialog, index) -> {
                    store.setCounterSize(options[index]);
                    counterSize.setText("횟수 표시 크기 · " + labels[index]);
                    dialog.dismiss();
                })
                .setNegativeButton("닫기", null)
                .create());
    }

    private void addPackages(LinearLayout content) {
        style.add(content, style.title("앱 연결", 18, style.ink), 22);
        style.add(content, style.text("개조 버전을 사용한다면 설치된 앱을 연결하세요.", 13, style.muted), 2);
        for (Platform platform : Platform.values()) {
            TextView entry = style.action(packageLabel(platform), false, () -> choosePackage(platform));
            packageLabels.put(platform, entry);
            style.add(content, entry, 6);
        }
    }

    private String packageLabel(Platform platform) {
        String current = store.customPackage(platform);
        return platform.label + " · " + (current.isEmpty() ? "자동 감지" : current);
    }

    private void choosePackage(Platform platform) {
        String[] options = {"설치된 앱에서 선택", "패키지명 직접 입력", "자동 감지로 되돌리기"};
        showPopup(new AlertDialog.Builder(activity)
                .setTitle(platform.label + " 연결")
                .setItems(options, (dialog, index) -> {
                    switch (index) {
                        case 0 -> pickInstalledApp(platform);
                        case 1 -> editPackageName(platform);
                        case 2 -> savePackage(platform, "");
                        default -> throw new IllegalArgumentException("Unknown package action");
                    }
                })
                .setNegativeButton("취소", null)
                .create());
    }

    private void pickInstalledApp(Platform platform) {
        PackageManager pm = activity.getPackageManager();
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        Set<String> seen = new HashSet<>();
        List<LaunchableApp> apps = new ArrayList<>();
        for (ResolveInfo app : pm.queryIntentActivities(launcher, 0)) {
            if (app.activityInfo == null) {
                continue;
            }
            String name = app.activityInfo.packageName;
            Platform official = Platform.fromPackage(name);
            if (!name.equals(activity.getPackageName())
                    && (official == null || official == platform) && seen.add(name)) {
                apps.add(new LaunchableApp(app.loadLabel(pm).toString(), name));
            }
        }
        apps.sort((first, second) -> {
            int score = Boolean.compare(matches(platform, second), matches(platform, first));
            return score != 0 ? score : first.label.compareToIgnoreCase(second.label);
        });
        if (apps.isEmpty()) {
            editPackageName(platform);
            return;
        }
        String[] labels = new String[apps.size()];
        for (int index = 0; index < apps.size(); index++) {
            LaunchableApp app = apps.get(index);
            labels[index] = app.label + "\n" + app.name;
        }
        showPopup(new AlertDialog.Builder(activity)
                .setTitle(platform.label + " 선택")
                .setItems(labels, (dialog, index) -> savePackage(platform, apps.get(index).name))
                .setNegativeButton("취소", null)
                .create());
    }

    private static boolean matches(Platform platform, LaunchableApp app) {
        String words = (app.label + " " + app.name).toLowerCase(Locale.ROOT);
        boolean localized = platform != Platform.X && words.contains(platform.label.toLowerCase(Locale.ROOT));
        return localized || switch (platform) {
            case INSTAGRAM -> words.contains("instagram") || words.contains("insta");
            case TIKTOK -> words.contains("tiktok") || words.contains("musically");
            case YOUTUBE -> words.contains("youtube") || words.contains("revanced");
            case X -> words.contains("twitter") || words.contains("piko") || app.label.equalsIgnoreCase("X");
        };
    }

    private void editPackageName(Platform platform) {
        LinearLayout content = style.column();
        style.pad(content, 22, 12);
        EditText input = new EditText(activity);
        input.setHint("예시 com.example.android");
        input.setText(store.customPackage(platform));
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setSingleLine();
        style.add(content, input, 0);
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(platform.label + " 패키지명")
                .setView(content)
                .setNegativeButton("취소", null)
                .setPositiveButton("저장", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    String name = input.getText().toString().trim();
                    try {
                        setPackage(platform, name);
                        dialog.dismiss();
                    } catch (IllegalArgumentException e) {
                        input.setError(e.getMessage());
                    }
                }));
        showPopup(dialog);
    }

    private void savePackage(Platform platform, String name) {
        try {
            setPackage(platform, name);
        } catch (IllegalArgumentException e) {
            showPopup(new AlertDialog.Builder(activity)
                    .setMessage(e.getMessage())
                    .setPositiveButton("확인", null)
                    .create());
        }
    }

    private void setPackage(Platform platform, String name) {
        if (name.equals(activity.getPackageName())) {
            throw new IllegalArgumentException("Bluemind는 연결할 수 없어요.");
        }
        store.setCustomPackage(platform, name);
        TextView label = packageLabels.get(platform);
        if (label != null) {
            label.setText(packageLabel(platform));
        }
    }

    private void addPermissions(LinearLayout content, boolean fullSettings) {
        style.add(content, style.title(fullSettings ? "작동 상태" : "권한 연결", 18, style.ink),
                fullSettings ? 22 : 14);
        accessibilityState = style.text("", 14, style.ink);
        style.add(content, accessibilityState, 5);
        style.add(content, style.action("접근성 설정 열기", true,
                () -> open(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))), 5);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            style.add(content, style.text("접근성에 ‘제한된 설정’이 표시되면 앱 정보 화면의 메뉴에서 "
                    + "제한된 설정 허용을 선택하세요.", 12, style.muted), 7);
        }

        batteryState = style.text("", 13, style.muted);
        style.add(content, batteryState, 10);
        style.add(content, style.action("배터리 최적화 설정", false,
                () -> open(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))), 5);
        style.add(content, style.action("앱 정보 열기", false, this::openAppInfo), 5);
        if (Build.MANUFACTURER.equalsIgnoreCase("samsung")) {
            style.add(content, style.text("Galaxy에서는 배터리의 백그라운드 사용 제한에서 "
                    + "Bluemind가 절전 앱으로 지정되어 있는지도 확인하세요.", 12, style.muted), 5);
        }

        notificationState = style.text("", 13, style.muted);
        style.add(content, notificationState, 10);
        style.add(content, style.action("알림 권한 설정", false, this::openNotifications), 5);
        heartbeatState = style.text("", 12, style.muted);
        style.add(content, heartbeatState, 10);
        if (fullSettings) {
            processExit = style.text("", 12, style.muted);
            style.add(content, processExit, 5);
            style.add(content, style.action("권한 설정 다시 열기", false, this::showSetup), 9);
        }
    }

    void refresh() {
        if (!isShowing()) {
            return;
        }
        boolean connected = ServiceHealth.isConnected(activity);
        updateText(accessibilityState, connected ? "접근성 서비스 연결됨" : accessibilityEnabled()
                ? "접근성 설정 켜짐 · 서비스 연결 대기 중"
                : "접근성 서비스 연결 필요");
        long heartbeat = ServiceHealth.lastHeartbeat(activity);
        updateText(heartbeatState, heartbeat == 0
                ? "서비스가 연결되면 마지막 응답 시각을 표시합니다."
                : "마지막 서비스 응답 " + formatTime(heartbeat));

        PowerManager power = activity.getSystemService(PowerManager.class);
        ActivityManager manager = activity.getSystemService(ActivityManager.class);
        List<String> battery = new ArrayList<>();
        battery.add(power.isIgnoringBatteryOptimizations(activity.getPackageName())
                ? "배터리 최적화 예외 적용" : "배터리 최적화 적용 중");
        if (power.isPowerSaveMode()) {
            battery.add("절전 모드 켜짐");
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && manager.isBackgroundRestricted()) {
            battery.add("백그라운드 사용 제한됨");
        }
        updateText(batteryState, String.join(" · ", battery));

        NotificationManager notification = activity.getSystemService(NotificationManager.class);
        updateText(notificationState, notification.areNotificationsEnabled()
                ? "알림 허용됨" : "알림 권한 없음 · 규칙 알림에 사용");
        if (processExit != null) {
            long lastScanError = ServiceHealth.lastScanErrorAt(activity);
            String exitStatus = recentExit(manager);
            if (lastScanError > 0) {
                exitStatus += "\n마지막 감지 재시도 " + formatTime(lastScanError)
                        + " · " + ServiceHealth.lastScanErrorType(activity);
            }
            updateText(processExit, exitStatus);
        }
    }

    private boolean accessibilityEnabled() {
        AccessibilityManager accessibility = activity.getSystemService(AccessibilityManager.class);
        for (AccessibilityServiceInfo service :
                accessibility.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)) {
            ResolveInfo component = service.getResolveInfo();
            if (component != null && component.serviceInfo != null
                    && activity.getPackageName().equals(component.serviceInfo.packageName)) {
                return true;
            }
        }
        return false;
    }

    private String recentExit(ActivityManager manager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return "서비스 종료 기록은 Android 11부터 확인할 수 있어요.";
        }
        List<ApplicationExitInfo> exits = manager.getHistoricalProcessExitReasons(null, 0, 1);
        if (exits.isEmpty()) {
            return "최근 앱 프로세스 종료 기록 없음";
        }
        ApplicationExitInfo exit = exits.get(0);
        String cause = switch (exit.getReason()) {
            case ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE -> "앱 오류";
            case ApplicationExitInfo.REASON_ANR -> "응답 없음";
            case ApplicationExitInfo.REASON_LOW_MEMORY -> "메모리 부족";
            case ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "권한 변경";
            case ApplicationExitInfo.REASON_USER_REQUESTED -> "사용자 또는 시스템 요청";
            case ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "앱 업데이트";
            case ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "리소스 과다 사용";
            default -> "시스템 코드 " + exit.getReason();
        };
        return "최근 앱 프로세스 종료 " + cause + " · " + formatTime(exit.getTimestamp());
    }

    private static String formatTime(long time) {
        return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, Locale.KOREA).format(time);
    }

    private void openNotifications() {
        NotificationManager notification = activity.getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && !notification.areNotificationsEnabled()
                && !setupState.getBoolean("notificationEntered", false)) {
            setupState.edit().putBoolean("notificationEntered", true).apply();
            activity.requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        } else {
            open(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, activity.getPackageName()));
        }
    }

    private void openAppInfo() {
        open(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", activity.getPackageName(), null)));
    }

    private void open(Intent intent) {
        try {
            activity.startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(activity, "이 기기에서는 시스템 설정에서 직접 변경해 주세요.",
                    Toast.LENGTH_LONG).show();
        }
    }

    private static String themeName(MindStore.ColorTheme theme) {
        return switch (theme) {
            case RAIN -> "빗방울";
            case DEEP_OCEAN -> "깊은 바다";
            case MOONLIGHT -> "달빛";
        };
    }

    private static String sizeName(MindStore.CounterSize size) {
        return switch (size) {
            case SMALL -> "작게";
            case MEDIUM -> "보통";
            case LARGE -> "크게";
        };
    }

    private static final class LaunchableApp {
        final String label;
        final String name;

        LaunchableApp(String label, String name) {
            this.label = label;
            this.name = name;
        }
    }
}
