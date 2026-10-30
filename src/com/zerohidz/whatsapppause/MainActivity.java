package com.zerohidz.whatsapppause;

import android.app.Activity;
import android.os.Bundle;
import android.content.Intent;
import android.provider.Settings;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.view.accessibility.AccessibilityManager;
import android.graphics.Color;
import android.view.View;
import android.widget.*;
import android.graphics.Typeface;

public final class MainActivity extends Activity {
    private TextView status;
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(32), dp(24), dp(24));
        root.setBackgroundColor(Color.rgb(10, 20, 27));
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = android.os.Build.VERSION.SDK_INT >= 30
                ? insets.getInsets(android.view.WindowInsets.Type.systemBars())
                : insets.getSystemWindowInsets();
            view.setPadding(dp(24) + bars.left, dp(24) + bars.top, dp(24) + bars.right, dp(24) + bars.bottom);
            return insets;
        });
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(10, 20, 27));
        scroll.addView(root);
        setContentView(scroll);
        root.addView(text("Bir Nefes", 34, true));
        root.addView(text("Ekrandan önce 30 saniye.", 22, false));
        root.addView(text("Otomatik açma alışkanlığına küçük bir ara. Moladan sonra ortak kullanım penceren açılır.", 17, false));
        status = text("", 17, true); root.addView(status);
        root.addView(text(SyncConfig.ENABLED ? "Telefon + Chrome · Ortak haklar\nSunucu: zmanga.zerohidz.com\nYenileme: 03.00 · İstanbul saati" : "Yerel kullanım · Senkronizasyon kapalı", 16, false));
        root.addView(text("İzin nasıl kullanılıyor?", 22, true));
        root.addView(text("Erişilebilirlik izni, açılan uygulamanın paket adını kontrol etmek ve mola ekranını göstermek için kullanılır. Mesajların ve ekran içeriğin okunmaz. Senkronizasyon açıkken yalnızca kullanım hakkı bilgisi kendi sunucuna HTTPS ile gönderilir. Mesajların gönderilmez.", 16, false));
        root.addView(text("İstisnalar dışındaki tüm telefon uygulamaları ve Chrome’daki mesajlaşma siteleri için günde toplam 5 ortak hak vardır. Her hak 30 saniyelik moladan sonra başlar ve 15 dakika sürer. Çıkıp girmen süreyi uzatmaz. Süre dolunca engelleyici ekran açılır. 22.30’dan sonra kalan haklardan yalnızca biri, 5 dakika kullanılabilir. 22.30’a taşan açık oturum da bu son hak sayılır ve en geç 22.35’te biter. Haklar her gün 03.00’te yenilenir.", 16, false));
        root.addView(text("Acele geçişi: ayda 4 ortak hak. Beklemeden 15 dakika açılır, günlük haklardan düşmez ve gece kısalmaz. Ayın 1’inde 03.00’te yenilenir. Telefon ve SMS her zaman açıktır.", 16, false));
        root.addView(text("Chrome’da video/film ayrı: 05.00–08.00 ve 17.00–22.30 serbest; 08.00–17.00 toplam 15 dakika, parça parça kullanılabilir. 22.30–05.00 kapalı. Telefonda tarayıcılar da genel uygulama kotasına dahildir.", 16, false));
        Button exceptions = new Button(this);
        exceptions.setAllCaps(false); exceptions.setText("İstisna uygulamaları seç");
        exceptions.setOnClickListener(v -> showExceptions()); root.addView(exceptions);
        Button setup = new Button(this);
        setup.setAllCaps(false);
        setup.setText("Anladım, ayarları aç");
        setup.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(80,181,130)));
        setup.setTextColor(Color.rgb(10,20,27));
        setup.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
        root.addView(setup);
        Button stop = new Button(this); stop.setAllCaps(false); stop.setText("Molayı kapat");
        stop.setOnClickListener(v -> {
            if (PauseService.instance != null) PauseService.instance.disableSelf();
            status.setText("Mola kapatıldı. Tekrar açmak için erişilebilirlik ayarlarını kullan.");
        });
        root.addView(stop);
        root.addView(text("APK dosyasından kurulumda Android, uygulama bilgileri menüsünden “Kısıtlanmış ayarlara izin ver” adımını isteyebilir.", 14, false));
    }
    private void showExceptions() {
        AppPolicy policy = new AppPolicy(this);
        java.util.Map<String, String> labels = new java.util.TreeMap<>();
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        for (android.content.pm.ResolveInfo info : getPackageManager().queryIntentActivities(launcher, 0))
            labels.put(info.activityInfo.packageName, info.loadLabel(getPackageManager()).toString());
        java.util.List<String> packages = new java.util.ArrayList<>(labels.keySet());
        packages.sort((a,b) -> labels.get(a).compareToIgnoreCase(labels.get(b)));
        LinearLayout list = new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(16), dp(8), dp(16), dp(8));
        TextView note = new TextView(this);
        note.setText("İşaretli uygulamalar her zaman açık. Değişiklikler hemen kaydedilir. Telefon, SMS, kişiler, ana ekran, klavye ve sistem ayarları korunur.");
        list.addView(note);
        for (String pkg : packages) {
            CheckBox row = new CheckBox(this);
            row.setText(labels.get(pkg) + (policy.essential(pkg) ? " · her zaman açık" : "") + "\n" + pkg);
            row.setChecked(policy.allowed(pkg)); row.setEnabled(!policy.essential(pkg));
            row.setOnCheckedChangeListener((button, checked) -> policy.setAllowed(pkg, checked));
            list.addView(row);
        }
        ScrollView scroll = new ScrollView(this); scroll.addView(list);
        new android.app.AlertDialog.Builder(this).setTitle("Her zaman açık uygulamalar")
            .setView(scroll).setPositiveButton("Tamam", null).show();
    }
    private TextView text(String value, int size, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value); view.setTextSize(size);
        view.setTextColor(Color.rgb(235, 242, 238));
        view.setPadding(0, 12, 0, 16);
        if (bold) view.setTypeface(null, Typeface.BOLD);
        return view;
    }
    @Override public void onResume() {
        super.onResume();
        AccessibilityManager manager = (AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
        boolean enabled = false;
        for (AccessibilityServiceInfo info : manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK))
            if (info.getResolveInfo().serviceInfo.packageName.equals(getPackageName())) enabled = true;
        status.setText(enabled ? "Mola etkin · 30 saniye" : "Mola kapalı · İzin gerekiyor");
    }
}
