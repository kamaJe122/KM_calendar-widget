package io.github.kamaje122.calwidget;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Full-screen Google-Calendar-style Month Grid View ("月"):
 * - Configurable Sunday/Monday week start
 * - Gregorian + Lunar day header per cell ("1 (廿三)", Today highlighted in dark pill "26 (十八)")
 * - Stacked colored event pills with luminance-matched text and "..." overflow indicator
 * - Smooth horizontal swipe paging to previous / next month
 */
public final class MonthCalendarView extends View {

    public interface Listener {
        void onDayClicked(LocalDate date, List<CalendarEvent> events);
        void onMonthSwiped(int deltaMonths);
    }

    private static final String[] WEEKDAYS_SUN = {"週日", "週一", "週二", "週三", "週四", "週五", "週六"};
    private static final String[] WEEKDAYS_MON = {"週一", "週二", "週三", "週四", "週五", "週六", "週日"};

    private YearMonth currentMonth = YearMonth.now();
    private LocalDate selectedDate = LocalDate.now();
    private boolean startOnMonday = false;
    private AppTheme.Palette palette;
    private Map<Long, List<CalendarEvent>> eventsByDay = new HashMap<>();
    private Listener listener;

    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cellPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint weekdayPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint dayLabelPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint pillTextPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF tmpRect = new RectF();

    private float dragTranslationX = 0f;
    private boolean swipingHorizontally = false;
    private float downX;
    private float downY;
    private final GestureDetector gestureDetector;

    public MonthCalendarView(Context context) {
        super(context);
        palette = AppTheme.of(context);
        strokePaint.setStyle(Paint.Style.STROKE);

        weekdayPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        weekdayPaint.setTextAlign(Paint.Align.CENTER);

        dayLabelPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        dayLabelPaint.setTextAlign(Paint.Align.CENTER);

        pillTextPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));

        gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                handleTap(e.getX(), e.getY());
                return true;
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                if (e1 == null || e2 == null) return false;
                float dx = e2.getX() - e1.getX();
                float dy = e2.getY() - e1.getY();
                if (Math.abs(dx) > dp(42) && Math.abs(dx) > Math.abs(dy) * 1.2f
                        && Math.abs(velocityX) > dp(180)) {
                    completeMonthSwipe(dx < 0 ? 1 : -1);
                    return true;
                }
                return false;
            }
        });
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void setData(
            YearMonth month,
            LocalDate selected,
            boolean startMonday,
            AppTheme.Palette themePalette,
            Map<Long, List<CalendarEvent>> groupedEvents) {
        this.currentMonth = month;
        this.selectedDate = selected;
        this.startOnMonday = startMonday;
        this.palette = themePalette;
        this.eventsByDay = groupedEvents != null ? groupedEvents : Collections.emptyMap();
        invalidate();
    }

    public LocalDate getGridStartDate() {
        DayOfWeek firstDow = startOnMonday ? DayOfWeek.MONDAY : DayOfWeek.SUNDAY;
        return currentMonth.atDay(1).with(TemporalAdjusters.previousOrSame(firstDow));
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        boolean handled = gestureDetector.onTouchEvent(event);
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            downX = event.getX();
            downY = event.getY();
            swipingHorizontally = false;
        } else if (action == MotionEvent.ACTION_MOVE) {
            float dx = event.getX() - downX;
            float dy = event.getY() - downY;
            if (!swipingHorizontally && Math.abs(dx) > dp(14) && Math.abs(dx) > Math.abs(dy) * 1.25f) {
                swipingHorizontally = true;
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
            }
            if (swipingHorizontally) {
                dragTranslationX = dx * 0.55f;
                invalidate();
                return true;
            }
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            if (swipingHorizontally) {
                float dx = event.getX() - downX;
                swipingHorizontally = false;
                if (Math.abs(dx) > getWidth() * 0.22f) {
                    completeMonthSwipe(dx < 0 ? 1 : -1);
                } else {
                    animateBackToZero();
                }
                return true;
            }
        }
        return handled || super.onTouchEvent(event);
    }

    private void completeMonthSwipe(int deltaMonths) {
        swipingHorizontally = false;
        dragTranslationX = 0f;
        invalidate();
        if (listener != null) {
            listener.onMonthSwiped(deltaMonths);
        }
    }

    private void animateBackToZero() {
        ValueAnimator anim = ValueAnimator.ofFloat(dragTranslationX, 0f);
        anim.setDuration(160L);
        anim.setInterpolator(new DecelerateInterpolator());
        anim.addUpdateListener(a -> {
            dragTranslationX = (float) a.getAnimatedValue();
            invalidate();
        });
        anim.start();
    }

    private void handleTap(float x, float y) {
        int w = getWidth();
        int h = getHeight();
        float headerH = dp(30);
        if (w <= 0 || h <= headerH || y < headerH) return;

        int col = Math.min(6, Math.max(0, (int) ((x / w) * 7f)));
        int row = Math.min(5, Math.max(0, (int) (((y - headerH) / (h - headerH)) * 6f)));
        LocalDate tapped = getGridStartDate().plusDays(row * 7L + col);
        selectedDate = tapped;
        invalidate();

        if (listener != null) {
            List<CalendarEvent> list = eventsByDay.get(tapped.toEpochDay());
            listener.onDayClicked(tapped, list != null ? new ArrayList<>(list) : Collections.emptyList());
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        bgPaint.setColor(palette.bgCanvas);
        canvas.drawRect(0, 0, w, h, bgPaint);

        float headerH = dp(30);
        bgPaint.setColor(palette.bgAppBar);
        canvas.drawRect(0, 0, w, headerH, bgPaint);

        // Draw weekday labels
        String[] weekdays = startOnMonday ? WEEKDAYS_MON : WEEKDAYS_SUN;
        weekdayPaint.setTextSize(sp(12f));
        weekdayPaint.setColor(palette.textPrimary);
        float colW = w / 7f;
        float weekdayBaseline = headerH * 0.66f;
        for (int c = 0; c < 7; c++) {
            canvas.drawText(weekdays[c], c * colW + colW * 0.5f, weekdayBaseline, weekdayPaint);
        }

        canvas.save();
        canvas.translate(dragTranslationX, 0f);

        float gridTop = headerH + dp(2);
        float rowH = (h - gridTop - dp(2)) / 6f;
        float cellGap = dp(1.4f);
        float cellRadius = dp(6f);

        LocalDate today = LocalDate.now();
        LocalDate gridStart = getGridStartDate();

        dayLabelPaint.setTextSize(sp(10.8f));
        pillTextPaint.setTextSize(sp(10.2f));
        float pillH = dp(16.2f);
        float pillGap = dp(2.0f);
        float dayHeaderZoneH = dp(23f);

        for (int r = 0; r < 6; r++) {
            float top = gridTop + r * rowH;
            float bottom = top + rowH;

            for (int c = 0; c < 7; c++) {
                LocalDate d = gridStart.plusDays(r * 7L + c);
                boolean inMonth = d.getMonthValue() == currentMonth.getMonthValue()
                        && d.getYear() == currentMonth.getYear();
                boolean isToday = d.equals(today);
                boolean isPast = d.isBefore(today);
                boolean isSelected = d.equals(selectedDate);

                float left = c * colW;
                float right = left + colW;

                // Rounded day cell card: past days (yesterday and earlier) use muted card background;
                // today and all future days (including upcoming next-month days) stay crisp & bright.
                tmpRect.set(left + cellGap, top + cellGap, right - cellGap, bottom - cellGap);
                cellPaint.setColor(isPast ? palette.bgCellOutsideMonth : palette.bgCell);
                canvas.drawRoundRect(tmpRect, cellRadius, cellRadius, cellPaint);

                if (isSelected && !isToday) {
                    strokePaint.setColor(palette.selectedStroke);
                    strokePaint.setStrokeWidth(dp(1.4f));
                    canvas.drawRoundRect(tmpRect, cellRadius, cellRadius, strokePaint);
                }

                // Day + Lunar text: e.g. "26 (十八)", or "12/1 (廿三)" on month boundary outside currentMonth
                String dayPrefix = (!inMonth && d.getDayOfMonth() == 1)
                        ? (d.getMonthValue() + "/" + d.getDayOfMonth())
                        : String.valueOf(d.getDayOfMonth());
                String dayLabel = dayPrefix + " (" + LunarCalendarHelper.getDayShort(d) + ")";
                float centerX = (left + right) * 0.5f;
                float textBaseline = top + dp(16.2f);

                if (isToday) {
                    float tw = dayLabelPaint.measureText(dayLabel);
                    float badgePadX = dp(5f);
                    float badgeTop = top + dp(3.5f);
                    float badgeBottom = badgeTop + dp(17.5f);
                    tmpRect.set(
                            Math.max(left + dp(2f), centerX - tw * 0.5f - badgePadX),
                            badgeTop,
                            Math.min(right - dp(2f), centerX + tw * 0.5f + badgePadX),
                            badgeBottom
                    );
                    pillPaint.setColor(palette.todayFill);
                    canvas.drawRoundRect(tmpRect, dp(9f), dp(9f), pillPaint);
                    dayLabelPaint.setColor(palette.todayText);
                } else if (isPast) {
                    dayLabelPaint.setColor(palette.textDim);
                } else {
                    dayLabelPaint.setColor(palette.textPrimary);
                }
                canvas.drawText(dayLabel, centerX, textBaseline, dayLabelPaint);

                // Stacked colored event pills
                List<CalendarEvent> dayEvents = eventsByDay.get(d.toEpochDay());
                if (dayEvents == null || dayEvents.isEmpty()) continue;

                float availH = (bottom - cellGap - dp(2f)) - (top + dayHeaderZoneH);
                int maxSlots = Math.max(1, (int) ((availH + pillGap) / (pillH + pillGap)));
                boolean hasOverflow = dayEvents.size() > maxSlots;
                int renderCount = hasOverflow ? Math.max(1, maxSlots - 1) : dayEvents.size();

                float yCursor = top + dayHeaderZoneH;
                long epochDay = d.toEpochDay();

                for (int i = 0; i < renderCount; i++) {
                    CalendarEvent ev = dayEvents.get(i);
                    boolean continuesLeft = c > 0 && ev.startEpochDay < epochDay;
                    boolean continuesRight = c < 6 && ev.endEpochDay > epochDay;

                    float pLeft = continuesLeft ? (left + dp(0.5f)) : (left + dp(3f));
                    float pRight = continuesRight ? (right - dp(0.5f)) : (right - dp(3f));
                    tmpRect.set(pLeft, yCursor, pRight, yCursor + pillH);

                    int pColor = isPast
                            ? AppTheme.pastPillColor(ev.color, palette)
                            : AppTheme.normalizePillColor(ev.color);
                    pillPaint.setColor(pColor);
                    canvas.drawRoundRect(tmpRect, dp(3.6f), dp(3.6f), pillPaint);

                    float textAvailW = (pRight - pLeft) - dp(5f);
                    if (textAvailW > dp(6f)) {
                        pillTextPaint.setColor(isPast
                                ? AppTheme.pastTextColorOnPill(ev.color)
                                : AppTheme.textColorOnPill(ev.color));
                        CharSequence ellip = TextUtils.ellipsize(
                                ev.title, pillTextPaint, textAvailW, TextUtils.TruncateAt.END);
                        Paint.FontMetrics fm = pillTextPaint.getFontMetrics();
                        float baseline = yCursor + (pillH - fm.bottom - fm.top) * 0.5f;
                        canvas.drawText(ellip, 0, ellip.length(), pLeft + dp(2.8f), baseline, pillTextPaint);
                    }
                    yCursor += pillH + pillGap;
                }

                if (hasOverflow) {
                    int remaining = dayEvents.size() - renderCount;
                    pillTextPaint.setColor(isPast ? palette.textDim : palette.textPrimary);
                    String dots = "••• +" + remaining;
                    canvas.drawText(dots, left + dp(4f), yCursor + dp(10.5f), pillTextPaint);
                }
            }
        }

        canvas.restore();
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    private float sp(float v) {
        return v * getResources().getDisplayMetrics().scaledDensity;
    }
}
