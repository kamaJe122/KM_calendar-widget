package io.github.kamaje122.calwidget;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Built-in OTA self-updater connected to GitHub Releases (kamaJe122/KM_calendar-widget).
 */
public final class AppUpdater {

    public static final String DEFAULT_GITHUB_REPO = "kamaJe122/KM_calendar-widget";
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    private static final Pattern BUILD_TAG_PATTERN = Pattern.compile("-b(\\d+)$");
    private static final Pattern BUILD_NOTES_PATTERN = Pattern.compile("\\[build:(\\d+)]");

    public static final class ReleaseInfo {
        public final String tagName;
        public final String cleanVersion;
        public final int remoteVersionCode;
        public final String title;
        public final String changelog;
        public final String apkDownloadUrl;
        public final long sizeBytes;
        public final boolean hasNewerUpdate;

        ReleaseInfo(String tagName, String cleanVersion, int remoteVersionCode, String title,
                    String changelog, String apkDownloadUrl, long sizeBytes, boolean hasNewerUpdate) {
            this.tagName = tagName;
            this.cleanVersion = cleanVersion;
            this.remoteVersionCode = remoteVersionCode;
            this.title = title;
            this.changelog = changelog;
            this.apkDownloadUrl = apkDownloadUrl;
            this.sizeBytes = sizeBytes;
            this.hasNewerUpdate = hasNewerUpdate;
        }
    }

    public interface CheckCallback {
        void onSuccess(ReleaseInfo info);
        void onError(String message);
    }

    public interface DownloadCallback {
        void onProgress(int percent);
        void onReadyToInstall(File apkFile);
        void onError(String message);
    }

    private AppUpdater() {}

    public static int getCurrentVersionCode(Context context) {
        try {
            PackageInfo pInfo = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return (int) pInfo.getLongVersionCode();
        } catch (Exception e) {
            return 10;
        }
    }

    public static String getCurrentVersionName(Context context) {
        try {
            PackageInfo pInfo = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return pInfo.versionName != null ? pInfo.versionName : "3.1.0";
        } catch (Exception e) {
            return "3.1.0";
        }
    }

    public static void checkLatestRelease(Context context, CheckCallback callback) {
        final Context appContext = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            HttpURLConnection conn = null;
            try {
                String apiUrl = "https://api.github.com/repos/" + DEFAULT_GITHUB_REPO
                        + "/releases/latest?t=" + System.currentTimeMillis();
                conn = (HttpURLConnection) new URL(apiUrl).openConnection();
                conn.setRequestMethod("GET");
                conn.setUseCaches(false);
                conn.setConnectTimeout(12_000);
                conn.setReadTimeout(12_000);
                conn.setRequestProperty("Accept", "application/vnd.github+json");
                conn.setRequestProperty("Cache-Control", "no-cache");
                conn.setRequestProperty("User-Agent", "KM-CalendarWidget-Updater");

                int code = conn.getResponseCode();
                if (code == 404) {
                    postError(callback, "GitHub 儲存庫目前尚無發佈的 Release 版本");
                    return;
                }
                if (code != 200) {
                    postError(callback, "無法取得 GitHub 更新資訊 (HTTP " + code + ")");
                    return;
                }

                StringBuilder sb = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line).append('\n');
                    }
                }

                JSONObject json = new JSONObject(sb.toString());
                String tag = json.optString("tag_name", "");
                String name = json.optString("name", tag);
                String notes = json.optString("body", "");

                int parsedBuild = 0;
                Matcher mTag = BUILD_TAG_PATTERN.matcher(tag);
                if (mTag.find()) {
                    parsedBuild = parseIntSafe(mTag.group(1), 0);
                } else {
                    Matcher mNotes = BUILD_NOTES_PATTERN.matcher(notes);
                    if (mNotes.find()) {
                        parsedBuild = parseIntSafe(mNotes.group(1), 0);
                    }
                }

                JSONArray assets = json.optJSONArray("assets");
                String apkUrl = "";
                long apkSize = 0L;
                if (assets != null) {
                    for (int i = 0; i < assets.length(); i++) {
                        JSONObject asset = assets.getJSONObject(i);
                        String assetName = asset.optString("name", "");
                        if (assetName.toLowerCase().endsWith(".apk")) {
                            apkUrl = asset.optString("browser_download_url", "");
                            apkSize = asset.optLong("size", 0L);
                            break;
                        }
                    }
                }

                if (apkUrl.isEmpty()) {
                    postError(callback, "最新 Release (" + tag + ") 中找不到 .apk 安裝檔");
                    return;
                }

                String cleanTag = tag.replaceAll("-b\\d+$", "");
                String cleanNotes = notes.replaceAll("\\[build:\\d+]", "").trim();
                int currentCode = getCurrentVersionCode(appContext);
                String currentVer = getCurrentVersionName(appContext);

                boolean newer;
                if (parsedBuild > 0) {
                    newer = parsedBuild > currentCode;
                } else {
                    newer = compareSemver(cleanTag, currentVer) > 0;
                }

                ReleaseInfo info = new ReleaseInfo(
                        tag, cleanTag, parsedBuild, name, cleanNotes, apkUrl, apkSize, newer);
                MAIN_HANDLER.post(() -> callback.onSuccess(info));
            } catch (Exception e) {
                postError(callback, "檢查更新失敗：" + e.getMessage());
            } finally {
                if (conn != null) conn.disconnect();
            }
        });
    }

    public static void downloadApk(Context context, String apkUrl, DownloadCallback callback) {
        final Context appContext = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            HttpURLConnection conn = null;
            try {
                File updatesDir = new File(appContext.getCacheDir(), "updates");
                if (!updatesDir.exists()) updatesDir.mkdirs();
                File targetFile = new File(updatesDir, "KM_calendar-widget-latest.apk");
                if (targetFile.exists()) targetFile.delete();

                String currentUrl = apkUrl;
                int redirects = 0;
                while (true) {
                    conn = (HttpURLConnection) new URL(currentUrl).openConnection();
                    conn.setInstanceFollowRedirects(true);
                    conn.setUseCaches(false);
                    conn.setConnectTimeout(15_000);
                    conn.setReadTimeout(30_000);
                    conn.setRequestProperty("Cache-Control", "no-cache");
                    conn.setRequestProperty("User-Agent", "KM-CalendarWidget-Updater");

                    int status = conn.getResponseCode();
                    if (status >= 301 && status <= 308) {
                        String loc = conn.getHeaderField("Location");
                        conn.disconnect();
                        if (loc == null || loc.isEmpty() || ++redirects > 6) {
                            postDownloadError(callback, "下載重新導向失敗");
                            return;
                        }
                        currentUrl = loc;
                    } else if (status == 200) {
                        break;
                    } else {
                        conn.disconnect();
                        postDownloadError(callback, "下載 APK 失敗 (HTTP " + status + ")");
                        return;
                    }
                }

                long total = conn.getContentLengthLong();
                try (InputStream input = conn.getInputStream();
                     FileOutputStream output = new FileOutputStream(targetFile)) {
                    byte[] buf = new byte[32 * 1024];
                    long downloaded = 0L;
                    int lastPct = -1;
                    int read;
                    while ((read = input.read(buf)) > 0) {
                        output.write(buf, 0, read);
                        downloaded += read;
                        if (total > 0) {
                            int pct = (int) Math.min(100L, Math.max(0L, (downloaded * 100L) / total));
                            if (pct != lastPct) {
                                lastPct = pct;
                                final int reportPct = pct;
                                MAIN_HANDLER.post(() -> callback.onProgress(reportPct));
                            }
                        }
                    }
                }

                MAIN_HANDLER.post(() -> {
                    callback.onProgress(100);
                    callback.onReadyToInstall(targetFile);
                });
            } catch (Exception e) {
                postDownloadError(callback, "下載失敗：" + e.getMessage());
            } finally {
                if (conn != null) conn.disconnect();
            }
        });
    }

    /**
     * Launches APK installation if allowed; if unknown-app-sources permission is needed,
     * opens the system permission screen and returns false so the caller can resume installation.
     */
    public static boolean promptInstallApk(Context context, File apkFile) {
        if (!context.getPackageManager().canRequestPackageInstalls()) {
            Intent permIntent = new Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + context.getPackageName()));
            permIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(permIntent);
            return false;
        }
        Uri apkUri = ApkFileProvider.getUriForFile(context, apkFile);
        Intent installIntent = new Intent(Intent.ACTION_VIEW);
        installIntent.setDataAndType(apkUri, "application/vnd.android.package-archive");
        installIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(installIntent);
        return true;
    }

    private static void postError(CheckCallback cb, String msg) {
        MAIN_HANDLER.post(() -> cb.onError(msg));
    }

    private static void postDownloadError(DownloadCallback cb, String msg) {
        MAIN_HANDLER.post(() -> cb.onError(msg));
    }

    private static int parseIntSafe(String s, int fallback) {
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return fallback;
        }
    }

    private static int compareSemver(String v1, String v2) {
        String s1 = v1.startsWith("v") || v1.startsWith("V") ? v1.substring(1) : v1;
        String s2 = v2.startsWith("v") || v2.startsWith("V") ? v2.substring(1) : v2;
        String[] p1 = s1.split("[^0-9]+");
        String[] p2 = s2.split("[^0-9]+");
        int len = Math.max(p1.length, p2.length);
        for (int i = 0; i < len; i++) {
            int n1 = i < p1.length && !p1[i].isEmpty() ? parseIntSafe(p1[i], 0) : 0;
            int n2 = i < p2.length && !p2[i].isEmpty() ? parseIntSafe(p2[i], 0) : 0;
            if (n1 != n2) return Integer.compare(n1, n2);
        }
        return 0;
    }
}
