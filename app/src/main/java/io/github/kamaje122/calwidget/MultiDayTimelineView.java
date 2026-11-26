package io.github.kamaje122.calwidget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.OverScroller;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Unified Google-Calendar-style Timeline View supporting:
 * - 7-Day Week View ("週")
 * - 3-Day View ("3 天")
 * - 1-Day View ("天")
 *
 * Features:
 * - Pinned weekday + Gregorian date + Lunar day header with Today circle highlight
 * - Pinned collapsible All-Day multi-day spanning banner row ("⌄" / "+N")
 * - 24-hour rounded slot grid with OverScroller fling, pinch-to-zoom hour height, and horizontal swipe
 * - Smart side-by-side column packing for overlapping timed events
 * - Live current-time horizontal line + dot on Today's column
 */
public final class MultiDayTimelineView extends View {

    public interface Listener {
        void onEventClicked(CalendarEvent event);
        void onDayHeaderClicked(LocalDate date);
        void onEmptySlotClicked(LocalDate date, int hour);
        void onSwipePeriod(int deltaDays);
    }

    private static final String[] DOW_LABELS = {
            "週一", "週二", "週三", "週四", "週五", "週六", "週日"
    };

    private static final class AllDaySpan {
        final CalendarEvent event;
        final int startCol;
        final int endCol;
        int lane;
        final RectF hitRect = new RectF();

        AllDaySpan(CalendarEvent event, int startCol, int endCol) {
            this.event = event;
            this.startCol = startCol;
            this.endCol = endCol;
        }
    }

    private static final class TimedBlock {
        final CalendarEvent event;
        final int col;
        final float startMin;
        final float endMin;
        int subCol;
        int totalSubCols;
        final RectF hitRect = new RectF();

        TimedBlock(CalendarEvent event, int col, float startMin, float endMin) {
            this.event = event;
            this.col = col;
            this.startMin = startMin;
            this.endMin = endMin;
        }
    }

    private LocalDate startDate = LocalDate.now();
    private int columnCount = 7;
    private AppTheme.Palette palette;
    private List<CalendarEvent> events = Collections.emptyList();
    private Listener listener;

    private final List<AllDaySpan> allDaySpans = new ArrayList<>();
    private final int[] colOverflowCounts = new int[7];
    private int maxAllDayLaneUsed = 0;
    private boolean allDayExpanded = false;

    private final List<TimedBlock> timedBlocks = new ArrayList<>();

    private float hourHeightDp = 54f;
    private float scrollYOffset = -1f;
    private final OverScroller scroller;
    private final GestureDetector gestureDetector;
    private final ScaleGestureDetector scaleDetector;

    private float downX;
    private float downY;
    private boolean swipingHorizontally = false;
    private float dragX = 0f;

    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint slotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint dowPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint dayNumPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint lunarPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint timeGutterPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint eventTitlePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint eventSubPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF tmpRect = new RectF();

    public MultiDayTimelineView(Context context) {
        super(context);
        palette = AppTheme.of(context);
        hourHeightDp = AppPrefs.getHourHeightDp(context);
        scroller = new OverScroller(context);

        dowPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        dowPaint.setTextAlign(Paint.Align.CENTER);

        dayNumPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        dayNumPaint.setTextAlign(Paint.Align.CENTER);

        lunarPaint.setTextAlign(Paint.Align.CENTER);

        timeGutterPaint.setTextAlign(Paint.Align.RIGHT);
        timeGutterPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));

        eventTitlePaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));

        gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                if (!scroller.isFinished()) {
                    scroller.abortAnimation();
                }
                return true;
            }

            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
                if (swipingHorizontally || scaleDetector.isInProgress()) return false;
                scrollByVertical(distanceY);
                return true;
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                if (scaleDetector.isInProgress()) return false;
                if (e1 != null && e2 != null) {
                    float dx = e2.getX() - e1.getX();
                    float dy = e2.getY() - e1.getY();
                    if (Math.abs(dx) > dp(46) && Math.abs(dx) > Math.abs(dy) * 1.3f
                            && Math.abs(velocityX) > dp(200)) {
                        swipingHorizontally = false;
                        dragX = 0f;
                        if (listener != null) {
                            listener.onSwipePeriod(dx < 0 ? columnCount : -columnCount);
                        }
                        invalidate();
                        return true;
                    }
                }
                int maxScroll = getMaxVerticalScroll();
                scroller.fling(0, Math.round(scrollYOffset), 0, Math.round(-velocityY),
                        0, 0, 0, maxScroll);
                postInvalidateOnAnimation();
                return true;
            }

            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                handleSingleTap(e.getX(), e.getY());
                return true;
            }
        });

        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                float factor = detector.getScaleFactor();
                float oldHourPx = dp(hourHeightDp);
                hourHeightDp = Math.max(38f, Math.min(104f, hourHeightDp * factor));
                float newHourPx = dp(hourHeightDp);
                if (oldHourPx > 0f) {
                    float focusInTimeline = (detector.getFocusY() - getPinnedHeaderHeight()) + scrollYOffset;
                    float ratio = newHourPx / oldHourPx;
                    scrollYOffset = clampScroll(focusInTimeline * ratio - (detector.getFocusY() - getPinnedHeaderHeight()));
                }
                AppPrefs.setHourHeightDp(getContext(), hourHeightDp);
                invalidate();
                return true;
            }
        });
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void setData(
            LocalDate periodStart,
            int cols,
            AppTheme.Palette themePalette,
            List<CalendarEvent> rangeEvents) {
        this.startDate = periodStart;
        this.columnCount = Math.max(1, Math.min(7, cols));
        this.palette = themePalette;
        this.events = rangeEvents != null ? rangeEvents : Collections.emptyList();
        rebuildEventGeometry();
        if (scrollYOffset < 0f) {
            scrollToDefaultHour();
        } else {
            scrollYOffset = clampScroll(scrollYOffset);
        }
        invalidate();
    }

    public void scrollToDefaultHour() {
        int nowHour = LocalTime.now().getHour();
        int targetHour = Math.max(7, Math.min(18, nowHour - 2));
        scrollYOffset = dp(hourHeightDp) * targetHour;
        invalidate();
    }

    @Override
    public void computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollYOffset = clampScroll(scroller.getCurrY());
            postInvalidateOnAnimation();
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        scaleDetector.onTouchEvent(event);
        boolean gHandled = gestureDetector.onTouchEvent(event);

        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            downX = event.getX();
            downY = event.getY();
            swipingHorizontally = false;
            dragX = 0f;
        } else if (action == MotionEvent.ACTION_MOVE && !scaleDetector.isInProgress()) {
            float dx = event.getX() - downX;
            float dy = event.getY() - downY;
            if (!swipingHorizontally && Math.abs(dx) > dp(18) && Math.abs(dx) > Math.abs(dy) * 1.45f) {
                swipingHorizontally = true;
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
            }
            if (swipingHorizontally) {
                dragX = dx * 0.45f;
                invalidate();
                return true;
            }
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            if (swipingHorizontally) {
                float dx = event.getX() - downX;
                swipingHorizontally = false;
                dragX = 0f;
                invalidate();
                if (Math.abs(dx) > getWidth() * 0.24f && listener != null) {
                    listener.onSwipePeriod(dx < 0 ? columnCount : -columnCount);
                }
                return true;
            }
        }
        return gHandled || super.onTouchEvent(event);
    }

    private void scrollByVertical(float dy) {
        scrollYOffset = clampScroll(scrollYOffset + dy);
        invalidate();
    }

    private int getMaxVerticalScroll() {
        float contentH = dp(hourHeightDp) * 24f + dp(28f);
        float viewportH = Math.max(1f, getHeight() - getPinnedHeaderHeight());
        return Math.max(0, Math.round(contentH - viewportH));
    }

    private float clampScroll(float raw) {
        return Math.max(0f, Math.min(getMaxVerticalScroll(), raw));
    }

    private float getDayHeaderHeight() {
        return dp(52f);
    }

    private int getVisibleAllDayLanes() {
        if (maxAllDayLaneUsed == 0) return 0;
        int cap = allDayExpanded ? 8 : 3;
        return Math.min(maxAllDayLaneUsed, cap);
    }

    private float getAllDayAreaHeight() {
        int lanes = getVisibleAllDayLanes();
        if (lanes == 0) return 0f;
        boolean hasHidden = maxAllDayLaneUsed > lanes;
        return lanes * dp(21.5f) + (hasHidden ? dp(16f) : dp(4f));
    }

    private float getPinnedHeaderHeight() {
        return getDayHeaderHeight() + getAllDayAreaHeight();
    }

    private void rebuildEventGeometry() {
        allDaySpans.clear();
        timedBlocks.clear();
        for (int i = 0; i < 7; i++) colOverflowCounts[i] = 0;
        maxAllDayLaneUsed = 0;

        long minEpoch = startDate.toEpochDay();
        long maxEpoch = startDate.plusDays(columnCount - 1L).toEpochDay();
        ZoneId zone = ZoneId.systemDefault();

        // 1. All-Day & Multi-Day Spanning Events -> Pinned All-Day Header
        List<AllDaySpan> rawAllDay = new ArrayList<>();
        for (CalendarEvent ev : events) {
            if (ev.endEpochDay < minEpoch || ev.startEpochDay > maxEpoch) continue;
            if (ev.allDay || ev.isMultiDay()) {
                int sCol = (int) Math.max(0L, ev.startEpochDay - minEpoch);
                int eCol = (int) Math.min(columnCount - 1L, ev.endEpochDay - minEpoch);
                rawAllDay.add(new AllDaySpan(ev, sCol, eCol));
            }
        }
        rawAllDay.sort((a, b) -> {
            int c1 = Integer.compare(a.startCol, b.startCol);
            if (c1 != 0) return c1;
            return Integer.compare(b.endCol - b.startCol, a.endCol - a.startCol);
        });

        List<int[]> laneEndCols = new ArrayList<>();
        for (AllDaySpan span : rawAllDay) {
            int assigned = -1;
            for (int l = 0; l < laneEndCols.size(); l++) {
                if (laneEndCols.get(l)[0] < span.startCol) {
                    assigned = l;
                    laneEndCols.get(l)[0] = span.endCol;
                    break;
                }
            }
            if (assigned < 0) {
                assigned = laneEndCols.size();
                laneEndCols.add(new int[]{span.endCol});
            }
            span.lane = assigned;
            allDaySpans.add(span);
            if (assigned + 1 > maxAllDayLaneUsed) {
                maxAllDayLaneUsed = assigned + 1;
            }
        }

        // 2. Single-day Timed Events -> 24-Hour Timeline Columns
        for (int col = 0; col < columnCount; col++) {
            LocalDate day = startDate.plusDays(col);
            long dayStartMs = day.atStartOfDay(zone).toInstant().toEpochMilli();
            long dayEndMs = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();

            List<TimedBlock> dayList = new ArrayList<>();
            for (CalendarEvent ev : events) {
                if (ev.allDay || ev.isMultiDay()) continue;
                if (ev.endMillis <= dayStartMs && ev.beginMillis < dayStartMs) continue;
                if (ev.beginMillis >= dayEndMs) continue;

                long clampedStart = Math.max(dayStartMs, ev.beginMillis);
                long rawEnd = ev.hasExplicitEnd && ev.endMillis > ev.beginMillis
                        ? ev.endMillis
                        : (ev.beginMillis + 3_600_000L);
                long clampedEnd = Math.min(dayEndMs, Math.max(clampedStart + 1_500_000L, rawEnd));

                LocalTime st = Instant.ofEpochMilli(clampedStart).atZone(zone).toLocalTime();
                float startMin = st.getHour() * 60f + st.getMinute();
                float durationMin = Math.max(24f, (clampedEnd - clampedStart) / 60_000f);
                float endMin = Math.min(1440f, startMin + durationMin);

                dayList.add(new TimedBlock(ev, col, startMin, endMin));
            }

            dayList.sort((a, b) -> {
                int c = Float.compare(a.startMin, b.startMin);
                if (c != 0) return c;
                return Float.compare(b.endMin, a.endMin);
            });

            packColumnOverlaps(dayList);
            timedBlocks.addAll(dayList);
        }
    }

    private static void packColumnOverlaps(List<TimedBlock> blocks) {
        List<TimedBlock> cluster = new ArrayList<>();
        float clusterMaxEnd = -1f;
        for (TimedBlock b : blocks) {
            if (!cluster.isEmpty() && b.startMin >= clusterMaxEnd) {
                assignClusterSubColumns(cluster);
                cluster.clear();
                clusterMaxEnd = -1f;
            }
            cluster.add(b);
            if (b.endMin > clusterMaxEnd) clusterMaxEnd = b.endMin;
        }
        if (!cluster.isEmpty()) {
            assignClusterSubColumns(cluster);
        }
    }

    private static void assignClusterSubColumns(List<TimedBlock> cluster) {
        List<Float> colEnds = new ArrayList<>();
        for (TimedBlock b : cluster) {
            int chosen = -1;
            for (int i = 0; i < colEnds.size(); i++) {
                if (colEnds.get(i) <= b.startMin + 0.5f) {
                    chosen = i;
                    colEnds.set(i, b.endMin);
                    break;
                }
            }
            if (chosen < 0) {
                chosen = colEnds.size();
                colEnds.add(b.endMin);
            }
            b.subCol = chosen;
        }
        int total = Math.max(1, colEnds.size());
        for (TimedBlock b : cluster) {
            b.totalSubCols = total;
        }
    }

    private void handleSingleTap(float x, float y) {
        float gutterW = dp(48f);
        float dayHeaderH = getDayHeaderHeight();
        float pinnedH = getPinnedHeaderHeight();

        // 1. Toggle expand/collapse on All-Day chevron or "+N" row
        if (y >= dayHeaderH && y < pinnedH) {
            if (x < gutterW && maxAllDayLaneUsed > 3) {
                allDayExpanded = !allDayExpanded;
                invalidate();
                return;
            }
            for (AllDaySpan span : allDaySpans) {
                if (!span.hitRect.isEmpty() && span.hitRect.contains(x, y)) {
                    if (listener != null) listener.onEventClicked(span.event);
                    return;
                }
            }
            if (maxAllDayLaneUsed > 3) {
                allDayExpanded = !allDayExpanded;
                invalidate();
            }
            return;
        }

        if (x < gutterW) return;
        float colW = (getWidth() - gutterW) / columnCount;
        int col = Math.min(columnCount - 1, Math.max(0, (int) ((x - gutterW) / colW)));
        LocalDate date = startDate.plusDays(col);

        // 2. Day header tap
        if (y < dayHeaderH) {
            if (listener != null) listener.onDayHeaderClicked(date);
            return;
        }

        // 3. Timed event block tap (iterate reverse for top-most Z order)
        for (int i = timedBlocks.size() - 1; i >= 0; i--) {
            TimedBlock b = timedBlocks.get(i);
            if (!b.hitRect.isEmpty() && b.hitRect.contains(x, y)) {
                if (listener != null) listener.onEventClicked(b.event);
                return;
            }
        }

        // 4. Empty hour slot tap -> quick add event at that hour
        float timelineY = (y - pinnedH) + scrollYOffset;
        int hour = Math.max(0, Math.min(23, (int) (timelineY / dp(hourHeightDp))));
        if (listener != null) {
            listener.onEmptySlotClicked(date, hour);
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

        float gutterW = dp(48f);
        float colW = (w - gutterW) / columnCount;
        float dayHeaderH = getDayHeaderHeight();
        float pinnedH = getPinnedHeaderHeight();
        float hourPx = dp(hourHeightDp);
        LocalDate today = LocalDate.now();

        // ================= 1. SCROLLABLE 24-HOUR TIMELINE =================
        canvas.save();
        canvas.clipRect(0, pinnedH, w, h);
        canvas.translate(dragX, pinnedH - scrollYOffset);

        slotPaint.setColor(palette.bgCell);
        float slotGap = dp(1.4f);
        float slotRadius = dp(5f);

        for (int hr = 0; hr < 24; hr++) {
            float top = hr * hourPx;
            float bottom = top + hourPx;
            if (bottom < scrollYOffset - hourPx || top > scrollYOffset + (h - pinnedH) + hourPx) {
                continue;
            }
            for (int c = 0; c < columnCount; c++) {
                float left = gutterW + c * colW;
                tmpRect.set(left + slotGap, top + slotGap, left + colW - slotGap, bottom - slotGap);
                canvas.drawRoundRect(tmpRect, slotRadius, slotRadius, slotPaint);
            }
        }

        // Left gutter hour labels (01:00 .. 23:00)
        timeGutterPaint.setTextSize(sp(10.8f));
        timeGutterPaint.setColor(palette.textSecondary);
        for (int hr = 1; hr < 24; hr++) {
            float y = hr * hourPx;
            if (y < scrollYOffset - dp(20) || y > scrollYOffset + (h - pinnedH) + dp(20)) continue;
            canvas.drawText(String.format(Locale.US, "%02d:00", hr),
                    gutterW - dp(7f), y + dp(4f), timeGutterPaint);
        }

        // Timed Event Blocks
        eventTitlePaint.setTextSize(sp(columnCount >= 7 ? 10.5f : 12f));
        eventSubPaint.setTextSize(sp(columnCount >= 7 ? 9.5f : 11f));

        for (TimedBlock b : timedBlocks) {
            float colLeft = gutterW + b.col * colW + dp(1.6f);
            float usableColW = colW - dp(3.2f);
            float subW = usableColW / b.totalSubCols;

            float left = colLeft + b.subCol * subW + dp(0.6f);
            float right = left + subW - dp(1.2f);
            float top = (b.startMin / 60f) * hourPx + dp(0.8f);
            float bottom = Math.max(top + dp(20f), (b.endMin / 60f) * hourPx - dp(0.8f));

            // Save screen-space hit rect for tap detection
            b.hitRect.set(left + dragX, top + pinnedH - scrollYOffset,
                    right + dragX, bottom + pinnedH - scrollYOffset);

            if (bottom < scrollYOffset - dp(20) || top > scrollYOffset + (h - pinnedH) + dp(20)) {
                continue;
            }

            tmpRect.set(left, top, right, bottom);
            pillPaint.setColor(b.event.color);
            canvas.drawRoundRect(tmpRect, dp(6f), dp(6f), pillPaint);

            int textW = (int) ((right - left) - dp(7f));
            int boxH = (int) ((bottom - top) - dp(5f));
            if (textW > dp(8f) && boxH > dp(10f)) {
                eventTitlePaint.setColor(AppTheme.textColorOnPill(b.event.color));
                String body = b.event.title;
                if (!TextUtils.isEmpty(b.event.location) && boxH > dp(34f)) {
                    body = b.event.title + "\n" + b.event.location.trim();
                } else if (b.event.isNotion() && !TextUtils.isEmpty(b.event.statusLabel) && boxH > dp(34f)) {
                    body = b.event.title + "\n" + b.event.statusLabel;
                }
                canvas.save();
                canvas.clipRect(left + dp(3.5f), top + dp(2.5f), right - dp(2.5f), bottom - dp(2.5f));
                canvas.translate(left + dp(3.5f), top + dp(2.5f));
                StaticLayout sl = StaticLayout.Builder.obtain(body, 0, body.length(), eventTitlePaint, textW)
                        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                        .setIncludePad(false)
                        .setMaxLines(Math.max(1, boxH / Math.max(1, Math.round(sp(13f)))))
                        .setEllipsize(TextUtils.TruncateAt.END)
                        .build();
                sl.draw(canvas);
                canvas.restore();
            }
        }

        // Live Current-Time Indicator Line + Dot on Today's Column
        for (int c = 0; c < columnCount; c++) {
            if (startDate.plusDays(c).equals(today)) {
                LocalTime now = LocalTime.now();
                float nowY = ((now.getHour() * 60f + now.getMinute()) / 60f) * hourPx;
                float cLeft = gutterW + c * colW;
                float cRight = cLeft + colW;
                linePaint.setColor(palette.nowIndicator);
                linePaint.setStrokeWidth(dp(1.8f));
                canvas.drawLine(cLeft, nowY, cRight, nowY, linePaint);
                canvas.drawCircle(cLeft + dp(1.5f), nowY, dp(4.5f), linePaint);
                break;
            }
        }

        canvas.restore();

        // ================= 2. PINNED HEADER + ALL-DAY BANNER =================
        bgPaint.setColor(palette.bgAppBar);
        canvas.drawRect(0, 0, w, pinnedH, bgPaint);

        dowPaint.setTextSize(sp(11.5f));
        dayNumPaint.setTextSize(sp(15.5f));
        lunarPaint.setTextSize(sp(10f));

        for (int c = 0; c < columnCount; c++) {
            LocalDate d = startDate.plusDays(c);
            boolean isToday = d.equals(today);
            float cx = gutterW + c * colW + colW * 0.5f + dragX;

            dowPaint.setColor(palette.textPrimary);
            canvas.drawText(DOW_LABELS[d.getDayOfWeek().getValue() - 1], cx, dp(15f), dowPaint);

            float badgeCy = dp(34.5f);
            if (isToday) {
                pillPaint.setColor(palette.todayFill);
                canvas.drawCircle(cx, badgeCy, dp(15.5f), pillPaint);
                dayNumPaint.setColor(palette.todayText);
                lunarPaint.setColor(palette.todayText);
            } else {
                dayNumPaint.setColor(palette.textPrimary);
                lunarPaint.setColor(palette.textSecondary);
            }
            canvas.drawText(String.valueOf(d.getDayOfMonth()), cx, dp(33.5f), dayNumPaint);
            canvas.drawText(LunarCalendarHelper.getDayShort(d), cx, dp(45.5f), lunarPaint);
        }

        // All-Day Event Lanes
        int visLanes = getVisibleAllDayLanes();
        for (int i = 0; i < 7; i++) colOverflowCounts[i] = 0;
        for (AllDaySpan span : allDaySpans) {
            span.hitRect.setEmpty();
            if (span.lane >= visLanes) {
                for (int col = span.startCol; col <= span.endCol && col < 7; col++) {
                    colOverflowCounts[col]++;
                }
                continue;
            }
            float left = gutterW + span.startCol * colW + dp(1.8f) + dragX;
            float right = gutterW + (span.endCol + 1) * colW - dp(1.8f) + dragX;
            float top = dayHeaderH + span.lane * dp(21.5f) + dp(1f);
            float bottom = top + dp(19.2f);

            span.hitRect.set(left, top, right, bottom);
            pillPaint.setColor(span.event.color);
            canvas.drawRoundRect(span.hitRect, dp(5f), dp(5f), pillPaint);

            float availW = (right - left) - dp(8f);
            if (availW > dp(8f)) {
                eventTitlePaint.setTextSize(sp(11f));
                eventTitlePaint.setColor(AppTheme.textColorOnPill(span.event.color));
                CharSequence ellip = TextUtils.ellipsize(
                        span.event.title, eventTitlePaint, availW, TextUtils.TruncateAt.END);
                Paint.FontMetrics fm = eventTitlePaint.getFontMetrics();
                float baseline = top + (dp(19.2f) - fm.bottom - fm.top) * 0.5f;
                canvas.drawText(ellip, 0, ellip.length(), left + dp(4.5f), baseline, eventTitlePaint);
            }
        }

        if (maxAllDayLaneUsed > 3) {
            dowPaint.setColor(palette.textPrimary);
            dowPaint.setTextSize(sp(14f));
            canvas.drawText(allDayExpanded ? "⌃" : "⌄",
                    gutterW * 0.5f, pinnedH - dp(5f), dowPaint);
        }
        if (maxAllDayLaneUsed > visLanes) {
            eventSubPaint.setColor(palette.textPrimary);
            eventSubPaint.setTextSize(sp(11f));
            float overflowBaseline = dayHeaderH + visLanes * dp(21.5f) + dp(12.5f);
            for (int c = 0; c < columnCount; c++) {
                if (colOverflowCounts[c] > 0) {
                    canvas.drawText("+" + colOverflowCounts[c],
                            gutterW + c * colW + dp(5f) + dragX, overflowBaseline, eventSubPaint);
                }
            }
        }

        // Bottom hairline divider under pinned header
        linePaint.setColor(palette.divider);
        linePaint.setStrokeWidth(dp(1f));
        canvas.drawLine(0, pinnedH, w, pinnedH, linePaint);
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    private float sp(float v) {
        return v * getResources().getDisplayMetrics().scaledDensity;
    }
}
