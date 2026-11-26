package io.github.kamaje122.calwidget;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
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

    public static void showDayOverviewDialog(
            Activity activity,
            LocalDate date,
            List<CalendarEvent> dayEvents,
            AppTheme.Palette palette,
            Callbacks callbacks) {

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(activity, 20), dp(activity, 18), dp(activity, 20), dp(activity, 14));
        root.setBackgroundColor(palette.cardElevated);

        String titleStr = String.format(Locale.TAIWAN, "%d月%d日 %s",
                date.getMonthValue(), date.getDayOfMonth(), DOW_NAMES[date.getDayOfWeek().getValue() - 1]);
        TextView header = new TextView(activity);
        header.setText(titleStr);
        header.setTextSize(20f);
        header.setTypeface(Typeface.DEFAULT_BOLD);
        header.setTextColor(palette.textPrimary);
        root.addView(header);

        TextView lunarSub = new TextView(activity);
        lunarSub.setText(LunarCalendarHelper.getFullDateLabel(date));
        lunarSub.setTextSize(13f);
        lunarSub.setTextColor(palette.textSecondary);
        lunarSub.setPadding(0, dp(activity, 2), 0, dp(activity, 12));
        root.addView(lunarSub);

        ScrollView scroll = new ScrollView(activity);
        LinearLayout listCol = new LinearLayout(activity);
        listCol.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(listCol);

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setView(root)
                .create();

        if (dayEvents.isEmpty()) {
            TextView empty = new TextView(activity);
            empty.setText("這一天目前沒有排定行程");
            empty.setTextSize(14.5f);
            empty.setTextColor(palette.textSecondary);
            empty.setPadding(0, dp(activity, 14), 0, dp(activity, 18));
            listCol.addView(empty);
        } else {
            ZoneId zone = ZoneId.systemDefault();
            for (CalendarEvent ev : dayEvents) {
                LinearLayout card = new LinearLayout(activity);
                card.setOrientation(LinearLayout.VERTICAL);
                card.setPadding(dp(activity, 14), dp(activity, 10), dp(activity, 14), dp(activity, 10));
                GradientDrawable bg = new GradientDrawable();
                bg.setCornerRadius(dp(activity, 10));
                bg.setColor(ev.color);
                card.setBackground(bg);

                LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                cLp.bottomMargin = dp(activity, 8);
                card.setLayoutParams(cLp);

                int mainText = AppTheme.textColorOnPill(ev.color);
                int subText = AppTheme.subTextColorOnPill(ev.color);

                TextView t = new TextView(activity);
                t.setText(ev.title);
                t.setTextSize(15f);
                t.setTypeface(Typeface.DEFAULT_BOLD);
                t.setTextColor(mainText);
                card.addView(t);

                StringBuilder subSb = new StringBuilder(ev.formatTimeRange(zone));
                if (!TextUtils.isEmpty(ev.calendarName)) {
                    subSb.append(" · ").append(ev.calendarName);
                }
                if (!TextUtils.isEmpty(ev.location)) {
                    subSb.append(" · ").append(ev.location);
                } else if (!TextUtils.isEmpty(ev.statusLabel)) {
                    subSb.append(" · ").append(ev.statusLabel);
                }

                TextView s = new TextView(activity);
                s.setText(subSb.toString());
                s.setTextSize(12.5f);
                s.setTextColor(subText);
                s.setPadding(0, dp(activity, 2), 0, 0);
                card.addView(s);

                card.setOnClickListener(v -> {
                    dialog.dismiss();
                    showEventDetailDialog(activity, ev, palette, callbacks);
                });
                listCol.addView(card);
            }
        }

        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(scroll, scrollLp);

        LinearLayout btnRow = new LinearLayout(activity);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.END);
        btnRow.setPadding(0, dp(activity, 10), 0, 0);

        Button dayTimelineBtn = makeFlatButton(activity, "單日時間軸", palette.textPrimary, palette.chipBg);
        dayTimelineBtn.setOnClickListener(v -> {
            dialog.dismiss();
            callbacks.onJumpToDateAndView(date, AppPrefs.VIEW_DAY_1);
        });
        btnRow.addView(dayTimelineBtn);

        Button addBtn = makeFlatButton(activity, "＋ 新增行程", palette.todayText, palette.todayFill);
        LinearLayout.LayoutParams addLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        addLp.setMarginStart(dp(activity, 8));
        addBtn.setLayoutParams(addLp);
        addBtn.setOnClickListener(v -> {
            dialog.dismiss();
            showCreateEventDialog(activity, date, -1, palette, callbacks);
        });
        btnRow.addView(addBtn);

        root.addView(btnRow);
        dialog.show();
    }

    public static void showEventDetailDialog(
            Activity activity,
            CalendarEvent ev,
            AppTheme.Palette palette,
            Callbacks callbacks) {

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(activity, 20), dp(activity, 20), dp(activity, 20), dp(activity, 16));
        root.setBackgroundColor(palette.cardElevated);

        LinearLayout titleRow = new LinearLayout(activity);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.TOP);

        View colorDot = new View(activity);
        GradientDrawable dotBg = new GradientDrawable();
        dotBg.setCornerRadius(dp(activity, 6));
        dotBg.setColor(ev.color);
        colorDot.setBackground(dotBg);
        LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(dp(activity, 18), dp(activity, 18));
        dLp.topMargin = dp(activity, 5);
        dLp.setMarginEnd(dp(activity, 12));
        colorDot.setLayoutParams(dLp);
        titleRow.addView(colorDot);

        TextView titleTv = new TextView(activity);
        titleTv.setText(ev.title);
        titleTv.setTextSize(20f);
        titleTv.setTypeface(Typeface.DEFAULT_BOLD);
        titleTv.setTextColor(palette.textPrimary);
        titleRow.addView(titleTv, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(titleRow);

        LocalDate startD = LocalDate.ofEpochDay(ev.startEpochDay);
        ZoneId zone = ZoneId.systemDefault();
        String dateLine = String.format(Locale.TAIWAN, "%d年%d月%d日 %s（%s）\n⏰ %s",
                startD.getYear(), startD.getMonthValue(), startD.getDayOfMonth(),
                DOW_NAMES[startD.getDayOfWeek().getValue() - 1],
                LunarCalendarHelper.getFullDateLabel(startD),
                ev.formatTimeRange(zone));
        addDetailRow(activity, root, dateLine, palette.textPrimary, 14.5f, 12);

        StringBuilder srcLine = new StringBuilder();
        srcLine.append(ev.isNotion() ? "📓 " : "📅 ");
        if (!TextUtils.isEmpty(ev.calendarName)) {
            srcLine.append(ev.calendarName);
        } else {
            srcLine.append(ev.isNotion() ? "Notion 日曆" : "系統行事曆");
        }
        if (!TextUtils.isEmpty(ev.accountName)) {
            srcLine.append("（").append(ev.accountName).append("）");
        }
        if (!TextUtils.isEmpty(ev.statusLabel)) {
            srcLine.append("\n🏷️ 狀態：").append(ev.statusLabel);
        }
        addDetailRow(activity, root, srcLine.toString(), palette.textSecondary, 13.5f, 10);

        if (!TextUtils.isEmpty(ev.location)) {
            TextView locTv = addDetailRow(activity, root,
                    "📍 " + ev.location.trim() + "（點擊開啟地圖）",
                    palette.selectedStroke, 13.8f, 10);
            locTv.setOnClickListener(v -> {
                try {
                    Uri geo = Uri.parse("geo:0,0?q=" + Uri.encode(ev.location.trim()));
                    activity.startActivity(new Intent(Intent.ACTION_VIEW, geo));
                } catch (ActivityNotFoundException ignored) {
                }
            });
        }

        if (!TextUtils.isEmpty(ev.description)) {
            ScrollView descScroll = new ScrollView(activity);
            TextView descTv = new TextView(activity);
            descTv.setText(ev.description.trim());
            descTv.setTextSize(13.5f);
            descTv.setTextColor(palette.textSecondary);
            descScroll.addView(descTv);
            LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            sLp.topMargin = dp(activity, 10);
            root.addView(descScroll, sLp);
        }

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setView(root)
                .create();

        // Optional external link row (open in Notion app / system Google Calendar)
        if (ev.isNotion() && !TextUtils.isEmpty(ev.notionUrl)) {
            TextView extLink = addDetailRow(activity, root,
                    "↗ 在 Notion 開啟完整頁面內容", palette.selectedStroke, 13.5f, 12);
            extLink.setTypeface(Typeface.DEFAULT_BOLD);
            extLink.setOnClickListener(v -> {
                dialog.dismiss();
                try {
                    activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(ev.notionUrl)));
                } catch (Exception ignored) {
                }
            });
        } else if (ev.eventId >= 0L) {
            TextView extLink = addDetailRow(activity, root,
                    "↗ 用系統 Google 日曆開啟（進階提醒／邀請對象）", palette.selectedStroke, 13f, 12);
            extLink.setOnClickListener(v -> {
                dialog.dismiss();
                try {
                    Uri uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, ev.eventId);
                    Intent viewIntent = new Intent(Intent.ACTION_VIEW, uri)
                            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, ev.beginMillis)
                            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, ev.endMillis);
                    activity.startActivity(viewIntent);
                } catch (ActivityNotFoundException ignored) {
                }
            });
        }

        // Primary Action Buttons Row: Delete + Direct In-App Edit & Save
        LinearLayout actions = new LinearLayout(activity);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.END);
        actions.setPadding(0, dp(activity, 16), 0, 0);

        if (ev.isNotion()) {
            if (!TextUtils.isEmpty(ev.notionPageId)) {
                Button delBtn = makeFlatButton(activity, "刪除", 0xFFD93025, palette.chipBg);
                delBtn.setOnClickListener(v -> {
                    new AlertDialog.Builder(activity)
                            .setTitle("移至 Notion 垃圾桶")
                            .setMessage("確定要在 Notion 將「" + ev.title + "」移至垃圾桶嗎？")
                            .setPositiveButton("確定刪除", (d, w) -> {
                                delBtn.setEnabled(false);
                                delBtn.setText("刪除中…");
                                NotionManager.trashNotionEventAsync(activity, ev.notionPageId,
                                        new NotionManager.MutationCallback() {
                                            @Override
                                            public void onSuccess() {
                                                dialog.dismiss();
                                                Toast.makeText(activity, "✅ 已從 Notion 移至垃圾桶", Toast.LENGTH_SHORT).show();
                                                callbacks.onEventsMutated();
                                            }

                                            @Override
                                            public void onError(String message) {
                                                delBtn.setEnabled(true);
                                                delBtn.setText("刪除");
                                                new AlertDialog.Builder(activity)
                                                        .setTitle("刪除失敗")
                                                        .setMessage(message)
                                                        .setPositiveButton("好", null)
                                                        .show();
                                            }
                                        });
                            })
                            .setNegativeButton("取消", null)
                            .show();
                });
                actions.addView(delBtn);
            }

            Button editNotionBtn = makeFlatButton(
                    activity, "✏️ 編輯並存回 Notion", palette.todayText, palette.todayFill);
            LinearLayout.LayoutParams eLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            eLp.setMarginStart(dp(activity, 8));
            editNotionBtn.setLayoutParams(eLp);
            editNotionBtn.setOnClickListener(v -> {
                dialog.dismiss();
                showEventEditorDialog(activity, ev, LocalDate.ofEpochDay(ev.startEpochDay), -1, palette, callbacks);
            });
            actions.addView(editNotionBtn);
        } else if (ev.eventId >= 0L) {
            Button delBtn = makeFlatButton(activity, "刪除", 0xFFD93025, palette.chipBg);
            delBtn.setOnClickListener(v -> {
                new AlertDialog.Builder(activity)
                        .setTitle("刪除行程")
                        .setMessage("確定要刪除「" + ev.title + "」並同步回來源日曆嗎？")
                        .setPositiveButton("刪除", (d, w) -> {
                            if (!CalendarDataRepository.hasWritePermission(activity)) {
                                callbacks.onRequestWritePermission();
                                return;
                            }
                            boolean ok = CalendarDataRepository.deleteSystemEvent(activity, ev.eventId);
                            if (ok) {
                                dialog.dismiss();
                                Toast.makeText(activity, "✅ 已刪除並同步回來源日曆", Toast.LENGTH_SHORT).show();
                                callbacks.onEventsMutated();
                            } else {
                                Toast.makeText(activity, "此日曆為唯讀訂閱日曆，無法刪除", Toast.LENGTH_SHORT).show();
                            }
                        })
                        .setNegativeButton("取消", null)
                        .show();
            });
            actions.addView(delBtn);

            Button editGoogleBtn = makeFlatButton(
                    activity, "✏️ 直接編輯儲存", palette.todayText, palette.todayFill);
            LinearLayout.LayoutParams eLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            eLp.setMarginStart(dp(activity, 8));
            editGoogleBtn.setLayoutParams(eLp);
            editGoogleBtn.setOnClickListener(v -> {
                dialog.dismiss();
                showEventEditorDialog(activity, ev, LocalDate.ofEpochDay(ev.startEpochDay), -1, palette, callbacks);
            });
            actions.addView(editGoogleBtn);
        } else {
            Button closeBtn = makeFlatButton(activity, "關閉", palette.textPrimary, palette.chipBg);
            closeBtn.setOnClickListener(v -> dialog.dismiss());
            actions.addView(closeBtn);
        }

        root.addView(actions);
        dialog.show();
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
     * Unified Two-Way Event Editor supporting:
     * - Creating new events directly into either Google Calendar OR Notion Database
     * - Editing existing Google Calendar events (single or recurring) and syncing back to Google
     * - Editing existing Notion Database events (title, date/time, status, notes) and writing back via Notion API
     */
    public static void showEventEditorDialog(
            Activity activity,
            CalendarEvent existingEvent,
            LocalDate fallbackDate,
            int initialHourOrNeg,
            AppTheme.Palette palette,
            Callbacks callbacks) {

        final boolean isEdit = (existingEvent != null);
        final boolean editingNotion = isEdit && existingEvent.isNotion();
        final ZoneId zone = ZoneId.systemDefault();

        // Check system event writability & recurrence metadata upfront when editing a Google/system event
        final CalendarDataRepository.SystemEventMeta sysMeta = (!isEdit || editingNotion)
                ? null
                : CalendarDataRepository.inspectSystemEvent(activity, existingEvent.eventId);

        if (isEdit && !editingNotion && sysMeta != null && !sysMeta.writable) {
            Toast.makeText(activity, "「" + existingEvent.calendarName + "」為唯讀訂閱日曆，無法直接編輯", Toast.LENGTH_LONG).show();
            return;
        }

        // Build target destinations list (writable Google Calendars + writable Notion Databases)
        final List<TargetDestination> destinations = new ArrayList<>();
        if (!isEdit || !editingNotion) {
            for (CalendarDataRepository.SystemCalendarItem c : CalendarDataRepository.loadSystemCalendars(activity)) {
                if (c.writable) destinations.add(new TargetDestination(c));
            }
        }
        if (!isEdit) {
            for (NotionManager.NotionSource ns : NotionManager.getWritableNotionSources(activity)) {
                destinations.add(new TargetDestination(activity, ns));
            }
        }

        // Initialize date & time state
        final LocalDate[] startDate = new LocalDate[1];
        final LocalDate[] endDate = new LocalDate[1];
        final LocalTime[] startTime = new LocalTime[1];
        final LocalTime[] endTime = new LocalTime[1];
        final boolean initialAllDay;

        if (isEdit) {
            initialAllDay = existingEvent.allDay;
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
            initialAllDay = false;
            int defHour = initialHourOrNeg >= 0
                    ? initialHourOrNeg
                    : (fallbackDate.equals(LocalDate.now())
                    ? Math.min(22, LocalTime.now().getHour() + 1) : 9);
            startDate[0] = fallbackDate;
            endDate[0] = fallbackDate;
            startTime[0] = LocalTime.of(defHour, 0);
            endTime[0] = LocalTime.of(Math.min(23, defHour + 1), defHour >= 23 ? 59 : 0);
        }

        ScrollView scroll = new ScrollView(activity);
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(activity, 20), dp(activity, 18), dp(activity, 20), dp(activity, 14));
        root.setBackgroundColor(palette.cardElevated);
        scroll.addView(root);

        TextView header = new TextView(activity);
        header.setText(isEdit
                ? (editingNotion ? "✏️ 編輯 Notion 行程" : "✏️ 編輯行事曆行程")
                : "＋ 新增行程（Google ／ Notion）");
        header.setTextSize(19f);
        header.setTypeface(Typeface.DEFAULT_BOLD);
        header.setTextColor(palette.textPrimary);
        root.addView(header);

        if (isEdit && editingNotion) {
            TextView badge = new TextView(activity);
            badge.setText("📓 來源：" + existingEvent.calendarName + "（儲存後將直接同步回 Notion）");
            badge.setTextSize(12.5f);
            badge.setTextColor(palette.textSecondary);
            badge.setPadding(0, dp(activity, 4), 0, 0);
            root.addView(badge);
        }

        final boolean[] userEditedTitle = {false};
        EditText titleInput = new EditText(activity);
        titleInput.setHint("輸入行程標題");
        titleInput.setTextColor(palette.textPrimary);
        titleInput.setHintTextColor(palette.textSecondary);
        titleInput.setSingleLine(true);
        if (isEdit) {
            titleInput.setText(existingEvent.title);
            titleInput.setSelection(titleInput.getText().length());
        }
        titleInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                userEditedTitle[0] = true;
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tLp.topMargin = dp(activity, 10);
        root.addView(titleInput, tLp);

        // Destination Spinner (shown when creating OR when editing a non-recurring Google event)
        Spinner destSpinner = new Spinner(activity);
        boolean showDestSpinner = !editingNotion && !destinations.isEmpty()
                && (!isEdit || (sysMeta != null && !sysMeta.recurring));
        int preselectIdx = 0;
        if (showDestSpinner) {
            CharSequence[] labels = new CharSequence[destinations.size()];
            for (int i = 0; i < destinations.size(); i++) {
                TargetDestination td = destinations.get(i);
                SpannableString sp = new SpannableString("● " + td.displayLabel);
                sp.setSpan(new ForegroundColorSpan(td.color), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                labels[i] = sp;
                if (isEdit && !td.isNotion && sysMeta != null && td.sysCal.id == sysMeta.calendarId) {
                    preselectIdx = i;
                }
            }
            ArrayAdapter<CharSequence> spinAdapter = new ArrayAdapter<>(
                    activity, android.R.layout.simple_spinner_dropdown_item, labels);
            destSpinner.setAdapter(spinAdapter);
            destSpinner.setSelection(preselectIdx);
            LinearLayout.LayoutParams spLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            spLp.topMargin = dp(activity, 8);
            root.addView(destSpinner, spLp);
        }

        Switch allDaySwitch = new Switch(activity);
        allDaySwitch.setText("全天行程");
        allDaySwitch.setChecked(initialAllDay);
        allDaySwitch.setTextColor(palette.textPrimary);
        allDaySwitch.setPadding(0, dp(activity, 10), 0, dp(activity, 6));
        root.addView(allDaySwitch);

        TextView computedDateHint = new TextView(activity);
        computedDateHint.setText("⚠️ 此 Notion 檢視的日期來自公式／關聯欄位（僅可修改標題與狀態）");
        computedDateHint.setTextSize(12f);
        computedDateHint.setTextColor(0xFFE65100);
        computedDateHint.setVisibility(View.GONE);
        root.addView(computedDateHint);

        Button btnStartDate = makeFlatButton(activity, "", palette.textPrimary, palette.chipBg);
        Button btnStartTime = makeFlatButton(activity, "", palette.textPrimary, palette.chipBg);
        Button btnEndDate = makeFlatButton(activity, "", palette.textPrimary, palette.chipBg);
        Button btnEndTime = makeFlatButton(activity, "", palette.textPrimary, palette.chipBg);

        Runnable refreshTimeButtons = () -> {
            boolean ad = allDaySwitch.isChecked();
            btnStartDate.setText(String.format(Locale.TAIWAN, "開始：%d/%02d/%02d (%s)",
                    startDate[0].getYear(), startDate[0].getMonthValue(), startDate[0].getDayOfMonth(),
                    DOW_NAMES[startDate[0].getDayOfWeek().getValue() - 1]));
            btnEndDate.setText(String.format(Locale.TAIWAN, "結束：%d/%02d/%02d (%s)",
                    endDate[0].getYear(), endDate[0].getMonthValue(), endDate[0].getDayOfMonth(),
                    DOW_NAMES[endDate[0].getDayOfWeek().getValue() - 1]));
            btnStartTime.setText(String.format(Locale.US, "%02d:%02d", startTime[0].getHour(), startTime[0].getMinute()));
            btnEndTime.setText(String.format(Locale.US, "%02d:%02d", endTime[0].getHour(), endTime[0].getMinute()));
            btnStartTime.setVisibility(ad ? View.GONE : View.VISIBLE);
            btnEndTime.setVisibility(ad ? View.GONE : View.VISIBLE);
        };

        allDaySwitch.setOnCheckedChangeListener((b, c) -> refreshTimeButtons.run());

        btnStartDate.setOnClickListener(v -> new DatePickerDialog(activity, (dpView, y, m, d) -> {
            startDate[0] = LocalDate.of(y, m + 1, d);
            if (endDate[0].isBefore(startDate[0])) endDate[0] = startDate[0];
            refreshTimeButtons.run();
        }, startDate[0].getYear(), startDate[0].getMonthValue() - 1, startDate[0].getDayOfMonth()).show());

        btnEndDate.setOnClickListener(v -> new DatePickerDialog(activity, (dpView, y, m, d) -> {
            LocalDate picked = LocalDate.of(y, m + 1, d);
            endDate[0] = picked.isBefore(startDate[0]) ? startDate[0] : picked;
            refreshTimeButtons.run();
        }, endDate[0].getYear(), endDate[0].getMonthValue() - 1, endDate[0].getDayOfMonth()).show());

        btnStartTime.setOnClickListener(v -> new TimePickerDialog(activity, (tp, h, min) -> {
            startTime[0] = LocalTime.of(h, min);
            if (endDate[0].equals(startDate[0]) && !endTime[0].isAfter(startTime[0])) {
                endTime[0] = startTime[0].plusHours(1);
            }
            refreshTimeButtons.run();
        }, startTime[0].getHour(), startTime[0].getMinute(), true).show());

        btnEndTime.setOnClickListener(v -> new TimePickerDialog(activity, (tp, h, min) -> {
            endTime[0] = LocalTime.of(h, min);
            refreshTimeButtons.run();
        }, endTime[0].getHour(), endTime[0].getMinute(), true).show());

        LinearLayout startRow = new LinearLayout(activity);
        startRow.setOrientation(LinearLayout.HORIZONTAL);
        startRow.addView(btnStartDate, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        stLp.setMarginStart(dp(activity, 6));
        startRow.addView(btnStartTime, stLp);
        root.addView(startRow);

        LinearLayout endRow = new LinearLayout(activity);
        endRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams erLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        erLp.topMargin = dp(activity, 6);
        endRow.setLayoutParams(erLp);
        endRow.addView(btnEndDate, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams etLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        etLp.setMarginStart(dp(activity, 6));
        endRow.addView(btnEndTime, etLp);
        root.addView(endRow);

        refreshTimeButtons.run();

        // Notion Status / Select Row (dynamically shown when target is Notion and DB has status/select)
        LinearLayout notionStatusContainer = new LinearLayout(activity);
        notionStatusContainer.setOrientation(LinearLayout.VERTICAL);
        notionStatusContainer.setVisibility(View.GONE);
        TextView notionStatusLabel = new TextView(activity);
        notionStatusLabel.setText("🏷️ Notion 狀態／分類");
        notionStatusLabel.setTextSize(12.5f);
        notionStatusLabel.setTypeface(Typeface.DEFAULT_BOLD);
        notionStatusLabel.setTextColor(palette.textSecondary);
        notionStatusLabel.setPadding(0, dp(activity, 10), 0, dp(activity, 2));
        notionStatusContainer.addView(notionStatusLabel);
        Spinner notionStatusSpinner = new Spinner(activity);
        notionStatusContainer.addView(notionStatusSpinner);
        root.addView(notionStatusContainer);

        // Location input (for Google / System calendars)
        EditText locInput = new EditText(activity);
        locInput.setHint("地點（選填）");
        locInput.setTextColor(palette.textPrimary);
        locInput.setHintTextColor(palette.textSecondary);
        locInput.setSingleLine(true);
        if (isEdit && !editingNotion) {
            locInput.setText(existingEvent.location);
        }
        LinearLayout.LayoutParams lLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lLp.topMargin = dp(activity, 8);
        root.addView(locInput, lLp);

        // Description / Notion Note input
        EditText descInput = new EditText(activity);
        descInput.setHint("備註說明（選填）");
        descInput.setTextColor(palette.textPrimary);
        descInput.setHintTextColor(palette.textSecondary);
        descInput.setMaxLines(4);
        if (isEdit && !editingNotion) {
            descInput.setText(existingEvent.description);
        }
        root.addView(descInput);

        final NotionManager.NotionEditableSchema[] activeNotionSchema = {null};
        final List<String> statusSpinnerValues = new ArrayList<>();

        // Helper to load Notion schema for either editing a Notion page or creating in a Notion DB
        final java.util.function.Consumer<String> loadNotionSchemaForKey = (srcKey) -> {
            activeNotionSchema[0] = null;
            notionStatusContainer.setVisibility(View.GONE);
            String pid = editingNotion ? existingEvent.notionPageId : null;
            NotionManager.fetchEditableSchemaAsync(activity, srcKey, pid,
                    new NotionManager.SchemaCallback() {
                        @Override
                        public void onReady(NotionManager.NotionEditableSchema schema) {
                            activeNotionSchema[0] = schema;
                            if (editingNotion && !userEditedTitle[0] && !TextUtils.isEmpty(schema.livePlainTitle)) {
                                titleInput.setText(schema.livePlainTitle);
                                titleInput.setSelection(titleInput.getText().length());
                                userEditedTitle[0] = false;
                            }
                            if (editingNotion && !TextUtils.isEmpty(schema.liveNoteText)
                                    && descInput.getText().toString().isEmpty()) {
                                descInput.setText(schema.liveNoteText);
                            }
                            if (!schema.dateEditable) {
                                computedDateHint.setVisibility(View.VISIBLE);
                                allDaySwitch.setEnabled(false);
                                btnStartDate.setEnabled(false);
                                btnEndDate.setEnabled(false);
                                btnStartTime.setEnabled(false);
                                btnEndTime.setEnabled(false);
                            } else {
                                computedDateHint.setVisibility(View.GONE);
                                allDaySwitch.setEnabled(true);
                                btnStartDate.setEnabled(true);
                                btnEndDate.setEnabled(true);
                                btnStartTime.setEnabled(true);
                                btnEndTime.setEnabled(true);
                            }

                            if (!TextUtils.isEmpty(schema.statusPropName) && !schema.statusOptions.isEmpty()) {
                                statusSpinnerValues.clear();
                                statusSpinnerValues.add(""); // keep unchanged / default
                                statusSpinnerValues.addAll(schema.statusOptions);

                                List<String> displayItems = new ArrayList<>();
                                displayItems.add("（保持預設／不變更）");
                                displayItems.addAll(schema.statusOptions);

                                notionStatusLabel.setText("🏷️ Notion 欄位：" + schema.statusPropName);
                                ArrayAdapter<String> stAdapter = new ArrayAdapter<>(
                                        activity, android.R.layout.simple_spinner_dropdown_item, displayItems);
                                notionStatusSpinner.setAdapter(stAdapter);

                                String targetStatus = !TextUtils.isEmpty(schema.liveStatus)
                                        ? schema.liveStatus
                                        : (editingNotion ? existingEvent.statusLabel : "");
                                int sel = 0;
                                for (int i = 1; i < statusSpinnerValues.size(); i++) {
                                    if (statusSpinnerValues.get(i).equals(targetStatus)) {
                                        sel = i;
                                        break;
                                    }
                                }
                                notionStatusSpinner.setSelection(sel);
                                notionStatusContainer.setVisibility(View.VISIBLE);
                            }
                        }

                        @Override
                        public void onError(String message) {
                        }
                    });
        };

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setView(scroll)
                .create();

        LinearLayout actionRow = new LinearLayout(activity);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setGravity(Gravity.END);
        actionRow.setPadding(0, dp(activity, 14), 0, 0);

        Button cancelBtn = makeFlatButton(activity, "取消", palette.textPrimary, palette.chipBg);
        cancelBtn.setOnClickListener(v -> dialog.dismiss());
        actionRow.addView(cancelBtn);

        Button saveBtn = makeFlatButton(
                activity,
                editingNotion ? "💾 儲存並寫回 Notion" : "💾 儲存並同步",
                palette.todayText,
                palette.todayFill);
        LinearLayout.LayoutParams svLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        svLp.setMarginStart(dp(activity, 8));
        saveBtn.setLayoutParams(svLp);

        if (editingNotion) {
            locInput.setVisibility(View.GONE);
            descInput.setHint("Notion 備註欄位（若資料庫含備註／說明文字欄將同步更新）");
            loadNotionSchemaForKey.accept(existingEvent.sourceKey);
        } else if (showDestSpinner) {
            destSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    TargetDestination td = destinations.get(position);
                    if (td.isNotion) {
                        locInput.setVisibility(View.GONE);
                        descInput.setHint("Notion 備註／頁面內文（選填）");
                        saveBtn.setText("💾 新增至 Notion");
                        loadNotionSchemaForKey.accept(td.notionSrc.sourceKey);
                    } else {
                        computedDateHint.setVisibility(View.GONE);
                        notionStatusContainer.setVisibility(View.GONE);
                        locInput.setVisibility(View.VISIBLE);
                        descInput.setHint("備註說明（選填）");
                        saveBtn.setText("💾 儲存並同步");
                    }
                }

                @Override
                public void onNothingSelected(AdapterView<?> parent) {
                }
            });
        }

        saveBtn.setOnClickListener(v -> {
            String title = titleInput.getText().toString().trim();
            if (title.isEmpty()) {
                Toast.makeText(activity, "請輸入行程標題", Toast.LENGTH_SHORT).show();
                return;
            }
            boolean ad = allDaySwitch.isChecked();
            ZonedDateTime bZdt = startDate[0].atTime(startTime[0]).atZone(zone);
            ZonedDateTime eZdt = endDate[0].atTime(endTime[0]).atZone(zone);
            if (!ad && !eZdt.isAfter(bZdt)) {
                eZdt = bZdt.plusHours(1);
            }
            final ZonedDateTime finalBeginZdt = bZdt;
            final ZonedDateTime finalEndZdt = eZdt;

            TargetDestination chosenDest = (showDestSpinner && !destinations.isEmpty())
                    ? destinations.get(Math.max(0, destSpinner.getSelectedItemPosition()))
                    : null;

            boolean targetIsNotion = editingNotion || (chosenDest != null && chosenDest.isNotion);

            if (targetIsNotion) {
                String chosenStatus = "";
                if (notionStatusContainer.getVisibility() == View.VISIBLE
                        && notionStatusSpinner.getSelectedItemPosition() > 0
                        && notionStatusSpinner.getSelectedItemPosition() < statusSpinnerValues.size()) {
                    chosenStatus = statusSpinnerValues.get(notionStatusSpinner.getSelectedItemPosition());
                }
                String noteVal = descInput.getText().toString().trim();
                saveBtn.setEnabled(false);
                saveBtn.setText("⏳ 同步至 Notion…");

                NotionManager.MutationCallback mutCb = new NotionManager.MutationCallback() {
                    @Override
                    public void onSuccess() {
                        dialog.dismiss();
                        Toast.makeText(activity,
                                editingNotion ? "✅ 已儲存並同步回 Notion！" : "✅ 已新增行程至 Notion！",
                                Toast.LENGTH_SHORT).show();
                        callbacks.onEventsMutated();
                    }

                    @Override
                    public void onError(String message) {
                        saveBtn.setEnabled(true);
                        saveBtn.setText(editingNotion ? "💾 儲存並寫回 Notion" : "💾 新增至 Notion");
                        new AlertDialog.Builder(activity)
                                .setTitle("寫入 Notion 失敗")
                                .setMessage(message)
                                .setPositiveButton("好", null)
                                .show();
                    }
                };

                if (editingNotion) {
                    NotionManager.updateNotionEventAsync(
                            activity,
                            existingEvent.sourceKey,
                            existingEvent.notionPageId,
                            activeNotionSchema[0],
                            title, ad, startDate[0], endDate[0],
                            finalBeginZdt, finalEndZdt, chosenStatus, noteVal, mutCb);
                } else {
                    NotionManager.createNotionEventAsync(
                            activity,
                            chosenDest.notionSrc.sourceKey,
                            activeNotionSchema[0],
                            title, ad, startDate[0], endDate[0],
                            finalBeginZdt, finalEndZdt, chosenStatus, noteVal, mutCb);
                }
                return;
            }

            // Google / System Calendar Create or Edit
            if (!CalendarDataRepository.hasWritePermission(activity)) {
                callbacks.onRequestWritePermission();
                return;
            }

            String locVal = locInput.getText().toString().trim();
            String descVal = descInput.getText().toString().trim();
            long beginMs = finalBeginZdt.toInstant().toEpochMilli();
            long endMs = finalEndZdt.toInstant().toEpochMilli();

            if (!isEdit) {
                if (chosenDest == null || chosenDest.sysCal == null) {
                    // Fallback to system calendar intent if no writable local calendar found
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

                long insertedId = CalendarDataRepository.insertSystemEvent(
                        activity, chosenDest.sysCal.id, title, locVal, descVal,
                        ad, startDate[0], endDate[0], beginMs, endMs);
                if (insertedId >= 0) {
                    dialog.dismiss();
                    Toast.makeText(activity, "✅ 已新增並同步回 Google／系統日曆", Toast.LENGTH_SHORT).show();
                    callbacks.onEventsMutated();
                } else {
                    Toast.makeText(activity, "新增失敗，請確認日曆寫入權限", Toast.LENGTH_SHORT).show();
                }
                return;
            }

            // Editing an existing System / Google Calendar event
            final long targetCalId = (chosenDest != null && chosenDest.sysCal != null)
                    ? chosenDest.sysCal.id
                    : (sysMeta != null ? sysMeta.calendarId : -1L);
            final boolean isRec = sysMeta != null && sysMeta.recurring;

            if (isRec) {
                String[] scopeChoices = {"只修改這一次行程", "修改整個重複系列"};
                new AlertDialog.Builder(activity)
                        .setTitle("這是重複性行程")
                        .setItems(scopeChoices, (scDialog, which) -> {
                            boolean onlyThis = (which == 0);
                            boolean ok = CalendarDataRepository.updateSystemEvent(
                                    activity, existingEvent.eventId, targetCalId,
                                    existingEvent.beginMillis, existingEvent.allDay,
                                    true, onlyThis, title, locVal, descVal,
                                    ad, startDate[0], endDate[0], beginMs, endMs);
                            if (ok) {
                                dialog.dismiss();
                                Toast.makeText(activity, "✅ 已儲存並同步回 Google／系統日曆", Toast.LENGTH_SHORT).show();
                                callbacks.onEventsMutated();
                            } else {
                                Toast.makeText(activity, "更新失敗", Toast.LENGTH_SHORT).show();
                            }
                        })
                        .show();
            } else {
                boolean ok = CalendarDataRepository.updateSystemEvent(
                        activity, existingEvent.eventId, targetCalId,
                        existingEvent.beginMillis, existingEvent.allDay,
                        false, false, title, locVal, descVal,
                        ad, startDate[0], endDate[0], beginMs, endMs);
                if (ok) {
                    dialog.dismiss();
                    Toast.makeText(activity, "✅ 已儲存並同步回 Google／系統日曆", Toast.LENGTH_SHORT).show();
                    callbacks.onEventsMutated();
                } else {
                    Toast.makeText(activity, "更新失敗，此行程可能屬於唯讀日曆", Toast.LENGTH_SHORT).show();
                }
            }
        });
        actionRow.addView(saveBtn);

        root.addView(actionRow);
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
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
