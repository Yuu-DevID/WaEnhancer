package com.yusuf.waantidelete;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public class MainActivity extends Activity {

    private static final String PREFS_NAME = "waantidelete_status";
    private TextView statusText;
    private TextView whatsappVersionText;
    private TextView hookStatusText;
    private TextView errorText;
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scrollView = new ScrollView(this);
        scrollView.setBackgroundColor(Color.parseColor("#1a1a2e"));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(24), dp(24), dp(24));

        TextView title = new TextView(this);
        title.setText("WaAntiDelete");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        title.setTextColor(Color.WHITE);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, dp(8));
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Xposed Module Status");
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        subtitle.setTextColor(Color.parseColor("#888888"));
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(0, 0, 0, dp(32));
        root.addView(subtitle);

        statusText = createStatusRow("Status WhatsApp");
        root.addView(statusText);

        whatsappVersionText = createStatusRow("WhatsApp Version");
        root.addView(whatsappVersionText);

        hookStatusText = createStatusRow("Hook Status");
        root.addView(hookStatusText);

        errorText = createStatusRow("Error");
        root.addView(errorText);

        TextView refreshHint = new TextView(this);
        refreshHint.setText("Auto-refresh setiap 2 detik");
        refreshHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        refreshHint.setTextColor(Color.parseColor("#555555"));
        refreshHint.setGravity(Gravity.CENTER);
        refreshHint.setPadding(0, dp(32), 0, 0);
        root.addView(refreshHint);

        scrollView.addView(root);
        setContentView(scrollView);

        refreshStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.postDelayed(refreshRunnable, 2000);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(refreshRunnable);
    }

    private final Runnable refreshRunnable = new Runnable() {
        @Override
        public void run() {
            refreshStatus();
            handler.postDelayed(this, 2000);
        }
    };

    private void refreshStatus() {
        try {
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

            String connection = prefs.getString("connection", "not_found");
            String version = prefs.getString("whatsapp_version", "unknown");
            String antiRevoke = prefs.getString("anti_revoke", "not_loaded");
            String viewOnce = prefs.getString("view_once", "not_loaded");
            String hasError = prefs.getString("has_error", "false");
            String lastError = prefs.getString("last_error", "none");
            String hookedCount = prefs.getString("hooked_count", "0");
            String errorCount = prefs.getString("error_count", "0");

            String connectionStatus;
            int connectionColor;
            switch (connection) {
                case "ok":
                    connectionStatus = "Connected & Active";
                    connectionColor = Color.parseColor("#4CAF50");
                    break;
                case "partial":
                    connectionStatus = "Partial (" + hookedCount + " ok, " + errorCount + " errors)";
                    connectionColor = Color.parseColor("#FF9800");
                    break;
                case "connecting":
                    connectionStatus = "Connecting...";
                    connectionColor = Color.parseColor("#2196F3");
                    break;
                case "error":
                    connectionStatus = "Error - Not Connected";
                    connectionColor = Color.parseColor("#F44336");
                    break;
                default:
                    connectionStatus = "Not Connected (module inactive)";
                    connectionColor = Color.parseColor("#F44336");
                    break;
            }

            statusText.setText("Status WhatsApp: " + connectionStatus);
            statusText.setTextColor(connectionColor);

            whatsappVersionText.setText("WhatsApp Version: " + version);
            whatsappVersionText.setTextColor(Color.parseColor("#4CAF50"));

            String hookInfo = "AntiRevoke: " + formatStatus(antiRevoke) + "\n" +
                    "ViewOnce: " + formatStatus(viewOnce);
            hookStatusText.setText(hookInfo);

            if ("true".equals(hasError)) {
                errorText.setText("Error Found:\n" + lastError);
                errorText.setTextColor(Color.parseColor("#F44336"));
                errorText.setVisibility(android.view.View.VISIBLE);
            } else {
                errorText.setText("No Errors Found");
                errorText.setTextColor(Color.parseColor("#4CAF50"));
            }

        } catch (Throwable e) {
            statusText.setText("Status: Failed to read (" + e.getMessage() + ")");
            statusText.setTextColor(Color.parseColor("#F44336"));
        }
    }

    private String formatStatus(String status) {
        if (status == null) return "unknown";
        if (status.startsWith("ok")) return "OK";
        if (status.startsWith("error")) return status;
        return status;
    }

    private TextView createStatusRow(String label) {
        TextView tv = new TextView(this);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        tv.setTextColor(Color.parseColor("#CCCCCC"));
        tv.setPadding(0, dp(12), 0, dp(12));
        return tv;
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics());
    }
}
