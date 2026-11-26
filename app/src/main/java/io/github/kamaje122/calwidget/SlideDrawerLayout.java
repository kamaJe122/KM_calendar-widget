package io.github.kamaje122.calwidget;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;

/**
 * Lightweight, dependency-free slide-out left Navigation Drawer container with animated scrim
 * and swipe-to-close / left-edge-drag-to-open support.
 */
public final class SlideDrawerLayout extends FrameLayout {

    private final View scrimView;
    private View drawerPanel;
    private float openFraction = 0f;
    private boolean drawerOpen = false;

    private float downX;
    private float downY;
    private boolean draggingDrawer = false;
    private float startFraction = 0f;

    public SlideDrawerLayout(Context context) {
        super(context);
        scrimView = new View(context);
        scrimView.setBackgroundColor(Color.BLACK);
        scrimView.setAlpha(0f);
        scrimView.setVisibility(GONE);
        scrimView.setOnClickListener(v -> closeDrawer());
    }

    public void attachViews(View mainContent, View leftDrawer, int drawerWidthPx) {
        removeAllViews();
        addView(mainContent, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        addView(scrimView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        this.drawerPanel = leftDrawer;
        LayoutParams dLp = new LayoutParams(drawerWidthPx, LayoutParams.MATCH_PARENT, Gravity.START);
        leftDrawer.setElevation(24f * getResources().getDisplayMetrics().density);
        addView(leftDrawer, dLp);
        applyFraction(0f);
    }

    public boolean isDrawerOpen() {
        return drawerOpen;
    }

    public void openDrawer() {
        drawerOpen = true;
        animateTo(1f);
    }

    public void closeDrawer() {
        drawerOpen = false;
        animateTo(0f);
    }

    private void animateTo(float target) {
        ValueAnimator anim = ValueAnimator.ofFloat(openFraction, target);
        anim.setDuration(210L);
        anim.setInterpolator(new DecelerateInterpolator());
        anim.addUpdateListener(a -> applyFraction((float) a.getAnimatedValue()));
        anim.start();
    }

    private void applyFraction(float f) {
        openFraction = Math.max(0f, Math.min(1f, f));
        if (drawerPanel != null) {
            int w = drawerPanel.getLayoutParams().width;
            if (w <= 0) w = Math.round(305 * getResources().getDisplayMetrics().density);
            drawerPanel.setTranslationX(-w * (1f - openFraction));
            drawerPanel.setVisibility(openFraction > 0.002f ? VISIBLE : INVISIBLE);
        }
        if (openFraction > 0.002f) {
            scrimView.setVisibility(VISIBLE);
            scrimView.setAlpha(0.46f * openFraction);
        } else {
            scrimView.setAlpha(0f);
            scrimView.setVisibility(GONE);
        }
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (drawerPanel == null) return super.onInterceptTouchEvent(ev);
        int action = ev.getActionMasked();
        float d = getResources().getDisplayMetrics().density;
        if (action == MotionEvent.ACTION_DOWN) {
            downX = ev.getX();
            downY = ev.getY();
            draggingDrawer = false;
            startFraction = openFraction;
            // Edge peek gesture from far left 22dp when closed
            if (!drawerOpen && downX <= 22f * d) {
                draggingDrawer = true;
                return true;
            }
        } else if (action == MotionEvent.ACTION_MOVE && drawerOpen) {
            float dx = ev.getX() - downX;
            float dy = ev.getY() - downY;
            if (dx < -14f * d && Math.abs(dx) > Math.abs(dy) * 1.25f) {
                draggingDrawer = true;
                return true;
            }
        }
        return super.onInterceptTouchEvent(ev);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (!draggingDrawer || drawerPanel == null) {
            return super.onTouchEvent(ev);
        }
        int w = Math.max(1, drawerPanel.getLayoutParams().width);
        int action = ev.getActionMasked();
        if (action == MotionEvent.ACTION_MOVE) {
            float dx = ev.getX() - downX;
            applyFraction(startFraction + (dx / w));
            return true;
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            draggingDrawer = false;
            if (openFraction >= 0.42f) {
                openDrawer();
            } else {
                closeDrawer();
            }
            return true;
        }
        return true;
    }
}
