package io.github.kamaje122.calwidget;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.appwidget.AppWidgetManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsetsController;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Main Google-Calendar-inspired Calendar App ("KM 行事曆"):
 * - Supports 5 switchable views: 時間表 (Schedule), 天 (1-Day), 3 天 (3-Day), 週 (Week), 月 (Month)
 * - Traditional Chinese Lunar dates & sexagenary year/month subtitle ("丙午年九月 - 十月")
 * - Slide-out Left Navigation Drawer with Google + Notion calendar checkboxes, theme & week-start toggles
 * - Deep-links seamlessly with Desktop Widgets & WidgetConfigActivity
 */
public class MainActivity extends Activity {

    public static final String EXTRA_TARGET_EPOCH_DAY = "km_target_epoch_day";
    public static final String EXTRA_OPEN_CREATE_EVENT = "km_open_create_event";
    public static final String EXTRA_DISMISS_NOTIF_ID = "km_dismiss_notif_id";

    private static final int REQ_PERMISSIONS = 201;
    private static final int REQ_POST_NOTIF_PREVIEW = 202;
    private static final String[] CALENDAR_PERMS = {
            Manifest.permission.READ_CALENDAR,
            Manifest.permission.WRITE_CALENDAR
    };

    private final ExecutorService dataExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private int loadGeneration = 0;

    private AppTheme.Palette palette;
    private int viewMode;
    private LocalDate anchorDate = LocalDate.now();
    private boolean hasAvailableUpdate = false;

    // Root & Shell Views
    private SlideDrawerLayout drawerLayout;
    private LinearLayout mainRoot;
    private LinearLayout topBar;
    private TextView topMonthTitle;
    private TextView topLunarSubtitle;
    private TodayBadgeIconView todayBadgeBtn;
    private SettingsBadgeIconView settingsBadgeBtn;
    private LinearLayout permissionBanner;
    private FrameLayout viewportFrame;
    private View fabPlus;

    // Calendar Views
    private MonthCalendarView monthView;
    private MultiDayTimelineView timelineView;
    private ScheduleAgendaView scheduleView;

    // Drawer Views
    private ScrollView drawerScroll;
    private LinearLayout drawerContent;

    private final CalendarDialogs.Callbacks dialogCallbacks = new CalendarDialogs.Callbacks() {
        @Override
        public void onJumpToDateAndView(LocalDate date, int optionalViewMode) {
            anchorDate = date;
            if (optionalViewMode >= AppPrefs.VIEW_SCHEDULE && optionalViewMode <= AppPrefs.VIEW_MONTH) {
                viewMode = optionalViewMode;
                AppPrefs.setViewMode(MainActivity.this, viewMode);
                rebuildDrawerContent();
            }
            if (timelineView != null) {
                timelineView.scrollToDefaultHour();
            }
            reloadCalendarData();
        }

        @Override
        public void onEventsMutated() {
            reloadCalendarData();
        }

        @Override
        public void onRequestWritePermission() {
            requestPermissions(CALENDAR_PERMS, REQ_PERMISSIONS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Forward legacy launcher APPWIDGET_CONFIGURE intents cleanly to WidgetConfigActivity
        Intent launchIntent = getIntent();
        if (launchIntent != null && AppWidgetManager.ACTION_APPWIDGET_CONFIGURE.equals(launchIntent.getAction())) {
            Intent fwd = new Intent(this, WidgetConfigActivity.class);
            fwd.putExtras(launchIntent);
            fwd.addFlags(Intent.FLAG_ACTIVITY_FORWARD_RESULT);
            startActivity(fwd);
            finish();
            return;
        }

        viewMode = AppPrefs.getViewMode(this);
        palette = AppTheme.of(this);
        EventReminderManager.ensureChannel(this);

        buildUiHierarchy();
        applyThemeToShell();
        handleIncomingDeepLink(launchIntent);

        List<String> initialPerms = new ArrayList<>();
        if (!CalendarDataRepository.hasReadPermission(this) || !CalendarDataRepository.hasWritePermission(this)) {
            initialPerms.add(Manifest.permission.READ_CALENDAR);
            initialPerms.add(Manifest.permission.WRITE_CALENDAR);
        }
        if (Build.VERSION.SDK_INT >= 33
                && AppPrefs.getNotifMode(this) != AppPrefs.NOTIF_MODE_OFF
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                && !AppPrefs.hasAskedPostNotifications(this)) {
            AppPrefs.setAskedPostNotifications(this, true);
            initialPerms.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!initialPerms.isEmpty()) {
            requestPermissions(initialPerms.toArray(new String[0]), REQ_PERMISSIONS);
        }

        // Silent check for GitHub update badge on ⚙️ icon
        AppUpdater.checkLatestRelease(this, new AppUpdater.CheckCallback() {
            @Override
            public void onSuccess(AppUpdater.ReleaseInfo info) {
                hasAvailableUpdate = info.hasNewerUpdate;
                if (settingsBadgeBtn != null) {
                    settingsBadgeBtn.setShowDot(hasAvailableUpdate);
                }
            }

            @Override
            public void onError(String message) {
            }
        });

        // Trigger background Notion refresh if connected
        if (NotionManager.hasToken(this)) {
            NotionManager.refreshEventsOnlyAsync(this, 45_000L, new NotionManager.SyncCallback() {
                @Override
                public void onSuccess(String workspaceName, int sourceCount, int eventCount) {
                    reloadCalendarData();
                    rebuildDrawerContent();
                }

                @Override
                public void onError(String errorMessage) {
                }
            });
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingDeepLink(intent);
        reloadCalendarData();
    }

    @Override
    protected void onResume() {
        super.onResume();
        palette = AppTheme.of(this);
        if (todayBadgeBtn != null) {
            todayBadgeBtn.setDayNumber(LocalDate.now().getDayOfMonth());
        }
        applyThemeToShell();
        rebuildDrawerContent();
        reloadCalendarData();
        EventReminderManager.scheduleAsync(this);
    }

    @Override
    public void onBackPressed() {
        if (drawerLayout != null && drawerLayout.isDrawerOpen()) {
            drawerLayout.closeDrawer();
            return;
        }
        super.onBackPressed();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        CalendarWidgetProvider.updateAll(this);
        EventReminderManager.scheduleAsync(this);
        if (requestCode == REQ_POST_NOTIF_PREVIEW && EventReminderManager.canPostNotifications(this)) {
            triggerTestNotificationPreview();
        }
        rebuildDrawerContent();
        reloadCalendarData();
    }

    private void handleIncomingDeepLink(Intent intent) {
        if (intent == null) return;
        if (intent.hasExtra(EXTRA_DISMISS_NOTIF_ID)) {
            int nid = intent.getIntExtra(EXTRA_DISMISS_NOTIF_ID, 0);
            intent.removeExtra(EXTRA_DISMISS_NOTIF_ID);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null && nid != 0) nm.cancel(nid);
            EventReminderManager.syncGroupSummary(this);
        }
        if (intent.hasExtra(EXTRA_TARGET_EPOCH_DAY)) {
            long epoch = intent.getLongExtra(EXTRA_TARGET_EPOCH_DAY, LocalDate.now().toEpochDay());
            anchorDate = LocalDate.ofEpochDay(epoch);
            intent.removeExtra(EXTRA_TARGET_EPOCH_DAY);
        }

        final CalendarEvent tappedEvent = CalendarEvent.fromIntent(intent);
        if (tappedEvent != null) {
            intent.removeExtra(CalendarEvent.EXTRA_HAS_EVENT);
            mainHandler.post(() -> CalendarDialogs.showEventDetailDialog(
                    this, tappedEvent, palette, dialogCallbacks));
        } else if (intent.getBooleanExtra(EXTRA_OPEN_CREATE_EVENT, false)) {
            intent.removeExtra(EXTRA_OPEN_CREATE_EVENT);
            mainHandler.post(() -> CalendarDialogs.showCreateEventDialog(
                    this, anchorDate, -1, palette, dialogCallbacks));
        }
    }

    private void buildUiHierarchy() {
        drawerLayout = new SlideDrawerLayout(this);
        drawerLayout.setFitsSystemWindows(true);

        // ================= MAIN CONTENT AREA =================
        mainRoot = new LinearLayout(this);
        mainRoot.setOrientation(LinearLayout.VERTICAL);

        // 1. Top App Bar (matches Google Calendar screenshot)
        topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(8), dp(8), dp(10), dp(6));

        HamburgerIconView menuBtn = new HamburgerIconView(this);
        menuBtn.setContentDescription("開啟選單");
        menuBtn.setOnClickListener(v -> drawerLayout.openDrawer());
        topBar.addView(menuBtn, new LinearLayout.LayoutParams(dp(44), dp(44)));

        LinearLayout titleCol = new LinearLayout(this);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        titleCol.setPadding(dp(6), dp(2), dp(8), dp(2));
        titleCol.setOnClickListener(v -> CalendarDialogs.showMonthYearPickerDialog(
                this, YearMonth.from(anchorDate), palette, dialogCallbacks));

        topMonthTitle = new TextView(this);
        topMonthTitle.setTextSize(21f);
        topMonthTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        topMonthTitle.setSingleLine(true);
        titleCol.addView(topMonthTitle);

        topLunarSubtitle = new TextView(this);
        topLunarSubtitle.setTextSize(12f);
        topLunarSubtitle.setSingleLine(true);
        titleCol.addView(topLunarSubtitle);

        topBar.addView(titleCol, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // Search icon button
        SearchIconView searchBtn = new SearchIconView(this);
        searchBtn.setContentDescription("搜尋行程");
        searchBtn.setOnClickListener(v -> CalendarDialogs.showSearchDialog(
                this, AppPrefs.getAppHiddenCalendars(this), palette, dialogCallbacks));
        topBar.addView(searchBtn, new LinearLayout.LayoutParams(dp(42), dp(42)));

        // Today "[26]" icon button
        todayBadgeBtn = new TodayBadgeIconView(this, LocalDate.now().getDayOfMonth());
        todayBadgeBtn.setContentDescription("回到今天");
        todayBadgeBtn.setOnClickListener(v -> {
            anchorDate = LocalDate.now();
            if (timelineView != null) {
                timelineView.scrollToDefaultHour();
            }
            reloadCalendarData();
        });
        topBar.addView(todayBadgeBtn, new LinearLayout.LayoutParams(dp(42), dp(42)));

        // Widget & Notion Settings button
        settingsBadgeBtn = new SettingsBadgeIconView(this);
        settingsBadgeBtn.setContentDescription("小工具、Notion 與更新設定");
        settingsBadgeBtn.setOnClickListener(v ->
                startActivity(new Intent(this, WidgetConfigActivity.class)));
        topBar.addView(settingsBadgeBtn, new LinearLayout.LayoutParams(dp(42), dp(42)));

        mainRoot.addView(topBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 2. Optional Permission Onboarding Strip
        permissionBanner = new LinearLayout(this);
        permissionBanner.setOrientation(LinearLayout.HORIZONTAL);
        permissionBanner.setGravity(Gravity.CENTER_VERTICAL);
        permissionBanner.setPadding(dp(14), dp(8), dp(14), dp(8));
        permissionBanner.setVisibility(View.GONE);

        TextView permLabel = new TextView(this);
        permLabel.setText("請授權系統行事曆或連接 Notion 以顯示行程");
        permLabel.setTextSize(13f);
        permissionBanner.addView(permLabel, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button permBtn = new Button(this);
        permBtn.setText("授權／連接");
        permBtn.setTextSize(12.5f);
        permBtn.setAllCaps(false);
        permBtn.setOnClickListener(v -> {
            if (!CalendarDataRepository.hasReadPermission(this)) {
                requestPermissions(CALENDAR_PERMS, REQ_PERMISSIONS);
            } else {
                startActivity(new Intent(this, WidgetConfigActivity.class));
            }
        });
        permissionBanner.addView(permBtn);
        mainRoot.addView(permissionBanner, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 3. Viewport + Floating '+' Squircle Action Button
        FrameLayout bodyOverlay = new FrameLayout(this);
        viewportFrame = new FrameLayout(this);
        bodyOverlay.addView(viewportFrame, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        monthView = new MonthCalendarView(this);
        monthView.setListener(new MonthCalendarView.Listener() {
            @Override
            public void onDayClicked(LocalDate date, List<CalendarEvent> events) {
                anchorDate = date;
                CalendarDialogs.showDayOverviewDialog(MainActivity.this, date, events, palette, dialogCallbacks);
            }

            @Override
            public void onMonthSwiped(int deltaMonths) {
                YearMonth nextYm = YearMonth.from(anchorDate).plusMonths(deltaMonths);
                int day = Math.min(anchorDate.getDayOfMonth(), nextYm.lengthOfMonth());
                anchorDate = nextYm.atDay(day);
                reloadCalendarData();
            }
        });

        timelineView = new MultiDayTimelineView(this);
        timelineView.setShowPencilBadges(true);
        timelineView.setListener(new MultiDayTimelineView.Listener() {
            @Override
            public void onEventClicked(CalendarEvent event) {
                CalendarDialogs.showEventDetailDialog(MainActivity.this, event, palette, dialogCallbacks);
            }

            @Override
            public void onEventEditClicked(CalendarEvent event) {
                CalendarDialogs.showEventEditorDialog(MainActivity.this, event,
                        LocalDate.ofEpochDay(event.startEpochDay), -1, palette, dialogCallbacks);
            }

            @Override
            public void onDayHeaderClicked(LocalDate date) {
                anchorDate = date;
                if (viewMode != AppPrefs.VIEW_DAY_1) {
                    viewMode = AppPrefs.VIEW_DAY_1;
                    AppPrefs.setViewMode(MainActivity.this, viewMode);
                    rebuildDrawerContent();
                    reloadCalendarData();
                } else {
                    CalendarDialogs.showCreateEventDialog(MainActivity.this, date, -1, palette, dialogCallbacks);
                }
            }

            @Override
            public void onEmptySlotClicked(LocalDate date, int hour) {
                CalendarDialogs.showCreateEventDialog(MainActivity.this, date, hour, palette, dialogCallbacks);
            }

            @Override
            public void onSwipePeriod(int deltaDays) {
                anchorDate = anchorDate.plusDays(deltaDays);
                reloadCalendarData();
            }
        });

        scheduleView = new ScheduleAgendaView(this);
        scheduleView.setListener(new ScheduleAgendaView.Listener() {
            @Override
            public void onEventClicked(CalendarEvent event) {
                CalendarDialogs.showEventDetailDialog(MainActivity.this, event, palette, dialogCallbacks);
            }

            @Override
            public void onEmptyDayClicked(LocalDate date) {
                CalendarDialogs.showCreateEventDialog(MainActivity.this, date, -1, palette, dialogCallbacks);
            }
        });

        fabPlus = new PlusSquircleFabView(this);
        fabPlus.setContentDescription("新增行程");
        fabPlus.setElevation(dp(6));
        fabPlus.setOnClickListener(v ->
                CalendarDialogs.showCreateEventDialog(this, anchorDate, -1, palette, dialogCallbacks));
        FrameLayout.LayoutParams fabLp = new FrameLayout.LayoutParams(dp(56), dp(56), Gravity.BOTTOM | Gravity.END);
        fabLp.setMargins(0, 0, dp(16), dp(18));
        bodyOverlay.addView(fabPlus, fabLp);

        mainRoot.addView(bodyOverlay, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // ================= LEFT SLIDE-OUT NAVIGATION DRAWER =================
        drawerScroll = new ScrollView(this);
        drawerScroll.setVerticalScrollBarEnabled(false);
        drawerContent = new LinearLayout(this);
        drawerContent.setOrientation(LinearLayout.VERTICAL);
        drawerContent.setPadding(dp(12), dp(16), dp(12), dp(28));
        drawerScroll.addView(drawerContent);

        int drawerW = Math.min(dp(312), Math.round(getResources().getDisplayMetrics().widthPixels * 0.84f));
        drawerLayout.attachViews(mainRoot, drawerScroll, drawerW);

        setContentView(drawerLayout);
        rebuildDrawerContent();
    }

    private void applyThemeToShell() {
        palette = AppTheme.of(this);
        drawerLayout.setBackgroundColor(palette.bgAppBar);
        mainRoot.setBackgroundColor(palette.bgCanvas);
        topBar.setBackgroundColor(palette.bgAppBar);
        topMonthTitle.setTextColor(palette.textPrimary);
        topLunarSubtitle.setTextColor(palette.textSecondary);
        drawerScroll.setBackgroundColor(palette.drawerBg);

        for (int i = 0; i < topBar.getChildCount(); i++) {
            topBar.getChildAt(i).invalidate();
        }
        if (fabPlus != null) {
            fabPlus.invalidate();
        }

        Window win = getWindow();
        if (win != null) {
            win.setStatusBarColor(palette.bgAppBar);
            win.setNavigationBarColor(palette.bgCanvas);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                WindowInsetsController wic = win.getInsetsController();
                if (wic != null) {
                    int lightMask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                            | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                    wic.setSystemBarsAppearance(palette.isDark ? 0 : lightMask, lightMask);
                }
            }
        }
    }

    private void reloadCalendarData() {
        boolean hasPerm = CalendarDataRepository.hasReadPermission(this);
        boolean hasNotion = NotionManager.hasToken(this);
        permissionBanner.setVisibility((!hasPerm && !hasNotion) ? View.VISIBLE : View.GONE);
        if (permissionBanner.getVisibility() == View.VISIBLE) {
            permissionBanner.setBackgroundColor(palette.chipBg);
            ((TextView) permissionBanner.getChildAt(0)).setTextColor(palette.textPrimary);
        }

        final int mode = viewMode;
        final boolean startMonday = AppPrefs.isStartWeekOnMonday(this);
        final DayOfWeek firstDow = startMonday ? DayOfWeek.MONDAY : DayOfWeek.SUNDAY;
        final LocalDate anchor = anchorDate;
        final AppTheme.Palette pal = palette;

        // Update Top App Bar Month & Lunar Range Subtitle immediately
        LocalDate lunarStart;
        LocalDate lunarEnd;
        LocalDate queryStart;
        LocalDate queryEnd;

        if (mode == AppPrefs.VIEW_MONTH) {
            YearMonth ym = YearMonth.from(anchor);
            lunarStart = ym.atDay(1);
            lunarEnd = ym.atEndOfMonth();
            queryStart = ym.atDay(1).with(TemporalAdjusters.previousOrSame(firstDow));
            queryEnd = queryStart.plusDays(41);
        } else if (mode == AppPrefs.VIEW_WEEK) {
            queryStart = anchor.with(TemporalAdjusters.previousOrSame(firstDow));
            queryEnd = queryStart.plusDays(6);
            lunarStart = queryStart;
            lunarEnd = queryEnd;
        } else if (mode == AppPrefs.VIEW_DAY_3) {
            queryStart = anchor;
            queryEnd = anchor.plusDays(2);
            lunarStart = queryStart;
            lunarEnd = queryEnd;
        } else if (mode == AppPrefs.VIEW_DAY_1) {
            queryStart = anchor;
            queryEnd = anchor;
            lunarStart = anchor;
            lunarEnd = anchor;
        } else {
            // VIEW_SCHEDULE
            lunarStart = anchor;
            lunarEnd = anchor;
            queryStart = anchor.minusDays(60);
            queryEnd = anchor.plusDays(180);
        }

        int curYear = LocalDate.now().getYear();
        String monthText = (anchor.getYear() == curYear)
                ? (anchor.getMonthValue() + "月 ▾")
                : (anchor.getYear() + "年" + anchor.getMonthValue() + "月 ▾");
        topMonthTitle.setText(monthText);
        topLunarSubtitle.setText(LunarCalendarHelper.getRangeHeaderSubtitle(lunarStart, lunarEnd));

        // Ensure correct active view in viewportFrame
        View desiredView;
        if (mode == AppPrefs.VIEW_MONTH) {
            desiredView = monthView;
        } else if (mode == AppPrefs.VIEW_SCHEDULE) {
            desiredView = scheduleView;
        } else {
            desiredView = timelineView;
        }
        if (viewportFrame.getChildCount() != 1 || viewportFrame.getChildAt(0) != desiredView) {
            viewportFrame.removeAllViews();
            viewportFrame.addView(desiredView, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }

        final int gen = ++loadGeneration;
        final Set<String> hiddenKeys = AppPrefs.getAppHiddenCalendars(this);
        final LocalDate fStart = queryStart;
        final LocalDate fEnd = queryEnd;

        dataExecutor.execute(() -> {
            List<CalendarEvent> events = CalendarDataRepository.loadEventsInRange(
                    getApplicationContext(), fStart, fEnd, hiddenKeys);
            Map<Long, List<CalendarEvent>> grouped = (mode == AppPrefs.VIEW_MONTH || mode == AppPrefs.VIEW_SCHEDULE)
                    ? CalendarDataRepository.groupEventsByEpochDay(events, fStart.toEpochDay(), fEnd.toEpochDay())
                    : Collections.emptyMap();

            mainHandler.post(() -> {
                if (gen != loadGeneration || isFinishing()) return;
                if (mode == AppPrefs.VIEW_MONTH) {
                    monthView.setData(YearMonth.from(anchor), anchor, startMonday, pal, grouped);
                } else if (mode == AppPrefs.VIEW_SCHEDULE) {
                    scheduleView.setData(fStart, fEnd, anchor, startMonday, pal, grouped);
                } else {
                    int cols = (mode == AppPrefs.VIEW_WEEK) ? 7 : (mode == AppPrefs.VIEW_DAY_3 ? 3 : 1);
                    timelineView.setData(fStart, cols, pal, events);
                }
            });
        });
    }

    private void rebuildDrawerContent() {
        if (drawerContent == null) return;
        drawerContent.removeAllViews();

        // 1. Brand Header ("KM 行事曆" in Google multi-color accent)
        LinearLayout brandRow = new LinearLayout(this);
        brandRow.setOrientation(LinearLayout.HORIZONTAL);
        brandRow.setGravity(Gravity.CENTER_VERTICAL);
        brandRow.setPadding(dp(12), dp(6), dp(12), dp(12));

        SpannableString brandSpan = new SpannableString("KM 行事曆");
        brandSpan.setSpan(new ForegroundColorSpan(0xFF4285F4), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        brandSpan.setSpan(new ForegroundColorSpan(0xFFEA4335), 1, 2, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        TextView brandTv = new TextView(this);
        brandTv.setText(brandSpan);
        brandTv.setTextSize(21f);
        brandTv.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        brandTv.setTextColor(palette.textPrimary);
        brandRow.addView(brandTv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView verChip = new TextView(this);
        verChip.setText("v" + AppUpdater.getCurrentVersionName(this));
        verChip.setTextSize(11.5f);
        verChip.setTextColor(palette.textSecondary);
        brandRow.addView(verChip);
        drawerContent.addView(brandRow);

        // 2. 5 View Mode Items (matching Screenshot_20261126-152005.png)
        addDrawerViewModeItem(AppPrefs.VIEW_SCHEDULE, "☰", "時間表");
        addDrawerViewModeItem(AppPrefs.VIEW_DAY_1, "▣", "天");
        addDrawerViewModeItem(AppPrefs.VIEW_DAY_3, "▥", "3 天");
        addDrawerViewModeItem(AppPrefs.VIEW_WEEK, "▦", "週");
        addDrawerViewModeItem(AppPrefs.VIEW_MONTH, "⊞", "月");

        addDrawerDivider();

        // 3. Refresh Action ("↻ 重新整理")
        LinearLayout refreshRow = new LinearLayout(this);
        refreshRow.setOrientation(LinearLayout.HORIZONTAL);
        refreshRow.setGravity(Gravity.CENTER_VERTICAL);
        refreshRow.setPadding(dp(14), dp(11), dp(14), dp(11));
        refreshRow.setOnClickListener(v -> {
            drawerLayout.closeDrawer();
            Toast.makeText(this, "⏳ 正在重新同步 Google 與 Notion 行事曆…", Toast.LENGTH_SHORT).show();
            CalendarWidgetProvider.requestSync(this, 0L);
            if (NotionManager.hasToken(this)) {
                NotionManager.refreshEventsOnlyAsync(this, 0L, new NotionManager.SyncCallback() {
                    @Override
                    public void onSuccess(String workspaceName, int sourceCount, int eventCount) {
                        reloadCalendarData();
                        rebuildDrawerContent();
                        Toast.makeText(MainActivity.this,
                                "✅ 已同步 Notion（" + eventCount + " 筆行程）", Toast.LENGTH_SHORT).show();
                    }

                    @Override
                    public void onError(String errorMessage) {
                        Toast.makeText(MainActivity.this, "⚠️ " + errorMessage, Toast.LENGTH_SHORT).show();
                    }
                });
            } else {
                reloadCalendarData();
            }
        });
        TextView refIcon = new TextView(this);
        refIcon.setText("↻");
        refIcon.setTextSize(18f);
        refIcon.setTypeface(Typeface.DEFAULT_BOLD);
        refIcon.setTextColor(palette.textPrimary);
        refIcon.setWidth(dp(34));
        refreshRow.addView(refIcon);

        TextView refLabel = new TextView(this);
        refLabel.setText("重新整理");
        refLabel.setTextSize(15f);
        refLabel.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        refLabel.setTextColor(palette.textPrimary);
        refreshRow.addView(refLabel);
        drawerContent.addView(refreshRow);

        // 4. Widget & Notion Settings Entry Card
        LinearLayout widgetBtn = new LinearLayout(this);
        widgetBtn.setOrientation(LinearLayout.VERTICAL);
        widgetBtn.setPadding(dp(14), dp(10), dp(14), dp(10));
        GradientDrawable wBg = new GradientDrawable();
        wBg.setCornerRadius(dp(14));
        wBg.setColor(palette.chipBg);
        widgetBtn.setBackground(wBg);
        LinearLayout.LayoutParams wLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wLp.setMargins(dp(4), dp(4), dp(4), dp(8));
        widgetBtn.setLayoutParams(wLp);
        widgetBtn.setOnClickListener(v -> {
            drawerLayout.closeDrawer();
            startActivity(new Intent(this, WidgetConfigActivity.class));
        });

        TextView wTitle = new TextView(this);
        wTitle.setText(hasAvailableUpdate
                ? "⚙️ 桌面小工具 · Notion 串接（🎉 有新版可更新）"
                : "⚙️ 桌面小工具設定 · Notion 串接 · 更新");
        wTitle.setTextSize(13.8f);
        wTitle.setTypeface(Typeface.DEFAULT_BOLD);
        wTitle.setTextColor(palette.textPrimary);
        widgetBtn.addView(wTitle);

        TextView wSub = new TextView(this);
        wSub.setText("管理各桌面小工具獨立設定、Notion 權限與 GitHub 更新");
        wSub.setTextSize(11.5f);
        wSub.setTextColor(palette.textSecondary);
        widgetBtn.addView(wSub);
        drawerContent.addView(widgetBtn);

        addDrawerDivider();

        // 5. Quick Theme & First-Day-Of-Week Segmented Switches
        TextView prefHeader = new TextView(this);
        prefHeader.setText("介面主題 ＆ 每週起始日");
        prefHeader.setTextSize(12f);
        prefHeader.setTypeface(Typeface.DEFAULT_BOLD);
        prefHeader.setTextColor(palette.textSecondary);
        prefHeader.setPadding(dp(10), dp(6), dp(10), dp(6));
        drawerContent.addView(prefHeader);

        int curTheme = AppPrefs.getThemeMode(this);
        LinearLayout themeRow = new LinearLayout(this);
        themeRow.setOrientation(LinearLayout.HORIZONTAL);
        themeRow.setPadding(dp(6), 0, dp(6), dp(6));
        addSegmentPill(themeRow, "淺色", curTheme == AppPrefs.THEME_LIGHT, () -> changeTheme(AppPrefs.THEME_LIGHT));
        addSegmentPill(themeRow, "深色", curTheme == AppPrefs.THEME_DARK, () -> changeTheme(AppPrefs.THEME_DARK));
        addSegmentPill(themeRow, "跟隨系統", curTheme == AppPrefs.THEME_SYSTEM, () -> changeTheme(AppPrefs.THEME_SYSTEM));
        drawerContent.addView(themeRow);

        boolean startMon = AppPrefs.isStartWeekOnMonday(this);
        LinearLayout dowRow = new LinearLayout(this);
        dowRow.setOrientation(LinearLayout.HORIZONTAL);
        dowRow.setPadding(dp(6), 0, dp(6), dp(6));
        addSegmentPill(dowRow, "週日起始", !startMon, () -> changeWeekStart(false));
        addSegmentPill(dowRow, "週一起始", startMon, () -> changeWeekStart(true));
        drawerContent.addView(dowRow);

        addDrawerDivider();

        // 5B. Event Reminder Notification Controls
        TextView notifHeader = new TextView(this);
        notifHeader.setText("🔔 行程提醒通知");
        notifHeader.setTextSize(12f);
        notifHeader.setTypeface(Typeface.DEFAULT_BOLD);
        notifHeader.setTextColor(palette.textSecondary);
        notifHeader.setPadding(dp(10), dp(6), dp(10), dp(6));
        drawerContent.addView(notifHeader);

        int notifMode = AppPrefs.getNotifMode(this);
        LinearLayout notifModeRow = new LinearLayout(this);
        notifModeRow.setOrientation(LinearLayout.HORIZONTAL);
        notifModeRow.setPadding(dp(6), 0, dp(6), dp(6));
        addSegmentPill(notifModeRow, "全部通知", notifMode == AppPrefs.NOTIF_MODE_ALL,
                () -> changeNotifMode(AppPrefs.NOTIF_MODE_ALL));
        addSegmentPill(notifModeRow, "僅 Notion", notifMode == AppPrefs.NOTIF_MODE_NOTION_ONLY,
                () -> changeNotifMode(AppPrefs.NOTIF_MODE_NOTION_ONLY));
        addSegmentPill(notifModeRow, "關閉", notifMode == AppPrefs.NOTIF_MODE_OFF,
                () -> changeNotifMode(AppPrefs.NOTIF_MODE_OFF));
        drawerContent.addView(notifModeRow);

        if (notifMode != AppPrefs.NOTIF_MODE_OFF) {
            int notionDefMin = AppPrefs.getNotionDefaultReminderMinutes(this);
            int snoozeMin = AppPrefs.getSnoozeMinutes(this);
            LinearLayout cfgRow = new LinearLayout(this);
            cfgRow.setOrientation(LinearLayout.HORIZONTAL);
            cfgRow.setPadding(dp(6), 0, dp(6), dp(6));
            addSegmentPill(cfgRow, "Notion：" + formatShortOffset(notionDefMin) + " ▾", false,
                    this::showNotionDefaultReminderPicker);
            addSegmentPill(cfgRow, "延後：" + snoozeMin + " 分鐘 ▾", false,
                    this::showSnoozeMinutesPicker);
            drawerContent.addView(cfgRow);

            TextView previewBtn = new TextView(this);
            previewBtn.setText("🔔 發送測試通知預覽（查看群組摺疊 ＆ 延後效果）");
            previewBtn.setTextSize(12.2f);
            previewBtn.setTypeface(Typeface.DEFAULT_BOLD);
            previewBtn.setGravity(Gravity.CENTER);
            previewBtn.setPadding(dp(10), dp(8), dp(10), dp(8));
            GradientDrawable pvBg = new GradientDrawable();
            pvBg.setCornerRadius(dp(14));
            pvBg.setColor(palette.chipBg);
            previewBtn.setBackground(pvBg);
            previewBtn.setTextColor(palette.selectedStroke);
            previewBtn.setOnClickListener(v -> triggerTestNotificationPreview());
            LinearLayout.LayoutParams pvLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            pvLp.setMargins(dp(8), 0, dp(8), dp(6));
            drawerContent.addView(previewBtn, pvLp);
        }

        addDrawerDivider();

        // 6. Calendar Account Checkboxes (Notion + Google/System Accounts)
        Set<String> appHidden = AppPrefs.getAppHiddenCalendars(this);

        // 6A. Notion Workspace
        if (NotionManager.hasToken(this)) {
            NotionManager.CachedWorkspace ws = NotionManager.loadCache(this);
            if (ws != null && !ws.sources.isEmpty()) {
                addAccountSectionHeader("📓 " + ws.workspaceName, "Notion 日曆資料庫（點色塊可改色）");
                for (NotionManager.NotionSource src : ws.sources) {
                    int color = NotionManager.getSourceColor(this, src);
                    boolean checked = !appHidden.contains(src.sourceKey);
                    int count = ws.countEventsForSource(src.sourceKey);
                    addDrawerCalendarCheckbox(
                            src.fullTitleWithIcon() + " (" + count + ")",
                            color,
                            checked,
                            isChecked -> {
                                AppPrefs.setAppCalendarHidden(this, src.sourceKey, !isChecked);
                                reloadCalendarData();
                            },
                            () -> showNotionColorPickerFromDrawer(src)
                    );
                }
                addDrawerDivider();
            }
        }

        // 6B. Google / System Calendars grouped by Account Email
        List<CalendarDataRepository.SystemCalendarItem> sysCals =
                CalendarDataRepository.loadSystemCalendars(this);
        String currentAccount = null;
        for (CalendarDataRepository.SystemCalendarItem cal : sysCals) {
            if (!cal.accountName.equals(currentAccount)) {
                if (currentAccount != null) {
                    addDrawerDivider();
                }
                currentAccount = cal.accountName;
                addAccountSectionHeader(
                        currentAccount.isEmpty() ? "手機本機行事曆" : currentAccount,
                        cal.accountType.contains("google") ? "Google" : "");
            }
            String key = String.valueOf(cal.id);
            boolean checked = cal.synced && !appHidden.contains(key);
            addDrawerCalendarCheckbox(
                    cal.displayName,
                    cal.color,
                    checked,
                    isChecked -> {
                        AppPrefs.setAppCalendarHidden(this, key, !isChecked);
                        reloadCalendarData();
                    },
                    null
            );
        }
    }

    private void changeTheme(int nextTheme) {
        AppPrefs.setThemeMode(this, nextTheme);
        applyThemeToShell();
        rebuildDrawerContent();
        reloadCalendarData();
    }

    private void changeWeekStart(boolean startMonday) {
        AppPrefs.setStartWeekOnMonday(this, startMonday);
        rebuildDrawerContent();
        reloadCalendarData();
    }

    private void showNotionColorPickerFromDrawer(NotionManager.NotionSource src) {
        CharSequence[] items = new CharSequence[NotionManager.NOTION_PALETTE.length];
        for (int i = 0; i < NotionManager.NOTION_PALETTE.length; i++) {
            SpannableString s = new SpannableString("●  " + NotionManager.NOTION_PALETTE_NAMES[i]);
            s.setSpan(new ForegroundColorSpan(NotionManager.NOTION_PALETTE[i]),
                    0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            items[i] = s;
        }
        new AlertDialog.Builder(this)
                .setTitle("自訂「" + src.displayName + "」顏色")
                .setItems(items, (d, which) -> {
                    NotionManager.setSourceColor(this, src.sourceKey, NotionManager.NOTION_PALETTE[which]);
                    CalendarWidgetProvider.updateAll(this);
                    rebuildDrawerContent();
                    reloadCalendarData();
                })
                .show();
    }

    private void addDrawerViewModeItem(int targetMode, String iconSymbol, String title) {
        boolean active = (viewMode == targetMode);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(11), dp(16), dp(11));

        if (active) {
            GradientDrawable pill = new GradientDrawable();
            pill.setCornerRadius(dp(24));
            pill.setColor(palette.drawerActivePill);
            row.setBackground(pill);
        }

        TextView icon = new TextView(this);
        icon.setText(iconSymbol);
        icon.setTextSize(16f);
        icon.setTypeface(Typeface.DEFAULT_BOLD);
        icon.setTextColor(active ? palette.drawerActiveText : palette.textPrimary);
        icon.setWidth(dp(34));
        row.addView(icon);

        TextView label = new TextView(this);
        label.setText(title);
        label.setTextSize(15f);
        label.setTypeface(Typeface.create("sans-serif-medium", active ? Typeface.BOLD : Typeface.NORMAL));
        label.setTextColor(active ? palette.drawerActiveText : palette.textPrimary);
        row.addView(label);

        row.setOnClickListener(v -> {
            viewMode = targetMode;
            AppPrefs.setViewMode(this, viewMode);
            drawerLayout.closeDrawer();
            rebuildDrawerContent();
            if (timelineView != null) {
                timelineView.scrollToDefaultHour();
            }
            reloadCalendarData();
        });

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(2);
        drawerContent.addView(row, lp);
    }

    private void addSegmentPill(LinearLayout parent, String text, boolean active, Runnable onClick) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(12.5f);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(8), dp(7), dp(8), dp(7));

        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(16));
        bg.setColor(active ? palette.drawerActivePill : palette.chipBg);
        tv.setBackground(bg);
        tv.setTextColor(active ? palette.drawerActiveText : palette.textPrimary);
        tv.setOnClickListener(v -> onClick.run());

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp(3), 0, dp(3), 0);
        parent.addView(tv, lp);
    }

    private static String formatShortOffset(int min) {
        if (min < 0) return "不預設";
        if (min == 0) return "開始時";
        if (min % 60 == 0) return (min / 60) + "小時前";
        return min + "分前";
    }

    private void changeNotifMode(int mode) {
        AppPrefs.setNotifMode(this, mode);
        if (mode != AppPrefs.NOTIF_MODE_OFF
                && Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_PERMISSIONS);
        }
        EventReminderManager.scheduleAsync(this);
        rebuildDrawerContent();
    }

    private void showNotionDefaultReminderPicker() {
        final int[] opts = {0, 5, 10, 15, 30, 60, 120, -1};
        final String[] labels = {
                "活動開始時",
                "5 分鐘之前",
                "10 分鐘之前（預設）",
                "15 分鐘之前",
                "30 分鐘之前",
                "1 小時之前",
                "2 小時之前",
                "不自動提醒（僅單筆手動新增時提醒）"
        };
        int cur = AppPrefs.getNotionDefaultReminderMinutes(this);
        int checked = 2;
        for (int i = 0; i < opts.length; i++) {
            if (opts[i] == cur) {
                checked = i;
                break;
            }
        }
        new AlertDialog.Builder(this)
                .setTitle("Notion 行程預設提醒時間")
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    AppPrefs.setNotionDefaultReminderMinutes(this, opts[which]);
                    EventReminderManager.scheduleAsync(this);
                    rebuildDrawerContent();
                    d.dismiss();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showSnoozeMinutesPicker() {
        final int[] opts = {5, 10, 15, 30, 60};
        final String[] labels = {
                "5 分鐘",
                "10 分鐘（預設）",
                "15 分鐘",
                "30 分鐘",
                "1 小時"
        };
        int cur = AppPrefs.getSnoozeMinutes(this);
        int checked = 1;
        for (int i = 0; i < opts.length; i++) {
            if (opts[i] == cur) {
                checked = i;
                break;
            }
        }
        new AlertDialog.Builder(this)
                .setTitle("通知「延後」預設時間")
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    AppPrefs.setSnoozeMinutes(this, opts[which]);
                    rebuildDrawerContent();
                    d.dismiss();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void triggerTestNotificationPreview() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_POST_NOTIF_PREVIEW);
            return;
        }
        if (!EventReminderManager.canPostNotifications(this)) {
            Toast.makeText(this, "請先在系統設定開啟「KM 行事曆」通知權限", Toast.LENGTH_LONG).show();
            try {
                Intent settings = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
                startActivity(settings);
            } catch (Exception ignored) {
            }
            return;
        }
        dataExecutor.execute(() -> {
            boolean sent = EventReminderManager.sendPreviewNotifications(getApplicationContext());
            mainHandler.post(() -> {
                if (sent) {
                    Toast.makeText(MainActivity.this,
                            "✅ 已送出 2 筆預覽通知！請下拉通知欄查看摺疊／展開與「延後」效果",
                            Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    private void addAccountSectionHeader(String title, String subtitle) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(12), dp(8), dp(12), dp(4));

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(13.5f);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(palette.textPrimary);
        col.addView(t);

        if (!TextUtils.isEmpty(subtitle)) {
            TextView s = new TextView(this);
            s.setText(subtitle);
            s.setTextSize(11.5f);
            s.setTextColor(palette.textSecondary);
            col.addView(s);
        }
        drawerContent.addView(col);
    }

    private interface BoolConsumer {
        void accept(boolean checked);
    }

    private void addDrawerCalendarCheckbox(
            String label,
            int color,
            boolean checked,
            BoolConsumer onToggle,
            Runnable optionalColorClick) {

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(2), dp(8), dp(2));

        CheckBox cb = new CheckBox(this);
        cb.setChecked(checked);
        cb.setButtonTintList(ColorStateList.valueOf(color));
        cb.setText(label);
        cb.setTextSize(14f);
        cb.setTextColor(palette.textPrimary);
        cb.setMinHeight(dp(40));
        cb.setOnCheckedChangeListener((b, isChecked) -> onToggle.accept(isChecked));
        row.addView(cb, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (optionalColorClick != null) {
            View dot = new View(this);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(6));
            bg.setColor(color);
            dot.setBackground(bg);
            LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(dp(20), dp(20));
            dLp.setMarginStart(dp(6));
            dot.setLayoutParams(dLp);
            dot.setOnClickListener(v -> optionalColorClick.run());
            row.addView(dot);
        }

        drawerContent.addView(row);
    }

    private void addDrawerDivider() {
        View div = new View(this);
        div.setBackgroundColor(palette.divider);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        lp.setMargins(dp(4), dp(8), dp(4), dp(8));
        drawerContent.addView(div, lp);
    }

    // ==================== CRISP CUSTOM VECTOR ICON VIEWS ====================

    private final class HamburgerIconView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        HamburgerIconView(MainActivity ctx) {
            super(ctx);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float d = getResources().getDisplayMetrics().density;
            p.setColor(palette.textPrimary);
            p.setStrokeWidth(2.1f * d);
            float cx = getWidth() * 0.5f;
            float cy = getHeight() * 0.5f;
            float hw = 9f * d;
            canvas.drawLine(cx - hw, cy - 5.5f * d, cx + hw, cy - 5.5f * d, p);
            canvas.drawLine(cx - hw, cy, cx + hw, cy, p);
            canvas.drawLine(cx - hw, cy + 5.5f * d, cx + hw, cy + 5.5f * d, p);
        }
    }

    private final class SearchIconView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        SearchIconView(MainActivity ctx) {
            super(ctx);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float d = getResources().getDisplayMetrics().density;
            p.setColor(palette.textPrimary);
            p.setStrokeWidth(2.0f * d);
            float cx = getWidth() * 0.46f;
            float cy = getHeight() * 0.46f;
            float r = 6.8f * d;
            canvas.drawCircle(cx, cy, r, p);
            canvas.drawLine(cx + r * 0.72f, cy + r * 0.72f, cx + 11.5f * d, cy + 11.5f * d, p);
        }
    }

    private final class TodayBadgeIconView extends View {
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF();
        private int dayNumber;

        TodayBadgeIconView(MainActivity ctx, int day) {
            super(ctx);
            this.dayNumber = day;
            stroke.setStyle(Paint.Style.STROKE);
            text.setTextAlign(Paint.Align.CENTER);
            text.setTypeface(Typeface.DEFAULT_BOLD);
        }

        void setDayNumber(int day) {
            this.dayNumber = day;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float d = getResources().getDisplayMetrics().density;
            float sd = getResources().getDisplayMetrics().scaledDensity;
            float cx = getWidth() * 0.5f;
            float cy = getHeight() * 0.5f;
            float half = 9.5f * d;
            box.set(cx - half, cy - half, cx + half, cy + half);

            stroke.setColor(palette.textPrimary);
            stroke.setStrokeWidth(1.9f * d);
            canvas.drawRoundRect(box, 4f * d, 4f * d, stroke);

            text.setColor(palette.textPrimary);
            text.setTextSize(10.5f * sd);
            Paint.FontMetrics fm = text.getFontMetrics();
            float baseline = cy - (fm.ascent + fm.descent) * 0.5f + 0.5f * d;
            canvas.drawText(String.valueOf(dayNumber), cx, baseline, text);
        }
    }

    private final class SettingsBadgeIconView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean showDot = false;

        SettingsBadgeIconView(MainActivity ctx) {
            super(ctx);
        }

        void setShowDot(boolean show) {
            this.showDot = show;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float d = getResources().getDisplayMetrics().density;
            float cx = getWidth() * 0.5f;
            float cy = getHeight() * 0.5f;

            p.setColor(palette.textPrimary);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2.0f * d);
            // Sliders / Tune icon for widget & Notion settings
            float hw = 8.5f * d;
            canvas.drawLine(cx - hw, cy - 5f * d, cx + hw, cy - 5f * d, p);
            canvas.drawLine(cx - hw, cy + 5f * d, cx + hw, cy + 5f * d, p);
            p.setStyle(Paint.Style.FILL);
            canvas.drawCircle(cx - 3f * d, cy - 5f * d, 2.8f * d, p);
            canvas.drawCircle(cx + 3.5f * d, cy + 5f * d, 2.8f * d, p);

            if (showDot) {
                dotPaint.setColor(0xFFEA4335);
                canvas.drawCircle(cx + 8.5f * d, cy - 8.5f * d, 4f * d, dotPaint);
            }
        }
    }

    private final class PlusSquircleFabView extends View {
        private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint plus = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();

        PlusSquircleFabView(MainActivity ctx) {
            super(ctx);
            plus.setStyle(Paint.Style.STROKE);
            plus.setStrokeCap(Paint.Cap.ROUND);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float d = getResources().getDisplayMetrics().density;
            rect.set(0, 0, getWidth(), getHeight());
            bg.setColor(palette.fabBg);
            canvas.drawRoundRect(rect, 16f * d, 16f * d, bg);

            plus.setColor(palette.fabIcon);
            plus.setStrokeWidth(2.5f * d);
            float cx = getWidth() * 0.5f;
            float cy = getHeight() * 0.5f;
            float arm = 8.5f * d;
            canvas.drawLine(cx - arm, cy, cx + arm, cy, plus);
            canvas.drawLine(cx, cy - arm, cx, cy + arm, plus);
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        dataExecutor.shutdownNow();
    }
}
