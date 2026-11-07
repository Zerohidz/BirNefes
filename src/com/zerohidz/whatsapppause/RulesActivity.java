package com.zerohidz.whatsapppause;

import android.app.Activity;
import android.os.Bundle;
import android.widget.LinearLayout;

public final class RulesActivity extends Activity {
    private LinearLayout page;
    private void section(String title, String body) {
        page.addView(Ui.text(this, title, 18, true), Ui.topMargin(this, 24));
        page.addView(Ui.text(this, body, 16, false), Ui.topMargin(this, 6));
    }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        page = Ui.page(this);
        page.addView(Ui.header(this, "Kurallar"));
        section("Günlük haklar", "İstisnalar dışındaki tüm telefon uygulamaları ve Chrome’daki mesajlaşma siteleri için günde toplam 5 ortak hak vardır. Her hak 30 saniyelik moladan sonra başlar ve 20 dakika sürer. Çıkıp girmen süreyi uzatmaz. Süre dolunca engelleyici ekran açılır. Haklar her gün 03.00’te yenilenir.");
        section("Gece", "22.30’dan sonra kalan haklardan yalnızca biri, 5 dakika kullanılabilir. 22.30’a taşan açık oturum da bu son hak sayılır ve en geç 22.35’te biter.");
        section("Acele geçişi", "Ayda 4 ortak hak. Beklemeden 20 dakika açılır, günlük haklardan düşmez ve gece kısalmaz. Ayın 1’inde 03.00’te yenilenir. Telefon ve SMS her zaman açıktır.");
        section("Chrome’da video ve film", "Hafta içi 05.00–08.00 ve 17.00–22.30 serbest, 08.00–17.00 toplam 20 dakika, parça parça kullanılabilir. Cumartesi ve pazar 05.00–22.30 serbest. Her gün 22.30–05.00 kapalı. Telefonda tarayıcılar da genel uygulama kotasına dahildir.");
        section("İzinler ve gizlilik", "Erişilebilirlik izni, açılan uygulamanın paket adını kontrol etmek ve mola ekranını göstermek için kullanılır. Mesajların ve ekran içeriğin okunmaz. Çağrı durumu izni, zil çalarken mola ekranını gizleyip aramayı açabilmen içindir: yalnız çağrı durumu okunur, numara okunmaz, kaydedilmez ve gönderilmez.");
        section("Kontrol süresi", "Kısıtlı uygulamaya girince engel ekranı sunucunun cevabını bu kadar bekler. 0,5 ile 3 saniye arasında seçilir, varsayılanı 1 saniyedir.");
        section("Senkronizasyon", SyncConfig.ENABLED
            ? "Telefon ve Chrome aynı hakları paylaşır. Yalnızca kullanım hakkı bilgisi kendi sunucuna (zmanga.zerohidz.com) HTTPS ile gönderilir. Mesajların gönderilmez. Yenileme saati İstanbul saatine göredir."
            : "Yerel kullanım. Senkronizasyon kapalı, hiçbir bilgi gönderilmez.");
    }
}
