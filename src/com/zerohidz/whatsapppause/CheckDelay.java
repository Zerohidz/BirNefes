package com.zerohidz.whatsapppause;

/** How long the blocker waits for the server's answer. The ceiling is fixed so it cannot become a way out. */
public final class CheckDelay {
    public static final String PREFERENCES = "settings";
    public static final String KEY = "checkDelayMs";
    public static final long MIN_MS = 500, MAX_MS = 3000, STEP_MS = 500, DEFAULT_MS = 1000, LOCKED_MAX_MS = 1500;
    private CheckDelay() {}
    public static long[] options() {
        long[] values = new long[(int) ((MAX_MS - MIN_MS) / STEP_MS) + 1];
        for (int i = 0; i < values.length; i++) values[i] = MIN_MS + i * STEP_MS;
        return values;
    }
    /** Under the guardian lock the delay can rise to 1.5 s at most; a longer one only shrinks. */
    public static long lockedCeiling(long current) { return Math.max(LOCKED_MAX_MS, normalize(current)); }
    public static long[] upTo(long current) {
        long ceiling = lockedCeiling(current);
        return java.util.Arrays.stream(options()).filter(value -> value <= ceiling).toArray();
    }
    /** Any stored value, even an edited one, ends up on a selectable step. */
    public static long normalize(long value) {
        long inRange = Math.max(MIN_MS, Math.min(MAX_MS, value));
        return inRange - inRange % STEP_MS;
    }
    public static String label(long value) {
        long half = value / STEP_MS;
        return half / 2 + (half % 2 == 0 ? "" : ",5") + " sn";
    }
}
