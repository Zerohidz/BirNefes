package com.zerohidz.whatsapppause;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.provider.MediaStore;
import android.provider.Telephony;
import android.telecom.InCallService;
import android.telecom.TelecomManager;
import android.view.inputmethod.InputMethodManager;
import java.util.HashSet;
import java.util.Set;

/** Package identities only; never inspects screen content. */
final class AppPolicy {
    private final Context context;
    private final SharedPreferences prefs;
    private final Set<String> essential = new HashSet<>();
    private final Set<String> callScreens = new HashSet<>();
    private final Set<String> pickers = new HashSet<>();
    AppPolicy(Context context) {
        this.context = context;
        prefs = context.getSharedPreferences("exceptions", Context.MODE_PRIVATE);
        refresh();
        if (!prefs.contains("allowed")) {
            Set<String> defaults = new HashSet<>();
            collect(defaults, new Intent(MediaStore.ACTION_IMAGE_CAPTURE));
            collect(defaults, new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_CALENDAR));
            collect(defaults, new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MAPS));
            collect(defaults, new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_CALCULATOR));
            java.util.Collections.addAll(defaults, "com.google.android.apps.maps", "com.google.android.calendar",
                "com.android.calendar", "com.android.camera", "com.google.android.GoogleCamera",
                "com.sec.android.app.camera", "com.samsung.android.calendar", "com.android.deskclock",
                "com.google.android.deskclock", "com.sec.android.app.clockpackage");
            prefs.edit().putStringSet("allowed", defaults).apply();
        }
    }
    void refresh() {
        essential.clear();
        callScreens.clear();
        callScreens.add("com.samsung.android.incallui");
        java.util.Collections.addAll(essential, context.getPackageName(), "android", "com.android.systemui",
            "com.android.settings", "com.android.phone", "com.android.server.telecom",
            "com.android.dialer", "com.google.android.dialer", "com.samsung.android.dialer",
            "com.samsung.android.incallui",
            "com.android.mms", "com.google.android.apps.messaging", "com.samsung.android.messaging",
            "com.android.emergency", "com.android.permissioncontroller", "com.google.android.permissioncontroller");
        collect(essential, new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME));
        collect(essential, new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_CONTACTS));
        collect(essential, new Intent(android.provider.Settings.ACTION_SETTINGS));
        String sms = Telephony.Sms.getDefaultSmsPackage(context);
        if (sms != null) essential.add(sms);
        TelecomManager telecom = (TelecomManager) context.getSystemService(Context.TELECOM_SERVICE);
        String dialer = null;
        if (telecom != null) {
            dialer = telecom.getDefaultDialerPackage();
            if (dialer != null) essential.add(dialer);
            String system = telecom.getSystemDialerPackage();
            if (system != null) essential.add(system);
        }
        // A third-party in-call UI is exempt only as the user's default dialer.
        for (ResolveInfo info : context.getPackageManager().queryIntentServices(
                new Intent(InCallService.SERVICE_INTERFACE), PackageManager.GET_META_DATA)) {
            ServiceInfo service = info.serviceInfo;
            if (service.metaData == null || !service.metaData.getBoolean(TelecomManager.METADATA_IN_CALL_SERVICE_UI)) continue;
            if ((service.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0 || service.packageName.equals(dialer))
            { essential.add(service.packageName); callScreens.add(service.packageName); }
        }
        pickers.clear();
        java.util.Collections.addAll(pickers, "com.google.android.documentsui", "com.android.documentsui", "com.android.intentresolver");
        collect(pickers, new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"));
        collect(pickers, new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"));
        InputMethodManager input = (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
        for (android.view.inputmethod.InputMethodInfo method : input.getEnabledInputMethodList())
            essential.add(method.getPackageName());
    }
    private void collect(Set<String> target, Intent intent) {
        for (ResolveInfo info : context.getPackageManager().queryIntentActivities(intent, 0))
            target.add(info.activityInfo.packageName);
    }
    boolean activityWindow(String pkg, CharSequence name) {
        if (name == null) return false;
        try {
            context.getPackageManager().getActivityInfo(
                new android.content.ComponentName(pkg, name.toString()), 0);
            return true;
        } catch (PackageManager.NameNotFoundException missing) { return false; }
    }
    boolean essential(String pkg) { return essential.contains(pkg); }
    boolean callScreen(String pkg) { return callScreens.contains(pkg); }
    /** A system file picker. Never an exception by itself: it counts only under a PickerPass. */
    boolean picker(String pkg) { return pickers.contains(pkg); }
    boolean allowed(String pkg) { return essential(pkg) || prefs.getStringSet("allowed", java.util.Collections.emptySet()).contains(pkg); }
    /** The user's own list, not the essential packages. */
    Set<String> selected() { return new HashSet<>(prefs.getStringSet("allowed", java.util.Collections.emptySet())); }
    void replaceSelected(Set<String> selected) { prefs.edit().putStringSet("allowed", new HashSet<>(selected)).apply(); }
    void setAllowed(String pkg, boolean value) {
        Set<String> selected = selected();
        if (value) selected.add(pkg); else selected.remove(pkg);
        prefs.edit().putStringSet("allowed", selected).apply();
    }
}
