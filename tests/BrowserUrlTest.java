import com.zerohidz.whatsapppause.BrowserUrlProbe;

public final class BrowserUrlTest {
    static int checks;
    static void check(boolean ok, String what) { checks++; if (!ok) throw new AssertionError(what); }
    public static void main(String[] args) {
        check(BrowserUrlProbe.video("youtube.com"), "bare domain");
        check(BrowserUrlProbe.video("m.youtube.com/watch?v=abc"), "mobile watch");
        check(BrowserUrlProbe.video("https://www.youtube.com/"), "full url");
        check(BrowserUrlProbe.video("YOUTU.BE/abc"), "short, upper case");
        check(BrowserUrlProbe.video("youtube-nocookie.com/embed/x"), "nocookie");
        check(!BrowserUrlProbe.video("notyoutube.com"), "other domain");
        check(!BrowserUrlProbe.video("google.com/search?q=youtube.com"), "query mention");
        check(!BrowserUrlProbe.video("youtube.com.evil.example"), "suffix trick");
        check(!BrowserUrlProbe.video("YouTube videoları izle"), "page title");
        check(BrowserUrlProbe.address("example.org/a"), "address");
        check(!BrowserUrlProbe.address("hava durumu"), "search words");
        check(BrowserUrlProbe.barNode("com.android.chrome:id/url_bar", false), "chrome bar");
        check(BrowserUrlProbe.barNode("com.sec.android.app.sbrowser:id/location_bar_edit_text", false), "samsung bar");
        check(BrowserUrlProbe.barNode(null, true), "editable");
        check(!BrowserUrlProbe.barNode("x:id/title", false), "title");
        System.out.println("BrowserUrlTest: " + checks + " checks passed");
    }
}
