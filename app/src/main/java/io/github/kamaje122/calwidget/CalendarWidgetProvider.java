package io.github.kamaje122.calwidget;

import android.Manifest;
import android.accounts.Account;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.CalendarContract;
import android.text.TextUtils;
import android.view.View;
import android.widget.RemoteViews;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.IsoFields;
import java.time.temporal.JulianFields;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

public class CalendarWidgetProvider extends AppWidgetProvider {

    static final String ACTION_SELECT = "io.github.kamaje122.calwidget.SELECT";
    static final String ACTION_TODAY = "io.github.kamaje122.calwidget.TODAY";
    static final String ACTION_TICK = "io.github.kamaje122.calwidget.TICK";

    private static final String PREFS = "widget";
    private static final String KEY_DAYS = "daysToShow";
    private static final String KEY_SHOW_ALL_DAY = "showAllDay";
    private static final String KEY_HIDDEN_CALENDARS = "hiddenCalendars";
    private static final String KEY_LAST_SYNC = "lastSyncRequest";
    private static final String KEY_SELECTED = "selectedEpochDay";
    private static final String KEY_SELECTED_ON = "selectedOnEpochDay";

    static final int MAX_DAYS = 14;
    static final long SYNC_ON_TOUCH_MS = 20_000L;
    static final long SYNC_BACKGROUND_MS = 1_500_000L;

    private static final String[] WEEKDAYS = new String[]{
            "週一", "週二", "週三", "週四", "週五", "週六", "週日"
    };

    private static final int[] WEEK_IDS = new int[]{
            R.id.w0, R.id.w1, R.id.w2, R.id.w3, R.id.w4, R.id.w5
    };

    private static final int[] DAY_IDS = new int[]{
            R.id.d0, R.id.d1, R.id.d2, R.id.d3, R.id.d4, R.id.d5, R.id.d6,
            R.id.d7, R.id.d8, R.id.d9, R.id.d10, R.id.d11, R.id.d12, R.id.d13,
            R.id.d14, R.id.d15, R.id.d16, R.id.d17, R.id.d18, R.id.d19, R.id.d20,
            R.id.d21, R.id.d22, R.id.d23, R.id.d24, R.id.d25, R.id.d26, R.id.d27,
            R.id.d28, R.id.d29, R.id.d30, R.id.d31, R.id.d32, R.id.d33, R.id.d34,
            R.id.d35, R.id.d36, R.id.d37, R.id.d38, R.id.d39, R.id.d40, R.id.d41
    };

    public static final class Event {
        long eventId = -1L;
        String notionUrl;
        String title;
        String subtitle;
        long begin;
        long end;
        boolean allDay;
        int color;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (ACTION_SELECT.equals(action) && intent.getData() != null) {
            try {
                long epochDay = Long.parseLong(intent.getData().getLastPathSegment());
                prefs(context).edit()
                        .putLong(KEY_SELECTED, epochDay)
                        .putLong(KEY_SELECTED_ON, LocalDate.now().toEpochDay())
                        .apply();
            } catch (NumberFormatException ignored) {
            }
            updateAll(context);
            syncWithAsyncGuard(this, context, SYNC_ON_TOUCH_MS);
            return;
        }

        if (ACTION_TODAY.equals(action)) {
            prefs(context).edit()
                    .remove(KEY_SELECTED)
                    .remove(KEY_SELECTED_ON)
                    .apply();
            updateAll(context);
            syncWithAsyncGuard(this, context, SYNC_ON_TOUCH_MS);
            return;
        }

        if (AppWidgetManager.ACTION_APPWIDGET_UPDATE.equals(action)
                || AppWidgetManager.ACTION_APPWIDGET_OPTIONS_CHANGED.equals(action)) {
            super.onReceive(context, intent);
            return;
        }

        if (action != null
                && !AppWidgetManager.ACTION_APPWIDGET_DELETED.equals(action)
                && !AppWidgetManager.ACTION_APPWIDGET_DISABLED.equals(action)
                && !AppWidgetManager.ACTION_APPWIDGET_ENABLED.equals(action)) {
            if (ACTION_TICK.equals(action)) {
                updateAll(context);
                syncWithAsyncGuard(this, context, SYNC_BACKGROUND_MS);
                return;
            }
            updateAll(context);
            return;
        }

        super.onReceive(context, intent);
    }

    private static void syncWithAsyncGuard(BroadcastReceiver receiver, Context context, long minIntervalMs) {
        requestSystemSync(context, minIntervalMs);
        if (!NotionManager.hasToken(context)) return;

        long now = System.currentTimeMillis();
        long notionInterval = Math.max(minIntervalMs, 30_000L);
        if (now - NotionManager.getLastSyncMillis(context) < notionInterval) return;

        final BroadcastReceiver.PendingResult pendingResult = receiver.goAsync();
        final AtomicBoolean finished = new AtomicBoolean(false);
        final Runnable finishOnce = () -> {
            if (finished.compareAndSet(false, true)) {
                try {
                    pendingResult.finish();
                } catch (Exception ignored) {
                }
            }
        };
        new Handler(Looper.getMainLooper()).postDelayed(finishOnce, 7_000L);
        NotionManager.refreshEventsOnlyAsync(context, notionInterval, new NotionManager.SyncCallback() {
            @Override
            public void onSuccess(String workspaceName, int sourceCount, int eventCount) {
                finishOnce.run();
            }

            @Override
            public void onError(String errorMessage) {
                finishOnce.run();
            }
        });
    }

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        requestSync(context, SYNC_BACKGROUND_MS);
        updateAll(context);
    }

    @Override
    public void onDisabled(Context context) {
        AlarmManager am = context.getSystemService(AlarmManager.class);
        if (am != null) {
            am.cancel(tickIntent(context));
        }
    }

    static void updateAll(Context context) {
        AppWidgetManager mgr = AppWidgetManager.getInstance(context);
        ComponentName cn = new ComponentName(context, CalendarWidgetProvider.class);
        if (mgr.getAppWidgetIds(cn).length == 0) {
            return;
        }
        mgr.updateAppWidget(cn, buildViews(context));
        scheduleNextTick(context);
    }

    static void requestSync(Context context, long minIntervalMs) {
        requestSystemSync(context, minIntervalMs);
        if (NotionManager.hasToken(context)) {
            NotionManager.refreshEventsOnlyAsync(context, Math.max(minIntervalMs, 30_000L), null);
        }
    }

    private static void requestSystemSync(Context context, long minIntervalMs) {
        if (context.checkSelfPermission(Manifest.permission.READ_CALENDAR)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        long now = System.currentTimeMillis();
        long last = prefs(context).getLong(KEY_LAST_SYNC, 0L);
        if (now >= last && (now - last) < minIntervalMs) {
            return;
        }
        prefs(context).edit().putLong(KEY_LAST_SYNC, now).apply();

        Set<String> seen = new HashSet<>();
        String[] proj = new String[]{
                CalendarContract.Calendars.ACCOUNT_NAME,
                CalendarContract.Calendars.ACCOUNT_TYPE
        };
        try (Cursor c = context.getContentResolver().query(
                CalendarContract.Calendars.CONTENT_URI,
                proj,
                CalendarContract.Calendars.SYNC_EVENTS + " = 1",
                null,
                null)) {
            if (c != null) {
                while (c.moveToNext()) {
                    String name = c.getString(0);
                    String type = c.getString(1);
                    if (TextUtils.isEmpty(name) || TextUtils.isEmpty(type)) continue;
                    if (!seen.add(type + "/" + name)) continue;
                    Bundle extras = new Bundle();
                    extras.putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true);
                    extras.putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true);
                    ContentResolver.requestSync(new Account(name, type), CalendarContract.AUTHORITY, extras);
                }
            }
        } catch (RuntimeException ignored) {
        }
    }

    static int daysToShow(Context context) {
        int val = prefs(context).getInt(KEY_DAYS, 1);
        return Math.max(1, Math.min(MAX_DAYS, val));
    }

    static void setDaysToShow(Context context, int days) {
        prefs(context).edit()
                .putInt(KEY_DAYS, Math.max(1, Math.min(MAX_DAYS, days)))
                .apply();
    }

    static boolean showAllDay(Context context) {
        return prefs(context).getBoolean(KEY_SHOW_ALL_DAY, true);
    }

    static void setShowAllDay(Context context, boolean show) {
        prefs(context).edit().putBoolean(KEY_SHOW_ALL_DAY, show).apply();
    }

    static Set<String> hiddenCalendars(Context context) {
        return new HashSet<>(prefs(context).getStringSet(KEY_HIDDEN_CALENDARS, Collections.emptySet()));
    }

    static void setCalendarHidden(Context context, long calendarId, boolean hidden) {
        setCalendarKeyHidden(context, String.valueOf(calendarId), hidden);
    }

    static void setCalendarKeyHidden(Context context, String key, boolean hidden) {
        Set<String> set = hiddenCalendars(context);
        if (hidden) {
            set.add(key);
        } else {
            set.remove(key);
        }
        prefs(context).edit().putStringSet(KEY_HIDDEN_CALENDARS, set).apply();
    }

    private static RemoteViews buildViews(Context context) {
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = LocalDate.now(zone);
        LocalDate selected = selectedDay(context, today);

        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_calendar);
        views.setTextViewText(R.id.month_title, context.getString(R.string.month_title, selected.getMonthValue()));
        views.setTextViewText(R.id.week_title, context.getString(R.string.week_title, weekNumber(selected)));

        Intent todayIntent = new Intent(context, CalendarWidgetProvider.class).setAction(ACTION_TODAY);
        views.setOnClickPendingIntent(R.id.month_header, broadcast(context, todayIntent));

        LocalDate gridStart = selected.withDayOfMonth(1)
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY));

        for (int row = 0; row < 6; row++) {
            views.setTextViewText(WEEK_IDS[row], String.valueOf(weekNumber(gridStart.plusDays(row * 7L))));
        }

        for (int i = 0; i < 42; i++) {
            LocalDate d = gridStart.plusDays(i);
            int cellId = DAY_IDS[i];
            views.setTextViewText(cellId, String.valueOf(d.getDayOfMonth()));

            int textColorRes;
            if (d.equals(selected)) {
                textColorRes = R.color.on_accent;
                views.setInt(cellId, "setBackgroundResource", R.drawable.day_selected);
            } else {
                views.setInt(cellId, "setBackgroundResource", 0);
                if (d.equals(today)) {
                    textColorRes = R.color.accent;
                } else if (d.getMonth() == selected.getMonth()) {
                    textColorRes = R.color.text_primary;
                } else {
                    textColorRes = R.color.text_dim;
                }
            }
            views.setColor(cellId, "setTextColor", textColorRes);

            Intent selIntent = new Intent(context, CalendarWidgetProvider.class)
                    .setAction(ACTION_SELECT)
                    .setData(Uri.parse("calwidget://select/" + d.toEpochDay()));
            views.setOnClickPendingIntent(cellId, broadcast(context, selIntent));
        }

        views.setTextViewText(R.id.weekday_title, WEEKDAYS[selected.getDayOfWeek().getValue() - 1]);
        views.setTextViewText(R.id.day_title, context.getString(R.string.day_title, selected.getDayOfMonth()));

        long selectedStartMillis = selected.atStartOfDay(zone).toInstant().toEpochMilli();
        Uri dayUri = ContentUris.withAppendedId(
                CalendarContract.CONTENT_URI.buildUpon().appendPath("time").build(),
                selectedStartMillis);
        PendingIntent openDayIntent = activity(context, 1, new Intent(Intent.ACTION_VIEW, dayUri));

        boolean hasCalPerm = context.checkSelfPermission(Manifest.permission.READ_CALENDAR)
                == PackageManager.PERMISSION_GRANTED;
        boolean hasNotion = NotionManager.hasToken(context);

        if (hasCalPerm || hasNotion) {
            views.setViewVisibility(R.id.empty, View.GONE);
            views.setViewVisibility(R.id.agenda_list, View.VISIBLE);
            views.setOnClickPendingIntent(R.id.day_header, openDayIntent);
            views.setRemoteAdapter(R.id.agenda_list, buildAgenda(context, selected, today, zone));

            Intent tpl = new Intent(context, OpenActivity.class);
            PendingIntent piTpl = PendingIntent.getActivity(
                    context, 20, tpl, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
            views.setPendingIntentTemplate(R.id.agenda_list, piTpl);
        } else {
            views.setViewVisibility(R.id.agenda_list, View.GONE);
            views.setViewVisibility(R.id.empty, View.VISIBLE);
            views.setTextViewText(R.id.empty, context.getString(R.string.need_permission));
            PendingIntent openMain = activity(context, 2, new Intent(context, MainActivity.class));
            views.setOnClickPendingIntent(R.id.agenda, openMain);
            views.setOnClickPendingIntent(R.id.day_header, openMain);
            views.setOnClickPendingIntent(R.id.empty, openMain);
        }

        ZonedDateTime insertStart = selected.equals(today)
                ? ZonedDateTime.now(zone).withMinute(0).withSecond(0).withNano(0).plusHours(1)
                : selected.atTime(LocalTime.of(9, 0)).atZone(zone);
        long insertBeginMillis = insertStart.toInstant().toEpochMilli();
        Intent insertIntent = new Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)
                .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, insertBeginMillis)
                .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, insertBeginMillis + 3_600_000L);
        views.setOnClickPendingIntent(R.id.add, activity(context, 3, insertIntent));

        return views;
    }

    private static RemoteViews.RemoteCollectionItems buildAgenda(
            Context context, LocalDate startDay, LocalDate today, ZoneId zone) {
        String pkg = context.getPackageName();
        RemoteViews.RemoteCollectionItems.Builder builder = new RemoteViews.RemoteCollectionItems.Builder()
                .setHasStableIds(false)
                .setViewTypeCount(3);

        int days = daysToShow(context);
        long itemId = 0L;

        for (int offset = 0; offset < days; offset++) {
            LocalDate day = startDay.plusDays(offset);
            long dayMillis = day.atStartOfDay(zone).toInstant().toEpochMilli();
            Intent dayFill = new Intent().putExtra(OpenActivity.EXTRA_DAY_MILLIS, dayMillis);

            if (offset > 0) {
                RemoteViews dayHeader = new RemoteViews(pkg, R.layout.widget_day_item);
                dayHeader.setTextViewText(R.id.item_weekday, WEEKDAYS[day.getDayOfWeek().getValue() - 1]);
                dayHeader.setTextViewText(R.id.item_day, context.getString(R.string.day_title, day.getDayOfMonth()));
                dayHeader.setOnClickFillInIntent(R.id.item_root, dayFill);
                builder.addItem(itemId++, dayHeader);
            }

            boolean isToday = day.equals(today);
            List<Event> events = loadEvents(context, day, zone, isToday);
            if (events.isEmpty()) {
                RemoteViews emptyItem = new RemoteViews(pkg, R.layout.widget_empty_item);
                emptyItem.setTextViewText(R.id.item_root,
                        context.getString(isToday ? R.string.no_more_events : R.string.no_events));
                emptyItem.setOnClickFillInIntent(R.id.item_root, dayFill);
                builder.addItem(itemId++, emptyItem);
            } else {
                for (Event ev : events) {
                    RemoteViews row = new RemoteViews(pkg, R.layout.widget_event_item);
                    row.setInt(R.id.item_bar, "setColorFilter", ev.color | 0xFF000000);
                    row.setTextViewText(R.id.item_title, ev.title);
                    row.setTextViewText(R.id.item_time, ev.subtitle);

                    Intent fill = new Intent();
                    if (!TextUtils.isEmpty(ev.notionUrl)) {
                        fill.putExtra(OpenActivity.EXTRA_NOTION_URL, ev.notionUrl);
                    } else {
                        fill.putExtra(OpenActivity.EXTRA_EVENT_ID, ev.eventId)
                                .putExtra(OpenActivity.EXTRA_BEGIN, ev.begin)
                                .putExtra(OpenActivity.EXTRA_END, ev.end);
                    }
                    row.setOnClickFillInIntent(R.id.item_root, fill);
                    builder.addItem(itemId++, row);
                }
            }
        }
        return builder.build();
    }

    static int weekNumber(LocalDate date) {
        return date.with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY))
                .plusDays(4)
                .get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
    }

    private static LocalDate selectedDay(Context context, LocalDate today) {
        SharedPreferences sp = prefs(context);
        if (sp.contains(KEY_SELECTED) && sp.getLong(KEY_SELECTED_ON, -1L) == today.toEpochDay()) {
            return LocalDate.ofEpochDay(sp.getLong(KEY_SELECTED, today.toEpochDay()));
        }
        return today;
    }

    private static List<Event> loadEvents(Context context, LocalDate day, ZoneId zone, boolean filterEndedToday) {
        List<Event> list = new ArrayList<>();
        long dayStart = day.atStartOfDay(zone).toInstant().toEpochMilli();
        long dayEnd = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
        long julianDay = day.getLong(JulianFields.JULIAN_DAY);
        long nowMillis = System.currentTimeMillis();
        boolean includeAllDay = showAllDay(context);
        Set<String> hidden = hiddenCalendars(context);

        if (context.checkSelfPermission(Manifest.permission.READ_CALENDAR)
                == PackageManager.PERMISSION_GRANTED) {
            Uri.Builder builder = CalendarContract.Instances.CONTENT_URI.buildUpon();
            ContentUris.appendId(builder, dayStart - 86_400_000L);
            ContentUris.appendId(builder, dayEnd + 86_400_000L);

            String[] proj = new String[]{
                    CalendarContract.Instances.EVENT_ID,
                    CalendarContract.Instances.TITLE,
                    CalendarContract.Instances.BEGIN,
                    CalendarContract.Instances.END,
                    CalendarContract.Instances.ALL_DAY,
                    CalendarContract.Instances.START_DAY,
                    CalendarContract.Instances.END_DAY,
                    CalendarContract.Instances.EVENT_LOCATION,
                    CalendarContract.Instances.DISPLAY_COLOR,
                    CalendarContract.Instances.SELF_ATTENDEE_STATUS,
                    CalendarContract.Instances.CALENDAR_ID
            };

            try (Cursor c = context.getContentResolver().query(
                    builder.build(),
                    proj,
                    CalendarContract.Instances.VISIBLE + " = 1",
                    null,
                    CalendarContract.Instances.BEGIN + " ASC")) {
                if (c != null) {
                    while (c.moveToNext()) {
                        if (c.getInt(9) == CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED) {
                            continue;
                        }
                        long calId = c.getLong(10);
                        if (hidden.contains(String.valueOf(calId))) {
                            continue;
                        }

                        Event ev = new Event();
                        ev.eventId = c.getLong(0);
                        ev.begin = c.getLong(2);
                        ev.end = c.getLong(3);
                        ev.allDay = c.getInt(4) != 0;

                        if (ev.allDay) {
                            if (!includeAllDay) continue;
                            if (c.getLong(5) > julianDay || c.getLong(6) < julianDay) continue;
                        } else {
                            boolean startsToday = ev.begin >= dayStart && ev.begin < dayEnd;
                            boolean spansToday = ev.begin < dayStart && ev.end > dayStart;
                            if (!startsToday && !spansToday) continue;
                            if (filterEndedToday && ev.end <= nowMillis && ev.end > ev.begin) continue;
                        }

                        String rawTitle = c.getString(1);
                        ev.title = TextUtils.isEmpty(rawTitle) ? context.getString(R.string.untitled) : rawTitle;
                        ev.color = c.getInt(8);

                        String timePart = ev.allDay
                                ? context.getString(R.string.all_day)
                                : (hm(ev.begin, zone) + "–" + hm(ev.end, zone));
                        String loc = c.getString(7);
                        if (!TextUtils.isEmpty(loc)) {
                            ev.subtitle = timePart + " · " + loc.trim();
                        } else {
                            ev.subtitle = timePart;
                        }
                        list.add(ev);
                    }
                }
            } catch (SecurityException | IllegalArgumentException ignored) {
            }
        }

        // Merge cached Notion Calendar events
        list.addAll(NotionManager.loadEventsForDay(
                context, day, zone, filterEndedToday, includeAllDay, hidden));

        list.sort((a, b) -> {
            if (a.allDay != b.allDay) {
                return a.allDay ? -1 : 1;
            }
            return Long.compare(a.begin, b.begin);
        });
        return list;
    }

    static String hm(long millis, ZoneId zone) {
        LocalTime lt = Instant.ofEpochMilli(millis).atZone(zone).toLocalTime();
        return String.format(Locale.US, "%02d:%02d", lt.getHour(), lt.getMinute());
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static PendingIntent broadcast(Context context, Intent intent) {
        return PendingIntent.getBroadcast(
                context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static PendingIntent activity(Context context, int requestCode, Intent intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(
                context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static PendingIntent tickIntent(Context context) {
        return broadcast(context, new Intent(context, CalendarWidgetProvider.class).setAction(ACTION_TICK));
    }

    private static void scheduleNextTick(Context context) {
        AlarmManager am = context.getSystemService(AlarmManager.class);
        if (am == null) return;
        long nextHourMillis = ZonedDateTime.now()
                .withMinute(0).withSecond(0).withNano(0)
                .plusHours(1)
                .toInstant()
                .toEpochMilli() + 1000L;
        am.set(AlarmManager.RTC, nextHourMillis, tickIntent(context));
    }
}
