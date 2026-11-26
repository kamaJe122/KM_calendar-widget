package io.github.kamaje122.calwidget;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;

/**
 * Google-Calendar-inspired Material 3 color palettes for Light & Dark modes,
 * plus contrast-aware text color helpers for colored event pills.
 */
public final class AppTheme {

    public static final class Palette {
        public final boolean isDark;
        public final int bgAppBar;
        public final int bgCanvas;
        public final int bgCell;
        public final int bgCellOutsideMonth;
        public final int gridBorder;
        public final int textPrimary;
        public final int textSecondary;
        public final int textDim;
        public final int todayFill;
        public final int todayText;
        public final int selectedStroke;
        public final int nowIndicator;
        public final int fabBg;
        public final int fabIcon;
        public final int drawerBg;
        public final int drawerActivePill;
        public final int drawerActiveText;
        public final int divider;
        public final int cardElevated;
        public final int chipBg;

        Palette(boolean isDark, int bgAppBar, int bgCanvas, int bgCell, int bgCellOutsideMonth,
                int gridBorder, int textPrimary, int textSecondary, int textDim,
                int todayFill, int todayText, int selectedStroke, int nowIndicator,
                int fabBg, int fabIcon, int drawerBg, int drawerActivePill, int drawerActiveText,
                int divider, int cardElevated, int chipBg) {
            this.isDark = isDark;
            this.bgAppBar = bgAppBar;
            this.bgCanvas = bgCanvas;
            this.bgCell = bgCell;
            this.bgCellOutsideMonth = bgCellOutsideMonth;
            this.gridBorder = gridBorder;
            this.textPrimary = textPrimary;
            this.textSecondary = textSecondary;
            this.textDim = textDim;
            this.todayFill = todayFill;
            this.todayText = todayText;
            this.selectedStroke = selectedStroke;
            this.nowIndicator = nowIndicator;
            this.fabBg = fabBg;
            this.fabIcon = fabIcon;
            this.drawerBg = drawerBg;
            this.drawerActivePill = drawerActivePill;
            this.drawerActiveText = drawerActiveText;
            this.divider = divider;
            this.cardElevated = cardElevated;
            this.chipBg = chipBg;
        }
    }

    // Matches the user's Google Calendar screenshots (warm-cool light gray + crisp white rounded slots)
    private static final Palette LIGHT = new Palette(
            false,
            0xFFEDEFF3, // bgAppBar
            0xFFE6E9EF, // bgCanvas (gap between rounded white cells)
            0xFFFFFFFF, // bgCell
            0xFFF6F7FA, // bgCellOutsideMonth
            0xFFDCE0E8, // gridBorder
            0xFF1F1F1F, // textPrimary
            0xFF5F6368, // textSecondary
            0xFF9AA0A6, // textDim
            0xFF1B1B1F, // todayFill (solid dark pill/circle like screenshot "26 (十八)")
            0xFFFFFFFF, // todayText
            0xFF1A73E8, // selectedStroke
            0xFF1B1B1F, // nowIndicator (black dot + horizontal line)
            0xFF2D3135, // fabBg (dark rounded-square '+' button)
            0xFFFFFFFF, // fabIcon
            0xFFF4F6FA, // drawerBg
            0xFF42454A, // drawerActivePill
            0xFFFFFFFF, // drawerActiveText
            0xFFDADCE0, // divider
            0xFFFFFFFF, // cardElevated
            0xFFE2E6EE  // chipBg
    );

    private static final Palette DARK = new Palette(
            true,
            0xFF14171F, // bgAppBar
            0xFF0E1117, // bgCanvas
            0xFF1B202B, // bgCell
            0xFF151922, // bgCellOutsideMonth
            0xFF282F3E, // gridBorder
            0xFFE8EAED, // textPrimary
            0xFF9AA0A6, // textSecondary
            0xFF5F6672, // textDim
            0xFFA8C7FA, // todayFill
            0xFF062E6F, // todayText
            0xFF8AB4F8, // selectedStroke
            0xFFE8EAED, // nowIndicator
            0xFF334155, // fabBg
            0xFFFFFFFF, // fabIcon
            0xFF181C26, // drawerBg
            0xFF334155, // drawerActivePill
            0xFFE8EAED, // drawerActiveText
            0xFF2A3140, // divider
            0xFF212735, // cardElevated
            0xFF262D3D  // chipBg
    );

    private AppTheme() {
    }

    public static boolean resolveIsDark(Context context) {
        int mode = AppPrefs.getThemeMode(context);
        if (mode == AppPrefs.THEME_LIGHT) return false;
        if (mode == AppPrefs.THEME_DARK) return true;
        int nightFlags = context.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        return nightFlags == Configuration.UI_MODE_NIGHT_YES;
    }

    public static Palette of(Context context) {
        return resolveIsDark(context) ? DARK : LIGHT;
    }

    /**
     * Picks dark charcoal text (#1F1F1F) on bright/yellow/amber pills and crisp white (#FFFFFF)
     * on darker pills — matching Google Calendar's event pill contrast.
     */
    public static int textColorOnPill(int bgColor) {
        int r = Color.red(bgColor);
        int g = Color.green(bgColor);
        int b = Color.blue(bgColor);
        double lum = 0.299 * r + 0.587 * g + 0.114 * b;
        return lum >= 158.0 ? 0xFF1F1F1F : 0xFFFFFFFF;
    }

    public static int subTextColorOnPill(int bgColor) {
        int main = textColorOnPill(bgColor);
        return main == 0xFF1F1F1F ? 0xCC1F1F1F : 0xEBFFFFFF;
    }

    /** Slightly softens overly neon calendar colors to match Google Calendar's pleasant pill tones. */
    public static int normalizePillColor(int rawColor) {
        return rawColor | 0xFF000000;
    }
}
