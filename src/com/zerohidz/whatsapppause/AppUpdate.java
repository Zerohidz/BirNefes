package com.zerohidz.whatsapppause;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Environment;
import android.provider.Settings;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Asks the download site whether a newer APK is out, and installs it on request. */
final class AppUpdate {
    static final String SITE = "https://birnefes.zerohidz.com";
    /** Always the published APK, so a cached answer never points at a removed file. */
    static final String APK = SITE + "/BirNefes-son.apk";
    private static final String PREFERENCES = "update";
    private static final long CHECK_EVERY_MS = 60 * 60 * 1000L;
    private AppUpdate() {}

    static final class Release {
        final long code; final String name;
        Release(long code, String name) { this.code = code; this.name = name; }
    }

    static long installedCode(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return info.getLongVersionCode();
        } catch (Exception error) { return Long.MAX_VALUE; }
    }

    /** The last known release if it is newer than this install, else null. No network. */
    static Release cached(Context context) {
        android.content.SharedPreferences saved = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
        long code = saved.getLong("code", 0);
        return code > installedCode(context) ? new Release(code, saved.getString("name", "")) : null;
    }

    /** Runs on a background thread; checks the site at most once an hour. */
    static Release check(Context context) {
        android.content.SharedPreferences saved = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        if (now - saved.getLong("checkedAt", 0) < CHECK_EVERY_MS) return cached(context);
        try {
            HttpURLConnection connection = (HttpURLConnection) new URL(SITE + "/surum.json").openConnection();
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(8000);
            connection.setUseCaches(false);
            if (connection.getResponseCode() != 200) return cached(context);
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            try (InputStream input = connection.getInputStream()) {
                byte[] buffer = new byte[2048];
                for (int read; (read = input.read(buffer)) > 0 && body.size() < 4096; ) body.write(buffer, 0, read);
            }
            org.json.JSONObject release = new org.json.JSONObject(body.toString("UTF-8"));
            saved.edit().putLong("checkedAt", now).putLong("code", release.getLong("versionCode"))
                .putString("name", release.getString("versionName")).apply();
        } catch (Exception error) {
            android.util.Log.w("BirNefes", "Update check failed: " + error.getClass().getSimpleName());
        }
        return cached(context);
    }

    /** Downloads the APK and opens the system installer. Returns a message for the screen. */
    static String install(Activity activity, Runnable done) {
        if (!activity.getPackageManager().canRequestPackageInstalls()) {
            activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + activity.getPackageName())));
            return "Bir Nefes’e uygulama yükleme izni ver, sonra yeniden Güncelle’ye bas.";
        }
        DownloadManager downloads = (DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);
        java.io.File old = new java.io.File(activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "BirNefes.apk");
        if (old.exists()) old.delete();
        long id = downloads.enqueue(new DownloadManager.Request(Uri.parse(APK))
            .setTitle("Bir Nefes güncellemesi")
            .setMimeType("application/vnd.android.package-archive")
            .setDestinationInExternalFilesDir(activity, Environment.DIRECTORY_DOWNLOADS, "BirNefes.apk"));
        Context app = activity.getApplicationContext();
        BroadcastReceiver finished = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) != id) return;
                app.unregisterReceiver(this);
                Uri file = downloads.getUriForDownloadedFile(id);
                if (file != null) app.startActivity(new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(file, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK));
                done.run();
            }
        };
        app.registerReceiver(finished, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), Context.RECEIVER_EXPORTED);
        return "İndiriliyor…";
    }
}
