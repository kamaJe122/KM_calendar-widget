package io.github.kamaje122.calwidget;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

/**
 * Handles exact reminder wakeups, 1-tap 「延後」 snooze actions, notification swipe-dismiss
 * group cleanup, device reboot rescheduling, and background Notion reminder syncs.
 */
public final class EventReminderReceiver extends BroadcastReceiver {

    public static final String ACTION_CHECK_REMINDERS =
            "io.github.kamaje122.calwidget.ACTION_CHECK_REMINDERS";
    public static final String ACTION_PERIODIC_SYNC =
            "io.github.kamaje122.calwidget.ACTION_PERIODIC_SYNC";
    public static final String ACTION_SNOOZE_REMINDER =
            "io.github.kamaje122.calwidget.ACTION_SNOOZE_REMINDER";
    public static final String ACTION_FIRE_SNOOZED =
            "io.github.kamaje122.calwidget.ACTION_FIRE_SNOOZED";
    public static final String ACTION_NOTIFICATION_DISMISSED =
            "io.github.kamaje122.calwidget.ACTION_NOTIFICATION_DISMISSED";

    public static final String EXTRA_NOTIF_ID = "km_notif_id";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        final String action = intent.getAction();
        final Context appCtx = context.getApplicationContext();

        switch (action) {
            case ACTION_NOTIFICATION_DISMISSED:
                EventReminderManager.syncGroupSummary(appCtx);
                return;

            case ACTION_SNOOZE_REMINDER: {
                int notifId = intent.getIntExtra(EXTRA_NOTIF_ID, 0);
                CalendarEvent ev = CalendarEvent.fromIntent(intent);
                EventReminderManager.snoozeNotification(appCtx, ev, notifId);
                int mins = AppPrefs.getSnoozeMinutes(appCtx);
                Toast.makeText(appCtx, "⏰ 已延後 " + mins + " 分鐘提醒", Toast.LENGTH_SHORT).show();
                return;
            }

            case ACTION_FIRE_SNOOZED: {
                int notifId = intent.getIntExtra(EXTRA_NOTIF_ID, 910_555);
                CalendarEvent ev = CalendarEvent.fromIntent(intent);
                if (ev != null) {
                    EventReminderManager.postImmediateEventNotification(appCtx, ev, notifId);
                }
                EventReminderManager.scheduleAsync(appCtx);
                return;
            }

            case ACTION_PERIODIC_SYNC: {
                if (NotionManager.hasToken(appCtx)) {
                    NotionManager.refreshEventsOnlyAsync(appCtx, 15L * 60_000L, null);
                }
                runAsyncCheck(appCtx);
                return;
            }

            case ACTION_CHECK_REMINDERS:
            case Intent.ACTION_BOOT_COMPLETED:
            case Intent.ACTION_MY_PACKAGE_REPLACED:
            case Intent.ACTION_TIME_CHANGED:
            case Intent.ACTION_TIMEZONE_CHANGED:
            default:
                runAsyncCheck(appCtx);
        }
    }

    private void runAsyncCheck(Context appCtx) {
        final PendingResult pending = goAsync();
        new Thread(() -> {
            try {
                EventReminderManager.evaluateAndScheduleSync(appCtx);
            } finally {
                pending.finish();
            }
        }).start();
    }
}
