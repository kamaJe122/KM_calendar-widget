package io.github.kamaje122.calwidget;

import android.accounts.Account;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SyncAdapterType;
import android.content.SyncRequest;
import android.content.SyncStatusObserver;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.CalendarContract;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Two-stage sub-second Calendar sync engine & real-time dirty-queue verifier:
 *
 * 1. Stage 1 (0.00s): Schedules an Android 12+ Expedited Job (`SYNC_EXTRAS_SCHEDULE_AS_EXPEDITED_JOB`)
 *    with `SYNC_EXTRAS_MANUAL = true`, `SYNC_EXTRAS_UPLOAD = true` (upload-only fast pass so Google's
 *    SyncAdapter pushes `DIRTY = 1` rows immediately instead of scanning/downloading all calendars),
 *    and `SYNC_EXTRAS_OVERRIDE_TOO_MANY_DELETIONS = true` (prevents Google's 5-delete threshold freeze).
 * 2. Live 250ms Verification Loop: Queries `CalendarContract.Events` (`DIRTY = 1`) until Google's
 *    SyncAdapter confirms HTTP 200 and clears `DIRTY` -> reports exact sub-second / 1s completion.
 * 3. Stage 2 (on `DIRTY == 0` & foreground resume): Immediately wakes both `CalendarContract.AUTHORITY`
 *    (pull pass) AND any secondary Google Calendar internal SyncAdapter authorities so incoming cloud
 *    changes and Google Calendar's private store stay synchronized in ~1 second.
 * 4. OS SyncStatusObserver: Listens to Android SyncManager active/pending transitions to reload UI and
 *    widgets the instant a sync finishes (bypassing AOSP CalendarProvider2's 30s broadcast delay).
 */
public final class CalendarSyncEngine {

    public static final int STATE_IDLE = 0;
    public static final int STATE_SYNCING = 1;
    public static final int STATE_SYNCED = 2;
    public static final int STATE_BLOCKED = 3;

    public static final String PKG_GOOGLE_CALENDAR = "com.google.android.calendar";
    public static final String PKG_CALENDAR_STORAGE = "com.android.providers.calendar";

    public interface StatusListener {
        void onSyncStateChanged(int state, String message, DirtyReport report);

        default void onSyncCompleted() {
        }
    }

    public static final class DirtyItem {
        public final long eventId;
        public final String title;
        public final String calendarName;
        public final String accountName;
        public final String accountType;
        public final boolean isDeletion;

        DirtyItem(long eventId, String title, String calendarName,
                  String accountName, String accountType, boolean isDeletion) {
            this.eventId = eventId;
            this.title = title;
            this.calendarName = calendarName;
            this.accountName = accountName;
            this.accountType = accountType;
            this.isDeletion = isDeletion;
        }
    }

    public static final class DirtyReport {
        public final int totalDirty;
        public final int dirtyDeletions;
        public final int dirtyMutations;
        public final List<DirtyItem> items;
        public final Set<Account> dirtyAccounts;
        public final boolean batterySaverOn;
        public final boolean masterSyncOff;
        public final List<Account> disabledSyncAccounts;
        public final boolean syncCurrentlyActive;

        DirtyReport(int totalDirty, int dirtyDeletions, int dirtyMutations,
                    List<DirtyItem> items, Set<Account> dirtyAccounts,
                    boolean batterySaverOn, boolean masterSyncOff,
                    List<Account> disabledSyncAccounts, boolean syncCurrentlyActive) {
            this.totalDirty = totalDirty;
            this.dirtyDeletions = dirtyDeletions;
            this.dirtyMutations = dirtyMutations;
            this.items = items;
            this.dirtyAccounts = dirtyAccounts;
            this.batterySaverOn = batterySaverOn;
            this.masterSyncOff = masterSyncOff;
            this.disabledSyncAccounts = disabledSyncAccounts;
            this.syncCurrentlyActive = syncCurrentlyActive;
        }
    }

    private static final ExecutorService SYNC_EXEC = Executors.newSingleThreadExecutor();
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static final CopyOnWriteArrayList<StatusListener> LISTENERS = new CopyOnWriteArrayList<>();

    private static volatile int sWatchGeneration = 0;
    private static volatile long sMutationStartMs = 0L;
    private static volatile long sLastResumePullMs = 0L;
    private static volatile int sCurrentState = STATE_IDLE;
    private static volatile String sCurrentMessage = "";
    private static volatile DirtyReport sLastReport = null;
    private static volatile Object sSystemSyncObserverHandle = null;
    private static volatile boolean sWasAnySyncActive = false;

    private CalendarSyncEngine() {
    }

    public static void ensureSystemSyncObserver(Context context) {
        if (sSystemSyncObserverHandle != null || context == null) return;
        final Context appCtx = context.getApplicationContext();
        try {
            int mask = ContentResolver.SYNC_OBSERVER_TYPE_ACTIVE
                    | ContentResolver.SYNC_OBSERVER_TYPE_PENDING;
            SyncStatusObserver obs = which -> SYNC_EXEC.execute(() -> {
                boolean nowActive = isAnyCalendarSyncActive(appCtx);
                boolean finished = sWasAnySyncActive && !nowActive;
                sWasAnySyncActive = nowActive;
                if (finished) {
                    // Bypass AOSP CalendarProvider2's 30-second SYNC_UPDATE_BROADCAST_TIMEOUT_MILLIS!
                    CalendarWidgetProvider.updateAll(appCtx);
                    notifySyncCompleted();
                }
            });
            sSystemSyncObserverHandle = ContentResolver.addStatusChangeListener(mask, obs);
        } catch (RuntimeException ignored) {
        }
    }

    private static void notifySyncCompleted() {
        MAIN_HANDLER.post(() -> {
            for (StatusListener l : LISTENERS) {
                l.onSyncCompleted();
            }
        });
    }

    private static boolean isAnyCalendarSyncActive(Context context) {
        for (Account acc : loadSyncedAccounts(context)) {
            try {
                if (ContentResolver.isSyncActive(acc, CalendarContract.AUTHORITY)) {
                    return true;
                }
            } catch (RuntimeException ignored) {
            }
        }
        return false;
    }

    public static void addListener(StatusListener listener) {
        if (listener == null) return;
        LISTENERS.addIfAbsent(listener);
        if (sCurrentState != STATE_IDLE && !TextUtils.isEmpty(sCurrentMessage)) {
            listener.onSyncStateChanged(sCurrentState, sCurrentMessage, sLastReport);
        }
    }

    public static void removeListener(StatusListener listener) {
        LISTENERS.remove(listener);
    }

    /** Called right after creating, editing, updating reminders, or deleting on a specific calendarId. */
    public static void pushCalendarImmediate(Context context, long calendarId) {
        final Context appCtx = context.getApplicationContext();
        ensureSystemSyncObserver(appCtx);
        SYNC_EXEC.execute(() -> {
            if (calendarId > 0 && CalendarDataRepository.hasReadPermission(appCtx)) {
                Uri calUri = ContentUris.withAppendedId(CalendarContract.Calendars.CONTENT_URI, calendarId);
                String[] proj = {
                        CalendarContract.Calendars.ACCOUNT_NAME,
                        CalendarContract.Calendars.ACCOUNT_TYPE
                };
                try (Cursor c = appCtx.getContentResolver().query(calUri, proj, null, null, null)) {
                    if (c != null && c.moveToFirst()) {
                        String name = c.getString(0);
                        String type = c.getString(1);
                        if (!TextUtils.isEmpty(name) && !TextUtils.isEmpty(type)) {
                            pushAccountInternal(appCtx, new Account(name, type));
                            return;
                        }
                    }
                } catch (RuntimeException ignored) {
                }
            }
            pushAllSyncedAccountsInternal(appCtx, true);
        });
    }

    /** Called right after deleting or mutating when accountName + accountType are already known. */
    public static void pushAccountImmediate(Context context, String accountName, String accountType) {
        final Context appCtx = context.getApplicationContext();
        ensureSystemSyncObserver(appCtx);
        SYNC_EXEC.execute(() -> {
            if (!TextUtils.isEmpty(accountName) && !TextUtils.isEmpty(accountType)
                    && !CalendarContract.ACCOUNT_TYPE_LOCAL.equals(accountType)) {
                pushAccountInternal(appCtx, new Account(accountName, accountType));
            } else {
                pushAllSyncedAccountsInternal(appCtx, true);
            }
        });
    }

    /**
     * Triggered automatically on EVERY `MainActivity.onResume()` (with a 2.5s anti-bounce guard):
     * 1. Repairs `SYNC_EVENTS = 1` / `VISIBLE = 1` on any non-hidden Google calendar (fixes Pixel
     *    shared-calendar bug #551608026 where shared calendars default to `SYNC_EVENTS = 0`).
     * 2. Flushes any `DIRTY = 1` local edits via Stage 1 Fast Upload.
     * 3. ALWAYS dispatches Stage 2 Expedited Job Pull across all Google/System accounts so edits
     *    made in Google Calendar (or by shared-calendar partners) arrive immediately!
     */
    public static void syncOnForegroundResume(Context context) {
        final Context appCtx = context.getApplicationContext();
        ensureSystemSyncObserver(appCtx);
        long now = System.currentTimeMillis();
        if (now >= sLastResumePullMs && (now - sLastResumePullMs) < 2_500L) {
            return;
        }
        sLastResumePullMs = now;
        syncNowBidirectional(appCtx, false);
    }

    /** Immediate bidirectional cloud sync (used by foreground resume & Top-Bar / Drawer `↻` button). */
    public static void syncNowBidirectional(Context context, boolean showPullBanner) {
        final Context appCtx = context.getApplicationContext();
        ensureSystemSyncObserver(appCtx);
        sLastResumePullMs = System.currentTimeMillis();
        SYNC_EXEC.execute(() -> {
            healSharedCalendarsSyncFlags(appCtx);
            DirtyReport report = inspectDirtyQueue(appCtx);
            Set<Account> accounts = loadSyncedAccounts(appCtx);
            if (report.totalDirty > 0) {
                sMutationStartMs = System.currentTimeMillis();
                for (Account acc : report.dirtyAccounts) {
                    dispatchStage1FastUpload(acc);
                }
                for (Account acc : accounts) {
                    if (!report.dirtyAccounts.contains(acc)) {
                        dispatchStage2FullSyncAndWakeGoogleApp(acc);
                    }
                }
                startVerificationLoop(appCtx, accounts.isEmpty() ? report.dirtyAccounts : accounts);
                return;
            }

            if (accounts.isEmpty()) {
                if (sCurrentState == STATE_BLOCKED) emitState(STATE_IDLE, "", report);
                return;
            }

            long pullStart = System.currentTimeMillis();
            for (Account acc : accounts) {
                dispatchStage2FullSyncAndWakeGoogleApp(acc);
            }
            if (showPullBanner) {
                final int gen = ++sWatchGeneration;
                emitState(STATE_SYNCING, "🔄 正在極速同步 Google 與 Notion 最新行程…", report);
                watchPullCompletion(appCtx, gen, pullStart, 1);
            } else {
                if (sCurrentState == STATE_BLOCKED) emitState(STATE_IDLE, "", report);
                // Also run a lightweight silent completion check at +0.9s and +2.2s to guarantee UI refresh
                scheduleSilentPostPullReload(appCtx);
            }
        });
    }

    private static void watchPullCompletion(Context appCtx, int gen, long startMs, int step) {
        MAIN_HANDLER.postDelayed(() -> SYNC_EXEC.execute(() -> {
            if (gen != sWatchGeneration) return;
            boolean active = isAnyCalendarSyncActive(appCtx);
            // Wait at least 2 steps (500ms) so SyncManager has transitioned into active state
            if (!active && step >= 2) {
                long elapsed = Math.max(200L, System.currentTimeMillis() - startMs);
                String sec = String.format(Locale.US, "%.1f", elapsed / 1000.0);
                DirtyReport rep = inspectDirtyQueue(appCtx);
                CalendarWidgetProvider.updateAll(appCtx);
                notifySyncCompleted();
                emitState(STATE_SYNCED, "✓ 已同步最新雲端行程（" + sec + " 秒）", rep);
                scheduleAutoHide(gen, 2400L);
                return;
            }
            if (step < 24) {
                watchPullCompletion(appCtx, gen, startMs, step + 1);
            } else {
                CalendarWidgetProvider.updateAll(appCtx);
                notifySyncCompleted();
                emitState(STATE_SYNCED, "✓ 已觸發背景雙向同步", sLastReport);
                scheduleAutoHide(gen, 2000L);
            }
        }), 250L);
    }

    private static void scheduleSilentPostPullReload(Context appCtx) {
        MAIN_HANDLER.postDelayed(() -> {
            notifySyncCompleted();
            CalendarWidgetProvider.updateAll(appCtx);
        }, 1_100L);
        MAIN_HANDLER.postDelayed(() -> {
            notifySyncCompleted();
            CalendarWidgetProvider.updateAll(appCtx);
        }, 2_600L);
    }

    /**
     * Fixes Pixel / Android 15–16 bug (Google Issue #551608026) where secondary/shared Google
     * calendars are initialized with `SYNC_EVENTS = 0` or `VISIBLE = 0`, preventing CalendarProvider
     * from syncing shared calendar updates unless enabled on `Calendars.CONTENT_URI`.
     */
    private static void healSharedCalendarsSyncFlags(Context appCtx) {
        if (!CalendarDataRepository.hasReadPermission(appCtx)
                || !CalendarDataRepository.hasWritePermission(appCtx)) {
            return;
        }
        Set<String> appHidden = AppPrefs.getAppHiddenCalendars(appCtx);
        String[] proj = {
                CalendarContract.Calendars._ID,
                CalendarContract.Calendars.VISIBLE,
                CalendarContract.Calendars.SYNC_EVENTS,
                CalendarContract.Calendars.ACCOUNT_TYPE
        };
        List<Long> toEnable = new ArrayList<>();
        try (Cursor c = appCtx.getContentResolver().query(
                CalendarContract.Calendars.CONTENT_URI, proj, null, null, null)) {
            if (c != null) {
                while (c.moveToNext()) {
                    long id = c.getLong(0);
                    if (appHidden.contains(String.valueOf(id))) continue;
                    String type = c.getString(3);
                    if (type == null || !type.contains("google")) continue;
                    boolean vis = c.getInt(1) != 0;
                    boolean sync = c.getInt(2) != 0;
                    if (!vis || !sync) {
                        toEnable.add(id);
                    }
                }
            }
        } catch (RuntimeException ignored) {
        }
        for (Long id : toEnable) {
            try {
                ContentValues cv = new ContentValues();
                cv.put(CalendarContract.Calendars.VISIBLE, 1);
                cv.put(CalendarContract.Calendars.SYNC_EVENTS, 1);
                appCtx.getContentResolver().update(
                        ContentUris.withAppendedId(CalendarContract.Calendars.CONTENT_URI, id),
                        cv, null, null);
            } catch (RuntimeException ignored) {
            }
        }
    }

    /** Legacy entrypoint kept for compatibility: flushes dirty queue & pulls latest changes. */
    public static void flushStuckDirtyQueueOnStartup(Context context) {
        syncOnForegroundResume(context);
    }

    /** Bidirectional expedited sync across all synced accounts (used by manual refresh & widgets). */
    public static void requestAllExpeditedPull(Context context) {
        final Context appCtx = context.getApplicationContext();
        ensureSystemSyncObserver(appCtx);
        SYNC_EXEC.execute(() -> pushAllSyncedAccountsInternal(appCtx, false));
    }

    private static void pushAccountInternal(Context appCtx, Account account) {
        if (CalendarContract.ACCOUNT_TYPE_LOCAL.equals(account.type)) return;
        sMutationStartMs = System.currentTimeMillis();
        dispatchStage1FastUpload(account);
        Set<Account> targets = new LinkedHashSet<>();
        targets.add(account);
        startVerificationLoop(appCtx, targets);
    }

    private static void pushAllSyncedAccountsInternal(Context appCtx, boolean uploadFirst) {
        Set<Account> accounts = loadSyncedAccounts(appCtx);
        if (accounts.isEmpty()) return;
        if (uploadFirst) {
            sMutationStartMs = System.currentTimeMillis();
            for (Account acc : accounts) {
                dispatchStage1FastUpload(acc);
            }
            startVerificationLoop(appCtx, accounts);
        } else {
            DirtyReport dirty = inspectDirtyQueue(appCtx);
            for (Account acc : accounts) {
                if (dirty.totalDirty > 0) {
                    dispatchStage1FastUpload(acc);
                }
                dispatchStage2FullSyncAndWakeGoogleApp(acc);
            }
            if (dirty.totalDirty > 0) {
                sMutationStartMs = System.currentTimeMillis();
                startVerificationLoop(appCtx, accounts);
            }
        }
    }

    /**
     * Stage 1: Schedules an Android 12+ Expedited Job with UPLOAD-ONLY + OVERRIDE_TOO_MANY_DELETIONS
     * + MANUAL (implies IGNORE_BACKOFF + IGNORE_SETTINGS).
     *
     * IMPORTANT (verified against AOSP ContentResolver.hasInvalidScheduleAsEjExtras):
     * `SYNC_EXTRAS_SCHEDULE_AS_EXPEDITED_JOB` MUST NOT be combined with legacy `SYNC_EXTRAS_EXPEDITED`
     * or `SYNC_EXTRAS_REQUIRE_CHARGING`, otherwise SyncRequest.Builder.build() throws IAE!
     */
    private static void dispatchStage1FastUpload(Account account) {
        Bundle uploadExtras = new Bundle();
        uploadExtras.putBoolean(ContentResolver.SYNC_EXTRAS_UPLOAD, true);
        uploadExtras.putBoolean(ContentResolver.SYNC_EXTRAS_OVERRIDE_TOO_MANY_DELETIONS, true);

        boolean scheduledEj = false;
        try {
            SyncRequest req = new SyncRequest.Builder()
                    .setSyncAdapter(account, CalendarContract.AUTHORITY)
                    .syncOnce()
                    .setScheduleAsExpeditedJob(true)
                    .setManual(true)
                    .setExtras(uploadExtras)
                    .build();
            ContentResolver.requestSync(req);
            scheduledEj = true;
        } catch (RuntimeException ignored) {
        }

        if (!scheduledEj) {
            try {
                Bundle legacy = new Bundle(uploadExtras);
                legacy.putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true);
                legacy.putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true);
                legacy.putBoolean(ContentResolver.SYNC_EXTRAS_IGNORE_BACKOFF, true);
                legacy.putBoolean(ContentResolver.SYNC_EXTRAS_IGNORE_SETTINGS, true);
                ContentResolver.requestSync(account, CalendarContract.AUTHORITY, legacy);
            } catch (RuntimeException ignored) {
            }
        }
    }

    /**
     * Stage 2: Once local `DIRTY` rows are uploaded (or during full pull), triggers an expedited
     * pull on `CalendarContract.AUTHORITY` AND wakes any secondary Google Calendar internal
     * SyncAdapter authorities (e.g. Google Calendar app's own internal store) on the same account!
     */
    private static void dispatchStage2FullSyncAndWakeGoogleApp(Account account) {
        Set<String> authorities = new LinkedHashSet<>();
        authorities.add(CalendarContract.AUTHORITY);
        try {
            SyncAdapterType[] types = ContentResolver.getSyncAdapterTypes();
            if (types != null) {
                for (SyncAdapterType t : types) {
                    if (t != null && account.type.equals(t.accountType) && !TextUtils.isEmpty(t.authority)) {
                        String lower = t.authority.toLowerCase(Locale.ROOT);
                        if (lower.contains("calendar")) {
                            authorities.add(t.authority);
                        }
                    }
                }
            }
        } catch (RuntimeException ignored) {
        }

        for (String authority : authorities) {
            Bundle extras = new Bundle();
            extras.putBoolean(ContentResolver.SYNC_EXTRAS_OVERRIDE_TOO_MANY_DELETIONS, true);
            boolean ok = false;
            try {
                SyncRequest req = new SyncRequest.Builder()
                        .setSyncAdapter(account, authority)
                        .syncOnce()
                        .setScheduleAsExpeditedJob(true)
                        .setManual(true)
                        .setExtras(extras)
                        .build();
                ContentResolver.requestSync(req);
                ok = true;
            } catch (RuntimeException ignored) {
            }
            if (!ok) {
                try {
                    Bundle legacy = new Bundle(extras);
                    legacy.putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true);
                    legacy.putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true);
                    ContentResolver.requestSync(account, authority, legacy);
                } catch (RuntimeException ignored) {
                }
            }
        }
    }

    private static void startVerificationLoop(Context appCtx, Set<Account> accounts) {
        final int gen = ++sWatchGeneration;
        DirtyReport initial = inspectDirtyQueue(appCtx);
        if (initial.totalDirty == 0) {
            for (Account acc : accounts) {
                dispatchStage2FullSyncAndWakeGoogleApp(acc);
            }
            emitState(STATE_SYNCED, "✓ 已秒速同步至 Google 雲端", initial);
            scheduleAutoHide(gen, 2400L);
            return;
        }

        emitState(STATE_SYNCING,
                "🔄 正在秒速同步至 Google 雲端（" + initial.totalDirty + " 筆變更上傳中…）", initial);

        // Poll dirty queue every 250ms up to 8 seconds so we detect cloud confirmation to the quarter-second
        schedulePollStep(appCtx, accounts, gen, 1);
    }

    private static void schedulePollStep(
            Context appCtx, Set<Account> accounts, int gen, int step) {
        MAIN_HANDLER.postDelayed(() -> SYNC_EXEC.execute(() -> {
            if (gen != sWatchGeneration) return;
            DirtyReport report = inspectDirtyQueue(appCtx);
            long elapsedMs = Math.max(100L, System.currentTimeMillis() - sMutationStartMs);

            if (report.totalDirty == 0) {
                for (Account acc : accounts) {
                    dispatchStage2FullSyncAndWakeGoogleApp(acc);
                }
                String secStr = String.format(Locale.US, "%.1f", elapsedMs / 1000.0);
                emitState(STATE_SYNCED, "✓ Google 雲端已同步完成（" + secStr + " 秒）", report);
                CalendarWidgetProvider.updateAll(appCtx);
                scheduleAutoHide(gen, 2800L);
                return;
            }

            // Re-nudge Stage 1 once at ~1.75s if still pending
            if (step == 7) {
                for (Account acc : report.dirtyAccounts) {
                    dispatchStage1FastUpload(acc);
                }
            }

            // Surface actionable blocker warning early (at 2.25s) if Battery Saver or Sync Off is blocking!
            if (step >= 9 && (report.batterySaverOn || report.masterSyncOff || !report.disabledSyncAccounts.isEmpty())) {
                String warn;
                if (report.batterySaverOn) {
                    warn = "⚡ 省電模式正在延遲 Google 背景同步（" + report.totalDirty + " 筆待上傳 · 點此一鍵設定無限制）";
                } else {
                    warn = "⚠️ 系統日曆自動同步已關閉（" + report.totalDirty + " 筆待上傳 · 點此一鍵開啟）";
                }
                emitState(STATE_BLOCKED, warn, report);
                if (step < 32) {
                    schedulePollStep(appCtx, accounts, gen, step + 1);
                }
                return;
            }

            if (step < 32) { // up to 8.0s (32 * 250ms)
                schedulePollStep(appCtx, accounts, gen, step + 1);
            } else {
                emitState(STATE_BLOCKED,
                        "⏳ 尚有 " + report.totalDirty + " 筆變更等待 Google 背景處理（點此查看診斷／強制重送）",
                        report);
            }
        }), 250L);
    }

    private static void scheduleAutoHide(int gen, long delayMs) {
        MAIN_HANDLER.postDelayed(() -> {
            if (gen == sWatchGeneration && sCurrentState == STATE_SYNCED) {
                emitState(STATE_IDLE, "", sLastReport);
            }
        }, delayMs);
    }

    private static void emitState(int state, String message, DirtyReport report) {
        sCurrentState = state;
        sCurrentMessage = message;
        sLastReport = report;
        MAIN_HANDLER.post(() -> {
            for (StatusListener l : LISTENERS) {
                l.onSyncStateChanged(state, message, report);
            }
        });
    }

    public static DirtyReport inspectDirtyQueue(Context context) {
        boolean batterySaver = false;
        try {
            PowerManager pm = context.getSystemService(PowerManager.class);
            if (pm != null) batterySaver = pm.isPowerSaveMode();
        } catch (RuntimeException ignored) {
        }

        boolean masterSyncOff = false;
        try {
            masterSyncOff = !ContentResolver.getMasterSyncAutomatically();
        } catch (RuntimeException ignored) {
        }

        Set<Account> syncedAccounts = loadSyncedAccounts(context);
        List<Account> disabledAccounts = new ArrayList<>();
        boolean anyActive = false;
        for (Account acc : syncedAccounts) {
            try {
                if (!ContentResolver.getSyncAutomatically(acc, CalendarContract.AUTHORITY)) {
                    disabledAccounts.add(acc);
                }
                if (ContentResolver.isSyncActive(acc, CalendarContract.AUTHORITY)) {
                    anyActive = true;
                }
            } catch (RuntimeException ignored) {
            }
        }

        if (!CalendarDataRepository.hasReadPermission(context)) {
            return new DirtyReport(0, 0, 0, Collections.emptyList(), Collections.emptySet(),
                    batterySaver, masterSyncOff, disabledAccounts, anyActive);
        }

        int total = 0;
        int deletions = 0;
        int mutations = 0;
        List<DirtyItem> items = new ArrayList<>();
        Set<Account> dirtyAccounts = new LinkedHashSet<>();

        String[] proj = {
                CalendarContract.Events._ID,
                CalendarContract.Events.TITLE,
                CalendarContract.Events.DELETED,
                CalendarContract.Events.STATUS,
                CalendarContract.Events.CALENDAR_DISPLAY_NAME,
                CalendarContract.Calendars.ACCOUNT_NAME,
                CalendarContract.Calendars.ACCOUNT_TYPE,
                CalendarContract.Calendars.SYNC_EVENTS
        };
        String sel = CalendarContract.Events.DIRTY + " = 1 AND "
                + CalendarContract.Calendars.ACCOUNT_TYPE + " != ?";
        String[] args = {CalendarContract.ACCOUNT_TYPE_LOCAL};

        try (Cursor c = context.getContentResolver().query(
                CalendarContract.Events.CONTENT_URI, proj, sel, args,
                CalendarContract.Events._ID + " DESC")) {
            if (c != null) {
                while (c.moveToNext()) {
                    if (!c.isNull(7) && c.getInt(7) == 0) continue; // unsynced local-only calendar
                    String accName = c.getString(5);
                    String accType = c.getString(6);
                    if (TextUtils.isEmpty(accName) || TextUtils.isEmpty(accType)) continue;

                    total++;
                    boolean deleted = (!c.isNull(2) && c.getInt(2) != 0)
                            || (!c.isNull(3) && c.getInt(3) == CalendarContract.Events.STATUS_CANCELED);
                    if (deleted) deletions++;
                    else mutations++;

                    dirtyAccounts.add(new Account(accName, accType));
                    if (items.size() < 8) {
                        String t = c.getString(1);
                        if (TextUtils.isEmpty(t)) t = "（無標題行程）";
                        String cal = c.getString(4);
                        items.add(new DirtyItem(
                                c.getLong(0), t, cal == null ? "" : cal, accName, accType, deleted));
                    }
                }
            }
        } catch (RuntimeException ignored) {
        }

        return new DirtyReport(total, deletions, mutations, items, dirtyAccounts,
                batterySaver, masterSyncOff, disabledAccounts, anyActive);
    }

    private static Set<Account> loadSyncedAccounts(Context context) {
        Set<Account> out = new LinkedHashSet<>();
        if (!CalendarDataRepository.hasReadPermission(context)) return out;
        String[] proj = {
                CalendarContract.Calendars.ACCOUNT_NAME,
                CalendarContract.Calendars.ACCOUNT_TYPE
        };
        try (Cursor c = context.getContentResolver().query(
                CalendarContract.Calendars.CONTENT_URI, proj,
                CalendarContract.Calendars.SYNC_EVENTS + " = 1", null, null)) {
            if (c != null) {
                while (c.moveToNext()) {
                    String name = c.getString(0);
                    String type = c.getString(1);
                    if (!TextUtils.isEmpty(name) && !TextUtils.isEmpty(type)
                            && !CalendarContract.ACCOUNT_TYPE_LOCAL.equals(type)) {
                        out.add(new Account(name, type));
                    }
                }
            }
        } catch (RuntimeException ignored) {
        }
        return out;
    }

    /** Enables Master Sync & Account Calendar Sync in 1 tap (uses WRITE_SYNC_SETTINGS). */
    public static void enableAllAutoSyncSettings(Context context) {
        try {
            if (!ContentResolver.getMasterSyncAutomatically()) {
                ContentResolver.setMasterSyncAutomatically(true);
            }
            for (Account acc : loadSyncedAccounts(context)) {
                if (ContentResolver.getIsSyncable(acc, CalendarContract.AUTHORITY) <= 0) {
                    ContentResolver.setIsSyncable(acc, CalendarContract.AUTHORITY, 1);
                }
                ContentResolver.setSyncAutomatically(acc, CalendarContract.AUTHORITY, true);
            }
        } catch (RuntimeException ignored) {
        }
    }

    public static boolean isPackageInstalled(Context context, String pkg) {
        try {
            context.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    public static void openAppBatterySettings(Activity activity, String pkg) {
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", pkg, null));
            activity.startActivity(i);
            Toast.makeText(activity,
                    "請點選「應用程式電池用量」→ 改為「無限制」，省電模式下也能 1 秒同步！",
                    Toast.LENGTH_LONG).show();
        } catch (ActivityNotFoundException ignored) {
        }
    }

    /**
     * Interactive Sync Health & 1-Second Optimization Sheet explaining Notion vs. Google sync
     * status in real time, listing any pending `DIRTY=1` rows, and providing 1-tap fixes.
     */
    public static void showSyncDiagnosticsDialog(Activity activity, AppTheme.Palette palette) {
        SYNC_EXEC.execute(() -> {
            final DirtyReport report = inspectDirtyQueue(activity);
            final boolean hasNotion = NotionManager.hasToken(activity);
            final boolean hasGCalApp = isPackageInstalled(activity, PKG_GOOGLE_CALENDAR);
            MAIN_HANDLER.post(() -> {
                if (activity.isFinishing()) return;
                renderDiagnosticsDialog(activity, palette, report, hasNotion, hasGCalApp);
            });
        });
    }

    private static void renderDiagnosticsDialog(
            Activity activity, AppTheme.Palette palette, DirtyReport report,
            boolean hasNotion, boolean hasGCalApp) {

        ScrollView scroll = new ScrollView(activity);
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        int p18 = dp(activity, 18);
        root.setPadding(p18, dp(activity, 16), p18, dp(activity, 12));
        root.setBackgroundColor(palette.cardElevated);
        scroll.addView(root);

        TextView title = new TextView(activity);
        title.setText("⚡ 雲端同步狀態 ＆ 1 秒極速同步診斷");
        title.setTextSize(17.5f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(palette.textPrimary);
        root.addView(title);

        // 1. Notion Pipeline Card
        addStatusCard(activity, root, palette,
                "📓 Notion 資料庫同步通道",
                hasNotion
                        ? "✅ HTTPS 直連雲端（0.3～0.8 秒即時寫入）\nKM 行事曆直接透過加密連線寫回 api.notion.com，不受 Android 省電模式限制。"
                        : "尚未連接 Notion（可於桌面小工具設定頁串接）",
                hasNotion ? 0xFF1E8E3E : palette.textSecondary);

        // 2. Google / System Calendar Pipeline Card
        String googleSummary;
        int googleAccent;
        if (report.totalDirty == 0) {
            googleSummary = "✅ 所有變更皆已 100% 同步至 Google 雲端（0 筆積壓）\n"
                    + "已啟用 Android 加急上傳通道（Expedited Job + Upload-Only + 解除大量刪除保護）。";
            googleAccent = 0xFF1E8E3E;
        } else {
            StringBuilder sb = new StringBuilder();
            sb.append("⏳ 目前有 ").append(report.totalDirty).append(" 筆本機變更等待 Google 背景上傳")
                    .append("（刪除 ").append(report.dirtyDeletions).append(" 筆 · 新增／修改 ")
                    .append(report.dirtyMutations).append(" 筆）：\n");
            for (DirtyItem item : report.items) {
                sb.append(" • ").append(item.isDeletion ? "[刪除] " : "[變更] ")
                        .append(item.title);
                if (!item.calendarName.isEmpty()) {
                    sb.append("（").append(item.calendarName).append("）");
                }
                sb.append('\n');
            }
            googleSummary = sb.toString().trim();
            googleAccent = 0xFFE65100;
        }
        addStatusCard(activity, root, palette, "📅 Google／系統行事曆同步通道", googleSummary, googleAccent);

        // 3. System Blockers & Why 1-Second Sync Can Stall Under Battery Saver
        StringBuilder diag = new StringBuilder();
        if (report.batterySaverOn) {
            diag.append("⚡ 偵測到手機正開啟「省電模式（Battery Saver）」！\n")
                    .append("因為 Google 日曆 App 使用獨立私有資料庫，必須靠系統背景 SyncAdapter 轉傳。")
                    .append("省電模式會凍結「Google 日曆」與「日曆儲存空間」的背景網路，導致你在 KM 刪除後 Google 日曆仍顯示舊快取。\n")
                    .append("👉 點擊下方按鈕將它們的電池用量設為「無限制」，即使開省電模式也能 1 秒同步！");
        } else if (report.masterSyncOff || !report.disabledSyncAccounts.isEmpty()) {
            diag.append("⚠️ 偵測到 Android「自動同步資料」或 Google 日曆帳號同步被關閉！\n")
                    .append("👉 點擊下方「一鍵開啟自動同步並強制上傳」即可立即恢復秒級同步。");
        } else {
            diag.append("💡 為什麼開啟省電模式時 Google 日曆偶爾慢幾秒？\n")
                    .append("Google 日曆 App 內部維護獨立快取（並非直接讀系統日曆庫）。建議將「Google 日曆」電池設為「無限制」，確保省電模式下背景推播也能在 1 秒內抵達！");
        }
        addStatusCard(activity, root, palette, "🔍 系統環境檢測", diag.toString(),
                (report.batterySaverOn || report.masterSyncOff || !report.disabledSyncAccounts.isEmpty())
                        ? 0xFFD93025 : palette.selectedStroke);

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setView(scroll)
                .setNegativeButton("關閉", null)
                .create();

        // Action Buttons
        if (report.masterSyncOff || !report.disabledSyncAccounts.isEmpty() || report.totalDirty > 0) {
            addActionButton(activity, root, "🚀 立即強制加急上傳所有變更（開啟自動同步）",
                    palette.todayText, palette.todayFill, () -> {
                        enableAllAutoSyncSettings(activity);
                        flushStuckDirtyQueueOnStartup(activity);
                        requestAllExpeditedPull(activity);
                        Toast.makeText(activity, "⚡ 已觸發最高優先級加急同步！", Toast.LENGTH_SHORT).show();
                        dialog.dismiss();
                    });
        } else {
            addActionButton(activity, root, "🔄 立即執行雙向極速同步（Google ＋ Notion）",
                    palette.todayText, palette.todayFill, () -> {
                        requestAllExpeditedPull(activity);
                        if (hasNotion) {
                            NotionManager.refreshEventsOnlyAsync(activity, 0L, null);
                        }
                        Toast.makeText(activity, "⚡ 已觸發雙向極速同步！", Toast.LENGTH_SHORT).show();
                        dialog.dismiss();
                    });
        }

        if (hasGCalApp) {
            addActionButton(activity, root, "⚡ 設定「Google 日曆」電池為「無限制」（省電模式不卡頓）",
                    palette.textPrimary, palette.chipBg, () -> openAppBatterySettings(activity, PKG_GOOGLE_CALENDAR));
        }
        addActionButton(activity, root, "⚙️ 設定「系統日曆儲存空間」電池為「無限制」",
                palette.textPrimary, palette.chipBg, () -> openAppBatterySettings(activity, PKG_CALENDAR_STORAGE));

        dialog.show();
    }

    private static void addStatusCard(
            Context ctx, LinearLayout parent, AppTheme.Palette palette,
            String heading, String body, int headingColor) {
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(ctx, 12);
        card.setPadding(pad, dp(ctx, 10), pad, dp(ctx, 10));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(ctx, 14));
        bg.setColor(palette.chipBg);
        card.setBackground(bg);

        TextView h = new TextView(ctx);
        h.setText(heading);
        h.setTextSize(13.5f);
        h.setTypeface(Typeface.DEFAULT_BOLD);
        h.setTextColor(headingColor);
        card.addView(h);

        TextView b = new TextView(ctx);
        b.setText(body);
        b.setTextSize(12.5f);
        b.setTextColor(palette.textPrimary);
        b.setLineSpacing(dp(ctx, 2), 1f);
        LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bLp.topMargin = dp(ctx, 4);
        card.addView(b, bLp);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(ctx, 10);
        parent.addView(card, lp);
    }

    private static void addActionButton(
            Context ctx, LinearLayout parent, String text, int textColor, int bgColor, Runnable onClick) {
        Button btn = new Button(ctx);
        btn.setText(text);
        btn.setAllCaps(false);
        btn.setTextSize(13f);
        btn.setTypeface(Typeface.DEFAULT_BOLD);
        btn.setTextColor(textColor);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(dp(ctx, 12), dp(ctx, 8), dp(ctx, 12), dp(ctx, 8));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(ctx, 18));
        bg.setColor(bgColor);
        btn.setBackground(bg);
        btn.setOnClickListener(v -> onClick.run());

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(ctx, 8);
        parent.addView(btn, lp);
    }

    private static int dp(Context ctx, int v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }
}
