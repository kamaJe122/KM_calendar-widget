package io.github.kamaje122.calwidget;

import android.Manifest;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.service.notification.StatusBarNotification;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.StyleSpan;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Schedules & renders grouped, expandable Notion-Calendar-style event reminder notifications
 * supporting both Google/System Calendars and Notion Databases, with 1-tap Snooze (「延後」).
 */
public final class EventReminderManager {

    public static final String CHANNEL_ID = "km_event_reminders";
    public static final String GROUP_KEY = "io.github.kamaje122.calwidget.EVENT_REMINDERS";
    public static final int NOTIFICATION_ID_SUMMARY = 910_000;

    private static final String PREFS_STATE = "km_reminder_state";
    private static final String KEY_FIRED_SET = "firedKeys";
    private static final String KEY_BASELINE_MS = "baselineInitMs";

    private static final int REQ_NEXT_ALARM = 920_001;
    private static final int REQ_PERIODIC_REFRESH = 920_002;
    private static final long PERIODIC_REFRESH_MS = 3L * 3_600_000L; // 3h rolling sync & reschedule

    private static final ExecutorService SCHED_EXECUTOR = Executors.newSingleThreadExecutor();

    private static final class PendingTrigger {
        final CalendarEvent event;
        final long triggerAtMillis;
        final int offsetMinutes;
        final String dedupKey;

        PendingTrigger(CalendarEvent event, long triggerAtMillis, int offsetMinutes, String dedupKey) {
            this.event = event;
            this.triggerAtMillis = triggerAtMillis;
            this.offsetMinutes = offsetMinutes;
            this.dedupKey = dedupKey;
        }
    }

    private EventReminderManager() {
    }

    public static boolean canPostNotifications(Context context) {
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm == null || !nm.areNotificationsEnabled()) return false;
        if (Build.VERSION.SDK_INT >= 33) {
            return context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    public static void ensureChannel(Context context) {
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel ch = nm.getNotificationChannel(CHANNEL_ID);
        if (ch == null) {
            ch = new NotificationChannel(
                    CHANNEL_ID,
                    "行程提醒通知",
                    NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("Google 行事曆與 Notion 資料庫行程提醒");
            ch.enableVibration(true);
            nm.createNotificationChannel(ch);
        }
    }

    /** Asynchronously evaluates due reminders and schedules the next exact AlarmManager wakeup. */
    public static void scheduleAsync(Context context) {
        final Context appCtx = context.getApplicationContext();
        SCHED_EXECUTOR.execute(() -> evaluateAndScheduleSync(appCtx));
    }

    /** Runs synchronously (for BroadcastReceiver goAsync() workers). */
    public static void evaluateAndScheduleSync(Context context) {
        ensureChannel(context);
        AlarmManager am = context.getSystemService(AlarmManager.class);
        if (am == null) return;

        int mode = AppPrefs.getNotifMode(context);
        if (mode == AppPrefs.NOTIF_MODE_OFF) {
            cancelScheduledAlarms(context, am);
            return;
        }

        long now = System.currentTimeMillis();
        SharedPreferences state = context.getSharedPreferences(PREFS_STATE, Context.MODE_PRIVATE);
        long baselineMs = state.getLong(KEY_BASELINE_MS, 0L);
        if (baselineMs <= 0L) {
            // Avoid retroactively firing reminders that were already in the past before feature init
            baselineMs = now - 60_000L;
            state.edit().putLong(KEY_BASELINE_MS, baselineMs).apply();
        }

        Map<String, Long> firedMap = loadFiredMap(state, now);

        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = LocalDate.now(zone);
        Set<String> hidden = AppPrefs.getAppHiddenCalendars(context);
        List<CalendarEvent> events = CalendarDataRepository.loadEventsInRange(
                context, today.minusDays(1), today.plusDays(7), hidden);

        // Batch-load Google/System reminders if mode includes Google/System calendars
        Map<Long, List<Integer>> sysReminders = new HashMap<>();
        if (mode == AppPrefs.NOTIF_MODE_ALL) {
            Set<Long> sysIds = new HashSet<>();
            for (CalendarEvent ev : events) {
                if (!ev.isNotion() && ev.eventId >= 0L) {
                    sysIds.add(ev.eventId);
                }
            }
            sysReminders = CalendarDataRepository.loadRemindersForEvents(context, sysIds);
        }

        List<PendingTrigger> dueNow = new ArrayList<>();
        long nextWakeupMillis = Long.MAX_VALUE;
        long graceWindowStart = Math.max(baselineMs, now - 15L * 60_000L);

        for (CalendarEvent ev : events) {
            boolean isNotion = ev.isNotion();
            if (!isNotion && mode == AppPrefs.NOTIF_MODE_NOTION_ONLY) continue;

            List<Integer> offsets;
            if (isNotion) {
                offsets = AppPrefs.getEffectiveNotionReminders(context, ev);
            } else {
                offsets = sysReminders.get(ev.eventId);
            }
            if (offsets == null || offsets.isEmpty()) continue;

            long anchorMillis = computeEventAnchorMillis(ev, zone);
            if (anchorMillis <= 0L) continue;
            // Don't fire reminders for events that have already completely ended
            long effectiveEnd = ev.endMillis > anchorMillis ? ev.endMillis : (anchorMillis + 3_600_000L);
            if (effectiveEnd < now) continue;

            for (Integer minBefore : offsets) {
                if (minBefore == null) continue;
                long triggerAt = anchorMillis - (minBefore * 60_000L);
                String key = buildDedupKey(ev, anchorMillis, minBefore);
                if (firedMap.containsKey(key)) continue;

                if (triggerAt <= now + 2_000L) {
                    if (triggerAt >= graceWindowStart) {
                        dueNow.add(new PendingTrigger(ev, triggerAt, minBefore, key));
                    } else {
                        // Missed beyond 15m window; mark seen so we don't spam stale alerts
                        firedMap.put(key, now);
                    }
                } else if (triggerAt < nextWakeupMillis) {
                    nextWakeupMillis = triggerAt;
                }
            }
        }

        dueNow.sort(Comparator.comparingLong(a -> a.event.beginMillis));
        boolean postedAny = false;
        if (!dueNow.isEmpty() && canPostNotifications(context)) {
            for (PendingTrigger pt : dueNow) {
                int notifId = stableNotificationId(pt.event, pt.offsetMinutes);
                postSingleChildNotification(context, pt.event, notifId, now);
                firedMap.put(pt.dedupKey, now);
                postedAny = true;
            }
            if (postedAny) {
                syncGroupSummary(context);
            }
        } else if (!dueNow.isEmpty()) {
            // Permission not yet granted: don't mark future events lost if still before start,
            // only mark past triggers so we don't build up a backlog.
            for (PendingTrigger pt : dueNow) {
                if (pt.triggerAtMillis < now - 60_000L) {
                    firedMap.put(pt.dedupKey, now);
                } else if (pt.triggerAtMillis + 60_000L < nextWakeupMillis) {
                    nextWakeupMillis = now + 60_000L;
                }
            }
        }

        saveFiredMap(state, firedMap);

        // Schedule next exact reminder alarm
        PendingIntent checkPi = PendingIntent.getBroadcast(
                context,
                REQ_NEXT_ALARM,
                new Intent(context, EventReminderReceiver.class)
                        .setAction(EventReminderReceiver.ACTION_CHECK_REMINDERS),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        if (nextWakeupMillis < Long.MAX_VALUE) {
            scheduleExactCompat(am, nextWakeupMillis, checkPi);
        } else {
            am.cancel(checkPi);
        }

        // Schedule periodic 3h rolling refresh (also refreshes Notion cache in background)
        PendingIntent refreshPi = PendingIntent.getBroadcast(
                context,
                REQ_PERIODIC_REFRESH,
                new Intent(context, EventReminderReceiver.class)
                        .setAction(EventReminderReceiver.ACTION_PERIODIC_SYNC),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, now + PERIODIC_REFRESH_MS, refreshPi);
    }

    /** Posts a single event notification and updates the Notion-Calendar-style group summary. */
    public static void postImmediateEventNotification(Context context, CalendarEvent ev, int notifId) {
        if (!canPostNotifications(context) || ev == null) return;
        ensureChannel(context);
        postSingleChildNotification(context, ev, notifId, System.currentTimeMillis());
        syncGroupSummary(context);
    }

    /**
     * One-tap 「延後」 handler: cancels the tapped notification immediately, updates the group
     * summary, and schedules an exact snooze re-alert after the user's configured snooze minutes.
     */
    public static void snoozeNotification(Context context, CalendarEvent ev, int notifId) {
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm != null && notifId != 0) {
            nm.cancel(notifId);
        }
        syncGroupSummary(context);

        if (ev == null) return;
        AlarmManager am = context.getSystemService(AlarmManager.class);
        if (am == null) return;

        int snoozeMin = AppPrefs.getSnoozeMinutes(context);
        long triggerAt = System.currentTimeMillis() + snoozeMin * 60_000L;
        int snoozeId = (notifId != 0) ? notifId : stableNotificationId(ev, 0);

        Intent fire = new Intent(context, EventReminderReceiver.class)
                .setAction(EventReminderReceiver.ACTION_FIRE_SNOOZED)
                .putExtra(EventReminderReceiver.EXTRA_NOTIF_ID, snoozeId);
        ev.writeToIntent(fire);

        PendingIntent pi = PendingIntent.getBroadcast(
                context,
                snoozeId,
                fire,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        scheduleExactCompat(am, triggerAt, pi);
    }

    /**
     * Sends 2 grouped preview notifications immediately so the user can inspect both the collapsed
     * `[2 ⌄]` stack and expanded `[2 ⌃]` cards + 「延後」 button right away.
     */
    public static boolean sendPreviewNotifications(Context context) {
        if (!canPostNotifications(context)) return false;
        ensureChannel(context);

        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = LocalDate.now(zone);
        List<CalendarEvent> upcoming = CalendarDataRepository.loadEventsInRange(
                context, today, today.plusDays(14), AppPrefs.getAppHiddenCalendars(context));

        long now = System.currentTimeMillis();
        List<CalendarEvent> picks = new ArrayList<>();
        for (CalendarEvent e : upcoming) {
            if (!e.allDay && e.endMillis >= now) {
                picks.add(e);
                if (picks.size() >= 2) break;
            }
        }
        for (CalendarEvent e : upcoming) {
            if (picks.size() >= 2) break;
            if (!picks.contains(e)) picks.add(e);
        }

        if (picks.isEmpty()) {
            ZonedDateTime base = ZonedDateTime.now(zone).withSecond(0).withNano(0).plusMinutes(30);
            CalendarEvent demo1 = new CalendarEvent();
            demo1.title = "出發：行程提醒預覽 ✍️";
            demo1.location = "台北市中山區民權東路一段60號";
            demo1.calendarName = "KM 行事曆";
            demo1.startEpochDay = base.toLocalDate().toEpochDay();
            demo1.endEpochDay = demo1.startEpochDay;
            demo1.beginMillis = base.toInstant().toEpochMilli();
            demo1.endMillis = base.plusMinutes(30).toInstant().toEpochMilli();
            demo1.hasExplicitEnd = true;
            picks.add(demo1);

            CalendarEvent demo2 = new CalendarEvent();
            demo2.title = "下一場待辦事項預覽 ✍️";
            demo2.location = "點擊「延後」可延後 " + AppPrefs.getSnoozeMinutes(context) + " 分鐘再提醒";
            demo2.calendarName = "Notion 日曆";
            demo2.startEpochDay = base.plusMinutes(30).toLocalDate().toEpochDay();
            demo2.endEpochDay = demo2.startEpochDay;
            demo2.beginMillis = base.plusMinutes(30).toInstant().toEpochMilli();
            demo2.endMillis = base.plusMinutes(90).toInstant().toEpochMilli();
            demo2.hasExplicitEnd = true;
            picks.add(demo2);
        } else if (picks.size() == 1) {
            CalendarEvent copy = new CalendarEvent();
            CalendarEvent src = picks.get(0);
            copy.title = "提醒預覽（展開可測試「延後」按鈕）";
            copy.location = TextUtils.isEmpty(src.location)
                    ? "當同時有 2 筆以上通知時會自動摺疊成群組卡片" : src.location;
            copy.calendarName = src.calendarName;
            copy.startEpochDay = src.startEpochDay;
            copy.endEpochDay = src.endEpochDay;
            copy.beginMillis = src.beginMillis + 1_800_000L;
            copy.endMillis = src.endMillis + 1_800_000L;
            copy.allDay = src.allDay;
            copy.hasExplicitEnd = true;
            picks.add(copy);
        }

        postSingleChildNotification(context, picks.get(0), 910_901, now);
        postSingleChildNotification(context, picks.get(1), 910_902, now);
        syncGroupSummary(context);
        return true;
    }

    private static void postSingleChildNotification(
            Context context, CalendarEvent ev, int notifId, long whenMillis) {
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm == null) return;

        ZoneId zone = ZoneId.systemDefault();
        String title = TextUtils.isEmpty(ev.title) ? context.getString(R.string.untitled) : ev.title;
        String timeLine = formatCompactTimeRange(ev, zone);
        String detail = resolveDetailLine(ev);

        // Single-line collapsed preview: "18:30–19:00 hoda創意包膜服務中心-台..."
        String collapsedText = detail.isEmpty() ? timeLine : (timeLine + " " + detail);
        // Expanded multi-line BigTextStyle:
        // Line 1: "18:00–18:30"
        // Line 2+: "hoda創意包膜服務中心-台北民權店, 104台北市中山區民權東路一段60號"
        String expandedBigText = detail.isEmpty() ? timeLine : (timeLine + "\n" + detail);

        // Content tap -> open MainActivity on that date and pop open the Unified Floating Event Sheet
        Intent openIntent = new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_TARGET_EPOCH_DAY, ev.startEpochDay)
                .putExtra(MainActivity.EXTRA_DISMISS_NOTIF_ID, notifId);
        ev.writeToIntent(openIntent);
        PendingIntent contentPi = PendingIntent.getActivity(
                context,
                notifId,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // Swipe dismiss -> clean up group summary if < 2 children remain
        Intent delIntent = new Intent(context, EventReminderReceiver.class)
                .setAction(EventReminderReceiver.ACTION_NOTIFICATION_DISMISSED)
                .putExtra(EventReminderReceiver.EXTRA_NOTIF_ID, notifId);
        PendingIntent deletePi = PendingIntent.getBroadcast(
                context,
                notifId + 100_000,
                delIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // Bottom-left 「延後」 action button
        Intent snoozeIntent = new Intent(context, EventReminderReceiver.class)
                .setAction(EventReminderReceiver.ACTION_SNOOZE_REMINDER)
                .putExtra(EventReminderReceiver.EXTRA_NOTIF_ID, notifId);
        ev.writeToIntent(snoozeIntent);
        PendingIntent snoozePi = PendingIntent.getBroadcast(
                context,
                notifId + 200_000,
                snoozeIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Action snoozeAction = new Notification.Action.Builder(
                (Icon) null, "延後", snoozePi).build();

        Notification notification = new Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_calendar_31)
                .setContentTitle(title)
                .setContentText(collapsedText)
                .setStyle(new Notification.BigTextStyle()
                        .setBigContentTitle(title)
                        .bigText(expandedBigText))
                .setWhen(whenMillis)
                .setShowWhen(true)
                .setCategory(Notification.CATEGORY_EVENT)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setAutoCancel(true)
                .setContentIntent(contentPi)
                .setDeleteIntent(deletePi)
                .setGroup(GROUP_KEY)
                .setGroupAlertBehavior(Notification.GROUP_ALERT_CHILDREN)
                .addAction(snoozeAction)
                .build();

        nm.notify(notifId, notification);
    }

    /**
     * Keeps the Android notification group summary in sync with currently active child cards:
     * - 2+ active children -> posts/updates `[N ⌄]` grouped stack summary (InboxStyle)
     * - 0 or 1 active child -> cancels summary so 1 card stands cleanly alone & 0 leaves no ghost
     */
    public static void syncGroupSummary(Context context) {
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm == null) return;

        StatusBarNotification[] active;
        try {
            active = nm.getActiveNotifications();
        } catch (RuntimeException e) {
            return;
        }

        List<StatusBarNotification> children = new ArrayList<>();
        for (StatusBarNotification sbn : active) {
            if (sbn.getId() == NOTIFICATION_ID_SUMMARY) continue;
            if (GROUP_KEY.equals(sbn.getNotification().getGroup())) {
                children.add(sbn);
            }
        }

        if (children.size() < 2) {
            nm.cancel(NOTIFICATION_ID_SUMMARY);
            return;
        }

        children.sort((a, b) -> Long.compare(b.getPostTime(), a.getPostTime()));
        long latestWhen = children.get(0).getNotification().when;
        if (latestWhen <= 0L) latestWhen = System.currentTimeMillis();

        Notification.InboxStyle inbox = new Notification.InboxStyle();
        int limit = Math.min(6, children.size());
        for (int i = 0; i < limit; i++) {
            Notification n = children.get(i).getNotification();
            CharSequence t = n.extras.getCharSequence(Notification.EXTRA_TITLE, "");
            CharSequence txt = n.extras.getCharSequence(Notification.EXTRA_TEXT, "");
            String titleStr = t == null ? "" : t.toString();
            String bodyStr = txt == null ? "" : txt.toString();
            if (bodyStr.isEmpty()) {
                SpannableString sp = new SpannableString(titleStr);
                sp.setSpan(new StyleSpan(Typeface.BOLD), 0, titleStr.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                inbox.addLine(sp);
            } else {
                String full = titleStr + " " + bodyStr;
                SpannableString sp = new SpannableString(full);
                sp.setSpan(new StyleSpan(Typeface.BOLD), 0, titleStr.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                inbox.addLine(sp);
            }
        }

        Intent openMain = new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent mainPi = PendingIntent.getActivity(
                context,
                NOTIFICATION_ID_SUMMARY,
                openMain,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification summary = new Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_calendar_31)
                .setContentTitle(children.size() + " 個行程提醒")
                .setContentText("展開查看詳細行程與延後選項")
                .setStyle(inbox)
                .setWhen(latestWhen)
                .setShowWhen(true)
                .setCategory(Notification.CATEGORY_EVENT)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setAutoCancel(true)
                .setContentIntent(mainPi)
                .setGroup(GROUP_KEY)
                .setGroupSummary(true)
                .setGroupAlertBehavior(Notification.GROUP_ALERT_CHILDREN)
                .build();

        nm.notify(NOTIFICATION_ID_SUMMARY, summary);
    }

    /** Formats compact time range matching `18:00–18:30` in the Notion Calendar screenshots. */
    private static String formatCompactTimeRange(CalendarEvent ev, ZoneId zone) {
        if (ev.allDay) {
            if (ev.isMultiDay()) {
                LocalDate s = LocalDate.ofEpochDay(ev.startEpochDay);
                LocalDate e = LocalDate.ofEpochDay(ev.endEpochDay);
                return String.format(Locale.TAIWAN, "%d/%d–%d/%d 全天",
                        s.getMonthValue(), s.getDayOfMonth(), e.getMonthValue(), e.getDayOfMonth());
            }
            return "全天";
        }
        ZonedDateTime b = Instant.ofEpochMilli(ev.beginMillis).atZone(zone);
        if (!ev.hasExplicitEnd || ev.endMillis <= ev.beginMillis) {
            return String.format(Locale.US, "%02d:%02d", b.getHour(), b.getMinute());
        }
        ZonedDateTime e = Instant.ofEpochMilli(ev.endMillis).atZone(zone);
        if (b.toLocalDate().equals(e.toLocalDate())) {
            return String.format(Locale.US, "%02d:%02d–%02d:%02d",
                    b.getHour(), b.getMinute(), e.getHour(), e.getMinute());
        }
        return String.format(Locale.US, "%d/%d %02d:%02d–%d/%d %02d:%02d",
                b.getMonthValue(), b.getDayOfMonth(), b.getHour(), b.getMinute(),
                e.getMonthValue(), e.getDayOfMonth(), e.getHour(), e.getMinute());
    }

    private static String resolveDetailLine(CalendarEvent ev) {
        if (!TextUtils.isEmpty(ev.location)) {
            String loc = ev.location.trim().replaceAll("\\s*\\r?\\n\\s*", ", ");
            if (!loc.isEmpty()) return loc;
        }
        String desc = ev.description == null ? "" : ev.description.trim().replaceAll("\\s*\\r?\\n\\s*", " ");
        if (desc.length() > 160) desc = desc.substring(0, 160) + "…";
        String status = ev.statusLabel == null ? "" : ev.statusLabel.trim();
        if (!desc.isEmpty() && !status.isEmpty()) {
            return status + " · " + desc;
        }
        if (!desc.isEmpty()) {
            return desc;
        }
        if (!status.isEmpty() && !TextUtils.isEmpty(ev.calendarName)) {
            return ev.calendarName + " · " + status;
        }
        if (!status.isEmpty()) {
            return status;
        }
        return ev.calendarName == null ? "" : ev.calendarName.trim();
    }

    private static long computeEventAnchorMillis(CalendarEvent ev, ZoneId zone) {
        if (ev.allDay) {
            return LocalDate.ofEpochDay(ev.startEpochDay)
                    .atTime(ev.isNotion() ? LocalTime.of(9, 0) : LocalTime.MIDNIGHT)
                    .atZone(zone)
                    .toInstant()
                    .toEpochMilli();
        }
        return ev.beginMillis;
    }

    private static String buildDedupKey(CalendarEvent ev, long anchorMillis, int minBefore) {
        String idPart = ev.isNotion()
                ? ("N:" + ev.notionPageId)
                : ("G:" + ev.eventId);
        return idPart + "@" + anchorMillis + "@" + minBefore;
    }

    private static int stableNotificationId(CalendarEvent ev, int offsetMin) {
        String raw = (ev.isNotion() ? ("N:" + ev.notionPageId) : ("G:" + ev.eventId))
                + "|" + ev.beginMillis + "|" + offsetMin;
        int h = raw.hashCode() & 0x0FFF_FFFF;
        if (h == 0 || h == NOTIFICATION_ID_SUMMARY) h = 910_111;
        return h;
    }

    private static void scheduleExactCompat(AlarmManager am, long triggerAtMillis, PendingIntent pi) {
        try {
            if (am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi);
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi);
            }
        } catch (SecurityException e) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi);
        }
    }

    private static void cancelScheduledAlarms(Context context, AlarmManager am) {
        PendingIntent checkPi = PendingIntent.getBroadcast(
                context,
                REQ_NEXT_ALARM,
                new Intent(context, EventReminderReceiver.class)
                        .setAction(EventReminderReceiver.ACTION_CHECK_REMINDERS),
                PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
        if (checkPi != null) am.cancel(checkPi);

        PendingIntent refreshPi = PendingIntent.getBroadcast(
                context,
                REQ_PERIODIC_REFRESH,
                new Intent(context, EventReminderReceiver.class)
                        .setAction(EventReminderReceiver.ACTION_PERIODIC_SYNC),
                PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
        if (refreshPi != null) am.cancel(refreshPi);
    }

    private static Map<String, Long> loadFiredMap(SharedPreferences sp, long now) {
        Map<String, Long> out = new HashMap<>();
        Set<String> raw = sp.getStringSet(KEY_FIRED_SET, null);
        if (raw == null) return out;
        long cutoff = now - 72L * 3_600_000L; // keep 72h history
        for (String entry : raw) {
            int sep = entry.lastIndexOf('=');
            if (sep <= 0) continue;
            try {
                long ts = Long.parseLong(entry.substring(sep + 1));
                if (ts >= cutoff) {
                    out.put(entry.substring(0, sep), ts);
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    private static void saveFiredMap(SharedPreferences sp, Map<String, Long> map) {
        Set<String> encoded = new HashSet<>(map.size());
        for (Map.Entry<String, Long> e : map.entrySet()) {
            encoded.add(e.getKey() + "=" + e.getValue());
        }
        sp.edit().putStringSet(KEY_FIRED_SET, encoded).apply();
    }
}
