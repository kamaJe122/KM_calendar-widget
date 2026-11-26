package io.github.kamaje122.calwidget;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.JulianFields;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Handles Notion Official API (2026-03-11 / 2025-09-03 Data Sources & Views API)
 * workspace discovery, multi-view calendar filtering, custom per-source colors,
 * local offline caching, and background widget synchronization.
 */
public final class NotionManager {

    private static final String PREFS_NOTION = "notion_prefs";
    private static final String KEY_TOKEN = "notion_token";
    private static final String KEY_WORKSPACE_NAME = "workspace_name";
    private static final String KEY_LAST_SYNC_MS = "last_sync_ms";
    private static final String KEY_COLOR_PREFIX = "color_";

    private static final String CACHE_FILE_NAME = "notion_cache_v2.json";
    private static final String API_BASE = "https://api.notion.com/v1";
    private static final String NOTION_VERSION_PRIMARY = "2026-03-11";
    private static final String NOTION_VERSION_FALLBACK = "2025-09-03";

    public static final int[] NOTION_PALETTE = new int[]{
            0xFF2E90FA, // 藍 Blue
            0xFF9B51E0, // 紫 Purple
            0xFFE5484D, // 紅 Red
            0xFF30A46C, // 綠 Green
            0xFFF59E0B, // 黃 Yellow
            0xFFF97316, // 橘 Orange
            0xFFF4937A, // 粉橘 Peach
            0xFF6366F1, // 靛藍 Indigo
            0xFF14B8A6, // 青綠 Teal
            0xFF8E8E93  // 灰 Grey
    };

    public static final String[] NOTION_PALETTE_NAMES = new String[]{
            "天空藍", "羅蘭紫", "珊瑚紅", "翡翠綠", "琥珀黃",
            "活力橘", "蜜桃粉", "靛青藍", "松石綠", "石墨灰"
    };

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static final AtomicBoolean SYNC_IN_PROGRESS = new AtomicBoolean(false);

    private static volatile long cachedFileMtime = -1L;
    private static volatile CachedWorkspace cachedWorkspace = null;

    public static final class DatePropOption {
        public final String id;
        public final String name;
        public final String type; // "date", "created_time", "last_edited_time"

        DatePropOption(String id, String name, String type) {
            this.id = id;
            this.name = name;
            this.type = type;
        }
    }

    public static final class ViewOption {
        public final String id;
        public final String name;
        public final String type; // "calendar", "timeline", "table", "board", "list", etc.
        public final String datePropId;
        public final String endDatePropId;
        public final String filterJson;

        ViewOption(String id, String name, String type, String datePropId, String endDatePropId, String filterJson) {
            this.id = id;
            this.name = name;
            this.type = type;
            this.datePropId = datePropId;
            this.endDatePropId = endDatePropId;
            this.filterJson = filterJson;
        }
    }

    public static final class NotionDatabaseMeta {
        public final String dataSourceId;
        public final String databaseId;
        public final String title;
        public final String iconEmoji;
        public final List<DatePropOption> dateProps;
        public final List<ViewOption> views;

        NotionDatabaseMeta(String dataSourceId, String databaseId, String title, String iconEmoji,
                           List<DatePropOption> dateProps, List<ViewOption> views) {
            this.dataSourceId = dataSourceId;
            this.databaseId = databaseId;
            this.title = title;
            this.iconEmoji = iconEmoji;
            this.dateProps = dateProps;
            this.views = views;
        }
    }

    public static final class NotionSource {
        public final String sourceKey;
        public final String dataSourceId;
        public final String databaseId;
        public final String viewId;
        public final String viewType;
        public final String displayName;
        public final String shortLabel;
        public final String iconEmoji;
        public final String datePropId;
        public final String datePropName;
        public final String datePropType;
        public final String endDatePropName;
        public final String filterJson;
        public final int defaultColorIndex;
        public final boolean customAdded;

        NotionSource(String sourceKey, String dataSourceId, String databaseId, String viewId,
                     String viewType, String displayName, String shortLabel, String iconEmoji,
                     String datePropId, String datePropName, String datePropType,
                     String endDatePropName, String filterJson, int defaultColorIndex,
                     boolean customAdded) {
            this.sourceKey = sourceKey;
            this.dataSourceId = dataSourceId;
            this.databaseId = databaseId;
            this.viewId = viewId;
            this.viewType = viewType;
            this.displayName = displayName;
            this.shortLabel = shortLabel;
            this.iconEmoji = iconEmoji;
            this.datePropId = datePropId;
            this.datePropName = datePropName;
            this.datePropType = datePropType;
            this.endDatePropName = endDatePropName;
            this.filterJson = filterJson;
            this.defaultColorIndex = defaultColorIndex;
            this.customAdded = customAdded;
        }

        public String fullTitleWithIcon() {
            if (!TextUtils.isEmpty(iconEmoji) && !displayName.startsWith(iconEmoji)) {
                return iconEmoji + " " + displayName;
            }
            return displayName;
        }
    }

    public static final class NotionCachedEvent {
        public final String sourceKey;
        public final String pageId;
        public final String title;
        public final String statusLabel;
        public final String notionUrl;
        public final boolean allDay;
        public final long startEpochDay; // inclusive
        public final long endEpochDay;   // inclusive (for allDay)
        public final long beginMillis;   // for timed events
        public final long endMillis;     // for timed events
        public final boolean hasExplicitEnd;

        NotionCachedEvent(String sourceKey, String pageId, String title, String statusLabel,
                          String notionUrl, boolean allDay, long startEpochDay, long endEpochDay,
                          long beginMillis, long endMillis, boolean hasExplicitEnd) {
            this.sourceKey = sourceKey;
            this.pageId = pageId;
            this.title = title;
            this.statusLabel = statusLabel;
            this.notionUrl = notionUrl;
            this.allDay = allDay;
            this.startEpochDay = startEpochDay;
            this.endEpochDay = endEpochDay;
            this.beginMillis = beginMillis;
            this.endMillis = endMillis;
            this.hasExplicitEnd = hasExplicitEnd;
        }
    }

    public static final class CachedWorkspace {
        public final String workspaceName;
        public final List<NotionSource> sources;
        public final List<NotionDatabaseMeta> databases;
        public final List<NotionCachedEvent> events;

        CachedWorkspace(String workspaceName, List<NotionSource> sources,
                        List<NotionDatabaseMeta> databases, List<NotionCachedEvent> events) {
            this.workspaceName = workspaceName;
            this.sources = sources;
            this.databases = databases;
            this.events = events;
        }
    }

    public interface SyncCallback {
        void onSuccess(String workspaceName, int sourceCount, int eventCount);
        void onError(String errorMessage);
    }

    private NotionManager() {}

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NOTION, Context.MODE_PRIVATE);
    }

    public static String getToken(Context context) {
        return prefs(context).getString(KEY_TOKEN, "").trim();
    }

    public static boolean hasToken(Context context) {
        return !getToken(context).isEmpty();
    }

    public static void saveToken(Context context, String rawToken) {
        String clean = rawToken == null ? "" : rawToken.trim();
        if (clean.regionMatches(true, 0, "Bearer ", 0, 7)) {
            clean = clean.substring(7).trim();
        }
        prefs(context).edit().putString(KEY_TOKEN, clean).apply();
    }

    public static void clearAccount(Context context) {
        prefs(context).edit().clear().apply();
        File f = new File(context.getFilesDir(), CACHE_FILE_NAME);
        if (f.exists()) f.delete();
        cachedFileMtime = -1L;
        cachedWorkspace = null;
    }

    public static String getWorkspaceName(Context context) {
        CachedWorkspace ws = loadCache(context);
        if (ws != null && !TextUtils.isEmpty(ws.workspaceName)) {
            return ws.workspaceName;
        }
        return prefs(context).getString(KEY_WORKSPACE_NAME, "Notion");
    }

    public static long getLastSyncMillis(Context context) {
        return prefs(context).getLong(KEY_LAST_SYNC_MS, 0L);
    }

    public static int getSourceColor(Context context, NotionSource source) {
        int fallback = NOTION_PALETTE[Math.floorMod(source.defaultColorIndex, NOTION_PALETTE.length)];
        return prefs(context).getInt(KEY_COLOR_PREFIX + source.sourceKey, fallback) | 0xFF000000;
    }

    public static void setSourceColor(Context context, String sourceKey, int color) {
        prefs(context).edit().putInt(KEY_COLOR_PREFIX + sourceKey, color | 0xFF000000).apply();
    }

    /**
     * Full workspace discovery + event sync (called when user clicks Connect/Rescan in MainActivity).
     */
    public static void scanWorkspaceAndSyncAsync(Context context, SyncCallback callback) {
        final Context appContext = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            if (!SYNC_IN_PROGRESS.compareAndSet(false, true)) {
                if (callback != null) {
                    MAIN_HANDLER.post(() -> callback.onError("正在同步 Notion 中，請稍候…"));
                }
                return;
            }
            try {
                String token = getToken(appContext);
                if (token.isEmpty()) {
                    if (callback != null) {
                        MAIN_HANDLER.post(() -> callback.onError("請先貼上 Notion Integration Secret Token"));
                    }
                    return;
                }

                // 1. Verify token & fetch workspace name
                HttpResult meRes = requestNotion(token, "GET", "/users/me", null);
                if (meRes.code == 401) {
                    postErr(callback, "Notion Token 無效 (HTTP 401)，請確認已複製完整的 Internal Integration Secret");
                    return;
                }
                if (meRes.code != 200) {
                    postErr(callback, "連線 Notion 失敗 (HTTP " + meRes.code + ")：" + meRes.errorMsg);
                    return;
                }

                JSONObject meJson = new JSONObject(meRes.body);
                String workspaceName = "Notion";
                JSONObject botObj = meJson.optJSONObject("bot");
                if (botObj != null && !TextUtils.isEmpty(botObj.optString("workspace_name"))) {
                    workspaceName = botObj.optString("workspace_name");
                } else if (!TextUtils.isEmpty(meJson.optString("name"))) {
                    workspaceName = meJson.optString("name");
                }

                // Preserve existing custom sources
                CachedWorkspace oldCache = loadCache(appContext);
                List<NotionSource> preservedCustom = new ArrayList<>();
                if (oldCache != null) {
                    for (NotionSource s : oldCache.sources) {
                        if (s.customAdded) preservedCustom.add(s);
                    }
                }

                // 2. Discover all shared data_sources
                List<JSONObject> rawDataSources = fetchAllDataSources(token);
                Map<String, JSONObject> dbContainerCache = new HashMap<>();

                List<NotionDatabaseMeta> discoveredDbs = new ArrayList<>();
                List<NotionSource> discoveredSources = new ArrayList<>();
                int colorCounter = 0;

                for (JSONObject ds : rawDataSources) {
                    if (ds.optBoolean("in_trash", false) || ds.optBoolean("is_archived", false)) {
                        continue;
                    }
                    String dsId = ds.optString("id", "");
                    if (dsId.isEmpty()) continue;

                    String dbId = "";
                    JSONObject parent = ds.optJSONObject("parent");
                    if (parent != null && "database_id".equals(parent.optString("type"))) {
                        dbId = parent.optString("database_id", "");
                    }

                    String dsTitle = extractPlainText(ds.optJSONArray("title")).trim();
                    String iconEmoji = extractEmoji(ds.optJSONObject("icon"));

                    if (!dbId.isEmpty() && (dsTitle.isEmpty() || "Default".equalsIgnoreCase(dsTitle) || iconEmoji.isEmpty())) {
                        JSONObject dbObj = dbContainerCache.get(dbId);
                        if (dbObj == null) {
                            HttpResult dbRes = requestNotion(token, "GET", "/databases/" + dbId, null);
                            if (dbRes.code == 200) {
                                dbObj = new JSONObject(dbRes.body);
                                dbContainerCache.put(dbId, dbObj);
                            }
                        }
                        if (dbObj != null) {
                            String containerTitle = extractPlainText(dbObj.optJSONArray("title")).trim();
                            String containerEmoji = extractEmoji(dbObj.optJSONObject("icon"));
                            if (!containerTitle.isEmpty()) {
                                if (dsTitle.isEmpty() || "Default".equalsIgnoreCase(dsTitle) || dsTitle.equals(containerTitle)) {
                                    dsTitle = containerTitle;
                                } else {
                                    dsTitle = containerTitle + " · " + dsTitle;
                                }
                            }
                            if (iconEmoji.isEmpty() && !containerEmoji.isEmpty()) {
                                iconEmoji = containerEmoji;
                            }
                        }
                    }
                    if (dsTitle.isEmpty()) dsTitle = "未命名資料庫";

                    JSONObject propsObj = ds.optJSONObject("properties");
                    if (propsObj == null || propsObj.length() == 0) {
                        HttpResult fullDsRes = requestNotion(token, "GET", "/data_sources/" + dsId, null);
                        if (fullDsRes.code == 200) {
                            JSONObject fullDs = new JSONObject(fullDsRes.body);
                            propsObj = fullDs.optJSONObject("properties");
                        }
                    }
                    if (propsObj == null) continue;

                    // Parse date-capable properties
                    List<DatePropOption> primaryDateProps = new ArrayList<>();
                    List<DatePropOption> allDateCapableProps = new ArrayList<>();
                    Map<String, DatePropOption> propLookup = new HashMap<>();

                    Iterator<String> keys = propsObj.keys();
                    while (keys.hasNext()) {
                        String propName = keys.next();
                        JSONObject p = propsObj.optJSONObject(propName);
                        if (p == null) continue;
                        String pType = p.optString("type", "");
                        String pId = p.optString("id", propName);
                        if ("date".equals(pType) || "created_time".equals(pType) || "last_edited_time".equals(pType)) {
                            DatePropOption opt = new DatePropOption(pId, propName, pType);
                            allDateCapableProps.add(opt);
                            propLookup.put(pId, opt);
                            propLookup.put(decodePropId(pId), opt);
                            propLookup.put(propName, opt);
                            if ("date".equals(pType)) {
                                primaryDateProps.add(opt);
                            }
                        }
                    }

                    if (allDateCapableProps.isEmpty()) continue;

                    // Discover views on this data_source / database
                    List<ViewOption> allViews = fetchViewsForDataSource(token, dsId, dbId);
                    List<ViewOption> calOrTimelineViews = new ArrayList<>();
                    for (ViewOption v : allViews) {
                        if ("calendar".equals(v.type) || "timeline".equals(v.type)) {
                            calOrTimelineViews.add(v);
                        }
                    }

                    // Only treat as a Notion Calendar database if it has at least one `date` property
                    // OR an explicit calendar/timeline view.
                    if (primaryDateProps.isEmpty() && calOrTimelineViews.isEmpty()) {
                        continue;
                    }

                    discoveredDbs.add(new NotionDatabaseMeta(
                            dsId, dbId, dsTitle, iconEmoji, allDateCapableProps, allViews));

                    if (!calOrTimelineViews.isEmpty()) {
                        boolean multipleCalViews = calOrTimelineViews.size() > 1;
                        for (int idx = 0; idx < calOrTimelineViews.size(); idx++) {
                            ViewOption v = calOrTimelineViews.get(idx);
                            DatePropOption resolvedStart = resolveDateProp(v.datePropId, propLookup, primaryDateProps, allDateCapableProps);
                            if (resolvedStart == null) continue;
                            DatePropOption resolvedEnd = !TextUtils.isEmpty(v.endDatePropId)
                                    ? propLookup.get(v.endDatePropId) : null;
                            if (resolvedEnd == null && !TextUtils.isEmpty(v.endDatePropId)) {
                                resolvedEnd = propLookup.get(decodePropId(v.endDatePropId));
                            }

                            String viewLabel = v.name == null ? "" : v.name.trim();
                            String displayName;
                            String shortLabel;
                            if (multipleCalViews) {
                                String suffix = !viewLabel.isEmpty() ? viewLabel : ("檢視 " + (idx + 1));
                                displayName = dsTitle + " · " + suffix;
                                shortLabel = dsTitle + " (" + suffix + ")";
                            } else if (!TextUtils.isEmpty(v.filterJson) && !isGenericViewName(viewLabel, dsTitle)) {
                                displayName = dsTitle + " · " + viewLabel;
                                shortLabel = dsTitle;
                            } else {
                                displayName = dsTitle;
                                shortLabel = dsTitle;
                            }

                            discoveredSources.add(new NotionSource(
                                    "notion:view:" + v.id,
                                    dsId,
                                    dbId,
                                    v.id,
                                    v.type,
                                    displayName,
                                    shortLabel,
                                    iconEmoji,
                                    resolvedStart.id,
                                    resolvedStart.name,
                                    resolvedStart.type,
                                    resolvedEnd != null ? resolvedEnd.name : "",
                                    v.filterJson,
                                    colorCounter++,
                                    false
                            ));
                        }
                    } else {
                        boolean multiDate = primaryDateProps.size() > 1;
                        for (DatePropOption dp : primaryDateProps) {
                            String displayName = multiDate ? (dsTitle + " · " + dp.name) : dsTitle;
                            discoveredSources.add(new NotionSource(
                                    "notion:ds:" + dsId + ":" + dp.id,
                                    dsId,
                                    dbId,
                                    "",
                                    "database",
                                    displayName,
                                    dsTitle,
                                    iconEmoji,
                                    dp.id,
                                    dp.name,
                                    dp.type,
                                    "",
                                    "",
                                    colorCounter++,
                                    false
                            ));
                        }
                    }
                }

                // Append preserved custom sources whose dataSource still exists
                Set<String> validDsIds = new HashSet<>();
                for (NotionDatabaseMeta m : discoveredDbs) validDsIds.add(m.dataSourceId);
                Set<String> existingKeys = new HashSet<>();
                for (NotionSource s : discoveredSources) existingKeys.add(s.sourceKey);
                for (NotionSource custom : preservedCustom) {
                    if (validDsIds.contains(custom.dataSourceId) && !existingKeys.contains(custom.sourceKey)) {
                        discoveredSources.add(custom);
                        existingKeys.add(custom.sourceKey);
                    }
                }

                // 3. Query events for all visible NotionSources
                Set<String> hidden = CalendarWidgetProvider.hiddenCalendars(appContext);
                List<NotionCachedEvent> events = queryEventsForSources(token, discoveredSources, hidden);

                // 4. Persist cache & update widget
                CachedWorkspace newWs = new CachedWorkspace(workspaceName, discoveredSources, discoveredDbs, events);
                saveCache(appContext, newWs);
                prefs(appContext).edit()
                        .putString(KEY_WORKSPACE_NAME, workspaceName)
                        .putLong(KEY_LAST_SYNC_MS, System.currentTimeMillis())
                        .apply();

                CalendarWidgetProvider.updateAll(appContext);

                final String finalWsName = workspaceName;
                final int srcCount = discoveredSources.size();
                final int evCount = events.size();
                if (callback != null) {
                    MAIN_HANDLER.post(() -> callback.onSuccess(finalWsName, srcCount, evCount));
                }
            } catch (Exception e) {
                postErr(callback, "Notion 同步發生錯誤：" + e.getMessage());
            } finally {
                SYNC_IN_PROGRESS.set(false);
            }
        });
    }

    /**
     * Adds a user-configured custom View + Date property combination and syncs immediately.
     */
    public static void addCustomSourceAndSync(Context context, NotionDatabaseMeta db,
                                              ViewOption viewOrNull, DatePropOption dateProp,
                                              int chosenColor, SyncCallback callback) {
        final Context appContext = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            CachedWorkspace ws = loadCache(appContext);
            if (ws == null) {
                postErr(callback, "請先連接並掃描 Notion 工作區");
                return;
            }
            String key = "notion:custom:" + UUID.randomUUID().toString().substring(0, 8);
            String suffix = (viewOrNull != null ? viewOrNull.name + " · " : "") + dateProp.name;
            String displayName = db.title + "（" + suffix + "）";
            NotionSource customSource = new NotionSource(
                    key,
                    db.dataSourceId,
                    db.databaseId,
                    viewOrNull != null ? viewOrNull.id : "",
                    viewOrNull != null ? viewOrNull.type : "custom",
                    displayName,
                    db.title,
                    db.iconEmoji,
                    dateProp.id,
                    dateProp.name,
                    dateProp.type,
                    "",
                    viewOrNull != null ? viewOrNull.filterJson : "",
                    ws.sources.size(),
                    true
            );
            setSourceColor(appContext, key, chosenColor);
            List<NotionSource> updatedSources = new ArrayList<>(ws.sources);
            updatedSources.add(customSource);
            saveCache(appContext, new CachedWorkspace(ws.workspaceName, updatedSources, ws.databases, ws.events));
            refreshEventsOnlyAsync(appContext, 0L, callback);
        });
    }

    public static void removeCustomSource(Context context, String sourceKey) {
        CachedWorkspace ws = loadCache(context);
        if (ws == null) return;
        List<NotionSource> keptSources = new ArrayList<>();
        for (NotionSource s : ws.sources) {
            if (!s.sourceKey.equals(sourceKey)) keptSources.add(s);
        }
        List<NotionCachedEvent> keptEvents = new ArrayList<>();
        for (NotionCachedEvent ev : ws.events) {
            if (!ev.sourceKey.equals(sourceKey)) keptEvents.add(ev);
        }
        saveCache(context, new CachedWorkspace(ws.workspaceName, keptSources, ws.databases, keptEvents));
        CalendarWidgetProvider.updateAll(context);
    }

    /**
     * Lightweight background event sync using already-discovered NotionSources.
     */
    public static void refreshEventsOnlyAsync(Context context, long minIntervalMs, SyncCallback callback) {
        final Context appContext = context.getApplicationContext();
        String token = getToken(appContext);
        if (token.isEmpty()) return;

        long now = System.currentTimeMillis();
        long last = getLastSyncMillis(appContext);
        if (minIntervalMs > 0L && now >= last && (now - last) < minIntervalMs) {
            return;
        }

        EXECUTOR.execute(() -> {
            if (!SYNC_IN_PROGRESS.compareAndSet(false, true)) return;
            try {
                CachedWorkspace ws = loadCache(appContext);
                if (ws == null || ws.sources.isEmpty()) {
                    SYNC_IN_PROGRESS.set(false);
                    scanWorkspaceAndSyncAsync(appContext, callback);
                    return;
                }
                Set<String> hidden = CalendarWidgetProvider.hiddenCalendars(appContext);
                List<NotionCachedEvent> events = queryEventsForSources(token, ws.sources, hidden);
                CachedWorkspace updated = new CachedWorkspace(ws.workspaceName, ws.sources, ws.databases, events);
                saveCache(appContext, updated);
                prefs(appContext).edit().putLong(KEY_LAST_SYNC_MS, System.currentTimeMillis()).apply();
                CalendarWidgetProvider.updateAll(appContext);
                if (callback != null) {
                    final String wsName = ws.workspaceName;
                    final int sCount = ws.sources.size();
                    final int eCount = events.size();
                    MAIN_HANDLER.post(() -> callback.onSuccess(wsName, sCount, eCount));
                }
            } catch (Exception e) {
                if (callback != null) {
                    postErr(callback, "同步 Notion 行程失敗：" + e.getMessage());
                }
            } finally {
                SYNC_IN_PROGRESS.set(false);
            }
        });
    }

    /**
     * Fast in-memory/local-disk lookup for CalendarWidgetProvider.loadEvents().
     * Never performs network I/O on the widget binder thread.
     */
    public static List<CalendarWidgetProvider.Event> loadEventsForDay(
            Context context,
            LocalDate day,
            ZoneId zone,
            boolean filterEndedToday,
            boolean showAllDay,
            Set<String> hiddenCalendars) {

        if (!hasToken(context)) return Collections.emptyList();
        CachedWorkspace ws = loadCache(context);
        if (ws == null || ws.events.isEmpty()) return Collections.emptyList();

        Map<String, NotionSource> activeSources = new HashMap<>();
        Map<String, Integer> sourceColors = new HashMap<>();
        for (NotionSource s : ws.sources) {
            if (!hiddenCalendars.contains(s.sourceKey)) {
                activeSources.put(s.sourceKey, s);
                sourceColors.put(s.sourceKey, getSourceColor(context, s));
            }
        }
        if (activeSources.isEmpty()) return Collections.emptyList();

        long targetEpochDay = day.toEpochDay();
        long dayStartMillis = day.atStartOfDay(zone).toInstant().toEpochMilli();
        long dayEndMillis = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
        long nowMillis = System.currentTimeMillis();

        List<CalendarWidgetProvider.Event> out = new ArrayList<>();
        for (NotionCachedEvent nev : ws.events) {
            NotionSource src = activeSources.get(nev.sourceKey);
            if (src == null) continue;

            if (nev.allDay) {
                if (!showAllDay) continue;
                if (targetEpochDay < nev.startEpochDay || targetEpochDay > nev.endEpochDay) {
                    continue;
                }
            } else {
                boolean startsToday = nev.beginMillis >= dayStartMillis && nev.beginMillis < dayEndMillis;
                boolean spansToday = nev.beginMillis < dayStartMillis && nev.endMillis > dayStartMillis;
                if (!startsToday && !spansToday) continue;
                if (filterEndedToday && nev.endMillis <= nowMillis && nev.endMillis > nev.beginMillis) {
                    continue;
                }
            }

            CalendarWidgetProvider.Event ev = new CalendarWidgetProvider.Event();
            ev.eventId = -1L;
            ev.notionUrl = nev.notionUrl;
            ev.allDay = nev.allDay;
            ev.begin = nev.allDay ? dayStartMillis : nev.beginMillis;
            ev.end = nev.allDay ? dayEndMillis : nev.endMillis;
            ev.color = sourceColors.getOrDefault(nev.sourceKey, NOTION_PALETTE[0]);
            ev.title = TextUtils.isEmpty(nev.title) ? context.getString(R.string.untitled) : nev.title;

            StringBuilder sub = new StringBuilder();
            if (nev.allDay) {
                sub.append(context.getString(R.string.all_day));
            } else if (nev.hasExplicitEnd) {
                sub.append(CalendarWidgetProvider.hm(nev.beginMillis, zone))
                        .append("–")
                        .append(CalendarWidgetProvider.hm(nev.endMillis, zone));
            } else {
                sub.append(CalendarWidgetProvider.hm(nev.beginMillis, zone));
            }
            if (!TextUtils.isEmpty(src.shortLabel)) {
                sub.append(" · ").append(src.shortLabel);
            }
            if (!TextUtils.isEmpty(nev.statusLabel)) {
                sub.append(" · ").append(nev.statusLabel);
            }
            ev.subtitle = sub.toString();
            out.add(ev);
        }
        return out;
    }

    public static synchronized CachedWorkspace loadCache(Context context) {
        File f = new File(context.getFilesDir(), CACHE_FILE_NAME);
        if (!f.exists()) {
            cachedFileMtime = -1L;
            cachedWorkspace = null;
            return null;
        }
        long mtime = f.lastModified();
        if (cachedWorkspace != null && mtime == cachedFileMtime) {
            return cachedWorkspace;
        }
        try {
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append('\n');
                }
            }
            JSONObject root = new JSONObject(sb.toString());
            String wsName = root.optString("workspaceName", "Notion");

            List<NotionSource> sources = new ArrayList<>();
            JSONArray sArr = root.optJSONArray("sources");
            if (sArr != null) {
                for (int i = 0; i < sArr.length(); i++) {
                    JSONObject o = sArr.getJSONObject(i);
                    sources.add(new NotionSource(
                            o.optString("sourceKey"),
                            o.optString("dataSourceId"),
                            o.optString("databaseId"),
                            o.optString("viewId"),
                            o.optString("viewType"),
                            o.optString("displayName"),
                            o.optString("shortLabel"),
                            o.optString("iconEmoji"),
                            o.optString("datePropId"),
                            o.optString("datePropName"),
                            o.optString("datePropType", "date"),
                            o.optString("endDatePropName"),
                            o.optString("filterJson"),
                            o.optInt("defaultColorIndex", i),
                            o.optBoolean("customAdded", false)
                    ));
                }
            }

            List<NotionDatabaseMeta> dbs = new ArrayList<>();
            JSONArray dbArr = root.optJSONArray("databases");
            if (dbArr != null) {
                for (int i = 0; i < dbArr.length(); i++) {
                    JSONObject d = dbArr.getJSONObject(i);
                    List<DatePropOption> dps = new ArrayList<>();
                    JSONArray dpArr = d.optJSONArray("dateProps");
                    if (dpArr != null) {
                        for (int j = 0; j < dpArr.length(); j++) {
                            JSONObject p = dpArr.getJSONObject(j);
                            dps.add(new DatePropOption(p.optString("id"), p.optString("name"), p.optString("type", "date")));
                        }
                    }
                    List<ViewOption> vws = new ArrayList<>();
                    JSONArray vwArr = d.optJSONArray("views");
                    if (vwArr != null) {
                        for (int j = 0; j < vwArr.length(); j++) {
                            JSONObject v = vwArr.getJSONObject(j);
                            vws.add(new ViewOption(
                                    v.optString("id"),
                                    v.optString("name"),
                                    v.optString("type"),
                                    v.optString("datePropId"),
                                    v.optString("endDatePropId"),
                                    v.optString("filterJson")
                            ));
                        }
                    }
                    dbs.add(new NotionDatabaseMeta(
                            d.optString("dataSourceId"),
                            d.optString("databaseId"),
                            d.optString("title"),
                            d.optString("iconEmoji"),
                            dps,
                            vws
                    ));
                }
            }

            List<NotionCachedEvent> events = new ArrayList<>();
            JSONArray eArr = root.optJSONArray("events");
            if (eArr != null) {
                for (int i = 0; i < eArr.length(); i++) {
                    JSONObject e = eArr.getJSONObject(i);
                    events.add(new NotionCachedEvent(
                            e.optString("sourceKey"),
                            e.optString("pageId"),
                            e.optString("title"),
                            e.optString("statusLabel"),
                            e.optString("notionUrl"),
                            e.optBoolean("allDay"),
                            e.optLong("startEpochDay"),
                            e.optLong("endEpochDay"),
                            e.optLong("beginMillis"),
                            e.optLong("endMillis"),
                            e.optBoolean("hasExplicitEnd", false)
                    ));
                }
            }

            CachedWorkspace parsed = new CachedWorkspace(wsName, sources, dbs, events);
            cachedWorkspace = parsed;
            cachedFileMtime = mtime;
            return parsed;
        } catch (Exception e) {
            return null;
        }
    }

    private static synchronized void saveCache(Context context, CachedWorkspace ws) {
        try {
            JSONObject root = new JSONObject();
            root.put("workspaceName", ws.workspaceName);

            JSONArray sArr = new JSONArray();
            for (NotionSource s : ws.sources) {
                JSONObject o = new JSONObject();
                o.put("sourceKey", s.sourceKey);
                o.put("dataSourceId", s.dataSourceId);
                o.put("databaseId", s.databaseId);
                o.put("viewId", s.viewId);
                o.put("viewType", s.viewType);
                o.put("displayName", s.displayName);
                o.put("shortLabel", s.shortLabel);
                o.put("iconEmoji", s.iconEmoji);
                o.put("datePropId", s.datePropId);
                o.put("datePropName", s.datePropName);
                o.put("datePropType", s.datePropType);
                o.put("endDatePropName", s.endDatePropName);
                o.put("filterJson", s.filterJson);
                o.put("defaultColorIndex", s.defaultColorIndex);
                o.put("customAdded", s.customAdded);
                sArr.put(o);
            }
            root.put("sources", sArr);

            JSONArray dbArr = new JSONArray();
            for (NotionDatabaseMeta d : ws.databases) {
                JSONObject o = new JSONObject();
                o.put("dataSourceId", d.dataSourceId);
                o.put("databaseId", d.databaseId);
                o.put("title", d.title);
                o.put("iconEmoji", d.iconEmoji);
                JSONArray dpArr = new JSONArray();
                for (DatePropOption p : d.dateProps) {
                    JSONObject po = new JSONObject();
                    po.put("id", p.id);
                    po.put("name", p.name);
                    po.put("type", p.type);
                    dpArr.put(po);
                }
                o.put("dateProps", dpArr);
                JSONArray vwArr = new JSONArray();
                for (ViewOption v : d.views) {
                    JSONObject vo = new JSONObject();
                    vo.put("id", v.id);
                    vo.put("name", v.name);
                    vo.put("type", v.type);
                    vo.put("datePropId", v.datePropId);
                    vo.put("endDatePropId", v.endDatePropId);
                    vo.put("filterJson", v.filterJson);
                    vwArr.put(vo);
                }
                o.put("views", vwArr);
                dbArr.put(o);
            }
            root.put("databases", dbArr);

            JSONArray eArr = new JSONArray();
            for (NotionCachedEvent e : ws.events) {
                JSONObject o = new JSONObject();
                o.put("sourceKey", e.sourceKey);
                o.put("pageId", e.pageId);
                o.put("title", e.title);
                o.put("statusLabel", e.statusLabel);
                o.put("notionUrl", e.notionUrl);
                o.put("allDay", e.allDay);
                o.put("startEpochDay", e.startEpochDay);
                o.put("endEpochDay", e.endEpochDay);
                o.put("beginMillis", e.beginMillis);
                o.put("endMillis", e.endMillis);
                o.put("hasExplicitEnd", e.hasExplicitEnd);
                eArr.put(o);
            }
            root.put("events", eArr);

            File tmp = new File(context.getFilesDir(), CACHE_FILE_NAME + ".tmp");
            File target = new File(context.getFilesDir(), CACHE_FILE_NAME);
            try (FileOutputStream fos = new FileOutputStream(tmp)) {
                fos.write(root.toString().getBytes(StandardCharsets.UTF_8));
            }
            if (tmp.renameTo(target)) {
                cachedWorkspace = ws;
                cachedFileMtime = target.lastModified();
            } else {
                try (FileOutputStream fos = new FileOutputStream(target)) {
                    fos.write(root.toString().getBytes(StandardCharsets.UTF_8));
                }
                cachedWorkspace = ws;
                cachedFileMtime = target.lastModified();
            }
        } catch (Exception ignored) {
        }
    }

    private static List<JSONObject> fetchAllDataSources(String token) throws Exception {
        List<JSONObject> list = new ArrayList<>();
        String nextCursor = null;
        for (int page = 0; page < 5; page++) {
            JSONObject body = new JSONObject();
            JSONObject filter = new JSONObject();
            filter.put("property", "object");
            filter.put("value", "data_source");
            body.put("filter", filter);
            body.put("page_size", 100);
            if (!TextUtils.isEmpty(nextCursor)) {
                body.put("start_cursor", nextCursor);
            }

            HttpResult res = requestNotion(token, "POST", "/search", body.toString());
            if (res.code != 200) break;

            JSONObject json = new JSONObject(res.body);
            JSONArray results = json.optJSONArray("results");
            if (results != null) {
                for (int i = 0; i < results.length(); i++) {
                    JSONObject item = results.optJSONObject(i);
                    if (item != null && "data_source".equals(item.optString("object"))) {
                        list.add(item);
                    }
                }
            }
            if (!json.optBoolean("has_more", false)) break;
            nextCursor = json.optString("next_cursor", "");
            if (TextUtils.isEmpty(nextCursor) || "null".equals(nextCursor)) break;
        }
        return list;
    }

    private static List<ViewOption> fetchViewsForDataSource(String token, String dsId, String dbId) {
        List<ViewOption> out = new ArrayList<>();
        try {
            HttpResult listRes = requestNotion(token, "GET", "/views?data_source_id=" + dsId + "&page_size=50", null);
            if ((listRes.code != 200 || countResults(listRes.body) == 0) && !TextUtils.isEmpty(dbId)) {
                listRes = requestNotion(token, "GET", "/views?database_id=" + dbId + "&page_size=50", null);
            }
            if (listRes.code != 200) return out;

            JSONObject listJson = new JSONObject(listRes.body);
            JSONArray stubs = listJson.optJSONArray("results");
            if (stubs == null) return out;

            Map<String, ViewOption> dedup = new LinkedHashMap<>();
            int limit = Math.min(stubs.length(), 20);
            for (int i = 0; i < limit; i++) {
                JSONObject stub = stubs.optJSONObject(i);
                if (stub == null) continue;
                String viewId = stub.optString("id", "");
                if (viewId.isEmpty() || dedup.containsKey(viewId)) continue;

                HttpResult vRes = requestNotion(token, "GET", "/views/" + viewId, null);
                if (vRes.code != 200) continue;

                JSONObject vObj = new JSONObject(vRes.body);
                String vDsId = vObj.optString("data_source_id", "");
                if (!vDsId.isEmpty() && !"null".equals(vDsId) && !normalizeUuid(vDsId).equals(normalizeUuid(dsId))) {
                    continue;
                }
                String vType = vObj.optString("type", "");
                if ("dashboard".equals(vType) || "form".equals(vType) || "chart".equals(vType)) {
                    continue;
                }
                String vName = vObj.optString("name", "").trim();
                String datePropId = "";
                String endDatePropId = "";
                JSONObject cfg = vObj.optJSONObject("configuration");
                if (cfg != null) {
                    datePropId = cfg.optString("date_property_id", "");
                    if ("null".equals(datePropId)) datePropId = "";
                    endDatePropId = cfg.optString("end_date_property_id", "");
                    if ("null".equals(endDatePropId)) endDatePropId = "";
                }
                JSONObject filterObj = vObj.optJSONObject("filter");
                String filterStr = filterObj != null ? filterObj.toString() : "";

                dedup.put(viewId, new ViewOption(viewId, vName, vType, datePropId, endDatePropId, filterStr));
            }
            out.addAll(dedup.values());
        } catch (Exception ignored) {
        }
        return out;
    }

    private static List<NotionCachedEvent> queryEventsForSources(
            String token, List<NotionSource> sources, Set<String> hiddenCalendars) {
        List<NotionCachedEvent> allEvents = new ArrayList<>();
        ZoneId zone = ZoneId.systemDefault();
        String windowStart = LocalDate.now(zone).minusDays(40).toString();
        String windowEnd = LocalDate.now(zone).plusDays(65).toString();

        for (NotionSource src : sources) {
            if (hiddenCalendars.contains(src.sourceKey)) continue;
            try {
                List<JSONObject> pages = querySingleSourcePages(token, src, windowStart, windowEnd);
                Set<String> seenPages = new HashSet<>();
                for (JSONObject page : pages) {
                    if (page.optBoolean("in_trash", false) || page.optBoolean("is_archived", false)) {
                        continue;
                    }
                    String pageId = page.optString("id", "");
                    if (pageId.isEmpty() || !seenPages.add(pageId)) continue;

                    NotionCachedEvent parsed = parsePageToEvent(page, src, zone);
                    if (parsed != null) {
                        allEvents.add(parsed);
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return allEvents;
    }

    private static List<JSONObject> querySingleSourcePages(
            String token, NotionSource src, String windowStart, String windowEnd) throws Exception {
        JSONObject dateWindowFilter = buildDateWindowFilter(src, windowStart, windowEnd);
        JSONObject combinedFilter = dateWindowFilter;
        boolean hasViewFilter = !TextUtils.isEmpty(src.filterJson);
        if (hasViewFilter) {
            try {
                JSONObject vf = new JSONObject(src.filterJson);
                JSONArray andArr = new JSONArray();
                andArr.put(vf);
                andArr.put(dateWindowFilter);
                combinedFilter = new JSONObject().put("and", andArr);
            } catch (Exception ignored) {
                hasViewFilter = false;
            }
        }

        List<JSONObject> out = executeDataSourceQuery(token, src.dataSourceId, combinedFilter);
        if (out == null && hasViewFilter) {
            // Fallback without view filter if beta/special filter operator returned HTTP 400
            out = executeDataSourceQuery(token, src.dataSourceId, dateWindowFilter);
        }
        return out != null ? out : Collections.emptyList();
    }

    private static JSONObject buildDateWindowFilter(NotionSource src, String windowStart, String windowEnd)
            throws Exception {
        JSONArray andArr = new JSONArray();
        if ("created_time".equals(src.datePropType) || "last_edited_time".equals(src.datePropType)) {
            JSONObject f1 = new JSONObject();
            f1.put("timestamp", src.datePropType);
            f1.put(src.datePropType, new JSONObject().put("on_or_after", windowStart));
            JSONObject f2 = new JSONObject();
            f2.put("timestamp", src.datePropType);
            f2.put(src.datePropType, new JSONObject().put("on_or_before", windowEnd));
            andArr.put(f1);
            andArr.put(f2);
        } else {
            JSONObject f1 = new JSONObject();
            f1.put("property", src.datePropName);
            f1.put("date", new JSONObject().put("on_or_after", windowStart));
            JSONObject f2 = new JSONObject();
            f2.put("property", src.datePropName);
            f2.put("date", new JSONObject().put("on_or_before", windowEnd));
            andArr.put(f1);
            andArr.put(f2);
        }
        return new JSONObject().put("and", andArr);
    }

    private static List<JSONObject> executeDataSourceQuery(
            String token, String dataSourceId, JSONObject filter) throws Exception {
        List<JSONObject> pages = new ArrayList<>();
        String cursor = null;
        for (int pageNum = 0; pageNum < 3; pageNum++) {
            JSONObject body = new JSONObject();
            if (filter != null) body.put("filter", filter);
            body.put("page_size", 100);
            body.put("result_type", "page");
            if (!TextUtils.isEmpty(cursor)) body.put("start_cursor", cursor);

            HttpResult res = requestNotion(token, "POST", "/data_sources/" + dataSourceId + "/query", body.toString());
            if (res.code == 400) {
                return null; // Caller may retry with simpler filter
            }
            if (res.code != 200) break;

            JSONObject json = new JSONObject(res.body);
            JSONArray results = json.optJSONArray("results");
            if (results != null) {
                for (int i = 0; i < results.length(); i++) {
                    JSONObject item = results.optJSONObject(i);
                    if (item != null && "page".equals(item.optString("object", "page"))) {
                        pages.add(item);
                    }
                }
            }
            if (!json.optBoolean("has_more", false)) break;
            cursor = json.optString("next_cursor", "");
            if (TextUtils.isEmpty(cursor) || "null".equals(cursor)) break;
        }
        return pages;
    }

    private static NotionCachedEvent parsePageToEvent(JSONObject page, NotionSource src, ZoneId zone) {
        JSONObject props = page.optJSONObject("properties");
        if (props == null) return null;

        String startStr = null;
        String endStr = null;

        if ("created_time".equals(src.datePropType)) {
            startStr = page.optString("created_time", "");
        } else if ("last_edited_time".equals(src.datePropType)) {
            startStr = page.optString("last_edited_time", "");
        } else {
            JSONObject dateProp = findPropertyObject(props, src.datePropName, src.datePropId);
            if (dateProp != null) {
                JSONObject dateVal = dateProp.optJSONObject("date");
                if (dateVal != null) {
                    startStr = optCleanString(dateVal, "start");
                    endStr = optCleanString(dateVal, "end");
                }
            }
            if (TextUtils.isEmpty(endStr) && !TextUtils.isEmpty(src.endDatePropName)) {
                JSONObject endProp = props.optJSONObject(src.endDatePropName);
                if (endProp != null && endProp.optJSONObject("date") != null) {
                    endStr = optCleanString(endProp.optJSONObject("date"), "start");
                }
            }
        }

        if (TextUtils.isEmpty(startStr)) return null;

        // Extract page title, optional emoji, and optional status badge
        String pageEmoji = extractEmoji(page.optJSONObject("icon"));
        String titleText = "";
        String statusText = "";

        Iterator<String> keys = props.keys();
        while (keys.hasNext()) {
            String k = keys.next();
            JSONObject p = props.optJSONObject(k);
            if (p == null) continue;
            String t = p.optString("type", "");
            if ("title".equals(t) && titleText.isEmpty()) {
                titleText = extractPlainText(p.optJSONArray("title")).trim();
            } else if ("status".equals(t) && statusText.isEmpty()) {
                JSONObject st = p.optJSONObject("status");
                if (st != null) statusText = optCleanString(st, "name");
            }
        }
        if (!pageEmoji.isEmpty() && !titleText.startsWith(pageEmoji)) {
            titleText = titleText.isEmpty() ? pageEmoji : (pageEmoji + " " + titleText);
        }

        String url = optCleanString(page, "url");

        boolean isAllDay = !startStr.contains("T") && startStr.length() >= 10;
        try {
            if (isAllDay) {
                LocalDate startDay = LocalDate.parse(startStr.substring(0, 10));
                LocalDate endDay = startDay;
                if (!TextUtils.isEmpty(endStr) && endStr.length() >= 10) {
                    LocalDate parsedEnd = LocalDate.parse(endStr.substring(0, 10));
                    if (!parsedEnd.isBefore(startDay)) endDay = parsedEnd;
                }
                return new NotionCachedEvent(
                        src.sourceKey, page.optString("id"), titleText, statusText, url,
                        true, startDay.toEpochDay(), endDay.toEpochDay(), 0L, 0L, false);
            } else {
                long beginMillis = parseIsoToMillis(startStr, zone);
                boolean hasExplicitEnd = !TextUtils.isEmpty(endStr);
                long endMillis;
                if (hasExplicitEnd) {
                    if (!endStr.contains("T") && endStr.length() >= 10) {
                        endMillis = LocalDate.parse(endStr.substring(0, 10))
                                .plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
                    } else {
                        endMillis = parseIsoToMillis(endStr, zone);
                    }
                    if (endMillis <= beginMillis) {
                        endMillis = beginMillis + 3_600_000L;
                    }
                } else {
                    endMillis = beginMillis + 3_600_000L;
                }
                LocalDate startDay = Instant.ofEpochMilli(beginMillis).atZone(zone).toLocalDate();
                return new NotionCachedEvent(
                        src.sourceKey, page.optString("id"), titleText, statusText, url,
                        false, startDay.toEpochDay(), startDay.toEpochDay(),
                        beginMillis, endMillis, hasExplicitEnd);
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static long parseIsoToMillis(String iso, ZoneId fallbackZone) {
        try {
            return OffsetDateTime.parse(iso).toInstant().toEpochMilli();
        } catch (Exception e1) {
            try {
                return Instant.parse(iso).toEpochMilli();
            } catch (Exception e2) {
                return LocalDateTime.parse(iso).atZone(fallbackZone).toInstant().toEpochMilli();
            }
        }
    }

    private static JSONObject findPropertyObject(JSONObject props, String propName, String propId) {
        if (!TextUtils.isEmpty(propName) && props.has(propName)) {
            return props.optJSONObject(propName);
        }
        String decodedTarget = decodePropId(propId);
        Iterator<String> keys = props.keys();
        while (keys.hasNext()) {
            String k = keys.next();
            JSONObject p = props.optJSONObject(k);
            if (p == null) continue;
            String id = p.optString("id", "");
            if (id.equals(propId) || decodePropId(id).equals(decodedTarget)) {
                return p;
            }
        }
        return null;
    }

    private static DatePropOption resolveDateProp(
            String propId,
            Map<String, DatePropOption> lookup,
            List<DatePropOption> primaryDateProps,
            List<DatePropOption> allDateCapableProps) {
        if (!TextUtils.isEmpty(propId)) {
            DatePropOption found = lookup.get(propId);
            if (found != null) return found;
            found = lookup.get(decodePropId(propId));
            if (found != null) return found;
        }
        if (!primaryDateProps.isEmpty()) return primaryDateProps.get(0);
        if (!allDateCapableProps.isEmpty()) return allDateCapableProps.get(0);
        return null;
    }

    private static boolean isGenericViewName(String viewName, String dbTitle) {
        if (TextUtils.isEmpty(viewName)) return true;
        String lower = viewName.toLowerCase();
        return lower.equals("calendar")
                || lower.equals("calendar view")
                || lower.equals("timeline")
                || lower.equals("default view")
                || viewName.equals("日曆")
                || viewName.equals("行事曆")
                || viewName.equals("時間軸")
                || viewName.equals(dbTitle);
    }

    private static String extractPlainText(JSONArray richTextArr) {
        if (richTextArr == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < richTextArr.length(); i++) {
            JSONObject rt = richTextArr.optJSONObject(i);
            if (rt != null) {
                sb.append(rt.optString("plain_text", ""));
            }
        }
        return sb.toString();
    }

    private static String extractEmoji(JSONObject iconObj) {
        if (iconObj == null) return "";
        if ("emoji".equals(iconObj.optString("type"))) {
            return optCleanString(iconObj, "emoji");
        }
        return "";
    }

    private static String optCleanString(JSONObject obj, String key) {
        if (obj == null || obj.isNull(key)) return "";
        String v = obj.optString(key, "");
        return "null".equals(v) ? "" : v;
    }

    private static String decodePropId(String id) {
        if (id == null) return "";
        try {
            return URLDecoder.decode(id, "UTF-8");
        } catch (Exception e) {
            return id;
        }
    }

    private static String normalizeUuid(String id) {
        return id == null ? "" : id.replace("-", "").toLowerCase();
    }

    private static int countResults(String body) {
        try {
            JSONArray a = new JSONObject(body).optJSONArray("results");
            return a != null ? a.length() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private static void postErr(SyncCallback cb, String msg) {
        if (cb != null) {
            MAIN_HANDLER.post(() -> cb.onError(msg));
        }
    }

    private static final class HttpResult {
        final int code;
        final String body;
        final String errorMsg;

        HttpResult(int code, String body, String errorMsg) {
            this.code = code;
            this.body = body;
            this.errorMsg = errorMsg;
        }
    }

    private static HttpResult requestNotion(String token, String method, String path, String jsonBody) {
        HttpResult primary = executeNotionHttp(token, method, path, jsonBody, NOTION_VERSION_PRIMARY);
        if (primary.code == 400 && primary.errorMsg.contains("Notion-Version")) {
            return executeNotionHttp(token, method, path, jsonBody, NOTION_VERSION_FALLBACK);
        }
        return primary;
    }

    private static HttpResult executeNotionHttp(
            String token, String method, String path, String jsonBody, String version) {
        for (int attempt = 0; attempt < 3; attempt++) {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(API_BASE + path).openConnection();
                conn.setRequestMethod(method);
                conn.setConnectTimeout(15_000);
                conn.setReadTimeout(20_000);
                conn.setUseCaches(false);
                conn.setRequestProperty("Authorization", "Bearer " + token);
                conn.setRequestProperty("Notion-Version", version);
                conn.setRequestProperty("Accept", "application/json");

                if (jsonBody != null) {
                    conn.setDoOutput(true);
                    conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                    byte[] payload = jsonBody.getBytes(StandardCharsets.UTF_8);
                    conn.getOutputStream().write(payload);
                }

                int code = conn.getResponseCode();
                if ((code == 429 || code == 503 || code == 529) && attempt < 2) {
                    int retrySec = 1 + attempt;
                    try {
                        String hdr = conn.getHeaderField("Retry-After");
                        if (hdr != null) retrySec = Math.min(5, Math.max(1, Integer.parseInt(hdr.trim())));
                    } catch (Exception ignored) {
                    }
                    conn.disconnect();
                    Thread.sleep(retrySec * 1000L);
                    continue;
                }

                InputStream stream = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
                String respText = "";
                if (stream != null) {
                    StringBuilder sb = new StringBuilder();
                    try (BufferedReader r = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = r.readLine()) != null) {
                            sb.append(line).append('\n');
                        }
                    }
                    respText = sb.toString();
                }

                String errMsg = "";
                if (code >= 400 && !respText.isEmpty()) {
                    try {
                        errMsg = new JSONObject(respText).optString("message", respText);
                    } catch (Exception ignored) {
                        errMsg = respText;
                    }
                }
                return new HttpResult(code, respText, errMsg);
            } catch (Exception e) {
                if (attempt == 2) {
                    return new HttpResult(-1, "", e.getMessage() != null ? e.getMessage() : "網路連線異常");
                }
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
        return new HttpResult(-1, "", "網路連線逾時");
    }
}
