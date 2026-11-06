import com.zerohidz.whatsapppause.PickerPass;
public class PickerPassTest {
 static int checks;
 static void check(boolean value, String name) { checks++; if (!value) throw new AssertionError(name); }
 public static void main(String[] args) {
  check(!PickerPass.active(1000),"inactive before start");
  PickerPass.start(1000);
  check(PickerPass.active(1000),"active at start");
  check(PickerPass.active(1000+300000-1),"active through five minutes");
  check(!PickerPass.active(1000+300000),"ends at five minutes");
  PickerPass.start(1000);PickerPass.end();
  check(!PickerPass.active(1001),"ends when the screen returns");
  System.out.println(checks+" picker pass checks passed");
 }
}
