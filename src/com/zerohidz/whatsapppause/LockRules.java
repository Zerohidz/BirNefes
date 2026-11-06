package com.zerohidz.whatsapppause;

/**
 * Guardian lock: which windows are covered and whether the lock holds. Pure Java so it
 * is unit tested without a device. Only the package and class name of a window are used.
 */
public final class LockRules {
    /** One rule: a package (or several) and a fragment of the window's class name. */
    private static final String[][] RULES = {
        // Turning the accessibility service off.
        {"com.android.settings", "Accessibility"},
        {"com.samsung.accessibility", "Accessibility"},
        // Uninstalling, from the installer's confirmation.
        {"com.android.packageinstaller", "Uninstall"},
        {"com.google.android.packageinstaller", "Uninstall"},
        // App info: force stop, uninstall, clear data.
        {"com.android.settings", "AppInfo"},
        {"com.android.settings", "InstalledAppDetails"},
        {"com.android.settings", "AppDetails"},
    };
    private LockRules() {}

    public static boolean covers(String pkg, CharSequence className) {
        if (pkg == null || className == null) return false;
        String name = className.toString();
        for (String[] rule : RULES)
            if (rule[0].equals(pkg) && name.contains(rule[1])) return true;
        return false;
    }

    /** The last guard the server reported. Defaults to no guardian, which never locks. */
    public static final class Guard {
        public final String guardian;
        public final boolean pendingGuardian, locked, pendingRelease, mailError;
        public final long releasedUntil;
        public Guard(String guardian, boolean pendingGuardian, boolean locked, long releasedUntil,
                     boolean pendingRelease, boolean mailError) {
            this.guardian = guardian == null || guardian.isEmpty() ? null : guardian;
            this.pendingGuardian = pendingGuardian;
            this.locked = locked;
            this.releasedUntil = releasedUntil;
            this.pendingRelease = pendingRelease;
            this.mailError = mailError;
        }
        /**
         * A saved answer can only restrict: a reported lock holds while offline, and a
         * reported release ends at its own time without asking the server again.
         */
        public boolean locked(long now) { return locked || guardian != null && now >= releasedUntil; }
        public boolean released(long now) { return guardian != null && !locked(now); }
    }
}
