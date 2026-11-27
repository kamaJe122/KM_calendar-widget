package io.github.kamaje122.calwidget;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Bulletproof Notion Calendar / Database & Views sync manager:
 * - Multi-pronged discovery (2026-03-11 data_source search + page parent/child_database crawl + 2022-06-28 fallback)
 * - Manual database URL / ID addition
 * - 4-tier query fallback (without Wiki-only result_type bug; supports status group filters, "me" person resolution,
 *   date, formula date, rollup date, created_time, last_edited_time)
 * - Per-widget visibility & color customization
 */
public final class NotionManager {

    private static final String PREFS_NOTION = "notion_prefs";
    private static final String KEY_TOKEN = "notion_token";
    private static final String KEY_WORKSPACE_NAME = "workspace_name";
    private static final String KEY_OWNER_USER_ID = "owner_user_id";
    private static final String KEY_LAST_SYNC_MS = "last_sync_ms";
    private static final String KEY_COLOR_PREFIX = "color_";

    private static final String CACHE_FILE_NAME = "notion_cache_v2.json";
    private static final String API_BASE = "https://api.notion.com/v1";
    private static final String NOTION_VERSION_PRIMARY = "2026-03-11";
    private static final String NOTION_VERSION_FALLBACK = "2025-09-03";
    private static final String NOTION_VERSION_LEGACY = "2022-06-28";
    private static final String BETA_STATUS_GROUP = "status-group-filters-2026-10-06";

    private static final Pattern HEX32_PATTERN = Pattern.compile(
            "([0-9a-fA-F]{8}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{12})");

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
        public final String type; // "date", "formula", "rollup", "created_time", "last_edited_time"

        DatePropOption(String id, String name, String type) {
            this.id = id;
            this.name = name;
            this.type = type;
        }
    }

    public static final class ViewOption {
        public final String id;
        public final String name;
        public final String type;
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
        public final long startEpochDay;
        public final long endEpochDay;
        public final long beginMillis;
        public final long endMillis;
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
        public final String diagnosticNote;

        CachedWorkspace(String workspaceName, List<NotionSource> sources,
                        List<NotionDatabaseMeta> databases, List<NotionCachedEvent> events,
                        String diagnosticNote) {
            this.workspaceName = workspaceName;
            this.sources = sources;
            this.databases = databases;
            this.events = events;
            this.diagnosticNote = diagnosticNote == null ? "" : diagnosticNote;
        }

        public int countEventsForSource(String sourceKey) {
            int count = 0;
            for (NotionCachedEvent ev : events) {
                if (sourceKey.equals(ev.sourceKey)) count++;
            }
            return count;
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
     * Full workspace discovery + event sync.
     */
    public static void scanWorkspaceAndSyncAsync(Context context, SyncCallback callback) {
        final Context appContext = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            if (!SYNC_IN_PROGRESS.compareAndSet(false, true)) {
                postErr(callback, "正在同步 Notion 中，請稍候…");
                return;
            }
            try {
                String token = getToken(appContext);
                if (token.isEmpty()) {
                    postErr(callback, "請先貼上 Notion Integration Secret Token");
                    return;
                }

                // 1. Verify token & resolve workspace name + owner user UUID (for "me" filters)
                HttpResult meRes = requestNotion(token, "GET", "/users/me", null, null);
                if (meRes.code == 401) {
                    postErr(callback, "Notion Token 無效 (HTTP 401)，請確認複製了正確的 Internal Integration Secret");
                    return;
                }
                if (meRes.code != 200) {
                    postErr(callback, "連線 Notion 失敗 (HTTP " + meRes.code + ")：" + meRes.errorMsg);
                    return;
                }

                JSONObject meJson = new JSONObject(meRes.body);
                String workspaceName = "Notion";
                String ownerUserId = "";
                JSONObject botObj = meJson.optJSONObject("bot");
                if (botObj != null) {
                    if (!TextUtils.isEmpty(botObj.optString("workspace_name"))) {
                        workspaceName = botObj.optString("workspace_name");
                    }
                    JSONObject owner = botObj.optJSONObject("owner");
                    if (owner != null && "user".equals(owner.optString("type"))) {
                        JSONObject u = owner.optJSONObject("user");
                        if (u != null) ownerUserId = optCleanString(u, "id");
                    }
                } else if (!TextUtils.isEmpty(meJson.optString("name"))) {
                    workspaceName = meJson.optString("name");
                }
                if (ownerUserId.isEmpty()) {
                    ownerUserId = discoverFirstPersonUserId(token);
                }

                CachedWorkspace oldCache = loadCache(appContext);
                List<NotionSource> preservedCustom = new ArrayList<>();
                if (oldCache != null) {
                    for (NotionSource s : oldCache.sources) {
                        if (s.customAdded) preservedCustom.add(s);
                    }
                }

                // 2. Multi-pronged discovery of all accessible data_sources & linked views
                Map<String, JSONObject> dbContainerCache = new HashMap<>();
                List<JSONObject> rawDataSources = discoverAllDataSources(token, dbContainerCache);

                List<NotionDatabaseMeta> discoveredDbs = new ArrayList<>();
                List<NotionSource> discoveredSources = new ArrayList<>();
                int colorCounter = 0;

                for (JSONObject ds : rawDataSources) {
                    if (ds.optBoolean("in_trash", false) || ds.optBoolean("is_archived", false)
                            || ds.optBoolean("archived", false)) {
                        continue;
                    }
                    String dsId = optCleanString(ds, "id");
                    if (dsId.isEmpty()) continue;

                    BuildDbResult built = buildMetaAndSourcesForDataSource(
                            token, ds, dbContainerCache, colorCounter);
                    if (built != null) {
                        discoveredDbs.add(built.meta);
                        discoveredSources.addAll(built.sources);
                        colorCounter = built.nextColorCounter;
                    }
                }

                // Re-attach preserved custom/URL-added sources
                Set<String> existingKeys = new HashSet<>();
                for (NotionSource s : discoveredSources) existingKeys.add(s.sourceKey);
                for (NotionSource custom : preservedCustom) {
                    if (!existingKeys.contains(custom.sourceKey)) {
                        discoveredSources.add(custom);
                        existingKeys.add(custom.sourceKey);
                    }
                }

                // 3. Query events for all NotionSources visible on at least one widget
                QueryBatchResult qRes = queryEventsForSources(appContext, token, ownerUserId, discoveredSources);

                CachedWorkspace newWs = new CachedWorkspace(
                        workspaceName, discoveredSources, discoveredDbs, qRes.events, qRes.diagnosticNote);
                saveCache(appContext, newWs);
                prefs(appContext).edit()
                        .putString(KEY_WORKSPACE_NAME, workspaceName)
                        .putString(KEY_OWNER_USER_ID, ownerUserId)
                        .putLong(KEY_LAST_SYNC_MS, System.currentTimeMillis())
                        .apply();

                CalendarWidgetProvider.updateAll(appContext);

                final String finalWsName = workspaceName;
                final int srcCount = discoveredSources.size();
                final int evCount = qRes.events.size();
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
     * Directly adds a Notion database by pasted Notion URL or 32-char ID (bypasses search index lag).
     */
    public static void addDatabaseByUrlOrIdAsync(Context context, String rawInput, SyncCallback callback) {
        final Context appContext = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            String token = getToken(appContext);
            if (token.isEmpty()) {
                postErr(callback, "請先貼上 Notion Token 並儲存");
                return;
            }
            String extractedId = extractNotionUuid(rawInput);
            if (extractedId.isEmpty()) {
                postErr(callback, "無法從輸入內容辨識出 32 碼 Notion 資料庫網址或 ID");
                return;
            }

            if (!SYNC_IN_PROGRESS.compareAndSet(false, true)) {
                postErr(callback, "正在同步 Notion 中，請稍候…");
                return;
            }
            try {
                Map<String, JSONObject> dbCache = new HashMap<>();
                List<JSONObject> targetDataSources = new ArrayList<>();

                // Try as database_id first (most Notion URLs contain database_id before ?v=...)
                HttpResult dbRes = requestNotion(token, "GET", "/databases/" + extractedId, null, null);
                if (dbRes.code == 200) {
                    JSONObject dbObj = new JSONObject(dbRes.body);
                    dbCache.put(formatUuid(extractedId), dbObj);
                    dbCache.put(extractedId, dbObj);
                    JSONArray dsStubs = dbObj.optJSONArray("data_sources");
                    if (dsStubs != null && dsStubs.length() > 0) {
                        for (int i = 0; i < dsStubs.length(); i++) {
                            String dsId = dsStubs.getJSONObject(i).optString("id", "");
                            if (!dsId.isEmpty()) {
                                HttpResult dsRes = requestNotion(token, "GET", "/data_sources/" + dsId, null, null);
                                if (dsRes.code == 200) {
                                    targetDataSources.add(new JSONObject(dsRes.body));
                                }
                            }
                        }
                    }
                } else if (dbRes.code == 403) {
                    postErr(callback, "權限不足 (HTTP 403)：請到 Notion Integration 設定確認已開啟「Read content（讀取內容）」權限！");
                    return;
                }

                // Also try directly as data_source_id
                if (targetDataSources.isEmpty()) {
                    HttpResult dsRes = requestNotion(token, "GET", "/data_sources/" + extractedId, null, null);
                    if (dsRes.code == 200) {
                        targetDataSources.add(new JSONObject(dsRes.body));
                    } else if (dbRes.code == 404) {
                        postErr(callback, "Notion 回傳找不到此資料庫 (HTTP 404)！\n這代表該「原始資料庫」尚未授權給你的 Integration。\n請在 Notion 打開該資料庫本體 → 右上角「•••」→「Connections」加入你的 Integration，或到 Notion Integration 的「Access」分頁勾選它！");
                        return;
                    } else {
                        postErr(callback, "讀取資料庫失敗 (HTTP " + dbRes.code + ")：" + dbRes.errorMsg);
                        return;
                    }
                }

                CachedWorkspace ws = loadCache(appContext);
                String wsName = ws != null ? ws.workspaceName : prefs(appContext).getString(KEY_WORKSPACE_NAME, "Notion");
                List<NotionDatabaseMeta> dbs = ws != null ? new ArrayList<>(ws.databases) : new ArrayList<>();
                List<NotionSource> sources = ws != null ? new ArrayList<>(ws.sources) : new ArrayList<>();
                Set<String> existingKeys = new HashSet<>();
                for (NotionSource s : sources) existingKeys.add(s.sourceKey);
                Set<String> existingDsIds = new HashSet<>();
                for (NotionDatabaseMeta m : dbs) existingDsIds.add(normalizeUuid(m.dataSourceId));

                int addedCount = 0;
                int colorCounter = sources.size();
                for (JSONObject ds : targetDataSources) {
                    BuildDbResult built = buildMetaAndSourcesForDataSource(token, ds, dbCache, colorCounter);
                    if (built == null) continue;
                    colorCounter = built.nextColorCounter;
                    if (!existingDsIds.contains(normalizeUuid(built.meta.dataSourceId))) {
                        dbs.add(built.meta);
                        existingDsIds.add(normalizeUuid(built.meta.dataSourceId));
                    }
                    for (NotionSource s : built.sources) {
                        NotionSource asCustom = new NotionSource(
                                s.sourceKey, s.dataSourceId, s.databaseId, s.viewId, s.viewType,
                                s.displayName, s.shortLabel, s.iconEmoji, s.datePropId, s.datePropName,
                                s.datePropType, s.endDatePropName, s.filterJson, s.defaultColorIndex, true);
                        if (existingKeys.add(asCustom.sourceKey)) {
                            sources.add(asCustom);
                            addedCount++;
                        }
                    }
                }

                if (addedCount == 0 && existingKeys.isEmpty()) {
                    postErr(callback, "此資料庫中沒有找到可用的日期欄位");
                    return;
                }

                String ownerUserId = prefs(appContext).getString(KEY_OWNER_USER_ID, "");
                QueryBatchResult qRes = queryEventsForSources(appContext, token, ownerUserId, sources);
                CachedWorkspace updated = new CachedWorkspace(wsName, sources, dbs, qRes.events, qRes.diagnosticNote);
                saveCache(appContext, updated);
                prefs(appContext).edit().putLong(KEY_LAST_SYNC_MS, System.currentTimeMillis()).apply();
                CalendarWidgetProvider.updateAll(appContext);

                final int totalSrc = sources.size();
                final int totalEv = qRes.events.size();
                if (callback != null) {
                    MAIN_HANDLER.post(() -> callback.onSuccess(wsName, totalSrc, totalEv));
                }
            } catch (Exception e) {
                postErr(callback, "加入資料庫失敗：" + e.getMessage());
            } finally {
                SYNC_IN_PROGRESS.set(false);
            }
        });
    }

    private static final class BuildDbResult {
        final NotionDatabaseMeta meta;
        final List<NotionSource> sources;
        final int nextColorCounter;

        BuildDbResult(NotionDatabaseMeta meta, List<NotionSource> sources, int nextColorCounter) {
            this.meta = meta;
            this.sources = sources;
            this.nextColorCounter = nextColorCounter;
        }
    }

    private static BuildDbResult buildMetaAndSourcesForDataSource(
            String token,
            JSONObject ds,
            Map<String, JSONObject> dbContainerCache,
            int colorCounter) throws Exception {

        String dsId = optCleanString(ds, "id");
        if (dsId.isEmpty()) return null;

        String dbId = "";
        JSONObject parent = ds.optJSONObject("parent");
        if (parent != null) {
            dbId = optCleanString(parent, "database_id");
        }

        String dsTitle = extractPlainText(ds.optJSONArray("title")).trim();
        String iconEmoji = extractEmoji(ds.optJSONObject("icon"));

        if (!dbId.isEmpty() && (dsTitle.isEmpty() || "Default".equalsIgnoreCase(dsTitle) || iconEmoji.isEmpty())) {
            JSONObject dbObj = dbContainerCache.get(dbId);
            if (dbObj == null) {
                HttpResult dbRes = requestNotion(token, "GET", "/databases/" + dbId, null, null);
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
            HttpResult fullDsRes = requestNotion(token, "GET", "/data_sources/" + dsId, null, null);
            if (fullDsRes.code == 200) {
                propsObj = new JSONObject(fullDsRes.body).optJSONObject("properties");
            }
        }
        if (propsObj == null) return null;

        List<DatePropOption> primaryDateProps = new ArrayList<>();
        List<DatePropOption> formulaOrRollupProps = new ArrayList<>();
        List<DatePropOption> allDateCapableProps = new ArrayList<>();
        Map<String, DatePropOption> propLookup = new HashMap<>();

        Iterator<String> keys = propsObj.keys();
        while (keys.hasNext()) {
            String propName = keys.next();
            JSONObject p = propsObj.optJSONObject(propName);
            if (p == null) continue;
            String pType = p.optString("type", "");
            String pId = p.optString("id", propName);
            if ("date".equals(pType) || "formula".equals(pType) || "rollup".equals(pType)
                    || "created_time".equals(pType) || "last_edited_time".equals(pType)) {
                DatePropOption opt = new DatePropOption(pId, propName, pType);
                allDateCapableProps.add(opt);
                propLookup.put(pId, opt);
                propLookup.put(decodePropId(pId), opt);
                propLookup.put(propName, opt);
                if ("date".equals(pType)) {
                    primaryDateProps.add(opt);
                } else if ("formula".equals(pType) || "rollup".equals(pType)) {
                    formulaOrRollupProps.add(opt);
                }
            }
        }

        if (allDateCapableProps.isEmpty()) return null;

        List<ViewOption> allViews = fetchViewsForDataSource(token, dsId, dbId);
        List<ViewOption> calOrTimelineViews = new ArrayList<>();
        for (ViewOption v : allViews) {
            if ("calendar".equals(v.type) || "timeline".equals(v.type)) {
                calOrTimelineViews.add(v);
            }
        }

        // Accept if database has at least one `date` property OR an explicit calendar/timeline view
        if (primaryDateProps.isEmpty() && calOrTimelineViews.isEmpty()) {
            return null;
        }

        NotionDatabaseMeta meta = new NotionDatabaseMeta(
                dsId, dbId, dsTitle, iconEmoji, allDateCapableProps, allViews);
        List<NotionSource> sources = new ArrayList<>();

        if (!calOrTimelineViews.isEmpty()) {
            boolean multipleCalViews = calOrTimelineViews.size() > 1;
            for (int idx = 0; idx < calOrTimelineViews.size(); idx++) {
                ViewOption v = calOrTimelineViews.get(idx);
                DatePropOption resolvedStart = resolveDateProp(
                        v.datePropId, propLookup, primaryDateProps, formulaOrRollupProps, allDateCapableProps);
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

                sources.add(new NotionSource(
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
                sources.add(new NotionSource(
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

        return new BuildDbResult(meta, sources, colorCounter);
    }

    /**
     * Multi-pronged discovery:
     * 1. POST /search (2026-03-11, filter=data_source)
     * 2. POST /search (2026-03-11, filter=page) -> inspect parent data_source_id/database_id
     *    + crawl child_database blocks on shared dashboard/parent pages!
     * 3. Fallback POST /search (2022-06-28, filter=database) -> GET /databases/{id}
     */
    private static List<JSONObject> discoverAllDataSources(
            String token, Map<String, JSONObject> dbContainerCache) throws Exception {
        LinkedHashMap<String, JSONObject> byDsId = new LinkedHashMap<>();
        Set<String> extraDbIdsToResolve = new HashSet<>();
        Set<String> extraDsIdsToResolve = new HashSet<>();

        // Prong 1: Standard 2026-03-11 data_source search
        String nextCursor = null;
        for (int page = 0; page < 5; page++) {
            JSONObject body = new JSONObject();
            body.put("filter", new JSONObject().put("property", "object").put("value", "data_source"));
            body.put("page_size", 100);
            if (!TextUtils.isEmpty(nextCursor)) body.put("start_cursor", nextCursor);

            HttpResult res = requestNotion(token, "POST", "/search", body.toString(), null);
            if (res.code != 200) break;

            JSONObject json = new JSONObject(res.body);
            JSONArray results = json.optJSONArray("results");
            if (results != null) {
                for (int i = 0; i < results.length(); i++) {
                    JSONObject item = results.optJSONObject(i);
                    if (item != null && "data_source".equals(item.optString("object"))) {
                        String id = optCleanString(item, "id");
                        if (!id.isEmpty()) byDsId.put(normalizeUuid(id), item);
                    }
                }
            }
            if (!json.optBoolean("has_more", false)) break;
            nextCursor = optCleanString(json, "next_cursor");
            if (nextCursor.isEmpty()) break;
        }

        // Prong 2: Page search -> catch parent data_sources + child_database blocks inside shared parent pages
        HttpResult pageSearch = requestNotion(token, "POST", "/search",
                new JSONObject()
                        .put("filter", new JSONObject().put("property", "object").put("value", "page"))
                        .put("page_size", 100)
                        .toString(),
                null);
        if (pageSearch.code == 200) {
            JSONArray pages = new JSONObject(pageSearch.body).optJSONArray("results");
            if (pages != null) {
                List<String> standalonePagesToCrawl = new ArrayList<>();
                for (int i = 0; i < pages.length(); i++) {
                    JSONObject p = pages.optJSONObject(i);
                    if (p == null || p.optBoolean("in_trash", false) || p.optBoolean("archived", false)) continue;
                    JSONObject parent = p.optJSONObject("parent");
                    if (parent == null) continue;
                    String pType = parent.optString("type", "");
                    String pDsId = optCleanString(parent, "data_source_id");
                    String pDbId = optCleanString(parent, "database_id");
                    if (!pDsId.isEmpty() && !byDsId.containsKey(normalizeUuid(pDsId))) {
                        extraDsIdsToResolve.add(pDsId);
                    } else if (!pDbId.isEmpty()) {
                        extraDbIdsToResolve.add(pDbId);
                    } else if ("workspace".equals(pType) || "page_id".equals(pType)) {
                        if (standalonePagesToCrawl.size() < 12) {
                            standalonePagesToCrawl.add(optCleanString(p, "id"));
                        }
                    }
                }

                // Crawl top-level shared pages for inline/linked child_database blocks
                for (String pageId : standalonePagesToCrawl) {
                    if (pageId.isEmpty()) continue;
                    HttpResult blkRes = requestNotion(token, "GET",
                            "/blocks/" + pageId + "/children?page_size=100", null, null);
                    if (blkRes.code != 200) continue;
                    JSONArray blocks = new JSONObject(blkRes.body).optJSONArray("results");
                    if (blocks == null) continue;
                    for (int b = 0; b < blocks.length(); b++) {
                        JSONObject blk = blocks.optJSONObject(b);
                        if (blk == null) continue;
                        if ("child_database".equals(blk.optString("type"))) {
                            String childDbId = optCleanString(blk, "id");
                            if (!childDbId.isEmpty()) {
                                extraDbIdsToResolve.add(childDbId);
                                // Also check views on linked database blocks!
                                HttpResult vRes = requestNotion(token, "GET",
                                        "/views?database_id=" + childDbId + "&page_size=20", null, null);
                                if (vRes.code == 200) {
                                    JSONArray vStubs = new JSONObject(vRes.body).optJSONArray("results");
                                    if (vStubs != null) {
                                        for (int vi = 0; vi < Math.min(vStubs.length(), 10); vi++) {
                                            String vid = vStubs.getJSONObject(vi).optString("id", "");
                                            if (!vid.isEmpty()) {
                                                HttpResult vd = requestNotion(token, "GET", "/views/" + vid, null, null);
                                                if (vd.code == 200) {
                                                    String linkedDs = optCleanString(new JSONObject(vd.body), "data_source_id");
                                                    if (!linkedDs.isEmpty() && !byDsId.containsKey(normalizeUuid(linkedDs))) {
                                                        extraDsIdsToResolve.add(linkedDs);
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Prong 3: Legacy 2022-06-28 database search fallback if nothing found yet
        if (byDsId.isEmpty() && extraDsIdsToResolve.isEmpty() && extraDbIdsToResolve.isEmpty()) {
            HttpResult legRes = executeNotionHttp(token, "POST", "/search",
                    new JSONObject()
                            .put("filter", new JSONObject().put("property", "object").put("value", "database"))
                            .put("page_size", 100)
                            .toString(),
                    NOTION_VERSION_LEGACY, null);
            if (legRes.code == 200) {
                JSONArray dbs = new JSONObject(legRes.body).optJSONArray("results");
                if (dbs != null) {
                    for (int i = 0; i < dbs.length(); i++) {
                        String dbId = optCleanString(dbs.optJSONObject(i), "id");
                        if (!dbId.isEmpty()) extraDbIdsToResolve.add(dbId);
                    }
                }
            }
        }

        for (String dbId : extraDbIdsToResolve) {
            HttpResult dbRes = requestNotion(token, "GET", "/databases/" + dbId, null, null);
            if (dbRes.code == 200) {
                JSONObject dbObj = new JSONObject(dbRes.body);
                dbContainerCache.put(dbId, dbObj);
                JSONArray dsArr = dbObj.optJSONArray("data_sources");
                if (dsArr != null) {
                    for (int i = 0; i < dsArr.length(); i++) {
                        String dsId = optCleanString(dsArr.optJSONObject(i), "id");
                        if (!dsId.isEmpty() && !byDsId.containsKey(normalizeUuid(dsId))) {
                            extraDsIdsToResolve.add(dsId);
                        }
                    }
                }
            }
        }

        for (String dsId : extraDsIdsToResolve) {
            if (byDsId.containsKey(normalizeUuid(dsId))) continue;
            HttpResult dsRes = requestNotion(token, "GET", "/data_sources/" + dsId, null, null);
            if (dsRes.code == 200) {
                byDsId.put(normalizeUuid(dsId), new JSONObject(dsRes.body));
            }
        }

        return new ArrayList<>(byDsId.values());
    }

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
            saveCache(appContext, new CachedWorkspace(
                    ws.workspaceName, updatedSources, ws.databases, ws.events, ws.diagnosticNote));
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
        saveCache(context, new CachedWorkspace(
                ws.workspaceName, keptSources, ws.databases, keptEvents, ws.diagnosticNote));
        CalendarWidgetProvider.updateAll(context);
    }

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
                String ownerUserId = prefs(appContext).getString(KEY_OWNER_USER_ID, "");
                QueryBatchResult qRes = queryEventsForSources(appContext, token, ownerUserId, ws.sources);
                CachedWorkspace updated = new CachedWorkspace(
                        ws.workspaceName, ws.sources, ws.databases, qRes.events, qRes.diagnosticNote);
                saveCache(appContext, updated);
                prefs(appContext).edit().putLong(KEY_LAST_SYNC_MS, System.currentTimeMillis()).apply();
                CalendarWidgetProvider.updateAll(appContext);
                if (callback != null) {
                    final String wsName = ws.workspaceName;
                    final int sCount = ws.sources.size();
                    final int eCount = qRes.events.size();
                    MAIN_HANDLER.post(() -> callback.onSuccess(wsName, sCount, eCount));
                }
            } catch (Exception e) {
                postErr(callback, "同步 Notion 行程失敗：" + e.getMessage());
            } finally {
                SYNC_IN_PROGRESS.set(false);
            }
        });
    }

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
            ev.notionPageId = nev.pageId == null ? "" : nev.pageId;
            ev.sourceKey = nev.sourceKey;
            ev.notionUrl = nev.notionUrl;
            ev.allDay = nev.allDay;
            ev.startEpochDay = nev.startEpochDay;
            ev.endEpochDay = nev.endEpochDay;
            ev.hasExplicitEnd = nev.hasExplicitEnd;
            ev.calendarName = src.fullTitleWithIcon();
            ev.statusLabel = nev.statusLabel == null ? "" : nev.statusLabel;
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

    /**
     * Loads all Notion events intersecting [startDay, endDay] for the full Calendar App views.
     */
    public static List<CalendarEvent> loadAppEventsInRange(
            Context context,
            LocalDate startDay,
            LocalDate endDay,
            Set<String> hiddenCalendars) {
        if (!hasToken(context)) return Collections.emptyList();
        CachedWorkspace ws = loadCache(context);
        if (ws == null || ws.events.isEmpty()) return Collections.emptyList();

        Map<String, NotionSource> activeSources = new HashMap<>();
        Map<String, Integer> sourceColors = new HashMap<>();
        for (NotionSource s : ws.sources) {
            if (hiddenCalendars == null || !hiddenCalendars.contains(s.sourceKey)) {
                activeSources.put(s.sourceKey, s);
                sourceColors.put(s.sourceKey, getSourceColor(context, s));
            }
        }
        if (activeSources.isEmpty()) return Collections.emptyList();

        long minEpoch = startDay.toEpochDay();
        long maxEpoch = endDay.toEpochDay();
        String accountLabel = "Notion · " + ws.workspaceName;
        List<CalendarEvent> out = new ArrayList<>();

        for (NotionCachedEvent nev : ws.events) {
            NotionSource src = activeSources.get(nev.sourceKey);
            if (src == null) continue;
            if (nev.endEpochDay < minEpoch || nev.startEpochDay > maxEpoch) continue;

            CalendarEvent ce = new CalendarEvent();
            ce.eventId = -1L;
            ce.notionPageId = nev.pageId == null ? "" : nev.pageId;
            ce.sourceKey = nev.sourceKey;
            ce.title = TextUtils.isEmpty(nev.title) ? context.getString(R.string.untitled) : nev.title;
            ce.calendarName = src.fullTitleWithIcon();
            ce.accountName = accountLabel;
            ce.statusLabel = nev.statusLabel == null ? "" : nev.statusLabel;
            ce.notionUrl = nev.notionUrl == null ? "" : nev.notionUrl;
            ce.color = AppTheme.normalizePillColor(sourceColors.getOrDefault(nev.sourceKey, NOTION_PALETTE[0]));
            ce.allDay = nev.allDay;
            ce.startEpochDay = nev.startEpochDay;
            ce.endEpochDay = Math.max(nev.startEpochDay, nev.endEpochDay);
            ce.beginMillis = nev.beginMillis;
            ce.endMillis = nev.endMillis;
            ce.hasExplicitEnd = nev.hasExplicitEnd;
            out.add(ce);
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
            String diag = root.optString("diagnosticNote", "");

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

            CachedWorkspace parsed = new CachedWorkspace(wsName, sources, dbs, events, diag);
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
            root.put("diagnosticNote", ws.diagnosticNote);

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

    private static List<ViewOption> fetchViewsForDataSource(String token, String dsId, String dbId) {
        List<ViewOption> out = new ArrayList<>();
        try {
            HttpResult listRes = requestNotion(token, "GET",
                    "/views?data_source_id=" + dsId + "&page_size=50", null, BETA_STATUS_GROUP);
            if ((listRes.code != 200 || countResults(listRes.body) == 0) && !TextUtils.isEmpty(dbId)) {
                listRes = requestNotion(token, "GET",
                        "/views?database_id=" + dbId + "&page_size=50", null, BETA_STATUS_GROUP);
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
                String viewId = optCleanString(stub, "id");
                if (viewId.isEmpty() || dedup.containsKey(viewId)) continue;

                HttpResult vRes = requestNotion(token, "GET", "/views/" + viewId, null, BETA_STATUS_GROUP);
                if (vRes.code != 200) continue;

                JSONObject vObj = new JSONObject(vRes.body);
                String vDsId = optCleanString(vObj, "data_source_id");
                if (!vDsId.isEmpty() && !normalizeUuid(vDsId).equals(normalizeUuid(dsId))) {
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
                    datePropId = optCleanString(cfg, "date_property_id");
                    endDatePropId = optCleanString(cfg, "end_date_property_id");
                }
                JSONObject filterObj = vObj.optJSONObject("filter");
                String filterStr = (filterObj != null && filterObj.length() > 0) ? filterObj.toString() : "";

                dedup.put(viewId, new ViewOption(viewId, vName, vType, datePropId, endDatePropId, filterStr));
            }
            out.addAll(dedup.values());
        } catch (Exception ignored) {
        }
        return out;
    }

    private static final class QueryBatchResult {
        final List<NotionCachedEvent> events;
        final String diagnosticNote;

        QueryBatchResult(List<NotionCachedEvent> events, String diagnosticNote) {
            this.events = events;
            this.diagnosticNote = diagnosticNote;
        }
    }

    private static QueryBatchResult queryEventsForSources(
            Context context, String token, String ownerUserId, List<NotionSource> sources) {
        List<NotionCachedEvent> allEvents = new ArrayList<>();
        String lastWarning = "";
        ZoneId zone = ZoneId.systemDefault();
        LocalDate minDay = LocalDate.now(zone).minusDays(180);
        LocalDate maxDay = LocalDate.now(zone).plusDays(270);
        String windowStart = minDay.toString();
        String windowEnd = maxDay.toString();

        for (NotionSource src : sources) {
            if (CalendarWidgetProvider.isHiddenInAllProfiles(context, src.sourceKey)) {
                continue;
            }
            try {
                SingleQueryOutcome outcome = querySingleSourcePages(
                        token, ownerUserId, src, windowStart, windowEnd);
                if (!outcome.errorHint.isEmpty() && lastWarning.isEmpty()) {
                    lastWarning = outcome.errorHint;
                }
                Set<String> seenPages = new HashSet<>();
                for (JSONObject page : outcome.pages) {
                    if (page.optBoolean("in_trash", false)
                            || page.optBoolean("is_archived", false)
                            || page.optBoolean("archived", false)) {
                        continue;
                    }
                    String pageId = optCleanString(page, "id");
                    if (pageId.isEmpty() || !seenPages.add(pageId)) continue;

                    NotionCachedEvent parsed = parsePageToEvent(page, src, zone);
                    if (parsed == null) continue;
                    // Client-side window guard (especially for Tier 3/4 unfiltered fallback)
                    if (parsed.endEpochDay < minDay.toEpochDay() || parsed.startEpochDay > maxDay.toEpochDay()) {
                        continue;
                    }
                    allEvents.add(parsed);
                }
            } catch (Exception e) {
                if (lastWarning.isEmpty() && e.getMessage() != null) {
                    lastWarning = e.getMessage();
                }
            }
        }
        return new QueryBatchResult(allEvents, lastWarning);
    }

    private static final class SingleQueryOutcome {
        final List<JSONObject> pages;
        final String errorHint;

        SingleQueryOutcome(List<JSONObject> pages, String errorHint) {
            this.pages = pages;
            this.errorHint = errorHint;
        }
    }

    private static SingleQueryOutcome querySingleSourcePages(
            String token, String ownerUserId, NotionSource src, String windowStart, String windowEnd)
            throws Exception {

        boolean canServerFilterDate = "date".equals(src.datePropType)
                || "formula".equals(src.datePropType)
                || "created_time".equals(src.datePropType)
                || "last_edited_time".equals(src.datePropType);

        JSONArray dateBounds = canServerFilterDate ? buildDateBoundArray(src, windowStart, windowEnd) : null;

        // Tier 1: Sanitized View Filter + Date Window Filter (flat <= 2-level compound filter)
        if (!TextUtils.isEmpty(src.filterJson)) {
            JSONObject sanitizedVf = sanitizeViewFilter(new JSONObject(src.filterJson), ownerUserId);
            if (sanitizedVf != null && sanitizedVf.length() > 0) {
                JSONObject tier1Filter = mergeFiltersFlat(sanitizedVf, dateBounds);
                QueryAttempt t1 = executeDataSourceQuery(token, src.dataSourceId, tier1Filter, BETA_STATUS_GROUP);
                if (t1.ok) {
                    return new SingleQueryOutcome(t1.pages, "");
                }
                if (t1.httpCode == 403) {
                    return new SingleQueryOutcome(Collections.emptyList(),
                            "權限不足 (HTTP 403)：請確認 Notion Integration 已勾選「Read content（讀取內容）」！");
                }
            }
        }

        // Tier 2: Date Window Filter only
        if (dateBounds != null) {
            JSONObject tier2Filter = new JSONObject().put("and", dateBounds);
            QueryAttempt t2 = executeDataSourceQuery(token, src.dataSourceId, tier2Filter, null);
            if (t2.ok) {
                return new SingleQueryOutcome(t2.pages, "");
            }
            if (t2.httpCode == 403) {
                return new SingleQueryOutcome(Collections.emptyList(),
                        "權限不足 (HTTP 403)：請確認 Notion Integration 已勾選「Read content（讀取內容）」！");
            }
        }

        // Tier 3: Unfiltered /data_sources/{id}/query sorted by last_edited_time descending
        QueryAttempt t3 = executeDataSourceQuery(token, src.dataSourceId, null, null);
        if (t3.ok) {
            return new SingleQueryOutcome(t3.pages, "");
        }

        // Tier 4: Legacy 2022-06-28 /databases/{databaseId}/query fallback
        if (!TextUtils.isEmpty(src.databaseId)) {
            QueryAttempt t4 = executeLegacyDatabaseQuery(token, src.databaseId);
            if (t4.ok) {
                return new SingleQueryOutcome(t4.pages, "");
            }
            return new SingleQueryOutcome(Collections.emptyList(),
                    "查詢「" + src.shortLabel + "」失敗 (HTTP " + t4.httpCode + ")：" + t4.errMsg);
        }

        return new SingleQueryOutcome(Collections.emptyList(),
                "查詢「" + src.shortLabel + "」失敗 (HTTP " + t3.httpCode + ")：" + t3.errMsg);
    }

    private static JSONArray buildDateBoundArray(NotionSource src, String windowStart, String windowEnd)
            throws Exception {
        JSONArray andArr = new JSONArray();
        if ("created_time".equals(src.datePropType) || "last_edited_time".equals(src.datePropType)) {
            andArr.put(new JSONObject()
                    .put("timestamp", src.datePropType)
                    .put(src.datePropType, new JSONObject().put("on_or_after", windowStart)));
            andArr.put(new JSONObject()
                    .put("timestamp", src.datePropType)
                    .put(src.datePropType, new JSONObject().put("on_or_before", windowEnd)));
        } else if ("formula".equals(src.datePropType)) {
            andArr.put(new JSONObject()
                    .put("property", src.datePropName)
                    .put("formula", new JSONObject().put("date", new JSONObject().put("on_or_after", windowStart))));
            andArr.put(new JSONObject()
                    .put("property", src.datePropName)
                    .put("formula", new JSONObject().put("date", new JSONObject().put("on_or_before", windowEnd))));
        } else {
            andArr.put(new JSONObject()
                    .put("property", src.datePropName)
                    .put("date", new JSONObject().put("on_or_after", windowStart)));
            andArr.put(new JSONObject()
                    .put("property", src.datePropName)
                    .put("date", new JSONObject().put("on_or_before", windowEnd)));
        }
        return andArr;
    }

    private static JSONObject mergeFiltersFlat(JSONObject sanitizedVf, JSONArray dateBounds) throws Exception {
        if (dateBounds == null || dateBounds.length() == 0) return sanitizedVf;
        JSONArray combined = new JSONArray();
        if (sanitizedVf.has("and") && sanitizedVf.length() == 1) {
            JSONArray vfAnd = sanitizedVf.optJSONArray("and");
            if (vfAnd != null) {
                for (int i = 0; i < vfAnd.length(); i++) {
                    combined.put(vfAnd.get(i));
                }
            }
        } else {
            combined.put(sanitizedVf);
        }
        for (int i = 0; i < dateBounds.length(); i++) {
            combined.put(dateBounds.get(i));
        }
        return new JSONObject().put("and", combined);
    }

    /**
     * Cleans Notion View filters for Internal Integrations:
     * - Replaces "me" in people filters with ownerUserId (or drops "contains: me" if unknown,
     *   because Notion returns 0 results for "me" on Internal Integrations).
     * - Strips empty {} filter nodes.
     */
    private static JSONObject sanitizeViewFilter(JSONObject filter, String ownerUserId) {
        if (filter == null || filter.length() == 0) return null;
        try {
            if (filter.has("and")) {
                JSONArray arr = filter.optJSONArray("and");
                if (arr == null) return null;
                JSONArray clean = new JSONArray();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject sub = sanitizeViewFilter(arr.optJSONObject(i), ownerUserId);
                    if (sub != null && sub.length() > 0) clean.put(sub);
                }
                if (clean.length() == 0) return null;
                if (clean.length() == 1) return clean.getJSONObject(0);
                return new JSONObject().put("and", clean);
            }
            if (filter.has("or")) {
                JSONArray arr = filter.optJSONArray("or");
                if (arr == null) return null;
                JSONArray clean = new JSONArray();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject sub = sanitizeViewFilter(arr.optJSONObject(i), ownerUserId);
                    if (sub != null && sub.length() > 0) clean.put(sub);
                }
                if (clean.length() == 0) return null;
                if (clean.length() == 1) return clean.getJSONObject(0);
                return new JSONObject().put("or", clean);
            }
            if (filter.has("people")) {
                JSONObject ppl = filter.optJSONObject("people");
                if (ppl != null) {
                    if ("me".equals(ppl.optString("contains"))) {
                        if (TextUtils.isEmpty(ownerUserId)) return null;
                        JSONObject copy = new JSONObject(filter.toString());
                        copy.getJSONObject("people").put("contains", ownerUserId);
                        return copy;
                    }
                    if ("me".equals(ppl.optString("does_not_contain"))) {
                        if (TextUtils.isEmpty(ownerUserId)) return null;
                        JSONObject copy = new JSONObject(filter.toString());
                        copy.getJSONObject("people").put("does_not_contain", ownerUserId);
                        return copy;
                    }
                }
            }
            return filter;
        } catch (Exception e) {
            return null;
        }
    }

    private static final class QueryAttempt {
        final boolean ok;
        final int httpCode;
        final String errMsg;
        final List<JSONObject> pages;

        QueryAttempt(boolean ok, int httpCode, String errMsg, List<JSONObject> pages) {
            this.ok = ok;
            this.httpCode = httpCode;
            this.errMsg = errMsg;
            this.pages = pages;
        }
    }

    private static QueryAttempt executeDataSourceQuery(
            String token, String dataSourceId, JSONObject filter, String betaHeader) throws Exception {
        List<JSONObject> pages = new ArrayList<>();
        String cursor = null;
        for (int pageNum = 0; pageNum < 3; pageNum++) {
            JSONObject body = new JSONObject();
            if (filter != null) {
                body.put("filter", filter);
            } else {
                JSONArray sorts = new JSONArray();
                sorts.put(new JSONObject()
                        .put("timestamp", "last_edited_time")
                        .put("direction", "descending"));
                body.put("sorts", sorts);
            }
            body.put("page_size", 100);
            if (!TextUtils.isEmpty(cursor)) body.put("start_cursor", cursor);

            // IMPORTANT: Do NOT pass "result_type": "page" here — Notion rejects result_type
            // on regular (non-wiki) data sources with HTTP 400!
            HttpResult res = requestNotion(
                    token, "POST", "/data_sources/" + dataSourceId + "/query", body.toString(), betaHeader);
            if (res.code != 200) {
                return new QueryAttempt(false, res.code, res.errorMsg, pages);
            }

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
            cursor = optCleanString(json, "next_cursor");
            if (cursor.isEmpty()) break;
        }
        return new QueryAttempt(true, 200, "", pages);
    }

    private static QueryAttempt executeLegacyDatabaseQuery(String token, String databaseId) throws Exception {
        List<JSONObject> pages = new ArrayList<>();
        JSONObject body = new JSONObject().put("page_size", 100);
        HttpResult res = executeNotionHttp(
                token, "POST", "/databases/" + databaseId + "/query", body.toString(), NOTION_VERSION_LEGACY, null);
        if (res.code != 200) {
            return new QueryAttempt(false, res.code, res.errorMsg, pages);
        }
        JSONArray results = new JSONObject(res.body).optJSONArray("results");
        if (results != null) {
            for (int i = 0; i < results.length(); i++) {
                JSONObject item = results.optJSONObject(i);
                if (item != null) pages.add(item);
            }
        }
        return new QueryAttempt(true, 200, "", pages);
    }

    private static NotionCachedEvent parsePageToEvent(JSONObject page, NotionSource src, ZoneId zone) {
        JSONObject props = page.optJSONObject("properties");
        if (props == null) return null;

        String[] startEnd = extractDateStartEndFromPage(page, props, src.datePropName, src.datePropId, src.datePropType);
        String startStr = startEnd[0];
        String endStr = startEnd[1];

        if (TextUtils.isEmpty(endStr) && !TextUtils.isEmpty(src.endDatePropName)) {
            String[] extraEnd = extractDateStartEndFromPage(page, props, src.endDatePropName, "", "date");
            if (!TextUtils.isEmpty(extraEnd[0])) {
                endStr = extraEnd[0];
            }
        }

        if (TextUtils.isEmpty(startStr)) return null;

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
                LocalDate endDay = Instant.ofEpochMilli(endMillis).atZone(zone).toLocalDate();
                return new NotionCachedEvent(
                        src.sourceKey, page.optString("id"), titleText, statusText, url,
                        false, startDay.toEpochDay(), Math.max(startDay.toEpochDay(), endDay.toEpochDay()),
                        beginMillis, endMillis, hasExplicitEnd);
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static String[] extractDateStartEndFromPage(
            JSONObject page, JSONObject props, String propName, String propId, String propType) {
        if ("created_time".equals(propType)) {
            return new String[]{optCleanString(page, "created_time"), ""};
        }
        if ("last_edited_time".equals(propType)) {
            return new String[]{optCleanString(page, "last_edited_time"), ""};
        }
        JSONObject prop = findPropertyObject(props, propName, propId);
        if (prop == null) return new String[]{"", ""};

        String actualType = prop.optString("type", propType);
        JSONObject dateObj = null;
        if ("date".equals(actualType)) {
            dateObj = prop.optJSONObject("date");
        } else if ("formula".equals(actualType)) {
            JSONObject f = prop.optJSONObject("formula");
            if (f != null && "date".equals(f.optString("type"))) {
                dateObj = f.optJSONObject("date");
            }
        } else if ("rollup".equals(actualType)) {
            JSONObject r = prop.optJSONObject("rollup");
            if (r != null && "date".equals(r.optString("type"))) {
                dateObj = r.optJSONObject("date");
            }
        } else if ("created_time".equals(actualType)) {
            return new String[]{optCleanString(prop, "created_time"), ""};
        } else if ("last_edited_time".equals(actualType)) {
            return new String[]{optCleanString(prop, "last_edited_time"), ""};
        }

        if (dateObj == null) return new String[]{"", ""};
        return new String[]{optCleanString(dateObj, "start"), optCleanString(dateObj, "end")};
    }

    private static String discoverFirstPersonUserId(String token) {
        try {
            HttpResult uRes = requestNotion(token, "GET", "/users?page_size=50", null, null);
            if (uRes.code == 200) {
                JSONArray arr = new JSONObject(uRes.body).optJSONArray("results");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject u = arr.optJSONObject(i);
                        if (u != null && "person".equals(u.optString("type"))) {
                            return optCleanString(u, "id");
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    private static String extractNotionUuid(String rawInput) {
        if (rawInput == null) return "";
        String trimmed = rawInput.trim();
        // Strip query string (?v=...) first so we grab the database ID rather than the view ID!
        int qIdx = trimmed.indexOf('?');
        String mainPart = qIdx >= 0 ? trimmed.substring(0, qIdx) : trimmed;
        Matcher m = HEX32_PATTERN.matcher(mainPart);
        String lastMatch = "";
        while (m.find()) {
            lastMatch = m.group(1);
        }
        if (!lastMatch.isEmpty()) return formatUuid(lastMatch);
        Matcher m2 = HEX32_PATTERN.matcher(trimmed);
        if (m2.find()) return formatUuid(m2.group(1));
        return "";
    }

    private static String formatUuid(String raw) {
        String hex = raw.replace("-", "").toLowerCase();
        if (hex.length() != 32) return raw;
        return hex.substring(0, 8) + "-" + hex.substring(8, 12) + "-"
                + hex.substring(12, 16) + "-" + hex.substring(16, 20) + "-" + hex.substring(20);
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
            if (!propId.isEmpty() && (id.equals(propId) || decodePropId(id).equals(decodedTarget))) {
                return p;
            }
        }
        return null;
    }

    private static DatePropOption resolveDateProp(
            String propId,
            Map<String, DatePropOption> lookup,
            List<DatePropOption> primaryDateProps,
            List<DatePropOption> formulaOrRollupProps,
            List<DatePropOption> allDateCapableProps) {
        if (!TextUtils.isEmpty(propId)) {
            DatePropOption found = lookup.get(propId);
            if (found != null) return found;
            found = lookup.get(decodePropId(propId));
            if (found != null) return found;
        }
        if (!primaryDateProps.isEmpty()) return primaryDateProps.get(0);
        if (!formulaOrRollupProps.isEmpty()) return formulaOrRollupProps.get(0);
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

    // ==================== TWO-WAY NOTION CREATE / EDIT / TRASH ====================

    public static final class NotionEditableSchema {
        public final String titlePropName;
        public final boolean dateEditable;
        public final String datePropName;
        public final String endDatePropName;
        public final String statusPropName;
        public final String statusPropType; // "status" or "select" or ""
        public final List<String> statusOptions;
        public final String notePropName;
        public final String livePlainTitle;
        public final String liveStatus;
        public final String liveNoteText;

        NotionEditableSchema(String titlePropName, boolean dateEditable, String datePropName,
                             String endDatePropName, String statusPropName, String statusPropType,
                             List<String> statusOptions, String notePropName,
                             String livePlainTitle, String liveStatus, String liveNoteText) {
            this.titlePropName = titlePropName;
            this.dateEditable = dateEditable;
            this.datePropName = datePropName;
            this.endDatePropName = endDatePropName;
            this.statusPropName = statusPropName;
            this.statusPropType = statusPropType;
            this.statusOptions = statusOptions;
            this.notePropName = notePropName;
            this.livePlainTitle = livePlainTitle;
            this.liveStatus = liveStatus;
            this.liveNoteText = liveNoteText;
        }
    }

    public interface SchemaCallback {
        void onReady(NotionEditableSchema schema);
        void onError(String message);
    }

    public interface MutationCallback {
        void onSuccess();
        default void onCreatedPage(String pageId) {
            onSuccess();
        }
        void onError(String message);
    }

    private static final Map<String, JSONObject> DS_SCHEMA_MEM_CACHE = new ConcurrentHashMap<>();

    public static List<NotionSource> getWritableNotionSources(Context context) {
        if (!hasToken(context)) return Collections.emptyList();
        CachedWorkspace ws = loadCache(context);
        if (ws == null || ws.sources.isEmpty()) return Collections.emptyList();
        List<NotionSource> out = new ArrayList<>();
        Set<String> seenDbProp = new HashSet<>();
        for (NotionSource src : ws.sources) {
            if (!"date".equals(src.datePropType)) continue;
            // Deduplicate identical (dataSourceId + datePropId) so the target selector stays crisp
            String dedupKey = src.dataSourceId + "|" + src.datePropName;
            if (seenDbProp.add(dedupKey)) {
                out.add(src);
            }
        }
        return out;
    }

    public static void fetchEditableSchemaAsync(
            Context context, String sourceKey, String optionalPageId, SchemaCallback callback) {
        final Context appContext = context.getApplicationContext();
        String token = getToken(appContext);
        if (token.isEmpty()) {
            if (callback != null) MAIN_HANDLER.post(() -> callback.onError("尚未設定 Notion Token"));
            return;
        }
        EXECUTOR.execute(() -> {
            try {
                NotionSource src = findSourceByKey(appContext, sourceKey);
                if (src == null) {
                    if (callback != null) MAIN_HANDLER.post(() -> callback.onError("找不到對應的 Notion 資料庫設定"));
                    return;
                }
                NotionEditableSchema schema = resolveSchemaBlocking(token, src, optionalPageId);
                if (callback != null) {
                    MAIN_HANDLER.post(() -> callback.onReady(schema));
                }
            } catch (Exception e) {
                if (callback != null) {
                    final String m = e.getMessage() != null ? e.getMessage() : "讀取欄位失敗";
                    MAIN_HANDLER.post(() -> callback.onError(m));
                }
            }
        });
    }

    private static NotionEditableSchema resolveSchemaBlocking(
            String token, NotionSource src, String optionalPageId) throws Exception {
        JSONObject propsSchema = DS_SCHEMA_MEM_CACHE.get(src.dataSourceId);
        if (propsSchema == null) {
            HttpResult dsRes = requestNotion(token, "GET", "/data_sources/" + src.dataSourceId, null, null);
            if (dsRes.code == 200) {
                JSONObject p = new JSONObject(dsRes.body).optJSONObject("properties");
                if (p != null) {
                    propsSchema = p;
                    DS_SCHEMA_MEM_CACHE.put(src.dataSourceId, p);
                }
            } else if (!TextUtils.isEmpty(src.databaseId)) {
                HttpResult dbRes = executeNotionHttp(
                        token, "GET", "/databases/" + src.databaseId, null, NOTION_VERSION_LEGACY, null);
                if (dbRes.code == 200) {
                    JSONObject p = new JSONObject(dbRes.body).optJSONObject("properties");
                    if (p != null) {
                        propsSchema = p;
                        DS_SCHEMA_MEM_CACHE.put(src.dataSourceId, p);
                    }
                }
            }
        }

        String titleProp = "";
        String statusProp = "";
        String statusType = "";
        List<String> statusOpts = new ArrayList<>();
        String noteProp = "";

        if (propsSchema != null) {
            // Pass 1: find title, status, and note-like rich_text
            Iterator<String> it = propsSchema.keys();
            while (it.hasNext()) {
                String k = it.next();
                JSONObject p = propsSchema.optJSONObject(k);
                if (p == null) continue;
                String t = p.optString("type", "");
                if ("title".equals(t) && titleProp.isEmpty()) {
                    titleProp = k;
                } else if ("status".equals(t) && statusProp.isEmpty()) {
                    statusProp = k;
                    statusType = "status";
                    JSONObject stCfg = p.optJSONObject("status");
                    if (stCfg != null) {
                        JSONArray opts = stCfg.optJSONArray("options");
                        if (opts != null) {
                            for (int i = 0; i < opts.length(); i++) {
                                String n = optCleanString(opts.optJSONObject(i), "name");
                                if (!n.isEmpty()) statusOpts.add(n);
                            }
                        }
                    }
                } else if ("rich_text".equals(t) && noteProp.isEmpty()) {
                    String lower = k.toLowerCase(Locale.ROOT);
                    if (lower.contains("備註") || lower.contains("說明") || lower.contains("描述")
                            || lower.contains("地點") || lower.contains("內容")
                            || lower.contains("note") || lower.contains("desc") || lower.contains("location")) {
                        noteProp = k;
                    }
                }
            }
            // Pass 2: if no "status" column exists, check for a status-like "select" column
            if (statusProp.isEmpty()) {
                Iterator<String> it2 = propsSchema.keys();
                while (it2.hasNext()) {
                    String k = it2.next();
                    JSONObject p = propsSchema.optJSONObject(k);
                    if (p == null || !"select".equals(p.optString("type"))) continue;
                    String lower = k.toLowerCase(Locale.ROOT);
                    if (lower.contains("狀態") || lower.contains("分類") || lower.contains("類別")
                            || lower.contains("status") || lower.contains("type") || lower.contains("category")) {
                        statusProp = k;
                        statusType = "select";
                        JSONObject selCfg = p.optJSONObject("select");
                        if (selCfg != null) {
                            JSONArray opts = selCfg.optJSONArray("options");
                            if (opts != null) {
                                for (int i = 0; i < opts.length(); i++) {
                                    String n = optCleanString(opts.optJSONObject(i), "name");
                                    if (!n.isEmpty()) statusOpts.add(n);
                                }
                            }
                        }
                        break;
                    }
                }
            }
        }

        String liveTitle = "";
        String liveStatus = "";
        String liveNote = "";

        if (!TextUtils.isEmpty(optionalPageId)) {
            HttpResult pageRes = requestNotion(
                    token, "GET", "/pages/" + formatUuid(optionalPageId), null, null);
            if (pageRes.code == 200) {
                JSONObject pageJson = new JSONObject(pageRes.body);
                JSONObject pageProps = pageJson.optJSONObject("properties");
                if (pageProps != null) {
                    Iterator<String> pk = pageProps.keys();
                    while (pk.hasNext()) {
                        String k = pk.next();
                        JSONObject p = pageProps.optJSONObject(k);
                        if (p == null) continue;
                        String t = p.optString("type", "");
                        if ("title".equals(t)) {
                            if (titleProp.isEmpty()) titleProp = k;
                            liveTitle = extractPlainText(p.optJSONArray("title")).trim();
                        } else if (!statusProp.isEmpty() && k.equals(statusProp)) {
                            JSONObject obj = p.optJSONObject(statusType);
                            if (obj != null) liveStatus = optCleanString(obj, "name");
                        } else if (!noteProp.isEmpty() && k.equals(noteProp)) {
                            liveNote = extractPlainText(p.optJSONArray("rich_text")).trim();
                        }
                    }
                }
            }
        }

        if (titleProp.isEmpty()) titleProp = "title";
        boolean dateEditable = "date".equals(src.datePropType);

        return new NotionEditableSchema(
                titleProp,
                dateEditable,
                src.datePropName,
                src.endDatePropName,
                statusProp,
                statusType,
                statusOpts,
                noteProp,
                liveTitle,
                liveStatus,
                liveNote
        );
    }

    public static void createNotionEventAsync(
            Context context,
            String sourceKey,
            NotionEditableSchema schemaOrNull,
            String title,
            boolean allDay,
            LocalDate startDate,
            LocalDate endDate,
            ZonedDateTime beginZdt,
            ZonedDateTime endZdt,
            String statusOption,
            String noteText,
            MutationCallback callback) {

        final Context appContext = context.getApplicationContext();
        String token = getToken(appContext);
        if (token.isEmpty()) {
            postMutErr(callback, "尚未設定 Notion Token");
            return;
        }

        EXECUTOR.execute(() -> {
            try {
                NotionSource src = findSourceByKey(appContext, sourceKey);
                if (src == null) {
                    postMutErr(callback, "找不到指定的 Notion 資料庫");
                    return;
                }
                NotionEditableSchema schema = schemaOrNull != null
                        ? schemaOrNull
                        : resolveSchemaBlocking(token, src, null);

                JSONObject props = buildMutationPropertiesJson(
                        schema, title, allDay, startDate, endDate, beginZdt, endZdt, statusOption, noteText);

                JSONObject body = new JSONObject();
                body.put("parent", new JSONObject()
                        .put("type", "data_source_id")
                        .put("data_source_id", formatUuid(src.dataSourceId)));
                body.put("properties", props);

                // If database has no dedicated note column and user wrote notes, attach as page body blocks
                if (TextUtils.isEmpty(schema.notePropName) && !TextUtils.isEmpty(noteText)) {
                    body.put("children", buildParagraphBlocksJson(noteText));
                }

                HttpResult res = requestNotion(token, "POST", "/pages", body.toString(), null);
                if (res.code == 400 && !TextUtils.isEmpty(src.databaseId)) {
                    // Fallback to database_id parent if workspace requires it
                    body.put("parent", new JSONObject()
                            .put("database_id", formatUuid(src.databaseId)));
                    res = requestNotion(token, "POST", "/pages", body.toString(), null);
                }

                if (res.code == 403) {
                    postMutErr(callback, "Notion 權限不足 (HTTP 403)：\n請到 Notion Integration 設定頁勾選「Insert content（新增內容）」並按 Save！");
                    return;
                }
                if (res.code < 200 || res.code >= 300) {
                    postMutErr(callback, "寫入 Notion 失敗 (HTTP " + res.code + ")：" + res.errorMsg);
                    return;
                }

                JSONObject createdPage = new JSONObject(res.body);
                upsertPageIntoLocalCache(appContext, src.dataSourceId, createdPage);
                CalendarWidgetProvider.updateAll(appContext);
                // Follow-up background sync so server-side view filters stay 100% consistent
                MAIN_HANDLER.postDelayed(() -> refreshEventsOnlyAsync(appContext, 0L, null), 1200L);

                final String createdId = createdPage.optString("id", "");
                if (callback != null) MAIN_HANDLER.post(() -> callback.onCreatedPage(createdId));
            } catch (Exception e) {
                postMutErr(callback, "新增至 Notion 失敗：" + e.getMessage());
            }
        });
    }

    public static void updateNotionEventAsync(
            Context context,
            String sourceKey,
            String pageId,
            NotionEditableSchema schemaOrNull,
            String title,
            boolean allDay,
            LocalDate startDate,
            LocalDate endDate,
            ZonedDateTime beginZdt,
            ZonedDateTime endZdt,
            String statusOption,
            String noteText,
            MutationCallback callback) {

        final Context appContext = context.getApplicationContext();
        String token = getToken(appContext);
        if (token.isEmpty()) {
            postMutErr(callback, "尚未設定 Notion Token");
            return;
        }
        if (TextUtils.isEmpty(pageId)) {
            postMutErr(callback, "缺少 Notion 頁面 ID，請先重新整理同步一次");
            return;
        }

        EXECUTOR.execute(() -> {
            try {
                NotionSource src = findSourceByKey(appContext, sourceKey);
                if (src == null) {
                    postMutErr(callback, "找不到對應的 Notion 資料庫");
                    return;
                }
                NotionEditableSchema schema = schemaOrNull != null
                        ? schemaOrNull
                        : resolveSchemaBlocking(token, src, pageId);

                JSONObject props = buildMutationPropertiesJson(
                        schema, title, allDay, startDate, endDate, beginZdt, endZdt, statusOption, noteText);
                JSONObject body = new JSONObject().put("properties", props);

                HttpResult res = requestNotion(
                        token, "PATCH", "/pages/" + formatUuid(pageId), body.toString(), null);
                if (res.code == 403) {
                    postMutErr(callback, "Notion 權限不足 (HTTP 403)：\n請到 Notion Integration 設定頁勾選「Update content（更新內容）」並按 Save！");
                    return;
                }
                if (res.code < 200 || res.code >= 300) {
                    postMutErr(callback, "儲存至 Notion 失敗 (HTTP " + res.code + ")：" + res.errorMsg);
                    return;
                }

                JSONObject updatedPage = new JSONObject(res.body);
                upsertPageIntoLocalCache(appContext, src.dataSourceId, updatedPage);
                CalendarWidgetProvider.updateAll(appContext);
                MAIN_HANDLER.postDelayed(() -> refreshEventsOnlyAsync(appContext, 0L, null), 1200L);

                if (callback != null) MAIN_HANDLER.post(callback::onSuccess);
            } catch (Exception e) {
                postMutErr(callback, "更新 Notion 失敗：" + e.getMessage());
            }
        });
    }

    public static void trashNotionEventAsync(
            Context context, String pageId, MutationCallback callback) {
        final Context appContext = context.getApplicationContext();
        String token = getToken(appContext);
        if (token.isEmpty() || TextUtils.isEmpty(pageId)) {
            postMutErr(callback, "缺少 Notion Token 或頁面 ID");
            return;
        }
        EXECUTOR.execute(() -> {
            try {
                String body = new JSONObject().put("in_trash", true).toString();
                HttpResult res = requestNotion(
                        token, "PATCH", "/pages/" + formatUuid(pageId), body, null);
                if (res.code == 403) {
                    postMutErr(callback, "Notion 權限不足 (HTTP 403)：請確認 Integration 已勾選「Update content」！");
                    return;
                }
                if (res.code < 200 || res.code >= 300) {
                    postMutErr(callback, "移至 Notion 垃圾桶失敗 (HTTP " + res.code + ")：" + res.errorMsg);
                    return;
                }
                removePageFromLocalCache(appContext, pageId);
                CalendarWidgetProvider.updateAll(appContext);
                if (callback != null) MAIN_HANDLER.post(callback::onSuccess);
            } catch (Exception e) {
                postMutErr(callback, "刪除 Notion 行程失敗：" + e.getMessage());
            }
        });
    }

    private static JSONObject buildMutationPropertiesJson(
            NotionEditableSchema schema,
            String title,
            boolean allDay,
            LocalDate startDate,
            LocalDate endDate,
            ZonedDateTime beginZdt,
            ZonedDateTime endZdt,
            String statusOption,
            String noteText) throws Exception {

        JSONObject props = new JSONObject();

        // 1. Title property
        JSONArray titleArr = new JSONArray().put(new JSONObject()
                .put("type", "text")
                .put("text", new JSONObject().put("content", title)));
        props.put(schema.titlePropName, new JSONObject().put("title", titleArr));

        // 2. Date property (when backed by an editable Notion `date` property)
        if (schema.dateEditable && !TextUtils.isEmpty(schema.datePropName)) {
            String startIso;
            String endIso = null;
            if (allDay) {
                startIso = startDate.toString();
                if (endDate != null && endDate.isAfter(startDate)) {
                    endIso = endDate.toString();
                }
            } else {
                startIso = beginZdt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
                if (endZdt != null && endZdt.isAfter(beginZdt)) {
                    endIso = endZdt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
                }
            }

            if (!TextUtils.isEmpty(schema.endDatePropName)
                    && !schema.endDatePropName.equals(schema.datePropName)) {
                JSONObject startDateObj = new JSONObject().put("start", startIso);
                props.put(schema.datePropName, new JSONObject().put("date", startDateObj));
                if (endIso != null) {
                    JSONObject endDateObj = new JSONObject().put("start", endIso);
                    props.put(schema.endDatePropName, new JSONObject().put("date", endDateObj));
                }
            } else {
                JSONObject dateObj = new JSONObject().put("start", startIso);
                dateObj.put("end", endIso != null ? endIso : JSONObject.NULL);
                props.put(schema.datePropName, new JSONObject().put("date", dateObj));
            }
        }

        // 3. Optional Status / Select property
        if (!TextUtils.isEmpty(schema.statusPropName)
                && !TextUtils.isEmpty(schema.statusPropType)
                && !TextUtils.isEmpty(statusOption)) {
            props.put(schema.statusPropName, new JSONObject()
                    .put(schema.statusPropType, new JSONObject().put("name", statusOption)));
        }

        // 4. Optional Rich-Text Note property
        if (!TextUtils.isEmpty(schema.notePropName) && noteText != null) {
            JSONArray rt = new JSONArray();
            if (!noteText.trim().isEmpty()) {
                rt.put(new JSONObject()
                        .put("type", "text")
                        .put("text", new JSONObject().put("content", noteText.trim())));
            }
            props.put(schema.notePropName, new JSONObject().put("rich_text", rt));
        }

        return props;
    }

    private static JSONArray buildParagraphBlocksJson(String text) throws Exception {
        JSONArray children = new JSONArray();
        for (String line : text.split("\n")) {
            JSONArray rt = new JSONArray().put(new JSONObject()
                    .put("type", "text")
                    .put("text", new JSONObject().put("content", line)));
            children.put(new JSONObject()
                    .put("object", "block")
                    .put("type", "paragraph")
                    .put("paragraph", new JSONObject().put("rich_text", rt)));
        }
        return children;
    }

    private static NotionSource findSourceByKey(Context context, String sourceKey) {
        CachedWorkspace ws = loadCache(context);
        if (ws == null) return null;
        for (NotionSource s : ws.sources) {
            if (s.sourceKey.equals(sourceKey)) return s;
        }
        return ws.sources.isEmpty() ? null : ws.sources.get(0);
    }

    private static void upsertPageIntoLocalCache(Context context, String dataSourceId, JSONObject pageJson) {
        CachedWorkspace ws = loadCache(context);
        if (ws == null) return;
        String targetPageUuid = normalizeUuid(pageJson.optString("id"));
        if (targetPageUuid.isEmpty()) return;

        ZoneId zone = ZoneId.systemDefault();
        String normDs = normalizeUuid(dataSourceId);
        List<NotionCachedEvent> updatedEvents = new ArrayList<>();
        Set<String> matchedSourceKeys = new HashSet<>();

        for (NotionCachedEvent ev : ws.events) {
            if (normalizeUuid(ev.pageId).equals(targetPageUuid)) {
                matchedSourceKeys.add(ev.sourceKey);
                continue;
            }
            updatedEvents.add(ev);
        }

        for (NotionSource src : ws.sources) {
            if (!normalizeUuid(src.dataSourceId).equals(normDs)) continue;
            // Re-insert into sources that previously held it, or non-filtered views of the same DB
            if (matchedSourceKeys.contains(src.sourceKey)
                    || matchedSourceKeys.isEmpty()
                    || TextUtils.isEmpty(src.filterJson)) {
                NotionCachedEvent parsed = parsePageToEvent(pageJson, src, zone);
                if (parsed != null) {
                    updatedEvents.add(parsed);
                }
            }
        }

        saveCache(context, new CachedWorkspace(
                ws.workspaceName, ws.sources, ws.databases, updatedEvents, ws.diagnosticNote));
    }

    private static void removePageFromLocalCache(Context context, String pageId) {
        CachedWorkspace ws = loadCache(context);
        if (ws == null) return;
        String normId = normalizeUuid(pageId);
        List<NotionCachedEvent> kept = new ArrayList<>();
        for (NotionCachedEvent ev : ws.events) {
            if (!normalizeUuid(ev.pageId).equals(normId)) {
                kept.add(ev);
            }
        }
        saveCache(context, new CachedWorkspace(
                ws.workspaceName, ws.sources, ws.databases, kept, ws.diagnosticNote));
    }

    private static void postMutErr(MutationCallback cb, String msg) {
        if (cb != null) {
            MAIN_HANDLER.post(() -> cb.onError(msg));
        }
    }

    private static HttpResult requestNotion(
            String token, String method, String path, String jsonBody, String betaHeader) {
        HttpResult primary = executeNotionHttp(token, method, path, jsonBody, NOTION_VERSION_PRIMARY, betaHeader);
        if (primary.code == 400 && primary.errorMsg.contains("Notion-Version")) {
            return executeNotionHttp(token, method, path, jsonBody, NOTION_VERSION_FALLBACK, betaHeader);
        }
        return primary;
    }

    private static HttpResult executeNotionHttp(
            String token, String method, String path, String jsonBody, String version, String betaHeader) {
        if ("PATCH".equalsIgnoreCase(method)) {
            return executeNotionTlsPatch(token, path, jsonBody, version, betaHeader);
        }
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
                if (!TextUtils.isEmpty(betaHeader)) {
                    conn.setRequestProperty("Notion-Beta", betaHeader);
                }

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

    /**
     * Android's HttpURLConnection rejects setRequestMethod("PATCH") with ProtocolException.
     * Sends RFC-compliant HTTPS HTTP/1.1 PATCH over verified TLS socket directly to api.notion.com.
     */
    private static HttpResult executeNotionTlsPatch(
            String token, String path, String jsonBody, String version, String betaHeader) {
        final String host = "api.notion.com";
        byte[] payload = (jsonBody != null ? jsonBody : "{}").getBytes(StandardCharsets.UTF_8);

        for (int attempt = 0; attempt < 3; attempt++) {
            SSLSocket socket = null;
            try {
                SSLSocketFactory factory = HttpsURLConnection.getDefaultSSLSocketFactory();
                socket = (SSLSocket) factory.createSocket();
                socket.connect(new InetSocketAddress(host, 443), 15_000);
                socket.setSoTimeout(20_000);

                SSLParameters sslParams = socket.getSSLParameters();
                sslParams.setServerNames(Collections.singletonList(new SNIHostName(host)));
                sslParams.setEndpointIdentificationAlgorithm("HTTPS");
                socket.setSSLParameters(sslParams);
                socket.startHandshake();

                if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, socket.getSession())) {
                    throw new IOException("TLS 主機憑證驗證失敗");
                }

                StringBuilder req = new StringBuilder();
                req.append("PATCH /v1").append(path).append(" HTTP/1.1\r\n");
                req.append("Host: ").append(host).append("\r\n");
                req.append("Authorization: Bearer ").append(token).append("\r\n");
                req.append("Notion-Version: ").append(version).append("\r\n");
                if (!TextUtils.isEmpty(betaHeader)) {
                    req.append("Notion-Beta: ").append(betaHeader).append("\r\n");
                }
                req.append("Accept: application/json\r\n");
                req.append("Accept-Encoding: identity\r\n");
                req.append("Content-Type: application/json; charset=utf-8\r\n");
                req.append("Content-Length: ").append(payload.length).append("\r\n");
                req.append("Connection: close\r\n\r\n");

                OutputStream out = socket.getOutputStream();
                out.write(req.toString().getBytes(StandardCharsets.UTF_8));
                out.write(payload);
                out.flush();

                InputStream in = socket.getInputStream();
                String statusLine = readAsciiHeaderLine(in);
                if (statusLine == null || !statusLine.startsWith("HTTP/")) {
                    throw new IOException("無效的伺服器回應");
                }
                int code = 500;
                String[] statusParts = statusLine.split(" ");
                if (statusParts.length >= 2) {
                    code = Integer.parseInt(statusParts[1].trim());
                }

                boolean chunked = false;
                int contentLength = -1;
                String hdrLine;
                while ((hdrLine = readAsciiHeaderLine(in)) != null && !hdrLine.isEmpty()) {
                    int colon = hdrLine.indexOf(':');
                    if (colon > 0) {
                        String k = hdrLine.substring(0, colon).trim().toLowerCase(Locale.ROOT);
                        String v = hdrLine.substring(colon + 1).trim().toLowerCase(Locale.ROOT);
                        if ("transfer-encoding".equals(k) && v.contains("chunked")) {
                            chunked = true;
                        } else if ("content-length".equals(k)) {
                            try {
                                contentLength = Integer.parseInt(v);
                            } catch (NumberFormatException ignored) {
                            }
                        }
                    }
                }

                if ((code == 429 || code == 503 || code == 529) && attempt < 2) {
                    socket.close();
                    Thread.sleep((1 + attempt) * 1000L);
                    continue;
                }

                ByteArrayOutputStream bodyBytes = new ByteArrayOutputStream();
                if (chunked) {
                    while (true) {
                        String sizeLine = readAsciiHeaderLine(in);
                        if (sizeLine == null) break;
                        int semi = sizeLine.indexOf(';');
                        if (semi >= 0) sizeLine = sizeLine.substring(0, semi);
                        sizeLine = sizeLine.trim();
                        if (sizeLine.isEmpty()) continue;
                        int chunkLen = Integer.parseInt(sizeLine, 16);
                        if (chunkLen <= 0) break;
                        readExactBytes(in, bodyBytes, chunkLen);
                        readAsciiHeaderLine(in); // consume trailing CRLF
                    }
                } else if (contentLength >= 0) {
                    readExactBytes(in, bodyBytes, contentLength);
                } else {
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        bodyBytes.write(buf, 0, n);
                    }
                }

                String respText = new String(bodyBytes.toByteArray(), StandardCharsets.UTF_8);
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
                    return new HttpResult(-1, "", e.getMessage() != null ? e.getMessage() : "TLS PATCH 連線異常");
                }
            } finally {
                if (socket != null) {
                    try {
                        socket.close();
                    } catch (IOException ignored) {
                    }
                }
            }
        }
        return new HttpResult(-1, "", "網路連線逾時");
    }

    private static String readAsciiHeaderLine(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream(128);
        int b;
        boolean readAny = false;
        while ((b = in.read()) != -1) {
            readAny = true;
            if (b == '\n') break;
            if (b != '\r') line.write(b);
        }
        if (!readAny && line.size() == 0) return null;
        return new String(line.toByteArray(), StandardCharsets.US_ASCII);
    }

    private static void readExactBytes(InputStream in, ByteArrayOutputStream out, int count) throws IOException {
        byte[] buf = new byte[Math.min(4096, Math.max(1, count))];
        int remaining = count;
        while (remaining > 0) {
            int n = in.read(buf, 0, Math.min(buf.length, remaining));
            if (n == -1) break;
            out.write(buf, 0, n);
            remaining -= n;
        }
    }
}
