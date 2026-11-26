package io.github.kamaje122.calwidget;

import android.Manifest;
import android.accounts.Account;
import android.app.Activity;
import android.app.AlertDialog;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.CalendarContract;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class MainActivity extends Activity {

    private static final int REQUEST_CALENDAR = 1;
    private static final String[] CALENDAR_PERMISSIONS = new String[]{
            Manifest.permission.READ_CALENDAR,
            Manifest.permission.WRITE_CALENDAR
    };
    private static final String NOTION_INTEGRATIONS_URL = "https://www.notion.so/profile/integrations";
    private static final String GITHUB_REPO_URL = "https://github.com/" + AppUpdater.DEFAULT_GITHUB_REPO;

    private boolean askedOnce;
    private int configureWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID;
    private int editingProfileId = -1;
    private boolean updatingControlsProgrammatically;

    private LinearLayout configBannerCard;
    private TextView configBannerText;

    private TextView status;
    private Button grant;
    private LinearLayout calendars;

    // Per-Widget Selector Views
    private LinearLayout widgetTabs;
    private TextView editingTargetLabel;
    private Button btnRenameWidget;
    private Button btnCopyToAll;
    private Switch switchAllDay;
    private TextView daysValue;
    private TextView sectionDisplayTitle;
    private TextView sectionCalendarsTitle;

    // GitHub OTA Updater Views
    private TextView updateStatus;
    private ProgressBar updateProgress;
    private Button btnCheckUpdate;
    private Button btnDownloadUpdate;
    private AppUpdater.ReleaseInfo latestRelease;
    private File pendingInstallApk;

    // Notion Views
    private EditText inputNotionToken;
    private Button btnNotionSync;
    private Button btnNotionClear;
    private Button btnNotionAddUrl;
    private Button btnNotionAddCustom;
    private TextView notionStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        TextView versionBadge = findViewById(R.id.version_badge);
        versionBadge.setText(String.format(Locale.US, "v%s (Build %d) · %s",
                AppUpdater.getCurrentVersionName(this),
                AppUpdater.getCurrentVersionCode(this),
                AppUpdater.DEFAULT_GITHUB_REPO));

        configBannerCard = findViewById(R.id.config_banner_card);
        configBannerText = findViewById(R.id.config_banner_text);
        findViewById(R.id.btn_finish_configure).setOnClickListener(v -> finishWidgetConfiguration());

        // Updater UI
        updateStatus = findViewById(R.id.update_status);
        updateProgress = findViewById(R.id.update_progress);
        btnCheckUpdate = findViewById(R.id.btn_check_update);
        btnDownloadUpdate = findViewById(R.id.btn_download_update);
        Button btnOpenGithub = findViewById(R.id.btn_open_github);

        btnCheckUpdate.setOnClickListener(v -> checkForGithubUpdate(true));
        btnDownloadUpdate.setOnClickListener(v -> startDownloadAndInstall());
        btnOpenGithub.setOnClickListener(v -> openUrl(GITHUB_REPO_URL));

        // System Calendar & Pin UI
        status = findViewById(R.id.status);
        grant = findViewById(R.id.btn_grant);
        calendars = findViewById(R.id.calendars);

        grant.setOnClickListener(v -> {
            if (askedOnce && !shouldShowRequestPermissionRationale(Manifest.permission.READ_CALENDAR)) {
                Intent settingsIntent = new Intent(
                        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", getPackageName(), null));
                startActivity(settingsIntent);
            } else {
                requestPermissions(CALENDAR_PERMISSIONS, REQUEST_CALENDAR);
            }
        });

        findViewById(R.id.btn_pin).setOnClickListener(v -> {
            AppWidgetManager mgr = AppWidgetManager.getInstance(this);
            if (mgr.isRequestPinAppWidgetSupported()) {
                mgr.requestPinAppWidget(new ComponentName(this, CalendarWidgetProvider.class), null, null);
            } else {
                Toast.makeText(this, R.string.pin_unsupported, Toast.LENGTH_LONG).show();
            }
        });

        // Notion UI
        inputNotionToken = findViewById(R.id.input_notion_token);
        btnNotionSync = findViewById(R.id.btn_notion_sync);
        btnNotionClear = findViewById(R.id.btn_notion_clear);
        btnNotionAddUrl = findViewById(R.id.btn_notion_add_url);
        btnNotionAddCustom = findViewById(R.id.btn_notion_add_custom);
        notionStatus = findViewById(R.id.notion_status);

        inputNotionToken.setText(NotionManager.getToken(this));
        findViewById(R.id.btn_notion_portal).setOnClickListener(v -> openUrl(NOTION_INTEGRATIONS_URL));
        btnNotionSync.setOnClickListener(v -> handleNotionConnectAndSync());
        btnNotionClear.setOnClickListener(v -> confirmClearNotion());
        btnNotionAddUrl.setOnClickListener(v -> showAddNotionByUrlDialog());
        btnNotionAddCustom.setOnClickListener(v -> showAddCustomNotionDialog());

        // Per-Widget Selector & Scoped Display Controls
        widgetTabs = findViewById(R.id.widget_tabs);
        editingTargetLabel = findViewById(R.id.editing_target_label);
        btnRenameWidget = findViewById(R.id.btn_rename_widget);
        btnCopyToAll = findViewById(R.id.btn_copy_to_all);
        sectionDisplayTitle = findViewById(R.id.section_display_title);
        sectionCalendarsTitle = findViewById(R.id.section_calendars_title);

        btnRenameWidget.setOnClickListener(v -> showRenameWidgetDialog());
        btnCopyToAll.setOnClickListener(v -> confirmCopyToAllWidgets());

        daysValue = findViewById(R.id.days_value);
        View.OnClickListener daysClick = v -> {
            int delta = (v.getId() == R.id.btn_days_plus) ? 1 : -1;
            int current = CalendarWidgetProvider.daysToShow(this, editingProfileId);
            CalendarWidgetProvider.setDaysToShow(this, editingProfileId, current + delta);
            daysValue.setText(getString(R.string.days_value,
                    CalendarWidgetProvider.daysToShow(this, editingProfileId)));
            CalendarWidgetProvider.updateWidget(this, editingProfileId);
            refreshWidgetSelectorTabs();
        };
        findViewById(R.id.btn_days_minus).setOnClickListener(daysClick);
        findViewById(R.id.btn_days_plus).setOnClickListener(daysClick);

        switchAllDay = findViewById(R.id.switch_all_day);
        switchAllDay.setOnCheckedChangeListener((btn, checked) -> {
            if (updatingControlsProgrammatically) return;
            CalendarWidgetProvider.setShowAllDay(this, editingProfileId, checked);
            CalendarWidgetProvider.updateWidget(this, editingProfileId);
            refreshWidgetSelectorTabs();
        });

        applyConfigureIntent(getIntent());
        checkForGithubUpdate(false);

        // If Notion token is already configured but cached events are empty (e.g. upgrading from v2.0.0),
        // automatically run a full discovery & sync immediately!
        if (NotionManager.hasToken(this)) {
            NotionManager.CachedWorkspace ws = NotionManager.loadCache(this);
            if (ws == null || ws.events.isEmpty()) {
                notionStatus.setText("⏳ 正在自動重新同步 Notion 資料庫與月曆行程…");
                NotionManager.scanWorkspaceAndSyncAsync(this, new NotionManager.SyncCallback() {
                    @Override
                    public void onSuccess(String workspaceName, int sourceCount, int eventCount) {
                        refreshNotionUiState();
                        showCalendars(hasCalPermission());
                    }

                    @Override
                    public void onError(String errorMessage) {
                        refreshNotionUiState();
                    }
                });
            }
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        applyConfigureIntent(intent);
        refresh();
    }

    private void applyConfigureIntent(Intent intent) {
        int id = intent != null
                ? intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
                : AppWidgetManager.INVALID_APPWIDGET_ID;
        if (id != AppWidgetManager.INVALID_APPWIDGET_ID && id > 0) {
            configureWidgetId = id;
            CalendarWidgetProvider.ensureWidgetInitialized(this, configureWidgetId);
            editingProfileId = configureWidgetId;

            Intent resultValue = new Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, configureWidgetId);
            setResult(RESULT_OK, resultValue);

            configBannerCard.setVisibility(View.VISIBLE);
            configBannerText.setText("🎯 正在個別設定「"
                    + CalendarWidgetProvider.getWidgetName(this, configureWidgetId)
                    + "」\n下方調整的「顯示全天」、「顯示天數」與「勾選的日曆」，只會套用到這一個小工具！");
        } else {
            configBannerCard.setVisibility(View.GONE);
            int[] activeIds = CalendarWidgetProvider.getActiveWidgetIds(this);
            if (editingProfileId < 0) {
                editingProfileId = activeIds.length > 0
                        ? activeIds[0]
                        : CalendarWidgetProvider.PROFILE_DEFAULT;
            }
        }
    }

    private void finishWidgetConfiguration() {
        if (configureWidgetId > 0) {
            CalendarWidgetProvider.updateWidget(this, configureWidgetId);
            Intent resultValue = new Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, configureWidgetId);
            setResult(RESULT_OK, resultValue);
        } else {
            CalendarWidgetProvider.updateAll(this);
        }
        finish();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (pendingInstallApk != null && pendingInstallApk.exists()
                && getPackageManager().canRequestPackageInstalls()) {
            File apk = pendingInstallApk;
            pendingInstallApk = null;
            AppUpdater.promptInstallApk(this, apk);
        }
        refresh();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        askedOnce = true;
        refresh();
    }

    private void refreshWidgetSelectorTabs() {
        int[] activeIds = CalendarWidgetProvider.getActiveWidgetIds(this);
        boolean validCurrent = (editingProfileId == CalendarWidgetProvider.PROFILE_DEFAULT);
        for (int id : activeIds) {
            CalendarWidgetProvider.ensureWidgetInitialized(this, id);
            if (id == editingProfileId) validCurrent = true;
        }
        if (!validCurrent) {
            editingProfileId = activeIds.length > 0 ? activeIds[0] : CalendarWidgetProvider.PROFILE_DEFAULT;
        }

        widgetTabs.removeAllViews();
        for (int id : activeIds) {
            addWidgetTabPill(id, "📌 " + CalendarWidgetProvider.getWidgetName(this, id));
        }
        addWidgetTabPill(CalendarWidgetProvider.PROFILE_DEFAULT, "⚙️ 預設模板");

        String targetName = CalendarWidgetProvider.getWidgetName(this, editingProfileId);
        boolean allDay = CalendarWidgetProvider.showAllDay(this, editingProfileId);
        int days = CalendarWidgetProvider.daysToShow(this, editingProfileId);

        editingTargetLabel.setText(String.format(Locale.TAIWAN,
                "👉 目前正在調整：%s（全天：%s · 顯示 %d 天）",
                targetName, allDay ? "開" : "關", days));
        sectionDisplayTitle.setText(getString(R.string.section_display) + " — " + targetName);
        sectionCalendarsTitle.setText(getString(R.string.section_calendars) + " — " + targetName);

        btnRenameWidget.setEnabled(editingProfileId > 0);
        btnCopyToAll.setVisibility(activeIds.length > 1 ? View.VISIBLE : View.GONE);

        updatingControlsProgrammatically = true;
        switchAllDay.setChecked(allDay);
        daysValue.setText(getString(R.string.days_value, days));
        updatingControlsProgrammatically = false;
    }

    private void addWidgetTabPill(int profileId, String title) {
        boolean selected = (profileId == editingProfileId);
        TextView pill = new TextView(this);
        pill.setText(title);
        pill.setTextSize(14f);
        pill.setTypeface(pill.getTypeface(), Typeface.BOLD);
        pill.setPadding(dp(14), dp(8), dp(14), dp(8));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(dp(8));
        pill.setLayoutParams(lp);

        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(18));
        int accentColor = getColor(R.color.accent);
        if (selected) {
            bg.setColor(accentColor);
            pill.setTextColor(Color.WHITE);
        } else {
            bg.setColor(Color.TRANSPARENT);
            bg.setStroke(dp(1), accentColor);
            pill.setTextColor(getColor(R.color.text_primary));
        }
        pill.setBackground(bg);

        pill.setOnClickListener(v -> {
            editingProfileId = profileId;
            if (configureWidgetId > 0 && profileId > 0) {
                configBannerText.setText("🎯 正在個別設定「"
                        + CalendarWidgetProvider.getWidgetName(this, profileId) + "」");
            }
            refreshWidgetSelectorTabs();
            showCalendars(hasCalPermission());
        });
        widgetTabs.addView(pill);
    }

    private void showRenameWidgetDialog() {
        if (editingProfileId <= 0) return;
        EditText input = new EditText(this);
        input.setHint("例如：主桌面、工作 Notion、LockDock");
        input.setText(CalendarWidgetProvider.getRawWidgetNickname(this, editingProfileId));
        input.setSingleLine(true);
        int pad = dp(20);
        LinearLayout wrap = new LinearLayout(this);
        wrap.setPadding(pad, dp(12), pad, 0);
        wrap.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(this)
                .setTitle("修改小工具 #" + editingProfileId + " 名稱")
                .setMessage("取個好記的位置名稱，方便在多個小工具之間快速辨識與切換：")
                .setView(wrap)
                .setPositiveButton("儲存", (d, w) -> {
                    CalendarWidgetProvider.setWidgetNickname(this, editingProfileId, input.getText().toString());
                    refreshWidgetSelectorTabs();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void confirmCopyToAllWidgets() {
        String srcName = CalendarWidgetProvider.getWidgetName(this, editingProfileId);
        new AlertDialog.Builder(this)
                .setTitle("套用到所有小工具")
                .setMessage("要把「" + srcName + "」的全天開關、顯示天數與勾選日曆，一併套用到手機上所有小工具嗎？")
                .setPositiveButton("全部套用", (d, w) -> {
                    CalendarWidgetProvider.copyProfileToAllWidgets(this, editingProfileId);
                    Toast.makeText(this, "已套用到所有小工具", Toast.LENGTH_SHORT).show();
                    refresh();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void checkForGithubUpdate(boolean userInitiated) {
        btnCheckUpdate.setEnabled(false);
        updateStatus.setText("⏳ 正在連線 GitHub Releases 檢查最新版本…");
        AppUpdater.checkLatestRelease(this, new AppUpdater.CheckCallback() {
            @Override
            public void onSuccess(AppUpdater.ReleaseInfo info) {
                btnCheckUpdate.setEnabled(true);
                latestRelease = info;
                String sizeMb = String.format(Locale.US, "%.2f MB", info.sizeBytes / (1024.0 * 1024.0));
                if (info.hasNewerUpdate) {
                    String notes = TextUtils.isEmpty(info.changelog) ? "" : ("\n更新內容：\n" + info.changelog);
                    updateStatus.setText("🎉 發現新版本：" + info.title + " (" + sizeMb + ")" + notes);
                    btnDownloadUpdate.setText(getString(R.string.btn_download_update));
                    btnDownloadUpdate.setVisibility(View.VISIBLE);
                } else {
                    updateStatus.setText("✅ 目前已是最新版本（GitHub 最新：" + info.cleanVersion + " · " + sizeMb + "）");
                    if (userInitiated) {
                        btnDownloadUpdate.setText("重新下載安裝");
                        btnDownloadUpdate.setVisibility(View.VISIBLE);
                    } else {
                        btnDownloadUpdate.setVisibility(View.GONE);
                    }
                }
            }

            @Override
            public void onError(String message) {
                btnCheckUpdate.setEnabled(true);
                updateStatus.setText("⚠️ " + message);
                if (userInitiated) {
                    Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    private void startDownloadAndInstall() {
        if (latestRelease == null || TextUtils.isEmpty(latestRelease.apkDownloadUrl)) return;
        btnDownloadUpdate.setEnabled(false);
        btnCheckUpdate.setEnabled(false);
        updateProgress.setProgress(0);
        updateProgress.setVisibility(View.VISIBLE);
        updateStatus.setText("⬇️ 正在從 GitHub 下載更新檔… 0%");

        AppUpdater.downloadApk(this, latestRelease.apkDownloadUrl, new AppUpdater.DownloadCallback() {
            @Override
            public void onProgress(int percent) {
                updateProgress.setProgress(percent);
                updateStatus.setText("⬇️ 正在從 GitHub 下載更新檔… " + percent + "%");
            }

            @Override
            public void onReadyToInstall(File apkFile) {
                btnDownloadUpdate.setEnabled(true);
                btnCheckUpdate.setEnabled(true);
                updateProgress.setVisibility(View.GONE);
                boolean started = AppUpdater.promptInstallApk(MainActivity.this, apkFile);
                if (started) {
                    updateStatus.setText("✅ 下載完成，已開啟系統安裝程式！");
                } else {
                    pendingInstallApk = apkFile;
                    updateStatus.setText("⚠️ 請在剛開啟的設定畫面開啟「允許安裝未知應用程式」，返回後將自動啟動安裝！");
                }
            }

            @Override
            public void onError(String message) {
                btnDownloadUpdate.setEnabled(true);
                btnCheckUpdate.setEnabled(true);
                updateProgress.setVisibility(View.GONE);
                updateStatus.setText("❌ " + message);
                Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void handleNotionConnectAndSync() {
        String rawToken = inputNotionToken.getText().toString().trim();
        if (rawToken.isEmpty()) {
            Toast.makeText(this, "請先貼上 Notion Integration Secret Token", Toast.LENGTH_SHORT).show();
            return;
        }
        NotionManager.saveToken(this, rawToken);
        btnNotionSync.setEnabled(false);
        btnNotionAddUrl.setVisibility(View.VISIBLE);
        notionStatus.setText("⏳ 正在連接 Notion 並深度掃描工作區資料庫、檢視表與近期行程…");

        NotionManager.scanWorkspaceAndSyncAsync(this, new NotionManager.SyncCallback() {
            @Override
            public void onSuccess(String workspaceName, int sourceCount, int eventCount) {
                btnNotionSync.setEnabled(true);
                refreshNotionUiState();
                showCalendars(hasCalPermission());
                if (sourceCount == 0) {
                    notionStatus.setText("⚠️ 已連上「" + workspaceName
                            + "」，但目前 0 個資料庫有開放給此 Integration！\n"
                            + "👉 解決方法：點上方「取得 Notion Token ／ 設定 Access 權限」打開你的 Integration，切換到「Access（存取權限）」分頁勾選你的資料庫並按 Save，再回來按同步（或點下方「貼上資料庫連結／ID 加入」測試）！");
                } else {
                    Toast.makeText(MainActivity.this,
                            "已同步「" + workspaceName + "」：" + sourceCount + " 個日曆檢視、共 " + eventCount + " 筆近期行程！",
                            Toast.LENGTH_LONG).show();
                }
            }

            @Override
            public void onError(String errorMessage) {
                btnNotionSync.setEnabled(true);
                notionStatus.setText("❌ " + errorMessage);
                Toast.makeText(MainActivity.this, errorMessage, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void showAddNotionByUrlDialog() {
        String rawToken = inputNotionToken.getText().toString().trim();
        if (!rawToken.isEmpty()) {
            NotionManager.saveToken(this, rawToken);
        }
        EditText input = new EditText(this);
        input.setHint("貼上 Notion 資料庫網址 (https://www.notion.so/...) 或 32 碼 ID");
        input.setMaxLines(3);
        int pad = dp(20);
        LinearLayout wrap = new LinearLayout(this);
        wrap.setPadding(pad, dp(12), pad, 0);
        wrap.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(this)
                .setTitle("手動貼上資料庫連結／ID")
                .setMessage("若剛在 Notion 開啟權限但搜尋索引尚未更新，可在 Notion 資料庫點「Copy link」貼到下方直接加入：")
                .setView(wrap)
                .setPositiveButton("讀取並加入", (d, w) -> {
                    String link = input.getText().toString().trim();
                    if (link.isEmpty()) return;
                    notionStatus.setText("⏳ 正在直接讀取指定的 Notion 資料庫與行程…");
                    NotionManager.addDatabaseByUrlOrIdAsync(this, link, new NotionManager.SyncCallback() {
                        @Override
                        public void onSuccess(String workspaceName, int sourceCount, int eventCount) {
                            refreshNotionUiState();
                            showCalendars(hasCalPermission());
                            Toast.makeText(MainActivity.this,
                                    "成功加入資料庫！目前共快取 " + eventCount + " 筆行程",
                                    Toast.LENGTH_LONG).show();
                        }

                        @Override
                        public void onError(String errorMessage) {
                            notionStatus.setText("❌ " + errorMessage);
                            new AlertDialog.Builder(MainActivity.this)
                                    .setTitle("無法讀取此 Notion 資料庫")
                                    .setMessage(errorMessage)
                                    .setPositiveButton("好", null)
                                    .show();
                        }
                    });
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void confirmClearNotion() {
        new AlertDialog.Builder(this)
                .setTitle("中斷 Notion 連結")
                .setMessage("確定要清除本機儲存的 Notion Token 與快取行程嗎？")
                .setPositiveButton("中斷並清除", (d, w) -> {
                    NotionManager.clearAccount(this);
                    inputNotionToken.setText("");
                    refresh();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showAddCustomNotionDialog() {
        NotionManager.CachedWorkspace ws = NotionManager.loadCache(this);
        if (ws == null || ws.databases.isEmpty()) {
            Toast.makeText(this, "請先同步出至少一個 Notion 資料庫", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] dbNames = new String[ws.databases.size()];
        for (int i = 0; i < ws.databases.size(); i++) {
            NotionManager.NotionDatabaseMeta db = ws.databases.get(i);
            dbNames[i] = (TextUtils.isEmpty(db.iconEmoji) ? "" : db.iconEmoji + " ") + db.title;
        }
        new AlertDialog.Builder(this)
                .setTitle("選擇 Notion 資料庫")
                .setItems(dbNames, (d1, dbIdx) -> promptViewForCustomNotion(ws.databases.get(dbIdx)))
                .show();
    }

    private void promptViewForCustomNotion(NotionManager.NotionDatabaseMeta db) {
        int viewCount = db.views.size();
        String[] viewLabels = new String[viewCount + 1];
        viewLabels[0] = "全部項目（不套用檢視表篩選）";
        for (int i = 0; i < viewCount; i++) {
            NotionManager.ViewOption v = db.views.get(i);
            String vName = TextUtils.isEmpty(v.name) ? ("檢視 " + (i + 1)) : v.name;
            viewLabels[i + 1] = vName + " [" + v.type + "]";
        }
        new AlertDialog.Builder(this)
                .setTitle("選擇要套用的檢視篩選")
                .setItems(viewLabels, (d2, viewChoice) -> {
                    NotionManager.ViewOption chosenView = viewChoice == 0 ? null : db.views.get(viewChoice - 1);
                    promptDatePropForCustomNotion(db, chosenView);
                })
                .show();
    }

    private void promptDatePropForCustomNotion(NotionManager.NotionDatabaseMeta db, NotionManager.ViewOption viewOrNull) {
        if (db.dateProps.size() == 1) {
            promptColorForCustomNotion(db, viewOrNull, db.dateProps.get(0));
            return;
        }
        String[] propLabels = new String[db.dateProps.size()];
        for (int i = 0; i < db.dateProps.size(); i++) {
            NotionManager.DatePropOption p = db.dateProps.get(i);
            propLabels[i] = p.name + ("date".equals(p.type) ? "" : " (" + p.type + ")");
        }
        new AlertDialog.Builder(this)
                .setTitle("選擇日期欄位")
                .setItems(propLabels, (d3, propIdx) ->
                        promptColorForCustomNotion(db, viewOrNull, db.dateProps.get(propIdx)))
                .show();
    }

    private void promptColorForCustomNotion(
            NotionManager.NotionDatabaseMeta db,
            NotionManager.ViewOption viewOrNull,
            NotionManager.DatePropOption dateProp) {
        CharSequence[] colorItems = buildColorMenuItems();
        new AlertDialog.Builder(this)
                .setTitle("選擇代表顏色")
                .setItems(colorItems, (d4, colorIdx) -> {
                    int color = NotionManager.NOTION_PALETTE[colorIdx];
                    notionStatus.setText("⏳ 正在新增自訂檢視並同步行程…");
                    NotionManager.addCustomSourceAndSync(this, db, viewOrNull, dateProp, color,
                            new NotionManager.SyncCallback() {
                                @Override
                                public void onSuccess(String workspaceName, int sourceCount, int eventCount) {
                                    refreshNotionUiState();
                                    showCalendars(hasCalPermission());
                                    Toast.makeText(MainActivity.this, "已新增自訂 Notion 日曆檢視！", Toast.LENGTH_SHORT).show();
                                }

                                @Override
                                public void onError(String errorMessage) {
                                    notionStatus.setText("❌ " + errorMessage);
                                }
                            });
                })
                .show();
    }

    private void showSourceColorPicker(NotionManager.NotionSource source) {
        CharSequence[] items = buildColorMenuItems();
        new AlertDialog.Builder(this)
                .setTitle("自訂「" + source.displayName + "」代表色")
                .setItems(items, (dialog, which) -> {
                    NotionManager.setSourceColor(this, source.sourceKey, NotionManager.NOTION_PALETTE[which]);
                    CalendarWidgetProvider.updateAll(this);
                    showCalendars(hasCalPermission());
                })
                .show();
    }

    private CharSequence[] buildColorMenuItems() {
        CharSequence[] items = new CharSequence[NotionManager.NOTION_PALETTE.length];
        for (int i = 0; i < NotionManager.NOTION_PALETTE.length; i++) {
            SpannableString s = new SpannableString("●  " + NotionManager.NOTION_PALETTE_NAMES[i]);
            s.setSpan(new ForegroundColorSpan(NotionManager.NOTION_PALETTE[i]),
                    0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            items[i] = s;
        }
        return items;
    }

    private boolean hasCalPermission() {
        return checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED;
    }

    private void refreshNotionUiState() {
        boolean connected = NotionManager.hasToken(this);
        btnNotionClear.setVisibility(connected ? View.VISIBLE : View.GONE);
        btnNotionAddUrl.setVisibility(connected ? View.VISIBLE : View.GONE);
        NotionManager.CachedWorkspace ws = NotionManager.loadCache(this);
        if (!connected) {
            notionStatus.setText("尚未連接 Notion（貼上 Token 後即可自動抓取你的 Notion 日曆）");
            btnNotionAddCustom.setVisibility(View.GONE);
        } else if (ws != null) {
            long lastMs = NotionManager.getLastSyncMillis(this);
            String timeStr = lastMs > 0
                    ? Instant.ofEpochMilli(lastMs).atZone(ZoneId.systemDefault())
                    .format(DateTimeFormatter.ofPattern("HH:mm"))
                    : "--:--";
            String base = String.format(Locale.TAIWAN,
                    "✅ 已連結：%s（%d 個日曆檢視 · 共抓到 %d 筆近期行程 · 同步於 %s）",
                    ws.workspaceName, ws.sources.size(), ws.events.size(), timeStr);
            if (ws.sources.isEmpty()) {
                base += "\n⚠️ 尚未抓到授權的資料庫！請點上方「設定 Access 權限」到 Notion Integration 的 Access 分頁勾選資料庫，或點下方「貼上資料庫連結／ID 加入」。";
            } else if (!TextUtils.isEmpty(ws.diagnosticNote)) {
                base += "\n⚠️ " + ws.diagnosticNote;
            }
            notionStatus.setText(base);
            btnNotionAddCustom.setVisibility(ws.databases.isEmpty() ? View.GONE : View.VISIBLE);
        } else {
            notionStatus.setText("已儲存 Token，請點「連接並同步 Notion」進行掃描");
            btnNotionAddCustom.setVisibility(View.GONE);
        }
    }

    private void refresh() {
        boolean granted = hasCalPermission();
        status.setText(granted ? R.string.status_granted : R.string.status_denied);
        grant.setEnabled(!granted);
        if (!granted && askedOnce && !shouldShowRequestPermissionRationale(Manifest.permission.READ_CALENDAR)) {
            grant.setText(R.string.btn_open_settings);
        } else {
            grant.setText(R.string.btn_grant);
        }

        refreshWidgetSelectorTabs();
        refreshNotionUiState();
        showCalendars(granted);
        CalendarWidgetProvider.requestSync(this, CalendarWidgetProvider.SYNC_ON_TOUCH_MS);
        CalendarWidgetProvider.updateAll(this);
    }

    private void showCalendars(boolean hasSystemPermission) {
        calendars.removeAllViews();
        boolean hasNotion = NotionManager.hasToken(this);
        NotionManager.CachedWorkspace notionWs = hasNotion ? NotionManager.loadCache(this) : null;
        boolean hasNotionSources = notionWs != null && !notionWs.sources.isEmpty();

        if (!hasSystemPermission && !hasNotionSources) {
            calendars.addView(note(getString(R.string.calendars_need_permission)));
            return;
        }

        final int targetProfileId = editingProfileId;
        Set<String> hidden = CalendarWidgetProvider.hiddenCalendars(this, targetProfileId);
        TextView summaryView = note("");
        calendars.addView(summaryView);

        int accountCount = 0;
        int totalCalendars = 0;
        int visibleCalendars = 0;

        // 1. System / Google Calendars
        if (hasSystemPermission) {
            String[] proj = new String[]{
                    CalendarContract.Calendars._ID,
                    CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                    CalendarContract.Calendars.ACCOUNT_NAME,
                    CalendarContract.Calendars.CALENDAR_COLOR,
                    CalendarContract.Calendars.VISIBLE,
                    CalendarContract.Calendars.SYNC_EVENTS,
                    CalendarContract.Calendars.ACCOUNT_TYPE
            };
            String sort = CalendarContract.Calendars.ACCOUNT_NAME + " COLLATE NOCASE ASC, "
                    + CalendarContract.Calendars.IS_PRIMARY + " DESC, "
                    + CalendarContract.Calendars.CALENDAR_DISPLAY_NAME + " COLLATE NOCASE ASC";

            String currentAccount = null;
            try (Cursor c = getContentResolver().query(
                    CalendarContract.Calendars.CONTENT_URI, proj, null, null, sort)) {
                if (c != null) {
                    while (c.moveToNext()) {
                        long calId = c.getLong(0);
                        String rawName = c.getString(1);
                        String accName = c.getString(2);
                        int color = c.getInt(3) | 0xFF000000;
                        boolean synced = c.getInt(4) != 0 && c.getInt(5) != 0;
                        String accType = c.getString(6);

                        String displayName = TextUtils.isEmpty(rawName) ? getString(R.string.untitled) : rawName;
                        String accountLabel = accName == null ? "" : accName;

                        if (!accountLabel.equals(currentAccount)) {
                            accountCount++;
                            calendars.addView(accountHeader(accountLabel));
                            currentAccount = accountLabel;
                        }

                        String labelText = synced ? displayName : getString(R.string.calendar_not_synced, displayName);
                        SpannableString span = new SpannableString("● " + labelText);
                        span.setSpan(new ForegroundColorSpan(color), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

                        CheckBox cb = new CheckBox(this);
                        cb.setText(span);
                        cb.setTextSize(15f);
                        cb.setMinHeight(dp(44));
                        boolean isChecked = synced && !hidden.contains(String.valueOf(calId));
                        cb.setChecked(isChecked);

                        final boolean wasSynced = synced;
                        cb.setOnCheckedChangeListener((btn, checked) -> {
                            if (checked && !wasSynced) {
                                boolean ok = enableSync(calId, accountLabel, accType);
                                Toast.makeText(this,
                                        getString(ok ? R.string.sync_requested : R.string.sync_failed, displayName),
                                        Toast.LENGTH_LONG).show();
                            }
                            CalendarWidgetProvider.setCalendarHidden(this, targetProfileId, calId, !checked);
                            CalendarWidgetProvider.updateWidget(this, targetProfileId);
                            calendars.post(() -> showCalendars(hasCalPermission()));
                        });
                        calendars.addView(cb);

                        totalCalendars++;
                        if (isChecked) visibleCalendars++;
                    }
                }
            } catch (SecurityException ignored) {
            }
        }

        // 2. Notion Workspace Calendars & Views
        if (hasNotionSources) {
            accountCount++;
            calendars.addView(accountHeader("Notion · " + notionWs.workspaceName));

            List<NotionManager.NotionSource> sources = notionWs.sources;
            for (NotionManager.NotionSource src : sources) {
                int srcColor = NotionManager.getSourceColor(this, src);
                boolean isChecked = !hidden.contains(src.sourceKey);
                int evCount = notionWs.countEventsForSource(src.sourceKey);

                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setMinimumHeight(dp(44));

                View colorChip = new View(this);
                LinearLayout.LayoutParams chipLp = new LinearLayout.LayoutParams(dp(22), dp(22));
                chipLp.setMarginEnd(dp(8));
                colorChip.setLayoutParams(chipLp);
                GradientDrawable chipBg = new GradientDrawable();
                chipBg.setShape(GradientDrawable.RECTANGLE);
                chipBg.setCornerRadius(dp(6));
                chipBg.setColor(srcColor);
                colorChip.setBackground(chipBg);
                colorChip.setContentDescription("修改代表色");
                colorChip.setOnClickListener(v -> showSourceColorPicker(src));
                row.addView(colorChip);

                CheckBox cb = new CheckBox(this);
                LinearLayout.LayoutParams cbLp = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                cb.setLayoutParams(cbLp);
                cb.setText(src.fullTitleWithIcon() + "（" + evCount + " 筆）");
                cb.setTextSize(15f);
                cb.setMinHeight(dp(44));
                cb.setChecked(isChecked);
                cb.setOnCheckedChangeListener((btn, checked) -> {
                    CalendarWidgetProvider.setCalendarKeyHidden(this, targetProfileId, src.sourceKey, !checked);
                    CalendarWidgetProvider.updateWidget(this, targetProfileId);
                    if (checked && evCount == 0) {
                        NotionManager.refreshEventsOnlyAsync(this, 0L, new NotionManager.SyncCallback() {
                            @Override
                            public void onSuccess(String workspaceName, int sourceCount, int eventCount) {
                                refreshNotionUiState();
                                showCalendars(hasCalPermission());
                            }

                            @Override
                            public void onError(String errorMessage) {
                            }
                        });
                    } else {
                        calendars.post(() -> showCalendars(hasCalPermission()));
                    }
                });
                row.addView(cb);

                if (src.customAdded) {
                    TextView removeBtn = new TextView(this);
                    removeBtn.setText("✕");
                    removeBtn.setTextSize(15f);
                    removeBtn.setPadding(dp(10), dp(6), dp(6), dp(6));
                    removeBtn.setOnClickListener(v -> {
                        NotionManager.removeCustomSource(this, src.sourceKey);
                        refreshNotionUiState();
                        showCalendars(hasCalPermission());
                    });
                    row.addView(removeBtn);
                }

                calendars.addView(row);
                totalCalendars++;
                if (isChecked) visibleCalendars++;
            }
        }

        if (totalCalendars == 0) {
            summaryView.setText(R.string.calendars_none);
        } else {
            summaryView.setText(getString(R.string.calendar_summary,
                    accountCount, totalCalendars, visibleCalendars));
        }
    }

    private TextView accountHeader(String title) {
        TextView tv = new TextView(this);
        tv.setText(title);
        tv.setTextSize(15f);
        tv.setTypeface(tv.getTypeface(), Typeface.BOLD);
        tv.setPadding(0, dp(14), 0, dp(2));
        return tv;
    }

    private boolean enableSync(long calendarId, String accountName, String accountType) {
        if (checkSelfPermission(Manifest.permission.WRITE_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(CALENDAR_PERMISSIONS, REQUEST_CALENDAR);
            return false;
        }
        try {
            ContentValues cv = new ContentValues();
            cv.put(CalendarContract.Calendars.SYNC_EVENTS, 1);
            cv.put(CalendarContract.Calendars.VISIBLE, 1);
            int rows = getContentResolver().update(
                    ContentUris.withAppendedId(CalendarContract.Calendars.CONTENT_URI, calendarId),
                    cv, null, null);
            if (rows == 0) return false;
            if (!TextUtils.isEmpty(accountName) && !TextUtils.isEmpty(accountType)) {
                Bundle extras = new Bundle();
                extras.putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true);
                extras.putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true);
                ContentResolver.requestSync(new Account(accountName, accountType),
                        CalendarContract.AUTHORITY, extras);
            }
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private TextView note(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(14f);
        tv.setTypeface(tv.getTypeface(), Typeface.BOLD);
        return tv;
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception ignored) {
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
