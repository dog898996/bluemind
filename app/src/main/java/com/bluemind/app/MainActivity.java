package com.bluemind.app;

import static com.bluemind.app.BlueTheme.updateText;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import java.text.NumberFormat;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;

public final class MainActivity extends Activity {
    private static final String[] TABS = {"오늘", "제한", "기록"};
    private static final DateTimeFormatter DAY_LABEL = DateTimeFormatter.ofPattern("M월 d일 EEEE", Locale.KOREA);
    private static final DateTimeFormatter WEEK_LABEL = DateTimeFormatter.ofPattern("M월 d일", Locale.KOREA);
    private static final long HOUR_MS = 3_600_000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final EnumMap<Platform, TextView> todayApps = new EnumMap<>(Platform.class);
    private final EnumMap<Platform, TextView> historyApps = new EnumMap<>(Platform.class);
    private final List<RuleProgress> ruleProgress = new ArrayList<>();
    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            refresh();
            handler.postDelayed(this, 1_500);
        }
    };

    private BlueTheme style;
    private MindStore store;
    private BluemindSettings settings;
    private LinearLayout shell;
    private LinearLayout header;
    private LinearLayout body;
    private LinearLayout nav;
    private ScrollView scroll;
    private int tab;
    private boolean week;
    private LocalDate lastToday = LocalDate.now();
    private LocalDate date = lastToday;
    private AlertDialog editor;

    private TextView todayCount;
    private TextView todayDuration;
    private TextView todayStatus;
    private TextView todayStatusDetail;
    private Switch toggle;
    private TextView dailyProgress;
    private ProgressBar dailyBar;
    private TextView historyCount;
    private TextView historyDuration;
    private TextView historyChange;
    private TextView dateLabel;
    private TextView nextDate;
    private HistoryChartView chart;

    @Override
    public void onCreate(Bundle state) {
        store = MindStore.get(this);
        MindStore.ColorTheme theme = store.colorTheme();
        setTheme(switch (theme) {
            case RAIN -> R.style.Theme_Bluemind;
            case DEEP_OCEAN -> R.style.Theme_Bluemind_Ocean;
            case MOONLIGHT -> R.style.Theme_Bluemind_Moonlight;
        });
        super.onCreate(state);
        style = new BlueTheme(this, theme);
        settings = new BluemindSettings(this, style, store);
        if (state != null) {
            tab = state.getInt("tab");
            week = state.getBoolean("week");
            date = LocalDate.ofEpochDay(state.getLong("date"));
        }
        setUpInsets();
        buildShell();
        renderPage();
        if (settings.needsFirstSetup() || (state != null && state.getBoolean("setup"))) {
            settings.showSetup();
        } else if (state != null && state.getBoolean("settings")) {
            settings.show();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.removeCallbacks(poll);
        handler.post(poll);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(poll);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (editor != null) {
            editor.dismiss();
        }
        settings.close();
        super.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putInt("tab", tab);
        state.putBoolean("week", week);
        state.putLong("date", date.toEpochDay());
        state.putBoolean("settings", settings.isShowing());
        state.putBoolean("setup", settings.isShowingSetup());
    }

    @SuppressWarnings("deprecation")
    private void setUpInsets() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
            int lightBars = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            getWindow().getDecorView().post(() -> {
                android.view.WindowInsetsController controller = getWindow().getInsetsController();
                if (controller != null) {
                    controller.setSystemBarsAppearance(style.light ? lightBars : 0, lightBars);
                }
            });
        } else {
            int lightBars = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | (style.light ? lightBars : 0));
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            getWindow().setNavigationBarContrastEnforced(false);
        }
    }

    @SuppressWarnings("deprecation")
    private void buildShell() {
        FrameLayout backdrop = new FrameLayout(this);
        backdrop.setBackgroundColor(style.background);
        shell = new LinearLayout(this) {
            @Override
            protected void onMeasure(int width, int height) {
                int maxWidth = Math.min(MeasureSpec.getSize(width), style.dp(580));
                super.onMeasure(MeasureSpec.makeMeasureSpec(maxWidth, MeasureSpec.EXACTLY), height);
            }
        };
        shell.setOrientation(LinearLayout.VERTICAL);
        backdrop.addView(shell, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER_HORIZONTAL));
        backdrop.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.graphics.Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                backdrop.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                return WindowInsets.CONSUMED;
            } else {
                backdrop.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
                return insets.consumeSystemWindowInsets();
            }
        });
        setContentView(backdrop);
        backdrop.requestApplyInsets();

        header = style.row();
        header.setMinimumHeight(style.dp(66));
        style.pad(header, 19, 5);
        style.add(shell, header, 0);

        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        body = style.column();
        body.setPadding(style.dp(19), style.dp(5), style.dp(19), style.dp(15));
        scroll.addView(body);
        shell.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        View divider = new View(this);
        divider.setBackgroundColor(style.outline);
        shell.addView(divider, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, style.dp(1)));
        nav = style.row();
        nav.setPadding(style.dp(13), style.dp(7), style.dp(13), style.dp(6));
        style.add(shell, nav, 0);
    }

    private void renderPage() {
        header.removeAllViews();
        body.removeAllViews();
        nav.removeAllViews();
        renderHeader();
        switch (tab) {
            case 0 -> renderToday();
            case 1 -> renderLimits();
            case 2 -> renderHistory();
            default -> throw new IllegalStateException("Unknown tab " + tab);
        }
        renderNavigation();
        scroll.scrollTo(0, 0);
        refresh();
    }

    private void renderHeader() {
        if (tab == 0) {
            ImageView icon = drop();
            icon.setBackground(style.rounded(style.field, 12, style.outline));
            icon.setPadding(style.dp(4), style.dp(4), style.dp(4), style.dp(4));
            header.addView(icon, new LinearLayout.LayoutParams(style.dp(39), style.dp(39)));
            TextView name = style.title("Bluemind", 27, style.ink);
            name.setPadding(style.dp(10), 0, 0, 0);
            style.weight(header, name);
        } else {
            style.weight(header, style.title(TABS[tab], 28, style.ink));
        }
        if (tab == 0) {
            TextView button = style.action("설정", false, settings::show);
            button.setContentDescription("Bluemind 설정");
            header.addView(button);
        }
    }

    private void renderNavigation() {
        for (int index = 0; index < TABS.length; index++) {
            int selectedIndex = index;
            boolean selected = index == tab;
            int color = selected ? style.paleBlue : style.muted;
            LinearLayout button = style.column();
            button.setGravity(Gravity.CENTER);
            button.setSelected(selected);
            button.setContentDescription(TABS[index] + (selected ? ", 선택됨" : ""));
            button.setOnClickListener(view -> {
                if (tab != selectedIndex) {
                    tab = selectedIndex;
                    renderPage();
                }
            });
            button.setBackground(style.ripple(selected ? style.field : Color.TRANSPARENT, 14, Color.TRANSPARENT));
            style.pad(button, 8, 8);
            button.addView(new NavIconView(this, index, color),
                    new LinearLayout.LayoutParams(style.dp(23), style.dp(23)));
            TextView label = style.title(TABS[index], 12, color);
            label.setGravity(Gravity.CENTER);
            label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            style.add(button, label, 3);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            params.setMargins(style.dp(3), 0, style.dp(3), 0);
            nav.addView(button, params);
        }
    }

    private ImageView drop() {
        ImageView image = new ImageView(this);
        image.setImageResource(R.drawable.ic_raindrop);
        image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return image;
    }

    private void renderToday() {
        FrameLayout hero = new RainSceneView(this, style);
        hero.setBackground(style.rounded(style.panel, 23, style.outline));
        hero.setClipToOutline(true);
        LinearLayout heroContent = style.row();
        heroContent.setMinimumHeight(style.dp(151));
        heroContent.setPadding(style.dp(20), style.dp(16), style.dp(10), style.dp(16));
        style.weight(heroContent, style.title("일상의 소리에\n귀 기울여요", 24, style.ink));
        heroContent.addView(drop(), new LinearLayout.LayoutParams(style.dp(111), style.dp(111)));
        hero.addView(heroContent, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        style.add(body, hero, 0);

        LinearLayout usage = style.panel();
        style.add(usage, style.title("오늘 본 콘텐츠", 17, style.ink), 0);
        LinearLayout summary = style.row();
        todayCount = style.title("0회", 45, style.paleBlue);
        todayCount.setTypeface(Typeface.create("sans-serif-black", Typeface.NORMAL));
        style.weight(summary, todayCount);
        LinearLayout time = style.column();
        style.add(time, style.text("총 이용 시간", 13, style.muted), 0);
        todayDuration = style.title("0초", 20, style.ink);
        style.add(time, todayDuration, 2);
        summary.addView(time);
        style.add(usage, summary, 2);
        todayApps.clear();
        Platform[] all = Platform.values();
        for (int index = 0; index < all.length; index += 2) {
            LinearLayout row = style.row();
            addPlatformChip(row, all[index], 0);
            if (index + 1 < all.length) {
                addPlatformChip(row, all[index + 1], 7);
            }
            style.add(usage, row, 7);
        }
        style.add(body, usage, 11);

        LinearLayout service = style.panel();
        LinearLayout switchRow = style.row();
        LinearLayout status = style.column();
        todayStatus = style.title("", 17, style.ink);
        todayStatusDetail = style.text("", 13, style.muted);
        style.add(status, todayStatus, 0);
        style.add(status, todayStatusDetail, 3);
        style.weight(switchRow, status);
        toggle = new Switch(this);
        toggle.setContentDescription("Bluemind 감지");
        toggle.setMinHeight(style.dp(48));
        toggle.setOnClickListener(view -> {
            store.setEnabled(toggle.isChecked());
            BluemindWidget.refresh(this);
            refresh();
        });
        switchRow.addView(toggle);
        style.add(service, switchRow, 0);
        style.add(body, service, 11);
    }

    private void addPlatformChip(LinearLayout row, Platform platform, int startMargin) {
        LinearLayout chip = style.row();
        style.pad(chip, 9, 7);
        chip.setBackground(style.rounded(style.field, 10, Color.TRANSPARENT));
        View mark = new View(this);
        mark.setBackground(style.rounded(platformColor(platform), 4, Color.TRANSPARENT));
        chip.addView(mark, new LinearLayout.LayoutParams(style.dp(6), style.dp(6)));
        TextView label = style.text(platform.label, 12, style.paleBlue);
        label.setPadding(style.dp(6), 0, style.dp(3), 0);
        style.weight(chip, label);
        TextView value = style.title("0", 13, style.ink);
        chip.addView(value);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        params.setMarginStart(style.dp(startMargin));
        row.addView(chip, params);
        todayApps.put(platform, value);
    }

    private void renderLimits() {
        LinearLayout daily = style.panel();
        LinearLayout title = style.row();
        style.weight(title, style.title("하루 제한", 19, style.ink));
        title.addView(style.action("변경", false, this::editDailyLimit));
        style.add(daily, title, 0);
        dailyProgress = style.title("", 28, style.paleBlue);
        style.add(daily, dailyProgress, 8);
        dailyBar = progressBar(style.accent);
        style.add(daily, dailyBar, 9);
        style.add(daily, style.text("매일 자정(00:00)에 초기화돼요", 13, style.muted), 9);
        style.add(body, daily, 0);

        LinearLayout titleRow = style.row();
        style.weight(titleRow, style.title("추가 규칙", 20, style.ink));
        titleRow.addView(style.action("+ 규칙 추가", true, () -> editRule(null)));
        style.add(body, titleRow, 18);
        ruleProgress.clear();
        List<Rule> rules = store.rules();
        if (rules.isEmpty()) {
            LinearLayout empty = style.panel();
            style.add(empty, style.text("최근 몇 시간 동안 볼 횟수를 제한하거나 알림을 받을 수 있어요.", 14, style.muted), 0);
            style.add(body, empty, 8);
        }
        for (Rule rule : rules) {
            LinearLayout item = style.panel();
            LinearLayout heading = style.row();
            int tint = rule.mode == Rule.Mode.LIMIT ? style.accent : style.reminder;
            TextView badge = style.title(rule.mode == Rule.Mode.LIMIT ? "제한" : "알림", 13, tint);
            style.pad(badge, 9, 4);
            badge.setBackground(style.rounded(style.field, 9, Color.TRANSPARENT));
            heading.addView(badge);
            TextView ruleLabel = style.title(number(rule.hours) + "시간 · " + number(rule.count) + "회", 17, style.ink);
            ruleLabel.setPadding(style.dp(8), 0, style.dp(4), 0);
            style.weight(heading, ruleLabel);
            heading.addView(style.action("편집", false, () -> editRule(rule)));
            style.add(item, heading, 0);
            TextView value = style.text("", 13, style.muted);
            style.add(item, value, 5);
            ProgressBar bar = progressBar(tint);
            style.add(item, bar, 7);
            ruleProgress.add(new RuleProgress(rule, value, bar));
            style.add(body, item, 8);
        }
    }

    private ProgressBar progressBar(int tint) {
        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setProgressTintList(ColorStateList.valueOf(tint));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(style.field));
        return bar;
    }

    private void editDailyLimit() {
        LinearLayout content = dialogContent();
        Integer current = store.dailyLimit();
        Switch unlimited = new Switch(this);
        unlimited.setText("제한 없음");
        unlimited.setChecked(current == null);
        unlimited.setMinHeight(style.dp(48));
        style.add(content, unlimited, 0);
        EditText count = integerField("하루 최대 횟수", current == null ? MindStore.DEFAULT_DAILY_LIMIT : current);
        count.setEnabled(current != null);
        style.add(content, count, 6);
        unlimited.setOnCheckedChangeListener((button, checked) -> count.setEnabled(!checked));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("하루 제한")
                .setView(scrollable(content))
                .setNegativeButton("취소", null)
                .setPositiveButton("저장", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    Integer cap = unlimited.isChecked() ? null : positiveInt(count);
                    if (!unlimited.isChecked() && cap == null) {
                        return;
                    }
                    store.setDailyLimit(cap);
                    dialog.dismiss();
                    refresh();
                }));
        editor = dialog;
        dialog.show();
    }

    private void editRule(Rule existing) {
        LinearLayout content = dialogContent();
        EditText hours = integerField("최근 몇 시간", existing == null ? 2 : existing.hours);
        EditText count = integerField("최대 횟수", existing == null ? 10 : existing.count);
        style.add(content, style.text("시간 범위", 13, style.muted), 0);
        style.add(content, hours, 1);
        style.add(content, style.text("횟수", 13, style.muted), 9);
        style.add(content, count, 1);

        boolean[] limit = {existing == null || existing.mode == Rule.Mode.LIMIT};
        LinearLayout modes = style.row();
        style.add(content, modes, 12);
        Runnable showModes = new Runnable() {
            @Override
            public void run() {
                modes.removeAllViews();
                segmented(modes, "제한", limit[0], () -> {
                    limit[0] = true;
                    run();
                });
                segmented(modes, "알림", !limit[0], () -> {
                    limit[0] = false;
                    run();
                });
            }
        };
        showModes.run();

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(existing == null ? "규칙 추가" : "규칙 편집")
                .setView(scrollable(content))
                .setNegativeButton("취소", null)
                .setPositiveButton("저장", null);
        if (existing != null) {
            builder.setNeutralButton("삭제", (dialog, which) -> {
                store.deleteRule(existing.id);
                refreshRules();
            });
        }
        AlertDialog dialog = builder.create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    Integer h = positiveInt(hours);
                    Integer n = positiveInt(count);
                    if (h == null || n == null) {
                        return;
                    }
                    Rule.Mode mode = limit[0] ? Rule.Mode.LIMIT : Rule.Mode.REMIND;
                    if (existing == null) {
                        store.addRule(h, n, mode);
                    } else {
                        store.updateRule(existing.id, h, n, mode);
                    }
                    dialog.dismiss();
                    refreshRules();
                }));
        editor = dialog;
        dialog.show();
    }

    private void refreshRules() {
        int y = scroll.getScrollY();
        body.removeAllViews();
        renderLimits();
        refresh();
        scroll.post(() -> scroll.scrollTo(0, y));
    }

    private void segmented(LinearLayout group, String label, boolean selected, Runnable onClick) {
        TextView option = style.action(label, selected, onClick);
        option.setSelected(selected);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        params.setMarginEnd(style.dp(group.getChildCount() == 0 ? 5 : 0));
        group.addView(option, params);
    }

    private LinearLayout dialogContent() {
        LinearLayout content = style.column();
        style.pad(content, 24, 12);
        return content;
    }

    private ScrollView scrollable(LinearLayout content) {
        ScrollView wrapper = new ScrollView(this);
        wrapper.addView(content);
        return wrapper;
    }

    private EditText integerField(String hint, int initial) {
        EditText edit = new EditText(this);
        edit.setInputType(InputType.TYPE_CLASS_NUMBER);
        edit.setSingleLine(true);
        edit.setHint(hint);
        edit.setContentDescription(hint);
        edit.setText(String.valueOf(initial));
        edit.setSelectAllOnFocus(true);
        return edit;
    }

    private Integer positiveInt(EditText field) {
        try {
            int value = Integer.parseInt(field.getText().toString());
            if (value > 0) {
                return value;
            }
        } catch (NumberFormatException ignored) {
        }
        field.setError("1 이상의 숫자를 입력하세요");
        return null;
    }

    private void renderHistory() {
        LinearLayout range = style.row();
        segmented(range, "일간", !week, () -> setWeek(false));
        segmented(range, "주간", week, () -> setWeek(true));
        style.add(body, range, 0);

        LinearLayout dateRow = style.row();
        TextView previous = style.action("‹", false, () -> moveDate(-1));
        previous.setContentDescription("이전 기간");
        dateRow.addView(previous);
        dateLabel = style.title("", 16, style.ink);
        dateLabel.setGravity(Gravity.CENTER);
        style.weight(dateRow, dateLabel);
        nextDate = style.action("›", false, () -> moveDate(1));
        nextDate.setContentDescription("다음 기간");
        dateRow.addView(nextDate);
        style.add(body, dateRow, 8);

        LinearLayout plot = style.panel();
        style.add(plot, style.title("열람 횟수", 15, style.ink), 0);
        chart = new HistoryChartView(this, style);
        plot.addView(chart, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, style.dp(193)));
        style.add(body, plot, 8);

        LinearLayout summaryPanel = style.panel();
        LinearLayout summary = style.row();
        historyCount = metric(summary, "콘텐츠");
        historyDuration = metric(summary, "이용 시간");
        historyChange = metric(summary, week ? "지난주 대비" : "전날 대비");
        style.add(summaryPanel, summary, 0);
        style.add(body, summaryPanel, 8);

        LinearLayout byApp = style.panel();
        style.add(byApp, style.title("앱별 기록", 17, style.ink), 0);
        historyApps.clear();
        for (Platform platform : Platform.values()) {
            LinearLayout row = style.row();
            style.weight(row, style.text(platform.label, 14, style.muted));
            TextView count = style.title("", 14, style.ink);
            row.addView(count);
            historyApps.put(platform, count);
            style.add(byApp, row, 8);
        }
        style.add(body, byApp, 8);
    }

    private TextView metric(LinearLayout row, String label) {
        LinearLayout group = style.column();
        TextView main = style.title("", 19, style.paleBlue);
        main.setGravity(Gravity.CENTER);
        style.add(group, main, 0);
        TextView sub = style.text(label, 12, style.muted);
        sub.setGravity(Gravity.CENTER);
        style.add(group, sub, 3);
        style.weight(row, group);
        return main;
    }

    private void setWeek(boolean value) {
        if (week != value) {
            week = value;
            renderPage();
        }
    }

    private void moveDate(int amount) {
        date = week ? date.plusWeeks(amount) : date.plusDays(amount);
        date = earlierOf(date, LocalDate.now());
        chart.clearSelection();
        refresh();
    }

    private static LocalDate earlierOf(LocalDate first, LocalDate second) {
        return first.isBefore(second) ? first : second;
    }

    private void refresh() {
        settings.refresh();
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = LocalDate.now(zone);
        if (date.equals(lastToday)) {
            date = today;
        }
        lastToday = today;
        if (tab == 2) {
            refreshHistory(today, zone);
            return;
        }
        long midnight = startOf(today, zone);
        long nextMidnight = startOf(today.plusDays(1), zone);
        if (tab == 0) {
            refreshToday(store.visits(midnight, nextMidnight));
        } else {
            refreshLimits(store.visitCount(midnight, nextMidnight), System.currentTimeMillis());
        }
    }

    private void refreshToday(List<UsageVisit> visits) {
        updateText(todayCount, number(visits.size()) + "회");
        EnumMap<Platform, Integer> perApp = new EnumMap<>(Platform.class);
        long duration = 0;
        for (UsageVisit visit : visits) {
            duration += visit.durationMs;
            perApp.merge(visit.platform, 1, Integer::sum);
        }
        updateText(todayDuration, duration(duration));
        for (Platform platform : Platform.values()) {
            updateText(todayApps.get(platform), number(perApp.getOrDefault(platform, 0)));
        }

        boolean enabled = store.isEnabled();
        toggle.setChecked(enabled);
        boolean connected = ServiceHealth.isConnected(this);
        if (!enabled) {
            updateText(todayStatus, "Bluemind 꺼짐");
            updateText(todayStatusDetail, "가리기와 기록이 일시 중지됐어요");
            todayStatusDetail.setOnClickListener(null);
        } else if (connected) {
            updateText(todayStatus, "감지 중");
            updateText(todayStatusDetail, "가리기와 기록이 작동 중이에요");
            todayStatusDetail.setOnClickListener(null);
        } else {
            updateText(todayStatus, "접근성 연결 필요");
            updateText(todayStatusDetail, "권한 설정 열기");
            todayStatusDetail.setOnClickListener(view -> settings.showSetup());
        }
    }

    private void refreshLimits(int today, long now) {
        Integer limit = store.dailyLimit();
        if (limit == null) {
            updateText(dailyProgress, number(today) + "회 · 제한 없음");
            dailyBar.setVisibility(View.GONE);
        } else {
            dailyBar.setVisibility(View.VISIBLE);
            updateText(dailyProgress, number(today) + " / " + number(limit) + "회");
            dailyBar.setMax(limit);
            dailyBar.setProgress(today);
        }

        for (RuleProgress progress : ruleProgress) {
            Rule rule = progress.rule;
            int count = store.visitCount(now - rule.hours * HOUR_MS + 1, now + 1);
            updateText(progress.text, "최근 " + number(rule.hours) + "시간 동안 "
                    + number(count) + " / " + number(rule.count) + "회");
            progress.bar.setMax(rule.count);
            progress.bar.setProgress(count);
        }
    }

    private void refreshHistory(LocalDate today, ZoneId zone) {
        LocalDate focus = earlierOf(date, today);
        LocalDate start = week
                ? focus.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                : focus;
        int days = week ? 7 : 1;
        LocalDate end = start.plusDays(days);
        List<UsageVisit> visits = store.visits(startOf(start, zone), startOf(end, zone));
        int[] bins = new int[week ? 7 : 24];
        EnumMap<Platform, Integer> perApp = new EnumMap<>(Platform.class);
        long duration = 0;
        for (UsageVisit visit : visits) {
            java.time.ZonedDateTime local = Instant.ofEpochMilli(visit.whenMs).atZone(zone);
            int index = week ? (int) ChronoUnit.DAYS.between(start, local.toLocalDate()) : local.getHour();
            bins[index]++;
            duration += visit.durationMs;
            perApp.merge(visit.platform, 1, Integer::sum);
        }
        updateText(dateLabel, week
                ? WEEK_LABEL.format(start) + " – " + WEEK_LABEL.format(end.minusDays(1))
                : (today.equals(focus) ? "오늘" : DAY_LABEL.format(focus)));
        boolean canAdvance = !end.isAfter(today);
        nextDate.setEnabled(canAdvance);
        nextDate.setAlpha(canAdvance ? 1f : .45f);
        chart.bind(bins, week);
        updateText(historyCount, number(visits.size()) + "회");
        updateText(historyDuration, duration(duration));
        int prior = store.visitCount(startOf(start.minusDays(days), zone), startOf(start, zone));
        if (prior == 0) {
            updateText(historyChange, "—");
        } else {
            long change = Math.round((visits.size() - prior) * 100.0 / prior);
            updateText(historyChange, (change > 0 ? "+" : "") + number(change) + "%");
        }
        for (Platform platform : Platform.values()) {
            updateText(historyApps.get(platform), number(perApp.getOrDefault(platform, 0)) + "회");
        }
    }

    private static long startOf(LocalDate day, ZoneId zone) {
        return day.atStartOfDay(zone).toInstant().toEpochMilli();
    }

    private static String number(long value) {
        return NumberFormat.getIntegerInstance(Locale.KOREA).format(value);
    }

    private static String duration(long milliseconds) {
        long seconds = milliseconds / 1000;
        if (seconds < 60) {
            return number(seconds) + "초";
        }
        long minutes = seconds / 60;
        if (minutes < 60) {
            return number(minutes) + "분 " + number(seconds % 60) + "초";
        }
        return number(minutes / 60) + "시간 " + number(minutes % 60) + "분";
    }

    private static int platformColor(Platform platform) {
        return switch (platform) {
            case INSTAGRAM -> 0xFFE29AF1;
            case TIKTOK -> 0xFF77DDD9;
            case YOUTUBE -> 0xFFF28E9D;
            case X -> 0xFFB7CBDD;
        };
    }

    private static final class RuleProgress {
        final Rule rule;
        final TextView text;
        final ProgressBar bar;

        RuleProgress(Rule rule, TextView text, ProgressBar bar) {
            this.rule = rule;
            this.text = text;
            this.bar = bar;
        }
    }
}
