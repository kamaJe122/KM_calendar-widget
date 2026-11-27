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

    /** Google Calendar style recurring edit scopes. */
    public static final int EDIT_SCOPE_SINGLE = 0;             // 只修改這一次活動
    public static final int EDIT_SCOPE_THIS_AND_FOLLOWING = 1; // 這個和後續活動
    public static final int EDIT_SCOPE_ALL = 2;                // 所有活動

    public static final class SystemEventMeta {
        public final long eventId;
        public final long calendarId;
        public final String accountName;
        public final String accountType;
        public final boolean writable;
        /** True when the row is a recurring series OR a detached exception of one. */
        public final boolean recurring;

        public String rrule = "";
        public String rdate = "";
        public long originalId = -1L;
        public String originalSyncId = "";
        public long originalInstanceTime = 0L;
        public boolean originalAllDay;
        public long dtStart;
        public boolean allDay;
        public String timezone = "";
        public int availability = -1;
        public int accessLevel = -1;
        public String calendarName = "";
        public int calendarColor = 0xFF4285F4;

        SystemEventMeta(long eventId, long calendarId, String accountName, String accountType,
                        boolean writable, boolean recurring) {
            this.eventId = eventId;
            this.calendarId = calendarId;
            this.accountName = accountName;
            this.accountType = accountType;
            this.writable = writable;
            this.recurring = recurring;
        }

        public boolean hasRecurrenceRule() {
            return !TextUtils.isEmpty(rrule);
        }

        public boolean isException() {
            return originalId > 0L || !TextUtils.isEmpty(originalSyncId);
        }

        /** Human readable recurrence summary such as 「每週」. */
        public String recurrenceLabel() {
            String r = rrule == null ? "" : rrule.toUpperCase(Locale.ROOT);
            String base;
            if (r.contains("FREQ=DAILY")) base = "每天";
            else if (r.contains("FREQ=WEEKLY")) base = "每週";
            else if (r.contains("FREQ=MONTHLY")) base = "每月";
            else if (r.contains("FREQ=YEARLY")) base = "每年";
            else base = "重複";
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("INTERVAL=(\\d+)").matcher(r);
            if (m.find() && !"1".equals(m.group(1))) {
                base = "每 " + m.group(1) + " " + base.substring(1);
            }
            return base;
        }
    }

    private static volatile boolean sRecurringInstancesHealed = false;

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
                CalendarContract.Events.RDATE,
                CalendarContract.Events.ORIGINAL_ID,
                CalendarContract.Events.ORIGINAL_SYNC_ID,
                CalendarContract.Events.ORIGINAL_INSTANCE_TIME,
                CalendarContract.Events.ORIGINAL_ALL_DAY,
                CalendarContract.Events.DTSTART,
                CalendarContract.Events.ALL_DAY,
                CalendarContract.Events.EVENT_TIMEZONE,
                CalendarContract.Events.AVAILABILITY,
                CalendarContract.Events.ACCESS_LEVEL,
                CalendarContract.Events.CALENDAR_DISPLAY_NAME,
                CalendarContract.Events.CALENDAR_COLOR
        };
        try (Cursor c = context.getContentResolver().query(uri, proj, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                long calId = c.getLong(0);
                String accName = nonNull(c.getString(1));
                String accType = nonNull(c.getString(2));
                int access = c.getInt(3);
                boolean writable = access >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR;
                String rrule = nonNull(c.getString(4));
                String rdate = nonNull(c.getString(5));
                long origId = -1L;
                String origIdStr = c.getString(6);
                if (!TextUtils.isEmpty(origIdStr)) {
                    try {
                        origId = Long.parseLong(origIdStr.trim());
                    } catch (NumberFormatException ignored) {
                    }
                }
                String origSyncId = nonNull(c.getString(7));
                if (origId <= 0L && !origSyncId.isEmpty() && calId > 0L) {
                    origId = findMasterIdBySyncId(context, calId, origSyncId);
                }
                boolean recurring = !rrule.isEmpty() || !rdate.isEmpty() || origId > 0L || !origSyncId.isEmpty();
                SystemEventMeta m = new SystemEventMeta(eventId, calId, accName, accType, writable, recurring);
                m.rrule = rrule;
                m.rdate = rdate;
                m.originalId = origId;
                m.originalSyncId = origSyncId;
                m.originalInstanceTime = c.isNull(8) ? 0L : c.getLong(8);
                m.originalAllDay = !c.isNull(9) && c.getInt(9) != 0;
                m.dtStart = c.getLong(10);
                m.allDay = c.getInt(11) != 0;
                m.timezone = nonNull(c.getString(12));
                m.availability = c.isNull(13) ? -1 : c.getInt(13);
                m.accessLevel = c.isNull(14) ? -1 : c.getInt(14);
                m.calendarName = nonNull(c.getString(15));
                m.calendarColor = AppTheme.normalizePillColor(c.getInt(16));
                return m;
            }
        } catch (RuntimeException ignored) {
        }
        return null;
    }

    private static long findMasterIdBySyncId(Context context, long calId, String syncId) {
        try (Cursor c = context.getContentResolver().query(
                CalendarContract.Events.CONTENT_URI,
                new String[]{CalendarContract.Events._ID},
                CalendarContract.Events.CALENDAR_ID + " = ? AND " + CalendarContract.Events._SYNC_ID + " = ?",
                new String[]{String.valueOf(calId), syncId}, null)) {
            if (c != null && c.moveToFirst()) {
                return c.getLong(0);
            }
        } catch (RuntimeException ignored) {
        }
        return -1L;
    }

    /**
     * Self-heals active recurring events in CalendarContract.Events whose Instances rows were
     * dropped when CalendarProvider2 received a DTSTART update without RRULE in modValues
     * (which leaves at most a single row at DTSTART and zero expanded future occurrences).
     */
    public static void healUnexpandedRecurringEvents(Context context) {
        if (sRecurringInstancesHealed || !hasReadPermission(context) || !hasWritePermission(context)) {
            return;
        }
        sRecurringInstancesHealed = true;
        try {
            long nowMs = System.currentTimeMillis();
            long winStart = nowMs - 7L * 86_400_000L;
            long winEnd = nowMs + 400L * 86_400_000L;

            String sel = CalendarContract.Events.VISIBLE + " = 1 AND "
                    + CalendarContract.Events.DELETED + " = 0 AND "
                    + CalendarContract.Events.RRULE + " IS NOT NULL AND "
                    + CalendarContract.Events.RRULE + " != '' AND "
                    + CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL + " >= "
                    + CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR;
            String[] proj = new String[]{
                    CalendarContract.Events._ID,
                    CalendarContract.Events.DTSTART,
                    CalendarContract.Events.LAST_DATE,
                    CalendarContract.Events.RRULE,
                    CalendarContract.Events.RDATE,
                    CalendarContract.Events.STATUS
            };

            Map<Long, Long> candidateDtStart = new HashMap<>();
            Map<Long, String[]> candidateRules = new HashMap<>();
            ContentResolver cr = context.getContentResolver();
            try (Cursor ec = cr.query(CalendarContract.Events.CONTENT_URI, proj, sel, null, null)) {
                if (ec != null) {
                    while (ec.moveToNext()) {
                        if (!ec.isNull(5) && ec.getInt(5) == CalendarContract.Events.STATUS_CANCELED) {
                            continue;
                        }
                        long id = ec.getLong(0);
                        long dtStart = ec.getLong(1);
                        boolean hasLast = !ec.isNull(2);
                        long lastDate = hasLast ? ec.getLong(2) : -1L;
                        String rrule = nonNull(ec.getString(3));
                        String rdate = nonNull(ec.getString(4));
                        if (dtStart > nowMs + 30L * 86_400_000L) continue;
                        if (hasLast && lastDate > 0L) {
                            if (lastDate < nowMs - 7L * 86_400_000L) continue;
                            if (lastDate <= dtStart + 86_400_000L) continue;
                        }
                        String upper = rrule.toUpperCase(Locale.ROOT);
                        if (upper.contains("FREQ=YEARLY") && upper.contains("INTERVAL=")
                                && !upper.contains("INTERVAL=1;") && !upper.endsWith("INTERVAL=1")) {
                            continue;
                        }
                        candidateDtStart.put(id, dtStart);
                        candidateRules.put(id, new String[]{rrule, rdate});
                    }
                }
            }
            if (candidateDtStart.isEmpty()) return;

            Set<Long> healthySeriesIds = new HashSet<>();
            Uri.Builder ib = CalendarContract.Instances.CONTENT_URI.buildUpon();
            ContentUris.appendId(ib, winStart);
            ContentUris.appendId(ib, winEnd);
            try (Cursor ic = cr.query(ib.build(),
                    new String[]{
                            CalendarContract.Instances.EVENT_ID,
                            CalendarContract.Instances.BEGIN,
                            CalendarContract.Events.ORIGINAL_ID
                    },
                    null, null, null)) {
                if (ic != null) {
                    while (ic.moveToNext()) {
                        long evId = ic.getLong(0);
                        long begin = ic.getLong(1);
                        Long masterDt = candidateDtStart.get(evId);
                        if (masterDt != null && begin > masterDt + 3_600_000L) {
                            healthySeriesIds.add(evId);
                        }
                        if (!ic.isNull(2)) {
                            try {
                                long origId = Long.parseLong(ic.getString(2));
                                Long origDt = candidateDtStart.get(origId);
                                if (origDt != null && begin > origDt + 3_600_000L) {
                                    healthySeriesIds.add(origId);
                                }
                            } catch (NumberFormatException ignored) {
                            }
                        }
                    }
                }
            }

            boolean anyRepaired = false;
            for (Map.Entry<Long, Long> e : candidateDtStart.entrySet()) {
                long id = e.getKey();
                if (!healthySeriesIds.contains(id)) {
                    String[] rules = candidateRules.get(id);
                    ContentValues fix = new ContentValues();
                    fix.put(CalendarContract.Events.DTSTART, e.getValue());
                    fix.put(CalendarContract.Events.RRULE, rules[0]);
                    if (!rules[1].isEmpty()) {
                        fix.put(CalendarContract.Events.RDATE, rules[1]);
                    }
                    Uri u = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id);
                    if (cr.update(u, fix, null, null) > 0) {
                        anyRepaired = true;
                    }
                }
            }
            if (anyRepaired) {
                CalendarWidgetProvider.updateAll(context);
            }
        } catch (RuntimeException ignored) {
        }
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
            healUnexpandedRecurringEvents(context);
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
     * Updates a system / Google Calendar event with Google-Calendar-style recurrence scopes and
     * immediately requests an expedited sync back to the source account.
     *
     * @param scope              {@link #EDIT_SCOPE_SINGLE}, {@link #EDIT_SCOPE_THIS_AND_FOLLOWING}
     *                           or {@link #EDIT_SCOPE_ALL} (ignored for non-recurring events)
     * @param instanceBeginMillis BEGIN of the tapped instance (from CalendarContract.Instances)
     * @param instanceAllDay      ALL_DAY of the tapped instance
     * @return the event row id that now represents the edited occurrence (use it for reminders),
     * or -1 on failure.
     */
    public static long updateSystemEvent(
            Context context,
            SystemEventMeta meta,
            int scope,
            long targetCalendarId,
            long instanceBeginMillis,
            boolean instanceAllDay,
            String title,
            String location,
            String description,
            boolean allDay,
            LocalDate startDate,
            LocalDate endDate,
            long beginMillis,
            long endMillis) {
        if (meta == null || meta.eventId < 0 || !hasWritePermission(context)) return -1L;
        ContentResolver cr = context.getContentResolver();
        long syncCal = meta.calendarId > 0 ? meta.calendarId : targetCalendarId;
        try {
            boolean isSeries = meta.recurring && !meta.isException();
            SystemEventMeta series;
            long origInstance;
            boolean origAllDay;

            if (isSeries) {
                series = meta;
                origInstance = instanceBeginMillis;
                origAllDay = instanceAllDay;
            } else if (meta.isException() && scope != EDIT_SCOPE_SINGLE) {
                series = inspectSystemEvent(context, meta.originalId);
                if (series == null) {
                    return plainUpdate(context, meta, -1L, title, location, description,
                            allDay, startDate, endDate, beginMillis, endMillis) ? meta.eventId : -1L;
                }
                origInstance = meta.originalInstanceTime > 0 ? meta.originalInstanceTime : instanceBeginMillis;
                origAllDay = meta.originalAllDay;
            } else {
                // Plain single event, or an already-detached exception edited "only this one"
                long calTarget = meta.isException() ? -1L : targetCalendarId;
                boolean ok = plainUpdate(context, meta, calTarget, title, location, description,
                        allDay, startDate, endDate, beginMillis, endMillis);
                if (ok) finishMutation(context, calTarget > 0 ? calTarget : syncCal);
                return ok ? meta.eventId : -1L;
            }

            long resultId;
            if (scope == EDIT_SCOPE_SINGLE) {
                ContentValues ex = new ContentValues();
                ex.put(CalendarContract.Events.ORIGINAL_INSTANCE_TIME, origInstance);
                ex.put(CalendarContract.Events.ORIGINAL_ALL_DAY, origAllDay ? 1 : 0);
                ex.put(CalendarContract.Events.TITLE, title);
                ex.put(CalendarContract.Events.EVENT_LOCATION, location == null ? "" : location);
                ex.put(CalendarContract.Events.DESCRIPTION, description == null ? "" : description);
                populateEventTimeValues(ex, allDay, startDate, endDate, beginMillis, endMillis);
                Uri exUri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_EXCEPTION_URI, series.eventId);
                Uri res = cr.insert(exUri, ex);
                resultId = parseId(res);
            } else if (scope == EDIT_SCOPE_THIS_AND_FOLLOWING
                    && origInstance > series.dtStart + 999L) {
                resultId = splitSeriesAndInsertFollowing(context, series, origInstance, origAllDay,
                        title, location, description, allDay, startDate, endDate, beginMillis, endMillis);
                if (resultId >= 0 && meta.isException()) {
                    // The detached exception is superseded by the new "following" series
                    cr.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, meta.eventId), null, null);
                }
            } else {
                // EDIT_SCOPE_ALL (or "this & following" on the very first occurrence == whole series)
                boolean ok = updateWholeSeries(context, series, origInstance, origAllDay,
                        title, location, description, allDay, startDate, endDate, beginMillis, endMillis);
                if (ok && meta.isException()) {
                    plainUpdate(context, meta, -1L, title, location, description,
                            allDay, startDate, endDate, beginMillis, endMillis);
                }
                resultId = ok ? series.eventId : -1L;
            }

            if (resultId >= 0) finishMutation(context, syncCal);
            return resultId;
        } catch (RuntimeException ignored) {
        }
        return -1L;
    }

    /** Legacy convenience overload kept for callers that only know single vs. series. */
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
        SystemEventMeta meta = inspectSystemEvent(context, eventId);
        if (meta == null) return false;
        int scope = onlyThisInstance ? EDIT_SCOPE_SINGLE : EDIT_SCOPE_ALL;
        return updateSystemEvent(context, meta, scope, targetCalendarId, originalBeginMillis, originalAllDay,
                title, location, description, allDay, startDate, endDate, beginMillis, endMillis) >= 0;
    }

    private static boolean plainUpdate(
            Context context, SystemEventMeta meta, long targetCalendarId,
            String title, String location, String description,
            boolean allDay, LocalDate startDate, LocalDate endDate, long beginMillis, long endMillis) {
        ContentValues cv = new ContentValues();
        if (targetCalendarId > 0 && !meta.isException()) {
            cv.put(CalendarContract.Events.CALENDAR_ID, targetCalendarId);
        }
        cv.put(CalendarContract.Events.TITLE, title);
        cv.put(CalendarContract.Events.EVENT_LOCATION, location == null ? "" : location);
        cv.put(CalendarContract.Events.DESCRIPTION, description == null ? "" : description);
        populateEventTimeValues(cv, allDay, startDate, endDate, beginMillis, endMillis);
        cv.putNull(CalendarContract.Events.DURATION);
        if (meta.isException()) {
            // CalendarInstancesHelper.updateInstancesLocked inspects modValues directly to detect
            // recurrence exceptions; preserve linkage keys so Instances re-expands properly.
            if (meta.originalId > 0L) {
                cv.put(CalendarContract.Events.ORIGINAL_ID, meta.originalId);
            }
            if (!TextUtils.isEmpty(meta.originalSyncId)) {
                cv.put(CalendarContract.Events.ORIGINAL_SYNC_ID, meta.originalSyncId);
            }
            if (meta.originalInstanceTime > 0L) {
                cv.put(CalendarContract.Events.ORIGINAL_INSTANCE_TIME, meta.originalInstanceTime);
            }
        }
        Uri uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, meta.eventId);
        return context.getContentResolver().update(uri, cv, null, null) > 0;
    }

    /** "所有活動": shift the series' DTSTART by the same day offset / new time-of-day as the tapped instance. */
    private static boolean updateWholeSeries(
            Context context, SystemEventMeta series, long origInstance, boolean origAllDay,
            String title, String location, String description,
            boolean allDay, LocalDate startDate, LocalDate endDate, long beginMillis, long endMillis) {
        ZoneId zone = ZoneId.systemDefault();
        LocalDate origInstanceDate = toLocalDate(origInstance, origAllDay, zone);
        long dayShift = startDate.toEpochDay() - origInstanceDate.toEpochDay();
        LocalDate masterDate = toLocalDate(series.dtStart, series.allDay, zone).plusDays(dayShift);

        ContentValues cv = new ContentValues();
        cv.put(CalendarContract.Events.TITLE, title);
        cv.put(CalendarContract.Events.EVENT_LOCATION, location == null ? "" : location);
        cv.put(CalendarContract.Events.DESCRIPTION, description == null ? "" : description);
        putSeriesTiming(cv, allDay, masterDate, startDate, endDate, beginMillis, endMillis, zone);

        // CRITICAL: Android's CalendarInstancesHelper.updateInstancesLocked(modValues, ...) checks
        // modValues (not the DB row!) for RRULE/RDATE right after deleting existing Instances when
        // DTSTART is present. Always include RRULE/RDATE so Instances are immediately re-expanded!
        String rruleToWrite = dayShift != 0
                ? adjustSingleByDay(series.rrule, masterDate)
                : series.rrule;
        if (!TextUtils.isEmpty(rruleToWrite)) {
            cv.put(CalendarContract.Events.RRULE, rruleToWrite);
        }
        if (!TextUtils.isEmpty(series.rdate)) {
            cv.put(CalendarContract.Events.RDATE, series.rdate);
        }

        Uri uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, series.eventId);
        return context.getContentResolver().update(uri, cv, null, null) > 0;
    }

    /**
     * "這個和後續活動": RFC 5545 split — end the original series right before the tapped
     * occurrence (UNTIL) and insert a new series starting at the edited occurrence.
     */
    private static long splitSeriesAndInsertFollowing(
            Context context, SystemEventMeta series, long origInstance, boolean origAllDay,
            String title, String location, String description,
            boolean allDay, LocalDate startDate, LocalDate endDate, long beginMillis, long endMillis) {
        ContentResolver cr = context.getContentResolver();
        ZoneId zone = ZoneId.systemDefault();
        Map<String, String> parts = parseRRule(series.rrule);

        // 1. New "following" series
        Map<String, String> newParts = new java.util.LinkedHashMap<>(parts);
        if (parts.containsKey("COUNT")) {
            int total = 1;
            try {
                total = Integer.parseInt(parts.get("COUNT"));
            } catch (NumberFormatException ignored) {
            }
            int before = countInstancesBefore(context, series.eventId, series.dtStart, origInstance);
            newParts.put("COUNT", String.valueOf(Math.max(1, total - before)));
        }
        String newRule = joinRRule(newParts);
        newRule = adjustSingleByDay(newRule, startDate);

        ContentValues nv = new ContentValues();
        nv.put(CalendarContract.Events.CALENDAR_ID, series.calendarId);
        nv.put(CalendarContract.Events.TITLE, title);
        nv.put(CalendarContract.Events.EVENT_LOCATION, location == null ? "" : location);
        nv.put(CalendarContract.Events.DESCRIPTION, description == null ? "" : description);
        nv.put(CalendarContract.Events.RRULE, newRule);
        putSeriesTiming(nv, allDay, startDate, startDate, endDate, beginMillis, endMillis, zone);
        if (series.availability >= 0) nv.put(CalendarContract.Events.AVAILABILITY, series.availability);
        if (series.accessLevel >= 0) nv.put(CalendarContract.Events.ACCESS_LEVEL, series.accessLevel);
        long newId = parseId(cr.insert(CalendarContract.Events.CONTENT_URI, nv));
        if (newId < 0) return -1L;

        // 2. Truncate the original series so it ends right before the tapped occurrence.
        // Include DTSTART so CalendarInstancesHelper.updateInstancesLocked re-expands Instances!
        Map<String, String> oldParts = new java.util.LinkedHashMap<>(parts);
        oldParts.remove("COUNT");
        oldParts.put("UNTIL", formatUntil(origInstance, origAllDay || series.allDay, zone));
        ContentValues ov = new ContentValues();
        ov.put(CalendarContract.Events.DTSTART, series.dtStart);
        ov.put(CalendarContract.Events.RRULE, joinRRule(oldParts));
        if (!TextUtils.isEmpty(series.rdate)) {
            ov.put(CalendarContract.Events.RDATE, series.rdate);
        }
        Uri oldUri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, series.eventId);
        if (cr.update(oldUri, ov, null, null) <= 0) {
            cr.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, newId), null, null);
            return -1L;
        }
        return newId;
    }

    private static void putSeriesTiming(
            ContentValues cv, boolean allDay, LocalDate seriesStartDate,
            LocalDate startDate, LocalDate endDate, long beginMillis, long endMillis, ZoneId zone) {
        if (allDay) {
            long days = Math.max(1L, (endDate.toEpochDay() - startDate.toEpochDay()) + 1L);
            cv.put(CalendarContract.Events.ALL_DAY, 1);
            cv.put(CalendarContract.Events.DTSTART, seriesStartDate.toEpochDay() * 86_400_000L);
            cv.putNull(CalendarContract.Events.DTEND);
            cv.put(CalendarContract.Events.DURATION, "P" + days + "D");
            cv.put(CalendarContract.Events.EVENT_TIMEZONE, "UTC");
        } else {
            java.time.LocalTime startTime = Instant.ofEpochMilli(beginMillis).atZone(zone).toLocalTime();
            long dtStart = seriesStartDate.atTime(startTime).atZone(zone).toInstant().toEpochMilli();
            long durSec = Math.max(900L, (Math.max(beginMillis + 900_000L, endMillis) - beginMillis) / 1000L);
            cv.put(CalendarContract.Events.ALL_DAY, 0);
            cv.put(CalendarContract.Events.DTSTART, dtStart);
            cv.putNull(CalendarContract.Events.DTEND);
            cv.put(CalendarContract.Events.DURATION, "P" + durSec + "S");
            cv.put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().getID());
        }
    }

    private static LocalDate toLocalDate(long millis, boolean allDayUtc, ZoneId zone) {
        return Instant.ofEpochMilli(millis).atZone(allDayUtc ? java.time.ZoneOffset.UTC : zone).toLocalDate();
    }

    private static String formatUntil(long origInstance, boolean allDay, ZoneId zone) {
        if (allDay) {
            LocalDate d = toLocalDate(origInstance, true, zone).minusDays(1);
            return d.format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
        }
        return java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'", Locale.US)
                .withZone(java.time.ZoneOffset.UTC)
                .format(Instant.ofEpochMilli(origInstance - 1000L));
    }

    private static Map<String, String> parseRRule(String rrule) {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        if (rrule == null) return out;
        String r = rrule.trim();
        if (r.toUpperCase(Locale.ROOT).startsWith("RRULE:")) r = r.substring(6);
        for (String p : r.split(";")) {
            int eq = p.indexOf('=');
            if (eq > 0) {
                out.put(p.substring(0, eq).trim().toUpperCase(Locale.ROOT), p.substring(eq + 1).trim());
            }
        }
        return out;
    }

    private static String joinRRule(Map<String, String> parts) {
        StringBuilder sb = new StringBuilder();
        // FREQ must come first for maximum compatibility
        if (parts.containsKey("FREQ")) sb.append("FREQ=").append(parts.get("FREQ"));
        for (Map.Entry<String, String> e : parts.entrySet()) {
            if ("FREQ".equals(e.getKey())) continue;
            if (sb.length() > 0) sb.append(';');
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    /** For simple weekly rules (single BYDAY), keep BYDAY aligned when the start weekday moves. */
    private static String adjustSingleByDay(String rrule, LocalDate newStart) {
        Map<String, String> parts = parseRRule(rrule);
        String byDay = parts.get("BYDAY");
        if (!"WEEKLY".equalsIgnoreCase(parts.get("FREQ")) || byDay == null
                || byDay.contains(",") || byDay.length() != 2) {
            return rrule;
        }
        String[] codes = {"MO", "TU", "WE", "TH", "FR", "SA", "SU"};
        parts.put("BYDAY", codes[newStart.getDayOfWeek().getValue() - 1]);
        return joinRRule(parts);
    }

    private static int countInstancesBefore(Context context, long eventId, long fromMillis, long beforeMillis) {
        if (beforeMillis <= fromMillis) return 0;
        Uri.Builder b = CalendarContract.Instances.CONTENT_URI.buildUpon();
        ContentUris.appendId(b, fromMillis - 86_400_000L);
        ContentUris.appendId(b, beforeMillis - 1L);
        try (Cursor c = context.getContentResolver().query(b.build(),
                new String[]{CalendarContract.Instances.BEGIN},
                CalendarContract.Instances.EVENT_ID + " = ? AND " + CalendarContract.Instances.BEGIN + " < ?",
                new String[]{String.valueOf(eventId), String.valueOf(beforeMillis)}, null)) {
            return c == null ? 0 : c.getCount();
        } catch (RuntimeException ignored) {
        }
        return 0;
    }

    private static long parseId(Uri uri) {
        if (uri == null || uri.getLastPathSegment() == null) return -1L;
        try {
            return Long.parseLong(uri.getLastPathSegment());
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    private static void finishMutation(Context context, long calendarId) {
        triggerExpeditedCalendarSync(context, calendarId);
        CalendarWidgetProvider.updateAll(context);
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

    /**
     * Deletes with Google Calendar recurrence scopes:
     * SINGLE cancels only the tapped occurrence, THIS_AND_FOLLOWING ends the series before it,
     * ALL deletes the whole series.
     */
    public static boolean deleteSystemEvent(
            Context context, SystemEventMeta meta, int scope, long instanceBeginMillis, boolean instanceAllDay) {
        if (meta == null || !hasWritePermission(context)) return false;
        if (!meta.recurring) return deleteSystemEvent(context, meta.eventId);
        ContentResolver cr = context.getContentResolver();
        try {
            SystemEventMeta series = meta.isException() ? inspectSystemEvent(context, meta.originalId) : meta;
            long origInstance = meta.isException() && meta.originalInstanceTime > 0
                    ? meta.originalInstanceTime : instanceBeginMillis;
            boolean origAllDay = meta.isException() ? meta.originalAllDay : instanceAllDay;
            boolean ok;
            if (scope == EDIT_SCOPE_SINGLE) {
                if (meta.isException()) {
                    ContentValues cv = new ContentValues();
                    cv.put(CalendarContract.Events.DTSTART, meta.dtStart);
                    if (meta.originalId > 0L) {
                        cv.put(CalendarContract.Events.ORIGINAL_ID, meta.originalId);
                    }
                    if (!TextUtils.isEmpty(meta.originalSyncId)) {
                        cv.put(CalendarContract.Events.ORIGINAL_SYNC_ID, meta.originalSyncId);
                    }
                    if (origInstance > 0L) {
                        cv.put(CalendarContract.Events.ORIGINAL_INSTANCE_TIME, origInstance);
                    }
                    cv.put(CalendarContract.Events.STATUS, CalendarContract.Events.STATUS_CANCELED);
                    ok = cr.update(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, meta.eventId),
                            cv, null, null) > 0;
                } else {
                    ContentValues ex = new ContentValues();
                    ex.put(CalendarContract.Events.ORIGINAL_INSTANCE_TIME, origInstance);
                    ex.put(CalendarContract.Events.ORIGINAL_ALL_DAY, origAllDay ? 1 : 0);
                    ex.put(CalendarContract.Events.STATUS, CalendarContract.Events.STATUS_CANCELED);
                    ok = cr.insert(ContentUris.withAppendedId(
                            CalendarContract.Events.CONTENT_EXCEPTION_URI, meta.eventId), ex) != null;
                }
            } else if (series == null) {
                return deleteSystemEvent(context, meta.eventId);
            } else if (scope == EDIT_SCOPE_THIS_AND_FOLLOWING && origInstance > series.dtStart + 999L
                    && series.hasRecurrenceRule()) {
                Map<String, String> parts = parseRRule(series.rrule);
                parts.remove("COUNT");
                parts.put("UNTIL", formatUntil(origInstance, origAllDay || series.allDay, ZoneId.systemDefault()));
                ContentValues ov = new ContentValues();
                ov.put(CalendarContract.Events.DTSTART, series.dtStart);
                ov.put(CalendarContract.Events.RRULE, joinRRule(parts));
                if (!TextUtils.isEmpty(series.rdate)) {
                    ov.put(CalendarContract.Events.RDATE, series.rdate);
                }
                ok = cr.update(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, series.eventId),
                        ov, null, null) > 0;
                if (ok && meta.isException()) {
                    cr.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, meta.eventId), null, null);
                }
            } else {
                ok = cr.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, series.eventId),
                        null, null) > 0;
            }
            if (ok) {
                triggerExpeditedAccountSync(meta.accountName, meta.accountType);
                CalendarWidgetProvider.updateAll(context);
            }
            return ok;
        } catch (RuntimeException ignored) {
        }
        return false;
    }

    // ===================== Reminders (CalendarContract.Reminders) =====================

    /** Returns reminder offsets (minutes before start), sorted ascending. */
    public static List<Integer> loadEventReminders(Context context, long eventId) {
        List<Integer> out = new ArrayList<>();
        if (eventId < 0 || !hasReadPermission(context)) return out;
        try (Cursor c = context.getContentResolver().query(
                CalendarContract.Reminders.CONTENT_URI,
                new String[]{CalendarContract.Reminders.MINUTES, CalendarContract.Reminders.METHOD},
                CalendarContract.Reminders.EVENT_ID + " = ?",
                new String[]{String.valueOf(eventId)}, null)) {
            if (c != null) {
                while (c.moveToNext()) {
                    int min = c.getInt(0);
                    if (min < 0) min = 10; // DEFAULT (-1) -> calendar default
                    if (!out.contains(min)) out.add(min);
                }
            }
        } catch (RuntimeException ignored) {
        }
        Collections.sort(out);
        return out;
    }

    /** Batch-loads reminder offsets (minutes before start) keyed by eventId in a single query. */
    public static Map<Long, List<Integer>> loadRemindersForEvents(Context context, Set<Long> eventIds) {
        Map<Long, List<Integer>> map = new HashMap<>();
        if (eventIds == null || eventIds.isEmpty() || !hasReadPermission(context)) return map;
        StringBuilder inClause = new StringBuilder();
        for (Long id : eventIds) {
            if (id == null || id < 0) continue;
            if (inClause.length() > 0) inClause.append(',');
            inClause.append(id);
        }
        if (inClause.length() == 0) return map;
        String sel = CalendarContract.Reminders.EVENT_ID + " IN (" + inClause + ")";
        try (Cursor c = context.getContentResolver().query(
                CalendarContract.Reminders.CONTENT_URI,
                new String[]{
                        CalendarContract.Reminders.EVENT_ID,
                        CalendarContract.Reminders.MINUTES,
                        CalendarContract.Reminders.METHOD
                },
                sel, null, null)) {
            if (c != null) {
                while (c.moveToNext()) {
                    int method = c.isNull(2) ? CalendarContract.Reminders.METHOD_DEFAULT : c.getInt(2);
                    if (method != CalendarContract.Reminders.METHOD_DEFAULT
                            && method != CalendarContract.Reminders.METHOD_ALERT) {
                        continue; // skip email/SMS-only reminders
                    }
                    long eid = c.getLong(0);
                    int min = c.getInt(1);
                    if (min == -1) min = 10; // MINUTES_DEFAULT (-1) -> 10 min
                    List<Integer> list = map.computeIfAbsent(eid, k -> new ArrayList<>());
                    if (!list.contains(min)) list.add(min);
                }
            }
        } catch (RuntimeException ignored) {
        }
        return map;
    }

    /** Replaces all reminders of the event with popup alerts at the given offsets. */
    public static boolean saveEventReminders(Context context, long eventId, List<Integer> minutesBefore) {
        if (eventId < 0 || !hasWritePermission(context)) return false;
        ContentResolver cr = context.getContentResolver();
        try {
            cr.delete(CalendarContract.Reminders.CONTENT_URI,
                    CalendarContract.Reminders.EVENT_ID + " = ?", new String[]{String.valueOf(eventId)});
            if (minutesBefore != null) {
                for (Integer m : minutesBefore) {
                    if (m == null || m < 0) continue;
                    ContentValues cv = new ContentValues();
                    cv.put(CalendarContract.Reminders.EVENT_ID, eventId);
                    cv.put(CalendarContract.Reminders.MINUTES, m);
                    cv.put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT);
                    cr.insert(CalendarContract.Reminders.CONTENT_URI, cv);
                }
            }
            return true;
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
