package com.bluemind.app;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Pattern;

public final class MindStore {
    public static final int DEFAULT_DAILY_LIMIT = 50;

    public enum CounterSize { SMALL, MEDIUM, LARGE }
    public enum ColorTheme { RAIN, DEEP_OCEAN, MOONLIGHT }

    private static final String ENABLED_KEY = "enabled";
    private static final String DAILY_LIMIT_KEY = "daily_limit";
    private static final String COUNTER_VISIBLE_KEY = "counter_visible";
    private static final String COUNTER_SIZE_KEY = "counter_size";
    private static final String COLOR_THEME_KEY = "color_theme";
    private static final Pattern PACKAGE_NAME =
            Pattern.compile("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+");
    private static final String TIMESTAMP_RANGE = "viewed_at >= ? AND viewed_at < ?";
    private static final String COUNT_VISITS =
            "SELECT COUNT(*) FROM (SELECT 1 FROM visits WHERE " + TIMESTAMP_RANGE + " LIMIT ?)";

    private static MindStore instance;

    private final SharedPreferences settings;
    private final String ownPackage;
    private final UsageDatabase database;
    private List<Rule> cachedRules;

    private MindStore(Context context) {
        settings = context.getSharedPreferences("bluemind_settings", Context.MODE_PRIVATE);
        ownPackage = context.getPackageName();
        database = new UsageDatabase(context);
        database.setWriteAheadLoggingEnabled(true);
    }

    public static synchronized MindStore get(Context context) {
        if (instance == null) {
            instance = new MindStore(context.getApplicationContext());
        }
        return instance;
    }

    public boolean isEnabled() {
        return settings.getBoolean(ENABLED_KEY, true);
    }

    public synchronized void setEnabled(boolean enabled) {
        save(settings.edit().putBoolean(ENABLED_KEY, enabled));
    }

    public Integer dailyLimit() {
        int limit = settings.getInt(DAILY_LIMIT_KEY, DEFAULT_DAILY_LIMIT);
        return limit == 0 ? null : limit;
    }

    public synchronized void setDailyLimit(Integer limit) {
        if (limit != null && limit < 1) {
            throw new IllegalArgumentException("The daily limit must be positive.");
        }
        save(settings.edit().putInt(DAILY_LIMIT_KEY, limit == null ? 0 : limit));
    }

    public String customPackage(Platform platform) {
        return settings.getString(packageKey(platform), "");
    }

    public synchronized void setCustomPackage(Platform platform, String packageName) {
        String validated = validatePackage(platform, packageName, ownPackage, this::customPackage);
        save(settings.edit().putString(packageKey(platform), validated));
    }

    public boolean isCounterVisible() {
        return settings.getBoolean(COUNTER_VISIBLE_KEY, true);
    }

    public synchronized void setCounterVisible(boolean visible) {
        save(settings.edit().putBoolean(COUNTER_VISIBLE_KEY, visible));
    }

    public CounterSize counterSize() {
        return readEnum(COUNTER_SIZE_KEY, CounterSize.MEDIUM);
    }

    public synchronized void setCounterSize(CounterSize size) {
        save(settings.edit().putString(COUNTER_SIZE_KEY, size.name()));
    }

    public ColorTheme colorTheme() {
        return readEnum(COLOR_THEME_KEY, ColorTheme.RAIN);
    }

    public synchronized void setColorTheme(ColorTheme theme) {
        save(settings.edit().putString(COLOR_THEME_KEY, theme.name()));
    }

    public synchronized List<Rule> rules() {
        if (cachedRules == null) {
            List<Rule> rules = new ArrayList<>();
            try (Cursor rows = database.getReadableDatabase().query(
                    "rules", new String[]{"id", "hours", "item_count", "mode"},
                    null, null, null, null, "id ASC")) {
                while (rows.moveToNext()) {
                    rules.add(new Rule(rows.getLong(0), rows.getInt(1), rows.getInt(2),
                            Rule.Mode.valueOf(rows.getString(3))));
                }
            }
            cachedRules = Collections.unmodifiableList(rules);
        }
        return cachedRules;
    }

    public synchronized long addRule(int hours, int count, Rule.Mode mode) {
        Rule rule = new Rule(0, hours, count, mode);
        long id = database.getWritableDatabase().insertOrThrow("rules", null, values(rule));
        cachedRules = null;
        return id;
    }

    public synchronized void updateRule(long id, int hours, int count, Rule.Mode mode) {
        Rule rule = new Rule(id, hours, count, mode);
        database.getWritableDatabase().update("rules", values(rule), "id = ?",
                new String[]{Long.toString(id)});
        cachedRules = null;
    }

    public synchronized void deleteRule(long id) {
        database.getWritableDatabase().delete("rules", "id = ?", new String[]{Long.toString(id)});
        cachedRules = null;
    }

    public synchronized boolean isBlocked(long nowMs) {
        if (!isEnabled()) {
            return false;
        }
        SQLiteDatabase db = database.getReadableDatabase();
        return RuleEngine.isBlocked(dailyLimit(), rules(), nowMs, ZoneId.systemDefault(), count(db));
    }

    public synchronized Admission admit(Platform platform, ContentTarget.Kind kind, long nowMs) {
        Objects.requireNonNull(platform);
        Objects.requireNonNull(kind);
        if (!isEnabled()) {
            return Admission.REJECTED;
        }
        List<Rule> rules = rules();
        SQLiteDatabase db = database.getWritableDatabase();
        db.beginTransactionNonExclusive();
        try {
            RuleEngine.Count counts = count(db);
            if (RuleEngine.isBlocked(dailyLimit(), rules, nowMs, ZoneId.systemDefault(), counts)) {
                return Admission.REJECTED;
            }
            List<Rule> reminders = RuleEngine.remindersForNextVisit(rules, nowMs, counts);
            ContentValues visit = new ContentValues();
            visit.put("viewed_at", nowMs);
            visit.put("platform", platform.name());
            visit.put("kind", kind.name());
            long id = db.insertOrThrow("visits", null, visit);
            db.setTransactionSuccessful();
            return new Admission(id, Collections.unmodifiableList(reminders));
        } finally {
            db.endTransaction();
        }
    }

    public synchronized void addDuration(long visitId, long durationDeltaMs) {
        if (visitId <= 0 || durationDeltaMs <= 0) {
            return;
        }
        database.getWritableDatabase().execSQL(
                "UPDATE visits SET duration_ms = duration_ms + ? WHERE id = ?",
                new Object[]{durationDeltaMs, visitId});
    }

    public synchronized int visitCount(long fromInclusive, long toExclusive) {
        return count(database.getReadableDatabase()).upTo(fromInclusive, toExclusive, Integer.MAX_VALUE);
    }

    public synchronized List<UsageVisit> visits(long fromInclusive, long toExclusive) {
        List<UsageVisit> result = new ArrayList<>();
        try (Cursor rows = database.getReadableDatabase().query(
                "visits", new String[]{"viewed_at", "duration_ms", "platform", "kind"},
                TIMESTAMP_RANGE, new String[]{Long.toString(fromInclusive), Long.toString(toExclusive)},
                null, null, "viewed_at ASC, id ASC")) {
            while (rows.moveToNext()) {
                result.add(new UsageVisit(rows.getLong(0), rows.getLong(1),
                        Platform.valueOf(rows.getString(2)), ContentTarget.Kind.valueOf(rows.getString(3))));
            }
        }
        return result;
    }

    private static void save(SharedPreferences.Editor editor) {
        if (!editor.commit()) {
            throw new IllegalStateException("Failed to persist app settings.");
        }
    }

    private <E extends Enum<E>> E readEnum(String key, E defaultValue) {
        String value = settings.getString(key, defaultValue.name());
        try {
            return Enum.valueOf(defaultValue.getDeclaringClass(), value);
        } catch (IllegalArgumentException ignored) {
            return defaultValue;
        }
    }

    private static String packageKey(Platform platform) {
        return "custom_package_" + platform.name();
    }

    static String validatePackage(Platform platform, String packageName, String ownPackage,
                                  Function<Platform, String> selected) {
        String name = packageName.trim();
        if (name.isEmpty()) {
            return "";
        }
        if (!PACKAGE_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("패키지 이름 형식을 확인해 주세요.");
        }
        if (ownPackage.equals(name)) {
            throw new IllegalArgumentException("Bluemind는 감지할 앱으로 선택할 수 없어요.");
        }
        Platform known = Platform.fromPackage(name);
        if (known != null && known != platform) {
            throw new IllegalArgumentException(known.label + "에서 사용되는 패키지예요.");
        }
        for (Platform other : Platform.values()) {
            if (other != platform && name.equals(selected.apply(other))) {
                throw new IllegalArgumentException(other.label + "에 이미 지정된 앱이에요.");
            }
        }
        return name;
    }

    private static ContentValues values(Rule rule) {
        ContentValues values = new ContentValues();
        values.put("hours", rule.hours);
        values.put("item_count", rule.count);
        values.put("mode", rule.mode.name());
        return values;
    }

    private static RuleEngine.Count count(SQLiteDatabase database) {
        return (from, to, maximum) -> (int) DatabaseUtils.longForQuery(database, COUNT_VISITS,
                new String[]{Long.toString(from), Long.toString(to), Integer.toString(maximum)});
    }

    public static final class Admission {
        private static final Admission REJECTED = new Admission(0, Collections.emptyList());

        public final long visitId;
        public final List<Rule> reminders;

        private Admission(long visitId, List<Rule> reminders) {
            this.visitId = visitId;
            this.reminders = reminders;
        }
    }

    private static final class UsageDatabase extends SQLiteOpenHelper {
        UsageDatabase(Context context) {
            super(context, "bluemind.db", null, 1);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE rules (id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "hours INTEGER NOT NULL CHECK(hours > 0), "
                    + "item_count INTEGER NOT NULL CHECK(item_count > 0), "
                    + "mode TEXT NOT NULL CHECK(mode IN ('LIMIT', 'REMIND')))");
            db.execSQL("CREATE TABLE visits (id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "viewed_at INTEGER NOT NULL, platform TEXT NOT NULL, kind TEXT NOT NULL, "
                    + "duration_ms INTEGER NOT NULL DEFAULT 0 CHECK(duration_ms >= 0))");
            db.execSQL("CREATE INDEX visits_by_time ON visits (viewed_at)");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            throw new IllegalStateException("No migration from version " + oldVersion
                    + " to version " + newVersion);
        }
    }
}
