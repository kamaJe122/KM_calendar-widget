package io.github.kamaje122.calwidget;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.ActivityNotFoundException;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
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
 * Rich dialogs & sheets for the KM Calendar main app:
 * 1) Day Events Overview Dialog (from Month grid tap)
 * 2) Event Detail Card (for both Google Calendar & Notion events)
 * 3) Native Quick-Create Event Form (+ shortcut to system Calendar editor)
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

        // Color header pill + Title
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

        // Date & Time
        LocalDate startD = LocalDate.ofEpochDay(ev.startEpochDay);
        ZoneId zone = ZoneId.systemDefault();
        String dateLine = String.format(Locale.TAIWAN, "%d年%d月%d日 %s（%s）\n⏰ %s",
                startD.getYear(), startD.getMonthValue(), startD.getDayOfMonth(),
                DOW_NAMES[startD.getDayOfWeek().getValue() - 1],
                LunarCalendarHelper.getFullDateLabel(startD),
                ev.formatTimeRange(zone));
        addDetailRow(activity, root, dateLine, palette.textPrimary, 14.5f, 12);

        // Calendar Source & Status
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

        // Location
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

        // Description
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

        // Action Buttons
        LinearLayout actions = new LinearLayout(activity);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.END);
        actions.setPadding(0, dp(activity, 18), 0, 0);

        if (ev.isNotion() && !TextUtils.isEmpty(ev.notionUrl)) {
            Button closeBtn = makeFlatButton(activity, "關閉", palette.textPrimary, palette.chipBg);
            closeBtn.setOnClickListener(v -> dialog.dismiss());
            actions.addView(closeBtn);

            Button openNotion = makeFlatButton(activity, "📓 在 Notion 開啟頁面", palette.todayText, palette.todayFill);
            LinearLayout.LayoutParams oLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            oLp.setMarginStart(dp(activity, 8));
            openNotion.setLayoutParams(oLp);
            openNotion.setOnClickListener(v -> {
                dialog.dismiss();
                try {
                    activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(ev.notionUrl)));
                } catch (Exception ignored) {
                }
            });
            actions.addView(openNotion);
        } else if (ev.eventId >= 0L) {
            Button delBtn = makeFlatButton(activity, "刪除", 0xFFD93025, palette.chipBg);
            delBtn.setOnClickListener(v -> {
                new AlertDialog.Builder(activity)
                        .setTitle("刪除行程")
                        .setMessage("確定要刪除「" + ev.title + "」嗎？")
                        .setPositiveButton("刪除", (d, w) -> {
                            if (!CalendarDataRepository.hasWritePermission(activity)) {
                                callbacks.onRequestWritePermission();
                                return;
                            }
                            boolean ok = CalendarDataRepository.deleteSystemEvent(activity, ev.eventId);
                            if (ok) {
                                dialog.dismiss();
                                Toast.makeText(activity, "已刪除行程", Toast.LENGTH_SHORT).show();
                                callbacks.onEventsMutated();
                            } else {
                                Toast.makeText(activity, "無法刪除此唯讀日曆行程", Toast.LENGTH_SHORT).show();
                            }
                        })
                        .setNegativeButton("取消", null)
                        .show();
            });
            actions.addView(delBtn);

            Button editSys = makeFlatButton(activity, "✏️ 在系統日曆編輯", palette.todayText, palette.todayFill);
            LinearLayout.LayoutParams eLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            eLp.setMarginStart(dp(activity, 8));
            editSys.setLayoutParams(eLp);
            editSys.setOnClickListener(v -> {
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
            actions.addView(editSys);
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

        List<CalendarDataRepository.SystemCalendarItem> allCals =
                CalendarDataRepository.loadSystemCalendars(activity);
        List<CalendarDataRepository.SystemCalendarItem> writableCals = new ArrayList<>();
        for (CalendarDataRepository.SystemCalendarItem c : allCals) {
            if (c.writable) writableCals.add(c);
        }

        ZoneId zone = ZoneId.systemDefault();
        int defHour = initialHourOrNeg >= 0
                ? initialHourOrNeg
                : (initialDate.equals(LocalDate.now())
                ? Math.min(22, LocalTime.now().getHour() + 1) : 9);

        final LocalDate[] startDate = {initialDate};
        final LocalDate[] endDate = {initialDate};
        final LocalTime[] startTime = {LocalTime.of(defHour, 0)};
        final LocalTime[] endTime = {LocalTime.of(Math.min(23, defHour + 1), defHour >= 23 ? 59 : 0)};

        ScrollView scroll = new ScrollView(activity);
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(activity, 20), dp(activity, 18), dp(activity, 20), dp(activity, 14));
        root.setBackgroundColor(palette.cardElevated);
        scroll.addView(root);

        TextView header = new TextView(activity);
        header.setText("＋ 新增行程");
        header.setTextSize(19f);
        header.setTypeface(Typeface.DEFAULT_BOLD);
        header.setTextColor(palette.textPrimary);
        root.addView(header);

        EditText titleInput = new EditText(activity);
        titleInput.setHint("輸入行程標題");
        titleInput.setTextColor(palette.textPrimary);
        titleInput.setHintTextColor(palette.textSecondary);
        titleInput.setSingleLine(true);
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tLp.topMargin = dp(activity, 10);
        root.addView(titleInput, tLp);

        // Calendar selector
        Spinner calSpinner = new Spinner(activity);
        if (!writableCals.isEmpty()) {
            CharSequence[] labels = new CharSequence[writableCals.size()];
            for (int i = 0; i < writableCals.size(); i++) {
                CalendarDataRepository.SystemCalendarItem c = writableCals.get(i);
                String raw = "● " + c.displayName + (TextUtils.isEmpty(c.accountName) ? "" : " (" + c.accountName + ")");
                SpannableString sp = new SpannableString(raw);
                sp.setSpan(new ForegroundColorSpan(c.color), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                labels[i] = sp;
            }
            ArrayAdapter<CharSequence> spinAdapter = new ArrayAdapter<>(
                    activity, android.R.layout.simple_spinner_dropdown_item, labels);
            calSpinner.setAdapter(spinAdapter);
            LinearLayout.LayoutParams spLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            spLp.topMargin = dp(activity, 8);
            root.addView(calSpinner, spLp);
        }

        Switch allDaySwitch = new Switch(activity);
        allDaySwitch.setText("全天行程");
        allDaySwitch.setTextColor(palette.textPrimary);
        allDaySwitch.setPadding(0, dp(activity, 10), 0, dp(activity, 6));
        root.addView(allDaySwitch);

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

        EditText locInput = new EditText(activity);
        locInput.setHint("地點（選填）");
        locInput.setTextColor(palette.textPrimary);
        locInput.setHintTextColor(palette.textSecondary);
        locInput.setSingleLine(true);
        LinearLayout.LayoutParams lLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lLp.topMargin = dp(activity, 8);
        root.addView(locInput, lLp);

        EditText descInput = new EditText(activity);
        descInput.setHint("備註說明（選填）");
        descInput.setTextColor(palette.textPrimary);
        descInput.setHintTextColor(palette.textSecondary);
        descInput.setMaxLines(3);
        root.addView(descInput);

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setView(scroll)
                .create();

        LinearLayout actionRow = new LinearLayout(activity);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setGravity(Gravity.END);
        actionRow.setPadding(0, dp(activity, 14), 0, 0);

        Button sysEditorBtn = makeFlatButton(activity, "系統完整編輯器", palette.textPrimary, palette.chipBg);
        sysEditorBtn.setOnClickListener(v -> {
            dialog.dismiss();
            ZonedDateTime bZdt = startDate[0].atTime(startTime[0]).atZone(zone);
            ZonedDateTime eZdt = endDate[0].atTime(endTime[0]).atZone(zone);
            Intent insert = new Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)
                    .putExtra(CalendarContract.Events.TITLE, titleInput.getText().toString().trim())
                    .putExtra(CalendarContract.Events.EVENT_LOCATION, locInput.getText().toString().trim())
                    .putExtra(CalendarContract.Events.DESCRIPTION, descInput.getText().toString().trim())
                    .putExtra(CalendarContract.Events.ALL_DAY, allDaySwitch.isChecked())
                    .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, bZdt.toInstant().toEpochMilli())
                    .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, eZdt.toInstant().toEpochMilli());
            try {
                activity.startActivity(insert);
            } catch (ActivityNotFoundException ignored) {
            }
        });
        actionRow.addView(sysEditorBtn);

        Button saveBtn = makeFlatButton(activity, "儲存行程", palette.todayText, palette.todayFill);
        LinearLayout.LayoutParams svLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        svLp.setMarginStart(dp(activity, 8));
        saveBtn.setLayoutParams(svLp);
        saveBtn.setOnClickListener(v -> {
            String title = titleInput.getText().toString().trim();
            if (title.isEmpty()) {
                Toast.makeText(activity, "請輸入行程標題", Toast.LENGTH_SHORT).show();
                return;
            }
            if (!CalendarDataRepository.hasWritePermission(activity)) {
                callbacks.onRequestWritePermission();
                return;
            }
            if (writableCals.isEmpty()) {
                Toast.makeText(activity, "找不到可寫入的系統行事曆，將開啟系統編輯器", Toast.LENGTH_SHORT).show();
                sysEditorBtn.performClick();
                return;
            }
            int idx = Math.max(0, calSpinner.getSelectedItemPosition());
            long calId = writableCals.get(idx).id;
            long beginMs = startDate[0].atTime(startTime[0]).atZone(zone).toInstant().toEpochMilli();
            long endMs = endDate[0].atTime(endTime[0]).atZone(zone).toInstant().toEpochMilli();

            long insertedId = CalendarDataRepository.insertSystemEvent(
                    activity, calId, title,
                    locInput.getText().toString().trim(),
                    descInput.getText().toString().trim(),
                    allDaySwitch.isChecked(),
                    startDate[0], endDate[0], beginMs, endMs);
            if (insertedId >= 0) {
                dialog.dismiss();
                Toast.makeText(activity, "✅ 已新增行程", Toast.LENGTH_SHORT).show();
                callbacks.onEventsMutated();
            } else {
                Toast.makeText(activity, "新增失敗，請改用系統完整編輯器", Toast.LENGTH_SHORT).show();
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
