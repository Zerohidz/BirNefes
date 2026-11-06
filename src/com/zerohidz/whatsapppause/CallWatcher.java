package com.zerohidz.whatsapppause;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;
import java.util.function.IntConsumer;

/** Delivers call states on the main thread. The number is never read or kept. */
final class CallWatcher {
    private final Context context;
    private final TelephonyManager telephony;
    private final IntConsumer listener;
    // Typed as Object: TelephonyCallback does not exist below API 31.
    private Object registered;

    CallWatcher(Context context, IntConsumer listener) {
        this.context = context;
        this.listener = listener;
        telephony = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
    }
    boolean active() { return registered != null; }
    void start() {
        if (registered != null || telephony == null) return;
        if (context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return;
        try {
            registered = Build.VERSION.SDK_INT >= 31 ? Modern.register(context, telephony, listener) : Legacy.register(telephony, listener);
            // Known synchronously so a reconnect during a call does not go Home.
            listener.accept(telephony.getCallState());
        } catch (SecurityException denied) {
            android.util.Log.w("BirNefes", "Call state unavailable: " + denied.getClass().getSimpleName());
        }
    }
    void stop() {
        if (registered == null) return;
        try {
            if (Build.VERSION.SDK_INT >= 31) Modern.unregister(telephony, registered);
            else Legacy.unregister(telephony, registered);
        } catch (RuntimeException failed) {
            android.util.Log.w("BirNefes", "Call state stop failed: " + failed.getClass().getSimpleName());
        }
        registered = null;
    }

    private static final class Modern extends TelephonyCallback implements TelephonyCallback.CallStateListener {
        private final IntConsumer listener;
        private Modern(IntConsumer listener) { this.listener = listener; }
        static Object register(Context context, TelephonyManager telephony, IntConsumer listener) {
            Modern callback = new Modern(listener);
            telephony.registerTelephonyCallback(context.getMainExecutor(), callback);
            return callback;
        }
        static void unregister(TelephonyManager telephony, Object callback) {
            telephony.unregisterTelephonyCallback((TelephonyCallback) callback);
        }
        @Override public void onCallStateChanged(int state) { listener.accept(state); }
    }

    // Built on the main thread, whose Looper then receives the callbacks.
    private static final class Legacy extends PhoneStateListener {
        private final IntConsumer listener;
        private Legacy(IntConsumer listener) { this.listener = listener; }
        static Object register(TelephonyManager telephony, IntConsumer listener) {
            Legacy callback = new Legacy(listener);
            telephony.listen(callback, LISTEN_CALL_STATE);
            return callback;
        }
        static void unregister(TelephonyManager telephony, Object callback) {
            telephony.listen((PhoneStateListener) callback, LISTEN_NONE);
        }
        @Override public void onCallStateChanged(int state, String ignoredNumber) { listener.accept(state); }
    }
}
