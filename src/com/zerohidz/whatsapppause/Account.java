package com.zerohidz.whatsapppause;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;
import org.json.JSONObject;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Server calls on the account level. Every method blocks: call it from an executor. */
final class Account {
    static final class Response {
        final int status; final JSONObject body;
        Response(int status, JSONObject body) { this.status = status; this.body = body; }
    }
    private static AccountStore store;

    private Account() {}

    @SuppressLint("HardwareIds")
    static synchronized AccountStore store(Context context) {
        if (store == null) {
            SharedPreferences preferences = context.getApplicationContext().getSharedPreferences("account", Context.MODE_PRIVATE);
            String androidId = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
            store = new AccountStore(new AccountStore.Saved() {
                public String get() { return preferences.getString("token", null); }
                public void put(String token) { preferences.edit().putString("token", token).commit(); }
            }, SyncConfig.TOKEN, AccountStore.install(androidId == null ? "" : androidId));
        }
        return store;
    }

    /** A call with this device's token; registers first if needed and once more after a 401. */
    static Response authorized(Context context, String method, String path, JSONObject body) throws IOException {
        AccountStore accounts = store(context);
        for (int tries = 0; ; tries++) {
            String token = accounts.token(Account::register);
            Response response = call(method, path, token, body);
            if (response.status != 401 || tries > 0) return response;
            accounts.rejected(token);
        }
    }
    static Response pairCode(Context context) throws IOException {
        return authorized(context, "POST", "/v1/pair-code", new JSONObject());
    }
    /** Moves this device to the account of a code shown by another device. */
    static Response pair(Context context, String code) throws IOException {
        AccountStore accounts = store(context);
        try {
            Response response = call("POST", "/v1/pair", null,
                new JSONObject().put("code", code).put("kind", "android").put("install", accounts.install()));
            if (response.status == 200) accounts.replace(response.body.getString("token"));
            return response;
        } catch (org.json.JSONException error) { throw new IOException(error); }
    }
    /** The last guard the server reported, from prefs. A local-only build never has one. */
    static LockRules.Guard guard(Context context) {
        if (!SyncConfig.ENABLED) return new LockRules.Guard(null, false, false, 0, false, false);
        SharedPreferences saved = context.getApplicationContext().getSharedPreferences("guard", Context.MODE_PRIVATE);
        return new LockRules.Guard(saved.getString("guardian", null), saved.getBoolean("pendingGuardian", false),
            saved.getBoolean("locked", false), saved.getLong("releasedUntil", 0),
            saved.getBoolean("pendingRelease", false), saved.getBoolean("mailError", false));
    }
    static boolean locked(Context context) { return guard(context).locked(System.currentTimeMillis()); }
    /** Saves a server guard object; anything malformed keeps the last one. */
    static void rememberGuard(Context context, JSONObject guard) {
        if (guard == null || !guard.has("locked")) return;
        context.getApplicationContext().getSharedPreferences("guard", Context.MODE_PRIVATE).edit()
            .putString("guardian", guard.isNull("guardian") ? null : guard.optString("guardian", null))
            .putBoolean("pendingGuardian", guard.optBoolean("pendingGuardian"))
            .putBoolean("locked", guard.optBoolean("locked"))
            .putLong("releasedUntil", guard.optLong("releasedUntil"))
            .putBoolean("pendingRelease", guard.optBoolean("pendingRelease"))
            .putBoolean("mailError", guard.optBoolean("mailError"))
            .commit();
    }
    private static String register(String install) throws IOException {
        try {
            Response response = call("POST", "/v1/register", null, new JSONObject().put("kind", "android").put("install", install));
            if (response.status != 200) throw new IOException("HTTP " + response.status);
            return response.body.getString("token");
        } catch (org.json.JSONException error) { throw new IOException(error); }
    }

    static Response call(String method, String path, String token, JSONObject body) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(SyncConfig.URL + path).openConnection();
        try {
            connection.setConnectTimeout(10000); connection.setReadTimeout(10000);
            connection.setInstanceFollowRedirects(false);
            if (token != null) connection.setRequestProperty("Authorization", "Bearer " + token);
            connection.setRequestMethod(method);
            if (body != null) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json");
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(bytes.length);
                try (java.io.OutputStream out = connection.getOutputStream()) { out.write(bytes); }
            }
            int status = connection.getResponseCode();
            java.io.InputStream input = status < 400 ? connection.getInputStream() : connection.getErrorStream();
            JSONObject json = new JSONObject();
            if (input != null) try (java.io.InputStream in = input) {
                java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
                byte[] buffer = new byte[1024]; int size;
                while ((size = in.read(buffer)) != -1) {
                    bytes.write(buffer, 0, size);
                    if (bytes.size() > 8192) throw new IOException("Response too large");
                }
                if (bytes.size() > 0) json = new JSONObject(bytes.toString("UTF-8"));
            } catch (org.json.JSONException error) {
                if (status == 200) throw new IOException(error);
            }
            return new Response(status, json);
        } finally { connection.disconnect(); }
    }
}
