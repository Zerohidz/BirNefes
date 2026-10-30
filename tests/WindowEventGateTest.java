import com.zerohidz.whatsapppause.WindowEventGate;
public class WindowEventGateTest {
 static int checks;
 static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
 public static void main(String[] args) {
  WindowEventGate g = new WindowEventGate();
  check(g.restrictedWindow(100, true), "Actual WhatsApp activity opens a gate");
  g.returningHome(200);
  check(!g.restrictedWindow(190, true), "Queued activity from before Home is ignored");
  check(!g.restrictedWindow(210, true), "Old app exposed during Home transition cannot reopen");
  check(g.allowedWindow(250), "Launcher confirms Home arrival");
  check(!g.restrictedWindow(240, true), "Out-of-order old app event after launcher is ignored");
  check(!g.restrictedWindow(750, false), "Delayed non-activity event half a second after Home is ignored");
  check(g.restrictedWindow(260, true), "Actual immediate reentry is blocked with no debounce bypass");
  check(!g.allowedWindow(255), "Stale launcher event cannot cancel the new gate");
  g.returningHome(1000);
  check(!g.allowedWindow(999), "Old allowed event cannot release Home guard");
  check(g.allowedWindow(1050), "New allowed foreground releases guard");
  check(g.restrictedWindow(1051, true), "New app after allowed app is still checked");
  System.out.println(checks+" window transition checks passed");
 }
}
