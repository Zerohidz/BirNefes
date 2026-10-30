package qa;
import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;
public class ChatActivity extends Activity {
 @Override public void onCreate(Bundle state) { super.onCreate(state); TextView text=new TextView(this); text.setPadding(30,100,30,30); text.setText("QA SECOND ACTIVITY — no second delay expected"); text.setTextSize(24); setContentView(text); }
}
