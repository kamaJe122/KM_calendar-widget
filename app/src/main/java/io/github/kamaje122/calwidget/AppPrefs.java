package io.github.kamaje122.calwidget;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
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

    /** Notification source scope modes. */
    public static final int NOTIF_MODE_ALL = 0;         // Google／系統行事曆 ＋ Notion
    public static final int NOTIF_MODE_NOTION_ONLY = 1; // 僅通知 Notion 行程
    public static final int NOTIF_MODE_OFF = 2;         // 關閉通知

    private static final String KEY_VIEW_MODE = "viewMode";
    private static final String KEY_THEME_MODE = "themeMode";
    private static final String KEY_START_MONDAY = "startMonday";
    private static final String KEY_APP_HIDDEN_CALS = "appHiddenCalendars";
    private static final String KEY_HOUR_HEIGHT_DP = "hourHeightDp_v2";

    private static final String KEY_NOTIF_MODE = "notifMode";
    private static final String KEY_NOTION_DEFAULT_REMINDER_MIN = "notionDefaultReminderMin";
    private static final String KEY_SNOOZE_MINUTES = "snoozeMinutes";
    private static final String KEY_ASKED_POST_NOTIF = "askedPostNotif";
    private static final String PREFIX_NOTION_REMINDERS = "notionRem_";
    private static final String KEY_EVENT_DRAG_HOLD_MS = "eventDragHoldMs";

    /** Default hold duration (ms) before an event enters drag-to-reschedule mode (0 = disabled). */
    public static final int DEFAULT_EVENT_DRAG_HOLD_MS = 500;

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

    /** Returns <= 0f when in full 00:00–24:00 fit mode (default), or explicit zoomed dp (..140f). */
    public static float getHourHeightDp(Context context) {
        float v = sp(context).getFloat(KEY_HOUR_HEIGHT_DP, -1f);
        if (v <= 0f) return -1f;
        return Math.min(140f, v);
    }

    public static void setHourHeightDp(Context context, float dp) {
        float stored = (dp <= 0f) ? -1f : Math.min(140f, dp);
        sp(context).edit().putFloat(KEY_HOUR_HEIGHT_DP, stored).apply();
    }

    // ===================== Event Notification Preferences =====================

    public static int getNotifMode(Context context) {
        int m = sp(context).getInt(KEY_NOTIF_MODE, NOTIF_MODE_ALL);
        return (m < NOTIF_MODE_ALL || m > NOTIF_MODE_OFF) ? NOTIF_MODE_ALL : m;
    }

    public static void setNotifMode(Context context, int mode) {
        sp(context).edit().putInt(KEY_NOTIF_MODE, mode).apply();
    }

    /** Default reminder offset for timed Notion events in minutes (default 10; -1 = disabled). */
    public static int getNotionDefaultReminderMinutes(Context context) {
        return sp(context).getInt(KEY_NOTION_DEFAULT_REMINDER_MIN, 10);
    }

    public static void setNotionDefaultReminderMinutes(Context context, int minutes) {
        sp(context).edit().putInt(KEY_NOTION_DEFAULT_REMINDER_MIN, minutes).apply();
    }

    /** Snooze duration in minutes when tapping 「延後」 on a notification (default 10). */
    public static int getSnoozeMinutes(Context context) {
        int m = sp(context).getInt(KEY_SNOOZE_MINUTES, 10);
        return Math.max(1, Math.min(1440, m));
    }

    public static void setSnoozeMinutes(Context context, int minutes) {
        sp(context).edit().putInt(KEY_SNOOZE_MINUTES, Math.max(1, Math.min(1440, minutes))).apply();
    }

    public static boolean hasAskedPostNotifications(Context context) {
        return sp(context).getBoolean(KEY_ASKED_POST_NOTIF, false);
    }

    public static void setAskedPostNotifications(Context context, boolean asked) {
        sp(context).edit().putBoolean(KEY_ASKED_POST_NOTIF, asked).apply();
    }

    private static String normNotionId(String pageId) {
        return pageId == null ? "" : pageId.replace("-", "").trim().toLowerCase(Locale.ROOT);
    }

    public static boolean hasCustomNotionReminders(Context context, String notionPageId) {
        String id = normNotionId(notionPageId);
        return !id.isEmpty() && sp(context).contains(PREFIX_NOTION_REMINDERS + id);
    }

    public static List<Integer> getCustomNotionReminders(Context context, String notionPageId) {
        String id = normNotionId(notionPageId);
        if (id.isEmpty()) return Collections.emptyList();
        String raw = sp(context).getString(PREFIX_NOTION_REMINDERS + id, null);
        if (raw == null || raw.trim().isEmpty()) return Collections.emptyList();
        List<Integer> out = new ArrayList<>();
        for (String part : raw.split(",")) {
            try {
                int m = Integer.parseInt(part.trim());
                if (m >= 0 && !out.contains(m)) out.add(m);
            } catch (NumberFormatException ignored) {
            }
        }
        Collections.sort(out);
        return out;
    }

    public static void setCustomNotionReminders(Context context, String notionPageId, List<Integer> minutes) {
        String id = normNotionId(notionPageId);
        if (id.isEmpty()) return;
        StringBuilder sb = new StringBuilder();
        if (minutes != null) {
            List<Integer> sorted = new ArrayList<>();
            for (Integer m : minutes) {
                if (m != null && m >= 0 && !sorted.contains(m)) sorted.add(m);
            }
            Collections.sort(sorted);
            for (int i = 0; i < sorted.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(sorted.get(i));
            }
        }
        sp(context).edit().putString(PREFIX_NOTION_REMINDERS + id, sb.toString()).apply();
    }

    private static final String PREFIX_NOTION_COLOR = "notionEvtColor_";

    /** Resolves effective reminder offsets (minutes before start) for a Notion event. */
    public static List<Integer> getEffectiveNotionReminders(Context context, CalendarEvent ev) {
        if (ev == null) return Collections.emptyList();
        if (!TextUtils.isEmpty(ev.notionPageId) && hasCustomNotionReminders(context, ev.notionPageId)) {
            return getCustomNotionReminders(context, ev.notionPageId);
        }
        if (ev.allDay) {
            return Collections.emptyList();
        }
        int defMin = getNotionDefaultReminderMinutes(context);
        if (defMin < 0) return Collections.emptyList();
        List<Integer> single = new ArrayList<>(1);
        single.add(defMin);
        return single;
    }

    // ===================== Per-Event Notion Custom Color Overrides =====================

    public static boolean hasCustomNotionEventColor(Context context, String notionPageId) {
        String id = normNotionId(notionPageId);
        return !id.isEmpty() && sp(context).contains(PREFIX_NOTION_COLOR + id);
    }

    public static int getCustomNotionEventColor(Context context, String notionPageId, int fallbackColor) {
        String id = normNotionId(notionPageId);
        if (id.isEmpty()) return fallbackColor;
        SharedPreferences prefs = sp(context);
        String key = PREFIX_NOTION_COLOR + id;
        return prefs.contains(key) ? (prefs.getInt(key, fallbackColor) | 0xFF000000) : fallbackColor;
    }

    public static void setCustomNotionEventColor(Context context, String notionPageId, int argbColor) {
        String id = normNotionId(notionPageId);
        if (id.isEmpty()) return;
        sp(context).edit().putInt(PREFIX_NOTION_COLOR + id, argbColor | 0xFF000000).apply();
    }

    public static void clearCustomNotionEventColor(Context context, String notionPageId) {
        String id = normNotionId(notionPageId);
        if (id.isEmpty()) return;
        sp(context).edit().remove(PREFIX_NOTION_COLOR + id).apply();
    }

    public static int resolveNotionEventColor(Context context, String notionPageId, int sourceDefaultColor) {
        return getCustomNotionEventColor(context, notionPageId, sourceDefaultColor);
    }

    // ===================== Event Long-Press Drag-to-Reschedule Delay =====================

    /**
     * Hold duration in milliseconds required to start dragging an event on the timeline.
     * Returns 0 when disabled, otherwise clamped to [150, 3000]. Default is 500 ms (0.5s).
     */
    public static int getEventDragHoldMs(Context context) {
        int raw = sp(context).getInt(KEY_EVENT_DRAG_HOLD_MS, DEFAULT_EVENT_DRAG_HOLD_MS);
        if (raw <= 0) return 0;
        return Math.max(150, Math.min(3000, raw));
    }

    public static void setEventDragHoldMs(Context context, int delayMs) {
        int normalized = (delayMs <= 0) ? 0 : Math.max(150, Math.min(3000, delayMs));
        sp(context).edit().putInt(KEY_EVENT_DRAG_HOLD_MS, normalized).apply();
    }

    public static String formatDragHoldLabel(int delayMs) {
        if (delayMs <= 0) return "已關閉";
        if (delayMs % 100 == 0) {
            return String.format(Locale.US, "%.1f 秒", delayMs / 1000f);
        }
        return String.format(Locale.US, "%.2f 秒", delayMs / 1000f);
    }
}
