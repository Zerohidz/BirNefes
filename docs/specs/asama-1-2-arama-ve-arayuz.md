# Spec — Aşama 1 (1.3.2) arama düzeltmesi, Aşama 2 (1.4.0) 20 dakika ve arayüz

> Durum: ONAYLI (tasarım kararları kullanıcıdan), uygulanıyor
> Tarih: 2026-11-06 · Onaylayan: Dursun Zahid Korkmaz · Branch: master, commit yok

İki aşama **sırayla** uygulanır ve ayrı derlenir. 1.3.2 içinde Bölüm 4B/5B maddelerinin hiçbiri bulunmaz.
Kilit (Aşama 3-4) bu spec'in dışındadır.

## 1. İş gereksinimi

- Gelen normal hat araması engellenmemeli, telefon açılabilmeli (Samsung S23 FE, One UI, root yok, sideload).
- Sıradan pencere, acele geçişi ve Chrome video bütçesi 20 dakika olmalı.
- Ana ekran sade olmalı, butonlar güzel görünmeli.
- İstisna listesinde paket adı değil, uygulama adı ve logosu görünmeli; liste dışa/içe aktarılabilmeli.

Aşama 0 ölçümü (S23 FE, adb): arama sırasında üstteki Activity `com.samsung.android.incallui/com.android.incallui.call.InCallActivity`, odak Bir Nefes overlay'inde. Kök neden tam ekran durumu için kanıtlandı. Heads-up durumu ölçülmedi. `pm list` çıktısındaki `user 150` hatası cihazda 0 dışı bir kullanıcı olduğunu gösteriyor, muhtemelen Güvenli Klasör (doğrulanmadı).

## 2. Kapsam dışı

- Kilit, kardeş izni, mail, Device Admin, heartbeat (Aşama 3-4).
- WhatsApp ve diğer VoIP aramaları. Bugünkü davranış sürer, README'de sınır olarak yazılır.
- Ana ekranda kalan hak sayısı. Yeni ağ kodu gerektirir, istenmedi.
- Gece 5 dakika, 30 saniye bekleme, 5 hak, 4 acele geçişi, yenileme saatleri değişmez.
- Sunucu dağıtımı, commit, push: kullanıcı onayı bekler.
- Split-screen, DeX, Güvenli Klasör açıkları. Cihazda `user 150` görüldüğü için Güvenli Klasör satırı Aşama 3-4 öncesi ölçülecek.

## 3. Seçilen tasarım

Arama için üç katman: arama arayüzü paketleri essential olur, `READ_PHONE_STATE` ile çağrı durumu dinlenir, zil çalarken overlay gate durumu bozulmadan gizlenir. Arama sürerken genel askı yoktur. Arayüz ham Android SDK ile kalır, istisna ekranı ayrı Activity olur.

| Reddedilen | Neden |
|---|---|
| Arama boyunca engeli tamamen durdurmak | Kendini arayıp tüm uygulamaları açma yolu olur |
| Yalnız `AudioManager` modu | AOSP `Ringer.startRinging` sessiz/titreşimde false döner, `MODE_RINGTONE` set edilmez |
| Yalnız paket eklemek | Telefon kullanılırken arama heads-up gelir; overlay katmanı (31) status bar'ın (15) üstünde kalır |
| Dosya seçiciyi kalıcı istisna yapmak | Sınırsız dosya uygulaması açar |

## 4A. Bağlayıcı teknik kararlar — Aşama 1

| # | Karar | Nerede |
|---|---|---|
| D1 | `refresh()` içinde `android.telecom.InCallService` servisleri `GET_META_DATA` ile sorgulanır; `android.telecom.IN_CALL_SERVICE_UI=true` olan paket yalnız `FLAG_SYSTEM` ise veya varsayılan dialer ise essential olur. Üçüncü taraf uygulama muaf olmaz. | `AppPolicy.java` |
| D2 | `com.samsung.android.incallui` sabit olarak essential listesine girer. **Fiziksel S23 FE'de adb ile doğrulandı:** gelen aramada `topResumedActivity` = `com.samsung.android.incallui/com.android.incallui.call.InCallActivity`, odak Bir Nefes overlay'inde. | `AppPolicy.java` |
| D3 | `<queries>` içine `android.telecom.InCallService` intent'i, manifeste `READ_PHONE_STATE` eklenir. Sürüm 1.3.2, versionCode 8; `extension/manifest.json` de 1.3.2. | `AndroidManifest.xml`, `extension/manifest.json` |
| D4 | Yeni `CallWatcher`: API 31+ `TelephonyCallback.CallStateListener`, API 29-30 `PhoneStateListener`. Kayıt `checkSelfPermission` kapısı ve `SecurityException` yakalaması ile yapılır; izin yokken servis düşmez. Servis kapanınca kayıt silinir. | yeni `CallWatcher.java`, `PauseService.java` |
| D5 | Yeni saf Java `CallScreenGate` (Android importu yok): çağrı durumu, overlay gizleme, HOME atlama, ekran yeniden kontrol kararları burada; birim testli. | yeni `CallScreenGate.java`, `tests/CallScreenGateTest.java` |
| D6 | RINGING iken overlay kaldırılır, `gate`/`remote` durumu ve `tick` değişmez, `render` overlay çizmez. RINGING bitince (IDLE/OFFHOOK) zorunlu `render(currentState())` yapılır. `leave()` bu yolda çağrılmaz. | `PauseService.java` |
| D7 | OFFHOOK genel askı değildir: kısıtlı pencere normal engellenir, arama ekranı D1/D2 ile açık kalır. | `PauseService.java` |
| D8 | İzin yoksa yedek: `AudioManager.getMode()` `MODE_RINGTONE` veya `MODE_IN_CALL` ise `tick` içinde aynı gizleme uygulanır. `MODE_IN_COMMUNICATION` sayılmaz (VoIP/kendi kendine kaçış). | `PauseService.java` |
| D9 | `USER_PRESENT` ve `onServiceConnected` içindeki HOME, çağrı aktifken atlanır. | `PauseService.java:30,56` |
| D10 | Ekran kapanırken gate AWAY değilse işaret bırakılır. `ACTION_SCREEN_ON` geldiğinde kilit ekranı yoksa ve çağrı yoksa `goHome()` çalışır; kilit ekranı varsa mevcut `USER_PRESENT` yolu çalışır. | `PauseService.java`, `CallScreenGate.java` |
| D11 | MainActivity izin yoksa "Aramalar için izin ver" butonu gösterir, `requestPermissions` çağırır, sonuçta çalışan servise yeniden kayıt yaptırır. Reddedilirse engel yine çalışır. Başka UI değişikliği yok. | `MainActivity.java` |
| D12 | Yeni test, CLAUDE.md test komutlarına; README'ye 1.3.2 bölümü, Privacy'ye çağrı durumu cümlesi (numara okunmaz) ve VoIP sınırı eklenir. | `CLAUDE.md`, `README.md` |
| D13 | `canRetrieveWindowContent`, `canPerformGestures` false kalır; olay metni, düğüm, ekran görüntüsü okunmaz; çağrı numarası hiçbir yere yazılmaz ve loglanmaz. | tümü |

## 5A. Kabul kriterleri — Aşama 1

| # | Given / When / Then | Doğrulama | Kanıt |
|---|---|---|---|
| A1 | Given arama arayüzü `InCallService` sistem paketi, when `refresh()`, then paket `allowed` döner; üçüncü taraf paket dönmez | Emülatör / kod okuma | |
| A2 | Given overlay açık, izin verilmiş, when gelen arama, then overlay kalkar ve arama ekranı/heads-up dokunulabilir | Emülatör `adb emu gsm call`, ekran görüntüsü + pencere listesi | |
| A3 | Given A2, when arayan kapatır, then aynı durumla overlay geri gelir, kısıtlı uygulama açıkta kalmaz | Emülatör | |
| A4 | Given sessiz/titreşim modunda izin verilmiş, when gelen arama, then A2 geçerli | Emülatör, gerçek değer cihazda | |
| A5 | Given çağrı OFFHOOK, when kısıtlı uygulama açılır, then engellenir | Emülatör | |
| A6 | Given çağrı aktif, when ekran kilidi açılır, then HOME atılmaz | Emülatör + birim test | |
| A7 | Given izin yok, when servis bağlanır, then crash ve HOME döngüsü yok | Emülatör logcat | |
| A8 | Given overlay açık, when ekran söner ve kilitlenmeden açılır, then uygulama açıkta kalmaz | Emülatör + birim test | |
| A9 | Given S23 FE, when sesli/titreşim/sessiz × kilit ekranı/ana ekran/açık uygulama/overlay, then arama açılır | **Fiziksel cihaz (kullanıcı)** | |
| A10 | Yerel testler ve 1.3.2 üretim APK derlemesi geçer | Komut çıktısı | |

## 4B. Bağlayıcı teknik kararlar — Aşama 2

| # | Karar | Nerede |
|---|---|---|
| E1 | `DAY_WINDOW`, `EMERGENCY_WINDOW`, `VIDEO_BUDGET`, `SESSION_MS` = 1.200.000. `NIGHT_WINDOW`, `WAIT`, hak sayıları aynı. | `server/app.py`, `DelayGate.java` |
| E2 | Tüm "15 dakika" metinleri 20 olur (geçmişi anlatan README 1.3.1 bölümü hariç). Testler güncellenir, `/health` sürümü 1.4.0, her iki manifest 1.4.0, versionCode 9. | `PauseService`, `extension/*`, `README.md`, `tests/*`, `server/*` |
| E3 | Ortak görsel yardımcı `Ui`: primary (dolgu 80,181,130; metin 10,20,27 bold), secondary (dolgu 28,38,44; 1dp kenar ARGB 50,235,242,238), destructive (şeffaf; metin 240,138,122). `RippleDrawable` + `GradientDrawable`, radius 14dp, yükseklik 52dp, `setAllCaps(false)`. Ekranda en fazla bir primary. Yeni renk yalnız destructive. Overlay butonları da bunu kullanır. Üçüncü taraf kütüphane ve yeni tema yok. | yeni `Ui.java`, `MainActivity`, `PauseService` |
| E4 | Ana ekran: başlık, durum kartı, İstisnalar, Kurallar, Bir Nefes'i durdur. Servis kapalıysa kartta primary "İzni aç" ve "Mesajların ve ekran içeriğin okunmaz."; "Kısıtlanmış ayar" notu yalnız bu durumda. Durdur yalnız servis açıkken görünür ve onay dialog'u ister ("Vazgeç"/"Durdur"). D11 izin butonu korunur. | `MainActivity.java` |
| E5 | Kural ve gizlilik metinleri yeni `RulesActivity`'ye taşınır, başlıklı bölümlerle. | yeni `RulesActivity.java`, manifest |
| E6 | `PauseService` yalnız `MainActivity` değil kendi tüm Activity'lerinde `leave()` çağırır. | `PauseService.java:65-69` |
| E7 | Yeni `ExceptionsActivity`: arama + `ListView` + `BaseAdapter`. Bölümler: İstisnalar, Kısıtlı uygulamalar, Sistem · her zaman açık (kapalı başlar). Satır: 40dp logo, ad, Switch; sistem satırında "Sistem". **Paket adı hiçbir yerde görünmez.** Sıralama `Collator` tr-TR, ekran açılışı ve import'ta hesaplanır, Switch'e basınca satır kaymaz. Arama yalnız adda, i/ı/İ/I eşit. | yeni `ExceptionsActivity.java` |
| E8 | Liste arka plan thread'inde yüklenir. `getView` içinde `setChecked` öncesi listener null. İkonlar `LruCache<String,Bitmap>` (8 MB), geri dönüşümde paket etiketi kontrol edilir. Ad `getApplicationLabel`. | `ExceptionsActivity.java` |
| E9 | Export/import ⋮ menüsünde, SAF (`ACTION_CREATE_DOCUMENT`/`ACTION_OPEN_DOCUMENT`), izin yok. Format JSON: `{"app":"bir-nefes","type":"exceptions","version":1,"exportedAt":"<ISO>","packages":[...]}`; yalnız kullanıcı listesi, essential yazılmaz. Dosya adı `bir-nefes-istisnalar-YYYY-MM-DD.json`. | `ExceptionsActivity.java`, yeni `ExceptionsFile.java` |
| E10 | Import: en fazla 256 KB, tür/sürüm kontrolü, paket adı regex'i, en fazla 2000 paket, tekrar temizliği. Önizleme dialog'u yüklü olmayan sayısını gösterir, onayla listeyi değiştirir. Yüklü olmayanlar saklanır. Üstte "Liste değişti · Geri al", ekran kapanana kadar. Bozuk dosyada liste değişmez. Doğrulama saf Java `ExceptionRules` içinde, birim testli (`org.json` yok). | yeni `ExceptionRules.java`, `tests/ExceptionRulesTest.java` |
| E11 | Seçici engellenmez ve hak harcamaz: başlatılırken 5 dakikalık `PickerPass` açılır, `onActivityResult` veya ekrana dönüşte biter. Süresince yalnız `ACTION_OPEN_DOCUMENT`/`CREATE_DOCUMENT` çözen paketler, `com.google.android.documentsui`, `com.android.documentsui`, `com.android.intentresolver` izinli sayılır. Kalıcı istisna yapılmaz. | yeni `PickerPass.java`, `PauseService`, `AppPolicy` veya servis, manifest `<queries>` |
| E12 | `allowBackup=false` kalır. README Build/Verification bölümleri, CLAUDE.md test komutları ve artifact listesi güncellenir. | `README.md`, `CLAUDE.md` |
| E13 | "Kontrol süresi" ayarı: kısıtlı uygulamaya girince engel ekranı sunucu cevabını kaç saniye bekler. 0,5 sn adımlarla 0,5-3 sn, varsayılan 1 sn. Üst sınır kaçış olmasın diye sabit. `PauseService.CONNECTING_GRACE_MS` sabiti bu ayarı okur. Aşama 3 kilidi açıkken değeri artırma kapalı, azaltma açık. | `MainActivity.java`, `PauseService.java` |

## 5B. Kabul kriterleri — Aşama 2

| # | Given / When / Then | Doğrulama | Kanıt |
|---|---|---|---|
| B1 | Normal hak ve acele 20 dk, video bütçesi 20 dk, gece 5 dk, bekleme 30 sn | Birim testler | |
| B2 | Kod ve eklentide kullanıcıya görünen "15 dakika" kalmaz | grep | |
| B3 | Ana ekran açıldığında uzun kural metni görünmez, primary en fazla bir | Emülatör screenshot, ajan Read ile açar | |
| B4 | Buton dolguları görünür: ekran görüntüsünde buton bölgesinin piksel rengi E3 değerlerine eşit, arka plandan farklı | Piksel ölçümü + Read | |
| B5 | Durum açık/kapalı doğru, kapalıyken Durdur yok | Emülatör | |
| B6 | Durdur onay ister, Vazgeç'te servis açık kalır | Emülatör | |
| B7 | İstisna listesinde logo ve ad var, `com.` metni görünmez | UI dump + Read | |
| B8 | Switch değişikliği kaydedilir, kısıtlı uygulama molasız açılır | Emülatör | |
| B9 | Arama Türkçe harf eşitliğiyle çalışır | Birim test + emülatör | |
| B10 | Export dosyası E9 formatında, içinde essential yok | Emülatör `adb pull` | |
| B11 | Import önizleme, uygulama, geri al çalışır; bozuk dosyada liste değişmez | Emülatör + birim test | |
| B12 | Seçici açılınca overlay gelmez ve hak harcanmaz | Emülatör | |
| B13 | Kendi Activity'lerine dönüşte overlay kalkar | Emülatör | |
| B14 | Yerel testler ve 1.4.0 APK + eklenti paketi derlenir | Komut çıktısı | |
| B15 | Kontrol süresi arayüzden 0,5-3 sn arasında değişir, 3 sn üstü seçilemez, yeniden açılışta korunur ve yeni girişte geçerli olur | Emülatör + birim test | |

Görsel kriterlerde hedef bölge ölçülür ve ekran görüntüsünü ajanın kendisi Read ile inceler. "Elemanın var olması" kanıt değildir. Tarayıcı yerine headless emülatör kullanılır, pencere açılmaz.

## 6. Edge case'ler

| Durum | Beklenen |
|---|---|
| Çift hat | Varsayılan abonelik kaydı tüm hatları görür (AOSP), fiziksel testte doğrulanır |
| Zil çalarken kısıtlı pencere olayı | Durum makinesi çalışır, overlay gizli kalır, IDLE'da görünür |
| Reddedilen arama | Overlay geri gelir |
| Takip eden ikinci arama | RINGING tekrar gizler |
| Eski 15 dk grant | Verilmiş deadline değişmez |
| Harcanmış video süresi | Korunur, yeni bütçeden düşer |
| Sunucu dağıtılmadan 1.4.0 | Metin 20 dk der, sunucu 15 dk verir: bilinen uyumsuzluk |
| Boş arama sonucu | "“xyz” adında uygulama yok." |
| Yüklü olmayan paket içeren import | Saklanır, önizlemede sayılır |
| Seçiciden vazgeçme | Pass biter, liste değişmez |

## 7. Riskler

| Risk | Azaltma |
|---|---|
| Paket doğrulandı, ama yalnız tam ekran durumu ölçüldü; heads-up ölçülmedi | RINGING gizleme korunur, A9 kullanıcı testi 1.3.2 ile |
| Yeni izin verilmezse sessiz zil örtülür | D11 görünür buton |
| Emülatör Telecom'u Samsung'la aynı değil | A9 zorunlu, kilit bu olmadan başlamaz |
| Üretim APK emülatörde üretim DB'ye gider | Emülatörde yalnız yerel derleme, `BIRNEFES_SYNC_CONFIG` yok sayılır |
| 1.3.1 kaynak ayrımı commit olmadan kaybolur | 1.3.2 kaynağı `.build/` altına arşivlenir |

## 8. Danışman notları

- **red-team:** Global askı ve yerel kota düşüşünü çürüttü, ekran söndürme açığını ve üçüncü taraf InCallService muafiyetini buldu. Kararlar D1, D6, D10'a işlendi.
- **senior-architect-advisor:** `org.json` saf sınıflardan uzak tutulur, `uiautomator` servis açıkken kullanılmaz.
- **ux-advisor:** Ana ekran, üç buton seviyesi, istisna ekranı, seçici tuzağı. Kalan hak kartı kapsam dışı bırakıldı.
- **domain-expert:** ERP uzmanı olduğu için çağrılmadı.

## 9. QA sonucu

Gate 6'da doldurulur.
