package com.zerohidz.whatsapppause;

import java.time.ZonedDateTime;
import java.util.Arrays;

public class RulesTest {
 static int checks;
 static void check(boolean value, String name) { checks++; if (!value) throw new AssertionError(name); }
 static int t(int hour, int minute) { return hour * 60 + minute; }
 static long at(int day, int hour, int minute) { return ZonedDateTime.of(2026, 11, day, hour, minute, 0, 0, Rules.ZONE).toInstant().toEpochMilli(); }
 /** Every offered value keeps the set valid and stays inside the §4.3 off() bounds. */
 static void optionsValid(Rules rules, String path) {
  int[] options = rules.options(path);
  check(options.length > 0, path + " has options");
  for (int value : options) check(rules.with(path, value).invalid() == null, path + " option " + value + " valid");
 }
 public static void main(String[] args) {
  Rules rules = Rules.defaults();
  check(rules.invalid() == null, "defaults valid");

  // Clock labels and wire form.
  check(Rules.clock(t(8, 0)).equals("08.00"), "clock 08.00");
  check(Rules.clock(t(22, 30)).equals("22.30"), "clock 22.30");
  check(Rules.wire(t(5, 0)).equals("05:00"), "wire 05:00");
  check(Rules.parse("22:30") == t(22, 30), "parse 22:30");
  boolean threw = false;
  try { Rules.parse("24:00"); } catch (IllegalArgumentException error) { threw = true; }
  check(threw, "parse rejects 24:00");
  check(Rules.at(t(3, 0)).equals("03.00’te"), "üçte");
  check(Rules.at(t(5, 0)).equals("05.00’te"), "beşte");
  check(Rules.at(t(6, 0)).equals("06.00’da"), "altıda");
  check(Rules.at(t(0, 0)).equals("00.00’da"), "sıfırda");
  check(Rules.at(t(4, 30)).equals("04.30’da"), "otuzda");
  check(Rules.at(t(22, 35)).equals("22.35’te"), "beşte, minutes");
  check(Rules.at(t(20, 0)).equals("20.00’de"), "yirmide");
  check(Rules.at(t(21, 40)).equals("21.40’ta"), "kırkta");
  check(Rules.from(t(22, 30)).equals("22.30’dan"), "otuzdan");
  check(Rules.from(t(21, 0)).equals("21.00’den"), "birden");
  check(Rules.from(t(23, 0)).equals("23.00’ten"), "üçten");
  check(Rules.to(t(22, 30)).equals("22.30’a"), "otuza");
  check(Rules.to(t(22, 0)).equals("22.00’ye"), "ikiye");
  check(Rules.minutes(20).equals("20 dakika") && Rules.minutes(180).equals("3 saat") && Rules.minutes(90).equals("1 saat 30 dakika"), "minutes");
  check(Rules.seconds(30).equals("30 saniye") && Rules.seconds(120).equals("2 dakika") && Rules.seconds(90).equals("90 saniye"), "seconds");
  check(Rules.when(at(10, 3, 0), at(9, 15, 0)).equals("yarın 03.00’te"), "tomorrow");
  check(Rules.when(at(9, 22, 30), at(9, 15, 0)).equals("bugün 22.30’da"), "today");
  check(Rules.when(at(12, 5, 0), at(9, 15, 0)).equals("12.11 05.00’te"), "later date");
  check(Rules.capitalized("yarın 03.00’te").equals("Yarın 03.00’te"), "capitalized");

  // Choices stay inside the validation bounds.
  for (String path : new String[] {"rights", "windowMin", "waitSec", "renewal", "nightStart", "nightWindowMin", "urgentCount", "urgentMin", "work.dailyMin",
    "video.weekday.closedFrom", "video.weekday.closedUntil", "video.weekday.meteredFrom", "video.weekday.meteredUntil", "video.weekday.budgetMin",
    "video.weekend.closedFrom", "video.weekend.closedUntil"}) optionsValid(rules, path);
  check(rules.options("rights").length == 11, "rights 0–10");
  check(rules.options("windowMin").length == 12 && rules.options("windowMin")[0] == 5, "window 5–60 by 5");
  check(rules.options("waitSec").length == 61, "wait 0–300 by 5");
  check(rules.options("work.dailyMin").length == 32, "daily 15–480 by 15");
  // nightStart: off in [720, 1380] from 03.00 → 15.00 .. 02.00.
  int[] night = rules.options("nightStart");
  check(night[0] == t(15, 0) && night[night.length - 1] == t(2, 0) && night.length == 23, "nightStart 15.00–02.00");
  for (int value : night) check(rules.off(value) >= 720 && rules.off(value) <= 1380, "nightStart off bound " + value);
  // closedFrom: off in [720, 1440], 1440 = null; after meteredUntil 17.00 here.
  int[] closedFrom = rules.options("video.weekday.closedFrom");
  check(closedFrom[0] == t(17, 0) && closedFrom[closedFrom.length - 1] == Rules.NONE, "closedFrom 17.00 .. never");
  int[] weekendFrom = rules.options("video.weekend.closedFrom");
  check(weekendFrom[0] == t(15, 0) && weekendFrom.length == 25, "weekend closedFrom off 720..1440");
  // closedUntil: off in [0, 720), up to meteredFrom.
  int[] closedUntil = rules.options("video.weekday.closedUntil");
  check(closedUntil[0] == t(3, 0) && closedUntil[closedUntil.length - 1] == t(8, 0), "closedUntil 03.00–08.00");
  int[] weekendUntil = rules.options("video.weekend.closedUntil");
  check(weekendUntil.length == 24 && weekendUntil[23] == t(14, 30), "weekend closedUntil off 0..690");
  int[] meteredFrom = rules.options("video.weekday.meteredFrom");
  check(meteredFrom[0] == t(5, 0) && meteredFrom[meteredFrom.length - 1] == t(16, 30), "meteredFrom 05.00–16.30");
  int[] meteredUntil = rules.options("video.weekday.meteredUntil");
  check(meteredUntil[0] == t(8, 30) && meteredUntil[meteredUntil.length - 1] == t(22, 30), "meteredUntil 08.30–22.30");
  // Renewal: 00.00–06.00, but only where the other times stay valid (closedUntil 05.00 rules out 05.30+).
  int[] renewal = rules.options("renewal");
  check(renewal[0] == 0 && renewal[renewal.length - 1] == t(5, 0), "renewal 00.00–05.00 with these times");
  check(Rules.defaults().with("renewal", t(23, 0)).invalid() != null, "renewal 23.00 invalid");
  check("nightStart".equals(Rules.defaults().with("nightStart", t(2, 30)).invalid()), "night after renewal-1h invalid");

  // Metered toggle.
  Rules weekend = rules.withMetered("weekend", true);
  check(weekend.invalid() == null && weekend.weekend.meteredFrom == t(8, 0) && weekend.weekend.budgetMin == 10, "metered on");
  Rules off = rules.withMetered("weekday", false);
  check(off.invalid() == null && off.weekday.meteredFrom == Rules.NONE && off.weekday.budgetMin == 0, "metered off");
  check(rules.withMetered("weekend", false).with("video.weekend.budgetMin", 5).invalid().equals("video.weekend.budgetMin"), "budget without metered");
  Rules late = rules.with("video.weekend.closedUntil", t(9, 0)).withMetered("weekend", true);
  check(late.invalid() == null && late.weekend.meteredFrom == t(9, 0), "metered on after a late opening");

  // Work minutes.
  check(rules.withWorkMinutes(new int[0]).invalid().equals("work.minutes"), "minutes not empty");
  check(rules.withWorkMinutes(new int[] {30, 15}).invalid().equals("work.minutes"), "minutes sorted");
  check(rules.withWorkMinutes(new int[] {20}).invalid().equals("work.minutes"), "minutes from the set");

  // Changes and their text.
  Rules edited = rules.with("rights", 4).with("video.weekday.budgetMin", 15).withWorkSummary(false);
  check(Rules.changes(rules, edited).equals(Arrays.asList("rights", "video.weekday.budgetMin", "work.summary")), "changes in fixed order");
  check(Rules.change("rights", rules, edited).equals("Günlük hak: 5 → 4"), "change line");
  check(Rules.change("video.weekday.budgetMin", rules, edited).equals("Hafta içi video sayaçlı bütçe: 10 dakika → 15 dakika"), "video change line");
  check(rules.same(rules.copy()) && !rules.same(edited), "same");
  check(rules.value("video.weekday.closedFrom").equals("22.30") && rules.with("video.weekday.closedFrom", Rules.NONE).value("video.weekday.closedFrom").equals("Kapanmaz"), "closedFrom value");
  check(rules.value("work.minutes").equals("15, 30, 60, 90 dakika"), "work minutes value");

  // Summaries.
  check(rules.dailyText().equals("İstisnalar dışındaki tüm telefon uygulamaları ve Chrome’daki mesajlaşma siteleri için günde toplam 5 ortak hak vardır. Her hak 30 saniyelik moladan sonra başlar ve 20 dakika sürer. Çıkıp girmen süreyi uzatmaz. Süre dolunca engelleyici ekran açılır. Haklar her gün 03.00’te yenilenir."), "daily text");
  check(rules.nightText().equals("22.30’dan sonra kalan haklardan yalnızca biri, 5 dakika kullanılabilir. 22.30’a taşan açık oturum da bu son hak sayılır ve en geç 22.35’te biter."), "night text");
  Rules custom = rules.with("nightStart", t(21, 0)).with("nightWindowMin", 10);
  check(custom.nightText().contains("21.00’den sonra") && custom.nightText().contains("en geç 21.10’da biter"), "custom night text");
  check(rules.urgentText().startsWith("Ayda 4 ortak hak. Beklemeden 20 dakika açılır"), "urgent text");
  check(rules.dayText("weekday").equals("05.00–08.00 serbest, 08.00–17.00 toplam 10 dakika, 17.00–22.30 serbest, 22.30–05.00 kapalı"), "weekday text");
  check(rules.dayText("weekend").equals("05.00–22.30 serbest, 22.30–05.00 kapalı"), "weekend text");
  Rules nine = rules.with("video.weekday.meteredFrom", t(9, 0)).with("video.weekday.meteredUntil", t(12, 0)).with("video.weekday.budgetMin", 30);
  check(nine.dayText("weekday").contains("09.00–12.00 toplam 30 dakika") && !nine.videoText().contains("08.00–17.00"), "custom metered text");
  Rules open = rules.withMetered("weekend", false).with("video.weekend.closedFrom", Rules.NONE).with("video.weekend.closedUntil", t(3, 0));
  check(open.dayText("weekend").equals("gün boyu serbest"), "open all day");
  check(rules.with("video.weekend.closedFrom", Rules.NONE).dayText("weekend").equals("05.00–03.00 serbest, 03.00–05.00 kapalı"), "no night closure");
  check(rules.workText().contains("Günde en fazla 3 saat") && rules.withWorkEnabled(false).workText().equals("Çalışma geçişi kapalı."), "work text");
  check(rules.with("renewal", t(4, 0)).statusLine().endsWith("Yenileme 04.00"), "status line from renewal");
  check(rules.dayEndChoice().equals("Gün sonuna kadar (03.00)"), "extra day choice");
  check(rules.with("rights", 3).with("windowMin", 10).dailyText().contains("toplam 3 ortak hak") && !rules.with("windowMin", 10).dailyText().contains("20 dakika"), "daily text values");
  System.out.println(checks + " rules checks passed");
 }
}
