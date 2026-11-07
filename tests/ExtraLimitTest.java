package com.zerohidz.whatsapppause;

import java.time.ZonedDateTime;

public class ExtraLimitTest {
 static int checks;
 static void check(boolean value, String name) { checks++; if (!value) throw new AssertionError(name); }
 static long at(int day, int hour, int minute) { return ZonedDateTime.of(2026, 11, day, hour, minute, 0, 0, ExtraLimit.ZONE).toInstant().toEpochMilli(); }
 public static void main(String[] args) {
  long now = at(7, 15, 0);
  check(ExtraLimit.until(at(7, 18, 30), now).equals("18.30’a kadar"), "otuz takes a");
  check(ExtraLimit.until(at(8, 3, 0), now).equals("yarın 03.00’e kadar"), "next day 03.00");
  check(ExtraLimit.until(at(7, 22, 0), now).equals("22.00’ye kadar"), "yirmi iki takes ye");
  check(ExtraLimit.until(at(7, 16, 0), now).equals("16.00’ya kadar"), "on altı takes ya");
  check(ExtraLimit.until(at(7, 20, 0), now).equals("20.00’ye kadar"), "yirmi takes ye");
  check(ExtraLimit.until(at(7, 19, 45), now).equals("19.45’e kadar"), "kırk beş takes e");
  check(ExtraLimit.until(at(7, 17, 10), now).equals("17.10’a kadar"), "on takes a");
  check(ExtraLimit.until(at(7, 17, 9), now).equals("17.09’a kadar"), "dokuz takes a");
  check(ExtraLimit.until(at(8, 0, 0), now).equals("yarın 00.00’a kadar"), "sıfır takes a");
  ExtraLimit limit = new ExtraLimit(now + 1, 0, false);
  check(limit.all(now) && !limit.video(now) && limit.active(now), "all active");
  check(!limit.active(now + 1), "ends at its time");
  System.out.println(checks + " extra limit checks passed");
 }
}
