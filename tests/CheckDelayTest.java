import com.zerohidz.whatsapppause.CheckDelay;
import java.util.Arrays;
public class CheckDelayTest {
 static int checks;
 static void check(boolean value, String name) { checks++; if (!value) throw new AssertionError(name); }
 public static void main(String[] args) {
  check(Arrays.equals(CheckDelay.options(),new long[]{500,1000,1500,2000,2500,3000}),"half second steps from 0.5 to 3");
  check(CheckDelay.DEFAULT_MS==1000,"default is one second");
  for(long v:CheckDelay.options()) check(CheckDelay.normalize(v)==v,"valid value kept");
  check(CheckDelay.normalize(3500)==3000,"above the ceiling is clamped");
  check(CheckDelay.normalize(Long.MAX_VALUE)==3000,"huge value is clamped");
  check(CheckDelay.normalize(0)==500,"below the floor is clamped");
  check(CheckDelay.normalize(-1)==500,"negative is clamped");
  check(CheckDelay.normalize(1200)==1000,"off-step value snaps down");
  check(CheckDelay.label(500).equals("0,5 sn"),"half second label");
  check(CheckDelay.label(1000).equals("1 sn"),"whole second label");
  check(CheckDelay.label(2500).equals("2,5 sn"),"decimal comma label");
  check(CheckDelay.label(3000).equals("3 sn"),"ceiling label");
  check(Arrays.equals(CheckDelay.upTo(1500),new long[]{500,1000,1500}),"locked: up to 1.5 s");
  check(Arrays.equals(CheckDelay.upTo(1000),new long[]{500,1000,1500}),"locked at 1 s can still reach 1.5 s");
  check(Arrays.equals(CheckDelay.upTo(500),new long[]{500,1000,1500}),"locked at the floor can still reach 1.5 s");
  check(Arrays.equals(CheckDelay.upTo(2000),new long[]{500,1000,1500,2000}),"locked above 1.5 s only shrinks");
  check(Arrays.equals(CheckDelay.upTo(9999),CheckDelay.options()),"locked at the ceiling keeps every step");
  System.out.println(checks+" check delay checks passed");
 }
}
