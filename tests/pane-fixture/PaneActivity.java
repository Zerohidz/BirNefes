package qa.pane;
import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.widget.TextView;

/** Disposable emulator fixture: imitates a late pane event after leaving an app. */
public final class PaneActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        TextView label = new TextView(this);
        label.setText("QA pane fixture: sends a pane-disappeared event 500 ms after Home");
        label.setTextSize(24); label.setPadding(30, 100, 30, 30); setContentView(label);
    }
    @Override protected void onPause() {
        super.onPause();
        for (int delay : new int[] {500, 1000}) new Handler(Looper.getMainLooper()).postDelayed(() -> {
            AccessibilityManager manager = (AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
            if (!manager.isEnabled()) { Log.i("BirNefesPaneQA", "not sent: accessibility disabled"); return; }
            AccessibilityEvent event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
            event.setPackageName(getPackageName());
            event.setClassName("android.widget.FrameLayout");
            event.setContentChangeTypes(delay == 500 ? AccessibilityEvent.CONTENT_CHANGE_TYPE_PANE_DISAPPEARED : 0);
            event.setEventTime(SystemClock.uptimeMillis());
            try {
                manager.sendAccessibilityEvent(event);
                Log.i("BirNefesPaneQA", "sent delayed TYPE_WINDOW_STATE_CHANGED FrameLayout delay=" + delay + "ms");
            } catch (RuntimeException error) { Log.e("BirNefesPaneQA", "send failed: " + error.getClass().getSimpleName()); }
        }, delay);
    }
}
