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
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.VelocityTracker;
import android.view.View;
import android.widget.OverScroller;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Unified Google-Calendar-style Timeline View supporting:
 * - 7-Day Week View ("週")
 * - 3-Day Free-Scroll View ("3 天" — freely position any 3 consecutive days with live neighboring events)
 * - 1-Day View ("天" & floating single-day popup)
 *
 * Features:
 * - Pinned weekday + Gregorian date + Lunar day header with Today circle highlight
 * - Pinned collapsible All-Day multi-day spanning banner row ("⌄" / "+N")
 * - Continuous horizontal panning showing neighboring days' events live on both sides (clipped cleanly at time gutter)
 * - Configurable long-press hold duration to drag & reschedule events across hours (15-min snap) and days
 * - 24-hour rounded slot grid with OverScroller fling & pinch-to-zoom hour height
 * - Smart side-by-side column packing for overlapping timed events
 */
public final class MultiDayTimelineView extends View {

    public interface Listener {
        void onEventClicked(CalendarEvent event);
        void onDayHeaderClicked(LocalDate date);
        void onEmptySlotClicked(LocalDate date, int hour);
        void onSwipePeriod(int deltaDays);

        /** Fired during/after continuous horizontal scrolling when the leftmost visible date shifts. */
        default void onVisibleStartDateChanged(LocalDate newStartDate, boolean settled) {
        }

        /** Fired when the user long-presses and drags an event to a new date and/or time. */
        default void onEventDragged(
                CalendarEvent event,
                boolean allDay,
                LocalDate newStartDate,
                LocalDate newEndDate,
                long newBeginMillis,
                long newEndMillis) {
        }
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
        int spanSubCols = 1;
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

    private int minRenderCol = -5;
    private int maxRenderCol = 7;
    private final List<AllDaySpan> allDaySpans = new ArrayList<>();
    private int[] colOverflowCounts = new int[32];
    private int maxAllDayLaneUsed = 0;
    private boolean allDayExpanded = false;

    private final List<TimedBlock> timedBlocks = new ArrayList<>();

    private float hourHeightDp = -1f;
    private float scrollYOffset = -1f;
    private final OverScroller scroller;

    // Continuous horizontal scrolling state (positive horizScrollPx = scrolled toward future days on the right)
    private float horizScrollPx = 0f;
    private final OverScroller horizScroller;
    private int lastHorizScrollerX = 0;
    private boolean horizAnimating = false;
    private LocalDate gestureBaseStartDate = LocalDate.now();

    private final GestureDetector gestureDetector;
    private final ScaleGestureDetector scaleDetector;
    private VelocityTracker velocityTracker;

    private float downX;
    private float downY;
    private float lastTouchX;
    private float lastTouchY;
    private boolean swipingHorizontally = false;

    // Long-press drag-to-reschedule state
    private TimedBlock pendingDragTimed = null;
    private AllDaySpan pendingDragAllDay = null;
    private boolean longPressScheduled = false;
    private boolean isDraggingEvent = false;
    private CalendarEvent draggingEvent = null;
    private boolean draggingAllDay = false;
    private int dragOrigCol = 0;
    private int dragTargetCol = 0;
    private int dragOrigStartMin = 0;
    private int dragTargetStartMin = 0;
    private int dragDurationMin = 60;
    private float dragGrabOffsetMin = 0f;
    private long dragOrigStartEpoch = 0L;
    private long dragTargetStartEpoch = 0L;
    private long dragSpanDays = 0L;
    private int dragGrabColOffset = 0;
    private long lastHorizontalAutoShiftMs = 0L;

    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint slotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dragStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint dowPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint dayNumPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint lunarPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint timeGutterPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint eventTitlePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint eventSubPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint dragBadgePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF tmpRect = new RectF();

    /** When hosted inside a floating Dialog: always keep touches inside this view. */
    private boolean floatingMode = false;
    private boolean compactDayHeader = false;
    private final ZoneId zoneForDraw = ZoneId.systemDefault();

    private final Runnable longPressRunnable = new Runnable() {
        @Override
        public void run() {
            longPressScheduled = false;
            if (swipingHorizontally || scaleDetector.isInProgress()) {
                pendingDragTimed = null;
                pendingDragAllDay = null;
                return;
            }
            if (pendingDragTimed != null) {
                beginTimedEventDrag(pendingDragTimed);
                pendingDragTimed = null;
            } else if (pendingDragAllDay != null) {
                beginAllDayEventDrag(pendingDragAllDay);
                pendingDragAllDay = null;
            }
        }
    };

    private final Runnable dragAutoScrollRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isDraggingEvent) return;
            boolean needsNextTick = false;
            float pinnedH = getPinnedHeaderHeight();
            int h = getHeight();
            int w = getWidth();

            // 1. Vertical auto-scroll when timeline is zoomed in
            if (!draggingAllDay && !isFullDayFit() && h > 0) {
                float edgeZone = dp(42f);
                if (lastTouchY > pinnedH && lastTouchY < pinnedH + edgeZone && scrollYOffset > 0f) {
                    float factor = 1f - Math.max(0f, (lastTouchY - pinnedH) / edgeZone);
                    scrollYOffset = clampScroll(scrollYOffset - dp(8.5f) * (0.35f + factor));
                    updateDragTargetFromPoint(lastTouchX, lastTouchY);
                    needsNextTick = true;
                } else if (lastTouchY > h - edgeZone && scrollYOffset < getMaxVerticalScroll()) {
                    float factor = Math.min(1f, (lastTouchY - (h - edgeZone)) / edgeZone);
                    scrollYOffset = clampScroll(scrollYOffset + dp(8.5f) * (0.35f + factor));
                    updateDragTargetFromPoint(lastTouchX, lastTouchY);
                    needsNextTick = true;
                }
            }

            // 2. Horizontal auto-day-shift when dragging near left/right edge
            float gutterW = getGutterWidth();
            float rightPad = getRightPadding();
            float hEdge = dp(26f);
            long now = System.currentTimeMillis();
            if (w > 0 && !floatingMode) {
                int shiftStep = (columnCount == 7) ? 7 : 1;
                if (lastTouchX >= gutterW && lastTouchX < gutterW + hEdge) {
                    needsNextTick = true;
                    if (now - lastHorizontalAutoShiftMs >= 460L) {
                        lastHorizontalAutoShiftMs = now;
                        startDate = startDate.minusDays(shiftStep);
                        rebuildEventGeometry();
                        if (listener != null) listener.onVisibleStartDateChanged(startDate, false);
                        updateDragTargetFromPoint(lastTouchX, lastTouchY);
                    }
                } else if (lastTouchX > w - rightPad - hEdge) {
                    needsNextTick = true;
                    if (now - lastHorizontalAutoShiftMs >= 460L) {
                        lastHorizontalAutoShiftMs = now;
                        startDate = startDate.plusDays(shiftStep);
                        rebuildEventGeometry();
                        if (listener != null) listener.onVisibleStartDateChanged(startDate, false);
                        updateDragTargetFromPoint(lastTouchX, lastTouchY);
                    }
                }
            }

            invalidate();
            if (needsNextTick) {
                postDelayed(this, 16L);
            }
        }
    };

    public MultiDayTimelineView(Context context) {
        super(context);
        palette = AppTheme.of(context);
        hourHeightDp = AppPrefs.getHourHeightDp(context);
        scroller = new OverScroller(context);
        horizScroller = new OverScroller(context);

        dowPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        dowPaint.setTextAlign(Paint.Align.CENTER);

        dayNumPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        dayNumPaint.setTextAlign(Paint.Align.CENTER);

        lunarPaint.setTextAlign(Paint.Align.CENTER);

        timeGutterPaint.setTextAlign(Paint.Align.RIGHT);
        timeGutterPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));

        eventTitlePaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));

        dragBadgePaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        dragBadgePaint.setTextAlign(Paint.Align.CENTER);

        dragStrokePaint.setStyle(Paint.Style.STROKE);

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
                if (isDraggingEvent || swipingHorizontally || scaleDetector.isInProgress()) return false;
                if (Math.abs(distanceY) > dp(2f)) {
                    cancelPendingLongPress();
                }
                scrollByVertical(distanceY);
                return true;
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                if (isDraggingEvent || swipingHorizontally || scaleDetector.isInProgress()) return false;
                int maxScroll = getMaxVerticalScroll();
                if (maxScroll > 0) {
                    scroller.fling(0, Math.round(scrollYOffset), 0, Math.round(-velocityY),
                            0, 0, 0, maxScroll);
                    postInvalidateOnAnimation();
                    return true;
                }
                return false;
            }

            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                if (isDraggingEvent) return false;
                cancelPendingLongPress();
                handleSingleTap(e.getX(), e.getY());
                return true;
            }
        });
        gestureDetector.setIsLongpressEnabled(false);

        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                if (isDraggingEvent) return false;
                cancelPendingLongPress();
                applyZoom(detector.getScaleFactor(), detector.getFocusY());
                return true;
            }

            @Override
            public boolean onScaleBegin(ScaleGestureDetector detector) {
                if (isDraggingEvent) return false;
                cancelPendingLongPress();
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                return true;
            }
        });
    }

    private void applyZoom(float factor, float focusY) {
        float minHourPx = getFitHourPx();
        float maxHourPx = dp(140f);
        float oldHourPx = getEffectiveHourPx();
        float newHourPx = Math.max(minHourPx, Math.min(maxHourPx, oldHourPx * factor));

        if (newHourPx <= minHourPx + 0.5f) {
            hourHeightDp = -1f;
            scrollYOffset = 0f;
        } else {
            float density = getResources().getDisplayMetrics().density;
            hourHeightDp = newHourPx / Math.max(0.5f, density);
            if (oldHourPx > 0f) {
                float focusInView = Math.max(0f, focusY - getPinnedHeaderHeight());
                float focusInTimeline = focusInView + Math.max(0f, scrollYOffset);
                float ratio = newHourPx / oldHourPx;
                scrollYOffset = clampScroll(focusInTimeline * ratio - focusInView);
            }
        }
        if (!floatingMode) {
            AppPrefs.setHourHeightDp(getContext(), hourHeightDp);
        }
        invalidate();
    }

    /** Zoom the hour height around the viewport centre (used by −/＋ buttons). */
    public void zoomBy(float factor) {
        float centreY = getPinnedHeaderHeight() + getViewportHeight() * 0.5f;
        applyZoom(factor, centreY);
    }

    /** Snaps immediately to full 00:00–24:00 overview filling the entire viewport cleanly. */
    public void resetToFullDay() {
        if (!scroller.isFinished()) {
            scroller.abortAnimation();
        }
        hourHeightDp = -1f;
        scrollYOffset = 0f;
        if (!floatingMode) {
            AppPrefs.setHourHeightDp(getContext(), -1f);
        }
        invalidate();
    }

    public boolean isFullDayFit() {
        return hourHeightDp <= 0f;
    }

    public void setFloatingMode(boolean floating) {
        this.floatingMode = floating;
        if (floating) {
            this.hourHeightDp = -1f;
            this.scrollYOffset = 0f;
        }
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public void setData(
            LocalDate periodStart,
            int cols,
            AppTheme.Palette themePalette,
            List<CalendarEvent> rangeEvents) {
        if (!horizScroller.isFinished()) {
            horizScroller.abortAnimation();
        }
        horizAnimating = false;
        horizScrollPx = 0f;
        this.startDate = periodStart;
        this.columnCount = Math.max(1, Math.min(7, cols));
        this.palette = themePalette;
        this.events = rangeEvents != null ? rangeEvents : Collections.emptyList();
        rebuildEventGeometry();
        if (scrollYOffset < 0f || isFullDayFit()) {
            scrollYOffset = 0f;
        } else if (getHeight() > 0) {
            scrollYOffset = clampScroll(scrollYOffset);
        }
        invalidate();
    }

    /**
     * Refreshes the buffered event list & palette without resetting the user's active horizontal
     * scroll offset or current startDate (used during seamless background buffer expansion).
     */
    public void updateBufferedEvents(List<CalendarEvent> rangeEvents, AppTheme.Palette themePalette) {
        this.palette = themePalette;
        this.events = rangeEvents != null ? rangeEvents : Collections.emptyList();
        rebuildEventGeometry();
        invalidate();
    }

    public void scrollToDefaultHour() {
        if (isFullDayFit()) {
            scrollYOffset = 0f;
        } else {
            scrollYOffset = clampScroll(0f);
        }
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (h > 0) {
            scrollYOffset = isFullDayFit() ? 0f : clampScroll(Math.max(0f, scrollYOffset));
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        cancelPendingLongPress();
        removeCallbacks(dragAutoScrollRunnable);
        recycleVelocityTracker();
        super.onDetachedFromWindow();
    }

    @Override
    public void computeScroll() {
        boolean needsInvalidate = false;
        if (scroller.computeScrollOffset()) {
            scrollYOffset = clampScroll(scroller.getCurrY());
            needsInvalidate = true;
        }
        if (horizAnimating) {
            if (horizScroller.computeScrollOffset()) {
                int currX = horizScroller.getCurrX();
                int step = currX - lastHorizScrollerX;
                lastHorizScrollerX = currX;
                horizScrollPx += step;
                if (isFreeDayScroll()) {
                    normalizeFreeHorizScroll();
                }
                needsInvalidate = true;
            }
            if (horizScroller.isFinished()) {
                horizAnimating = false;
                settleHorizontalScroll();
                needsInvalidate = true;
            }
        }
        if (needsInvalidate) {
            postInvalidateOnAnimation();
        }
    }

    private boolean isFreeDayScroll() {
        return columnCount < 7;
    }

    private float getColumnWidth() {
        return Math.max(1f, (getWidth() - getGutterWidth() - getRightPadding()) / columnCount);
    }

    private void normalizeFreeHorizScroll() {
        float colW = getColumnWidth();
        if (colW <= 1f) return;
        int wholeDays = (int) (horizScrollPx / colW);
        if (wholeDays != 0) {
            startDate = startDate.plusDays(wholeDays);
            horizScrollPx -= wholeDays * colW;
            rebuildEventGeometry();
            if (listener != null) {
                listener.onVisibleStartDateChanged(startDate, false);
            }
        }
    }

    private void settleHorizontalScroll() {
        float colW = getColumnWidth();
        if (colW > 1f) {
            int remDays = Math.round(horizScrollPx / colW);
            if (remDays != 0) {
                startDate = startDate.plusDays(remDays);
            }
        }
        horizScrollPx = 0f;
        rebuildEventGeometry();
        if (listener != null) {
            int totalDelta = (int) (startDate.toEpochDay() - gestureBaseStartDate.toEpochDay());
            listener.onVisibleStartDateChanged(startDate, true);
            if (floatingMode && totalDelta != 0) {
                listener.onSwipePeriod(totalDelta);
            }
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();

        // Active event drag mode takes exclusive ownership of touch stream
        if (isDraggingEvent) {
            switch (action) {
                case MotionEvent.ACTION_MOVE:
                    lastTouchX = event.getX();
                    lastTouchY = event.getY();
                    updateDragTargetFromPoint(lastTouchX, lastTouchY);
                    removeCallbacks(dragAutoScrollRunnable);
                    post(dragAutoScrollRunnable);
                    invalidate();
                    return true;
                case MotionEvent.ACTION_UP:
                    finishEventDrag(true);
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    finishEventDrag(false);
                    return true;
                default:
                    return true;
            }
        }

        if (velocityTracker == null) {
            velocityTracker = VelocityTracker.obtain();
        }
        velocityTracker.addMovement(event);

        scaleDetector.onTouchEvent(event);
        boolean gHandled = gestureDetector.onTouchEvent(event);

        if (action == MotionEvent.ACTION_DOWN) {
            if (horizAnimating) {
                horizScroller.abortAnimation();
                horizAnimating = false;
                if (isFreeDayScroll()) {
                    normalizeFreeHorizScroll();
                }
            }
            gestureBaseStartDate = startDate;
            downX = event.getX();
            downY = event.getY();
            lastTouchX = downX;
            lastTouchY = downY;
            swipingHorizontally = false;
            if (floatingMode && getParent() != null) {
                getParent().requestDisallowInterceptTouchEvent(true);
            }
            scheduleLongPressIfOnEvent(downX, downY);
        } else if (action == MotionEvent.ACTION_POINTER_DOWN) {
            cancelPendingLongPress();
            if (getParent() != null) {
                getParent().requestDisallowInterceptTouchEvent(true);
            }
        } else if (action == MotionEvent.ACTION_MOVE && !scaleDetector.isInProgress()) {
            float x = event.getX();
            float y = event.getY();
            float dxFromDown = x - downX;
            float dyFromDown = y - downY;

            if (longPressScheduled && Math.hypot(dxFromDown, dyFromDown) > dp(10f)) {
                cancelPendingLongPress();
            }

            if (!swipingHorizontally
                    && Math.abs(dxFromDown) > dp(12f)
                    && Math.abs(dxFromDown) > Math.abs(dyFromDown) * 1.25f) {
                swipingHorizontally = true;
                cancelPendingLongPress();
                if (!scroller.isFinished()) {
                    scroller.abortAnimation();
                }
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
            }

            if (swipingHorizontally) {
                float stepDx = x - lastTouchX;
                lastTouchX = x;
                lastTouchY = y;
                horizScrollPx -= stepDx;
                if (isFreeDayScroll() && !floatingMode) {
                    normalizeFreeHorizScroll();
                } else {
                    float maxSpan = getColumnWidth() * (floatingMode ? 1.15f : 7f);
                    horizScrollPx = Math.max(-maxSpan, Math.min(maxSpan, horizScrollPx));
                }
                invalidate();
                return true;
            }
            lastTouchX = x;
            lastTouchY = y;
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            cancelPendingLongPress();
            if (swipingHorizontally) {
                swipingHorizontally = false;
                float vx = 0f;
                if (action == MotionEvent.ACTION_UP && velocityTracker != null) {
                    velocityTracker.computeCurrentVelocity(1000, dp(3400f));
                    vx = velocityTracker.getXVelocity();
                }
                recycleVelocityTracker();
                animateHorizontalSnap(vx, action == MotionEvent.ACTION_CANCEL);
                return true;
            }
            recycleVelocityTracker();
        }
        return gHandled || super.onTouchEvent(event);
    }

    private void animateHorizontalSnap(float velocityX, boolean canceled) {
        float colW = getColumnWidth();
        if (colW <= 1f) {
            horizScrollPx = 0f;
            invalidate();
            return;
        }

        int targetDays;
        if (canceled) {
            targetDays = 0;
        } else if (floatingMode) {
            if (Math.abs(velocityX) > dp(240f)) {
                targetDays = velocityX < 0 ? 1 : -1;
            } else if (Math.abs(horizScrollPx) > colW * 0.24f) {
                targetDays = horizScrollPx > 0 ? 1 : -1;
            } else {
                targetDays = 0;
            }
        } else if (isFreeDayScroll()) {
            // Free 1-day granularity for 3-Day & 1-Day views!
            if (Math.abs(velocityX) > dp(260f)) {
                float projectedPx = horizScrollPx - velocityX * 0.24f;
                float maxFlingPx = colW * 9f;
                projectedPx = Math.max(-maxFlingPx, Math.min(maxFlingPx, projectedPx));
                targetDays = Math.round(projectedPx / colW);
                if (targetDays == 0) {
                    targetDays = velocityX < 0 ? 1 : -1;
                }
            } else {
                targetDays = Math.round(horizScrollPx / colW);
            }
        } else {
            // 7-Day Week view snaps cleanly by ±7 days (1 full week)
            if (Math.abs(velocityX) > dp(260f)) {
                targetDays = velocityX < 0 ? 7 : -7;
            } else if (Math.abs(horizScrollPx) > colW * 1.35f) {
                targetDays = horizScrollPx > 0 ? 7 : -7;
            } else {
                targetDays = 0;
            }
        }

        int startX = Math.round(horizScrollPx);
        int destX = Math.round(targetDays * colW);
        int deltaX = destX - startX;
        if (Math.abs(deltaX) <= 1) {
            if (targetDays != 0) {
                startDate = startDate.plusDays(targetDays);
            }
            horizScrollPx = 0f;
            rebuildEventGeometry();
            if (listener != null) {
                int totalDelta = (int) (startDate.toEpochDay() - gestureBaseStartDate.toEpochDay());
                listener.onVisibleStartDateChanged(startDate, true);
                if (floatingMode && totalDelta != 0) {
                    listener.onSwipePeriod(totalDelta);
                }
            }
            invalidate();
            return;
        }

        int duration = Math.min(420, 175 + Math.abs(targetDays) * 40);
        lastHorizScrollerX = startX;
        horizAnimating = true;
        horizScroller.startScroll(startX, 0, deltaX, 0, duration);
        postInvalidateOnAnimation();
    }

    private void recycleVelocityTracker() {
        if (velocityTracker != null) {
            velocityTracker.recycle();
            velocityTracker = null;
        }
    }

    // ===================== Long-Press Event Drag Logic =====================

    private void scheduleLongPressIfOnEvent(float x, float y) {
        cancelPendingLongPress();
        int holdMs = AppPrefs.getEventDragHoldMs(getContext());
        if (holdMs <= 0) return;

        float gutterW = getGutterWidth();
        float rightPad = getRightPadding();
        float dayHeaderH = getDayHeaderHeight();
        float pinnedH = getPinnedHeaderHeight();
        if (x < gutterW || x > getWidth() - rightPad) return;

        if (y >= dayHeaderH && y < pinnedH) {
            for (int i = allDaySpans.size() - 1; i >= 0; i--) {
                AllDaySpan span = allDaySpans.get(i);
                if (!span.hitRect.isEmpty() && span.hitRect.contains(x, y)) {
                    pendingDragAllDay = span;
                    longPressScheduled = true;
                    postDelayed(longPressRunnable, holdMs);
                    return;
                }
            }
            return;
        }

        if (y >= pinnedH) {
            for (int i = timedBlocks.size() - 1; i >= 0; i--) {
                TimedBlock b = timedBlocks.get(i);
                if (!b.hitRect.isEmpty() && b.hitRect.contains(x, y)) {
                    pendingDragTimed = b;
                    longPressScheduled = true;
                    postDelayed(longPressRunnable, holdMs);
                    return;
                }
            }
        }
    }

    private void cancelPendingLongPress() {
        if (longPressScheduled) {
            removeCallbacks(longPressRunnable);
            longPressScheduled = false;
        }
        pendingDragTimed = null;
        pendingDragAllDay = null;
    }

    private void beginTimedEventDrag(TimedBlock b) {
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        if (getParent() != null) {
            getParent().requestDisallowInterceptTouchEvent(true);
        }
        if (!scroller.isFinished()) scroller.abortAnimation();
        if (!horizScroller.isFinished()) {
            horizScroller.abortAnimation();
            horizAnimating = false;
        }

        isDraggingEvent = true;
        draggingAllDay = false;
        draggingEvent = b.event;
        dragOrigCol = b.col;
        dragTargetCol = b.col;
        dragOrigStartMin = Math.max(0, Math.min(1425, Math.round(b.startMin)));
        dragTargetStartMin = dragOrigStartMin;

        long rawDurMs = (b.event.hasExplicitEnd && b.event.endMillis > b.event.beginMillis)
                ? (b.event.endMillis - b.event.beginMillis)
                : 3_600_000L;
        dragDurationMin = Math.max(15, Math.round(rawDurMs / 60_000f));

        float touchTimelineMin = ((downY - getPinnedHeaderHeight() + scrollYOffset) / getEffectiveHourPx()) * 60f;
        dragGrabOffsetMin = Math.max(0f, Math.min(b.endMin - b.startMin, touchTimelineMin - b.startMin));
        lastHorizontalAutoShiftMs = System.currentTimeMillis();
        invalidate();
    }

    private void beginAllDayEventDrag(AllDaySpan span) {
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        if (getParent() != null) {
            getParent().requestDisallowInterceptTouchEvent(true);
        }
        isDraggingEvent = true;
        draggingAllDay = true;
        draggingEvent = span.event;
        dragOrigStartEpoch = span.event.startEpochDay;
        dragTargetStartEpoch = dragOrigStartEpoch;
        dragSpanDays = Math.max(0L, span.event.endEpochDay - span.event.startEpochDay);

        float colW = getColumnWidth();
        int grabbedCol = Math.max(0, Math.min(columnCount - 1,
                (int) Math.floor((downX - getGutterWidth() + horizScrollPx) / colW)));
        long grabbedEpoch = startDate.plusDays(grabbedCol).toEpochDay();
        dragGrabColOffset = (int) (grabbedEpoch - span.event.startEpochDay);
        lastHorizontalAutoShiftMs = System.currentTimeMillis();
        invalidate();
    }

    private void updateDragTargetFromPoint(float x, float y) {
        if (!isDraggingEvent || draggingEvent == null) return;
        float gutterW = getGutterWidth();
        float colW = getColumnWidth();
        int hoveredCol = Math.max(0, Math.min(columnCount - 1,
                (int) Math.floor((x - gutterW + horizScrollPx) / colW)));

        if (draggingAllDay) {
            long hoveredEpoch = startDate.plusDays(hoveredCol).toEpochDay();
            long nextStartEpoch = hoveredEpoch - dragGrabColOffset;
            if (nextStartEpoch != dragTargetStartEpoch) {
                dragTargetStartEpoch = nextStartEpoch;
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            }
            return;
        }

        if (Math.hypot(x - downX, y - downY) < dp(5f)) {
            return;
        }

        float pinnedH = getPinnedHeaderHeight();
        float rawMin = ((y - pinnedH + scrollYOffset) / getEffectiveHourPx()) * 60f - dragGrabOffsetMin;
        int snappedMin = Math.round(rawMin / 15f) * 15;
        int maxStartMin = Math.max(0, 1440 - Math.min(1425, dragDurationMin));
        snappedMin = Math.max(0, Math.min(maxStartMin, snappedMin));

        if (hoveredCol != dragTargetCol || snappedMin != dragTargetStartMin) {
            dragTargetCol = hoveredCol;
            dragTargetStartMin = snappedMin;
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        }
    }

    private void finishEventDrag(boolean commit) {
        removeCallbacks(dragAutoScrollRunnable);
        CalendarEvent ev = draggingEvent;
        boolean wasAllDay = draggingAllDay;
        int finalCol = dragTargetCol;
        int finalStartMin = dragTargetStartMin;
        long finalStartEpoch = dragTargetStartEpoch;
        long spanDays = dragSpanDays;
        long origStartEpoch = dragOrigStartEpoch;

        isDraggingEvent = false;
        draggingEvent = null;
        recycleVelocityTracker();
        invalidate();

        if (!commit || ev == null || listener == null) return;

        if (wasAllDay) {
            if (finalStartEpoch == origStartEpoch) return;
            LocalDate newStart = LocalDate.ofEpochDay(finalStartEpoch);
            LocalDate newEnd = LocalDate.ofEpochDay(finalStartEpoch + spanDays);
            listener.onEventDragged(ev, true, newStart, newEnd, 0L, 0L);
        } else {
            LocalDate newStartDate = startDate.plusDays(finalCol);
            LocalTime newStartTime = LocalTime.of(finalStartMin / 60, finalStartMin % 60);
            ZonedDateTime newBeginZdt = newStartDate.atTime(newStartTime).atZone(zoneForDraw);
            long origDurMs = (ev.hasExplicitEnd && ev.endMillis > ev.beginMillis)
                    ? (ev.endMillis - ev.beginMillis)
                    : 3_600_000L;
            long newBeginMs = newBeginZdt.toInstant().toEpochMilli();
            long newEndMs = newBeginMs + origDurMs;
            if (Math.abs(newBeginMs - ev.beginMillis) < 60_000L) return;
            LocalDate newEndDate = Instant.ofEpochMilli(newEndMs).atZone(zoneForDraw).toLocalDate();
            listener.onEventDragged(ev, false, newStartDate, newEndDate, newBeginMs, newEndMs);
        }
    }

    // ===================== Geometry & Layout =====================

    private void scrollByVertical(float dy) {
        if (isFullDayFit()) return;
        scrollYOffset = clampScroll(scrollYOffset + dy);
        invalidate();
    }

    private float getViewportHeight() {
        return Math.max(1f, getHeight() - getPinnedHeaderHeight());
    }

    /** Exact pixel height per hour so 00:00–24:00 fills 100% of the viewport with zero dead space. */
    private float getFitHourPx() {
        if (getHeight() <= 0) return dp(25f);
        return Math.max(dp(16f), getViewportHeight() / 24f);
    }

    private float getEffectiveHourPx() {
        float fitPx = getFitHourPx();
        if (hourHeightDp <= 0f) {
            return fitPx;
        }
        return Math.max(fitPx, dp(hourHeightDp));
    }

    private int getMaxVerticalScroll() {
        if (getHeight() <= 0 || isFullDayFit()) return 0;
        float contentH = getEffectiveHourPx() * 24f;
        return Math.max(0, Math.round(contentH - getViewportHeight()));
    }

    private float clampScroll(float raw) {
        return Math.max(0f, Math.min(getMaxVerticalScroll(), raw));
    }

    private float getGutterWidth() {
        return dp(46f);
    }

    private float getRightPadding() {
        return floatingMode ? dp(10f) : dp(3f);
    }

    private float getDayHeaderHeight() {
        return compactDayHeader ? dp(4f) : dp(52f);
    }

    /** Hide the weekday/date header row (used by the floating single-day popup, which has its own). */
    public void setCompactDayHeader(boolean compact) {
        this.compactDayHeader = compact;
        invalidate();
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
        maxAllDayLaneUsed = 0;

        int marginCols = (columnCount == 7) ? 7 : 5;
        minRenderCol = -marginCols;
        maxRenderCol = columnCount - 1 + marginCols;
        int totalCols = maxRenderCol - minRenderCol + 1;
        if (colOverflowCounts.length < totalCols) {
            colOverflowCounts = new int[totalCols];
        }
        for (int i = 0; i < totalCols; i++) colOverflowCounts[i] = 0;

        long minEpoch = startDate.plusDays(minRenderCol).toEpochDay();
        long maxEpoch = startDate.plusDays(maxRenderCol).toEpochDay();
        long baseEpoch = startDate.toEpochDay();
        ZoneId zone = ZoneId.systemDefault();

        // 1. All-Day & Multi-Day Spanning Events across [minRenderCol, maxRenderCol]
        List<AllDaySpan> rawAllDay = new ArrayList<>();
        for (CalendarEvent ev : events) {
            if (ev.endEpochDay < minEpoch || ev.startEpochDay > maxEpoch) continue;
            if (ev.allDay || ev.isMultiDay()) {
                int sCol = (int) Math.max(minRenderCol, ev.startEpochDay - baseEpoch);
                int eCol = (int) Math.min(maxRenderCol, ev.endEpochDay - baseEpoch);
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
            // Only expand header height based on spans touching visible or immediately adjacent columns
            if (span.endCol >= -1 && span.startCol <= columnCount && assigned + 1 > maxAllDayLaneUsed) {
                maxAllDayLaneUsed = assigned + 1;
            }
        }

        // 2. Single-day Timed Events across [minRenderCol, maxRenderCol]
        for (int col = minRenderCol; col <= maxRenderCol; col++) {
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
            int span = 1;
            for (int nextCol = b.subCol + 1; nextCol < total; nextCol++) {
                boolean blocked = false;
                for (TimedBlock other : cluster) {
                    if (other.subCol == nextCol
                            && other.startMin < b.endMin - 0.5f
                            && other.endMin > b.startMin + 0.5f) {
                        blocked = true;
                        break;
                    }
                }
                if (blocked) break;
                span++;
            }
            b.spanSubCols = span;
        }
    }

    private void handleSingleTap(float x, float y) {
        float gutterW = getGutterWidth();
        float rightPad = getRightPadding();
        float dayHeaderH = getDayHeaderHeight();
        float pinnedH = getPinnedHeaderHeight();

        // 1. Toggle expand/collapse on All-Day chevron or tap an All-Day event pill
        if (y >= dayHeaderH && y < pinnedH) {
            if (x < gutterW && maxAllDayLaneUsed > 3) {
                allDayExpanded = !allDayExpanded;
                invalidate();
                return;
            }
            for (int i = allDaySpans.size() - 1; i >= 0; i--) {
                AllDaySpan span = allDaySpans.get(i);
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

        if (x < gutterW || x > getWidth() - rightPad) return;
        float colW = getColumnWidth();
        int col = (int) Math.floor((x - gutterW + horizScrollPx) / colW);
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
        int hour = Math.max(0, Math.min(23, (int) (timelineY / getEffectiveHourPx())));
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

        float gutterW = getGutterWidth();
        float rightPad = getRightPadding();
        float gridRightEdge = w - rightPad;
        float colW = getColumnWidth();
        float dayHeaderH = getDayHeaderHeight();
        float pinnedH = getPinnedHeaderHeight();
        float viewportH = Math.max(1f, h - pinnedH);
        float hourPx = getEffectiveHourPx();
        scrollYOffset = isFullDayFit() ? 0f : clampScroll(Math.max(0f, scrollYOffset));
        LocalDate today = LocalDate.now();

        int firstVisCol = Math.max(minRenderCol, (int) Math.floor(horizScrollPx / colW) - 1);
        int lastVisCol = Math.min(maxRenderCol, (int) Math.ceil((horizScrollPx + (gridRightEdge - gutterW)) / colW) + 1);

        // ================= 1A. PINNED LEFT TIME GUTTER (00:00 – 24:00) =================
        canvas.save();
        canvas.clipRect(0, pinnedH, gutterW, h);
        canvas.translate(0f, pinnedH - scrollYOffset);

        timeGutterPaint.setTextSize(hourPx < dp(29f) ? sp(9.6f) : sp(10.6f));
        timeGutterPaint.setColor(palette.textSecondary);
        Paint.FontMetrics gFm = timeGutterPaint.getFontMetrics();
        float labelHalfH = (-gFm.ascent - gFm.descent) * 0.5f;
        for (int hr = 0; hr <= 24; hr++) {
            float baseline;
            if (hr == 0) {
                baseline = -gFm.ascent + dp(2f);
            } else if (hr == 24) {
                baseline = 24f * hourPx - gFm.descent - dp(1.5f);
            } else {
                baseline = hr * hourPx + labelHalfH;
            }
            if (baseline + gFm.ascent < scrollYOffset - dp(1f)
                    || baseline + gFm.descent > scrollYOffset + viewportH + dp(1f)) {
                continue;
            }
            canvas.drawText(String.format(Locale.US, "%02d:00", hr),
                    gutterW - dp(6f), baseline, timeGutterPaint);
        }
        canvas.restore();

        // ================= 1B. SCROLLABLE DAY COLUMNS & TIMED EVENTS =================
        canvas.save();
        canvas.clipRect(gutterW, pinnedH, gridRightEdge, h);
        canvas.translate(-horizScrollPx, pinnedH - scrollYOffset);

        slotPaint.setColor(palette.bgCell);
        float slotGap = hourPx < dp(29f) ? dp(1.0f) : dp(1.4f);
        float slotRadius = dp(5f);

        for (int hr = 0; hr < 24; hr++) {
            float top = hr * hourPx;
            float bottom = top + hourPx;
            if (bottom < scrollYOffset - hourPx || top > scrollYOffset + viewportH + hourPx) {
                continue;
            }
            for (int c = firstVisCol; c <= lastVisCol; c++) {
                float left = gutterW + c * colW;
                tmpRect.set(left + slotGap, top + slotGap, left + colW - slotGap, bottom - slotGap);
                canvas.drawRoundRect(tmpRect, slotRadius, slotRadius, slotPaint);
            }
        }

        boolean compactZoom = hourPx < dp(30f);
        eventTitlePaint.setTextSize(sp(columnCount >= 7 ? 10.2f : (compactZoom ? 11.2f : 12f)));
        eventSubPaint.setTextSize(sp(columnCount >= 7 ? 9.5f : 11f));

        for (TimedBlock b : timedBlocks) {
            if (b.col < firstVisCol || b.col > lastVisCol) {
                b.hitRect.setEmpty();
                continue;
            }
            float colLeft = gutterW + b.col * colW + dp(1.6f);
            float usableColW = colW - dp(3.2f);
            float subW = usableColW / b.totalSubCols;

            float left = colLeft + b.subCol * subW + dp(0.6f);
            float right = left + subW * b.spanSubCols - dp(1.2f);
            float top = (b.startMin / 60f) * hourPx + dp(0.8f);
            float minBlockH = Math.min(dp(19f), hourPx * 0.85f);
            float bottom = Math.min(24f * hourPx - dp(0.8f),
                    Math.max(top + minBlockH, (b.endMin / 60f) * hourPx - dp(0.8f)));

            float screenLeft = Math.max(gutterW, left - horizScrollPx);
            float screenRight = Math.min(gridRightEdge, right - horizScrollPx);
            if (screenRight > screenLeft) {
                b.hitRect.set(screenLeft, top + pinnedH - scrollYOffset,
                        screenRight, bottom + pinnedH - scrollYOffset);
            } else {
                b.hitRect.setEmpty();
            }

            if (bottom < scrollYOffset - dp(20) || top > scrollYOffset + viewportH + dp(20)) {
                continue;
            }

            boolean isBeingDragged = isDraggingEvent && !draggingAllDay && b.event == draggingEvent;
            tmpRect.set(left, top, right, bottom);
            if (isBeingDragged) {
                pillPaint.setColor((b.event.color & 0x00FFFFFF) | 0x4A000000);
            } else {
                pillPaint.setColor(b.event.color);
            }
            canvas.drawRoundRect(tmpRect, dp(5.5f), dp(5.5f), pillPaint);

            float contentTop = (top < scrollYOffset)
                    ? Math.min(Math.max(top, bottom - dp(24f)), scrollYOffset + dp(2f))
                    : top;
            float visibleH = bottom - contentTop;

            int textW = (int) ((right - left) - dp(7f));
            if (textW > dp(8f) && visibleH >= dp(12f)) {
                int baseTxt = AppTheme.textColorOnPill(b.event.color);
                eventTitlePaint.setColor(isBeingDragged ? ((baseTxt & 0x00FFFFFF) | 0x66000000) : baseTxt);
                if (visibleH < dp(29f)) {
                    CharSequence ellip = TextUtils.ellipsize(
                            b.event.title, eventTitlePaint, textW, TextUtils.TruncateAt.END);
                    Paint.FontMetrics fm = eventTitlePaint.getFontMetrics();
                    float baseline = contentTop + (visibleH - fm.bottom - fm.top) * 0.5f;
                    canvas.drawText(ellip, 0, ellip.length(), left + dp(4f), baseline, eventTitlePaint);
                } else {
                    int boxH = (int) (visibleH - dp(4.5f));
                    String body = b.event.title;
                    if (columnCount == 1 && boxH > dp(28f)) {
                        body = b.event.title + "\n" + b.event.formatTimeRange(zoneForDraw);
                        if (!TextUtils.isEmpty(b.event.location) && boxH > dp(46f)) {
                            body = body + "\n📍 " + b.event.location.trim();
                        } else if (b.event.isNotion() && !TextUtils.isEmpty(b.event.statusLabel) && boxH > dp(46f)) {
                            body = body + "\n" + b.event.statusLabel;
                        }
                    } else if (!TextUtils.isEmpty(b.event.location) && boxH > dp(34f)) {
                        body = b.event.title + "\n" + b.event.location.trim();
                    } else if (b.event.isNotion() && !TextUtils.isEmpty(b.event.statusLabel) && boxH > dp(34f)) {
                        body = b.event.title + "\n" + b.event.statusLabel;
                    }
                    canvas.save();
                    canvas.clipRect(left + dp(4f), contentTop + dp(2.2f), left + dp(4f) + textW, bottom - dp(2f));
                    canvas.translate(left + dp(4f), contentTop + dp(2.2f));
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
        }

        // Live Current-Time Indicator Line + Dot on Today's Column
        for (int c = firstVisCol; c <= lastVisCol; c++) {
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

        // Elevated Floating Preview Card when dragging a timed event
        if (isDraggingEvent && !draggingAllDay && draggingEvent != null) {
            float dLeft = gutterW + dragTargetCol * colW + dp(1.5f);
            float dRight = dLeft + colW - dp(3f);
            float dTop = (dragTargetStartMin / 60f) * hourPx;
            float minDragH = Math.min(dp(20f), hourPx * 0.9f);
            float dBottom = Math.min(24f * hourPx,
                    Math.max(dTop + minDragH, ((dragTargetStartMin + dragDurationMin) / 60f) * hourPx));

            // Soft drop shadow
            tmpRect.set(dLeft + dp(1.5f), dTop + dp(3f), dRight + dp(1.5f), dBottom + dp(3f));
            pillPaint.setColor(0x44000000);
            canvas.drawRoundRect(tmpRect, dp(6.5f), dp(6.5f), pillPaint);

            // Vivid lifted block + crisp highlight border
            tmpRect.set(dLeft, dTop, dRight, dBottom);
            pillPaint.setColor(draggingEvent.color | 0xFF000000);
            canvas.drawRoundRect(tmpRect, dp(6.5f), dp(6.5f), pillPaint);
            dragStrokePaint.setColor(palette.isDark ? 0xFFFFFFFF : 0xFF1A73E8);
            dragStrokePaint.setStrokeWidth(dp(2f));
            canvas.drawRoundRect(tmpRect, dp(6.5f), dp(6.5f), dragStrokePaint);

            int textW = (int) ((dRight - dLeft) - dp(8f));
            float boxH = dBottom - dTop;
            if (textW > dp(8f) && boxH >= dp(12f)) {
                eventTitlePaint.setColor(AppTheme.textColorOnPill(draggingEvent.color));
                CharSequence ellip = TextUtils.ellipsize(
                        draggingEvent.title, eventTitlePaint, textW, TextUtils.TruncateAt.END);
                Paint.FontMetrics fm = eventTitlePaint.getFontMetrics();
                float baseline = dTop + Math.min(boxH * 0.5f, dp(12f)) - (fm.bottom + fm.top) * 0.5f;
                canvas.drawText(ellip, 0, ellip.length(), dLeft + dp(4f), baseline, eventTitlePaint);
            }
        }

        canvas.restore();

        // ================= 2. PINNED HEADER + ALL-DAY BANNER =================
        bgPaint.setColor(palette.bgAppBar);
        canvas.drawRect(0, 0, w, pinnedH, bgPaint);

        canvas.save();
        canvas.clipRect(gutterW, 0, gridRightEdge, pinnedH);

        dowPaint.setTextSize(sp(11.5f));
        dayNumPaint.setTextSize(sp(15.5f));
        lunarPaint.setTextSize(sp(10f));

        for (int c = firstVisCol; c <= lastVisCol && !compactDayHeader; c++) {
            LocalDate d = startDate.plusDays(c);
            boolean isToday = d.equals(today);
            float cx = gutterW + c * colW + colW * 0.5f - horizScrollPx;

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
            String dayStr = (d.getDayOfMonth() == 1 && !isToday && columnCount <= 3)
                    ? (d.getMonthValue() + "/1")
                    : String.valueOf(d.getDayOfMonth());
            canvas.drawText(dayStr, cx, dp(33.5f), dayNumPaint);
            canvas.drawText(LunarCalendarHelper.getDayShort(d), cx, dp(45.5f), lunarPaint);
        }

        // All-Day Event Lanes
        int visLanes = getVisibleAllDayLanes();
        int totalCols = maxRenderCol - minRenderCol + 1;
        for (int i = 0; i < totalCols; i++) colOverflowCounts[i] = 0;
        for (AllDaySpan span : allDaySpans) {
            span.hitRect.setEmpty();
            if (span.lane >= visLanes) {
                for (int col = span.startCol; col <= span.endCol; col++) {
                    int idx = col - minRenderCol;
                    if (idx >= 0 && idx < totalCols) colOverflowCounts[idx]++;
                }
                continue;
            }
            float left = gutterW + span.startCol * colW + dp(1.8f) - horizScrollPx;
            float right = gutterW + (span.endCol + 1) * colW - dp(1.8f) - horizScrollPx;
            if (right <= gutterW || left >= gridRightEdge) continue;

            float top = dayHeaderH + span.lane * dp(21.5f) + dp(1f);
            float bottom = top + dp(19.2f);

            span.hitRect.set(Math.max(gutterW, left), top, Math.min(gridRightEdge, right), bottom);
            boolean isBeingDragged = isDraggingEvent && draggingAllDay && span.event == draggingEvent;
            tmpRect.set(left, top, right, bottom);
            pillPaint.setColor(isBeingDragged
                    ? ((span.event.color & 0x00FFFFFF) | 0x4A000000)
                    : span.event.color);
            canvas.drawRoundRect(tmpRect, dp(5f), dp(5f), pillPaint);

            // Keep spanning all-day title pinned inside the visible left edge
            float textStartX = Math.max(left + dp(4.5f), gutterW + dp(4.5f));
            float availW = (Math.min(right, gridRightEdge) - dp(4f)) - textStartX;
            if (availW > dp(8f)) {
                eventTitlePaint.setTextSize(sp(11f));
                int baseTxt = AppTheme.textColorOnPill(span.event.color);
                eventTitlePaint.setColor(isBeingDragged ? ((baseTxt & 0x00FFFFFF) | 0x66000000) : baseTxt);
                CharSequence ellip = TextUtils.ellipsize(
                        span.event.title, eventTitlePaint, availW, TextUtils.TruncateAt.END);
                Paint.FontMetrics fm = eventTitlePaint.getFontMetrics();
                float baseline = top + (dp(19.2f) - fm.bottom - fm.top) * 0.5f;
                canvas.drawText(ellip, 0, ellip.length(), textStartX, baseline, eventTitlePaint);
            }
        }

        if (maxAllDayLaneUsed > visLanes) {
            eventSubPaint.setColor(palette.textPrimary);
            eventSubPaint.setTextSize(sp(11f));
            float overflowBaseline = dayHeaderH + visLanes * dp(21.5f) + dp(12.5f);
            for (int c = firstVisCol; c <= lastVisCol; c++) {
                int idx = c - minRenderCol;
                if (idx >= 0 && idx < totalCols && colOverflowCounts[idx] > 0) {
                    canvas.drawText("+" + colOverflowCounts[idx],
                            gutterW + c * colW + dp(5f) - horizScrollPx, overflowBaseline, eventSubPaint);
                }
            }
        }

        // Lifted preview for dragged All-Day event
        if (isDraggingEvent && draggingAllDay && draggingEvent != null) {
            long baseEpoch = startDate.toEpochDay();
            float sCol = dragTargetStartEpoch - baseEpoch;
            float eCol = (dragTargetStartEpoch + dragSpanDays) - baseEpoch;
            float dLeft = gutterW + sCol * colW + dp(1.8f) - horizScrollPx;
            float dRight = gutterW + (eCol + 1f) * colW - dp(1.8f) - horizScrollPx;
            float dTop = dayHeaderH + dp(1.5f);
            float dBottom = dTop + dp(19.5f);
            tmpRect.set(dLeft, dTop, dRight, dBottom);
            pillPaint.setColor(draggingEvent.color | 0xFF000000);
            canvas.drawRoundRect(tmpRect, dp(5.5f), dp(5.5f), pillPaint);
            dragStrokePaint.setColor(palette.isDark ? 0xFFFFFFFF : 0xFF1A73E8);
            dragStrokePaint.setStrokeWidth(dp(1.8f));
            canvas.drawRoundRect(tmpRect, dp(5.5f), dp(5.5f), dragStrokePaint);
        }

        canvas.restore();

        if (maxAllDayLaneUsed > 3) {
            dowPaint.setColor(palette.textPrimary);
            dowPaint.setTextSize(sp(14f));
            canvas.drawText(allDayExpanded ? "⌃" : "⌄",
                    gutterW * 0.5f, pinnedH - dp(5f), dowPaint);
        }

        // Bottom hairline divider under pinned header
        linePaint.setColor(palette.divider);
        linePaint.setStrokeWidth(dp(1f));
        canvas.drawLine(0, pinnedH, w, pinnedH, linePaint);

        // ================= 3. FLOATING TIME TOOLTIP BADGE DURING DRAG =================
        if (isDraggingEvent && draggingEvent != null) {
            String badgeText;
            float badgeCenterX;
            float badgeTopY;
            if (draggingAllDay) {
                LocalDate dStart = LocalDate.ofEpochDay(dragTargetStartEpoch);
                LocalDate dEnd = LocalDate.ofEpochDay(dragTargetStartEpoch + dragSpanDays);
                badgeText = dragSpanDays > 0
                        ? String.format(Locale.TAIWAN, "移至 %d/%d (%s) → %d/%d",
                        dStart.getMonthValue(), dStart.getDayOfMonth(),
                        DOW_LABELS[dStart.getDayOfWeek().getValue() - 1],
                        dEnd.getMonthValue(), dEnd.getDayOfMonth())
                        : String.format(Locale.TAIWAN, "移至 %d/%d (%s) 全天",
                        dStart.getMonthValue(), dStart.getDayOfMonth(),
                        DOW_LABELS[dStart.getDayOfWeek().getValue() - 1]);
                badgeCenterX = Math.max(gutterW + dp(70f), Math.min(gridRightEdge - dp(70f), lastTouchX));
                badgeTopY = pinnedH + dp(6f);
            } else {
                LocalDate dDate = startDate.plusDays(dragTargetCol);
                int sH = dragTargetStartMin / 60;
                int sM = dragTargetStartMin % 60;
                int endTotal = dragTargetStartMin + dragDurationMin;
                int eH = (endTotal / 60) % 24;
                int eM = endTotal % 60;
                badgeText = String.format(Locale.TAIWAN, "%d/%d (%s)  %02d:%02d – %02d:%02d",
                        dDate.getMonthValue(), dDate.getDayOfMonth(),
                        DOW_LABELS[dDate.getDayOfWeek().getValue() - 1],
                        sH, sM, eH, eM);
                float colCenter = gutterW + dragTargetCol * colW + colW * 0.5f - horizScrollPx;
                float blockScreenTop = pinnedH - scrollYOffset + (dragTargetStartMin / 60f) * hourPx;
                float blockScreenBot = blockScreenTop + (dragDurationMin / 60f) * hourPx;
                badgeCenterX = colCenter;
                badgeTopY = (blockScreenTop - dp(28f) >= pinnedH + dp(2f))
                        ? (blockScreenTop - dp(26f))
                        : Math.min(h - dp(28f), blockScreenBot + dp(4f));
            }

            dragBadgePaint.setTextSize(sp(11.5f));
            float tw = dragBadgePaint.measureText(badgeText);
            float padX = dp(10f);
            float bh = dp(22f);
            float left = Math.max(dp(6f), Math.min(w - tw - padX * 2f - dp(6f), badgeCenterX - tw * 0.5f - padX));
            tmpRect.set(left, badgeTopY, left + tw + padX * 2f, badgeTopY + bh);
            pillPaint.setColor(0xEE1F242D);
            canvas.drawRoundRect(tmpRect, dp(11f), dp(11f), pillPaint);
            dragBadgePaint.setColor(0xFFFFFFFF);
            Paint.FontMetrics bFm = dragBadgePaint.getFontMetrics();
            float bBase = badgeTopY + (bh - bFm.bottom - bFm.top) * 0.5f;
            canvas.drawText(badgeText, tmpRect.centerX(), bBase, dragBadgePaint);
        }
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    private float sp(float v) {
        return v * getResources().getDisplayMetrics().scaledDensity;
    }
}
