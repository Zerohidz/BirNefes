package qa;
import android.app.Activity;
import android.os.Bundle;
import android.widget.*;
import android.content.Intent;
public class TargetActivity extends Activity {
 @Override public void onCreate(Bundle state) {
  super.onCreate(state);
  LinearLayout layout=new LinearLayout(this); layout.setOrientation(1); layout.setPadding(30,100,30,30);
  TextView text=new TextView(this); text.setText("QA TARGET VISIBLE — surrogate, not real WhatsApp"); text.setTextSize(24); layout.addView(text);
  Button button=new Button(this); button.setText("Open second activity"); button.setOnClickListener(v->startActivity(new Intent(this,ChatActivity.class))); layout.addView(button);
  Button picker=new Button(this); picker.setText("Open file picker"); picker.setOnClickListener(v->startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE),1)); layout.addView(picker);
  setContentView(layout);
 }
}
