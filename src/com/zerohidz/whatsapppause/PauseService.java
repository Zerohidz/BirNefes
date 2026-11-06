package com.zerohidz.whatsapppause;

import android.accessibilityservice.AccessibilityService;
import android.app.KeyguardManager;
import android.content.*;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.media.AudioManager;
import android.os.*;
import android.view.*;
import android.view.accessibility.AccessibilityEvent;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;

public final class PauseService extends AccessibilityService {
    static PauseService instance;
    private DelayGate gate;
    private final WindowEventGate eventGate = new WindowEventGate();
    private final CallScreenGate calls = new CallScreenGate();
    private CallWatcher callWatcher;
    private AudioManager audio;
    private AppPolicy policy;
    private long policyRefresh;
    private RemoteGate remote;
    private SharedPreferences preferences;
    private String savedState = "";
    private DelayGate.State overlayState;
    private long connectingGraceUntil;
    // Leaves room for the normal window event of the app returning after a call.
    private static final long AFTER_CALL_MS = 1500;
    // Longer than the call screen's window event after an answer, about 0.9 s when measured.
    private static final long AFTER_ANSWER_MS = 2500;
    private final Runnable afterAnswer = () -> {
        boolean locked = ((KeyguardManager) getSystemService(KEYGUARD_SERVICE)).isKeyguardLocked();
        if (calls.recheckAfterAnswer(locked) && currentState() == DelayGate.State.AWAY) enterRestricted();
    };
    private final Runnable afterCall = () -> {
        boolean locked = ((KeyguardManager) getSystemService(KEYGUARD_SERVICE)).isKeyguardLocked();
        if (calls.recheckAfterCall(locked, callActive()) && currentState() == DelayGate.State.AWAY) enterRestricted();
    };
    private final Handler handler = new Handler(Looper.getMainLooper());
    private WindowManager windows;
    private ScrollView overlay;
    private TextView countdown;
    private final BroadcastReceiver screenOff = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            String action = i.getAction();
            boolean call = callActive();
            if (Intent.ACTION_SCREEN_ON.equals(action)) {
                if (calls.screenOn(((KeyguardManager) getSystemService(KEYGUARD_SERVICE)).isKeyguardLocked(), call)) goHome();
                return;
            }
            if (Intent.ACTION_SCREEN_OFF.equals(action)) calls.screenOff(currentState() != DelayGate.State.AWAY);
            leave();
            if (Intent.ACTION_USER_PRESENT.equals(action) && !call) performGlobalAction(GLOBAL_ACTION_HOME);
        }
    };
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (currentState() == DelayGate.State.AWAY) return;
            if (remote != null) { remote.tick(SystemClock.elapsedRealtime()); render(remote.state()); }
            else render(gate.tick(System.currentTimeMillis(), SystemClock.elapsedRealtime(), java.time.ZoneId.systemDefault()));
            save();
            if (currentState() != DelayGate.State.AWAY) handler.postDelayed(this, 200);
        }
    };
    @Override protected void onServiceConnected() {
        instance = this;
        policy = new AppPolicy(this);
        preferences = getSharedPreferences("usage", MODE_PRIVATE);
        gate = new DelayGate(preferences.getString("day", ""), preferences.getInt("used", 0),
            preferences.getBoolean("nightUsed", false), preferences.getLong("end", 0));
        if (SyncConfig.ENABLED) remote = new RemoteGate(this);
        windows = (WindowManager) getSystemService(WINDOW_SERVICE);
        audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (callWatcher == null) callWatcher = new CallWatcher(this, this::onCallState);
        callWatcher.start();
        IntentFilter screenFilter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        screenFilter.addAction(Intent.ACTION_USER_PRESENT);
        screenFilter.addAction(Intent.ACTION_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenOff, screenFilter, RECEIVER_EXPORTED);
        else registerReceiver(screenOff, screenFilter);
        // A reconnect cannot reliably identify an already open foreground window
        // without content access. Return home so the next entry is checked.
        if ((remote != null || gate.end() > 0) && !callActive()) performGlobalAction(GLOBAL_ACTION_HOME);
    }
    /** Lets MainActivity attach the watcher to a running service after the permission result. */
    public void refreshCallWatcher() { if (callWatcher != null) callWatcher.start(); }
    private void onCallState(int state) {
        boolean changed = calls.onCallState(state);
        // The call screen's window event can lag behind this report, and a blocker drawn
        // now would cover the answered call. Returning to the app starts a new check.
        if (calls.answered() && currentState() != DelayGate.State.ALLOWED) {
            leave();
            handler.removeCallbacks(afterAnswer);
            handler.postDelayed(afterAnswer, AFTER_ANSWER_MS);
        }
        else if (changed) render(currentState());
        if (calls.ended()) { handler.removeCallbacks(afterCall); handler.postDelayed(afterCall, AFTER_CALL_MS); }
    }
    private boolean audioCall() {
        int mode = audio == null ? AudioManager.MODE_NORMAL : audio.getMode();
        return mode == AudioManager.MODE_RINGTONE || mode == AudioManager.MODE_IN_CALL;
    }
    private boolean callActive() { return calls.callActive(audioCall()); }
    private boolean overlaySuppressed() {
        return calls.suppressOverlay((callWatcher == null || !callWatcher.active()) && audioCall());
    }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (gate == null) return;
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || event.getPackageName() == null) return;
        // Pane appearance/disappearance is not an app foreground transition.
        if (event.getContentChangeTypes() != 0) return;
        String pkg = event.getPackageName().toString();
        // Only package identity is read. No event text, root nodes, screenshots or logging.
        if (pkg.equals(getPackageName())) {
            // Any of our own Activities, not the overlay or a dialog window.
            if (policy.activityWindow(pkg, event.getClassName())) {
                calls.window(false);
                if (eventGate.allowedWindow(event.getEventTime())) leave();
            }
            return;
        }
        if (pkg.equals("com.android.systemui")) return;
        InputMethodManager input = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        for (android.view.inputmethod.InputMethodInfo method : input.getEnabledInputMethodList())
            if (pkg.equals(method.getPackageName())) return;
        // Guardian lock: the windows that would switch Bir Nefes off are covered, by class name only.
        if (LockRules.covers(pkg, event.getClassName()) && Account.locked(this)) {
            if (eventGate.restrictedWindow(event.getEventTime(), true)) showLock();
            return;
        }
        if (SystemClock.elapsedRealtime() >= policyRefresh) { policy.refresh(); policyRefresh = SystemClock.elapsedRealtime() + 5000; }
        if (policy.allowed(pkg) || (policy.picker(pkg) && PickerPass.active(SystemClock.elapsedRealtime()))) {
            if (policy.callScreen(pkg)) calls.callScreenWindow(); else calls.window(false);
            if (eventGate.allowedWindow(event.getEventTime())) leave();
            return;
        }
        if (!eventGate.restrictedWindow(event.getEventTime(), policy.activityWindow(pkg, event.getClassName()))) return;
        calls.window(true);
        enterRestricted();
    }
    /** A fresh entry is checked quietly; the blocker waits this long for the server's answer. */
    private long checkDelay() {
        return CheckDelay.normalize(getSharedPreferences(CheckDelay.PREFERENCES, MODE_PRIVATE).getLong(CheckDelay.KEY, CheckDelay.DEFAULT_MS));
    }
    private void enterRestricted() {
        if (remote != null) {
            // Only a real AWAY entry starts the grace, so app switching cannot keep extending it.
            if (remote.state() == DelayGate.State.AWAY) connectingGraceUntil = SystemClock.elapsedRealtime() + checkDelay();
            remote.enter();
            render(remote.state());
        }
        else render(gate.enter(System.currentTimeMillis(), SystemClock.elapsedRealtime(), java.time.ZoneId.systemDefault()));
        save();
        handler.removeCallbacks(tick);
        handler.post(tick);
    }
    private TextView label(String text, int size) {
        TextView view = new TextView(this);
        view.setText(text); view.setTextSize(size); view.setTextColor(Color.WHITE);
        view.setGravity(Gravity.CENTER); view.setPadding(16, 12, 16, 12);
        return view;
    }
    private DelayGate.State currentState() { return remote == null ? gate.state() : remote.state(); }
    private void save() {
        if (remote != null) return;
        String value = gate.day() + ":" + gate.used() + ":" + gate.nightUsed() + ":" + gate.end();
        if (value.equals(savedState)) return;
        if (preferences.edit().putString("day", gate.day()).putInt("used", gate.used())
                .putBoolean("nightUsed", gate.nightUsed()).putLong("end", gate.end()).commit()) savedState = value;
    }
    private void goHome() {
        // Set the guard before detaching our overlay: its removal can expose
        // the old app briefly while Android is still switching to the launcher.
        eventGate.returningHome(SystemClock.uptimeMillis());
        calls.window(false);
        leave();
        performGlobalAction(GLOBAL_ACTION_HOME);
    }
    private void leave() {
        if (remote != null) remote.leave();
        else if (gate != null) { gate.leave(); save(); }
        handler.removeCallbacks(tick);
        removeOverlay();
    }
    private void render(DelayGate.State state) {
        if (state == DelayGate.State.ALLOWED || state == DelayGate.State.AWAY || overlaySuppressed()) { removeOverlay(); return; }
        if (state == DelayGate.State.CONNECTING && overlay == null && SystemClock.elapsedRealtime() < connectingGraceUntil) return;
        if (overlay == null || overlayState != state) showOverlay(state);
        if (state == DelayGate.State.WAITING && countdown != null)
            countdown.setText(String.valueOf(((remote == null ? gate.remaining(SystemClock.elapsedRealtime()) : remote.remaining(SystemClock.elapsedRealtime())) + 999) / 1000));
    }
    /** The guardian lock screen. It never touches the quota: no right is used and no timer runs. */
    private void showLock() {
        leave();
        LinearLayout content = openOverlay(null);
        content.addView(label("Bu ayar kilitli.", 32));
        content.addView(label("Kardeşinden izin iste.", 18));
        Button home = Ui.primary(this, "Ana sayfaya dön");
        home.setOnClickListener(v -> goHome());
        content.addView(home);
        Button ask = Ui.secondary(this, "İzin iste");
        ask.setOnClickListener(v -> {
            leave();
            startActivity(new Intent(this, MainActivity.class).putExtra(MainActivity.ASK_RELEASE, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        });
        content.addView(ask);
        attachOverlay();
    }
    /** A fresh, empty blocker; state is null for the lock screen. */
    private LinearLayout openOverlay(DelayGate.State state) {
        removeOverlay();
        overlayState = state;
        overlay = new ScrollView(this);
        overlay.setFillViewport(true);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER);
        int side = Ui.dp(this, 24);
        content.setPadding(side, side, side, side);
        overlay.addView(content);
        overlay.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = Build.VERSION.SDK_INT >= 30
                ? insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout())
                : insets.getSystemWindowInsets();
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        overlay.setBackgroundColor(Color.rgb(10, 20, 27));
        return content;
    }
    private void showOverlay(DelayGate.State state) {
        LinearLayout content = openOverlay(state);
        boolean waiting = state == DelayGate.State.WAITING;
        boolean connecting = state == DelayGate.State.CONNECTING;
        boolean offline = state == DelayGate.State.ERROR;
        content.addView(label(connecting ? "Hakların kontrol ediliyor…" : offline ? "Sunucuya ulaşılamadı." : waiting ? "Bir nefes al." : state == DelayGate.State.EXPIRED
            ? "Kullanım süren doldu." : "Bugünkü hakkın bitti.", 32));
        content.addView(label(connecting ? "Telefon ve bilgisayar aynı hakkı paylaşır." : offline ? "Bağlantını kontrol edip yeniden gir." : waiting ? "Şu an bu uygulamaya neden giriyorsun?"
            : "Ekrandan ayrılma zamanı.", 18));
        countdown = null;
        if (waiting) {
            countdown = label("30", 80);
            countdown.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            content.addView(countdown);
        }
        content.addView(label(connecting ? "Birazdan devam edeceksin." : offline ? "Bağlantı kurulmadan yeni kullanım hakkı açılamaz." : waiting ? "30 saniyelik mola bitince otomatik devam edeceksin."
            : state == DelayGate.State.EXPIRED ? "Ana sayfaya dön. Kalan hakkın varsa yeniden girişte yeni mola başlar."
            : "Hakların her gün 03.00’te yenilenir.", 16));
        Button home = Ui.primary(this, "Ana sayfaya dön");
        home.setOnClickListener(v -> goHome());
        content.addView(home);
        if (remote != null && !connecting && remote.emergencyRemaining() != 0) {
            Button urgent = Ui.secondary(this, "Acele geçişi · 20 dakika" + (remote.emergencyRemaining() >= 0 ? " · " + remote.emergencyRemaining() + "/4 kaldı" : ""));
            urgent.setOnClickListener(v -> {
                urgent.setText("Aylık 1 hakkı kullan ve aç");
                urgent.setOnClickListener(confirm -> { remote.emergency(); render(remote.state()); });
            });
            content.addView(urgent);
            content.addView(label("Günlük hakkından düşmez. Ayda 4 kez; ayın 1’inde 03.00’te yenilenir. İnternet gerekir.", 14));
        }
        attachOverlay();
    }
    private void attachOverlay() {
        overlay.setFocusableInTouchMode(true);
        overlay.setOnKeyListener((v, key, event) -> {
            if (key == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) {
                goHome(); return true;
            }
            return false;
        });
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.OPAQUE);
        params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        if (Build.VERSION.SDK_INT >= 30) params.setFitInsetsTypes(0);
        try { windows.addView(overlay, params); overlay.requestFocus(); }
        catch (RuntimeException error) {
            android.util.Log.e("BirNefes", "Blocking overlay could not be displayed", error);
            overlay = null;
            goHome();
        }
    }
    private void removeOverlay() {
        if (overlay != null) {
            try { windows.removeView(overlay); } catch (IllegalArgumentException ignored) { /* Already detached by system. */ }
            overlay = null;
        }
        countdown = null;
    }
    @Override public void onInterrupt() { leave(); }
    @Override public void onDestroy() {
        if (callWatcher != null) callWatcher.stop();
        handler.removeCallbacks(afterCall);
        handler.removeCallbacks(afterAnswer);
        leave(); if (remote != null) remote.close(); instance = null;
        try { unregisterReceiver(screenOff); } catch (IllegalArgumentException ignored) { /* Service was not connected. */ }
        super.onDestroy();
    }
}
