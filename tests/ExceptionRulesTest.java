import com.zerohidz.whatsapppause.ExceptionRules;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
public class ExceptionRulesTest {
 static int checks;
 static void check(boolean value, String name) { checks++; if (!value) throw new AssertionError(name); }
 static byte[] utf8(String value) { return value.getBytes(StandardCharsets.UTF_8); }
 static ExceptionRules.Result parse(String value) { return ExceptionRules.parse(utf8(value)); }
 static String file(String packages) { return "{\"app\":\"bir-nefes\",\"type\":\"exceptions\",\"version\":1,\"exportedAt\":\"2026-11-06T10:00:00Z\",\"packages\":"+packages+"}"; }
 static void refused(String value, String error, String name) { ExceptionRules.Result r=parse(value); check(!r.ok()&&error.equals(r.error)&&r.packages.isEmpty(),name); }
 public static void main(String[] args) {
  String exported=ExceptionRules.export(List.of("com.a.b","org.c_d"),"2026-11-06T10:00:00Z");
  check(exported.equals(file("[\"com.a.b\",\"org.c_d\"]")),"export format");
  check(parse(exported).ok()&&parse(exported).packages.equals(List.of("com.a.b","org.c_d")),"export round trip");
  check(parse(ExceptionRules.export(List.of(),"x")).packages.isEmpty(),"empty list is valid");
  check(ExceptionRules.fileName(LocalDate.of(2026,11,6)).equals("bir-nefes-istisnalar-2026-11-06.json"),"file name");
  check(parse(" \n"+file("[\"com.a\",\"com.a\",\"com.b\"]")+"\t").packages.equals(List.of("com.a","com.b")),"duplicates removed, whitespace tolerated");
  check(parse("\uFEFF"+file("[\"com.a\"]")).ok(),"byte order mark tolerated");
  check(parse("{\"packages\":[\"com.\\u0061\"],\"extra\":{\"x\":[1,null,true,false,\"\\n\"]},\"version\":1.0,\"type\":\"exceptions\",\"app\":\"bir-nefes\"}").packages.equals(List.of("com.a")),"key order, escapes and unknown fields");
  refused("","Dosya bozuk.","empty file");
  refused("{","Dosya bozuk.","cut object");
  refused(file("[\"com.a\"]")+"x","Dosya bozuk.","trailing bytes");
  refused(file("[\"com.a\",]"),"Dosya bozuk.","trailing comma");
  refused("[".repeat(100000),"Dosya bozuk.","deep nesting does not overflow the stack");
  refused(file("[\"com\u0001a\"]"),"Dosya bozuk.","raw control character");
  refused(file("[\"com\\q\"]"),"Dosya bozuk.","bad escape");
  refused(file("[\"com\\u12\"]"),"Dosya bozuk.","cut unicode escape");
  check(!ExceptionRules.parse(new byte[]{'{','"',(byte)0xFF,'"','}'}).ok(),"invalid UTF-8");
  refused("[]",ExceptionRules.WRONG_TYPE,"list root");
  refused("null",ExceptionRules.WRONG_TYPE,"null root");
  refused("{\"app\":\"other\",\"type\":\"exceptions\",\"version\":1,\"packages\":[]}",ExceptionRules.WRONG_TYPE,"other app");
  refused("{\"app\":\"bir-nefes\",\"type\":\"other\",\"version\":1,\"packages\":[]}",ExceptionRules.WRONG_TYPE,"other type");
  refused("{\"app\":\"bir-nefes\",\"type\":\"exceptions\",\"version\":1}",ExceptionRules.WRONG_TYPE,"missing packages");
  refused("{\"app\":\"bir-nefes\",\"type\":\"exceptions\",\"version\":1,\"packages\":\"com.a\"}",ExceptionRules.WRONG_TYPE,"packages not a list");
  refused("{\"app\":\"bir-nefes\",\"type\":\"exceptions\",\"version\":2,\"packages\":[]}",ExceptionRules.WRONG_VERSION,"newer version");
  refused("{\"app\":\"bir-nefes\",\"type\":\"exceptions\",\"version\":\"1\",\"packages\":[]}",ExceptionRules.WRONG_VERSION,"text version");
  refused("{\"app\":\"bir-nefes\",\"type\":\"exceptions\",\"packages\":[]}",ExceptionRules.WRONG_VERSION,"missing version");
  for(String bad:new String[]{"\"nodots\"","\"1com.a\"","\"com..a\"","\"com.a.\"","\".com.a\"","\"com.1a\"","\"com a.b\"","\"com.a/b\"","\"\"","7","null","[\"com.a\"]"})
   refused(file("["+bad+"]"),ExceptionRules.BAD_PACKAGE,"bad package "+bad);
  refused(file("[\"a."+"b".repeat(254)+"\"]"),ExceptionRules.BAD_PACKAGE,"package length limit");
  check(ExceptionRules.validPackage("com.Samsung_x.a1"),"regex accepts a real name");
  StringBuilder many=new StringBuilder("[");
  for(int i=0;i<2000;i++) many.append(i==0?"":",").append("\"a.b").append(i).append('"');
  check(parse(file(many+"]")).packages.size()==2000,"2000 packages accepted");
  check(parse(file(many+",\"a.b0\"]")).packages.size()==2000,"limit counts packages after duplicates");
  refused(file(many+",\"a.extra\"]"),ExceptionRules.TOO_MANY,"2001 packages refused");
  byte[] exact=new byte[ExceptionRules.MAX_BYTES]; java.util.Arrays.fill(exact,(byte)' ');
  byte[] body=utf8(file("[\"com.a\"]")); System.arraycopy(body,0,exact,0,body.length);
  check(ExceptionRules.parse(exact).ok(),"exactly 256 KB accepted");
  byte[] over=java.util.Arrays.copyOf(exact,exact.length+1); over[exact.length]=' ';
  check(ExceptionRules.TOO_LARGE.equals(ExceptionRules.parse(over).error),"one byte over refused");
  check(ExceptionRules.matches("Takvim","TAKVİM"),"İ matches i");
  check(ExceptionRules.matches("Takvim","TAKVIM"),"I matches i");
  check(ExceptionRules.matches("Takvim","takvım"),"ı matches i");
  check(ExceptionRules.matches("Sıra","SIRA"),"I matches ı");
  check(ExceptionRules.matches("SİNEMA","sinema"),"i matches İ");
  check(ExceptionRules.matches("Isı","isi"),"all four are one letter");
  check(ExceptionRules.matches("I\u0307stanbul","istanbul"),"decomposed İ");
  check(ExceptionRules.matches("Çiçek","ÇİÇ"),"other Turkish letters keep case folding");
  check(!ExceptionRules.matches("Çiçek","cicek"),"only the i family is folded");
  check(ExceptionRules.matches("Haritalar","  rita "),"query is trimmed and matches anywhere");
  check(!ExceptionRules.matches("Haritalar","xyz"),"no match");
  System.out.println(checks+" exception rule checks passed");
 }
}
