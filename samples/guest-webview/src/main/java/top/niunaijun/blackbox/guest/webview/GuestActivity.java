package top.niunaijun.blackbox.guest.webview;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.WebView;

/** Lightweight guest. Loads a local page and logs a heartbeat every 10 seconds. */
public class GuestActivity extends Activity {
    public static final String TAG = "GuestWebView";
    private WebView webView;
    private int beats;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable beat = new Runnable() {
        @Override public void run() {
            beats++;
            Log.i(TAG, "heartbeat " + beats);
            if (webView != null) {
                webView.evaluateJavascript("document.getElementById('ready').textContent", null);
            }
            handler.postDelayed(this, 10_000L);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        webView = new WebView(this);
        webView.getSettings().setJavaScriptEnabled(true);
        setContentView(webView);
        webView.loadData(
                "<html><body><h1 id='ready'>GuestWebView</h1></body></html>",
                "text/html", "utf-8");
        handler.postDelayed(beat, 10_000L);
        Log.i(TAG, "created");
    }

    @Override protected void onDestroy() {
        handler.removeCallbacks(beat);
        super.onDestroy();
    }
}
