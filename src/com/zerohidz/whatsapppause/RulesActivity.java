package com.zerohidz.whatsapppause;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The account's rules. A synced build edits them: a change starts at the next renewal, and while the
 * guardian lock holds the server takes only stricter sets. A local-only build shows the defaults.
 */
public final class RulesActivity extends Activity {
    static final String LOCKED = "Kilit açıkken yalnız sıkılaştırabilirsin. Gevşetmek için ana ekrandan izin iste.";
    private LinearLayout page;
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    /** In force, waiting for the next renewal (or null), and the one being edited. */
    private Rules current, pending, edited;
    private int currentVersion, pendingVersion;
    private long pendingAt, serverOffset;
    private boolean pendingLooser, locked, loaded, busy;
    private String note;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        page = Ui.page(this);
        if (!SyncConfig.ENABLED) { readOnly(Rules.defaults()); return; }
        current = Account.rules(this);
        edited = current.copy();
        locked = Account.locked(this);
        note = "Kurallar yükleniyor…";
        render();
        load();
    }
    @Override public void onDestroy() { network.shutdownNow(); super.onDestroy(); }

    private void section(String title, String body) {
        page.addView(Ui.text(this, title, 18, true), Ui.topMargin(this, 24));
        page.addView(Ui.text(this, body, 16, false), Ui.topMargin(this, 6));
    }
    private void heading(String title, String summary) {
        page.addView(Ui.text(this, title, 18, true), Ui.topMargin(this, 28));
        if (summary != null) page.addView(Ui.muted(this, summary, 14), Ui.topMargin(this, 6));
    }
    private void fixedSections(Rules rules) {
        if (SyncConfig.ENABLED) section("Ek kısıt", rules.extraText());
        section("İzinler ve gizlilik", "Erişilebilirlik izni, açılan uygulamanın paket adını kontrol etmek ve mola ekranını göstermek için kullanılır. Mesajların ve ekran içeriğin okunmaz. Çağrı durumu izni, zil çalarken mola ekranını gizleyip aramayı açabilmen içindir: yalnız çağrı durumu okunur, numara okunmaz, kaydedilmez ve gönderilmez.");
        section("Kontrol süresi", "Kısıtlı uygulamaya girince engel ekranı sunucunun cevabını bu kadar bekler. 0,5 ile 3 saniye arasında seçilir, varsayılanı 1 saniyedir.");
        section("Senkronizasyon", SyncConfig.ENABLED
            ? "Telefon ve Chrome aynı hakları paylaşır. Yalnızca kullanım hakkı bilgisi kendi sunucuna (zmanga.zerohidz.com) HTTPS ile gönderilir. Mesajların gönderilmez. Yenileme saati İstanbul saatine göredir."
            : "Yerel kullanım. Senkronizasyon kapalı, hiçbir bilgi gönderilmez.");
    }
    /** Local-only build: today's text, drawn from the defaults. */
    private void readOnly(Rules rules) {
        page.addView(Ui.header(this, "Kurallar"));
        section("Günlük haklar", rules.dailyText());
        section("Gece", rules.nightText());
        section("Acele geçişi", rules.urgentText());
        section("YouTube, video ve film", rules.videoText());
        fixedSections(rules);
    }

    // ---- Editor ----

    private Rules baseline() { return pending != null ? pending : current; }
    private int baseVersion() { return pending != null ? pendingVersion : currentVersion; }
    private long serverNow() { return System.currentTimeMillis() + serverOffset; }

    private void render() {
        page.removeAllViews();
        page.addView(Ui.header(this, "Kurallar"));
        page.addView(Ui.muted(this, "Değişiklik bir sonraki yenilemede geçerli olur."
            + (locked ? " Kardeş kilidi açıkken yalnız sıkılaştırabilirsin." : ""), 15), Ui.topMargin(this, 8));
        if (note != null) page.addView(Ui.text(this, note, 15, false), Ui.topMargin(this, 8));
        if (pending != null) page.addView(pendingCard(), Ui.topMargin(this, 16));

        heading("Günlük haklar", edited.dailyText());
        row("Günlük hak", "rights", "Telefon ve Chrome’daki mesajlaşma siteleri bu hakları paylaşır.");
        row("Hak süresi", "windowMin", null);
        row("Mola", "waitSec", "Her hak bu beklemeden sonra başlar.");

        heading("Gece", edited.nightText());
        row("Başlangıç", "nightStart", "Yenilemeden en az 12 saat sonra, en az 1 saat önce.");
        row("Gece hakkı süresi", "nightWindowMin", "Hak yok seçilirse gece yeni hak açılmaz.");

        heading("Yenileme saati", "Haklar, gece kuralı ve video günü bu saatte yenilenir.");
        row("Yenileme", "renewal", "00.00 ile 06.00 arası. Gece ve video saatleriyle uyumlu olanlar gösterilir.");

        heading("Acele geçişi", edited.urgentText());
        row("Ayda", "urgentCount", null);
        row("Süre", "urgentMin", null);

        heading("YouTube, video ve film", "Telefondaki YouTube uygulaması ve Chrome’daki video siteleri aynı kurallarla açılır. Sayaçlı süre telefon ve bilgisayarda ortaktır.");
        videoDay("weekday", "Hafta içi");
        videoDay("weekend", "Cumartesi ve pazar");

        heading("Çalışma geçişi", edited.workText());
        toggle("Çalışma geçişi", edited.workEnabled, on -> edited = edited.withWorkEnabled(on));
        if (edited.workEnabled) {
            Ui.row(this, page, "Süre seçenekleri", edited.value("work.minutes"), loaded && !busy ? v -> chooseWorkMinutes() : null);
            row("Günlük tavan", "work.dailyMin", null);
            toggle("Kardeşe gün sonu özeti", edited.workSummary, on -> edited = edited.withWorkSummary(on));
        }

        Button save = Ui.primary(this, "Kaydet");
        boolean changed = loaded && !edited.same(baseline());
        save.setEnabled(changed && !busy);
        save.setAlpha(changed && !busy ? 1f : 0.5f);
        save.setOnClickListener(v -> save());
        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) save.getLayoutParams();
        params.topMargin = Ui.dp(this, 24);
        page.addView(save, params);

        fixedSections(current);
    }
    private LinearLayout pendingCard() {
        LinearLayout card = Ui.card(this);
        card.addView(Ui.text(this, "Bekleyen değişiklik", 18, true));
        StringBuilder lines = new StringBuilder();
        for (String path : Rules.changes(current, pending)) lines.append(lines.length() > 0 ? "\n" : "").append(Rules.change(path, current, pending));
        if (lines.length() > 0) card.addView(Ui.muted(this, lines.toString(), 14), Ui.topMargin(this, 6));
        TextView when = Ui.text(this, Rules.capitalized(Rules.when(pendingAt, serverNow())) + " geçerli"
            + (pendingLooser ? " · gevşetme" : ""), 15, true);
        when.setTextColor(Ui.PRIMARY);
        card.addView(when, Ui.topMargin(this, 8));
        Button cancel = Ui.secondary(this, "Vazgeç");
        cancel.setEnabled(!busy);
        cancel.setOnClickListener(v -> cancelPending());
        card.addView(cancel);
        return card;
    }
    private void videoDay(String key, String title) {
        Rules.Day day = edited.day(key);
        page.addView(Ui.text(this, title, 16, true), Ui.topMargin(this, 16));
        page.addView(Ui.muted(this, Rules.capitalized(edited.dayText(key)) + ".", 14), Ui.topMargin(this, 4));
        String at = "video." + key + ".";
        row("Gece kapanışı", at + "closedFrom", "Bu saatten yenilemeye kadar video kapalı.");
        row("Sabah açılışı", at + "closedUntil", "Yenilemeden bu saate kadar video kapalı.");
        toggle("Sayaçlı pencere", day.metered(), on -> edited = edited.withMetered(key, on));
        if (day.metered()) {
            row("Sayaçlı başlangıç", at + "meteredFrom", null);
            row("Sayaçlı bitiş", at + "meteredUntil", null);
            row("Sayaçlı bütçe", at + "budgetMin", "Bu pencerede telefon ve bilgisayar toplamı.");
        }
    }
    private void row(String label, String path, String explanation) {
        Ui.row(this, page, label, edited.value(path), loaded && !busy ? v -> choose(label, path, explanation) : null);
    }
    private View title(String title, String explanation) {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.VERTICAL);
        int padding = Ui.dp(this, 24);
        view.setPadding(padding, padding, padding, 0);
        view.addView(Ui.text(this, title, 20, true));
        if (explanation != null) view.addView(Ui.muted(this, explanation, 15), Ui.topMargin(this, 6));
        return view;
    }
    /** The control-delay pattern: a single-choice list of the values that keep the set valid. */
    private void choose(String label, String path, String explanation) {
        int[] options = edited.options(path);
        String[] labels = new String[options.length];
        int checked = 0;
        for (int i = 0; i < options.length; i++) {
            labels[i] = edited.optionLabel(path, options[i]);
            if (options[i] == edited.get(path)) checked = i;
        }
        Ui.show(new AlertDialog.Builder(this).setCustomTitle(title(label, explanation))
            .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                edited = edited.with(path, options[which]);
                dialog.dismiss();
                render();
            }).setNegativeButton("Vazgeç", null));
    }
    private interface Toggle { void set(boolean on); }
    private void toggle(String label, boolean value, Toggle toggle) {
        Ui.row(this, page, label, value ? "Açık" : "Kapalı", !loaded || busy ? null : v ->
            Ui.show(new AlertDialog.Builder(this).setCustomTitle(title(label, null))
                .setSingleChoiceItems(new String[] {"Açık", "Kapalı"}, value ? 0 : 1, (dialog, which) -> {
                    toggle.set(which == 0);
                    dialog.dismiss();
                    render();
                }).setNegativeButton("Vazgeç", null)));
    }
    private void chooseWorkMinutes() {
        int[] choices = Rules.WORK_CHOICES;
        String[] labels = new String[choices.length];
        boolean[] checked = new boolean[choices.length];
        for (int i = 0; i < choices.length; i++) {
            labels[i] = Rules.minutes(choices[i]);
            for (int value : edited.workMinutes) checked[i] |= value == choices[i];
        }
        Ui.show(new AlertDialog.Builder(this).setCustomTitle(title("Süre seçenekleri", "Çalışma geçişi açılırken bu sürelerden biri seçilir. En az biri gerekir."))
            .setMultiChoiceItems(labels, checked, (dialog, which, on) -> checked[which] = on)
            .setNegativeButton("Vazgeç", null)
            .setPositiveButton("Tamam", (dialog, which) -> {
                List<Integer> chosen = new ArrayList<>();
                for (int i = 0; i < choices.length; i++) if (checked[i]) chosen.add(choices[i]);
                if (chosen.isEmpty()) { message("Değişmedi", "En az bir süre seçmelisin."); return; }
                int[] minutes = new int[chosen.size()];
                for (int i = 0; i < minutes.length; i++) minutes[i] = chosen.get(i);
                edited = edited.withWorkMinutes(minutes);
                render();
            }));
    }

    // ---- Server ----

    private void message(String title, String text) {
        if (!isFinishing()) Ui.show(new AlertDialog.Builder(this).setTitle(title).setMessage(text).setPositiveButton("Tamam", null));
    }
    /** Reads a GET /v1/rules body; anything malformed throws and keeps the screen as it was. */
    private void apply(JSONObject body) throws Exception {
        JSONObject now = body.getJSONObject("current");
        Rules nowRules = Account.rulesFrom(now.getJSONObject("rules"));
        JSONObject next = body.optJSONObject("pending");
        Rules nextRules = next == null ? null : Account.rulesFrom(next.getJSONObject("rules"));
        current = nowRules;
        currentVersion = now.optInt("version");
        pending = nextRules;
        pendingVersion = next == null ? 0 : next.optInt("version");
        pendingAt = next == null ? 0 : next.optLong("effectiveAt");
        pendingLooser = next != null && next.optBoolean("looser");
        locked = body.optBoolean("locked");
        if (body.has("serverNow")) serverOffset = body.getLong("serverNow") - System.currentTimeMillis();
        edited = baseline().copy();
        loaded = true;
    }
    private interface Call { Account.Response run() throws Exception; }
    private interface Answer { void handle(Account.Response response); }
    /** Network work off the main thread; the answer comes back on it. A failure is an unreachable server. */
    private void request(Call call, Answer answer) {
        busy = true;
        render();
        network.execute(() -> {
            Account.Response response;
            try { response = call.run(); }
            catch (Exception error) {
                android.util.Log.w("BirNefes", "Rules request failed: " + error.getClass().getSimpleName());
                response = null;
            }
            Account.Response result = response;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                busy = false;
                answer.handle(result);
                render();
            });
        });
    }
    private void load() {
        request(() -> Account.rulesView(this), response -> {
            note = null;
            if (response == null || response.status != 200) {
                note = response != null && response.status == 404 ? "Sunucu kural değişikliğini henüz desteklemiyor."
                    : "Sunucuya ulaşılamadı. Değiştirmek için bağlantı gerekir.";
                return;
            }
            try { apply(response.body); } catch (Exception error) { note = "Sunucunun cevabı okunamadı."; }
        });
    }
    /** A failed save or check, in words; reloads when another device changed the rules first. */
    private String failure(Account.Response response) {
        if (response == null) return "Sunucuya ulaşılamadı.";
        String error = response.body.optString("error");
        if (response.status == 409 && error.equals("locked")) return LOCKED;
        if (response.status == 409 && error.equals("stale")) {
            load();
            return "Kurallar başka bir cihazda değişti. Güncel hâli yüklendi, değişikliğini yeniden yap.";
        }
        if (response.status == 400 && error.equals("invalid_rules")) {
            String field = response.body.optString("field");
            return "Sunucu bu değeri kabul etmedi" + (field.isEmpty() ? "." : ": " + Rules.label(field) + ".");
        }
        if (response.status == 404) return "Sunucu kural değişikliğini henüz desteklemiyor.";
        return "Sunucuya ulaşılamadı.";
    }
    private void save() {
        Rules proposal = edited.copy();
        if (pending != null && proposal.same(current)) {
            Ui.show(new AlertDialog.Builder(this).setTitle("Değişiklik yok")
                .setMessage("Bu kurallar şu an geçerli olanlarla aynı. Bekleyen değişiklik kaldırılsın mı?")
                .setNegativeButton("Vazgeç", null)
                .setPositiveButton("Kaldır", (dialog, which) -> cancelPending()));
            return;
        }
        int base = baseVersion();
        request(() -> Account.saveRules(this, proposal, base, true), response -> {
            if (response == null || response.status != 200) { message("Kaydedilemedi", failure(response)); return; }
            summary(proposal, base, response.body);
        });
    }
    /** The dry run's answer: what changes, whether it loosens, and when it starts. */
    private void summary(Rules proposal, int base, JSONObject check) {
        List<String> paths = new ArrayList<>();
        JSONArray changes = check.optJSONArray("changes");
        if (changes != null) for (int i = 0; i < changes.length(); i++) paths.add(changes.optString(i));
        else paths = Rules.changes(current, proposal);
        StringBuilder text = new StringBuilder();
        for (String path : paths) {
            boolean known = false;
            for (String leaf : Rules.PATHS) known |= leaf.equals(path);
            text.append(known ? Rules.change(path, current, proposal) : Rules.label(path)).append('\n');
        }
        if (paths.isEmpty()) text.append("Şu an geçerli kurallardan farkı yok.\n");
        boolean looser = check.optBoolean("looser");
        boolean allowed = check.optBoolean("allowed", true);
        long effective = check.optLong("effectiveAt");
        text.append('\n').append(looser ? "Bu değişiklik kuralları gevşetiyor." : "Bu değişiklik kuralları gevşetmiyor.");
        if (effective > 0) text.append('\n').append(Rules.capitalized(Rules.when(effective, serverNow()))).append(" geçerli olur.");
        if (pending != null) text.append('\n').append("Bekleyen değişikliğin yerine geçer.");
        if (!allowed) {
            text.append("\n\n").append(LOCKED);
            Ui.show(new AlertDialog.Builder(this).setTitle("Kaydedilemez").setMessage(text.toString()).setPositiveButton("Tamam", null));
            return;
        }
        Ui.show(new AlertDialog.Builder(this).setTitle("Değişiklik özeti").setMessage(text.toString())
            .setNegativeButton("Vazgeç", null)
            .setPositiveButton("Kaydet", (dialog, which) -> request(() -> Account.saveRules(this, proposal, base, false), response -> {
                if (response == null || response.status != 200) { message("Kaydedilemedi", failure(response)); return; }
                try { apply(response.body); note = "Kaydedildi."; }
                catch (Exception error) { load(); }
            })));
    }
    private void cancelPending() {
        request(() -> Account.cancelRules(this), response -> {
            if (response == null || response.status != 200) { message("Kaldırılamadı", failure(response)); return; }
            try { apply(response.body); note = "Bekleyen değişiklik kaldırıldı."; }
            catch (Exception error) { load(); }
        });
    }
}
