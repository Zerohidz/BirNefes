package com.zerohidz.whatsapppause;

import android.accessibilityservice.AccessibilityService;
import android.content.*;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.*;
import android.view.*;
import android.view.accessibility.AccessibilityEvent;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;

public final class PauseService extends AccessibilityService {
    static PauseService instance;
    private DelayGate gate;
    private final WindowEventGate eventGate = new WindowEventGate();
    private AppPolicy policy;
    private long policyRefresh;
    private RemoteGate remote;
    private SharedPreferences preferences;
    private String savedState = "";
    private DelayGate.State overlayState;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private WindowManager windows;
    private ScrollView overlay;
    private TextView countdown;
    private final BroadcastReceiver screenOff = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            leave();
            if (Intent.ACTION_USER_PRESENT.equals(i.getAction())) performGlobalAction(GLOBAL_ACTION_HOME);
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
        if (SyncConfig.ENABLED) remote = new RemoteGate();
        windows = (WindowManager) getSystemService(WINDOW_SERVICE);
        IntentFilter screenFilter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        screenFilter.addAction(Intent.ACTION_USER_PRESENT);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenOff, screenFilter, RECEIVER_EXPORTED);
        else registerReceiver(screenOff, screenFilter);
        // A reconnect cannot reliably identify an already open foreground window
        // without content access. Return home so the next entry is checked.
        if (remote != null || gate.end() > 0) performGlobalAction(GLOBAL_ACTION_HOME);
    }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (gate == null) return;
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || event.getPackageName() == null) return;
        // Pane appearance/disappearance is not an app foreground transition.
        if (event.getContentChangeTypes() != 0) return;
        String pkg = event.getPackageName().toString();
        // Only package identity is read. No event text, root nodes, screenshots or logging.
        if (pkg.equals(getPackageName())) {
            if (MainActivity.class.getName().contentEquals(event.getClassName() == null ? "" : event.getClassName())) {
                if (eventGate.allowedWindow(event.getEventTime())) leave();
            }
            return;
        }
        if (pkg.equals("com.android.systemui")) return;
        InputMethodManager input = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        for (android.view.inputmethod.InputMethodInfo method : input.getEnabledInputMethodList())
            if (pkg.equals(method.getPackageName())) return;
        if (SystemClock.elapsedRealtime() >= policyRefresh) { policy.refresh(); policyRefresh = SystemClock.elapsedRealtime() + 5000; }
        if (policy.allowed(pkg)) {
            if (eventGate.allowedWindow(event.getEventTime())) leave();
            return;
        }
        if (!eventGate.restrictedWindow(event.getEventTime(), policy.activityWindow(pkg, event.getClassName()))) return;
        if (remote != null) { remote.enter(); render(remote.state()); }
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
        if (state == DelayGate.State.ALLOWED || state == DelayGate.State.AWAY) { removeOverlay(); return; }
        if (overlay == null || overlayState != state) showOverlay(state);
        if (state == DelayGate.State.WAITING && countdown != null)
            countdown.setText(String.valueOf(((remote == null ? gate.remaining(SystemClock.elapsedRealtime()) : remote.remaining(SystemClock.elapsedRealtime())) + 999) / 1000));
    }
    private void showOverlay(DelayGate.State state) {
        removeOverlay();
        overlayState = state;
        overlay = new ScrollView(this);
        overlay.setFillViewport(true);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER);
        content.setPadding(30, 60, 30, 60);
        overlay.addView(content);
        overlay.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = Build.VERSION.SDK_INT >= 30
                ? insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout())
                : insets.getSystemWindowInsets();
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        overlay.setBackgroundColor(Color.rgb(10, 20, 27));
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
        Button home = new Button(this); home.setText("Ana sayfaya dön");
        home.setOnClickListener(v -> goHome());
        content.addView(home);
        if (remote != null && !connecting && remote.emergencyRemaining() != 0) {
            Button urgent = new Button(this);
            urgent.setAllCaps(false);
            urgent.setText("Acele geçişi · 15 dakika" + (remote.emergencyRemaining() >= 0 ? " · " + remote.emergencyRemaining() + "/4 kaldı" : ""));
            urgent.setOnClickListener(v -> {
                urgent.setText("Aylık 1 hakkı kullan ve aç");
                urgent.setOnClickListener(confirm -> { remote.emergency(); render(remote.state()); });
            });
            content.addView(urgent);
            content.addView(label("Günlük hakkından düşmez. Ayda 4 kez; ayın 1’inde 03.00’te yenilenir. İnternet gerekir.", 14));
        }
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
    }
    @Override public void onInterrupt() { leave(); }
    @Override public void onDestroy() {
        leave(); if (remote != null) remote.close(); instance = null;
        try { unregisterReceiver(screenOff); } catch (IllegalArgumentException ignored) { /* Service was not connected. */ }
        super.onDestroy();
    }
}
