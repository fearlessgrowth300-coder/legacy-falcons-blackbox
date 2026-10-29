package top.niunaijun.blackbox.guest.foreground;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.widget.TextView;

/** Starts the foreground service and stays on screen so the matrix can tap it. */
public class GuestActivity extends Activity {
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView label = new TextView(this);
        label.setText("GuestForeground");
        label.setTextSize(24f);
        setContentView(label);
        Intent service = new Intent(this, GuestForegroundService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(service);
        } else {
            startService(service);
        }
    }
}
