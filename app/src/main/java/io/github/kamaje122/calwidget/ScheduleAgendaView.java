package io.github.kamaje122.calwidget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Google-Calendar-style Schedule / Agenda View ("時間表", ref Screenshot_20261126-152112.png):
 * - Left gutter showing Weekday + Date + Lunar day on the first event of each date (Today circled)
 * - Week range dividers (e.g. "11月29日至12月5日")
 * - Full-width rounded colored event cards with title + time/location/Notion status
 * - Live current-time horizontal indicator line + dot on Today
 */
public final class ScheduleAgendaView extends FrameLayout {

    public interface Listener {
        void onEventClicked(CalendarEvent event);
        void onEmptyDayClicked(LocalDate date);
    }

    private static final String[] DOW_LABELS = {
            "週一", "週二", "週三", "週四", "週五", "週六", "週日"
    };

    private static final int TYPE_WEEK_HEADER = 0;
    private static final int TYPE_NOW_LINE = 1;
    private static final int TYPE_EVENT_ROW = 2;
    private static final int TYPE_EMPTY_TODAY = 3;

    private static final class RowItem {
        final int type;
        final LocalDate date;
        final boolean showDateGutter;
        final String headerText;
        final CalendarEvent event;

        RowItem(int type, LocalDate date, boolean showDateGutter, String headerText, CalendarEvent event) {
            this.type = type;
            this.date = date;
            this.showDateGutter = showDateGutter;
            this.headerText = headerText;
            this.event = event;
        }
    }

    private final ListView listView;
    private final AgendaAdapter adapter;
    private final List<RowItem> rows = new ArrayList<>();
    private AppTheme.Palette palette;
    private Listener listener;

    public ScheduleAgendaView(Context context) {
        super(context);
        palette = AppTheme.of(context);
        listView = new ListView(context);
        listView.setDivider(null);
        listView.setDividerHeight(0);
        listView.setClipToPadding(false);
        listView.setPadding(0, dp(6), 0, dp(88));
        adapter = new AgendaAdapter();
        listView.setAdapter(adapter);
        addView(listView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void setData(
            LocalDate rangeStart,
            LocalDate rangeEnd,
            LocalDate scrollTargetDate,
            boolean startMonday,
            AppTheme.Palette themePalette,
            Map<Long, List<CalendarEvent>> groupedEvents) {
        this.palette = themePalette;
        setBackgroundColor(palette.bgAppBar);
        listView.setBackgroundColor(palette.bgAppBar);

        rows.clear();
        LocalDate today = LocalDate.now();
        long nowMillis = System.currentTimeMillis();
        DayOfWeek weekStartDow = startMonday ? DayOfWeek.MONDAY : DayOfWeek.SUNDAY;

        LocalDate lastWeekStart = null;
        int scrollIndex = -1;
        long targetEpoch = scrollTargetDate.toEpochDay();

        for (LocalDate d = rangeStart; !d.isAfter(rangeEnd); d = d.plusDays(1)) {
            long epoch = d.toEpochDay();
            List<CalendarEvent> dayEvents = groupedEvents.get(epoch);
            boolean isToday = d.equals(today);
            boolean hasEvents = dayEvents != null && !dayEvents.isEmpty();

            if (!hasEvents && !isToday && !d.equals(scrollTargetDate)) {
                continue;
            }

            LocalDate ws = d.with(TemporalAdjusters.previousOrSame(weekStartDow));
            if (!ws.equals(lastWeekStart)) {
                if (lastWeekStart != null) {
                    LocalDate we = ws.plusDays(6);
                    String label = String.format(Locale.TAIWAN, "%d月%d日至%d月%d日",
                            ws.getMonthValue(), ws.getDayOfMonth(),
                            we.getMonthValue(), we.getDayOfMonth());
                    rows.add(new RowItem(TYPE_WEEK_HEADER, ws, false, label, null));
                }
                lastWeekStart = ws;
            }

            if (scrollIndex < 0 && epoch >= targetEpoch) {
                scrollIndex = rows.size();
            }

            if (!hasEvents) {
                rows.add(new RowItem(TYPE_EMPTY_TODAY, d, true,
                        isToday ? "今天目前沒有行程" : "此日沒有行程", null));
                if (isToday) {
                    rows.add(new RowItem(TYPE_NOW_LINE, d, false, "", null));
                }
                continue;
            }

            boolean insertedNowLine = !isToday;
            for (int i = 0; i < dayEvents.size(); i++) {
                CalendarEvent ev = dayEvents.get(i);
                if (!insertedNowLine && !ev.allDay && ev.beginMillis >= nowMillis) {
                    rows.add(new RowItem(TYPE_NOW_LINE, d, i == 0, "", null));
                    insertedNowLine = true;
                    rows.add(new RowItem(TYPE_EVENT_ROW, d, false, "", ev));
                } else {
                    rows.add(new RowItem(TYPE_EVENT_ROW, d, i == 0, "", ev));
                }
            }
            if (!insertedNowLine) {
                rows.add(new RowItem(TYPE_NOW_LINE, d, false, "", null));
            }
        }

        adapter.notifyDataSetChanged();
        if (scrollIndex >= 0 && scrollIndex < rows.size()) {
            final int targetIdx = scrollIndex;
            listView.post(() -> listView.setSelectionFromTop(targetIdx, dp(8)));
        }
    }

    private final class AgendaAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return rows.size();
        }

        @Override
        public Object getItem(int position) {
            return rows.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public int getViewTypeCount() {
            return 4;
        }

        @Override
        public int getItemViewType(int position) {
            return rows.get(position).type;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            RowItem item = rows.get(position);
            Context ctx = parent.getContext();

            if (item.type == TYPE_WEEK_HEADER) {
                TextView tv = convertView instanceof TextView ? (TextView) convertView : new TextView(ctx);
                tv.setPadding(dp(64), dp(16), dp(16), dp(6));
                tv.setTextSize(12.5f);
                tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                tv.setTextColor(palette.textSecondary);
                tv.setText(item.headerText);
                return tv;
            }

            if (item.type == TYPE_NOW_LINE) {
                LinearLayout wrap = new LinearLayout(ctx);
                wrap.setOrientation(LinearLayout.HORIZONTAL);
                wrap.setGravity(Gravity.CENTER_VERTICAL);
                wrap.setPadding(dp(8), dp(2), dp(12), dp(2));
                wrap.addView(buildDateGutter(ctx, item.date, item.showDateGutter));
                wrap.addView(new NowLineBar(ctx, palette.nowIndicator),
                        new LinearLayout.LayoutParams(0, dp(14), 1f));
                return wrap;
            }

            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.TOP);
            row.setPadding(dp(8), item.showDateGutter ? dp(8) : dp(3), dp(12), dp(3));

            row.addView(buildDateGutter(ctx, item.date, item.showDateGutter));

            if (item.type == TYPE_EMPTY_TODAY) {
                TextView emptyCard = new TextView(ctx);
                emptyCard.setText(item.headerText + "（點此新增）");
                emptyCard.setTextSize(14f);
                emptyCard.setTextColor(palette.textSecondary);
                emptyCard.setPadding(dp(14), dp(12), dp(14), dp(12));
                GradientDrawable bg = new GradientDrawable();
                bg.setCornerRadius(dp(12));
                bg.setColor(palette.bgCell);
                bg.setStroke(dp(1), palette.divider);
                emptyCard.setBackground(bg);
                emptyCard.setOnClickListener(v -> {
                    if (listener != null) listener.onEmptyDayClicked(item.date);
                });
                row.addView(emptyCard, new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                return row;
            }

            CalendarEvent ev = item.event;
            LinearLayout card = new LinearLayout(ctx);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(14), dp(9), dp(14), dp(9));
            GradientDrawable cardBg = new GradientDrawable();
            cardBg.setCornerRadius(dp(12));
            cardBg.setColor(ev.color);
            card.setBackground(cardBg);

            int mainColor = AppTheme.textColorOnPill(ev.color);
            int subColor = AppTheme.subTextColorOnPill(ev.color);

            TextView titleTv = new TextView(ctx);
            titleTv.setText(ev.title);
            titleTv.setTextSize(14.8f);
            titleTv.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            titleTv.setTextColor(mainColor);
            titleTv.setMaxLines(2);
            titleTv.setEllipsize(TextUtils.TruncateAt.END);
            card.addView(titleTv);

            String sub = ev.formatAgendaSubtitle(ZoneId.systemDefault());
            if (!TextUtils.isEmpty(sub)) {
                TextView subTv = new TextView(ctx);
                subTv.setText(sub);
                subTv.setTextSize(12.5f);
                subTv.setTextColor(subColor);
                subTv.setMaxLines(1);
                subTv.setEllipsize(TextUtils.TruncateAt.END);
                LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                sLp.topMargin = dp(2);
                card.addView(subTv, sLp);
            }

            card.setOnClickListener(v -> {
                if (listener != null) listener.onEventClicked(ev);
            });

            row.addView(card, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            return row;
        }

        private View buildDateGutter(Context ctx, LocalDate date, boolean visible) {
            LinearLayout gutter = new LinearLayout(ctx);
            gutter.setOrientation(LinearLayout.VERTICAL);
            gutter.setGravity(Gravity.CENTER_HORIZONTAL);
            LinearLayout.LayoutParams gLp = new LinearLayout.LayoutParams(
                    dp(52), ViewGroup.LayoutParams.WRAP_CONTENT);
            gLp.setMarginEnd(dp(4));
            gutter.setLayoutParams(gLp);

            if (!visible) {
                return gutter;
            }

            boolean isToday = date.equals(LocalDate.now());

            TextView dow = new TextView(ctx);
            dow.setText(DOW_LABELS[date.getDayOfWeek().getValue() - 1]);
            dow.setTextSize(11.5f);
            dow.setTypeface(Typeface.DEFAULT_BOLD);
            dow.setTextColor(palette.textPrimary);
            dow.setGravity(Gravity.CENTER);
            gutter.addView(dow);

            LinearLayout circleBox = new LinearLayout(ctx);
            circleBox.setOrientation(LinearLayout.VERTICAL);
            circleBox.setGravity(Gravity.CENTER);
            int circleSize = dp(34);
            LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(circleSize, circleSize);
            cLp.topMargin = dp(2);
            circleBox.setLayoutParams(cLp);

            if (isToday) {
                GradientDrawable badge = new GradientDrawable();
                badge.setShape(GradientDrawable.OVAL);
                badge.setColor(palette.todayFill);
                circleBox.setBackground(badge);
            }

            TextView dayNum = new TextView(ctx);
            dayNum.setText(String.valueOf(date.getDayOfMonth()));
            dayNum.setTextSize(14.5f);
            dayNum.setTypeface(Typeface.DEFAULT_BOLD);
            dayNum.setIncludeFontPadding(false);
            dayNum.setTextColor(isToday ? palette.todayText : palette.textPrimary);
            dayNum.setGravity(Gravity.CENTER);
            circleBox.addView(dayNum);

            TextView lunar = new TextView(ctx);
            lunar.setText(LunarCalendarHelper.getDayShort(date));
            lunar.setTextSize(9.5f);
            lunar.setIncludeFontPadding(false);
            lunar.setTextColor(isToday ? palette.todayText : palette.textSecondary);
            lunar.setGravity(Gravity.CENTER);
            circleBox.addView(lunar);

            gutter.addView(circleBox);
            return gutter;
        }
    }

    private static final class NowLineBar extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        NowLineBar(Context context, int color) {
            super(context);
            p.setColor(color);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float cy = getHeight() * 0.5f;
            float d = getResources().getDisplayMetrics().density;
            p.setStrokeWidth(1.8f * d);
            canvas.drawCircle(5f * d, cy, 4.5f * d, p);
            canvas.drawLine(5f * d, cy, getWidth(), cy, p);
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
