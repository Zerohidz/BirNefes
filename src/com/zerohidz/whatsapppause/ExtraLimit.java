package com.zerohidz.whatsapppause;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Locale;

/** The self-imposed extra limit as the server last reported it, and its Turkish clock text. Plain Java. */
final class ExtraLimit {
    static final ZoneId ZONE = ZoneId.of("Europe/Istanbul");
    final long allUntil, videoUntil;
    final boolean endPending;
    ExtraLimit(long allUntil, long videoUntil, boolean endPending) {
        this.allUntil = allUntil; this.videoUntil = videoUntil; this.endPending = endPending;
    }
    boolean all(long now) { return allUntil > now; }
    boolean video(long now) { return videoUntil > now; }
    boolean active(long now) { return all(now) || video(now); }

    /** "18.30’a kadar", with "yarın" when the end is on the next Istanbul day. */
    static String until(long time, long now) {
        ZonedDateTime at = Instant.ofEpochMilli(time).atZone(ZONE);
        String clock = String.format(Locale.ROOT, "%02d.%02d", at.getHour(), at.getMinute());
        boolean tomorrow = !at.toLocalDate().equals(Instant.ofEpochMilli(now).atZone(ZONE).toLocalDate());
        return (tomorrow ? "yarın " : "") + clock + "’" + dative(at.getHour(), at.getMinute()) + " kadar";
    }

    /** The dative suffix follows the last spoken number: the minute, or the hour on the full hour. */
    static String dative(int hour, int minute) {
        int number = minute != 0 ? minute : hour;
        if (number == 0) return "a"; // sıfır
        int ones = number % 10;
        if (ones != 0) return new String[] {"", "e", "ye", "e", "e", "e", "ya", "ye", "e", "a"}[ones];
        return new String[] {"", "a", "ye", "a", "a", "ye"}[number / 10]; // on, yirmi, otuz, kırk, elli
    }
}
