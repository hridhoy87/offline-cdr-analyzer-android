package com.example.offlinecdranalyzer;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class LinkAnalysisActivity extends AppCompatActivity {

    private static final long INACTIVITY_TIMEOUT = 30 * 60 * 1000;
    private final Handler inactivityHandler = new Handler(Looper.getMainLooper());
    private final Runnable inactivityRunnable = () -> {
        Toast.makeText(this, R.string.session_expired_affinity, Toast.LENGTH_LONG).show();
        finishAffinity(); // Close all activities
    };

    @Override
    public void onUserInteraction() {
        super.onUserInteraction();
        resetInactivityTimer();
    }

    private void resetInactivityTimer() {
        inactivityHandler.removeCallbacks(inactivityRunnable);
        inactivityHandler.postDelayed(inactivityRunnable, INACTIVITY_TIMEOUT);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resetInactivityTimer();
    }

    @Override
    protected void onPause() {
        super.onPause();
        inactivityHandler.removeCallbacks(inactivityRunnable);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);

        setContentView(R.layout.activity_link_analysis);

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            final WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        }

        String graphData = getIntent().getStringExtra("graph_data");
        WebView webView = findViewById(R.id.webViewGraph);
        ImageButton btnBack = findViewById(R.id.btnBack);
        ImageButton btnExportPdf = findViewById(R.id.btnExportPdf);
        View loadingOverlay = findViewById(R.id.loadingOverlay);

        btnBack.setOnClickListener(v -> finish());
        btnExportPdf.setOnClickListener(v -> exportToPdf(webView));

        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setAllowFileAccess(true);
        webSettings.setAllowContentAccess(true);
        webSettings.setDomStorageEnabled(true);
        webView.setBackgroundColor(Color.TRANSPARENT);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                if (graphData != null) {
                    view.evaluateJavascript("renderOfflineData('" + graphData.replace("'", "\\'") + "')", null);
                }
                loadingOverlay.animate().alpha(0f).setDuration(500).withEndAction(() -> loadingOverlay.setVisibility(View.GONE));
            }
        });
        webView.loadUrl("file:///android_asset/link_analysis.html");

        if (graphData != null) populateAreaHistogram(graphData);
    }

    private void exportToPdf(WebView webView) {
        PdfDocument document = new PdfDocument();
        PdfDocument.PageInfo pageInfo = new PdfDocument.PageInfo.Builder(595, 842, 1).create();
        PdfDocument.Page page = document.startPage(pageInfo);
        Canvas canvas = page.getCanvas();

        android.graphics.Paint paint = new android.graphics.Paint();
        paint.setColor(Color.BLACK);
        paint.setTextSize(18);
        canvas.drawText("CDR Intelligence Report - Link Analysis", 50, 50, paint);

        paint.setTextSize(12);
        String date = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
        canvas.drawText("Generated on: " + date, 50, 75, paint);

        try {
            Bitmap bitmap = Bitmap.createBitmap(webView.getWidth(), webView.getHeight(), Bitmap.Config.ARGB_8888);
            Canvas bCanvas = new Canvas(bitmap);
            webView.draw(bCanvas);
            float scale = Math.min((float) 500 / bitmap.getWidth(), (float) 600 / bitmap.getHeight());
            Bitmap scaledBitmap = Bitmap.createScaledBitmap(bitmap, (int)(bitmap.getWidth() * scale), (int)(bitmap.getHeight() * scale), true);
            canvas.drawBitmap(scaledBitmap, 50, 100, paint);
        } catch (Exception e) {
            canvas.drawText("Error capturing graph: " + e.getMessage(), 50, 100, paint);
        }

        document.finishPage(page);
        File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "CDR_Reports");
        if (!dir.exists()) {
            if (!dir.mkdirs()) {
                Toast.makeText(this, "Failed to create directory", Toast.LENGTH_SHORT).show();
                document.close();
                return;
            }
        }

        String fileName = "Link_Analysis_" + System.currentTimeMillis() + ".pdf";
        File file = new File(dir, fileName);
        try {
            document.writeTo(new FileOutputStream(file));
            Toast.makeText(this, R.string.pdf_export_success, Toast.LENGTH_LONG).show();
        } catch (IOException e) {
            Toast.makeText(this, "Export failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        } finally {
            document.close();
        }
    }

    private void populateAreaHistogram(String jsonString) {
        LinearLayout histogramLayout = findViewById(R.id.areaHistogram);
        try {
            JSONObject jsonObject = new JSONObject(jsonString);
            if (!jsonObject.has("area_clusters")) {
                showNoClustersMessage();
                return;
            }
            JSONArray clusters = jsonObject.getJSONArray("area_clusters");
            if (clusters.length() == 0) {
                showNoClustersMessage();
                return;
            }

            int maxCount = 0;
            for (int i = 0; i < clusters.length(); i++) maxCount = Math.max(maxCount, clusters.getJSONObject(i).getInt("count"));

            for (int i = 0; i < clusters.length(); i++) {
                JSONObject cluster = clusters.getJSONObject(i);
                String area = cluster.getString("area");
                View itemView = getLayoutInflater().inflate(R.layout.item_histogram_bar, histogramLayout, false);
                ((TextView) itemView.findViewById(R.id.areaName)).setText(area);
                ProgressBar bar = itemView.findViewById(R.id.areaBar);
                bar.setMax(maxCount);
                bar.setProgress(cluster.getInt("count"));
                ((TextView) itemView.findViewById(R.id.areaCount)).setText(String.valueOf(cluster.getInt("count")));
                
                itemView.setOnClickListener(v -> openGoogleMaps(area));
                
                histogramLayout.addView(itemView);
            }
        } catch (JSONException e) {
            showNoClustersMessage();
        }
    }

    private void openGoogleMaps(String address) {
        if (address == null || address.isEmpty() || address.equalsIgnoreCase("Unknown")) return;
        String uri = "https://www.google.com/maps/dir/?api=1&destination=" + Uri.encode(address) + "&travelmode=driving";
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(uri));
        intent.setPackage("com.google.android.apps.maps");
        if (intent.resolveActivity(getPackageManager()) != null) {
            startActivity(intent);
        } else {
            // Fallback to browser
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(uri)));
        }
    }

    private void showNoClustersMessage() {
        LinearLayout histogramLayout = findViewById(R.id.areaHistogram);
        TextView noData = new TextView(this);
        noData.setText("No regional data available for clustering.");
        noData.setTextColor(Color.parseColor("#8b949e"));
        noData.setPadding(0, 20, 0, 0);
        histogramLayout.addView(noData);
    }
}