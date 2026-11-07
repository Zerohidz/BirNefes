package com.zerohidz.whatsapppause;

import android.Manifest;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.accessibility.AccessibilityManager;
import android.text.InputFilter;
import android.text.InputType;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    /** Set by the lock screen's "İzin iste": opens the request form once. */
    static final String ASK_RELEASE = "askRelease";
    // disableSelf reaches the accessibility manager a moment after the call returns.
    private static final long STATUS_SETTLE_MS = 500;
    private LinearLayout page;
    private TextView status, statusDetail, quota, restrictedNote, callNote, pairCode, pairNote;
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private Button enable, stop, askRelease, callPermission, checkDelay;
    private TextView guardStatus, guardNote, guardWarning;
    private EditText guardEmail;
    private Button guardAdd, guardChange;
    private SharedPreferences settings;
    private LinearLayout updateCard;
    private TextView updateTitle, updateNote;
    private Button updateButton;
    private TextView extraStatus, extraNote;
    private Button extraStart, extraEnd;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        settings = getSharedPreferences(CheckDelay.PREFERENCES, MODE_PRIVATE);
        page = Ui.page(this);
        page.addView(Ui.text(this, "Bir Nefes", 32, true));
        page.addView(updateCard(), Ui.topMargin(this, 16));

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        int padding = Ui.dp(this, 16);
        card.setPadding(padding, padding, padding, padding);
        card.setBackground(Ui.shape(this, Ui.SECONDARY, Ui.STROKE));
        status = Ui.text(this, "", 20, true);
        card.addView(status);
        statusDetail = Ui.muted(this, "", 15);
        card.addView(statusDetail, Ui.topMargin(this, 6));
        quota = Ui.muted(this, "", 15);
        quota.setVisibility(View.GONE);
        card.addView(quota, Ui.topMargin(this, 6));
        enable = Ui.primary(this, "İzni aç");
        enable.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
        card.addView(enable);
        restrictedNote = Ui.muted(this, "APK dosyasından kurulumda Android, uygulama bilgileri menüsünden “Kısıtlanmış ayarlara izin ver” adımını isteyebilir.", 14);
        card.addView(restrictedNote, Ui.topMargin(this, 12));
        page.addView(card, Ui.topMargin(this, 16));
        if (SyncConfig.ENABLED) {
            page.addView(accountCard(), Ui.topMargin(this, 16));
            page.addView(extraCard(), Ui.topMargin(this, 16));
        }

        callPermission = Ui.secondary(this, "Aramalar için izin ver");
        callPermission.setOnClickListener(v -> requestPermissions(new String[] {Manifest.permission.READ_PHONE_STATE}, 1));
        page.addView(callPermission);
        callNote = Ui.muted(this, "Zil çalarken mola ekranı gizlensin diye yalnız çağrı durumu okunur. Numara okunmaz.", 14);
        page.addView(callNote, Ui.topMargin(this, 6));

        Button exceptions = Ui.secondary(this, "İstisnalar");
        exceptions.setOnClickListener(v -> startActivity(new Intent(this, ExceptionsActivity.class)));
        page.addView(exceptions);
        checkDelay = Ui.secondary(this, "");
        checkDelay.setOnClickListener(v -> chooseCheckDelay());
        page.addView(checkDelay);
        Button rules = Ui.secondary(this, "Kurallar");
        rules.setOnClickListener(v -> startActivity(new Intent(this, RulesActivity.class)));
        page.addView(rules);
        stop = Ui.destructive(this, "Bir Nefes’i durdur");
        stop.setOnClickListener(v -> confirmStop());
        page.addView(stop);
        askRelease = Ui.secondary(this, "İzin iste");
        askRelease.setOnClickListener(v -> askRelease());
        askRelease.setVisibility(View.GONE);
        page.addView(askRelease);
    }
    @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent); setIntent(intent); }
    /** One card for everything about the shared account; guardian approval will be added here. */
    private LinearLayout accountCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        int padding = Ui.dp(this, 16);
        card.setPadding(padding, padding, padding, padding);
        card.setBackground(Ui.shape(this, Ui.SECONDARY, Ui.STROKE));
        card.addView(Ui.text(this, "Hesap", 20, true));
        card.addView(Ui.muted(this, "Bağlı telefon ve bilgisayarlar aynı hakları paylaşır.", 15), Ui.topMargin(this, 6));
        pairCode = Ui.text(this, "", 34, true);
        pairCode.setLetterSpacing(0.15f);
        pairCode.setTextIsSelectable(true);
        pairCode.setVisibility(View.GONE);
        card.addView(pairCode, Ui.topMargin(this, 12));
        pairNote = Ui.muted(this, "", 14);
        pairNote.setVisibility(View.GONE);
        card.addView(pairNote, Ui.topMargin(this, 4));
        Button connect = Ui.secondary(this, "Bilgisayarı bağla");
        connect.setOnClickListener(v -> requestPairCode());
        card.addView(connect);
        Button join = Ui.secondary(this, "Kodu gir");
        join.setOnClickListener(v -> enterCode());
        card.addView(join);
        guardianSection(card);
        return card;
    }
    /** Guardian approval: the sibling's mail decides releases; this phone only asks. */
    private void guardianSection(LinearLayout card) {
        card.addView(Ui.text(this, "Kardeş onayı", 18, true), Ui.topMargin(this, 20));
        guardStatus = Ui.muted(this, "", 15);
        card.addView(guardStatus, Ui.topMargin(this, 6));
        guardEmail = new EditText(this);
        guardEmail.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        guardEmail.setSingleLine(true);
        guardEmail.setTextColor(Ui.TEXT);
        guardEmail.setHintTextColor(Ui.MUTED);
        guardEmail.setHint("kardes@ornek.com");
        card.addView(guardEmail, Ui.topMargin(this, 8));
        guardAdd = Ui.secondary(this, "Ekle");
        guardAdd.setOnClickListener(v -> setGuardian(guardEmail.getText().toString().trim(),
            "Doğrulama maili gönderildi, onaylanınca kilit başlar."));
        card.addView(guardAdd);
        guardChange = Ui.secondary(this, "Kardeşi değiştir");
        guardChange.setOnClickListener(v -> changeGuardian());
        card.addView(guardChange);
        guardNote = Ui.muted(this, "", 14);
        card.addView(guardNote, Ui.topMargin(this, 8));
        guardWarning = Ui.text(this, "Mail gönderilemedi. Adresi kontrol edip yeniden dene.", 14, false);
        guardWarning.setTextColor(Ui.DESTRUCTIVE);
        card.addView(guardWarning, Ui.topMargin(this, 6));
    }
    private boolean locked() { return Account.locked(this); }
    /** Draws the guard from the last saved answer; the server refresh saves and redraws. */
    private void showGuard(String message) {
        LockRules.Guard guard = Account.guard(this);
        long now = System.currentTimeMillis();
        boolean lock = guard.locked(now);
        if (guardStatus != null) {
            boolean none = guard.guardian == null;
            guardStatus.setText(none ? "Kardeşini eklersen onun izni olmadan Bir Nefes durdurulamaz ve istisna eklenemez. İzni, kardeşine giden maildeki sayfadan verir."
                : "Kardeş: " + guard.guardian + "\n" + (lock ? "Kilitli" : "İzinli · " + until(guard.releasedUntil) + " saatine kadar"));
            if (!none && guardEmail.hasFocus()) {
                // The field is going away; do not leave its keyboard open.
                guardEmail.clearFocus();
                ((android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
                    .hideSoftInputFromWindow(guardEmail.getWindowToken(), 0);
            }
            guardEmail.setVisibility(none ? View.VISIBLE : View.GONE);
            guardAdd.setVisibility(none ? View.VISIBLE : View.GONE);
            guardChange.setVisibility(none ? View.GONE : View.VISIBLE);
            String note = message != null ? message
                : guard.pendingGuardian ? (none ? "Doğrulama maili gönderildi, onaylanınca kilit başlar." : "Kardeş değişikliği mevcut kardeşinin onayını bekliyor.")
                : guard.pendingRelease ? "İzin isteğin kardeşinin onayını bekliyor." : "";
            guardNote.setText(note);
            guardNote.setVisibility(note.isEmpty() ? View.GONE : View.VISIBLE);
            guardWarning.setVisibility(guard.mailError ? View.VISIBLE : View.GONE);
        }
        boolean on = serviceEnabled();
        stop.setVisibility(on && !lock ? View.VISIBLE : View.GONE);
        askRelease.setVisibility(lock ? View.VISIBLE : View.GONE);
    }
    private static String until(long time) {
        java.time.ZonedDateTime at = java.time.Instant.ofEpochMilli(time).atZone(java.time.ZoneId.systemDefault());
        String clock = String.format(java.util.Locale.ROOT, "%02d.%02d", at.getHour(), at.getMinute());
        return at.toLocalDate().equals(java.time.LocalDate.now()) ? clock
            : String.format(java.util.Locale.ROOT, "%d.%02d %s", at.getDayOfMonth(), at.getMonthValue(), clock);
    }
    private static String guardError(Account.Response response) {
        String error = response.body.optString("error");
        return error.equals("mail_limit") ? "Bugünkü mail sınırı doldu. Yarın yeniden dene."
            : error.equals("invalid_email") ? "E-posta adresi geçersiz."
            : error.equals("no_guardian") ? "Önce kardeşini ekle."
            : "Sunucuya ulaşılamadı.";
    }
    /** Shared by Ekle and Kardeşi değiştir: the server sends the right mail for each case. */
    private void setGuardian(String email, String sent) {
        if (email.isEmpty()) { showGuard("Önce e-posta adresini yaz."); return; }
        showGuard("Gönderiliyor…");
        guardRequest("/v1/guardian", email, sent);
    }
    private void guardRequest(String path, String value, String sent) {
        network.execute(() -> {
            String message;
            try {
                org.json.JSONObject body = new org.json.JSONObject().put(path.equals("/v1/guardian") ? "email" : "reason", value);
                Account.Response response = Account.authorized(this, "POST", path, body);
                if (response.status == 200) Account.rememberGuard(this, response.body);
                message = response.status == 200 ? sent : guardError(response);
            } catch (Exception error) {
                android.util.Log.w("BirNefes", "Guard request failed: " + error.getClass().getSimpleName());
                message = "Sunucuya ulaşılamadı.";
            }
            String shown = message;
            runOnUiThread(() -> { if (!isDestroyed()) showGuard(shown); });
        });
    }
    private void changeGuardian() {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        input.setSingleLine(true);
        input.setTextColor(Ui.TEXT);
        input.setHint("yeni@ornek.com");
        Ui.show(new AlertDialog.Builder(this).setTitle("Kardeşi değiştir").setView(form(
                "Değişikliği önce mevcut kardeşin onaylar, sonra yeni adres doğrulanır.", input))
            .setNegativeButton("Vazgeç", null)
            .setPositiveButton("Gönder", (dialog, which) -> setGuardian(input.getText().toString().trim(),
                "Mevcut kardeşine onay maili gönderildi.")));
    }
    private void askRelease() {
        EditText input = new EditText(this);
        input.setFilters(new InputFilter[] {new InputFilter.LengthFilter(200)});
        input.setTextColor(Ui.TEXT);
        input.setHint("Kısa gerekçe (isteğe bağlı)");
        Ui.show(new AlertDialog.Builder(this).setTitle("İzin iste").setView(form(
                "Kardeşine mail gider. 1 saat, 24 saat ya da kalıcı açmaya o karar verir.", input))
            .setNegativeButton("Vazgeç", null)
            .setPositiveButton("Gönder", (dialog, which) -> {
                showGuard("Gönderiliyor…");
                guardRequest("/v1/release", input.getText().toString().trim(), "İzin isteği kardeşine gönderildi.");
            }));
    }
    private LinearLayout form(String explanation, EditText input) {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        int padding = Ui.dp(this, 24);
        form.setPadding(padding, Ui.dp(this, 8), padding, 0);
        form.addView(Ui.muted(this, explanation, 15));
        input.setHintTextColor(Ui.MUTED);
        form.addView(input, Ui.topMargin(this, 8));
        return form;
    }
    /** The user's own extra limit: started here, ended early only by the guardian. */
    private LinearLayout extraCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        int padding = Ui.dp(this, 16);
        card.setPadding(padding, padding, padding, padding);
        card.setBackground(Ui.shape(this, Ui.SECONDARY, Ui.STROKE));
        card.addView(Ui.text(this, "Ek kısıt", 20, true));
        extraStatus = Ui.muted(this, "", 15);
        card.addView(extraStatus, Ui.topMargin(this, 6));
        extraStart = Ui.secondary(this, "Ek kısıt başlat");
        extraStart.setOnClickListener(v -> chooseExtraScope());
        card.addView(extraStart);
        extraEnd = Ui.secondary(this, "Erken bitirmek için izin iste");
        extraEnd.setOnClickListener(v -> askExtraEnd());
        card.addView(extraEnd);
        extraNote = Ui.muted(this, "", 14);
        card.addView(extraNote, Ui.topMargin(this, 8));
        return card;
    }
    /** Draws the extra limit from the last saved answer; every server answer saves and redraws. */
    private void showExtra(String message) {
        if (extraStatus == null) return;
        ExtraLimit extra = Account.extra(this);
        long now = System.currentTimeMillis();
        boolean active = extra.active(now);
        StringBuilder lines = new StringBuilder();
        if (extra.all(now)) lines.append("Her şey kısıtlı · ").append(ExtraLimit.until(extra.allUntil, now));
        if (extra.video(now)) lines.append(lines.length() > 0 ? "\n" : "").append("Video ve film kısıtlı · ").append(ExtraLimit.until(extra.videoUntil, now));
        extraStatus.setText(active ? lines.toString()
            : "Kendine bir süre daha kısıt koy. Süre dolmadan yalnız kardeşin bitirebilir.");
        extraStart.setText(active ? "Uzat" : "Ek kısıt başlat");
        boolean noGuardian = Account.guard(this).guardian == null;
        extraEnd.setVisibility(active && !noGuardian && !extra.endPending ? View.VISIBLE : View.GONE);
        String note = message != null ? message
            : !active ? ""
            : noGuardian ? "Kardeş ekli olmadığı için süre dolmadan bitmez."
            : extra.endPending ? "Kardeşinin kararı bekleniyor." : "";
        extraNote.setText(note);
        extraNote.setVisibility(note.isEmpty() ? View.GONE : View.VISIBLE);
    }
    private void chooseExtraScope() {
        String[] scopes = {"all", "video"};
        Ui.show(new AlertDialog.Builder(this).setTitle("Ne kısıtlansın?")
            .setItems(new String[] {"Her şey (telefon ve bilgisayar)", "Video ve film siteleri (bilgisayar)"},
                (dialog, which) -> chooseExtraDuration(scopes[which]))
            .setNegativeButton("Vazgeç", null));
    }
    private void chooseExtraDuration(String scope) {
        Object[] minutes = {30, 60, 120, 240, "day"};
        Ui.show(new AlertDialog.Builder(this).setTitle("Ne kadar sürsün?")
            .setItems(new String[] {"30 dakika", "1 saat", "2 saat", "4 saat", "Gün sonuna kadar (03.00)"},
                (dialog, which) -> confirmExtra(scope, minutes[which]))
            .setNegativeButton("Vazgeç", null));
    }
    private void confirmExtra(String scope, Object minutes) {
        Ui.show(new AlertDialog.Builder(this).setTitle("Ek kısıt başlasın mı?")
            .setMessage("Başlayınca süre dolmadan kendin bitiremezsin. Erken bitirmek kardeş onayı ister.")
            .setNegativeButton("Vazgeç", null)
            .setPositiveButton("Başlat", (dialog, which) -> startExtra(scope, minutes)));
    }
    private void startExtra(String scope, Object minutes) {
        ExtraLimit before = Account.extra(this);
        long previous = scope.equals("all") ? before.allUntil : before.videoUntil;
        boolean wasActive = before.active(System.currentTimeMillis());
        showExtra("Gönderiliyor…");
        extraRequest("/v1/extra", () -> new org.json.JSONObject().put("scope", scope).put("minutes", minutes), response -> {
            ExtraLimit after = Account.extra(this);
            long until = scope.equals("all") ? after.allUntil : after.videoUntil;
            return until <= previous ? "Seçtiğin süre açık kısıttan kısa, değişiklik olmadı."
                : wasActive ? "Ek kısıt uzatıldı." : "Ek kısıt başladı.";
        });
    }
    private void askExtraEnd() {
        EditText input = new EditText(this);
        input.setFilters(new InputFilter[] {new InputFilter.LengthFilter(200)});
        input.setTextColor(Ui.TEXT);
        input.setHint("Kısa gerekçe (isteğe bağlı)");
        Ui.show(new AlertDialog.Builder(this).setTitle("Erken bitirmek için izin iste").setView(form(
                "Kardeşine mail gider. Ek kısıtı o bitirebilir.", input))
            .setNegativeButton("Vazgeç", null)
            .setPositiveButton("Gönder", (dialog, which) -> {
                String reason = input.getText().toString().trim();
                showExtra("Gönderiliyor…");
                extraRequest("/v1/extra-end", () -> new org.json.JSONObject().put("reason", reason),
                    response -> "İstek kardeşine gönderildi.");
            }));
    }
    private interface Body { org.json.JSONObject build() throws org.json.JSONException; }
    private interface Done { String message(Account.Response response); }
    private void extraRequest(String path, Body body, Done done) {
        network.execute(() -> {
            String message;
            try {
                Account.Response response = Account.authorized(this, "POST", path, body.build());
                if (response.status == 200) Account.rememberExtra(this, response.body);
                String error = response.body.optString("error");
                message = response.status == 200 ? done.message(response)
                    : error.equals("no_guardian") ? "Kardeş ekli olmadığı için süre dolmadan bitmez."
                    : error.equals("no_extra") ? "Açık bir ek kısıt yok."
                    : error.equals("mail_limit") ? "Bugünkü mail sınırı doldu. Yarın yeniden dene."
                    : "Sunucuya ulaşılamadı.";
            } catch (Exception error) {
                android.util.Log.w("BirNefes", "Extra limit request failed: " + error.getClass().getSimpleName());
                message = "Sunucuya ulaşılamadı.";
            }
            String shown = message;
            runOnUiThread(() -> { if (!isDestroyed()) showExtra(shown); });
        });
    }
    private void showPair(String code, String note) {
        pairCode.setText(code);
        pairCode.setVisibility(code.isEmpty() ? View.GONE : View.VISIBLE);
        pairNote.setText(note);
        pairNote.setVisibility(View.VISIBLE);
    }
    private void requestPairCode() {
        showPair("", "Kod alınıyor…");
        background(() -> {
            Account.Response response = Account.pairCode(this);
            String code = response.status == 200 ? response.body.optString("code", "") : "";
            runOnUiThread(() -> {
                if (code.isEmpty()) showPair("", "Sunucuya ulaşılamadı.");
                else showPair(code.toUpperCase(java.util.Locale.ROOT), "Chrome’da Bir Nefes eklentisine bu kodu gir · 10 dk");
            });
        }, true);
    }
    private void enterCode() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        int padding = Ui.dp(this, 24);
        form.setPadding(padding, Ui.dp(this, 8), padding, 0);
        form.addView(Ui.muted(this, "Diğer cihazda “Bilgisayarı bağla” ya da eklentide gösterilen 8 harfli kod. Bu telefon o hesabın haklarını kullanır.", 15));
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setFilters(new InputFilter[] {new InputFilter.AllCaps(), new InputFilter.LengthFilter(12)});
        input.setTextColor(Ui.TEXT);
        input.setHint("ABCDEFGH");
        form.addView(input, Ui.topMargin(this, 8));
        Ui.show(new AlertDialog.Builder(this).setTitle("Kodu gir").setView(form)
            .setNegativeButton("Vazgeç", null)
            .setPositiveButton("Bağlan", (dialog, which) -> {
                String code = input.getText().toString().trim();
                showPair("", "Bağlanıyor…");
                background(() -> {
                    int status;
                    try { status = Account.pair(this, code).status; }
                    catch (java.io.IOException error) { status = 0; }
                    int result = status;
                    runOnUiThread(() -> pairResult(result));
                }, true);
            }));
    }
    private void pairResult(int status) {
        boolean paired = status == 200;
        String message = paired ? "Bu telefon artık o hesabın haklarını kullanıyor."
            : status == 403 || status == 400 ? "Kod geçersiz ya da süresi doldu. Yeni kod alıp tekrar dene."
            : "Sunucuya ulaşılamadı. Bağlantını kontrol edip tekrar dene.";
        showPair("", paired ? "Bağlandı." : message);
        if (paired) refreshQuota();
        if (!isFinishing()) Ui.show(new AlertDialog.Builder(this).setTitle(paired ? "Bağlandı" : "Bağlanamadı")
            .setMessage(message).setPositiveButton("Tamam", null));
    }
    private interface Work { void run() throws Exception; }
    /** Network work off the main thread. A failure reads as an unreachable server where the user is waiting. */
    private void background(Work work, boolean visible) {
        network.execute(() -> {
            try { work.run(); }
            catch (Exception error) {
                android.util.Log.w("BirNefes", "Account request failed: " + error.getClass().getSimpleName());
                if (visible) runOnUiThread(() -> showPair("", "Sunucuya ulaşılamadı."));
            }
        });
    }
    private void refreshQuota() {
        if (!SyncConfig.ENABLED) return;
        background(() -> {
            Account.Response response = Account.authorized(this, "GET", "/v1/status", null);
            if (response.status != 200) return;
            Account.rememberGuard(this, response.body.optJSONObject("guard"));
            Account.rememberExtra(this, response.body);
            int remaining = response.body.optInt("remaining", -1);
            boolean open = "allowed".equals(response.body.optString("state"));
            runOnUiThread(() -> {
                quota.setText(open ? "Şu an bir kullanım açık" + (remaining >= 0 ? " · Bugün " + remaining + " hak kaldı" : "")
                    : remaining >= 0 ? "Bugün " + remaining + " hak kaldı" : "");
                quota.setVisibility(remaining >= 0 ? View.VISIBLE : View.GONE);
                if (!isDestroyed()) { showGuard(null); showExtra(null); }
            });
        }, false);
    }
    @Override public void onDestroy() { network.shutdownNow(); super.onDestroy(); }
    private long storedCheckDelay() { return CheckDelay.normalize(settings.getLong(CheckDelay.KEY, CheckDelay.DEFAULT_MS)); }
    private void showCheckDelay() { checkDelay.setText("Kontrol süresi · " + CheckDelay.label(storedCheckDelay())); }
    private void chooseCheckDelay() {
        boolean lock = locked();
        long[] options = lock ? CheckDelay.upTo(storedCheckDelay()) : CheckDelay.options();
        String[] labels = new String[options.length];
        int current = 0;
        for (int i = 0; i < options.length; i++) {
            labels[i] = CheckDelay.label(options[i]);
            if (options[i] == storedCheckDelay()) current = i;
        }
        LinearLayout title = new LinearLayout(this);
        title.setOrientation(LinearLayout.VERTICAL);
        int padding = Ui.dp(this, 24);
        title.setPadding(padding, padding, padding, 0);
        title.addView(Ui.text(this, "Kontrol süresi", 20, true));
        title.addView(Ui.muted(this, "Kısıtlı uygulamaya girince engel ekranı sunucunun cevabını bu kadar bekler."
            + (lock ? " Kilitliyken en fazla 1,5 sn seçilebilir." : ""), 15), Ui.topMargin(this, 6));
        Ui.show(new AlertDialog.Builder(this).setCustomTitle(title)
            .setSingleChoiceItems(labels, current, (dialog, which) -> {
                // The lock may have started while the list was open.
                if (locked() && options[which] > CheckDelay.lockedCeiling(storedCheckDelay())) { dialog.dismiss(); return; }
                settings.edit().putLong(CheckDelay.KEY, options[which]).apply();
                showCheckDelay();
                dialog.dismiss();
            }).setNegativeButton("Vazgeç", null));
    }
    private void confirmStop() {
        Ui.show(new AlertDialog.Builder(this).setTitle("Bir Nefes durdurulsun mu?")
            .setMessage("Uygulamalar engellenmez. Yeniden açmak için erişilebilirlik iznini açman gerekir.")
            .setNegativeButton("Vazgeç", null)
            .setPositiveButton("Durdur", (dialog, which) -> {
                if (locked()) { showGuard(null); return; }
                if (PauseService.instance == null) {
                    startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    return;
                }
                PauseService.instance.disableSelf();
                page.postDelayed(this::refreshStatus, STATUS_SETTLE_MS);
            }));
    }
    private boolean serviceEnabled() {
        AccessibilityManager manager = (AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
        for (AccessibilityServiceInfo info : manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK))
            if (info.getResolveInfo().serviceInfo.packageName.equals(getPackageName())) return true;
        return false;
    }
    private void refreshStatus() {
        boolean on = serviceEnabled();
        status.setText(on ? "Bir Nefes açık" : "Bir Nefes kapalı");
        statusDetail.setText(!on ? "Mesajların ve ekran içeriğin okunmaz."
            : SyncConfig.ENABLED ? "Telefon + Chrome · Ortak haklar · Yenileme 03.00" : "Yerel kullanım · Senkronizasyon kapalı");
        int whenOff = on ? View.GONE : View.VISIBLE;
        enable.setVisibility(whenOff);
        restrictedNote.setVisibility(whenOff);
        showGuard(null);
    }
    private LinearLayout updateCard() {
        updateCard = new LinearLayout(this);
        updateCard.setOrientation(LinearLayout.VERTICAL);
        int padding = Ui.dp(this, 16);
        updateCard.setPadding(padding, padding, padding, padding);
        updateCard.setBackground(Ui.shape(this, Ui.SECONDARY, Ui.PRIMARY));
        updateTitle = Ui.text(this, "", 18, true);
        updateCard.addView(updateTitle);
        updateNote = Ui.muted(this, "Kotan ve ayarların korunur.", 14);
        updateCard.addView(updateNote, Ui.topMargin(this, 6));
        updateButton = Ui.secondary(this, "Güncelle");
        updateButton.setOnClickListener(v -> {
            updateButton.setEnabled(false);
            updateNote.setText(AppUpdate.install(this, () -> runOnUiThread(() -> {
                if (isDestroyed()) return;
                updateButton.setEnabled(true);
                updateNote.setText("Kurulum ekranında Güncelle’ye bas.");
            })));
            if (!updateNote.getText().toString().startsWith("İndir")) updateButton.setEnabled(true);
        });
        updateCard.addView(updateButton);
        updateCard.setVisibility(View.GONE);
        return updateCard;
    }
    private void showUpdate(AppUpdate.Release release) {
        if (release == null) { updateCard.setVisibility(View.GONE); return; }
        updateTitle.setText("Yeni sürüm: " + release.name);
        updateCard.setVisibility(View.VISIBLE);
    }
    private void refreshUpdate() {
        showUpdate(AppUpdate.cached(this));
        network.execute(() -> {
            AppUpdate.Release release = AppUpdate.check(this);
            runOnUiThread(() -> { if (!isDestroyed()) showUpdate(release); });
        });
    }
    private void refreshCallPermission() {
        boolean granted = checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED;
        int visibility = granted ? View.GONE : View.VISIBLE;
        callPermission.setVisibility(visibility);
        callNote.setVisibility(visibility);
        if (granted && PauseService.instance != null) PauseService.instance.refreshCallWatcher();
    }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        refreshCallPermission();
    }
    @Override public void onResume() {
        super.onResume();
        refreshStatus();
        refreshCallPermission();
        showCheckDelay();
        showExtra(null);
        refreshQuota();
        refreshUpdate();
        if (getIntent().getBooleanExtra(ASK_RELEASE, false)) {
            getIntent().removeExtra(ASK_RELEASE);
            if (locked()) askRelease();
        }
    }
}
