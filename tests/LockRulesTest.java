import com.zerohidz.whatsapppause.LockRules;
public class LockRulesTest {
 static int checks;
 static void check(boolean value, String name) { checks++; if (!value) throw new AssertionError(name); }
 public static void main(String[] args) {
  check(LockRules.covers("com.android.settings","com.android.settings.Settings$AccessibilitySettingsActivity"),"AOSP accessibility settings");
  check(LockRules.covers("com.android.settings","com.android.settings.accessibility.AccessibilitySettings"),"accessibility fragment class");
  check(LockRules.covers("com.samsung.accessibility","com.samsung.accessibility.core.winset.activity.AccessibilitySubSettings"),"Samsung accessibility");
  check(LockRules.covers("com.android.packageinstaller","com.android.packageinstaller.UninstallerActivity"),"AOSP uninstall");
  check(LockRules.covers("com.google.android.packageinstaller","com.android.packageinstaller.UninstallerActivity"),"Google uninstall");
  check(LockRules.covers("com.android.settings","com.android.settings.applications.InstalledAppDetailsTop"),"installed app details");
  check(LockRules.covers("com.android.settings","com.android.settings.Settings$AppInfoDashboardActivity"),"app info dashboard");
  check(LockRules.covers("com.android.settings","com.android.settings.applications.appinfo.AppDetailsActivity"),"app details");
  check(!LockRules.covers("com.android.settings","com.android.settings.Settings$WifiSettingsActivity"),"Wi-Fi stays open");
  check(!LockRules.covers("com.android.settings","com.android.settings.SubSettings"),"SubSettings is not blocked in general");
  check(!LockRules.covers("com.android.settings","com.android.settings.homepage.SettingsHomepageActivity"),"settings home stays open");
  check(!LockRules.covers("com.android.packageinstaller","com.android.packageinstaller.InstallStart"),"installing stays open");
  check(!LockRules.covers("com.whatsapp","com.whatsapp.AccessibilityHelper"),"other packages are never covered");
  check(!LockRules.covers(null,"Accessibility"),"no package");
  check(!LockRules.covers("com.android.settings",null),"no class");

  LockRules.Guard none=new LockRules.Guard(null,false,false,0,false,false);
  check(!none.locked(1000)&&!none.released(1000),"no guardian never locks");
  LockRules.Guard pending=new LockRules.Guard("",true,false,0,false,false);
  check(!pending.locked(1000),"an unverified guardian does not lock");
  LockRules.Guard locked=new LockRules.Guard("a***@x.com",false,true,0,false,false);
  check(locked.locked(1000),"reported lock holds");
  LockRules.Guard released=new LockRules.Guard("a***@x.com",false,false,5000,false,false);
  check(!released.locked(4999)&&released.released(4999),"release holds until its time");
  check(released.locked(5000),"saved release ends at its own time, offline too");
  LockRules.Guard stale=new LockRules.Guard("a***@x.com",false,true,9000,false,false);
  check(stale.locked(1000),"a saved lock wins over a saved release time");
  java.util.List<CharSequence> t=new java.util.ArrayList<>();
  t.add("Bir Nefes — ekran molası");
  check(LockRules.coversTitle("com.android.settings",t),"Samsung service page by title");
  t.set(0,"Uygulama bilgileri");
  check(LockRules.coversTitle("com.android.settings",t),"Samsung app info by title");
  t.set(0,"Wi-Fi");
  check(!LockRules.coversTitle("com.android.settings",t),"Wi-Fi stays open");
  t.set(0,"Erişilebilirlik");
  check(!LockRules.coversTitle("com.android.settings",t),"accessibility list stays open");
  t.set(0,"Bir Nefes — ekran molası");
  check(!LockRules.coversTitle("com.whatsapp",t),"titles only count in Settings");
  check(!LockRules.coversTitle("com.android.settings",null),"no text");
  System.out.println(checks+" lock rules checks passed");
 }
}
