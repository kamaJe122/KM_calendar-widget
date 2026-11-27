package io.github.kamaje122.calwidget;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.text.InputType;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import java.util.Collections;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import android.app.TimePickerDialog;
import android.content.ActivityNotFoundException;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.provider.CalendarContract;
import android.text.Editable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Rich dialogs & two-way editors for the KM Calendar main app:
 * 1) Day Events Overview Dialog (from Month grid tap)
 * 2) Event Detail Card (with in-app Edit & Delete for BOTH Google Calendar & Notion events)
 * 3) Unified Two-Way Event Editor (Create & Edit directly synced to Google Calendar or Notion API)
 * 4) Instant Live Search across Google + Notion events
 * 5) Quick Year/Month Jump Picker ("11月 ▾")
 */
public final class CalendarDialogs {

    public interface Callbacks {
        void onJumpToDateAndView(LocalDate date, int optionalViewMode);
        void onEventsMutated();
        void onRequestWritePermission();
    }

    private static final String[] DOW_NAMES = {
            "週一", "週二", "週三", "週四", "週五", "週六", "週日"
    };

    private static final class TargetDestination {
        final boolean isNotion;
        final CalendarDataRepository.SystemCalendarItem sysCal;
        final NotionManager.NotionSource notionSrc;
        final int color;
        final String displayLabel;

        TargetDestination(CalendarDataRepository.SystemCalendarItem sysCal) {
            this.isNotion = false;
            this.sysCal = sysCal;
            this.notionSrc = null;
            this.color = sysCal.color;
            this.displayLabel = "📅 " + sysCal.displayName
                    + (TextUtils.isEmpty(sysCal.accountName) ? "" : " (" + sysCal.accountName + ")");
        }

        TargetDestination(Context ctx, NotionManager.NotionSource src) {
            this.isNotion = true;
            this.sysCal = null;
            this.notionSrc = src;
            this.color = NotionManager.getSourceColor(ctx, src);
            this.displayLabel = "📓 Notion · " + src.fullTitleWithIcon();
        }
    }

    private CalendarDialogs() {
    }

    // =====================================================================================
    // 1) Floating single-day popup with zoomable 24h timeline + ✎ pencil on every event
    // =====================================================================================

    private static final ExecutorService DIALOG_IO = Executors.newSingleThreadExecutor();

    public static void showDayOverviewDialog(
            Activity activity,
            LocalDate date,
            List<CalendarEvent> dayEvents,
            AppTheme.Palette palette,
            Callbacks callbacks) {

        final LocalDate[] current = {date};
        final Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(roundedBg(palette.cardElevated, dp(activity, 24)));
        card.setClipToOutline(true);

        // ---- Header: ‹ date › · − + · ✕
        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(activity, 6), dp(activity, 12), dp(activity, 8), dp(activity, 2));

        TextView prevBtn = makeIconButton(activity, "‹", palette.textPrimary, 28f);
        TextView nextBtn = makeIconButton(activity, "›", palette.textPrimary, 28f);
        prevBtn.setContentDescription("前一天");
        nextBtn.setContentDescription("後一天");

        LinearLayout titleCol = new LinearLayout(activity);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        titleCol.setPadding(dp(activity, 2), 0, dp(activity, 2), 0);
        TextView titleTv = new TextView(activity);
        titleTv.setTextSize(19f);
        titleTv.setTypeface(Typeface.DEFAULT_BOLD);
        titleTv.setTextColor(palette.textPrimary);
        titleTv.setSingleLine(true);
        TextView lunarTv = new TextView(activity);
        lunarTv.setTextSize(12.5f);
        lunarTv.setTextColor(palette.textSecondary);
        lunarTv.setSingleLine(true);
        lunarTv.setEllipsize(TextUtils.TruncateAt.END);
        titleCol.addView(titleTv);
        titleCol.addView(lunarTv);

        TextView zoomFit = makeChipButton(activity, "00–24", palette.textSecondary, palette.chipBg, 11.5f);
        zoomFit.setPadding(dp(activity, 8), 0, dp(activity, 8), 0);
        zoomFit.setContentDescription("顯示完整 00–24 點");
        TextView zoomOut = makeChipButton(activity, "−", palette.textPrimary, palette.chipBg, 19f);
        TextView zoomIn = makeChipButton(activity, "＋", palette.textPrimary, palette.chipBg, 16.5f);
        zoomOut.setContentDescription("縮小時間軸");
        zoomIn.setContentDescription("放大時間軸");
        TextView closeBtn = makeIconButton(activity, "✕", palette.textSecondary, 19f);

        header.addView(prevBtn);
        header.addView(titleCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(nextBtn);
        LinearLayout.LayoutParams zFitLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(activity, 32));
        zFitLp.setMarginStart(dp(activity, 2));
        header.addView(zoomFit, zFitLp);
        LinearLayout.LayoutParams zLp = new LinearLayout.LayoutParams(dp(activity, 34), dp(activity, 32));
        zLp.setMarginStart(dp(activity, 4));
        header.addView(zoomOut, zLp);
        LinearLayout.LayoutParams zLp2 = new LinearLayout.LayoutParams(dp(activity, 34), dp(activity, 32));
        zLp2.setMarginStart(dp(activity, 4));
        header.addView(zoomIn, zLp2);
        header.addView(closeBtn);
        card.addView(header);

        TextView hint = new TextView(activity);
        hint.setTextSize(11.5f);
        hint.setTextColor(palette.textSecondary);
        hint.setPadding(dp(activity, 18), 0, dp(activity, 18), dp(activity, 6));
        card.addView(hint);

        // ---- Body: 1-column zoomable timeline
        MultiDayTimelineView timeline = new MultiDayTimelineView(activity);
        timeline.setFloatingMode(true);
        timeline.setShowPencilBadges(true);
        timeline.setCompactDayHeader(true);
        card.addView(timeline, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // ---- Bottom bar
        LinearLayout bottom = new LinearLayout(activity);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        bottom.setPadding(dp(activity, 14), dp(activity, 10), dp(activity, 14), dp(activity, 12));
        Button fullBtn = makeFlatButton(activity, "⤢ 全螢幕單日", palette.textPrimary, palette.chipBg);
        Button addBtn = makeFlatButton(activity, "＋ 新增行程", palette.todayText, palette.todayFill);
        bottom.addView(fullBtn);
        LinearLayout.LayoutParams addLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        addLp.setMarginStart(dp(activity, 8));
        bottom.addView(addBtn, addLp);
        card.addView(bottom);

        final int[] gen = {0};
        final Runnable[] reload = new Runnable[1];
        final java.util.function.Consumer<List<CalendarEvent>> applyData = evs -> {
            LocalDate d = current[0];
            timeline.setData(d, 1, palette, evs);
            boolean empty = true;
            for (CalendarEvent e : evs) {
                if (e.occursOn(d.toEpochDay())) {
                    empty = false;
                    break;
                }
            }
            hint.setText(empty
                    ? "這一天沒有行程 · 點任一時段即可新增"
                    : "雙指縮放時間軸 · 左右滑動切換日期 · 點 ✎ 編輯");
        };
        final Runnable applyHeader = () -> {
            LocalDate d = current[0];
            String t = String.format(Locale.TAIWAN, "%d月%d日 %s",
                    d.getMonthValue(), d.getDayOfMonth(), DOW_NAMES[d.getDayOfWeek().getValue() - 1]);
            if (d.getYear() != LocalDate.now().getYear()) t = d.getYear() + "年" + t;
            titleTv.setText(t);
            lunarTv.setText(LunarCalendarHelper.getFullDateLabel(d)
                    + (d.equals(LocalDate.now()) ? " · 今天" : ""));
        };

        final Context appCtx = activity.getApplicationContext();
        reload[0] = () -> {
            final LocalDate d = current[0];
            applyHeader.run();
            final int g = ++gen[0];
            final Set<String> hidden = AppPrefs.getAppHiddenCalendars(activity);
            DIALOG_IO.execute(() -> {
                List<CalendarEvent> evs = CalendarDataRepository.loadEventsInRange(appCtx, d, d, hidden);
                activity.runOnUiThread(() -> {
                    if (g != gen[0] || !dialog.isShowing() || activity.isFinishing()) return;
                    applyData.accept(evs);
                });
            });
        };

        final Callbacks inner = new Callbacks() {
            @Override
            public void onJumpToDateAndView(LocalDate d, int optionalViewMode) {
                dialog.dismiss();
                callbacks.onJumpToDateAndView(d, optionalViewMode);
            }

            @Override
            public void onEventsMutated() {
                callbacks.onEventsMutated();
                if (dialog.isShowing()) reload[0].run();
            }

            @Override
            public void onRequestWritePermission() {
                callbacks.onRequestWritePermission();
            }
        };

        timeline.setListener(new MultiDayTimelineView.Listener() {
            @Override
            public void onEventClicked(CalendarEvent event) {
                showEventEditorDialog(activity, event, current[0], -1, palette, inner);
            }

            @Override
            public void onEventEditClicked(CalendarEvent event) {
                showEventEditorDialog(activity, event, current[0], -1, palette, inner);
            }

            @Override
            public void onDayHeaderClicked(LocalDate d) {
            }

            @Override
            public void onEmptySlotClicked(LocalDate d, int hour) {
                showEventEditorDialog(activity, null, d, hour, palette, inner);
            }

            @Override
            public void onSwipePeriod(int deltaDays) {
                current[0] = current[0].plusDays(deltaDays > 0 ? 1 : -1);
                reload[0].run();
            }
        });

        prevBtn.setOnClickListener(v -> {
            current[0] = current[0].minusDays(1);
            reload[0].run();
        });
        nextBtn.setOnClickListener(v -> {
            current[0] = current[0].plusDays(1);
            reload[0].run();
        });
        titleCol.setOnClickListener(v -> new DatePickerDialog(activity, (dp, y, m, dd) -> {
            current[0] = LocalDate.of(y, m + 1, dd);
            reload[0].run();
        }, current[0].getYear(), current[0].getMonthValue() - 1, current[0].getDayOfMonth()).show());
        zoomFit.setOnClickListener(v -> timeline.resetToFullDay());
        zoomOut.setOnClickListener(v -> timeline.zoomBy(0.78f));
        zoomIn.setOnClickListener(v -> timeline.zoomBy(1.28f));
        closeBtn.setOnClickListener(v -> dialog.dismiss());
        fullBtn.setOnClickListener(v -> {
            dialog.dismiss();
            callbacks.onJumpToDateAndView(current[0], AppPrefs.VIEW_DAY_1);
        });
        addBtn.setOnClickListener(v -> showEventEditorDialog(activity, null, current[0], -1, palette, inner));

        applyHeader.run();
        applyData.accept(dayEvents != null ? dayEvents : new ArrayList<>());

        dialog.setContentView(card);
        dialog.show();
        Window w = dialog.getWindow();
        if (w != null) {
            DisplayMetrics dm = activity.getResources().getDisplayMetrics();
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(Math.round(dm.widthPixels * 0.94f), Math.round(dm.heightPixels * 0.86f));
            w.setGravity(Gravity.CENTER);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setDimAmount(0.5f);
        }
    }

    // =====================================================================================
    // 2) Unified Google-Calendar-style floating event sheet (view + edit + create)
    // =====================================================================================

    public static void showEventDetailDialog(
            Activity activity,
            CalendarEvent ev,
            AppTheme.Palette palette,
            Callbacks callbacks) {
        showEventEditorDialog(activity, ev,
                ev != null ? LocalDate.ofEpochDay(ev.startEpochDay) : LocalDate.now(), -1, palette, callbacks);
    }

    public static void showCreateEventDialog(
            Activity activity,
            LocalDate initialDate,
            int initialHourOrNeg,
            AppTheme.Palette palette,
            Callbacks callbacks) {
        showEventEditorDialog(activity, null, initialDate, initialHourOrNeg, palette, callbacks);
    }

    /**
     * One floating sheet for viewing, editing and creating events — for BOTH Google/system
     * calendars (incl. reminders + 3-scope recurring edits) and Notion databases.
     */
    public static void showEventEditorDialog(
            Activity activity,
            CalendarEvent existingEvent,
            LocalDate fallbackDate,
            int initialHourOrNeg,
            AppTheme.Palette palette,
            Callbacks callbacks) {

        final boolean isEdit = existingEvent != null;
        final boolean editingNotion = isEdit && existingEvent.isNotion();
        final boolean editingSystem = isEdit && !editingNotion && existingEvent.eventId >= 0L;
        final ZoneId zone = ZoneId.systemDefault();

        final CalendarDataRepository.SystemEventMeta sysMeta = editingSystem
                ? CalendarDataRepository.inspectSystemEvent(activity, existingEvent.eventId)
                : null;

        final boolean readOnly;
        final String readOnlyReason;
        if (editingNotion && TextUtils.isEmpty(existingEvent.notionPageId)) {
            readOnly = true;
            readOnlyReason = "🔒 此 Notion 項目缺少頁面 ID，僅供檢視";
        } else if (isEdit && !editingNotion && !editingSystem) {
            readOnly = true;
            readOnlyReason = "🔒 此行程來源不支援編輯，僅供檢視";
        } else if (editingSystem && (sysMeta == null || !sysMeta.writable)) {
            readOnly = true;
            readOnlyReason = "🔒「" + existingEvent.calendarName + "」為唯讀（訂閱／節日）日曆，僅供檢視";
        } else {
            readOnly = false;
            readOnlyReason = "";
        }
        final boolean isRecurring = sysMeta != null && sysMeta.recurring;

        // ---- Destinations (writable Google calendars + writable Notion databases)
        final List<TargetDestination> destinations = new ArrayList<>();
        if (!readOnly && (!isEdit || (editingSystem && !isRecurring))) {
            for (CalendarDataRepository.SystemCalendarItem c : CalendarDataRepository.loadSystemCalendars(activity)) {
                if (c.writable) destinations.add(new TargetDestination(c));
            }
        }
        if (!isEdit) {
            for (NotionManager.NotionSource ns : NotionManager.getWritableNotionSources(activity)) {
                destinations.add(new TargetDestination(activity, ns));
            }
        }
        final boolean showDestSpinner = !destinations.isEmpty() && (!isEdit || destinations.size() > 1);

        // ---- Date / time state
        final LocalDate[] startDate = new LocalDate[1];
        final LocalDate[] endDate = new LocalDate[1];
        final LocalTime[] startTime = new LocalTime[1];
        final LocalTime[] endTime = new LocalTime[1];
        final boolean[] allDay = new boolean[1];

        if (isEdit) {
            allDay[0] = existingEvent.allDay;
            startDate[0] = LocalDate.ofEpochDay(existingEvent.startEpochDay);
            endDate[0] = LocalDate.ofEpochDay(Math.max(existingEvent.startEpochDay, existingEvent.endEpochDay));
            if (existingEvent.allDay || existingEvent.beginMillis <= 0L) {
                startTime[0] = LocalTime.of(9, 0);
                endTime[0] = LocalTime.of(10, 0);
            } else {
                ZonedDateTime bZdt = Instant.ofEpochMilli(existingEvent.beginMillis).atZone(zone);
                long rawEnd = (existingEvent.hasExplicitEnd && existingEvent.endMillis > existingEvent.beginMillis)
                        ? existingEvent.endMillis : (existingEvent.beginMillis + 3_600_000L);
                ZonedDateTime eZdt = Instant.ofEpochMilli(rawEnd).atZone(zone);
                startDate[0] = bZdt.toLocalDate();
                endDate[0] = eZdt.toLocalDate();
                startTime[0] = bZdt.toLocalTime().withSecond(0).withNano(0);
                endTime[0] = eZdt.toLocalTime().withSecond(0).withNano(0);
            }
        } else {
            allDay[0] = false;
            int defHour = initialHourOrNeg >= 0
                    ? initialHourOrNeg
                    : (fallbackDate.equals(LocalDate.now())
                    ? Math.min(22, LocalTime.now().getHour() + 1) : 9);
            startDate[0] = fallbackDate;
            endDate[0] = fallbackDate;
            startTime[0] = LocalTime.of(defHour, 0);
            endTime[0] = defHour >= 23 ? LocalTime.of(23, 59) : LocalTime.of(defHour + 1, 0);
        }

        final boolean[] targetNotion = {editingNotion
                || (!isEdit && !destinations.isEmpty() && destinations.get(0).isNotion)};

        // ---- Per-event custom color state (Google Colors table + Notion custom override)
        final int initialBaseCalColor;
        if (editingNotion) {
            initialBaseCalColor = NotionManager.getSourceColorByKey(
                    activity, existingEvent.sourceKey, existingEvent.color);
        } else if (editingSystem && sysMeta != null) {
            initialBaseCalColor = sysMeta.calendarColor;
        } else if (!destinations.isEmpty()) {
            initialBaseCalColor = destinations.get(0).color;
        } else {
            initialBaseCalColor = isEdit ? existingEvent.color : palette.todayFill;
        }
        final int[] baseCalColor = {initialBaseCalColor};
        final boolean[] customColorEnabled = {false};
        final String[] chosenColorKey = {""};
        final int[] chosenCustomColor = {initialBaseCalColor};
        final String[] chosenColorName = {""};
        final List<CalendarDataRepository.EventColorOption> colorPalette = new ArrayList<>();

        if (editingSystem && sysMeta != null && sysMeta.hasCustomColor) {
            customColorEnabled[0] = true;
            chosenColorKey[0] = sysMeta.eventColorKey;
            chosenCustomColor[0] = sysMeta.eventColor != 0 ? sysMeta.eventColor : existingEvent.color;
        } else if (editingNotion && AppPrefs.hasCustomNotionEventColor(activity, existingEvent.notionPageId)) {
            customColorEnabled[0] = true;
            chosenColorKey[0] = "";
            chosenCustomColor[0] = AppPrefs.getCustomNotionEventColor(
                    activity, existingEvent.notionPageId, existingEvent.color);
        }

        final boolean[] userTouchedReminders = {false};
        final List<Integer> reminders = new ArrayList<>();
        final List<Integer> originalReminders = new ArrayList<>();
        if (editingSystem) {
            reminders.addAll(CalendarDataRepository.loadEventReminders(activity, existingEvent.eventId));
            originalReminders.addAll(reminders);
        } else if (editingNotion) {
            reminders.addAll(AppPrefs.getEffectiveNotionReminders(activity, existingEvent));
            originalReminders.addAll(reminders);
        } else {
            int defMin = targetNotion[0] ? AppPrefs.getNotionDefaultReminderMinutes(activity) : 10;
            if (defMin >= 0) reminders.add(defMin);
        }

        // ================= Floating card skeleton =================
        final Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        final MaxHeightLinearLayout card = new MaxHeightLinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(roundedBg(palette.cardElevated, dp(activity, 26)));
        card.setClipToOutline(true);
        card.maxHeightPx = Math.round(activity.getResources().getDisplayMetrics().heightPixels * 0.9f);

        // Drag handle (swipe down to dismiss)
        FrameLayout handleArea = new FrameLayout(activity);
        View handle = new View(activity);
        handle.setBackground(roundedBg(palette.textDim, dp(activity, 3)));
        FrameLayout.LayoutParams hLp = new FrameLayout.LayoutParams(dp(activity, 36), dp(activity, 4), Gravity.CENTER);
        handleArea.addView(handle, hLp);
        card.addView(handleArea, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 20)));
        attachSwipeToDismiss(handleArea, card, dialog);

        // Top bar: ✕ · source chip · ↗ · 🗑 · 儲存
        LinearLayout topBar = new LinearLayout(activity);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(activity, 8), 0, dp(activity, 14), dp(activity, 4));
        attachSwipeToDismiss(topBar, card, dialog);

        TextView closeBtn = makeIconButton(activity, "✕", palette.textPrimary, 20f);
        closeBtn.setContentDescription("關閉");
        topBar.addView(closeBtn);

        TextView sourceChip = makeChipButton(activity, "", palette.textPrimary, palette.chipBg, 13f);
        sourceChip.setPadding(dp(activity, 12), 0, dp(activity, 12), 0);
        LinearLayout.LayoutParams scLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(activity, 32));
        scLp.setMarginStart(dp(activity, 4));
        topBar.addView(sourceChip, scLp);

        View spacer = new View(activity);
        topBar.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

        TextView openExtBtn = makeIconButton(activity, "↗", palette.textPrimary, 20f);
        openExtBtn.setContentDescription(editingNotion ? "在 Notion 開啟" : "用 Google 日曆開啟");
        TextView deleteBtn = makeIconButton(activity, "🗑", palette.textPrimary, 18f);
        deleteBtn.setContentDescription("刪除");
        if (isEdit && (editingNotion ? !TextUtils.isEmpty(existingEvent.notionUrl) : editingSystem)) {
            topBar.addView(openExtBtn);
        }
        if (isEdit && !readOnly) {
            topBar.addView(deleteBtn);
        }

        Button saveBtn = makeFlatButton(activity, "儲存", palette.todayText, palette.todayFill);
        saveBtn.setPadding(dp(activity, 20), dp(activity, 6), dp(activity, 20), dp(activity, 6));
        LinearLayout.LayoutParams svLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        svLp.setMarginStart(dp(activity, 6));
        if (!readOnly) topBar.addView(saveBtn, svLp);
        card.addView(topBar);

        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(false);
        LinearLayout body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(activity, 6), 0, dp(activity, 18), dp(activity, 18));
        scroll.addView(body);
        card.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        if (readOnly) {
            TextView ro = new TextView(activity);
            ro.setText(readOnlyReason);
            ro.setTextSize(12.5f);
            ro.setTextColor(palette.textSecondary);
            ro.setBackground(roundedBg(palette.chipBg, dp(activity, 10)));
            ro.setPadding(dp(activity, 12), dp(activity, 8), dp(activity, 12), dp(activity, 8));
            LinearLayout.LayoutParams roLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            roLp.setMargins(dp(activity, 12), dp(activity, 2), 0, dp(activity, 6));
            body.addView(ro, roLp);
        }

        // ---- Title (large, borderless) with tappable colour square like Google Calendar
        LinearLayout titleRow = new LinearLayout(activity);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        View colorSquare = new View(activity);
        int initialColor = customColorEnabled[0] ? chosenCustomColor[0] : initialBaseCalColor;
        colorSquare.setBackground(roundedBg(initialColor, dp(activity, 6)));
        colorSquare.setContentDescription("點選設定活動顏色");
        LinearLayout.LayoutParams csLp = new LinearLayout.LayoutParams(dp(activity, 20), dp(activity, 20));
        csLp.setMarginStart(dp(activity, 12));
        csLp.setMarginEnd(dp(activity, 12));
        titleRow.addView(colorSquare, csLp);

        final boolean[] userEditedTitle = {false};
        EditText titleInput = new EditText(activity);
        titleInput.setHint("新增標題");
        titleInput.setTextSize(24f);
        titleInput.setTextColor(palette.textPrimary);
        titleInput.setHintTextColor(palette.textDim);
        titleInput.setBackground(null);
        titleInput.setPadding(0, dp(activity, 8), 0, dp(activity, 8));
        titleInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        titleInput.setMaxLines(3);
        if (isEdit) {
            titleInput.setText(existingEvent.title);
        }
        titleInput.addTextChangedListener(new SimpleWatcher(() -> userEditedTitle[0] = true));
        titleRow.addView(titleInput, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        body.addView(titleRow);

        // ---- Time row: 🕒 09:00 → 18:00      9 小時
        LinearLayout timeContent = new LinearLayout(activity);
        timeContent.setOrientation(LinearLayout.HORIZONTAL);
        timeContent.setGravity(Gravity.CENTER_VERTICAL);
        TextView startTimeTv = makeValueText(activity, palette, 21f);
        TextView arrowTv = new TextView(activity);
        arrowTv.setText("  →  ");
        arrowTv.setTextSize(17f);
        arrowTv.setTextColor(palette.textSecondary);
        TextView endTimeTv = makeValueText(activity, palette, 21f);
        TextView durationTv = new TextView(activity);
        durationTv.setTextSize(13.5f);
        durationTv.setTextColor(palette.textSecondary);
        durationTv.setGravity(Gravity.END);
        timeContent.addView(startTimeTv);
        timeContent.addView(arrowTv);
        timeContent.addView(endTimeTv);
        timeContent.addView(durationTv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout timeRow = makeSheetRow(activity, palette, "🕒", timeContent);
        body.addView(timeRow);

        // ---- Date row: 📅 11月28日 週六 → 11月28日 週六 (+ lunar)
        LinearLayout dateCol = new LinearLayout(activity);
        dateCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout dateLine = new LinearLayout(activity);
        dateLine.setOrientation(LinearLayout.HORIZONTAL);
        dateLine.setGravity(Gravity.CENTER_VERTICAL);
        TextView startDateTv = makeValueText(activity, palette, 16f);
        TextView dateArrow = new TextView(activity);
        dateArrow.setText("  →  ");
        dateArrow.setTextSize(14f);
        dateArrow.setTextColor(palette.textSecondary);
        TextView endDateTv = makeValueText(activity, palette, 16f);
        dateLine.addView(startDateTv);
        dateLine.addView(dateArrow);
        dateLine.addView(endDateTv);
        TextView lunarDateTv = new TextView(activity);
        lunarDateTv.setTextSize(12.5f);
        lunarDateTv.setTextColor(palette.textSecondary);
        dateCol.addView(dateLine);
        dateCol.addView(lunarDateTv);
        body.addView(makeSheetRow(activity, palette, "📅", dateCol));

        // ---- Chips: 全天 · 時區 · 重複
        LinearLayout chipRow = new LinearLayout(activity);
        chipRow.setOrientation(LinearLayout.HORIZONTAL);
        chipRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView allDayChip = makeChipButton(activity, "", palette.textPrimary, palette.chipBg, 13f);
        allDayChip.setPadding(dp(activity, 12), 0, dp(activity, 12), 0);
        chipRow.addView(allDayChip, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(activity, 32)));
        TextView tzChip = makeChipButton(activity,
                "🌐 " + shortTzName(),
                palette.textSecondary, palette.chipBg, 12.5f);
        tzChip.setPadding(dp(activity, 12), 0, dp(activity, 12), 0);
        LinearLayout.LayoutParams tzLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(activity, 32));
        tzLp.setMarginStart(dp(activity, 8));
        chipRow.addView(tzChip, tzLp);
        if (isRecurring) {
            TextView repChip = makeChipButton(activity, "🔁 " + sysMeta.recurrenceLabel(),
                    palette.textSecondary, palette.chipBg, 12.5f);
            repChip.setPadding(dp(activity, 12), 0, dp(activity, 12), 0);
            LinearLayout.LayoutParams rpLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(activity, 32));
            rpLp.setMarginStart(dp(activity, 8));
            chipRow.addView(repChip, rpLp);
        }
        HorizontalScrollView chipScroll = new HorizontalScrollView(activity);
        chipScroll.setHorizontalScrollBarEnabled(false);
        chipScroll.addView(chipRow);
        body.addView(makeSheetRow(activity, palette, "", chipScroll));

        TextView computedDateHint = new TextView(activity);
        computedDateHint.setText("⚠️ 此 Notion 檢視的日期來自公式／關聯欄位（僅可修改標題、狀態與備註）");
        computedDateHint.setTextSize(12f);
        computedDateHint.setTextColor(0xFFE65100);
        computedDateHint.setPadding(dp(activity, 56), dp(activity, 2), 0, dp(activity, 4));
        computedDateHint.setVisibility(View.GONE);
        body.addView(computedDateHint);

        body.addView(makeDivider(activity, palette));

        // ---- 📍 Location
        LinearLayout locContent = new LinearLayout(activity);
        locContent.setOrientation(LinearLayout.HORIZONTAL);
        locContent.setGravity(Gravity.CENTER_VERTICAL);
        EditText locInput = makeBorderlessInput(activity, palette, "新增位置");
        if (isEdit && !editingNotion) locInput.setText(existingEvent.location);
        TextView mapBtn = makeChipButton(activity, "地圖", palette.selectedStroke, palette.chipBg, 12.5f);
        mapBtn.setPadding(dp(activity, 12), 0, dp(activity, 12), 0);
        locContent.addView(locInput, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        locContent.addView(mapBtn, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(activity, 30)));
        Runnable refreshMapBtn = () -> mapBtn.setVisibility(
                locInput.getText().toString().trim().isEmpty() ? View.GONE : View.VISIBLE);
        locInput.addTextChangedListener(new SimpleWatcher(refreshMapBtn));
        refreshMapBtn.run();
        mapBtn.setOnClickListener(v -> {
            try {
                Uri geo = Uri.parse("geo:0,0?q=" + Uri.encode(locInput.getText().toString().trim()));
                activity.startActivity(new Intent(Intent.ACTION_VIEW, geo));
            } catch (ActivityNotFoundException ignored) {
            }
        });
        LinearLayout locRow = makeSheetRow(activity, palette, "📍", locContent);
        body.addView(locRow);

        // ---- 📝 Description / Notion note
        EditText descInput = makeBorderlessInput(activity, palette, "新增說明");
        descInput.setSingleLine(false);
        descInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        descInput.setMinLines(1);
        descInput.setMaxLines(8);
        if (isEdit && !editingNotion) descInput.setText(existingEvent.description);
        body.addView(makeSheetRow(activity, palette, "📝", descInput));

        // ---- 🏷️ Notion status (shown when schema has status/select)
        LinearLayout statusCol = new LinearLayout(activity);
        statusCol.setOrientation(LinearLayout.VERTICAL);
        TextView statusLabel = new TextView(activity);
        statusLabel.setText("Notion 狀態");
        statusLabel.setTextSize(12.5f);
        statusLabel.setTextColor(palette.textSecondary);
        Spinner statusSpinner = new Spinner(activity);
        statusCol.addView(statusLabel);
        statusCol.addView(statusSpinner);
        LinearLayout statusRow = makeSheetRow(activity, palette, "🏷️", statusCol);
        statusRow.setVisibility(View.GONE);
        body.addView(statusRow);

        // ---- 🔗 Open Notion page
        if (editingNotion && !TextUtils.isEmpty(existingEvent.notionUrl)) {
            TextView linkTv = new TextView(activity);
            linkTv.setText("在 Notion 開啟完整頁面");
            linkTv.setTextSize(15f);
            linkTv.setTextColor(palette.selectedStroke);
            linkTv.setPadding(0, dp(activity, 10), 0, dp(activity, 10));
            linkTv.setOnClickListener(v -> openExternal(activity, existingEvent));
            body.addView(makeSheetRow(activity, palette, "🔗", linkTv));
        }

        body.addView(makeDivider(activity, palette));

        // ---- Calendar / database row
        LinearLayout calContent = new LinearLayout(activity);
        calContent.setOrientation(LinearLayout.VERTICAL);
        TextView calNameTv = new TextView(activity);
        calNameTv.setTextSize(15f);
        calNameTv.setTextColor(palette.textPrimary);
        TextView calSubTv = new TextView(activity);
        calSubTv.setTextSize(12.5f);
        calSubTv.setTextColor(palette.textSecondary);
        Spinner destSpinner = new Spinner(activity);
        if (showDestSpinner) {
            CharSequence[] labels = new CharSequence[destinations.size()];
            int preselect = 0;
            for (int i = 0; i < destinations.size(); i++) {
                TargetDestination td = destinations.get(i);
                SpannableString sp = new SpannableString("● " + td.displayLabel);
                sp.setSpan(new ForegroundColorSpan(td.color), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                labels[i] = sp;
                if (isEdit && !td.isNotion && sysMeta != null && td.sysCal.id == sysMeta.calendarId) {
                    preselect = i;
                }
            }
            ArrayAdapter<CharSequence> spinAdapter = new ArrayAdapter<>(
                    activity, android.R.layout.simple_spinner_dropdown_item, labels);
            destSpinner.setAdapter(spinAdapter);
            destSpinner.setSelection(preselect);
            calContent.addView(destSpinner);
        } else {
            String calName;
            if (isEdit) {
                calName = !TextUtils.isEmpty(existingEvent.calendarName) ? existingEvent.calendarName
                        : (editingNotion ? "Notion 日曆" : "系統行事曆");
                if (!TextUtils.isEmpty(existingEvent.accountName) && !existingEvent.accountName.equals(calName)) {
                    calName = calName + "（" + existingEvent.accountName + "）";
                }
            } else {
                calName = "找不到可寫入的日曆（將改用系統日曆 App 新增）";
            }
            calNameTv.setText(calName);
            calContent.addView(calNameTv);
        }
        calContent.addView(calSubTv);
        View calDot = new View(activity);
        calDot.setBackground(roundedBg(initialBaseCalColor, dp(activity, 7)));
        LinearLayout calRow = makeSheetRowWithIconView(activity, calDot, calContent);
        body.addView(calRow);

        // ---- 🎨 Per-Event Color Picker Row (Google Calendar 11 Event Colors + Notion per-event color)
        LinearLayout colorCol = new LinearLayout(activity);
        colorCol.setOrientation(LinearLayout.VERTICAL);
        colorCol.setPadding(0, dp(activity, 2), 0, dp(activity, 4));

        TextView colorLabelTv = new TextView(activity);
        colorLabelTv.setTextSize(14.5f);
        colorLabelTv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        colorLabelTv.setTextColor(palette.textPrimary);
        colorLabelTv.setPadding(0, dp(activity, 4), 0, dp(activity, 6));
        colorCol.addView(colorLabelTv);

        LinearLayout swatchStrip = new LinearLayout(activity);
        swatchStrip.setOrientation(LinearLayout.HORIZONTAL);
        swatchStrip.setGravity(Gravity.CENTER_VERTICAL);
        swatchStrip.setPadding(0, dp(activity, 2), dp(activity, 6), dp(activity, 4));

        HorizontalScrollView swatchScroll = new HorizontalScrollView(activity);
        swatchScroll.setHorizontalScrollBarEnabled(false);
        swatchScroll.addView(swatchStrip);
        if (!readOnly) {
            colorCol.addView(swatchScroll);
        }

        View eventColorDot = new View(activity);
        eventColorDot.setBackground(roundedBg(initialColor, dp(activity, 7)));
        LinearLayout colorRow = makeSheetRowWithIconView(activity, eventColorDot, colorCol);
        if (readOnly && !customColorEnabled[0]) {
            colorRow.setVisibility(View.GONE);
        }
        body.addView(colorRow);

        final Runnable[] renderColorPicker = new Runnable[1];
        final Runnable openColorListDialog = () -> {
            if (readOnly) return;
            String defLabel = targetNotion[0] ? "預設顏色（跟隨資料庫顏色）" : "預設顏色（跟隨所屬日曆）";
            CharSequence[] items = new CharSequence[colorPalette.size() + 1];
            SpannableString defSpan = new SpannableString("●  " + defLabel);
            defSpan.setSpan(new ForegroundColorSpan(baseCalColor[0]), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            items[0] = defSpan;

            int checkedIdx = 0;
            for (int i = 0; i < colorPalette.size(); i++) {
                CalendarDataRepository.EventColorOption opt = colorPalette.get(i);
                SpannableString sp = new SpannableString("●  " + opt.name);
                sp.setSpan(new ForegroundColorSpan(opt.color), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                items[i + 1] = sp;
                if (customColorEnabled[0]) {
                    boolean match = (!TextUtils.isEmpty(chosenColorKey[0])
                            && chosenColorKey[0].equals(opt.colorKey))
                            || (opt.color == chosenCustomColor[0]);
                    if (match && checkedIdx == 0) checkedIdx = i + 1;
                }
            }

            new AlertDialog.Builder(activity)
                    .setTitle("選擇此行程專屬顏色")
                    .setSingleChoiceItems(items, checkedIdx, (d, which) -> {
                        if (which == 0) {
                            customColorEnabled[0] = false;
                            chosenColorKey[0] = "";
                            chosenColorName[0] = "";
                        } else {
                            CalendarDataRepository.EventColorOption picked = colorPalette.get(which - 1);
                            customColorEnabled[0] = true;
                            chosenColorKey[0] = picked.colorKey;
                            chosenCustomColor[0] = picked.color;
                            chosenColorName[0] = picked.name;
                        }
                        renderColorPicker[0].run();
                        d.dismiss();
                    })
                    .setNegativeButton("取消", null)
                    .show();
        };

        if (!readOnly) {
            applyRipple(colorLabelTv, false);
            colorLabelTv.setOnClickListener(v -> openColorListDialog.run());
            colorSquare.setClickable(true);
            colorSquare.setFocusable(true);
            colorSquare.setOnClickListener(v -> openColorListDialog.run());
        }

        renderColorPicker[0] = () -> {
            int effective = customColorEnabled[0] ? chosenCustomColor[0] : baseCalColor[0];
            colorSquare.setBackground(roundedBg(effective, dp(activity, 6)));
            eventColorDot.setBackground(roundedBg(effective, dp(activity, 7)));
            calDot.setBackground(roundedBg(baseCalColor[0], dp(activity, 7)));

            String defShort = targetNotion[0] ? "跟隨資料庫預設" : "跟隨日曆預設";
            if (!customColorEnabled[0]) {
                colorLabelTv.setText("活動顏色：預設（" + defShort + "）" + (readOnly ? "" : " ▾"));
            } else {
                String cName = !TextUtils.isEmpty(chosenColorName[0])
                        ? chosenColorName[0]
                        : CalendarDataRepository.nearestColorName(chosenCustomColor[0]);
                colorLabelTv.setText("活動顏色：" + cName + (readOnly ? "" : " ▾"));
            }

            if (readOnly) return;
            swatchStrip.removeAllViews();
            int size = dp(activity, 34);
            int gap = dp(activity, 8);
            int subtleBorder = palette.isDark ? 0x33FFFFFF : 0x26000000;

            // Swatch 0: Default (follow calendar / Notion database color)
            TextView defCircle = new TextView(activity);
            defCircle.setGravity(Gravity.CENTER);
            defCircle.setTypeface(Typeface.DEFAULT_BOLD);
            boolean defSelected = !customColorEnabled[0];
            defCircle.setText(defSelected ? "✓" : "預");
            defCircle.setTextSize(defSelected ? 15f : 11f);
            defCircle.setTextColor(AppTheme.textColorOnPill(baseCalColor[0]));
            GradientDrawable defBg = new GradientDrawable();
            defBg.setShape(GradientDrawable.OVAL);
            defBg.setColor(baseCalColor[0]);
            defBg.setStroke(dp(activity, defSelected ? 3 : 1),
                    defSelected ? palette.textPrimary : subtleBorder);
            defCircle.setBackground(defBg);
            defCircle.setContentDescription("預設顏色（" + defShort + "）");
            defCircle.setOnClickListener(v -> {
                customColorEnabled[0] = false;
                chosenColorKey[0] = "";
                chosenColorName[0] = "";
                renderColorPicker[0].run();
            });
            LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(size, size);
            dLp.setMarginEnd(gap);
            swatchStrip.addView(defCircle, dLp);

            // Swatches 1..N: Individual event colors
            for (CalendarDataRepository.EventColorOption opt : colorPalette) {
                boolean selected = customColorEnabled[0] && (
                        (!TextUtils.isEmpty(chosenColorKey[0]) && chosenColorKey[0].equals(opt.colorKey))
                                || (TextUtils.isEmpty(chosenColorKey[0]) && opt.color == chosenCustomColor[0]));
                TextView sw = new TextView(activity);
                sw.setGravity(Gravity.CENTER);
                sw.setTypeface(Typeface.DEFAULT_BOLD);
                sw.setText(selected ? "✓" : "");
                sw.setTextSize(15f);
                sw.setTextColor(AppTheme.textColorOnPill(opt.color));
                GradientDrawable bg = new GradientDrawable();
                bg.setShape(GradientDrawable.OVAL);
                bg.setColor(opt.color);
                bg.setStroke(dp(activity, selected ? 3 : 1),
                        selected ? palette.textPrimary : subtleBorder);
                sw.setBackground(bg);
                sw.setContentDescription(opt.name);
                sw.setOnClickListener(v -> {
                    customColorEnabled[0] = true;
                    chosenColorKey[0] = opt.colorKey;
                    chosenCustomColor[0] = opt.color;
                    chosenColorName[0] = opt.name;
                    renderColorPicker[0].run();
                });
                LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(size, size);
                sLp.setMarginEnd(gap);
                swatchStrip.addView(sw, sLp);
            }
        };

        // ---- 🔔 Reminders (Google / system + Notion)
        LinearLayout reminderSection = new LinearLayout(activity);
        reminderSection.setOrientation(LinearLayout.VERTICAL);
        View reminderDivider = makeDivider(activity, palette);
        body.addView(reminderDivider);
        LinearLayout reminderList = new LinearLayout(activity);
        reminderList.setOrientation(LinearLayout.VERTICAL);
        reminderSection.addView(reminderList);
        LinearLayout reminderRow = makeSheetRow(activity, palette, "🔔", reminderSection);
        body.addView(reminderRow);

        final Runnable[] renderReminders = new Runnable[1];
        renderReminders[0] = () -> {
            reminderList.removeAllViews();
            Collections.sort(reminders);
            for (Integer m : new ArrayList<>(reminders)) {
                LinearLayout r = new LinearLayout(activity);
                r.setOrientation(LinearLayout.HORIZONTAL);
                r.setGravity(Gravity.CENTER_VERTICAL);
                TextView label = new TextView(activity);
                label.setText(formatReminder(m));
                label.setTextSize(15f);
                label.setTextColor(palette.textPrimary);
                label.setPadding(0, dp(activity, 9), 0, dp(activity, 9));
                r.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                if (!readOnly) {
                    TextView x = makeIconButton(activity, "✕", palette.textSecondary, 15f);
                    x.setOnClickListener(v -> {
                        userTouchedReminders[0] = true;
                        reminders.remove(m);
                        renderReminders[0].run();
                    });
                    r.addView(x, new LinearLayout.LayoutParams(dp(activity, 36), dp(activity, 36)));
                }
                reminderList.addView(r);
            }
            if (!readOnly) {
                TextView add = new TextView(activity);
                add.setText(reminders.isEmpty() ? "新增提醒" : "＋ 新增提醒");
                add.setTextSize(15f);
                add.setTextColor(reminders.isEmpty() ? palette.textSecondary : palette.selectedStroke);
                add.setPadding(0, dp(activity, 9), 0, dp(activity, 9));
                add.setOnClickListener(v -> {
                    final int[] opts = {0, 5, 10, 15, 30, 60, 120, 1440, 2880, 10080};
                    String[] labels = new String[opts.length];
                    for (int i = 0; i < opts.length; i++) labels[i] = formatReminder(opts[i]);
                    new AlertDialog.Builder(activity)
                            .setTitle("新增提醒")
                            .setItems(labels, (d, which) -> {
                                userTouchedReminders[0] = true;
                                if (!reminders.contains(opts[which])) reminders.add(opts[which]);
                                renderReminders[0].run();
                            })
                            .show();
                });
                reminderList.addView(add);
            } else if (reminders.isEmpty()) {
                TextView none = new TextView(activity);
                none.setText("沒有提醒");
                none.setTextSize(15f);
                none.setTextColor(palette.textSecondary);
                none.setPadding(0, dp(activity, 9), 0, dp(activity, 9));
                reminderList.addView(none);
            }
        };
        renderReminders[0].run();

        // ================= Dynamic refreshers =================
        final Runnable refreshTime = () -> {
            boolean ad = allDay[0];
            startTimeTv.setText(String.format(Locale.US, "%02d:%02d", startTime[0].getHour(), startTime[0].getMinute()));
            endTimeTv.setText(String.format(Locale.US, "%02d:%02d", endTime[0].getHour(), endTime[0].getMinute()));
            startDateTv.setText(formatSheetDate(startDate[0]));
            boolean sameDay = endDate[0].equals(startDate[0]);
            endDateTv.setText(sameDay ? "當天" : formatSheetDate(endDate[0]));
            endDateTv.setTextColor(sameDay ? palette.textSecondary : palette.textPrimary);
            lunarDateTv.setText(LunarCalendarHelper.getFullDateLabel(startDate[0]));
            timeRow.setVisibility(ad ? View.GONE : View.VISIBLE);
            if (ad) {
                long days = endDate[0].toEpochDay() - startDate[0].toEpochDay() + 1;
                durationTv.setText(days + " 天");
            } else {
                long mins = java.time.Duration.between(
                        startDate[0].atTime(startTime[0]), endDate[0].atTime(endTime[0])).toMinutes();
                durationTv.setText(formatDuration(mins));
            }
            allDayChip.setText(ad ? "✓ 全天" : "全天");
            allDayChip.setTextColor(ad ? palette.todayText : palette.textPrimary);
            allDayChip.setBackground(roundedBg(ad ? palette.todayFill : palette.chipBg, dp(activity, 16)));
        };

        final Runnable applyTargetMode = () -> {
            boolean notion = targetNotion[0];
            TargetDestination td = (showDestSpinner && !destinations.isEmpty())
                    ? destinations.get(Math.max(0, destSpinner.getSelectedItemPosition())) : null;
            baseCalColor[0] = td != null ? td.color : initialBaseCalColor;

            String accName = "";
            String acctType = "";
            if (!notion) {
                if (td != null && td.sysCal != null) {
                    accName = td.sysCal.accountName;
                    acctType = td.sysCal.accountType;
                } else if (sysMeta != null) {
                    accName = sysMeta.accountName;
                    acctType = sysMeta.accountType;
                }
            }
            colorPalette.clear();
            colorPalette.addAll(CalendarDataRepository.loadEventColorOptions(activity, accName, acctType));

            // Re-align selected custom color against active destination's colorPalette so colorKey is valid
            if (customColorEnabled[0] && !colorPalette.isEmpty()) {
                CalendarDataRepository.EventColorOption matched = null;
                if (!TextUtils.isEmpty(chosenColorKey[0])) {
                    for (CalendarDataRepository.EventColorOption o : colorPalette) {
                        if (chosenColorKey[0].equals(o.colorKey)) {
                            matched = o;
                            break;
                        }
                    }
                }
                if (matched == null) {
                    String targetName = !TextUtils.isEmpty(chosenColorName[0])
                            ? chosenColorName[0]
                            : CalendarDataRepository.nearestColorName(chosenCustomColor[0]);
                    for (CalendarDataRepository.EventColorOption o : colorPalette) {
                        if (o.color == chosenCustomColor[0] || targetName.equals(o.name)) {
                            matched = o;
                            break;
                        }
                    }
                }
                if (matched != null) {
                    chosenColorKey[0] = matched.colorKey;
                    chosenCustomColor[0] = matched.color;
                    chosenColorName[0] = matched.name;
                } else {
                    chosenColorKey[0] = "";
                    chosenColorName[0] = CalendarDataRepository.nearestColorName(chosenCustomColor[0]);
                }
            }
            renderColorPicker[0].run();

            locRow.setVisibility(notion ? View.GONE : View.VISIBLE);
            reminderRow.setVisibility(View.VISIBLE);
            reminderDivider.setVisibility(View.VISIBLE);
            if (!isEdit && !userTouchedReminders[0]) {
                reminders.clear();
                int defMin = notion ? AppPrefs.getNotionDefaultReminderMinutes(activity) : 10;
                if (defMin >= 0) reminders.add(defMin);
                renderReminders[0].run();
            }
            if (!notion) statusRow.setVisibility(View.GONE);
            if (!notion) computedDateHint.setVisibility(View.GONE);
            descInput.setHint(notion ? "Notion 備註／說明（若資料庫有文字欄位將同步）" : "新增說明");
            boolean google = acctType != null && acctType.contains("google");
            sourceChip.setText(notion ? "📓 Notion" : (google ? "📅 Google 活動" : "📅 活動"));
            calSubTv.setText(notion ? "Notion 資料庫 · 儲存後直接寫回 Notion"
                    : "忙碌 · 預設瀏覽權限" + (isRecurring ? " · 重複活動" : ""));
        };

        // ================= Interactions =================
        allDayChip.setOnClickListener(v -> {
            if (readOnly) return;
            allDay[0] = !allDay[0];
            refreshTime.run();
        });

        startTimeTv.setOnClickListener(v -> new TimePickerDialog(activity, (tp, h, min) -> {
            java.time.LocalDateTime oldStart = startDate[0].atTime(startTime[0]);
            java.time.LocalDateTime oldEnd = endDate[0].atTime(endTime[0]);
            long dur = Math.max(15, java.time.Duration.between(oldStart, oldEnd).toMinutes());
            java.time.LocalDateTime ns = startDate[0].atTime(h, min);
            java.time.LocalDateTime ne = ns.plusMinutes(dur);
            startTime[0] = ns.toLocalTime();
            endDate[0] = ne.toLocalDate();
            endTime[0] = ne.toLocalTime();
            refreshTime.run();
        }, startTime[0].getHour(), startTime[0].getMinute(), true).show());

        endTimeTv.setOnClickListener(v -> new TimePickerDialog(activity, (tp, h, min) -> {
            endTime[0] = LocalTime.of(h, min);
            if (endDate[0].equals(startDate[0]) && !endTime[0].isAfter(startTime[0])) {
                endDate[0] = startDate[0].plusDays(1);
            }
            refreshTime.run();
        }, endTime[0].getHour(), endTime[0].getMinute(), true).show());

        startDateTv.setOnClickListener(v -> new DatePickerDialog(activity, (dpv, y, m, d) -> {
            LocalDate picked = LocalDate.of(y, m + 1, d);
            long span = endDate[0].toEpochDay() - startDate[0].toEpochDay();
            startDate[0] = picked;
            endDate[0] = picked.plusDays(Math.max(0, span));
            refreshTime.run();
        }, startDate[0].getYear(), startDate[0].getMonthValue() - 1, startDate[0].getDayOfMonth()).show());

        endDateTv.setOnClickListener(v -> new DatePickerDialog(activity, (dpv, y, m, d) -> {
            LocalDate picked = LocalDate.of(y, m + 1, d);
            endDate[0] = picked.isBefore(startDate[0]) ? startDate[0] : picked;
            if (!allDay[0] && endDate[0].equals(startDate[0]) && !endTime[0].isAfter(startTime[0])) {
                endTime[0] = startTime[0].plusHours(1);
                if (endTime[0].isBefore(startTime[0])) endTime[0] = LocalTime.of(23, 59);
            }
            refreshTime.run();
        }, endDate[0].getYear(), endDate[0].getMonthValue() - 1, endDate[0].getDayOfMonth()).show());

        closeBtn.setOnClickListener(v -> dialog.dismiss());
        openExtBtn.setOnClickListener(v -> {
            dialog.dismiss();
            openExternal(activity, existingEvent);
        });

        // ---- Notion schema loader
        final NotionManager.NotionEditableSchema[] activeNotionSchema = {null};
        final List<String> statusValues = new ArrayList<>();
        final java.util.function.Consumer<String> loadNotionSchema = srcKey -> {
            activeNotionSchema[0] = null;
            statusRow.setVisibility(View.GONE);
            String pid = editingNotion ? existingEvent.notionPageId : null;
            NotionManager.fetchEditableSchemaAsync(activity, srcKey, pid, new NotionManager.SchemaCallback() {
                @Override
                public void onReady(NotionManager.NotionEditableSchema schema) {
                    if (!targetNotion[0]) return;
                    activeNotionSchema[0] = schema;
                    if (editingNotion && !userEditedTitle[0] && !TextUtils.isEmpty(schema.livePlainTitle)) {
                        titleInput.setText(schema.livePlainTitle);
                        userEditedTitle[0] = false;
                    }
                    if (editingNotion && !TextUtils.isEmpty(schema.liveNoteText)
                            && descInput.getText().toString().isEmpty()) {
                        descInput.setText(schema.liveNoteText);
                    }
                    boolean dateOk = schema.dateEditable && !readOnly;
                    computedDateHint.setVisibility(schema.dateEditable ? View.GONE : View.VISIBLE);
                    allDayChip.setEnabled(dateOk);
                    startTimeTv.setEnabled(dateOk);
                    endTimeTv.setEnabled(dateOk);
                    startDateTv.setEnabled(dateOk);
                    endDateTv.setEnabled(dateOk);

                    if (!TextUtils.isEmpty(schema.statusPropName) && !schema.statusOptions.isEmpty()) {
                        statusValues.clear();
                        statusValues.add("");
                        statusValues.addAll(schema.statusOptions);
                        List<String> display = new ArrayList<>();
                        display.add("（保持預設／不變更）");
                        display.addAll(schema.statusOptions);
                        statusLabel.setText("Notion 欄位：" + schema.statusPropName);
                        statusSpinner.setAdapter(new ArrayAdapter<>(
                                activity, android.R.layout.simple_spinner_dropdown_item, display));
                        String target = !TextUtils.isEmpty(schema.liveStatus)
                                ? schema.liveStatus : (editingNotion ? existingEvent.statusLabel : "");
                        int sel = 0;
                        for (int i = 1; i < statusValues.size(); i++) {
                            if (statusValues.get(i).equals(target)) {
                                sel = i;
                                break;
                            }
                        }
                        statusSpinner.setSelection(sel);
                        statusSpinner.setEnabled(!readOnly);
                        statusRow.setVisibility(View.VISIBLE);
                    }
                }

                @Override
                public void onError(String message) {
                }
            });
        };

        if (editingNotion) {
            loadNotionSchema.accept(existingEvent.sourceKey);
        }
        if (showDestSpinner) {
            destSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    TargetDestination td = destinations.get(position);
                    targetNotion[0] = td.isNotion;
                    applyTargetMode.run();
                    if (td.isNotion) {
                        loadNotionSchema.accept(td.notionSrc.sourceKey);
                    } else {
                        allDayChip.setEnabled(true);
                        startTimeTv.setEnabled(true);
                        endTimeTv.setEnabled(true);
                        startDateTv.setEnabled(true);
                        endDateTv.setEnabled(true);
                    }
                }

                @Override
                public void onNothingSelected(AdapterView<?> parent) {
                }
            });
        }

        if (readOnly) {
            titleInput.setEnabled(false);
            locInput.setEnabled(false);
            descInput.setEnabled(false);
            startTimeTv.setEnabled(false);
            endTimeTv.setEnabled(false);
            startDateTv.setEnabled(false);
            endDateTv.setEnabled(false);
            if (descInput.getText().toString().trim().isEmpty()) {
                descInput.setHint("沒有說明");
            }
            if (locInput.getText().toString().trim().isEmpty()) {
                locRow.setVisibility(View.GONE);
            }
        }

        // ---- Delete (scope aware for recurring Google events)
        deleteBtn.setOnClickListener(v -> {
            if (editingNotion) {
                new AlertDialog.Builder(activity)
                        .setTitle("移至 Notion 垃圾桶")
                        .setMessage("確定要在 Notion 將「" + existingEvent.title + "」移至垃圾桶嗎？")
                        .setPositiveButton("刪除", (d, w) -> {
                            deleteBtn.setEnabled(false);
                            NotionManager.trashNotionEventAsync(activity, existingEvent.notionPageId,
                                    new NotionManager.MutationCallback() {
                                        @Override
                                        public void onSuccess() {
                                            AppPrefs.clearCustomNotionEventColor(activity, existingEvent.notionPageId);
                                            dialog.dismiss();
                                            Toast.makeText(activity, "✅ 已從 Notion 移至垃圾桶", Toast.LENGTH_SHORT).show();
                                            callbacks.onEventsMutated();
                                        }

                                        @Override
                                        public void onError(String message) {
                                            deleteBtn.setEnabled(true);
                                            showError(activity, "刪除失敗", message);
                                        }
                                    });
                        })
                        .setNegativeButton("取消", null)
                        .show();
                return;
            }
            if (!CalendarDataRepository.hasWritePermission(activity)) {
                callbacks.onRequestWritePermission();
                return;
            }
            if (isRecurring) {
                promptRecurringScope(activity, "刪除重複活動", "刪除", scope -> {
                    boolean ok = CalendarDataRepository.deleteSystemEvent(activity, sysMeta, scope,
                            existingEvent.beginMillis, existingEvent.allDay);
                    afterSystemDelete(activity, dialog, callbacks, ok);
                });
            } else {
                new AlertDialog.Builder(activity)
                        .setTitle("刪除活動")
                        .setMessage("確定要刪除「" + existingEvent.title + "」並同步回來源日曆嗎？")
                        .setPositiveButton("刪除", (d, w) -> {
                            boolean ok = CalendarDataRepository.deleteSystemEvent(activity, existingEvent.eventId);
                            afterSystemDelete(activity, dialog, callbacks, ok);
                        })
                        .setNegativeButton("取消", null)
                        .show();
            }
        });

        // ---- Save
        saveBtn.setOnClickListener(v -> {
            String title = titleInput.getText().toString().trim();
            if (title.isEmpty()) {
                Toast.makeText(activity, "請輸入標題", Toast.LENGTH_SHORT).show();
                return;
            }
            final boolean ad = allDay[0];
            ZonedDateTime bZdt = startDate[0].atTime(startTime[0]).atZone(zone);
            ZonedDateTime eZdt = endDate[0].atTime(endTime[0]).atZone(zone);
            if (!ad && !eZdt.isAfter(bZdt)) {
                eZdt = bZdt.plusHours(1);
            }
            final ZonedDateTime finalBegin = bZdt;
            final ZonedDateTime finalEnd = eZdt;

            final TargetDestination chosenDest = (showDestSpinner && !destinations.isEmpty())
                    ? destinations.get(Math.max(0, destSpinner.getSelectedItemPosition())) : null;

            if (targetNotion[0]) {
                String chosenStatus = "";
                int sp = statusSpinner.getSelectedItemPosition();
                if (statusRow.getVisibility() == View.VISIBLE && sp > 0 && sp < statusValues.size()) {
                    chosenStatus = statusValues.get(sp);
                }
                String noteVal = descInput.getText().toString().trim();
                saveBtn.setEnabled(false);
                saveBtn.setText("同步中…");
                NotionManager.MutationCallback mutCb = new NotionManager.MutationCallback() {
                    @Override
                    public void onSuccess() {
                        if (editingNotion && !TextUtils.isEmpty(existingEvent.notionPageId)) {
                            if (userTouchedReminders[0]) {
                                AppPrefs.setCustomNotionReminders(activity, existingEvent.notionPageId, reminders);
                            }
                            if (customColorEnabled[0]) {
                                AppPrefs.setCustomNotionEventColor(
                                        activity, existingEvent.notionPageId, chosenCustomColor[0]);
                            } else {
                                AppPrefs.clearCustomNotionEventColor(activity, existingEvent.notionPageId);
                            }
                        }
                        CalendarWidgetProvider.updateAll(activity);
                        EventReminderManager.scheduleAsync(activity);
                        dialog.dismiss();
                        Toast.makeText(activity,
                                editingNotion ? "✅ 已儲存並同步回 Notion" : "✅ 已新增至 Notion",
                                Toast.LENGTH_SHORT).show();
                        callbacks.onEventsMutated();
                    }

                    @Override
                    public void onCreatedPage(String pageId) {
                        if (!TextUtils.isEmpty(pageId)) {
                            if (userTouchedReminders[0]) {
                                AppPrefs.setCustomNotionReminders(activity, pageId, reminders);
                            }
                            if (customColorEnabled[0]) {
                                AppPrefs.setCustomNotionEventColor(activity, pageId, chosenCustomColor[0]);
                            }
                        }
                        onSuccess();
                    }

                    @Override
                    public void onError(String message) {
                        saveBtn.setEnabled(true);
                        saveBtn.setText("儲存");
                        showError(activity, "寫入 Notion 失敗", message);
                    }
                };
                if (editingNotion) {
                    NotionManager.updateNotionEventAsync(activity, existingEvent.sourceKey,
                            existingEvent.notionPageId, activeNotionSchema[0], title, ad,
                            startDate[0], endDate[0], finalBegin, finalEnd, chosenStatus, noteVal, mutCb);
                } else if (chosenDest != null && chosenDest.notionSrc != null) {
                    NotionManager.createNotionEventAsync(activity, chosenDest.notionSrc.sourceKey,
                            activeNotionSchema[0], title, ad, startDate[0], endDate[0],
                            finalBegin, finalEnd, chosenStatus, noteVal, mutCb);
                }
                return;
            }

            // ---- Google / system calendar
            if (!CalendarDataRepository.hasWritePermission(activity)) {
                callbacks.onRequestWritePermission();
                return;
            }
            final String locVal = locInput.getText().toString().trim();
            final String descVal = descInput.getText().toString().trim();
            final long beginMs = finalBegin.toInstant().toEpochMilli();
            final long endMs = finalEnd.toInstant().toEpochMilli();
            final CalendarDataRepository.EventColorSelection colorSel = customColorEnabled[0]
                    ? CalendarDataRepository.EventColorSelection.custom(chosenColorKey[0], chosenCustomColor[0])
                    : CalendarDataRepository.EventColorSelection.calendarDefault();

            if (!isEdit) {
                if (chosenDest == null || chosenDest.sysCal == null) {
                    Intent insert = new Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)
                            .putExtra(CalendarContract.Events.TITLE, title)
                            .putExtra(CalendarContract.Events.EVENT_LOCATION, locVal)
                            .putExtra(CalendarContract.Events.DESCRIPTION, descVal)
                            .putExtra(CalendarContract.Events.ALL_DAY, ad)
                            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, beginMs)
                            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endMs);
                    dialog.dismiss();
                    try {
                        activity.startActivity(insert);
                    } catch (ActivityNotFoundException ignored) {
                    }
                    return;
                }
                long newId = CalendarDataRepository.insertSystemEvent(activity, chosenDest.sysCal.id,
                        title, locVal, descVal, ad, startDate[0], endDate[0], beginMs, endMs, colorSel);
                if (newId >= 0) {
                    if (!reminders.isEmpty()) {
                        CalendarDataRepository.saveEventReminders(activity, newId, reminders);
                    }
                    EventReminderManager.scheduleAsync(activity);
                    dialog.dismiss();
                    Toast.makeText(activity, "✅ 已新增並同步回 Google 日曆", Toast.LENGTH_SHORT).show();
                    callbacks.onEventsMutated();
                } else {
                    Toast.makeText(activity, "新增失敗，請確認日曆寫入權限", Toast.LENGTH_SHORT).show();
                }
                return;
            }

            final long targetCalId = (chosenDest != null && chosenDest.sysCal != null)
                    ? chosenDest.sysCal.id : (sysMeta != null ? sysMeta.calendarId : -1L);
            final boolean remindersChanged = !new java.util.HashSet<>(reminders)
                    .equals(new java.util.HashSet<>(originalReminders));

            java.util.function.IntConsumer doUpdate = scope -> {
                long resultId = CalendarDataRepository.updateSystemEvent(activity, sysMeta, scope,
                        targetCalId, existingEvent.beginMillis, existingEvent.allDay,
                        title, locVal, descVal, ad, startDate[0], endDate[0], beginMs, endMs, colorSel);
                if (resultId >= 0) {
                    boolean newRow = resultId != existingEvent.eventId;
                    if (remindersChanged || (newRow && !reminders.isEmpty())) {
                        CalendarDataRepository.saveEventReminders(activity, resultId, reminders);
                    }
                    EventReminderManager.scheduleAsync(activity);
                    dialog.dismiss();
                    Toast.makeText(activity, "✅ 已儲存並同步回 Google 日曆", Toast.LENGTH_SHORT).show();
                    callbacks.onEventsMutated();
                } else {
                    Toast.makeText(activity, "更新失敗，此活動可能屬於唯讀日曆", Toast.LENGTH_SHORT).show();
                }
            };

            if (isRecurring) {
                promptRecurringScope(activity, "儲存重複活動", "儲存", doUpdate);
            } else {
                doUpdate.accept(CalendarDataRepository.EDIT_SCOPE_SINGLE);
            }
        });

        refreshTime.run();
        applyTargetMode.run();
        if (!isEdit) {
            titleInput.requestFocus();
        }

        dialog.setContentView(card);
        dialog.show();
        Window w = dialog.getWindow();
        if (w != null) {
            DisplayMetrics dm = activity.getResources().getDisplayMetrics();
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(Math.round(dm.widthPixels * 0.96f), ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.BOTTOM);
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.y = dp(activity, 10);
            w.setAttributes(lp);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setDimAmount(0.45f);
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                    | (isEdit ? WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
                    : WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE));
            w.clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
        }
    }

    // ---------------------------- Sheet helpers ----------------------------

    /** Google Calendar's 3-way prompt for recurring events. */
    private static void promptRecurringScope(
            Activity activity, String title, String positive, java.util.function.IntConsumer onChosen) {
        final String[] items = {
                "只修改這一次活動",
                "這個和後續活動（包含這件事情跟後面全部）",
                "所有活動"
        };
        if (positive.equals("刪除")) {
            items[0] = "只刪除這一次活動";
            items[1] = "這個和後續活動";
            items[2] = "所有活動（整個重複系列）";
        }
        final int[] chosen = {0};
        new AlertDialog.Builder(activity)
                .setTitle(title)
                .setSingleChoiceItems(items, 0, (d, which) -> chosen[0] = which)
                .setPositiveButton(positive, (d, w) -> {
                    int scope = chosen[0] == 0 ? CalendarDataRepository.EDIT_SCOPE_SINGLE
                            : (chosen[0] == 1 ? CalendarDataRepository.EDIT_SCOPE_THIS_AND_FOLLOWING
                            : CalendarDataRepository.EDIT_SCOPE_ALL);
                    onChosen.accept(scope);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private static void afterSystemDelete(Activity activity, Dialog dialog, Callbacks callbacks, boolean ok) {
        if (ok) {
            EventReminderManager.scheduleAsync(activity);
            dialog.dismiss();
            Toast.makeText(activity, "✅ 已刪除並啟動秒速雲端同步", Toast.LENGTH_SHORT).show();
            callbacks.onEventsMutated();
        } else {
            Toast.makeText(activity, "刪除失敗，此日曆可能為唯讀", Toast.LENGTH_SHORT).show();
        }
    }

    private static void openExternal(Activity activity, CalendarEvent ev) {
        if (ev == null) return;
        try {
            if (ev.isNotion()) {
                if (!TextUtils.isEmpty(ev.notionUrl)) {
                    activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(ev.notionUrl)));
                }
            } else if (ev.eventId >= 0L) {
                Uri uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, ev.eventId);
                activity.startActivity(new Intent(Intent.ACTION_VIEW, uri)
                        .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, ev.beginMillis)
                        .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, ev.endMillis));
            }
        } catch (ActivityNotFoundException ignored) {
        }
    }

    private static void showError(Activity activity, String title, String message) {
        new AlertDialog.Builder(activity)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("好", null)
                .show();
    }

    private static String formatReminder(int minutes) {
        if (minutes == 0) return "活動開始時";
        if (minutes % 10080 == 0) return (minutes / 10080) + " 週之前";
        if (minutes % 1440 == 0) return (minutes / 1440) + " 天之前";
        if (minutes % 60 == 0) return (minutes / 60) + " 小時之前";
        return minutes + " 分鐘之前";
    }

    private static String formatDuration(long mins) {
        if (mins <= 0) return "";
        long d = mins / 1440;
        long h = (mins % 1440) / 60;
        long m = mins % 60;
        StringBuilder sb = new StringBuilder();
        if (d > 0) sb.append(d).append(" 天 ");
        if (h > 0) sb.append(h).append(" 小時 ");
        if (m > 0) sb.append(m).append(" 分鐘");
        return sb.toString().trim();
    }

    private static String formatSheetDate(LocalDate d) {
        String s = String.format(Locale.TAIWAN, "%d月%d日 %s",
                d.getMonthValue(), d.getDayOfMonth(), DOW_NAMES[d.getDayOfWeek().getValue() - 1]);
        if (d.getYear() != LocalDate.now().getYear()) s = d.getYear() + "年" + s;
        return s;
    }

    private static String shortTzName() {
        String n = TimeZone.getDefault().getDisplayName(false, TimeZone.LONG, Locale.TAIWAN);
        return n == null ? "" : n;
    }

    private static LinearLayout makeSheetRow(Context ctx, AppTheme.Palette palette, String icon, View content) {
        TextView iconTv = new TextView(ctx);
        iconTv.setText(icon);
        iconTv.setTextSize(17f);
        iconTv.setTextColor(palette.textSecondary);
        iconTv.setGravity(Gravity.CENTER);
        return makeSheetRowWithIconView(ctx, iconTv, content);
    }

    private static LinearLayout makeSheetRowWithIconView(Context ctx, View iconView, View content) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(ctx, 48));
        row.setPadding(0, dp(ctx, 3), 0, dp(ctx, 3));
        boolean isDot = !(iconView instanceof TextView);
        LinearLayout.LayoutParams iLp = isDot
                ? new LinearLayout.LayoutParams(dp(ctx, 14), dp(ctx, 14))
                : new LinearLayout.LayoutParams(dp(ctx, 44), ViewGroup.LayoutParams.WRAP_CONTENT);
        if (isDot) {
            iLp.setMarginStart(dp(ctx, 15));
            iLp.setMarginEnd(dp(ctx, 15));
        }
        row.addView(iconView, iLp);
        row.addView(content, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    private static View makeDivider(Context ctx, AppTheme.Palette palette) {
        View v = new View(ctx);
        v.setBackgroundColor(palette.divider);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 1));
        lp.setMargins(dp(ctx, 44), dp(ctx, 6), 0, dp(ctx, 6));
        v.setLayoutParams(lp);
        return v;
    }

    private static TextView makeValueText(Context ctx, AppTheme.Palette palette, float sp) {
        TextView t = new TextView(ctx);
        t.setTextSize(sp);
        t.setTextColor(palette.textPrimary);
        t.setPadding(0, dp(ctx, 8), 0, dp(ctx, 8));
        applyRipple(t, false);
        return t;
    }

    private static EditText makeBorderlessInput(Context ctx, AppTheme.Palette palette, String hint) {
        EditText e = new EditText(ctx);
        e.setHint(hint);
        e.setTextSize(15.5f);
        e.setTextColor(palette.textPrimary);
        e.setHintTextColor(palette.textSecondary);
        e.setBackground(null);
        e.setSingleLine(true);
        e.setPadding(0, dp(ctx, 10), 0, dp(ctx, 10));
        return e;
    }

    private static TextView makeIconButton(Context ctx, String glyph, int color, float sp) {
        TextView t = new TextView(ctx);
        t.setText(glyph);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        t.setLayoutParams(new LinearLayout.LayoutParams(dp(ctx, 42), dp(ctx, 42)));
        t.setMinWidth(dp(ctx, 42));
        t.setMinHeight(dp(ctx, 42));
        applyRipple(t, true);
        return t;
    }

    private static TextView makeChipButton(Context ctx, String label, int textColor, int bgColor, float sp) {
        TextView t = new TextView(ctx);
        t.setText(label);
        t.setTextSize(sp);
        t.setTextColor(textColor);
        t.setGravity(Gravity.CENTER);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setSingleLine(true);
        t.setBackground(roundedBg(bgColor, dp(ctx, 16)));
        return t;
    }

    private static GradientDrawable roundedBg(int color, int radiusPx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusPx);
        return g;
    }

    private static void applyRipple(View v, boolean borderless) {
        TypedValue tv = new TypedValue();
        if (v.getContext().getTheme().resolveAttribute(borderless
                ? android.R.attr.selectableItemBackgroundBorderless
                : android.R.attr.selectableItemBackground, tv, true)) {
            v.setBackgroundResource(tv.resourceId);
        }
        v.setClickable(true);
        v.setFocusable(true);
    }

    /** Drag the sheet down by its handle / top bar to dismiss (Google bottom-sheet feel). */
    private static void attachSwipeToDismiss(View dragArea, View sheet, Dialog dialog) {
        final float[] downY = {0f};
        final boolean[] dragging = {false};
        final float threshold = 110f * dragArea.getResources().getDisplayMetrics().density;
        dragArea.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downY[0] = e.getRawY();
                    dragging[0] = false;
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dy = e.getRawY() - downY[0];
                    if (dy > 12f) dragging[0] = true;
                    if (dragging[0]) sheet.setTranslationY(Math.max(0f, dy));
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    float dy = e.getRawY() - downY[0];
                    if (dragging[0] && dy > threshold) {
                        sheet.animate().translationY(sheet.getHeight()).setDuration(160)
                                .withEndAction(dialog::dismiss).start();
                    } else {
                        sheet.animate().translationY(0f).setDuration(160).start();
                    }
                    return true;
                }
                default:
                    return false;
            }
        });
    }

    private static final class MaxHeightLinearLayout extends LinearLayout {
        int maxHeightPx = Integer.MAX_VALUE;

        MaxHeightLinearLayout(Context ctx) {
            super(ctx);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int size = MeasureSpec.getSize(heightMeasureSpec);
            int mode = MeasureSpec.getMode(heightMeasureSpec);
            int cap = (mode == MeasureSpec.UNSPECIFIED) ? maxHeightPx : Math.min(size, maxHeightPx);
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST));
        }
    }

    private static final class SimpleWatcher implements TextWatcher {
        private final Runnable onChange;

        SimpleWatcher(Runnable onChange) {
            this.onChange = onChange;
        }

        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {
        }

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
            onChange.run();
        }

        @Override
        public void afterTextChanged(Editable s) {
        }
    }

    public static void showSearchDialog(
            Activity activity,
            Set<String> hiddenCalendars,
            AppTheme.Palette palette,
            Callbacks callbacks) {

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(activity, 18), dp(activity, 16), dp(activity, 18), dp(activity, 12));
        root.setBackgroundColor(palette.cardElevated);

        TextView title = new TextView(activity);
        title.setText("🔍 搜尋行程（Google ＋ Notion）");
        title.setTextSize(18f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(palette.textPrimary);
        root.addView(title);

        EditText searchInput = new EditText(activity);
        searchInput.setHint("輸入關鍵字（標題、地點、日曆名稱、Notion 狀態）");
        searchInput.setTextColor(palette.textPrimary);
        searchInput.setHintTextColor(palette.textSecondary);
        searchInput.setSingleLine(true);
        root.addView(searchInput);

        TextView countHint = new TextView(activity);
        countHint.setText("請輸入關鍵字即時篩選近一年行程");
        countHint.setTextSize(12.5f);
        countHint.setTextColor(palette.textSecondary);
        countHint.setPadding(0, dp(activity, 4), 0, dp(activity, 6));
        root.addView(countHint);

        ListView resultList = new ListView(activity);
        resultList.setDividerHeight(0);
        final List<CalendarEvent> results = new ArrayList<>();

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setView(root)
                .setNegativeButton("關閉", null)
                .create();

        ArrayAdapter<CalendarEvent> resAdapter = new ArrayAdapter<CalendarEvent>(activity, 0, results) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                CalendarEvent ev = results.get(position);
                LinearLayout card = new LinearLayout(activity);
                card.setOrientation(LinearLayout.VERTICAL);
                card.setPadding(dp(activity, 12), dp(activity, 8), dp(activity, 12), dp(activity, 8));
                GradientDrawable bg = new GradientDrawable();
                bg.setCornerRadius(dp(activity, 10));
                bg.setColor(ev.color);
                card.setBackground(bg);

                TextView t = new TextView(activity);
                t.setText(ev.title);
                t.setTextSize(14.5f);
                t.setTypeface(Typeface.DEFAULT_BOLD);
                t.setTextColor(AppTheme.textColorOnPill(ev.color));
                card.addView(t);

                LocalDate d = LocalDate.ofEpochDay(ev.startEpochDay);
                String meta = String.format(Locale.TAIWAN, "%d/%02d/%02d (%s) · %s · %s",
                        d.getYear(), d.getMonthValue(), d.getDayOfMonth(),
                        DOW_NAMES[d.getDayOfWeek().getValue() - 1],
                        ev.formatTimeRange(ZoneId.systemDefault()),
                        ev.calendarName);
                TextView sub = new TextView(activity);
                sub.setText(meta);
                sub.setTextSize(12f);
                sub.setTextColor(AppTheme.subTextColorOnPill(ev.color));
                card.addView(sub);

                LinearLayout wrap = new LinearLayout(activity);
                wrap.setPadding(0, dp(activity, 4), 0, dp(activity, 4));
                wrap.addView(card, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                return wrap;
            }
        };
        resultList.setAdapter(resAdapter);
        resultList.setOnItemClickListener((parent, view, position, id) -> {
            CalendarEvent ev = results.get(position);
            dialog.dismiss();
            callbacks.onJumpToDateAndView(LocalDate.ofEpochDay(ev.startEpochDay), -1);
            showEventDetailDialog(activity, ev, palette, callbacks);
        });

        root.addView(resultList, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 340)));

        searchInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                String q = s.toString().trim();
                results.clear();
                if (q.isEmpty()) {
                    countHint.setText("請輸入關鍵字即時篩選近一年行程");
                } else {
                    results.addAll(CalendarDataRepository.searchEvents(activity, q, hiddenCalendars));
                    countHint.setText("找到 " + results.size() + " 筆相符行程");
                }
                resAdapter.notifyDataSetChanged();
            }
        });

        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
        }
    }

    public static void showMonthYearPickerDialog(
            Activity activity,
            YearMonth currentYm,
            AppTheme.Palette palette,
            Callbacks callbacks) {

        final int[] yearHolder = {currentYm.getYear()};

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(activity, 18), dp(activity, 16), dp(activity, 18), dp(activity, 16));
        root.setBackgroundColor(palette.cardElevated);

        LinearLayout yearRow = new LinearLayout(activity);
        yearRow.setOrientation(LinearLayout.HORIZONTAL);
        yearRow.setGravity(Gravity.CENTER_VERTICAL);

        Button prevY = makeFlatButton(activity, "◀", palette.textPrimary, palette.chipBg);
        Button nextY = makeFlatButton(activity, "▶", palette.textPrimary, palette.chipBg);
        TextView yearLabel = new TextView(activity);
        yearLabel.setText(yearHolder[0] + " 年");
        yearLabel.setTextSize(19f);
        yearLabel.setTypeface(Typeface.DEFAULT_BOLD);
        yearLabel.setTextColor(palette.textPrimary);
        yearLabel.setGravity(Gravity.CENTER);

        prevY.setOnClickListener(v -> {
            yearHolder[0]--;
            yearLabel.setText(yearHolder[0] + " 年");
        });
        nextY.setOnClickListener(v -> {
            yearHolder[0]++;
            yearLabel.setText(yearHolder[0] + " 年");
        });

        yearRow.addView(prevY);
        yearRow.addView(yearLabel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        yearRow.addView(nextY);
        root.addView(yearRow);

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setView(root)
                .create();

        for (int r = 0; r < 3; r++) {
            LinearLayout mRow = new LinearLayout(activity);
            mRow.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rLp.topMargin = dp(activity, 10);
            mRow.setLayoutParams(rLp);

            for (int c = 0; c < 4; c++) {
                final int month = r * 4 + c + 1;
                boolean isCurrent = (month == currentYm.getMonthValue() && yearHolder[0] == currentYm.getYear());
                Button mBtn = makeFlatButton(activity, month + "月",
                        isCurrent ? palette.todayText : palette.textPrimary,
                        isCurrent ? palette.todayFill : palette.chipBg);
                LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                if (c > 0) bLp.setMarginStart(dp(activity, 6));
                mBtn.setLayoutParams(bLp);
                mBtn.setOnClickListener(v -> {
                    dialog.dismiss();
                    callbacks.onJumpToDateAndView(LocalDate.of(yearHolder[0], month, 1), -1);
                });
                mRow.addView(mBtn);
            }
            root.addView(mRow);
        }

        dialog.show();
    }

    private static TextView addDetailRow(
            Context ctx, LinearLayout parent, String text, int color, float spSize, int topMarginDp) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(spSize);
        tv.setTextColor(color);
        tv.setLineSpacing(dp(ctx, 3), 1f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(ctx, topMarginDp);
        parent.addView(tv, lp);
        return tv;
    }

    private static Button makeFlatButton(Context ctx, String label, int textColor, int bgColor) {
        Button b = new Button(ctx);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(13.5f);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextColor(textColor);
        b.setMinimumHeight(dp(ctx, 40));
        b.setMinHeight(dp(ctx, 40));
        b.setPadding(dp(ctx, 14), dp(ctx, 6), dp(ctx, 14), dp(ctx, 6));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(ctx, 20));
        bg.setColor(bgColor);
        b.setBackground(bg);
        return b;
    }

    private static int dp(Context ctx, int v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }
}
