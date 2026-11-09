# Spec — Hesap başına kurallar

> Durum: ONAYLI · Tarih: 2026-11-09 · Branch: master (çalışma ağacı, commit yok)

## 1. İş gereksinimi

Bugün sabit olan kotalar (günlük hak, mola, gece kuralı, yenileme, acele geçişi, video takvimi,
çalışma geçişi) her hesap için ayrı ayarlanabilsin. Kendini kandırmayı önleme korunur: kardeş kilidi
tutarken yalnız sıkılaştırma yapılabilir (AppBlock "sıkı mod" benzeri). Eklenti popup'ı sade,
sekmeli, açıklamaları varsayılan kapalı olsun.

## 2. Kapsam dışı

- `PauseService.java`, `VideoGate.java`, `BrowserUrl.java`: DOKUNULMAZ (P3b, sonra). Engel ekranındaki
  sabit metinler o aşamada kurallardan üretilecek.
- Yerel (`BIRNEFES_LOCAL_ONLY`) derleme: `DelayGate.java` varsayılanlarla kalır, değişmez.
- Ek kısıt seçenekleri, kira süresi (5 sn), mail limiti, eşleştirme kodu, LockRules listesi,
  kilitli kontrol süresi tavanı (1,5 sn): ayarlanamaz.
- Eklentide kural düzenleyici yok (salt okunur sekme).
- Sunucu deploy'u, sürüm yükseltme (`versionCode`/`versionName`, `manifest.json` version),
  APK/eklenti yayını, production DB'ye yazma, commit: YAPILMAZ.
- Yeni mail türü, set başına onay token'ı, 24 saat bekleme: YOK (reddedildi, bkz. §3).

## 3. Seçilen tasarım

Hesap başına sürümlü kural setleri (`rule_sets`). Satır yoksa `DEFAULT_RULES` (bugünkü değerler)
geçerli. Her değişiklik bir sonraki yenilemede yürürlüğe girer; hesap başına tek bekleyen set.
Kardeş kilidi tutarken (`guard.locked`) şu an geçerli sete göre gevşek olan set 409 `locked` ile
reddedilir; gevşetmek için mevcut `/v1/release` akışı kullanılır. Sunucu kuralları tüm cevaplarda
taşır, istemciler metinleri buradan üretir.

| Reddedilen alternatif | Neden |
|---|---|
| Gevşetmeye ayrı onay maili + set başına token | Kullanıcı kararı: mevcut kilit/izin akışı yeterli, ikinci mail türü gereksiz |
| Gevşetme için 24 saat bekleme | Kullanıcı kararı: sıkı mod modeli; kilit yokken serbest |
| Serbest segment listeli video takvimi | Arayüz ve gevşeklik karşılaştırması karmaşıklaşır |
| Haftanın 7 günü ayrı takvim | Kullanıcı kararı: hafta içi / hafta sonu |
| Değişikliğin anında yürürlüğe girmesi | Gün içinde sayaç/gece/yenileme tutarsızlığı; anlık sıkılaştırma için "Ek kısıt" var |

## 4. Bağlayıcı teknik kararlar

### 4.1 Kural JSON'u (sözleşme, alan adları aynen)

```json
{
  "rights": 5, "windowMin": 20, "waitSec": 30,
  "renewal": "03:00", "nightStart": "22:30", "nightWindowMin": 5,
  "urgentCount": 4, "urgentMin": 20,
  "video": {
    "weekday": {"closedFrom": "22:30", "closedUntil": "05:00", "meteredFrom": "08:00", "meteredUntil": "17:00", "budgetMin": 10},
    "weekend": {"closedFrom": "22:30", "closedUntil": "05:00", "meteredFrom": null, "meteredUntil": null, "budgetMin": 0}
  },
  "work": {"enabled": true, "minutes": [15, 30, 60, 90], "dailyMin": 180, "summary": true}
}
```

Bu nesne `DEFAULT_RULES`'tur (bugünkü davranışın aynısı).

### 4.2 Zaman modeli

- Saatler `"HH:MM"`, dakika 00 ya da 30 (30 dk ızgara).
- Bütçe günü `renewal`'dan bir sonraki `renewal`'a kadardır. `off(t) = (t - renewal) mod 1440` dakika.
- Gün tipi bütçe gününün tarihinden: Cumartesi/Pazar = `weekend`, diğerleri `weekday`
  (Cuma gecesi Cuma'ya aittir; bugünkü davranış).
- Video, bütçe günü D için gün tipi T ile: `[renewal, closedUntil_T)` ve `[closedFrom_T, sonraki renewal)`
  kapalı; `meteredFrom` varsa `[meteredFrom, meteredUntil)` sayaçlı (`budgetMin` dk); geri kalan serbest.
- Telefon gece kuralı: `off(t) >= off(nightStart)` iken gece (bugünkü 22.30 mantığı aynen, cutoff = nightStart).

### 4.3 Doğrulama (`400 invalid_rules`, `field`: hatalı alanın yolu, ör. `video.weekday.meteredFrom`)

| Alan | Kural |
|---|---|
| rights | tam sayı 0–10 |
| windowMin | 5–60, 5'in katı |
| waitSec | 0–300, 5'in katı |
| renewal | 00:00–06:00 arası |
| nightStart | `off` ∈ [720, 1380] (yenilemeden en az 12 sa sonra, en az 1 sa önce) |
| nightWindowMin | 0–20, 5'in katı (0 = gece hiç hak yok) |
| urgentCount | 0–8; urgentMin 5–30, 5'in katı |
| video.T.closedFrom | `off` ∈ [720, 1440] — 1440 değeri `"closedFrom": null` ile ifade edilir (gece kapanmaz) |
| video.T.closedUntil | `off` ∈ [0, 720) |
| video.T.metered | ikisi de null ya da ikisi de dolu; `off(closedUntil) <= off(meteredFrom) < off(meteredUntil) <= off(closedFrom)`; budgetMin 0–120, 5'in katı; metered null ise budgetMin 0 |
| work.minutes | {15,30,45,60,90,120} alt kümesi, boş değil, sıralı tekil |
| work.dailyMin | 15–480, 15'in katı |
| work.enabled, work.summary | bool |
| Fazla/eksik anahtar, yanlış tip | 400 |

Sıra kuralı (red-team): gece başlangıcı < yenileme < video gecesi bitişi, yukarıdaki `off` aralıklarıyla
zorunlanır. Yenileme 23.00 yapılıp gece kuralı kaldırılamaz.

### 4.4 Gevşeklik (`looser(new, current)` — new, ŞU AN GEÇERLİ sete karşı)

Aşağıdakilerden biri doğruysa gevşektir (karışık değişiklik = gevşek):
rights↑, windowMin↑, waitSec↓, renewal değişti (her yön), off(nightStart)↑, nightWindowMin↑,
urgentCount↑, urgentMin↑, her gün tipi için herhangi bir 30 dk dilimde rütbe yükseldi
(kapalı 0 < sayaçlı 1 < serbest 2), budgetMin↑ (gün tipi başına), work.enabled false→true,
max(work.minutes)↑, work.dailyMin↑, work.summary true→false.

### 4.5 Veri modeli ve yürürlük

- Yeni tablo: `rule_sets(account_id INTEGER, version INTEGER, effective_at INTEGER, rules TEXT, created INTEGER, device TEXT, PRIMARY KEY(account_id, version))`.
- `rules_at(db, account, now)`: `effective_at <= now` olan en yüksek version'ın kuralları, yoksa DEFAULT_RULES. Geçerli version yoksa 0.
- Kaydetme: `effective_at` = ŞU AN GEÇERLİ kurallara göre bir sonraki yenileme anı. Önce bu hesabın `effective_at > now` satırları silinir (tek bekleyen), sonra `version = max(version)+1` ile eklenir. Eski satırlar tarihçe olarak kalır.
- `baseVersion` = istemcinin gördüğü en büyük version (bekleyen varsa onun, yoksa geçerlinin; hiç yoksa 0). Eşleşmezse `409 stale`.
- Yenileme saati değişirse yürürlük eski kuralın yenilemesidir; yeni yenileme daha geçse arada gün anahtarı dünkü gün kalır (zaten tüketilmiş kota sürer) — kabul edilen davranış, testle sabitlenir.
- Her şey tek `BEGIN IMMEDIATE` transaction'ında; `request()`, `video_request()`, `start_work()`, `work_reports()`, `start_extra('day')`, `issue()` (mail günü) kuralları `rules_at(now)` ile okur. Modül seviyesindeki `bounds(now)` kural parametresi alır; sabit 22.30/03.00/5/8/17 kodda kalmaz (DEFAULT_RULES hariç).
- Policy sabitleri `WAIT, DAY_WINDOW, NIGHT_WINDOW, EMERGENCY_WINDOW, VIDEO_BUDGET, WORK_MINUTES, WORK_DAILY` kaldırılır ya da yalnız DEFAULT_RULES'tan türetilir.

### 4.6 Kilit (sıkı mod)

- `POST /v1/rules` (dryRun değilken): `guard.locked` (guard_view'deki tanım: kardeş var ve `released_until <= now`) ve `looser(new, current)` → `409 {"error":"locked"}`, hiçbir şey yazılmaz.
- Kilit yok ya da izin süresi içinde: her değişiklik kabul.
- Kaydedilmiş bekleyen gevşetme, yürürlük anında kilit kapanmış olsa da geçerli olur.
- `POST /v1/rules-cancel` her zaman serbest.
- Yeni mail türü ya da guard_requests değişikliği YOK.

### 4.7 Ek düzeltmeler (yerinde kalır)

- `quota.started INTEGER NOT NULL DEFAULT 0` kolonu: acele geçişi verilirken `started=now`. Video ücretinde `grant['end'] - EMERGENCY_WINDOW` yerine `started` kullanılır; `started=0` olan eski satırda `end - 1_200_000`.
- `/v1/pair`: `install` verilmişse ve o install'un mevcut hesabı kilitliyse (guard.locked) ve hedef hesap farklıysa `403 {"error":"locked"}`. Kilit yoksa bugünkü taşıma davranışı aynen (mevcut test korunur). Chrome tarafı (install yok) değişmez.
- `APPROVE_PAGE` içindeki `KIND` sözlüğüne `extra` metni eklenir (bugün "undefined" basıyor).

### 4.8 HTTP sözleşmesi

- `GET /v1/rules` →
  `{"current":{"version":N,"effectiveAt":ms|0,"rules":{...}},"pending":{"version":N,"effectiveAt":ms,"rules":{...},"looser":bool}|null,"defaults":{...},"locked":bool,"serverNow":ms}`
- `POST /v1/rules {"rules":{...},"baseVersion":N,"dryRun":bool?}` (gövde limiti 4096) →
  - dryRun: `200 {"looser":bool,"changes":["rights","video.weekday.budgetMin",...],"effectiveAt":ms,"allowed":bool}` (hiçbir şey yazmaz; `allowed=false` kilit + gevşek)
  - kayıt: `200` + GET /v1/rules gövdesi; `400 invalid_rules {field}`; `409 stale`; `409 locked`.
  - `changes`: değeri değişen yaprak alan yolları, sabit sırada.
- `POST /v1/rules-cancel` → `200` + GET /v1/rules gövdesi.
- `/v1/status`, `/v1/enter|complete|emergency`, tüm `/v1/video-*` cevapları ek olarak `"rulesVersion":N,"rules":{...}` (şu an geçerli) taşır. Var olan alanlar değişmez. `remaining`, `emergencyRemaining`, `videoRemainingMs`, `workLeftMs`, `nextChangeAt` kurallardan hesaplanır.

### 4.9 İstemciler

- Eklenti: kural metinleri yalnız cevaptaki `rules`'tan üretilir (popup, content.js, video.js). Cevapta `rules` yoksa (eski sunucu) DEFAULT_RULES ile aynı değerleri içeren yerel sabit kullanılır.
- Popup 4 sekme: **Durum** (telefon+mesaj durumu, video durumu, acele sayısı), **Geçişler** (çalışma geçişi, ek kısıt), **Kurallar** (salt okunur özet + bekleyen değişiklik ve yürürlük saati; "Değiştirmek için telefondaki Bir Nefes → Kurallar"), **Hesap** (eşleştirme, telefon bağla, başka hesaba bağlan). Açıklama/ipucu metinleri varsayılan kapalı `<details>` içinde. Bugünkü sabit `<small>` kural listeleri kalkar. Son seçilen sekme `sessionStorage`/`storage.session`'da tutulabilir; varsayılan Durum. Çalışma geçişi `rules.work.enabled=false` ise gizlenir, süre seçenekleri `rules.work.minutes`'tan, yemin denetimi aynen.
- Android: `RulesActivity` düzenleyici (senkron derlemede). Yerel derlemede bugünkü gibi salt okunur, metinler DEFAULT değerlerden. `MainActivity` 297 ("Gün sonuna kadar (03.00)") ve 489 ("Yenileme 03.00") kurallardan. `RemoteGate` cevaptaki `rules`/`rulesVersion`'ı `Account.rememberRules` ile saklar (yalnız `RemoteGate.java` ve `Account.java`, PauseService değişmez).

## 5. Kabul kriterleri

| # | Given / When / Then | Nasıl doğrulanır |
|---|---|---|
| AC1 | Given rule_sets satırı olmayan hesap, when mevcut 55 sunucu testi koşar, then hepsi değişmeden geçer | `python3 -m unittest discover -s server -v` |
| AC2 | Given rights=3 kaydedildi 10.00'da, when aynı gün 4. hak istenir, then verilir (eski kural); when ertesi gün 03.00'ten sonra 4. hak, then `exhausted` | unit test |
| AC3 | Given windowMin=10 waitSec=60 yürürlükte, when enter/complete, then `readyAt=now+60000`, `expiresAt=now+600000` | unit test |
| AC4 | Given nightStart=21:00 nightWindowMin=10, when 21.30'da hak açılır, then 10 dk; when 20.55'te açılan oturum, then en geç 21.10'da biter | unit test |
| AC5 | Given renewal 03:00→05:00 kaydedildi, when ertesi gün 03.00–05.00 arası istek, then `budgetDay` önceki gün; 05.00'te yeni gün ve tam haklar | unit test |
| AC6 | Given renewal=23:00, nightStart=22:30 gönderilir, then `400 invalid_rules field=nightStart` (ya da renewal) | unit test |
| AC7 | Given hafta içi meteredFrom=09:00 meteredUntil=12:00 budgetMin=30, when 08.30 / 10.00 / 12.30 video-status, then `free` / `metered` (bütçe 30 dk) / `free`; `nextChangeAt` sırasıyla 09.00, —, closedFrom | unit test |
| AC8 | Given weekend metered 10:00–12:00, when Cumartesi 11.00, then `metered`; Pazar gecesi closedFrom'da `night` | unit test |
| AC9 | Given çalışma geçişi enabled=false, when `/v1/video-work`, then `409 disabled`; given minutes=[15,45], when 60 istenir, then `400 invalid_work` | unit test |
| AC10 | Given meteredFrom 09:00–12:00, when 13.00'te çalışma geçişi, then `409 not_metered`; 10.00'da, then 200 | unit test |
| AC11 | Given work.summary=false, when gün kapanır, then kardeşe özet maili gitmez | unit test |
| AC12 | Given kardeş kilidi tutuyor, when rights 5→6 kaydedilir, then `409 locked` ve rule_sets değişmez; when rights 5→4, then 200 ve pending | unit test |
| AC13 | Given kardeş 1 sa izin verdi, when gevşetme kaydedilir, then 200; when izin biter ve yenileme gelir, then gevşek set yürürlükte | unit test |
| AC14 | Given kilitli, when sıkı ama gevşek dilim içeren karışık set (rights↓, budgetMin↑), then `409 locked` | unit test |
| AC15 | Given kilitli, when renewal 03:00→02:00, then `409 locked` | unit test |
| AC16 | Given bekleyen set var, when baseVersion eski, then `409 stale`; when yeni set gönderilir, then tek bekleyen kalır; when rules-cancel, then pending null | unit test |
| AC17 | Given dryRun=true, when gönderilir, then `looser`, `changes`, `effectiveAt`, `allowed` döner ve hiçbir satır yazılmaz | unit test |
| AC18 | Given urgentCount 4→2 yürürlükte ve ay içinde 3 kullanılmış, when status, then `emergencyRemaining=0` ve yeni acele `emergency_exhausted` | unit test |
| AC19 | Given acele geçişi 23.00'te başladı (urgentMin=20) ve yenilemede urgentMin=30 oldu, when video ücretlendirmesi, then `quota.started` kullanılır, sayaçlı dilime yanlış ücret yazılmaz | unit test |
| AC20 | Given install A kilitli hesapta, when A başka hesabın koduyla `/v1/pair`, then `403 locked`; kilit yokken mevcut taşıma testi geçer | unit test |
| AC21 | Given status/video cevapları, then `rulesVersion` ve `rules` var, eski alanlar aynı | unit test (HTTP) |
| AC22 | Given geçersiz JSON / fazla anahtar / 4096 bayt üstü gövde, then 400 | unit test (HTTP) |
| AC23 | Given approve sayfası `extra` isteği, then metin "undefined" değil | unit test (HTTP sayfa içeriği) |
| AC24 | Given popup açılır, then 4 sekme görünür, Durum aktif, diğer paneller gizli; tüm `<details>` kapalı; `<small>` sabit kural listesi yok | headless Chromium + stub `chrome.runtime`; ekran görüntüsü ajanın kendisince Read ile incelenir; aktif olmayan panelin computed `display` = none |
| AC25 | Given stub cevapta rules.rights=3, windowMin=10, video weekday 09:00–12:00 30 dk, when Kurallar sekmesi, then bu değerler yazılır ("3 × 10 dakika", "09.00–12.00 toplam 30 dakika") ve hiçbir yerde "20 dakika"/"08.00–17.00" geçmez | headless + metin araması + ekran görüntüsü |
| AC26 | Given pending var, when Kurallar sekmesi, then bekleyen değişiklik ve yürürlük saati görünür | headless (stub `/v1/rules` için background mesajı) |
| AC27 | Given content.js/video.js engel ekranı, when rules.urgentMin=30, urgentCount=6, then "Acele geçişi · 30 dakika · x/6 kaldı" | headless sayfa ya da kod içi tek fonksiyon testi (node) |
| AC28 | Given Android senkron derleme, when `bash build.sh`, then APK üretilir, derleme hatası yok | `ANDROID_SDK_ROOT=~/Android/Sdk bash build.sh` çıktısı |
| AC29 | Given `Rules.java` (saf Java) — saat etiketleri, seçenek listeleri, özet metin, JSON-dışı değer sınıfı — then `tests/RulesTest.java` geçer | `javac` + `java` |
| AC30 | Given RulesActivity, when kilit tutarken gevşek değişiklik kaydedilir, then sunucu 409 locked → "Kilit açıkken yalnız sıkılaştırabilirsin. Gevşetmek için ana ekrandan izin iste." gösterilir; kaydetmeden önce dryRun ile özet (ne değişiyor, ne zaman geçerli, gevşek mi) gösterilir | kod incelemesi + (emülatör varsa) ekran görüntüsü |
| AC31 | Given mevcut Java testleri, then hepsi geçer | CLAUDE.md'deki komutlar |

## 6. Edge case'ler

| Durum | Beklenen |
|---|---|
| Yürürlük anında açık normal oturum | Zaten yenilemede biter (`min(.., renewal)`) |
| Yürürlük anında açık acele geçişi | Verilen deadline korunur; ücret `started`'dan |
| Ay ortasında urgentCount artışı | Yürürlükten itibaren aynı ayda fazladan hak (kabul) |
| Video ortasında yenileme | Gün sıfırlanır (bugünkü gibi) |
| Bekleyen gevşetme varken kilit kapandı | Yürürlüğe girer |
| Kilitli, bekleyen sıkılaştırma var, yeni set geçerliye göre sıkı ama bekleyene göre gevşek | Kabul (karşılaştırma geçerli sete karşı) |
| Eski istemci (rules alanını tanımaz) | Sunucu doğru uygular, metinler eski; kabul |
| Ek kısıt 'day' | Geçerli kuralın yenileme anına kadar |

## 7. Etkilenen dosyalar

| Dosya | Sahip |
|---|---|
| `server/app.py`, `server/test_app.py`, `README.md`, `CLAUDE.md` | sunucu implementer |
| `extension/popup.html`, `popup.css`, `popup.js`, `content.js`, `video.js`, `background.js`, yeni `extension/rules.js` (ortak biçimlendirici + DEFAULT_RULES), `manifest.json` (yalnız yeni script girdisi gerekiyorsa; version DEĞİŞMEZ) | eklenti implementer |
| `RulesActivity.java`, `MainActivity.java`, `RemoteGate.java`, `Account.java`, `Ui.java`, yeni `Rules.java`, yeni `tests/RulesTest.java` | Android implementer |
| `PauseService.java`, `VideoGate.java`, `BrowserUrl.java`, `DelayGate.java` | DOKUNULMAZ |
