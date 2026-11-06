import com.zerohidz.whatsapppause.DelayGate;
import static com.zerohidz.whatsapppause.DelayGate.State.*;
import java.time.*;
public class DelayGateTest {
 static final ZoneId Z = ZoneId.of("Europe/Istanbul");
 static int checks;
 static long at(String time) { return LocalDateTime.parse("2026-10-01T" + time).atZone(Z).toInstant().toEpochMilli(); }
 static void check(boolean value, String name) { checks++; if (!value) throw new AssertionError(name); }
 static void start(DelayGate g, long t) {
  check(g.enter(t, t, Z) == WAITING, "new wait");
  check(g.tick(t+29999,t+29999,Z)==WAITING,"full 30 seconds");
  check(g.tick(t+30000,t+30000,Z)==ALLOWED,"opens at 30 seconds");
 }
 public static void main(String[] args) {
  DelayGate g = new DelayGate(); long t=at("10:00:00");
  g.enter(t,t,Z); g.leave(); check(g.used()==0,"cancel does not consume");
  start(g,t); long end=g.end(); check(end==t+1230000,"twenty minutes after wait");
  g.leave(); check(g.enter(t+180000,t+180000,Z)==ALLOWED,"three minute return");
  check(g.end()==end,"return does not extend");
  check(g.enter(t+200000,t+200000,Z)==ALLOWED,"same package navigation");
  check(g.tick(end-1,end-1,Z)==ALLOWED,"last millisecond");
  check(g.tick(end,end,Z)==EXPIRED,"foreground forcibly blocked");
  check(g.enter(end+1000,end+1000,Z)==EXPIRED,"events cannot auto-start next right");
  g.leave(); start(g,end+60000); check(g.used()==2,"second right");
  DelayGate restored=new DelayGate(g.day(),g.used(),g.nightUsed(),g.end());
  check(restored.enter(end+100000,end+100000,Z)==ALLOWED,"restore active");
  check(restored.used()==2,"restore quota");
  g=restored;
  for(int i=3;i<=5;i++){ t=g.end()+1000; g.leave(); start(g,t); check(g.used()==i,"right "+i); }
  t=g.end(); g.tick(t,t,Z); g.leave(); check(g.enter(t,t,Z)==EXHAUSTED,"sixth denied");
  t=at("22:31:00");g.leave();check(g.enter(t,t,Z)==EXHAUSTED,"night grants no extra right");
  g=new DelayGate();t=at("22:30:00"); start(g,t); check(g.end()==t+330000,"night lasts five minutes");
  t=g.end();g.tick(t,t,Z);g.leave();check(g.enter(t,t,Z)==EXHAUSTED,"night only once");
  g=new DelayGate();t=at("22:27:00");start(g,t);t=at("22:30:00");g.tick(t,t,Z);
  check(g.end()==at("22:35:00") && g.nightUsed(),"crossing cutoff clamps active session");
  t=g.end();check(g.tick(t,t,Z)==EXPIRED,"night forced expiry");g.leave();check(g.enter(t,t,Z)==EXHAUSTED,"crossing session consumes night right");
  g=new DelayGate();t=at("22:27:00");start(g,t);g.leave();t=at("22:40:00");check(g.enter(t,t,Z)==EXHAUSTED,"away crossing cutoff also counts");
  g=new DelayGate();t=at("22:29:45");start(g,t);check(g.end()==at("22:35:15"),"wait crossing cutoff receives five minutes");
  g=new DelayGate();t=at("23:58:00");start(g,t);
  check(g.end()==t+330000,"night session continues across midnight");
  t=at("00:00:00")+86400000;
  check(g.tick(t,t,Z)==ALLOWED && g.used()==1,"midnight does not reset or block");
  DelayGate saved=new DelayGate(g.day(),g.used(),g.nightUsed(),g.end());
  check(saved.enter(t,t,Z)==ALLOWED,"restore after midnight keeps night session");
  t=g.end();g.tick(t,t,Z);g.leave();
  check(g.enter(t,t,Z)==EXHAUSTED,"night use still exhausted after midnight");
  t=at("02:59:59")+86400000;g.leave();
  check(g.enter(t,t,Z)==EXHAUSTED,"no renewal before 03:00");
  t=at("03:00:00")+86400000;g.leave();start(g,t);
  check(g.used()==1 && !g.nightUsed() && g.end()==t+1230000,"03:00 renews daytime budget");
  g=new DelayGate();t=at("02:58:00");start(g,t);
  check(g.day().equals("2026-09-30"),"early hours belong to preceding budget date");
  check(g.end()==at("03:00:00"),"cap night session at 03:00");
  t=g.end();check(g.tick(t,t,Z)==EXPIRED,"03:00 blocks active old session");
  g.leave();start(g,t);check(g.used()==1 && !g.nightUsed(),"new budget after active expiry");
  g=new DelayGate("2026-10-01",5,true,0);t=at("01:00:00")+86400000;
  check(g.enter(t,t,Z)==EXHAUSTED && g.used()==5,"five uses persist across midnight");
  g=new DelayGate();t=at("02:59:45");start(g,t);
  check(!g.nightUsed() && g.end()==at("03:20:15"),"wait crossing renewal grants daytime window");
  g=new DelayGate();t=at("12:00:00");g.enter(t,1000,Z);check(g.tick(t+900000,2000,Z)==WAITING,"wall clock cannot skip countdown");
  g=new DelayGate();t=at("12:00:00");start(g,t);
  check(g.tick(t-60000,t+1230000,Z)==EXPIRED,"clock rollback cannot extend active use");
  g.leave();check(g.enter(t-60000,t+1230000,Z)==WAITING,"expired window cannot revive after rollback");
  System.out.println(checks+" state checks passed");
 }
}
