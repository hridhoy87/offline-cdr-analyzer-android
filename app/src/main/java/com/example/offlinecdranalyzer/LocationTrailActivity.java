package com.example.offlinecdranalyzer;

import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import org.json.JSONObject;

public class LocationTrailActivity extends AppCompatActivity {

    private WebView webView;
    private TextView tvDateRange;
    private LinearLayout legendContainer;
    private Calendar startCal, endCal;
    private List<String> filePaths;
    private final String[] colors = {"#E74C3C", "#2ECC71", "#3498DB", "#F1C40F", "#9B59B6", "#1ABC9C", "#E67E22", "#34495E"};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_location_trail);

        webView = findViewById(R.id.webViewMap);
        tvDateRange = findViewById(R.id.tvDateRange);
        legendContainer = findViewById(R.id.legendContainer);
        ImageButton btnBack = findViewById(R.id.btnBack);
        ImageButton btnCalendar = findViewById(R.id.btnCalendar);

        filePaths = getIntent().getStringArrayListExtra("file_paths");

        btnBack.setOnClickListener(v -> finish());
        btnCalendar.setOnClickListener(v -> showTimelineOptionDialog());

        setupWebView();
    }

    private void setupWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, android.webkit.WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (url.contains("google.com/maps")) {
                    Uri gmmIntentUri = Uri.parse(url);
                    Intent mapIntent = new Intent(Intent.ACTION_VIEW, gmmIntentUri);
                    mapIntent.setPackage("com.google.android.apps.maps");
                    
                    if (mapIntent.resolveActivity(getPackageManager()) != null) {
                        startActivity(mapIntent);
                    } else {
                        startActivity(new Intent(Intent.ACTION_VIEW, gmmIntentUri));
                    }
                    return true;
                }
                return false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                loadTrailData();
            }
        });
        webView.loadUrl("file:///android_asset/location_trail.html");
    }

    private void loadTrailData() {
        SharedPreferences prefs = getSharedPreferences("LocationTrailPrefs", MODE_PRIVATE);
        String jsonData = prefs.getString("trail_data", null);
        if (jsonData != null) {
            // Escape backslashes and single quotes for JS injection
            String escapedData = jsonData.replace("\\", "\\\\").replace("'", "\\'");
            webView.evaluateJavascript("renderTrailData('" + escapedData + "')", null);
            updateLegend(jsonData);
        } else {
            Toast.makeText(this, "No trail data found. Ensure you have Lat/Long columns.", Toast.LENGTH_LONG).show();
        }
    }

    private void updateLegend(String jsonData) {
        legendContainer.removeAllViews();
        TextView title = new TextView(this);
        title.setText("Legend (A-Parties)");
        title.setTextSize(11);
        title.setPadding(0, 0, 0, 8);
        title.setTextColor(Color.BLACK);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        legendContainer.addView(title);

        try {
            JSONObject json = new JSONObject(jsonData);
            java.util.Iterator<String> keys = json.keys();
            int colorIdx = 0;
            while (keys.hasNext()) {
                String aparty = keys.next();
                String color = colors[colorIdx % colors.length];
                colorIdx++;

                LinearLayout item = new LinearLayout(this);
                item.setOrientation(LinearLayout.HORIZONTAL);
                item.setGravity(android.view.Gravity.CENTER_VERTICAL);
                item.setPadding(0, 4, 0, 4);

                View dot = new View(this);
                LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(24, 24);
                dotParams.setMargins(0, 0, 16, 0);
                dot.setLayoutParams(dotParams);
                dot.setBackgroundColor(Color.parseColor(color));

                TextView tv = new TextView(this);
                tv.setText(aparty);
                tv.setTextSize(10);
                tv.setTextColor(Color.BLACK);

                item.addView(dot);
                item.addView(tv);
                legendContainer.addView(item);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void showTimelineOptionDialog() {
        startCal = Calendar.getInstance();
        endCal = Calendar.getInstance();
        new DatePickerDialog(this, (view, year, month, dayOfMonth) -> {
            startCal.set(Calendar.YEAR, year);
            startCal.set(Calendar.MONTH, month);
            startCal.set(Calendar.DAY_OF_MONTH, dayOfMonth);
            new TimePickerDialog(this, (v, h, m) -> {
                startCal.set(Calendar.HOUR_OF_DAY, h);
                startCal.set(Calendar.MINUTE, m);
                showEndPicker();
            }, 0, 0, true).show();
        }, startCal.get(Calendar.YEAR), startCal.get(Calendar.MONTH), startCal.get(Calendar.DAY_OF_MONTH)).show();
    }

    private void showEndPicker() {
        new DatePickerDialog(this, (view, year, month, dayOfMonth) -> {
            endCal.set(Calendar.YEAR, year);
            endCal.set(Calendar.MONTH, month);
            endCal.set(Calendar.DAY_OF_MONTH, dayOfMonth);
            new TimePickerDialog(this, (v, h, m) -> {
                endCal.set(Calendar.HOUR_OF_DAY, h);
                endCal.set(Calendar.MINUTE, m);
                reRunPython();
            }, 23, 59, true).show();
        }, endCal.get(Calendar.YEAR), endCal.get(Calendar.MONTH), endCal.get(Calendar.DAY_OF_MONTH)).show();
    }

    private void reRunPython() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        String startTs = sdf.format(startCal.getTime());
        String endTs = sdf.format(endCal.getTime());
        tvDateRange.setText(new SimpleDateFormat("dd MMM HH:mm", Locale.US).format(startCal.getTime()) + " - " + new SimpleDateFormat("dd MMM HH:mm", Locale.US).format(endCal.getTime()));

        new Thread(() -> {
            try {
                Python py = Python.getInstance();
                PyObject pyModule = py.getModule("index");
                pyModule.callAttr("bring_loc_trail_out", filePaths.toArray(new String[0]), startTs, endTs, this);
                runOnUiThread(this::loadTrailData);
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "Update error: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (isFinishing()) {
            // Instruction f: Erase shared preference when app will be stopped
            SharedPreferences prefs = getSharedPreferences("LocationTrailPrefs", MODE_PRIVATE);
            prefs.edit().clear().apply();
        }
    }
}
