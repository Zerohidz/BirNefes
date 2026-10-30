package com.zerohidz.whatsapppause;

import java.time.*;

/** Shared daily budget, renewed at local 03:00. Only completed countdowns consume a fixed usage window. */
public final class DelayGate {
    public static final long DELAY_MS = 30_000;
    public static final long SESSION_MS = 900_000;
    public static final long NIGHT_MS = 300_000;
    public enum State { AWAY, WAITING, ALLOWED, EXPIRED, EXHAUSTED, CONNECTING, ERROR }
    private String day = "";
    private int used;
    private boolean nightUsed;
    private long end;
    private long waitEnd;
    private long elapsedEnd;
    private State state = State.AWAY;

    public DelayGate() {}
    public DelayGate(String day, int used, boolean nightUsed, long end) {
        this.day = day; this.used = used; this.nightUsed = nightUsed; this.end = end;
    }
    public String day() { return day; }
    public int used() { return used; }
    public boolean nightUsed() { return nightUsed; }
    public long end() { return end; }
    public State state() { return state; }
    private boolean night(long now, ZoneId zone) {
        LocalTime time = Instant.ofEpochMilli(now).atZone(zone).toLocalTime();
        return time.isBefore(LocalTime.of(3, 0)) || !time.isBefore(LocalTime.of(22, 30));
    }
    private LocalDate budgetDate(long now, ZoneId zone) {
        ZonedDateTime local = Instant.ofEpochMilli(now).atZone(zone);
        return local.toLocalTime().isBefore(LocalTime.of(3, 0))
            ? local.toLocalDate().minusDays(1) : local.toLocalDate();
    }
    private void refresh(long now, ZoneId zone) {
        LocalDate date = budgetDate(now, zone);
        if (!date.toString().equals(day)) {
            boolean wasActive = state == State.ALLOWED;
            day = date.toString(); used = 0; nightUsed = false; end = 0; elapsedEnd = 0;
            if (wasActive) state = State.EXPIRED;
        }
        long cutoff = date.atTime(22, 30).atZone(zone).toInstant().toEpochMilli();
        // An existing window crossing 22:30 is the one permitted night window,
        // even if the user was away at the cutoff.
        if (now >= cutoff && end > cutoff && !nightUsed) {
            nightUsed = true;
            end = Math.min(end, cutoff + NIGHT_MS);
        }
    }
    private boolean available(long now, ZoneId zone) {
        return used < 5 && (!night(now, zone) || !nightUsed);
    }
    private void constrainDeadline(long now, long elapsed) {
        if (end == 0) return;
        long candidate = elapsed + Math.max(0, end - now);
        elapsedEnd = elapsedEnd == 0 ? candidate : Math.min(elapsedEnd, candidate);
        if (elapsed >= elapsedEnd) end = Math.min(end, now);
    }
    public State enter(long now, long elapsed, ZoneId zone) {
        if (state != State.AWAY) return tick(now, elapsed, zone);
        refresh(now, zone);
        constrainDeadline(now, elapsed);
        if (end > now) state = State.ALLOWED;
        else if (!available(now, zone)) state = State.EXHAUSTED;
        else { state = State.WAITING; waitEnd = elapsed + DELAY_MS; }
        return state;
    }
    public State tick(long now, long elapsed, ZoneId zone) {
        refresh(now, zone);
        constrainDeadline(now, elapsed);
        if (state == State.ALLOWED && now >= end) state = State.EXPIRED;
        if (state == State.WAITING && elapsed >= waitEnd) {
            if (!available(now, zone)) state = State.EXHAUSTED;
            else {
                used++;
                boolean late = night(now, zone);
                if (late) nightUsed = true;
                long renewal = budgetDate(now, zone).plusDays(1)
                    .atTime(3, 0).atZone(zone).toInstant().toEpochMilli();
                end = Math.min(now + (late ? NIGHT_MS : SESSION_MS), renewal);
                elapsedEnd = elapsed + (end - now);
                state = State.ALLOWED;
            }
        }
        return state;
    }
    public long remaining(long elapsed) { return state == State.WAITING ? Math.max(0, waitEnd - elapsed) : 0; }
    public void leave() { state = State.AWAY; waitEnd = 0; }
}
