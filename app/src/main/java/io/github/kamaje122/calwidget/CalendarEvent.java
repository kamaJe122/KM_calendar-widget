package io.github.kamaje122.calwidget;

import android.content.Intent;
import android.text.TextUtils;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Locale;

/**
 * Unified calendar event model covering both Android CalendarContract instances
 * (Google Calendar, Exchange, local calendars) and Notion database/view entries.
 */
public final class CalendarEvent {

    public static final String EXTRA_HAS_EVENT = "km_has_event";
    public static final String EXTRA_EVENT_ID = "km_event_id";
    public static final String EXTRA_SOURCE_KEY = "km_source_key";
    public static final String EXTRA_TITLE = "km_title";
    public static final String EXTRA_LOCATION = "km_location";
    public static final String EXTRA_DESCRIPTION = "km_description";
    public static final String EXTRA_CALENDAR_NAME = "km_calendar_name";
    public static final String EXTRA_ACCOUNT_NAME = "km_account_name";
    public static final String EXTRA_STATUS_LABEL = "km_status_label";
    public static final String EXTRA_NOTION_URL = "km_notion_url";
    public static final String EXTRA_COLOR = "km_color";
    public static final String EXTRA_ALL_DAY = "km_all_day";
    public static final String EXTRA_START_EPOCH_DAY = "km_start_epoch_day";
    public static final String EXTRA_END_EPOCH_DAY = "km_end_epoch_day";
    public static final String EXTRA_BEGIN_MILLIS = "km_begin_millis";
    public static final String EXTRA_END_MILLIS = "km_end_millis";
    public static final String EXTRA_HAS_EXPLICIT_END = "km_has_explicit_end";

    public long eventId = -1L;
    public String sourceKey = "";
    public String title = "";
    public String location = "";
    public String description = "";
    public String calendarName = "";
    public String accountName = "";
    public String statusLabel = "";
    public String notionUrl = "";
    public int color = 0xFF4285F4;
    public boolean allDay;
    public long startEpochDay;
    public long endEpochDay;
    public long beginMillis;
    public long endMillis;
    public boolean hasExplicitEnd = true;

    public boolean isNotion() {
        return !TextUtils.isEmpty(notionUrl) || (sourceKey != null && sourceKey.startsWith("notion:"));
    }

    public boolean isMultiDay() {
        return endEpochDay > startEpochDay;
    }

    public boolean occursOn(long epochDay) {
        return epochDay >= startEpochDay && epochDay <= endEpochDay;
    }

    public String formatTimeRange(ZoneId zone) {
        if (allDay) {
            if (endEpochDay > startEpochDay) {
                LocalDate s = LocalDate.ofEpochDay(startEpochDay);
                LocalDate e = LocalDate.ofEpochDay(endEpochDay);
                return String.format(Locale.TAIWAN, "全天 · %d/%d – %d/%d",
                        s.getMonthValue(), s.getDayOfMonth(), e.getMonthValue(), e.getDayOfMonth());
            }
            return "全天";
        }
        String startHm = formatHm(beginMillis, zone);
        if (!hasExplicitEnd || endMillis <= beginMillis) {
            return startHm;
        }
        return startHm + " – " + formatHm(endMillis, zone);
    }

    public String formatAgendaSubtitle(ZoneId zone) {
        StringBuilder sb = new StringBuilder();
        if (!allDay) {
            sb.append(formatTimeRange(zone));
        }
        if (!TextUtils.isEmpty(location)) {
            if (sb.length() > 0) sb.append(" 於");
            sb.append(location.trim());
        } else if (isNotion() && !TextUtils.isEmpty(statusLabel)) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(statusLabel);
        }
        return sb.toString();
    }

    public static String formatHm(long millis, ZoneId zone) {
        LocalTime lt = Instant.ofEpochMilli(millis).atZone(zone).toLocalTime();
        return String.format(Locale.US, "%02d:%02d", lt.getHour(), lt.getMinute());
    }

    public void writeToIntent(Intent intent) {
        intent.putExtra(EXTRA_HAS_EVENT, true)
                .putExtra(EXTRA_EVENT_ID, eventId)
                .putExtra(EXTRA_SOURCE_KEY, sourceKey)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_LOCATION, location)
                .putExtra(EXTRA_DESCRIPTION, description)
                .putExtra(EXTRA_CALENDAR_NAME, calendarName)
                .putExtra(EXTRA_ACCOUNT_NAME, accountName)
                .putExtra(EXTRA_STATUS_LABEL, statusLabel)
                .putExtra(EXTRA_NOTION_URL, notionUrl)
                .putExtra(EXTRA_COLOR, color)
                .putExtra(EXTRA_ALL_DAY, allDay)
                .putExtra(EXTRA_START_EPOCH_DAY, startEpochDay)
                .putExtra(EXTRA_END_EPOCH_DAY, endEpochDay)
                .putExtra(EXTRA_BEGIN_MILLIS, beginMillis)
                .putExtra(EXTRA_END_MILLIS, endMillis)
                .putExtra(EXTRA_HAS_EXPLICIT_END, hasExplicitEnd);
    }

    public static CalendarEvent fromIntent(Intent intent) {
        if (intent == null || !intent.getBooleanExtra(EXTRA_HAS_EVENT, false)) {
            return null;
        }
        CalendarEvent ev = new CalendarEvent();
        ev.eventId = intent.getLongExtra(EXTRA_EVENT_ID, -1L);
        ev.sourceKey = nonNull(intent.getStringExtra(EXTRA_SOURCE_KEY));
        ev.title = nonNull(intent.getStringExtra(EXTRA_TITLE));
        ev.location = nonNull(intent.getStringExtra(EXTRA_LOCATION));
        ev.description = nonNull(intent.getStringExtra(EXTRA_DESCRIPTION));
        ev.calendarName = nonNull(intent.getStringExtra(EXTRA_CALENDAR_NAME));
        ev.accountName = nonNull(intent.getStringExtra(EXTRA_ACCOUNT_NAME));
        ev.statusLabel = nonNull(intent.getStringExtra(EXTRA_STATUS_LABEL));
        ev.notionUrl = nonNull(intent.getStringExtra(EXTRA_NOTION_URL));
        ev.color = intent.getIntExtra(EXTRA_COLOR, 0xFF4285F4);
        ev.allDay = intent.getBooleanExtra(EXTRA_ALL_DAY, false);
        ev.startEpochDay = intent.getLongExtra(EXTRA_START_EPOCH_DAY, LocalDate.now().toEpochDay());
        ev.endEpochDay = intent.getLongExtra(EXTRA_END_EPOCH_DAY, ev.startEpochDay);
        ev.beginMillis = intent.getLongExtra(EXTRA_BEGIN_MILLIS, 0L);
        ev.endMillis = intent.getLongExtra(EXTRA_END_MILLIS, 0L);
        ev.hasExplicitEnd = intent.getBooleanExtra(EXTRA_HAS_EXPLICIT_END, true);
        return ev;
    }

    private static String nonNull(String s) {
        return s == null ? "" : s;
    }
}
