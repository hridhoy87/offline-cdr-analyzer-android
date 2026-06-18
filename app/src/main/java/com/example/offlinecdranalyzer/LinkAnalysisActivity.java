package com.example.offlinecdranalyzer;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.ImageButton;
import android.widget.ImageView;
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

    private String rawGraphData;
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

        String graphDataPath = getIntent().getStringExtra("graph_data_path");
        String graphData = null;
        if (graphDataPath != null) {
            graphData = readDataFromFile(graphDataPath);
        }
        this.rawGraphData = graphData;

        WebView webView = findViewById(R.id.webViewGraph);
        WebView webViewSimImei = findViewById(R.id.webViewSimImei);
        ImageButton btnBack = findViewById(R.id.btnBack);
        ImageButton btnExportPdf = findViewById(R.id.btnExportPdf);
        
        ImageView loadingLogo = findViewById(R.id.loadingLogo);
        Animation complexAnim = AnimationUtils.loadAnimation(this, R.anim.complex_loader);
        if (loadingLogo != null) loadingLogo.startAnimation(complexAnim);

        btnBack.setOnClickListener(v -> finish());
        btnExportPdf.setOnClickListener(v -> exportToPdf(webView));

        setupWebView(webView, graphData, true);
        setupWebView(webViewSimImei, graphData, false);

        if (graphData != null) {
            populateAreaHistogram(graphData);
            populateImeiMapping(graphData);
        }
    }

    private void setupWebView(WebView webView, String graphData, boolean isPrimary) {
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
                    try {
                        JSONObject json = new JSONObject(graphData);
                        String dataToRender = null;
                        String functionName = null;
                        
                        if (isPrimary) {
                            dataToRender = graphData; 
                            functionName = "renderOfflineData";
                        } else {
                            dataToRender = graphData; 
                            functionName = "renderSimImeiData";
                        }

                        if (dataToRender != null && functionName != null) {
                            String encoded = Base64.encodeToString(dataToRender.getBytes(java.nio.charset.StandardCharsets.UTF_8), Base64.NO_WRAP);
                            view.evaluateJavascript("window." + functionName + "(decodeURIComponent(escape(window.atob('" + encoded + "'))))", null);
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
                
                if (isPrimary) {
                    View overlay = findViewById(R.id.loadingOverlay);
                    ImageView logo = findViewById(R.id.loadingLogo);
                    if (overlay != null) {
                        overlay.animate().alpha(0f).setDuration(500).withEndAction(() -> {
                            if (logo != null) logo.clearAnimation();
                            overlay.setVisibility(View.GONE);
                        });
                    }
                }
            }
        });
        webView.loadUrl("file:///android_asset/link_analysis.html");
    }

    private void populateImeiMapping(String jsonString) {
        LinearLayout mappingLayout = findViewById(R.id.imeiMappingLayout);
        mappingLayout.removeAllViews(); // Clear previous
        try {
            JSONObject jsonObject = new JSONObject(jsonString);
            if (!jsonObject.has("imei_to_sim_map")) {
                showNoImeiDataMessage();
                return;
            }
            JSONObject mapping = jsonObject.getJSONObject("imei_to_sim_map");
            if (mapping.length() == 0) {
                showNoImeiDataMessage();
                return;
            }

            java.util.Iterator<String> keys = mapping.keys();
            while (keys.hasNext()) {
                String imei = keys.next();
                JSONObject imeiData = mapping.getJSONObject(imei);
                JSONArray sims = imeiData.getJSONArray("sims");
                String hardware = imeiData.optString("hardware", "Generic Device");
                String alias = imeiData.optString("alias", "");
                String imeiLabel = imei + (alias.isEmpty() ? "" : "(📌 " + alias + ")");
                
                View itemView = getLayoutInflater().inflate(R.layout.item_histogram_bar, mappingLayout, false);
                ((TextView) itemView.findViewById(R.id.areaName)).setText("Handset: " + hardware);
                
                ProgressBar bar = itemView.findViewById(R.id.areaBar);
                bar.setMax(8); 
                bar.setProgress(sims.length());
                
                StringBuilder simList = new StringBuilder();
                for (int i = 0; i < sims.length(); i++) {
                    String sim = sims.getString(i);
                    simList.append(sim);
                    if (i < sims.length() - 1) simList.append(", ");
                }
                
                TextView countView = itemView.findViewById(R.id.areaCount);
                countView.setText(sims.length() + " SIMs");

                TextView detailView = new TextView(this);
                detailView.setText("IMEI: " + imeiLabel + "\nUsed by SIM(s): " + simList.toString());
                detailView.setTextSize(11);
                detailView.setTextColor(Color.parseColor("#8b949e"));
                detailView.setPadding(40, 0, 0, 30);

                mappingLayout.addView(itemView);
                mappingLayout.addView(detailView);
            }
        } catch (JSONException e) {
            showNoImeiDataMessage();
        }
    }

    private void showNoImeiDataMessage() {
        LinearLayout mappingLayout = findViewById(R.id.imeiMappingLayout);
        TextView noData = new TextView(this);
        noData.setText("No multi-SIM handset anomalies detected.");
        noData.setTextColor(Color.parseColor("#8b949e"));
        noData.setPadding(0, 10, 0, 0);
        mappingLayout.addView(noData);
    }

    private String readDataFromFile(String path) {
        try {
            File file = new File(path);
            java.io.FileInputStream fis = new java.io.FileInputStream(file);
            byte[] data = new byte[(int) file.length()];
            int read = fis.read(data);
            fis.close();
            return new String(data, 0, read, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private void exportToPdf(WebView webView) {
        PdfDocument document = new PdfDocument();
        PdfDocument.PageInfo pageInfo = new PdfDocument.PageInfo.Builder(595, 842, 1).create();
        final PdfDocument.Page[] page = {document.startPage(pageInfo)};
        final Canvas[] canvas = {page[0].getCanvas()};
        final float[] y = {50};

        Paint paint = new Paint();
        paint.setColor(Color.BLACK);
        
        // Initial Header
        paint.setTextSize(18);
        paint.setFakeBoldText(true);
        canvas[0].drawText("CDR Intelligence Report - Link Correlation", 50, y[0], paint);
        y[0] += 20;

        paint.setFakeBoldText(false);
        paint.setTextSize(10);
        String date = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
        canvas[0].drawText("Generated on: " + date, 50, y[0], paint);
        y[0] += 30;

        try {
            if (rawGraphData != null) {
                JSONObject json = new JSONObject(rawGraphData);
                JSONObject aliasMap = json.optJSONObject("alias_map");
                
                // 1. Summary of Common B-Parties
                paint.setTextSize(14);
                paint.setFakeBoldText(true);
                canvas[0].drawText("Summary of Common B-Parties (Correlation):", 50, y[0], paint);
                y[0] += 25;

                paint.setFakeBoldText(false);
                paint.setTextSize(11);

                JSONArray common = json.optJSONArray("common-links");
                if (common != null && common.length() > 0) {
                    for (int i = 0; i < common.length(); i++) {
                        JSONObject link = common.getJSONObject(i);
                        String target = link.optString("target", "Unknown");
                        String targetAlias = (aliasMap != null) ? aliasMap.optString(target, "") : "";
                        String targetDisplay = target + (targetAlias.isEmpty() ? "" : "(📌 " + targetAlias + ")");
                        
                        JSONArray sources = link.optJSONArray("source");
                        
                        paint.setFakeBoldText(true);
                        canvas[0].drawText((i + 1) + ". " + targetDisplay + " was found common between:", 60, y[0], paint);
                        y[0] += 15;
                        
                        paint.setFakeBoldText(false);
                        StringBuilder sb = new StringBuilder();
                        if (sources != null) {
                            for (int k = 0; k < sources.length(); k++) {
                                String source = sources.optString(k);
                                String sourceAlias = (aliasMap != null) ? aliasMap.optString(source, "") : "";
                                sb.append(source).append(sourceAlias.isEmpty() ? "" : "(📌 " + sourceAlias + ")");
                                if (k < sources.length() - 1) sb.append(", ");
                            }
                        }
                        canvas[0].drawText(sb.toString(), 75, y[0], paint);
                        y[0] += 30;

                        if (y[0] > 780) {
                            document.finishPage(page[0]);
                            page[0] = document.startPage(pageInfo);
                            canvas[0] = page[0].getCanvas();
                            y[0] = 50;
                        }
                    }
                } else {
                    canvas[0].drawText("No common contacts identified across different A-Parties.", 60, y[0], paint);
                    y[0] += 30;
                }

                // 2. Summary of Hardware Correlation (SIM to IMEI)
                y[0] += 20;
                if (y[0] > 700) {
                    document.finishPage(page[0]);
                    page[0] = document.startPage(pageInfo);
                    canvas[0] = page[0].getCanvas();
                    y[0] = 50;
                }
                
                paint.setTextSize(14);
                paint.setFakeBoldText(true);
                canvas[0].drawText("Summary of Hardware Correlation (SIM to IMEI):", 50, y[0], paint);
                y[0] += 25;

                // Draw Table Headers helper
                drawHardwareTableHeader(canvas[0], paint, y[0]);
                y[0] += 20;
                paint.setFakeBoldText(false);
                paint.setTextSize(10);

                JSONObject simImeiMap = json.optJSONObject("sim_to_imei_map");

                if (simImeiMap != null && simImeiMap.length() > 0) {
                    java.util.Iterator<String> simKeys = simImeiMap.keys();
                    int serial = 1;
                    while (simKeys.hasNext()) {
                        String sim = simKeys.next();
                        String simAlias = (aliasMap != null) ? aliasMap.optString(sim, "") : "";
                        String simDisplay = sim + (simAlias.isEmpty() ? "" : "(📌 " + simAlias + ")");
                        
                        JSONArray imeis = simImeiMap.getJSONArray(sim);
                        
                        for (int k = 0; k < imeis.length(); k++) {
                            // ... (page break logic preserved)
                            if (y[0] > 780) {
                                document.finishPage(page[0]);
                                page[0] = document.startPage(pageInfo);
                                canvas[0] = page[0].getCanvas();
                                y[0] = 50;
                                drawHardwareTableHeader(canvas[0], paint, y[0]);
                                y[0] += 20;
                                paint.setFakeBoldText(false);
                                paint.setTextSize(10);
                            }

                            JSONObject imeiObj = imeis.getJSONObject(k);
                            String imei = imeiObj.optString("imei", "Unknown");
                            String hw = imeiObj.optString("hw", "Generic Handset");
                            String imeiAlias = imeiObj.optString("alias", "");
                            String imeiDisplay = imei + (imeiAlias.isEmpty() ? "" : "(📌 " + imeiAlias + ")");

                            if (k == 0) {
                                paint.setFakeBoldText(true);
                                canvas[0].drawText(String.valueOf(serial), 50, y[0], paint);
                                canvas[0].drawText(simDisplay, 100, y[0], paint);
                                paint.setFakeBoldText(false);
                            }
                            
                            canvas[0].drawText(imeiDisplay, 230, y[0], paint);
                            String brandDisplay = hw.length() > 30 ? hw.substring(0, 27) + "..." : hw;
                            canvas[0].drawText(brandDisplay, 400, y[0], paint);
                            
                            y[0] += 15;
                        }
                        serial++;
                        y[0] += 5;
                        canvas[0].drawLine(50, y[0], 550, y[0], paint);
                        y[0] += 15;
                    }
                } else {
                    canvas[0].drawText("No hardware signature data available.", 60, y[0], paint);
                }
            }
        } catch (Exception e) {
            paint.setColor(Color.RED);
            canvas[0].drawText("Error generating report: " + e.getMessage(), 50, y[0], paint);
        }

        document.finishPage(page[0]);
        savePdfFile(document);
    }

    private void drawHardwareTableHeader(Canvas canvas, Paint paint, float y) {
        paint.setTextSize(10);
        paint.setFakeBoldText(true);
        canvas.drawText("Serial", 50, y, paint);
        canvas.drawText("SIM (A Party)", 100, y, paint);
        canvas.drawText("IMEI Signature(s)", 230, y, paint);
        canvas.drawText("Brand Info", 400, y, paint);
        canvas.drawLine(50, y + 5, 550, y + 5, paint);
    }

    private void savePdfFile(PdfDocument document) {
        File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "CDR_Reports");
        if (!dir.exists() && !dir.mkdirs()) {
            Toast.makeText(this, "Failed to create directory", Toast.LENGTH_SHORT).show();
            document.close();
            return;
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
