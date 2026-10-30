package com.zerohidz.whatsapppause;

/** Orders window transitions without reading accessibility nodes or screen text. */
public final class WindowEventGate {
    private long newest;
    private boolean returningHome;

    public void returningHome(long uptime) {
        newest = Math.max(newest, uptime);
        returningHome = true;
    }
    public boolean allowedWindow(long eventTime) {
        if (eventTime < newest) return false;
        newest = eventTime;
        returningHome = false;
        return true;
    }
    public boolean restrictedWindow(long eventTime, boolean activityWindow) {
        if (returningHome || eventTime < newest || !activityWindow) return false;
        newest = eventTime;
        return true;
    }
}
