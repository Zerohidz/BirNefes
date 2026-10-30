package com.zerohidz.whatsapppause;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import org.json.JSONObject;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** HTTPS quota client. Network work never runs on the accessibility thread. */
public final class RemoteGate {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private DelayGate.State state = DelayGate.State.AWAY;
    private long deadline, pollAt, generation, session;
    private boolean busy;
    private int emergencyRemaining = -1;
    public int emergencyRemaining() { return emergencyRemaining; }
    public void emergency() {
        if (busy || state == DelayGate.State.ALLOWED || state == DelayGate.State.AWAY) return;
        attempt = UUID.randomUUID().toString();
        state = DelayGate.State.CONNECTING;
        request("emergency");
    }
    private String attempt;
    public DelayGate.State state() { return state; }
    public long remaining(long elapsed) { return state == DelayGate.State.WAITING ? Math.max(0, deadline - elapsed) : 0; }
    public void enter() {
        if (state != DelayGate.State.AWAY) return;
        attempt = UUID.randomUUID().toString();
        state = DelayGate.State.CONNECTING;
        request("enter");
    }
    public void leave() { generation++; state = DelayGate.State.AWAY; busy = false; }
    public void close() { leave(); network.shutdownNow(); main.removeCallbacksAndMessages(null); }
    public void tick(long elapsed) {
        if (state == DelayGate.State.ALLOWED && elapsed >= deadline) state = DelayGate.State.EXPIRED;
        if (state == DelayGate.State.WAITING && elapsed >= deadline && !busy) {
            state = DelayGate.State.CONNECTING;
            request("complete");
        } else if (state == DelayGate.State.ALLOWED && elapsed >= pollAt && !busy) request("status");
    }
    private void request(String operation) {
        busy = true;
        final long requestGeneration = generation;
        final long sent = SystemClock.elapsedRealtime();
        final String requestAttempt = attempt;
        network.execute(() -> {
            JSONObject response = null;
            for (int tries = 0; tries < (operation.equals("status") ? 1 : 2) && response == null; tries++) {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(SyncConfig.URL + "/v1/" + operation).openConnection();
                connection.setConnectTimeout(10000); connection.setReadTimeout(10000);
                connection.setInstanceFollowRedirects(false);
                connection.setRequestProperty("Authorization", "Bearer " + SyncConfig.TOKEN);
                if (!operation.equals("status")) {
                    connection.setRequestMethod("POST"); connection.setDoOutput(true);
                    connection.setRequestProperty("Content-Type", "application/json");
                    byte[] body = new JSONObject().put("attempt", requestAttempt).toString().getBytes(StandardCharsets.UTF_8);
                    connection.setFixedLengthStreamingMode(body.length);
                    try (java.io.OutputStream out = connection.getOutputStream()) { out.write(body); }
                }
                int status = connection.getResponseCode();
                if (status != 200) throw new java.io.IOException("HTTP " + status);
                try (java.io.InputStream input = connection.getInputStream()) {
                    java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
                    byte[] buffer = new byte[1024]; int size;
                    while ((size = input.read(buffer)) != -1) {
                        bytes.write(buffer, 0, size);
                        if (bytes.size() > 8192) throw new java.io.IOException("Response too large");
                    }
                    response = new JSONObject(bytes.toString("UTF-8"));
                }
            } catch (Exception error) {
                // Log only the exception class: no credentials, headers, or body.
                android.util.Log.w("BirNefes", "Quota connection failed: " + error.getClass().getSimpleName());
                response = null;
            } finally { if (connection != null) connection.disconnect(); }
            }
            final JSONObject result = response;
            main.post(() -> {
                if (generation != requestGeneration || state == DelayGate.State.AWAY) return;
                busy = false;
                long elapsed = SystemClock.elapsedRealtime();
                pollAt = elapsed + 5000;
                if (operation.equals("status") && (state != DelayGate.State.ALLOWED || elapsed >= deadline)) {
                    state = DelayGate.State.EXPIRED; return;
                }
                if (result == null) {
                    if (state != DelayGate.State.ALLOWED) state = DelayGate.State.ERROR;
                    return;
                }
                try {
                    emergencyRemaining = result.optInt("emergencyRemaining", -1);
                    String value = result.getString("state");
                    long serverNow = result.getLong("serverNow");
                    long roundTrip = elapsed - sent;
                    if (value.equals("allowed")) {
                        long newSession = result.getLong("session");
                        long left = Math.max(0, result.getLong("expiresAt") - serverNow - roundTrip);
                        if (operation.equals("status") && session != newSession) { state = DelayGate.State.EXPIRED; return; }
                        deadline = operation.equals("status") ? Math.min(deadline, elapsed + left) : elapsed + left;
                        session = newSession;
                        state = left > 0 ? DelayGate.State.ALLOWED : DelayGate.State.EXPIRED;
                    } else if (value.equals("waiting")) {
                        // Do not subtract RTT here: waiting slightly longer avoids early completions.
                        deadline = elapsed + Math.max(0, result.getLong("readyAt") - serverNow);
                        state = DelayGate.State.WAITING;
                    } else state = (value.equals("exhausted") || value.equals("emergency_exhausted")) ? DelayGate.State.EXHAUSTED : DelayGate.State.EXPIRED;
                } catch (org.json.JSONException error) {
                    if (state != DelayGate.State.ALLOWED) state = DelayGate.State.ERROR;
                }
            });
        });
    }
}
