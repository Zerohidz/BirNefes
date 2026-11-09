package com.zerohidz.whatsapppause;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The account's quota rules (docs/specs/hesap-kurallari.md §4.1–4.3): values, the choices that keep
 * them valid, and the Turkish text drawn from them. Plain Java, no org.json: the JSON lives in Account.
 * Times are minutes of the day on a 30-minute grid; NONE stands for a JSON null.
 */
final class Rules {
    static final int NONE = -1;
    static final int DAY = 1440;
    static final ZoneId ZONE = ZoneId.of("Europe/Istanbul");
    static final int[] WORK_CHOICES = {15, 30, 45, 60, 90, 120};
    static final String[] DAYS = {"weekday", "weekend"};
    /** Leaf paths in the server's fixed order; work.minutes is one leaf. */
    static final String[] PATHS = {
        "rights", "windowMin", "waitSec", "renewal", "nightStart", "nightWindowMin", "urgentCount", "urgentMin",
        "video.weekday.closedFrom", "video.weekday.closedUntil", "video.weekday.meteredFrom", "video.weekday.meteredUntil", "video.weekday.budgetMin",
        "video.weekend.closedFrom", "video.weekend.closedUntil", "video.weekend.meteredFrom", "video.weekend.meteredUntil", "video.weekend.budgetMin",
        "work.enabled", "work.minutes", "work.dailyMin", "work.summary"};

    /** One day type of the video schedule. */
    static final class Day {
        int closedFrom, closedUntil, meteredFrom, meteredUntil, budgetMin;
        Day(int closedFrom, int closedUntil, int meteredFrom, int meteredUntil, int budgetMin) {
            this.closedFrom = closedFrom; this.closedUntil = closedUntil;
            this.meteredFrom = meteredFrom; this.meteredUntil = meteredUntil; this.budgetMin = budgetMin;
        }
        Day copy() { return new Day(closedFrom, closedUntil, meteredFrom, meteredUntil, budgetMin); }
        boolean metered() { return meteredFrom != NONE; }
    }

    int rights, windowMin, waitSec, renewal, nightStart, nightWindowMin, urgentCount, urgentMin;
    Day weekday, weekend;
    boolean workEnabled, workSummary;
    int[] workMinutes;
    int workDailyMin;

    /** DEFAULT_RULES: today's behavior. */
    static Rules defaults() {
        Rules rules = new Rules();
        rules.rights = 5; rules.windowMin = 20; rules.waitSec = 30;
        rules.renewal = 3 * 60; rules.nightStart = 22 * 60 + 30; rules.nightWindowMin = 5;
        rules.urgentCount = 4; rules.urgentMin = 20;
        rules.weekday = new Day(22 * 60 + 30, 5 * 60, 8 * 60, 17 * 60, 10);
        rules.weekend = new Day(22 * 60 + 30, 5 * 60, NONE, NONE, 0);
        rules.workEnabled = true; rules.workMinutes = new int[] {15, 30, 60, 90}; rules.workDailyMin = 180; rules.workSummary = true;
        return rules;
    }
    Rules copy() {
        Rules rules = new Rules();
        rules.rights = rights; rules.windowMin = windowMin; rules.waitSec = waitSec;
        rules.renewal = renewal; rules.nightStart = nightStart; rules.nightWindowMin = nightWindowMin;
        rules.urgentCount = urgentCount; rules.urgentMin = urgentMin;
        rules.weekday = weekday.copy(); rules.weekend = weekend.copy();
        rules.workEnabled = workEnabled; rules.workMinutes = workMinutes.clone();
        rules.workDailyMin = workDailyMin; rules.workSummary = workSummary;
        return rules;
    }
    Day day(String key) {
        if (key.equals("weekday")) return weekday;
        if (key.equals("weekend")) return weekend;
        throw new IllegalArgumentException(key);
    }
    /** Minutes after the renewal: the budget day's own clock (§4.2). */
    int off(int time) { return Math.floorMod(time - renewal, DAY); }
    /** A closing time, with "never closes" at the end of the budget day. */
    private int offClose(int time) { return time == NONE ? DAY : off(time); }

    // ---- Integer fields by path ----

    int get(String path) {
        switch (path) {
            case "rights": return rights;
            case "windowMin": return windowMin;
            case "waitSec": return waitSec;
            case "renewal": return renewal;
            case "nightStart": return nightStart;
            case "nightWindowMin": return nightWindowMin;
            case "urgentCount": return urgentCount;
            case "urgentMin": return urgentMin;
            case "work.dailyMin": return workDailyMin;
            default:
        }
        String[] parts = path.split("\\.");
        if (parts.length == 3 && parts[0].equals("video")) {
            Day day = day(parts[1]);
            switch (parts[2]) {
                case "closedFrom": return day.closedFrom;
                case "closedUntil": return day.closedUntil;
                case "meteredFrom": return day.meteredFrom;
                case "meteredUntil": return day.meteredUntil;
                case "budgetMin": return day.budgetMin;
                default:
            }
        }
        throw new IllegalArgumentException(path);
    }
    /** A copy with one integer field changed. */
    Rules with(String path, int value) {
        Rules rules = copy();
        switch (path) {
            case "rights": rules.rights = value; return rules;
            case "windowMin": rules.windowMin = value; return rules;
            case "waitSec": rules.waitSec = value; return rules;
            case "renewal": rules.renewal = value; return rules;
            case "nightStart": rules.nightStart = value; return rules;
            case "nightWindowMin": rules.nightWindowMin = value; return rules;
            case "urgentCount": rules.urgentCount = value; return rules;
            case "urgentMin": rules.urgentMin = value; return rules;
            case "work.dailyMin": rules.workDailyMin = value; return rules;
            default:
        }
        String[] parts = path.split("\\.");
        if (parts.length == 3 && parts[0].equals("video")) {
            Day day = rules.day(parts[1]);
            switch (parts[2]) {
                case "closedFrom": day.closedFrom = value; return rules;
                case "closedUntil": day.closedUntil = value; return rules;
                case "meteredFrom": day.meteredFrom = value; return rules;
                case "meteredUntil": day.meteredUntil = value; return rules;
                case "budgetMin": day.budgetMin = value; return rules;
                default:
            }
        }
        throw new IllegalArgumentException(path);
    }
    /** Turns a day's metered window on (08.00–17.00, 10 minutes when that fits) or off. */
    Rules withMetered(String key, boolean on) {
        Rules rules = copy();
        Day day = rules.day(key);
        if (!on) { day.meteredFrom = NONE; day.meteredUntil = NONE; day.budgetMin = 0; return rules; }
        if (day.metered()) return rules;
        day.budgetMin = 10;
        day.meteredFrom = 8 * 60; day.meteredUntil = 17 * 60;
        if (rules.invalid() == null) return rules;
        // Otherwise the first half hour after the morning opening.
        day.meteredFrom = day.closedUntil; day.meteredUntil = (day.closedUntil + 30) % DAY;
        return rules;
    }
    Rules withWorkMinutes(int[] minutes) { Rules rules = copy(); rules.workMinutes = minutes.clone(); return rules; }
    Rules withWorkEnabled(boolean on) { Rules rules = copy(); rules.workEnabled = on; return rules; }
    Rules withWorkSummary(boolean on) { Rules rules = copy(); rules.workSummary = on; return rules; }

    // ---- Validation, §4.3 ----

    private static boolean step(int value, int min, int max, int step) { return value >= min && value <= max && value % step == 0; }
    private static boolean time(int value) { return value >= 0 && value < DAY && value % 30 == 0; }
    /** The path of the first invalid field, or null. */
    String invalid() {
        if (!step(rights, 0, 10, 1)) return "rights";
        if (!step(windowMin, 5, 60, 5)) return "windowMin";
        if (!step(waitSec, 0, 300, 5)) return "waitSec";
        if (!step(renewal, 0, 6 * 60, 30)) return "renewal";
        if (!time(nightStart) || off(nightStart) < 720 || off(nightStart) > 1380) return "nightStart";
        if (!step(nightWindowMin, 0, 20, 5)) return "nightWindowMin";
        if (!step(urgentCount, 0, 8, 1)) return "urgentCount";
        if (!step(urgentMin, 5, 30, 5)) return "urgentMin";
        for (String key : DAYS) {
            Day day = day(key);
            String at = "video." + key + ".";
            if (day.closedFrom != NONE && (!time(day.closedFrom) || off(day.closedFrom) < 720)) return at + "closedFrom";
            if (!time(day.closedUntil) || off(day.closedUntil) >= 720) return at + "closedUntil";
            if ((day.meteredFrom == NONE) != (day.meteredUntil == NONE)) return at + "meteredFrom";
            if (day.metered()) {
                if (!time(day.meteredFrom) || off(day.meteredFrom) < off(day.closedUntil)) return at + "meteredFrom";
                if (!time(day.meteredUntil) || off(day.meteredUntil) <= off(day.meteredFrom)
                    || off(day.meteredUntil) > offClose(day.closedFrom)) return at + "meteredUntil";
                if (!step(day.budgetMin, 0, 120, 5)) return at + "budgetMin";
            } else if (day.budgetMin != 0) return at + "budgetMin";
        }
        if (workMinutes == null || workMinutes.length == 0) return "work.minutes";
        for (int i = 0; i < workMinutes.length; i++) {
            boolean known = false;
            for (int choice : WORK_CHOICES) known |= choice == workMinutes[i];
            if (!known || (i > 0 && workMinutes[i] <= workMinutes[i - 1])) return "work.minutes";
        }
        if (!step(workDailyMin, 15, 480, 15)) return "work.dailyMin";
        return null;
    }

    // ---- Choices: every value the field can take while the whole set stays valid ----

    private static int[] range(int min, int max, int step) {
        int[] values = new int[(max - min) / step + 1];
        for (int i = 0; i < values.length; i++) values[i] = min + i * step;
        return values;
    }
    /** Half hours of the budget day, starting at the renewal; closedFrom also gets NONE at the end. */
    private int[] clockCandidates(boolean never) {
        int[] values = new int[DAY / 30 + (never ? 1 : 0)];
        for (int i = 0; i < DAY / 30; i++) values[i] = (renewal + i * 30) % DAY;
        if (never) values[values.length - 1] = NONE;
        return values;
    }
    private int[] candidates(String path) {
        switch (path) {
            case "rights": return range(0, 10, 1);
            case "windowMin": return range(5, 60, 5);
            case "waitSec": return range(0, 300, 5);
            case "renewal": return range(0, 6 * 60, 30);
            case "nightStart": return clockCandidates(false);
            case "nightWindowMin": return range(0, 20, 5);
            case "urgentCount": return range(0, 8, 1);
            case "urgentMin": return range(5, 30, 5);
            case "work.dailyMin": return range(15, 480, 15);
            default:
        }
        if (path.endsWith(".closedFrom")) return clockCandidates(true);
        if (path.endsWith(".closedUntil") || path.endsWith(".meteredFrom") || path.endsWith(".meteredUntil")) return clockCandidates(false);
        if (path.endsWith(".budgetMin")) return range(0, 120, 5);
        throw new IllegalArgumentException(path);
    }
    /** The values offered for a field: those that keep the set valid, and always the current one. */
    int[] options(String path) {
        int current = get(path);
        List<Integer> valid = new ArrayList<>();
        for (int value : candidates(path))
            if (value == current || with(path, value).invalid() == null) valid.add(value);
        int[] values = new int[valid.size()];
        for (int i = 0; i < values.length; i++) values[i] = valid.get(i);
        return values;
    }

    // ---- Text ----

    /** "08.00"; NONE is empty. */
    static String clock(int time) {
        return time == NONE ? "" : String.format(Locale.ROOT, "%02d.%02d", time / 60, time % 60);
    }
    /** The JSON form, "08:00". */
    static String wire(int time) { return String.format(Locale.ROOT, "%02d:%02d", time / 60, time % 60); }
    /** "08:00" to minutes; anything else throws IllegalArgumentException. */
    static int parse(String value) {
        if (value == null || !value.matches("\\d\\d:\\d\\d")) throw new IllegalArgumentException("time");
        int hour = Integer.parseInt(value.substring(0, 2)), minute = Integer.parseInt(value.substring(3));
        if (hour > 23 || minute > 59) throw new IllegalArgumentException("time");
        return hour * 60 + minute;
    }
    static String minutes(int value) {
        if (value < 60) return value + " dakika";
        return value % 60 == 0 ? value / 60 + " saat" : value / 60 + " saat " + value % 60 + " dakika";
    }
    static String seconds(int value) {
        return value >= 60 && value % 60 == 0 ? value / 60 + " dakika" : value + " saniye";
    }
    /** "30 saniyelik", "1 dakikalık", "3 saatlik". */
    static String adjective(String duration) { return duration + (duration.endsWith("dakika") ? "lık" : "lik"); }

    // The case suffix follows the last spoken number: the minute, or the hour on the full hour.
    private static final boolean[] BACK_ONES = {true, false, false, false, false, false, true, false, false, true};
    private static final boolean[] HARD_ONES = {false, false, false, true, true, true, false, false, false, false};
    private static final boolean[] VOWEL_ONES = {false, false, true, false, false, false, true, true, false, false};
    private static final boolean[] BACK_TENS = {false, true, false, true, true, false};
    private static final boolean[] HARD_TENS = {false, false, false, false, true, false};
    private static final boolean[] VOWEL_TENS = {false, false, true, false, false, true};
    /** kind: 'a' dative (’a, ’ye), 'd' locative (’da, ’te), 'n' ablative (’dan, ’ten). */
    static String suffix(int time, char kind) {
        int hour = time / 60 % 24, minute = time % 60;
        int number = minute != 0 ? minute : hour;
        boolean back, hard, vowel;
        if (number % 10 != 0 || number == 0) {
            back = BACK_ONES[number % 10]; hard = HARD_ONES[number % 10]; vowel = VOWEL_ONES[number % 10];
        } else {
            back = BACK_TENS[number / 10]; hard = HARD_TENS[number / 10]; vowel = VOWEL_TENS[number / 10];
        }
        String a = back ? "a" : "e";
        if (kind == 'a') return (vowel ? "y" : "") + a;
        String located = (hard ? "t" : "d") + a;
        return kind == 'n' ? located + "n" : located;
    }
    /** "03.00’te" */
    static String at(int time) { return clock(time) + "’" + suffix(time, 'd'); }
    /** "22.30’dan" */
    static String from(int time) { return clock(time) + "’" + suffix(time, 'n'); }
    /** "22.30’a" */
    static String to(int time) { return clock(time) + "’" + suffix(time, 'a'); }
    private static String span(int start, int end) { return clock(start) + "–" + clock(end); }

    /** When a pending set starts, "yarın 03.00’te", on the Istanbul calendar. */
    static String when(long at, long now) {
        ZonedDateTime time = Instant.ofEpochMilli(at).atZone(ZONE);
        java.time.LocalDate today = Instant.ofEpochMilli(now).atZone(ZONE).toLocalDate();
        java.time.LocalDate date = time.toLocalDate();
        String day = date.equals(today) ? "bugün" : date.equals(today.plusDays(1)) ? "yarın"
            : String.format(Locale.ROOT, "%d.%02d", date.getDayOfMonth(), date.getMonthValue());
        return day + " " + at(time.getHour() * 60 + time.getMinute());
    }
    static String capitalized(String text) {
        return text.isEmpty() ? text : text.substring(0, 1).toUpperCase(Locale.forLanguageTag("tr")) + text.substring(1);
    }

    /** The shown value of a leaf path. */
    String value(String path) {
        switch (path) {
            case "rights": return Integer.toString(rights);
            case "windowMin": return minutes(windowMin);
            case "waitSec": return waitSec == 0 ? "Yok" : seconds(waitSec);
            case "renewal": return clock(renewal);
            case "nightStart": return clock(nightStart);
            case "nightWindowMin": return nightWindowMin == 0 ? "Hak yok" : minutes(nightWindowMin);
            case "urgentCount": return urgentCount == 0 ? "Yok" : "Ayda " + urgentCount;
            case "urgentMin": return minutes(urgentMin);
            case "work.enabled": return workEnabled ? "Açık" : "Kapalı";
            case "work.summary": return workSummary ? "Açık" : "Kapalı";
            case "work.dailyMin": return minutes(workDailyMin);
            case "work.minutes": {
                StringBuilder text = new StringBuilder();
                for (int value : workMinutes) text.append(text.length() > 0 ? ", " : "").append(value);
                return text + " dakika";
            }
            default:
        }
        int value = get(path);
        if (path.endsWith(".closedFrom")) return value == NONE ? "Kapanmaz" : clock(value);
        if (path.endsWith(".meteredFrom") || path.endsWith(".meteredUntil")) return value == NONE ? "Yok" : clock(value);
        if (path.endsWith(".budgetMin")) return minutes(value);
        return clock(value);
    }
    /** The value of one choice for a field, as the list shows it. */
    String optionLabel(String path, int value) { return with(path, value).value(path); }

    static String label(String path) {
        switch (path) {
            case "rights": return "Günlük hak";
            case "windowMin": return "Hak süresi";
            case "waitSec": return "Mola";
            case "renewal": return "Yenileme saati";
            case "nightStart": return "Gece başlangıcı";
            case "nightWindowMin": return "Gece hakkı süresi";
            case "urgentCount": return "Acele geçişi sayısı";
            case "urgentMin": return "Acele geçişi süresi";
            case "work.enabled": return "Çalışma geçişi";
            case "work.minutes": return "Çalışma geçişi süreleri";
            case "work.dailyMin": return "Çalışma geçişi günlük tavanı";
            case "work.summary": return "Kardeşe gün sonu özeti";
            default:
        }
        String[] parts = path.split("\\.");
        if (parts.length != 3) return path;
        String day = parts[1].equals("weekday") ? "Hafta içi video " : "Hafta sonu video ";
        switch (parts[2]) {
            case "closedFrom": return day + "gece kapanışı";
            case "closedUntil": return day + "sabah açılışı";
            case "meteredFrom": return day + "sayaçlı başlangıç";
            case "meteredUntil": return day + "sayaçlı bitiş";
            case "budgetMin": return day + "sayaçlı bütçe";
            default: return path;
        }
    }
    /** "Günlük hak: 5 → 4" */
    static String change(String path, Rules before, Rules after) {
        return label(path) + ": " + before.value(path) + " → " + after.value(path);
    }
    /** Leaf paths whose value differs, in the fixed order. */
    static List<String> changes(Rules before, Rules after) {
        List<String> paths = new ArrayList<>();
        for (String path : PATHS) if (!before.value(path).equals(after.value(path))) paths.add(path);
        return paths;
    }
    boolean same(Rules other) { return changes(this, other).isEmpty(); }

    // ---- Summaries for the Kurallar screen and the main screen ----

    String dailyText() {
        String head = rights == 0
            ? "İstisnalar dışındaki telefon uygulamaları ve Chrome’daki mesajlaşma siteleri için günlük hak yoktur, yalnız acele geçişi açar."
            : "İstisnalar dışındaki tüm telefon uygulamaları ve Chrome’daki mesajlaşma siteleri için günde toplam " + rights
                + " ortak hak vardır. Her hak " + (waitSec == 0 ? "beklemeden başlar" : adjective(seconds(waitSec)) + " moladan sonra başlar")
                + " ve " + minutes(windowMin) + " sürer. Çıkıp girmen süreyi uzatmaz. Süre dolunca engelleyici ekran açılır.";
        return head + " Haklar her gün " + at(renewal) + " yenilenir.";
    }
    String nightText() {
        if (nightWindowMin == 0)
            return from(nightStart) + " sonra yeni hak açılmaz. " + to(nightStart) + " taşan açık oturum o anda biter.";
        return from(nightStart) + " sonra kalan haklardan yalnızca biri, " + minutes(nightWindowMin) + " kullanılabilir. "
            + to(nightStart) + " taşan açık oturum da bu son hak sayılır ve en geç " + at((nightStart + nightWindowMin) % DAY) + " biter.";
    }
    String urgentText() {
        if (urgentCount == 0) return "Acele geçişi yok. Telefon ve SMS her zaman açıktır.";
        return "Ayda " + urgentCount + " ortak hak. Beklemeden " + minutes(urgentMin)
            + " açılır, günlük haklardan düşmez ve gece kısalmaz. Ayın 1’inde " + at(renewal) + " yenilenir. Telefon ve SMS her zaman açıktır.";
    }
    /** One day type: "05.00–08.00 serbest, 08.00–17.00 toplam 10 dakika, 17.00–22.30 serbest, 22.30–05.00 kapalı" */
    String dayText(String key) {
        Day day = day(key);
        List<String> parts = new ArrayList<>();
        int free = day.closedUntil;
        if (day.metered()) {
            if (off(day.meteredFrom) > off(day.closedUntil)) parts.add(span(day.closedUntil, day.meteredFrom) + " serbest");
            parts.add(span(day.meteredFrom, day.meteredUntil) + (day.budgetMin == 0 ? " kapalı" : " toplam " + minutes(day.budgetMin)));
            free = day.meteredUntil;
        }
        boolean closed = day.closedFrom != NONE || off(day.closedUntil) > 0;
        if (!closed && !day.metered()) return "gün boyu serbest";
        if (offClose(day.closedFrom) > off(free)) parts.add(span(free, day.closedFrom == NONE ? renewal : day.closedFrom) + " serbest");
        if (closed) parts.add(span(day.closedFrom == NONE ? renewal : day.closedFrom, day.closedUntil) + " kapalı");
        return String.join(", ", parts);
    }
    String videoText() {
        boolean metered = weekday.metered() || weekend.metered();
        return "Telefondaki YouTube uygulaması ve Chrome’daki video siteleri aynı kurallarla açılır, mola bunları açmaz ve istisna yapılamaz. "
            + "Hafta içi " + dayText("weekday") + ". Cumartesi ve pazar " + dayText("weekend") + "."
            + (metered ? " Sayaçlı süre telefon ve bilgisayarda ortaktır, parça parça kullanılabilir." : "")
            + " Telefonda tarayıcılar da genel uygulama kotasına dahildir.";
    }
    String workText() {
        if (!workEnabled) return "Çalışma geçişi kapalı.";
        return "Sayaçlı video saatlerinde iş ya da ders için bilgisayardan yeminli çalışma geçişi açılabilir. Süre seçenekleri: "
            + value("work.minutes") + ". Günde en fazla " + minutes(workDailyMin) + "."
            + (workSummary ? " Kardeşe gün sonunda özet gider." : " Kardeşe özet gitmez.");
    }
    String extraText() {
        return "Ana ekrandan kendine 30 dakikadan gün sonuna (" + clock(renewal) + ") kadar ek kısıt koyabilirsin. “Her şey” seçilirse telefonda ve Chrome’da yeni hak açılmaz, yalnız acele geçişi kullanılabilir; “Video” telefondaki YouTube uygulamasını ve bilgisayardaki video ve film sitelerini kapatır. Süre uzatılabilir ama kısaltılamaz, erken bitirmeye yalnız kardeşin karar verir.";
    }
    /** The main screen's status line. */
    String statusLine() { return "Telefon + Chrome · Ortak haklar · Yenileme " + clock(renewal); }
    /** The extra limit's "until the end of the day" choice. */
    String dayEndChoice() { return "Gün sonuna kadar (" + clock(renewal) + ")"; }
}
