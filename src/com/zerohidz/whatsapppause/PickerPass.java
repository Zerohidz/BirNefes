package com.zerohidz.whatsapppause;

/** Lets the system file picker open without a block while Bir Nefes itself is waiting for it. */
public final class PickerPass {
    public static final long DURATION_MS = 300_000;
    private static long until;
    private PickerPass() {}
    public static void start(long elapsed) { until = elapsed + DURATION_MS; }
    public static void end() { until = 0; }
    public static boolean active(long elapsed) { return elapsed < until; }
}
