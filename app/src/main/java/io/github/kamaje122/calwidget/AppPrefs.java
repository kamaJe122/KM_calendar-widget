package io.github.kamaje122.calwidget;

import android.content.Context;
import android.content.SharedPreferences;

import java.time.DayOfWeek;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Stores preferences for the KM Calendar main app (independent from per-widget settings).
 */
public final class AppPrefs {

    private static final String PREFS_NAME = "km_calendar_app";

    public static final int VIEW_SCHEDULE = 0; // 時間表
    public static final int VIEW_DAY_1 = 1;    // 天
    public static final int VIEW_DAY_3 = 2;    // 3 天
    public static final int VIEW_WEEK = 3;     // 週
    public static final int VIEW_MONTH = 4;    // 月

    public static final int THEME_SYSTEM = 0;
    public static final int THEME_LIGHT = 1;
    public static final int THEME_DARK = 2;

    private static final String KEY_VIEW_MODE = "viewMode";
    private static final String KEY_THEME_MODE = "themeMode";
    private static final String KEY_START_MONDAY = "startMonday";
    private static final String KEY_APP_HIDDEN_CALS = "appHiddenCalendars";
    private static final String KEY_HOUR_HEIGHT_DP = "hourHeightDp";

    private AppPrefs() {
    }

    private static SharedPreferences sp(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static int getViewMode(Context context) {
        int m = sp(context).getInt(KEY_VIEW_MODE, VIEW_MONTH);
        return (m < VIEW_SCHEDULE || m > VIEW_MONTH) ? VIEW_MONTH : m;
    }

    public static void setViewMode(Context context, int mode) {
        sp(context).edit().putInt(KEY_VIEW_MODE, mode).apply();
    }

    public static int getThemeMode(Context context) {
        int t = sp(context).getInt(KEY_THEME_MODE, THEME_SYSTEM);
        return (t < THEME_SYSTEM || t > THEME_DARK) ? THEME_SYSTEM : t;
    }

    public static void setThemeMode(Context context, int themeMode) {
        sp(context).edit().putInt(KEY_THEME_MODE, themeMode).apply();
    }

    public static boolean isStartWeekOnMonday(Context context) {
        // Default false (Sunday first, matching Google Calendar screenshots)
        return sp(context).getBoolean(KEY_START_MONDAY, false);
    }

    public static void setStartWeekOnMonday(Context context, boolean startMonday) {
        sp(context).edit().putBoolean(KEY_START_MONDAY, startMonday).apply();
    }

    public static DayOfWeek getFirstDayOfWeek(Context context) {
        return isStartWeekOnMonday(context) ? DayOfWeek.MONDAY : DayOfWeek.SUNDAY;
    }

    public static Set<String> getAppHiddenCalendars(Context context) {
        return new HashSet<>(sp(context).getStringSet(KEY_APP_HIDDEN_CALS, Collections.emptySet()));
    }

    public static void setAppCalendarHidden(Context context, String key, boolean hidden) {
        Set<String> set = getAppHiddenCalendars(context);
        if (hidden) {
            set.add(key);
        } else {
            set.remove(key);
        }
        sp(context).edit().putStringSet(KEY_APP_HIDDEN_CALS, set).apply();
    }

    public static float getHourHeightDp(Context context) {
        float v = sp(context).getFloat(KEY_HOUR_HEIGHT_DP, 54f);
        return Math.max(30f, Math.min(140f, v));
    }

    public static void setHourHeightDp(Context context, float dp) {
        sp(context).edit().putFloat(KEY_HOUR_HEIGHT_DP, Math.max(30f, Math.min(140f, dp))).apply();
    }
}
