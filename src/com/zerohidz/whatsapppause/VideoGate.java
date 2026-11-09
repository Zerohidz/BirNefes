package com.zerohidz.whatsapppause;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import org.json.JSONObject;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The phone's client for the shared video budget (the same /v1/video-* operations as Chrome).
 * A metered grant is a five-second lease renewed every two seconds while the app is in front;
 * leaving sends a stop so the budget pauses.
 */
public final class VideoGate {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Context context;
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    VideoGate(Context context) { this.context = context.getApplicationContext(); }
    private DelayGate.State state = DelayGate.State.AWAY;
    private String mode = "", attempt;
    private long deadline, budgetEnd, pollAt, generation, limitUntil, serverOffset;
    private int emergencyRemaining = -1;
    private boolean busy;

    public DelayGate.State state() { return state; }
    /** free, metered, emergency, work, night, exhausted or limited; empty before the first answer. */
    public String mode() { return mode; }
    public int emergencyRemaining() { return emergencyRemaining; }
    /** Epoch ms on this device's clock when an extra limit ends; 0 = none. */
    public long limitUntil() { return limitUntil == 0 ? 0 : limitUntil - serverOffset; }
    /** Elapsed-realtime end for the countdown notification; 0 in free hours, which need none. */
    public long clockEnd() { return state != DelayGate.State.ALLOWED || mode.equals("free") ? 0 : budgetEnd; }

    public void enter() {
        if (state != DelayGate.State.AWAY) return;
        generation++;
        attempt = UUID.randomUUID().toString();
        state = DelayGate.State.CONNECTING;
        request("video-enter");
    }
    public void leave() {
        if (state == DelayGate.State.AWAY) return;
        String previous = attempt;
        generation++; busy = false; attempt = null; state = DelayGate.State.AWAY;
        if (previous != null) network.execute(() -> {
            try { Account.authorized(context, "POST", "/v1/video-stop", new JSONObject().put("attempt", previous)); }
            catch (Exception error) { android.util.Log.w("BirNefes", "Video stop failed: " + error.getClass().getSimpleName()); }
        });
    }
    public void emergency() {
        if (busy || state == DelayGate.State.ALLOWED || state == DelayGate.State.AWAY) return;
        state = DelayGate.State.CONNECTING;
        request("video-emergency");
    }
    public void close() { leave(); network.shutdown(); main.removeCallbacksAndMessages(null); }
    public void tick(long elapsed) {
        if (state == DelayGate.State.AWAY) return;
        if (state == DelayGate.State.ALLOWED && elapsed >= deadline) state = DelayGate.State.CONNECTING;
        if (!busy && elapsed >= pollAt) request("video-tick");
    }

    private void request(String operation) {
        busy = true;
        final long requestGeneration = generation;
        final long sent = SystemClock.elapsedRealtime();
        final String requestAttempt = attempt;
        network.execute(() -> {
            JSONObject response = null;
            for (int tries = 0; tries < 2 && response == null; tries++) {
                try {
                    Account.Response reply = Account.authorized(context, "POST", "/v1/" + operation, new JSONObject().put("attempt", requestAttempt));
                    if (reply.status != 200) throw new java.io.IOException("HTTP " + reply.status);
                    response = reply.body;
                    Account.rememberExtra(context, response);
                } catch (Exception error) {
                    android.util.Log.w("BirNefes", "Video connection failed: " + error.getClass().getSimpleName());
                    response = null;
                }
            }
            final JSONObject result = response;
            main.post(() -> {
                if (generation != requestGeneration || state == DelayGate.State.AWAY) return;
                busy = false;
                long elapsed = SystemClock.elapsedRealtime();
                pollAt = elapsed + 5000;
                if (result == null) {
                    if (state != DelayGate.State.ALLOWED || elapsed >= deadline) state = DelayGate.State.ERROR;
                    return;
                }
                try {
                    mode = result.getString("mode");
                    emergencyRemaining = result.optInt("emergencyRemaining", -1);
                    long serverNow = result.getLong("serverNow");
                    serverOffset = serverNow - System.currentTimeMillis();
                    limitUntil = Math.max(result.optLong("extraAllUntil"), result.optLong("extraVideoUntil"));
                    if (result.getString("state").equals("allowed")) {
                        long left = Math.max(0, result.getLong("expiresAt") - serverNow - (elapsed - sent));
                        deadline = elapsed + left;
                        // The lease is short; the clock shows what is left of the day's budget.
                        budgetEnd = mode.equals("metered") ? elapsed + Math.max(0, result.optLong("videoRemainingMs")) : deadline;
                        if (mode.equals("metered")) pollAt = elapsed + 2000;
                        state = left > 0 ? DelayGate.State.ALLOWED : DelayGate.State.CONNECTING;
                    } else state = DelayGate.State.EXHAUSTED;
                } catch (org.json.JSONException error) {
                    state = DelayGate.State.ERROR;
                }
            });
        });
    }
}
