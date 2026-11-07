package com.kim.autobuyer;

import android.app.Activity;
import android.app.DownloadManager;
import android.app.PendingIntent;
import android.content.pm.PackageInstaller;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import android.database.Cursor;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class AppUpdater {
    private static final String MANIFEST_URL =
            "https://walking2129.github.io/Teenoi-Coupon/update-server/latest.json";
    private static final String EXPECTED_HOST = "walking2129.github.io";
    private static final String APK_NAME = "KimAutoBuyer-update.apk";
    private static final String PREFS = "kim_auto_updater";

    private final Activity activity;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final DownloadManager downloadManager;
    private boolean receiverRegistered;

    private final BroadcastReceiver downloadReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())) {
                long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L);
                if (id == prefs().getLong("download_id", -2L)) {
                    processPendingDownload();
                }
            }
        }
    };

    public AppUpdater(Activity activity) {
        this.activity = activity;
        this.downloadManager = (DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);
        IntentFilter filter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        if (Build.VERSION.SDK_INT >= 33) {
            activity.registerReceiver(downloadReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            activity.registerReceiver(downloadReceiver, filter);
        }
        receiverRegistered = true;
    }

    public void checkForUpdates() {
        if (downloadManager == null) {
            show("อุปกรณ์ไม่รองรับการดาวน์โหลดอัปเดต");
            return;
        }
        executor.execute(() -> {
            HttpURLConnection connection = null;
            try {
                int installedVersion = installedVersionCode();
                long oldDownloadId = prefs().getLong("download_id", -1L);
                if (oldDownloadId >= 0L) {
                    int queuedVersion = prefs().getInt("download_version", 0);
                    if (queuedVersion > installedVersion) {
                        prefs().edit().putBoolean("install_launched", false).apply();
                        activity.runOnUiThread(() -> {
                            show("พบไฟล์อัปเดตที่ดาวน์โหลดไว้แล้ว กำลังเปิดตัวติดตั้ง");
                            processPendingDownload();
                        });
                        return;
                    }
                    clearPendingDownload();
                }

                URL manifestUrl = new URL(MANIFEST_URL);
                if (!"https".equalsIgnoreCase(manifestUrl.getProtocol()) ||
                        !EXPECTED_HOST.equalsIgnoreCase(manifestUrl.getHost())) {
                    throw new IllegalStateException("ที่อยู่เซิร์ฟเวอร์อัปเดตไม่ถูกต้อง");
                }
                connection = (HttpURLConnection) manifestUrl.openConnection();
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(15000);
                connection.setRequestMethod("GET");
                connection.setUseCaches(false);
                if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                    throw new IllegalStateException("ยังไม่พบข้อมูลเวอร์ชันอัปเดตบนเซิร์ฟเวอร์");
                }
                StringBuilder body = new StringBuilder();
                try (InputStream input = connection.getInputStream()) {
                    byte[] buffer = new byte[4096];
                    int count;
                    while ((count = input.read(buffer)) != -1) body.append(new String(buffer, 0, count, "UTF-8"));
                }
                JSONObject json = new JSONObject(body.toString());
                int latestCode = json.getInt("versionCode");
                String latestName = json.getString("versionName");
                String apkUrl = json.getString("apkUrl");
                String sha256 = json.getString("sha256").toLowerCase(Locale.ROOT);

                URL parsedApkUrl = new URL(apkUrl);
                if (!"https".equalsIgnoreCase(parsedApkUrl.getProtocol()) ||
                        !EXPECTED_HOST.equalsIgnoreCase(parsedApkUrl.getHost()) ||
                        !sha256.matches("[0-9a-f]{64}") || latestCode <= 0) {
                    throw new IllegalStateException("ข้อมูลอัปเดตไม่ผ่านการตรวจสอบ");
                }

                if (latestCode <= installedVersion) {
                    activity.runOnUiThread(() -> show("Kim Auto Buyer เป็นเวอร์ชันล่าสุดแล้ว (" + latestName + ")"));
                    return;
                }

                DownloadManager.Request request = new DownloadManager.Request(Uri.parse(apkUrl));
                request.setTitle("Kim Auto Buyer " + latestName);
                request.setDescription("กำลังดาวน์โหลดอัปเดต");
                request.setMimeType("application/vnd.android.package-archive");
                request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                request.setAllowedOverMetered(true);
                request.setAllowedOverRoaming(false);
                request.setDestinationInExternalFilesDir(activity, Environment.DIRECTORY_DOWNLOADS, APK_NAME);

                long id = downloadManager.enqueue(request);
                prefs().edit()
                        .putLong("download_id", id)
                        .putInt("download_version", latestCode)
                        .putString("download_version_name", latestName)
                        .putString("download_sha256", sha256)
                        .putBoolean("install_launched", false)
                        .putBoolean("needs_install_permission", false)
                        .apply();
                activity.runOnUiThread(() -> show("พบเวอร์ชัน " + latestName + " — เริ่มดาวน์โหลดแล้ว"));
            } catch (Exception e) {
                String message = e.getMessage() == null ? "ตรวจสอบอัปเดตไม่สำเร็จ" : e.getMessage();
                activity.runOnUiThread(() -> show("ตรวจสอบอัปเดตไม่สำเร็จ: " + message));
            } finally {
                if (connection != null) connection.disconnect();
            }
        });
    }

    public void resumePendingInstall() {
        int queuedVersion = prefs().getInt("download_version", 0);
        try {
            if (queuedVersion > 0 && queuedVersion <= installedVersionCode()) {
                clearPendingDownload();
                return;
            }
        } catch (Exception ignored) {
            // A transient package-manager error should not discard the download.
        }
        if (prefs().getBoolean("needs_install_permission", false)) {
            if (canInstallPackages()) {
                prefs().edit().putBoolean("needs_install_permission", false)
                        .putBoolean("install_launched", false).apply();
                processPendingDownload();
            }
            return;
        }
        processPendingDownload();
    }

    private void processPendingDownload() {
        long id = prefs().getLong("download_id", -1L);
        if (id < 0L || prefs().getBoolean("install_launched", false)) return;

        DownloadManager.Query query = new DownloadManager.Query().setFilterById(id);
        try (Cursor cursor = downloadManager.query(query)) {
            if (cursor == null || !cursor.moveToFirst()) return;
            int statusColumn = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS);
            int status = cursor.getInt(statusColumn);
            if (status == DownloadManager.STATUS_PENDING ||
                    status == DownloadManager.STATUS_RUNNING ||
                    status == DownloadManager.STATUS_PAUSED) return;
            if (status != DownloadManager.STATUS_SUCCESSFUL) {
                clearPendingDownload();
                show("ดาวน์โหลดอัปเดตไม่สำเร็จ กรุณาลองใหม่");
                return;
            }
        } catch (Exception e) {
            show("อ่านสถานะดาวน์โหลดไม่สำเร็จ");
            return;
        }

        File apk = new File(activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), APK_NAME);
        if (!apk.isFile()) {
            clearPendingDownload();
            show("ไม่พบไฟล์ APK ที่ดาวน์โหลด");
            return;
        }

        try {
            String expected = prefs().getString("download_sha256", "");
            String actual = sha256(apk);
            if (expected.isEmpty() || !expected.equalsIgnoreCase(actual)) {
                // A failed integrity check must never be passed to Android's installer.
                //noinspection ResultOfMethodCallIgnored
                apk.delete();
                clearPendingDownload();
                show("ไฟล์อัปเดตไม่ผ่านการตรวจสอบความถูกต้อง จึงยกเลิกการติดตั้ง");
                return;
            }
            launchInstaller(apk);
        } catch (Exception e) {
            show("ตรวจสอบไฟล์อัปเดตไม่สำเร็จ");
        }
    }

    private void launchInstaller(File apk) {
        if (Build.VERSION.SDK_INT >= 26 && !canInstallPackages()) {
            prefs().edit().putBoolean("needs_install_permission", true).apply();
            show("กรุณาอนุญาตให้ Kim Auto Buyer ติดตั้งแอปจากแหล่งนี้หนึ่งครั้ง");
            Intent settings = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + activity.getPackageName()));
            activity.startActivity(settings);
            return;
        }

        int sessionId = -1;
        try {
            PackageInstaller installer = activity.getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams params =
                    new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            params.setAppPackageName(activity.getPackageName());
            params.setSize(apk.length());
            if (Build.VERSION.SDK_INT >= 31) {
                // Android may install this app's own update without a confirmation screen
                // when its platform update-owner/installer conditions are satisfied.
                params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
            }
            sessionId = installer.createSession(params);
            Intent resultIntent = new Intent(activity, InstallResultReceiver.class)
                    .setAction(InstallResultReceiver.ACTION_INSTALL_RESULT)
                    .putExtra(InstallResultReceiver.EXTRA_SESSION_ID, sessionId)
                    .putExtra(InstallResultReceiver.EXTRA_VERSION,
                            prefs().getInt("download_version", 0));
            int pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 31) pendingFlags |= PendingIntent.FLAG_MUTABLE;
            PendingIntent resultPendingIntent = PendingIntent.getBroadcast(
                    activity, sessionId, resultIntent, pendingFlags);

            try (PackageInstaller.Session session = installer.openSession(sessionId);
                 FileInputStream input = new FileInputStream(apk);
                 OutputStream output = session.openWrite("base.apk", 0, apk.length())) {
                byte[] buffer = new byte[16 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                session.fsync(output);
                session.commit(resultPendingIntent.getIntentSender());
            }
            prefs().edit().putBoolean("install_launched", true).apply();
            show("ส่งไฟล์อัปเดตให้ Android ติดตั้งแล้ว");
        } catch (Exception e) {
            if (sessionId >= 0) {
                try {
                    activity.getPackageManager().getPackageInstaller().abandonSession(sessionId);
                } catch (Exception ignored) {
                    // Best-effort cleanup only.
                }
            }
            prefs().edit().putBoolean("install_launched", false).apply();
            show("ติดตั้งอัปเดตไม่สำเร็จ: " +
                    (e.getMessage() == null ? "เกิดข้อผิดพลาด" : e.getMessage()));
        }
    }

    private boolean canInstallPackages() {
        return Build.VERSION.SDK_INT < 26 || activity.getPackageManager().canRequestPackageInstalls();
    }

    private int installedVersionCode() throws Exception {
        PackageInfo info = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0);
        if (Build.VERSION.SDK_INT >= 28) return (int) info.getLongVersionCode();
        //noinspection deprecation
        return info.versionCode;
    }

    private String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        StringBuilder result = new StringBuilder();
        for (byte value : digest.digest()) result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        return result.toString();
    }

    private android.content.SharedPreferences prefs() {
        return activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private void clearPendingDownload() {
        long id = prefs().getLong("download_id", -1L);
        if (id >= 0L && downloadManager != null) downloadManager.remove(id);
        prefs().edit().remove("download_id").remove("download_version")
                .remove("download_version_name").remove("download_sha256")
                .putBoolean("install_launched", false)
                .putBoolean("needs_install_permission", false).apply();
    }

    private void show(String message) {
        if (activity.isFinishing()) return;
        activity.runOnUiThread(() -> Toast.makeText(activity, message, Toast.LENGTH_LONG).show());
    }

    public void close() {
        if (receiverRegistered) {
            try {
                activity.unregisterReceiver(downloadReceiver);
            } catch (IllegalArgumentException ignored) {
                // Already unregistered by Android.
            }
            receiverRegistered = false;
        }
        executor.shutdownNow();
    }
}
