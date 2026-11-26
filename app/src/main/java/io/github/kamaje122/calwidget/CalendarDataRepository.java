package io.github.kamaje122.calwidget;

import android.Manifest;
import android.accounts.Account;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.CalendarContract;
import android.text.TextUtils;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.JulianFields;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

/**
 * Unified repository querying & mutating Android CalendarContract (Google Calendars, local calendars)
 * and NotionManager cached databases across arbitrary date ranges.
 */
public final class CalendarDataRepository {

    private static final long JULIAN_EPOCH_1970 =
            LocalDate.ofEpochDay(0).getLong(JulianFields.JULIAN_DAY);

    public static final class SystemCalendarItem {
        public final long id;
        public final String displayName;
        public final String accountName;
        public final String accountType;
        public final int color;
        public final boolean synced;
        public final boolean writable;

        SystemCalendarItem(long id, String displayName, String accountName, String accountType,
                           int color, boolean synced, boolean writable) {
            this.id = id;
            this.displayName = displayName;
            this.accountName = accountName;
            this.accountType = accountType;
            this.color = color;
            this.synced = synced;
            this.writable = writable;
        }
    }

    public static final class SystemEventMeta {
        public final long eventId;
        public final long calendarId;
        public final String accountName;
        public final String accountType;
        public final boolean writable;
        public final boolean recurring;

        SystemEventMeta(long eventId, long calendarId, String accountName, String accountType,
                        boolean writable, boolean recurring) {
            this.eventId = eventId;
            this.calendarId = calendarId;
            this.accountName = accountName;
            this.accountType = accountType;
            this.writable = writable;
            this.recurring = recurring;
        }
    }

    private CalendarDataRepository() {
    }

    public static boolean hasReadPermission(Context context) {
        return context.checkSelfPermission(Manifest.permission.READ_CALENDAR)
                == PackageManager.PERMISSION_GRANTED;
    }

    public static boolean hasWritePermission(Context context) {
        return context.checkSelfPermission(Manifest.permission.WRITE_CALENDAR)
                == PackageManager.PERMISSION_GRANTED;
    }

    public static List<SystemCalendarItem> loadSystemCalendars(Context context) {
        if (!hasReadPermission(context)) return Collections.emptyList();
        List<SystemCalendarItem> out = new ArrayList<>();
        String[] proj = new String[]{
                CalendarContract.Calendars._ID,
                CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                CalendarContract.Calendars.ACCOUNT_NAME,
                CalendarContract.Calendars.ACCOUNT_TYPE,
                CalendarContract.Calendars.CALENDAR_COLOR,
                CalendarContract.Calendars.VISIBLE,
                CalendarContract.Calendars.SYNC_EVENTS,
                CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL
        };
        String sort = CalendarContract.Calendars.ACCOUNT_NAME + " COLLATE NOCASE ASC, "
                + CalendarContract.Calendars.IS_PRIMARY + " DESC, "
                + CalendarContract.Calendars.CALENDAR_DISPLAY_NAME + " COLLATE NOCASE ASC";

        try (Cursor c = context.getContentResolver().query(
                CalendarContract.Calendars.CONTENT_URI, proj, null, null, sort)) {
            if (c != null) {
                while (c.moveToNext()) {
                    long id = c.getLong(0);
                    String name = c.getString(1);
                    String acc = c.getString(2);
                    String type = c.getString(3);
                    int color = AppTheme.normalizePillColor(c.getInt(4));
                    boolean synced = c.getInt(5) != 0 && c.getInt(6) != 0;
                    int access = c.getInt(7);
                    boolean writable = access >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR;

                    out.add(new SystemCalendarItem(
                            id,
                            TextUtils.isEmpty(name) ? context.getString(R.string.untitled) : name,
                            acc == null ? "" : acc,
                            type == null ? "" : type,
                            color,
                            synced,
                            writable
                    ));
                }
            }
        } catch (RuntimeException ignored) {
        }
        return out;
    }

    public static SystemEventMeta inspectSystemEvent(Context context, long eventId) {
        if (eventId < 0 || !hasReadPermission(context)) return null;
        Uri uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId);
        String[] proj = new String[]{
                CalendarContract.Events.CALENDAR_ID,
                CalendarContract.Calendars.ACCOUNT_NAME,
                CalendarContract.Calendars.ACCOUNT_TYPE,
                CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
                CalendarContract.Events.RRULE,
                CalendarContract.Events.RDATE
        };
        try (Cursor c = context.getContentResolver().query(uri, proj, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                long calId = c.getLong(0);
                String accName = nonNull(c.getString(1));
                String accType = nonNull(c.getString(2));
                int access = c.getInt(3);
                boolean writable = access >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR;
                boolean recurring = !TextUtils.isEmpty(c.getString(4)) || !TextUtils.isEmpty(c.getString(5));
                return new SystemEventMeta(eventId, calId, accName, accType, writable, recurring);
            }
        } catch (RuntimeException ignored) {
        }
        return null;
    }

    /**
     * Loads all Google/System + Notion events overlapping [startDate, endDate] inclusive.
     */
    public static List<CalendarEvent> loadEventsInRange(
            Context context,
            LocalDate startDate,
            LocalDate endDate,
            Set<String> hiddenCalendars) {

        ZoneId zone = ZoneId.systemDefault();
        List<CalendarEvent> all = new ArrayList<>();
        long minEpochDay = startDate.toEpochDay();
        long maxEpochDay = endDate.toEpochDay();

        if (hasReadPermission(context)) {
            long queryStartMs = startDate.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
            long queryEndMs = endDate.plusDays(2).atStartOfDay(zone).toInstant().toEpochMilli();

            Uri.Builder builder = CalendarContract.Instances.CONTENT_URI.buildUpon();
            ContentUris.appendId(builder, queryStartMs);
            ContentUris.appendId(builder, queryEndMs);

            String[] proj = new String[]{
                    CalendarContract.Instances.EVENT_ID,
                    CalendarContract.Instances.TITLE,
                    CalendarContract.Instances.BEGIN,
                    CalendarContract.Instances.END,
                    CalendarContract.Instances.ALL_DAY,
                    CalendarContract.Instances.START_DAY,
                    CalendarContract.Instances.END_DAY,
                    CalendarContract.Instances.EVENT_LOCATION,
                    CalendarContract.Instances.DESCRIPTION,
                    CalendarContract.Instances.DISPLAY_COLOR,
                    CalendarContract.Instances.SELF_ATTENDEE_STATUS,
                    CalendarContract.Instances.CALENDAR_ID,
                    CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
                    CalendarContract.Calendars.ACCOUNT_NAME
            };

            try (Cursor c = context.getContentResolver().query(
                    builder.build(),
                    proj,
                    CalendarContract.Instances.VISIBLE + " = 1",
                    null,
                    CalendarContract.Instances.BEGIN + " ASC")) {
                if (c != null) {
                    while (c.moveToNext()) {
                        if (c.getInt(10) == CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED) {
                            continue;
                        }
                        long calId = c.getLong(11);
                        String calKey = String.valueOf(calId);
                        if (hiddenCalendars != null && hiddenCalendars.contains(calKey)) {
                            continue;
                        }

                        long begin = c.getLong(2);
                        long end = Math.max(begin, c.getLong(3));
                        boolean allDay = c.getInt(4) != 0;

                        long startEpoch;
                        long endEpoch;
                        if (allDay) {
                            long sJulian = c.getLong(5);
                            long eJulian = c.getLong(6);
                            startEpoch = sJulian - JULIAN_EPOCH_1970;
                            endEpoch = Math.max(startEpoch, eJulian - JULIAN_EPOCH_1970);
                        } else {
                            startEpoch = Instant.ofEpochMilli(begin).atZone(zone).toLocalDate().toEpochDay();
                            long adjEnd = end > begin ? (end - 1L) : begin;
                            endEpoch = Math.max(startEpoch,
                                    Instant.ofEpochMilli(adjEnd).atZone(zone).toLocalDate().toEpochDay());
                        }

                        if (endEpoch < minEpochDay || startEpoch > maxEpochDay) {
                            continue;
                        }

                        CalendarEvent ev = new CalendarEvent();
                        ev.eventId = c.getLong(0);
                        ev.sourceKey = calKey;
                        String rawTitle = c.getString(1);
                        ev.title = TextUtils.isEmpty(rawTitle)
                                ? context.getString(R.string.untitled) : rawTitle;
                        ev.beginMillis = begin;
                        ev.endMillis = end;
                        ev.allDay = allDay;
                        ev.startEpochDay = startEpoch;
                        ev.endEpochDay = endEpoch;
                        ev.hasExplicitEnd = end > begin;
                        ev.location = nonNull(c.getString(7));
                        ev.description = nonNull(c.getString(8));
                        ev.color = AppTheme.normalizePillColor(c.getInt(9));
                        ev.calendarName = nonNull(c.getString(12));
                        ev.accountName = nonNull(c.getString(13));
                        all.add(ev);
                    }
                }
            } catch (RuntimeException ignored) {
            }
        }

        // Merge Notion events
        all.addAll(NotionManager.loadAppEventsInRange(context, startDate, endDate, hiddenCalendars));

        sortEvents(all);
        return all;
    }

    public static Map<Long, List<CalendarEvent>> groupEventsByEpochDay(
            List<CalendarEvent> events, long minEpochDay, long maxEpochDay) {
        Map<Long, List<CalendarEvent>> map = new HashMap<>();
        for (CalendarEvent ev : events) {
            long s = Math.max(minEpochDay, ev.startEpochDay);
            long e = Math.min(maxEpochDay, ev.endEpochDay);
            for (long d = s; d <= e; d++) {
                map.computeIfAbsent(d, k -> new ArrayList<>()).add(ev);
            }
        }
        for (List<CalendarEvent> dayList : map.values()) {
            sortEvents(dayList);
        }
        return map;
    }

    public static void sortEvents(List<CalendarEvent> list) {
        list.sort((a, b) -> {
            if (a.allDay != b.allDay) {
                return a.allDay ? -1 : 1;
            }
            if (a.isMultiDay() != b.isMultiDay()) {
                return a.isMultiDay() ? -1 : 1;
            }
            int cmp = Long.compare(a.beginMillis, b.beginMillis);
            if (cmp != 0) return cmp;
            return a.title.compareToIgnoreCase(b.title);
        });
    }

    public static List<CalendarEvent> searchEvents(
            Context context, String rawQuery, Set<String> hiddenCalendars) {
        String q = rawQuery == null ? "" : rawQuery.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) return Collections.emptyList();

        LocalDate today = LocalDate.now();
        List<CalendarEvent> pool = loadEventsInRange(
                context, today.minusDays(180), today.plusDays(270), hiddenCalendars);
        List<CalendarEvent> matched = new ArrayList<>();
        for (CalendarEvent ev : pool) {
            if (ev.title.toLowerCase(Locale.ROOT).contains(q)
                    || ev.location.toLowerCase(Locale.ROOT).contains(q)
                    || ev.description.toLowerCase(Locale.ROOT).contains(q)
                    || ev.calendarName.toLowerCase(Locale.ROOT).contains(q)
                    || ev.statusLabel.toLowerCase(Locale.ROOT).contains(q)) {
                matched.add(ev);
                if (matched.size() >= 150) break;
            }
        }
        return matched;
    }

    public static long insertSystemEvent(
            Context context,
            long calendarId,
            String title,
            String location,
            String description,
            boolean allDay,
            LocalDate startDate,
            LocalDate endDate,
            long beginMillis,
            long endMillis) {
        if (!hasWritePermission(context)) return -1L;
        try {
            ContentValues cv = new ContentValues();
            cv.put(CalendarContract.Events.CALENDAR_ID, calendarId);
            cv.put(CalendarContract.Events.TITLE, title);
            cv.put(CalendarContract.Events.EVENT_LOCATION, location == null ? "" : location);
            cv.put(CalendarContract.Events.DESCRIPTION, description == null ? "" : description);
            populateEventTimeValues(cv, allDay, startDate, endDate, beginMillis, endMillis);

            ContentResolver cr = context.getContentResolver();
            Uri uri = cr.insert(CalendarContract.Events.CONTENT_URI, cv);
            if (uri != null && uri.getLastPathSegment() != null) {
                long id = Long.parseLong(uri.getLastPathSegment());
                triggerExpeditedCalendarSync(context, calendarId);
                CalendarWidgetProvider.updateAll(context);
                return id;
            }
        } catch (RuntimeException ignored) {
        }
        return -1L;
    }

    /**
     * Updates an existing system / Google Calendar event directly in CalendarContract and
     * immediately requests an expedited sync back to the source account (e.g. Google Calendar).
     *
     * @param onlyThisInstance When the event is part of a recurring series, true creates a
     *                         single-instance exception via CONTENT_EXCEPTION_URI; false updates
     *                         the recurring series itself.
     */
    public static boolean updateSystemEvent(
            Context context,
            long eventId,
            long targetCalendarId,
            long originalBeginMillis,
            boolean originalAllDay,
            boolean isRecurringSeries,
            boolean onlyThisInstance,
            String title,
            String location,
            String description,
            boolean allDay,
            LocalDate startDate,
            LocalDate endDate,
            long beginMillis,
            long endMillis) {
        if (eventId < 0 || !hasWritePermission(context)) return false;
        ContentResolver cr = context.getContentResolver();
        try {
            if (isRecurringSeries && onlyThisInstance) {
                ContentValues ex = new ContentValues();
                ex.put(CalendarContract.Events.ORIGINAL_INSTANCE_TIME, originalBeginMillis);
                ex.put(CalendarContract.Events.ORIGINAL_ALL_DAY, originalAllDay ? 1 : 0);
                ex.put(CalendarContract.Events.TITLE, title);
                ex.put(CalendarContract.Events.EVENT_LOCATION, location == null ? "" : location);
                ex.put(CalendarContract.Events.DESCRIPTION, description == null ? "" : description);
                populateEventTimeValues(ex, allDay, startDate, endDate, beginMillis, endMillis);

                Uri exUri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_EXCEPTION_URI, eventId);
                Uri res = cr.insert(exUri, ex);
                if (res != null) {
                    triggerExpeditedCalendarSync(context, targetCalendarId);
                    CalendarWidgetProvider.updateAll(context);
                    return true;
                }
                return false;
            }

            ContentValues cv = new ContentValues();
            if (!isRecurringSeries && targetCalendarId > 0) {
                cv.put(CalendarContract.Events.CALENDAR_ID, targetCalendarId);
            }
            cv.put(CalendarContract.Events.TITLE, title);
            cv.put(CalendarContract.Events.EVENT_LOCATION, location == null ? "" : location);
            cv.put(CalendarContract.Events.DESCRIPTION, description == null ? "" : description);

            if (isRecurringSeries) {
                // Recurring series in CalendarContract use DTSTART + DURATION (DTEND must remain null)
                if (allDay) {
                    long days = Math.max(1L, (endDate.toEpochDay() - startDate.toEpochDay()) + 1L);
                    cv.put(CalendarContract.Events.ALL_DAY, 1);
                    cv.put(CalendarContract.Events.DTSTART, startDate.toEpochDay() * 86_400_000L);
                    cv.putNull(CalendarContract.Events.DTEND);
                    cv.put(CalendarContract.Events.DURATION, "P" + days + "D");
                    cv.put(CalendarContract.Events.EVENT_TIMEZONE, "UTC");
                } else {
                    long durSec = Math.max(900L, (Math.max(beginMillis + 900_000L, endMillis) - beginMillis) / 1000L);
                    cv.put(CalendarContract.Events.ALL_DAY, 0);
                    cv.put(CalendarContract.Events.DTSTART, beginMillis);
                    cv.putNull(CalendarContract.Events.DTEND);
                    cv.put(CalendarContract.Events.DURATION, "PT" + durSec + "S");
                    cv.put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().getID());
                }
            } else {
                populateEventTimeValues(cv, allDay, startDate, endDate, beginMillis, endMillis);
                cv.putNull(CalendarContract.Events.DURATION);
            }

            Uri uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId);
            int rows = cr.update(uri, cv, null, null);
            if (rows > 0) {
                triggerExpeditedCalendarSync(context, targetCalendarId);
                CalendarWidgetProvider.updateAll(context);
                return true;
            }
        } catch (RuntimeException ignored) {
        }
        return false;
    }

    public static boolean deleteSystemEvent(Context context, long eventId) {
        if (eventId < 0 || !hasWritePermission(context)) return false;
        try {
            SystemEventMeta meta = inspectSystemEvent(context, eventId);
            Uri uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId);
            int rows = context.getContentResolver().delete(uri, null, null);
            if (rows > 0) {
                if (meta != null) {
                    triggerExpeditedAccountSync(meta.accountName, meta.accountType);
                } else {
                    triggerAllExpeditedSyncs(context);
                }
                CalendarWidgetProvider.updateAll(context);
                return true;
            }
        } catch (RuntimeException ignored) {
        }
        return false;
    }

    private static void populateEventTimeValues(
            ContentValues cv,
            boolean allDay,
            LocalDate startDate,
            LocalDate endDate,
            long beginMillis,
            long endMillis) {
        if (allDay) {
            long utcStart = startDate.toEpochDay() * 86_400_000L;
            long utcEnd = (Math.max(startDate.toEpochDay(), endDate.toEpochDay()) + 1L) * 86_400_000L;
            cv.put(CalendarContract.Events.ALL_DAY, 1);
            cv.put(CalendarContract.Events.DTSTART, utcStart);
            cv.put(CalendarContract.Events.DTEND, utcEnd);
            cv.put(CalendarContract.Events.EVENT_TIMEZONE, "UTC");
        } else {
            cv.put(CalendarContract.Events.ALL_DAY, 0);
            cv.put(CalendarContract.Events.DTSTART, beginMillis);
            cv.put(CalendarContract.Events.DTEND, Math.max(beginMillis + 900_000L, endMillis));
            cv.put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().getID());
        }
    }

    private static void triggerExpeditedCalendarSync(Context context, long calendarId) {
        if (calendarId > 0 && hasReadPermission(context)) {
            Uri calUri = ContentUris.withAppendedId(CalendarContract.Calendars.CONTENT_URI, calendarId);
            String[] proj = {CalendarContract.Calendars.ACCOUNT_NAME, CalendarContract.Calendars.ACCOUNT_TYPE};
            try (Cursor c = context.getContentResolver().query(calUri, proj, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    triggerExpeditedAccountSync(c.getString(0), c.getString(1));
                    return;
                }
            } catch (RuntimeException ignored) {
            }
        }
        triggerAllExpeditedSyncs(context);
    }

    private static void triggerAllExpeditedSyncs(Context context) {
        Set<String> seen = new HashSet<>();
        for (SystemCalendarItem item : loadSystemCalendars(context)) {
            if (!item.accountName.isEmpty() && !item.accountType.isEmpty()
                    && seen.add(item.accountType + "/" + item.accountName)) {
                triggerExpeditedAccountSync(item.accountName, item.accountType);
            }
        }
    }

    private static void triggerExpeditedAccountSync(String accountName, String accountType) {
        if (TextUtils.isEmpty(accountName) || TextUtils.isEmpty(accountType)) return;
        try {
            Bundle extras = new Bundle();
            extras.putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true);
            extras.putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true);
            ContentResolver.requestSync(
                    new Account(accountName, accountType), CalendarContract.AUTHORITY, extras);
        } catch (RuntimeException ignored) {
        }
    }

    private static String nonNull(String s) {
        return s == null ? "" : s;
    }
}
