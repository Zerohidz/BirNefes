package com.zerohidz.whatsapppause;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.widget.RemoteViews;

/** An ongoing notification that counts down the open usage window; the system ticks it. */
final class UsageTimer {
    private static final String CHANNEL = "usage";
    private static final int ID = 7;
    private final Context context;
    private long shownEnd;
    UsageTimer(Context context) { this.context = context; }

    /** elapsedEnd is in elapsedRealtime; only ALLOWED shows, ended states remove it. */
    void show(DelayGate.State state, long elapsedEnd) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return;
        long left = elapsedEnd - SystemClock.elapsedRealtime();
        if (state != DelayGate.State.ALLOWED || left <= 0) {
            // Leaving the app does not stop the window, so AWAY keeps the countdown.
            if (state != DelayGate.State.AWAY && shownEnd != 0) { manager.cancel(ID); shownEnd = 0; }
            return;
        }
        long end = System.currentTimeMillis() + left;
        if (Math.abs(end - shownEnd) < 1500) return;
        shownEnd = end;
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "Kalan kullanım süresi", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(context, 0,
            new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE);
        // A custom view so the clock is large; the header's own chronometer is tiny.
        String pkg = context.getPackageName();
        RemoteViews clock = new RemoteViews(pkg, context.getResources().getIdentifier("usage_timer", "layout", pkg));
        int clockId = context.getResources().getIdentifier("usage_clock", "id", pkg);
        clock.setChronometer(clockId, elapsedEnd, null, true);
        clock.setChronometerCountDown(clockId, true);
        manager.notify(ID, new Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Kullanım süren işliyor")
            .setContentText("Süre bitince kısıtlı uygulamalar kapanır.")
            .setStyle(new Notification.DecoratedCustomViewStyle())
            .setCustomContentView(clock).setCustomBigContentView(clock)
            .setShowWhen(false)
            .setOngoing(true).setOnlyAlertOnce(true).setTimeoutAfter(left)
            .setCategory(Notification.CATEGORY_STOPWATCH).setContentIntent(open)
            .build());
    }
}
