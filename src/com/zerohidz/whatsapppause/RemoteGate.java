package com.zerohidz.whatsapppause;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import org.json.JSONObject;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** HTTPS quota client. Network work never runs on the accessibility thread. */
public final class RemoteGate {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Context context;
    public RemoteGate(Context context) { this.context = context.getApplicationContext(); }
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private DelayGate.State state = DelayGate.State.AWAY;
    private long deadline, pollAt, generation, session;
    private boolean busy;
    private int emergencyRemaining = -1;
    public int emergencyRemaining() { return emergencyRemaining; }
    private int used = -1, rights = -1;
    /** Rights already used today and rights still open, from the last server answer; -1 if unknown. */
    public int used() { return used; }
    public int rights() { return rights; }
    private long extraAllUntil, extraVideoUntil;
    private boolean extraEndPending;
    /** Extra limit ends from the last server answer, epoch ms on the server clock; 0 = none. */
    public long extraAllUntil() { return extraAllUntil; }
    public long extraVideoUntil() { return extraVideoUntil; }
    public boolean extraEndPending() { return extraEndPending; }
    /** Whether the "all" extra limit still holds, judged on the server clock. */
    public boolean extraAllActive() { return extraAllUntil > System.currentTimeMillis() + serverOffset; }
    private long serverOffset;
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
            try {
                Account.Response reply = Account.authorized(context, operation.equals("status") ? "GET" : "POST", "/v1/" + operation,
                    operation.equals("status") ? null : new JSONObject().put("attempt", requestAttempt));
                if (reply.status != 200) throw new java.io.IOException("HTTP " + reply.status);
                response = reply.body;
                // Status also carries the guardian lock; keep it for the service and the main screen.
                if (operation.equals("status")) Account.rememberGuard(context, response.optJSONObject("guard"));
                // Every answer carries the extra limit; the main screen reads the saved copy.
                Account.rememberExtra(context, response);
            } catch (Exception error) {
                // Log only the exception class: no credentials, headers, or body.
                android.util.Log.w("BirNefes", "Quota connection failed: " + error.getClass().getSimpleName());
                response = null;
            }
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
                    used = result.optInt("used", -1);
                    rights = result.optInt("remaining", -1);
                    extraAllUntil = result.optLong("extraAllUntil");
                    extraVideoUntil = result.optLong("extraVideoUntil");
                    extraEndPending = result.optBoolean("extraEndPending");
                    String value = result.getString("state");
                    long serverNow = result.getLong("serverNow");
                    serverOffset = serverNow - System.currentTimeMillis();
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
