package com.zerohidz.whatsapppause;

/** Test access to the package-private BrowserUrl. */
public final class BrowserUrlProbe {
    public static boolean video(String text) { return BrowserUrl.video(text); }
    public static boolean address(String text) { return BrowserUrl.address(text); }
    public static boolean barNode(String id, boolean editable) { return BrowserUrl.barNode(id, editable); }
}
