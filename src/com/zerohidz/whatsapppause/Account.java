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
    /** The last extra limit the server reported, from prefs. A local-only build never has one. */
    static ExtraLimit extra(Context context) {
        if (!SyncConfig.ENABLED) return new ExtraLimit(0, 0, false);
        SharedPreferences saved = context.getApplicationContext().getSharedPreferences("extra", Context.MODE_PRIVATE);
        return new ExtraLimit(saved.getLong("allUntil", 0), saved.getLong("videoUntil", 0), saved.getBoolean("endPending", false));
    }
    /** Saves the extra fields of a server answer; an answer without them keeps the last ones. */
    static void rememberExtra(Context context, JSONObject answer) {
        if (answer == null || !answer.has("extraAllUntil")) return;
        context.getApplicationContext().getSharedPreferences("extra", Context.MODE_PRIVATE).edit()
            .putLong("allUntil", answer.optLong("extraAllUntil"))
            .putLong("videoUntil", answer.optLong("extraVideoUntil"))
            .putBoolean("endPending", answer.optBoolean("extraEndPending"))
            .commit();
    }
    /** The rules in force as the server last reported them; DEFAULT in a local-only build or before any answer. */
    static Rules rules(Context context) {
        if (!SyncConfig.ENABLED) return Rules.defaults();
        String saved = context.getApplicationContext().getSharedPreferences("rules", Context.MODE_PRIVATE).getString("current", null);
        if (saved != null) try { return rulesFrom(new JSONObject(saved)); } catch (Exception ignored) { }
        return Rules.defaults();
    }
    /** Saves the rules of a server answer: quota answers carry rules/rulesVersion, /v1/rules carries current. Malformed keeps the last. */
    static void rememberRules(Context context, JSONObject answer) {
        if (answer == null) return;
        JSONObject current = answer.optJSONObject("current");
        JSONObject rules = current != null ? current.optJSONObject("rules") : answer.optJSONObject("rules");
        if (rules == null) return;
        try { rulesFrom(rules); } catch (Exception error) { return; }
        context.getApplicationContext().getSharedPreferences("rules", Context.MODE_PRIVATE).edit()
            .putString("current", rules.toString())
            .putInt("version", current != null ? current.optInt("version") : answer.optInt("rulesVersion"))
            .commit();
    }
    static Response rulesView(Context context) throws IOException {
        Response response = authorized(context, "GET", "/v1/rules", null);
        if (response.status == 200) rememberRules(context, response.body);
        return response;
    }
    /** Saves a rule set, or with dryRun only asks what it would change. */
    static Response saveRules(Context context, Rules rules, int baseVersion, boolean dryRun) throws IOException {
        try {
            JSONObject body = new JSONObject().put("rules", rulesJson(rules)).put("baseVersion", baseVersion);
            if (dryRun) body.put("dryRun", true);
            Response response = authorized(context, "POST", "/v1/rules", body);
            if (response.status == 200 && !dryRun) rememberRules(context, response.body);
            return response;
        } catch (org.json.JSONException error) { throw new IOException(error); }
    }
    static Response cancelRules(Context context) throws IOException {
        Response response = authorized(context, "POST", "/v1/rules-cancel", new JSONObject());
        if (response.status == 200) rememberRules(context, response.body);
        return response;
    }
    private static int time(JSONObject json, String key) throws org.json.JSONException {
        return json.isNull(key) ? Rules.NONE : Rules.parse(json.getString(key));
    }
    private static Object wireTime(int time) { return time == Rules.NONE ? JSONObject.NULL : Rules.wire(time); }
    /** The §4.1 JSON to Rules; a missing key, wrong type or invalid value throws. */
    static Rules rulesFrom(JSONObject json) throws org.json.JSONException {
        Rules rules = new Rules();
        rules.rights = json.getInt("rights"); rules.windowMin = json.getInt("windowMin"); rules.waitSec = json.getInt("waitSec");
        rules.renewal = Rules.parse(json.getString("renewal")); rules.nightStart = Rules.parse(json.getString("nightStart"));
        rules.nightWindowMin = json.getInt("nightWindowMin");
        rules.urgentCount = json.getInt("urgentCount"); rules.urgentMin = json.getInt("urgentMin");
        JSONObject video = json.getJSONObject("video");
        Rules.Day[] days = new Rules.Day[2];
        for (int i = 0; i < 2; i++) {
            JSONObject day = video.getJSONObject(Rules.DAYS[i]);
            days[i] = new Rules.Day(time(day, "closedFrom"), Rules.parse(day.getString("closedUntil")),
                time(day, "meteredFrom"), time(day, "meteredUntil"), day.getInt("budgetMin"));
        }
        rules.weekday = days[0]; rules.weekend = days[1];
        JSONObject work = json.getJSONObject("work");
        rules.workEnabled = work.getBoolean("enabled");
        org.json.JSONArray minutes = work.getJSONArray("minutes");
        rules.workMinutes = new int[minutes.length()];
        for (int i = 0; i < minutes.length(); i++) rules.workMinutes[i] = minutes.getInt(i);
        rules.workDailyMin = work.getInt("dailyMin");
        rules.workSummary = work.getBoolean("summary");
        String invalid = rules.invalid();
        if (invalid != null) throw new org.json.JSONException("invalid " + invalid);
        return rules;
    }
    static JSONObject rulesJson(Rules rules) throws org.json.JSONException {
        JSONObject video = new JSONObject();
        for (String key : Rules.DAYS) {
            Rules.Day day = rules.day(key);
            video.put(key, new JSONObject().put("closedFrom", wireTime(day.closedFrom)).put("closedUntil", Rules.wire(day.closedUntil))
                .put("meteredFrom", wireTime(day.meteredFrom)).put("meteredUntil", wireTime(day.meteredUntil)).put("budgetMin", day.budgetMin));
        }
        org.json.JSONArray minutes = new org.json.JSONArray();
        for (int value : rules.workMinutes) minutes.put(value);
        return new JSONObject().put("rights", rules.rights).put("windowMin", rules.windowMin).put("waitSec", rules.waitSec)
            .put("renewal", Rules.wire(rules.renewal)).put("nightStart", Rules.wire(rules.nightStart)).put("nightWindowMin", rules.nightWindowMin)
            .put("urgentCount", rules.urgentCount).put("urgentMin", rules.urgentMin).put("video", video)
            .put("work", new JSONObject().put("enabled", rules.workEnabled).put("minutes", minutes)
                .put("dailyMin", rules.workDailyMin).put("summary", rules.workSummary));
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
