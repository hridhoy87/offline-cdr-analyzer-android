package com.example.offlinecdranalyzer;

import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.ImageView;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class SameLocationActivity extends AppCompatActivity {

    private TableLayout locTable;
    private String rawJson;
    private View loadingOverlay;
    private ImageView loadingLogo;
    private Animation complexAnim;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        
        setContentView(R.layout.activity_same_location);

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            final WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        }

        locTable = findViewById(R.id.locTable);
        loadingOverlay = findViewById(R.id.loadingOverlayLoc);
        loadingLogo = findViewById(R.id.loadingLogoLoc);
        complexAnim = AnimationUtils.loadAnimation(this, R.anim.complex_loader);
        ImageButton btnBack = findViewById(R.id.btnBackLoc);
        ImageButton btnExportPdf = findViewById(R.id.btnExportPdfLoc);

        String jsonPath = getIntent().getStringExtra("loc_json_path");
        
        btnBack.setOnClickListener(v -> finish());
        btnExportPdf.setOnClickListener(v -> exportToPdf());

        if (jsonPath != null) {
            startAsyncPopulation(jsonPath);
        } else {
            Toast.makeText(this, "No data path received", Toast.LENGTH_SHORT).show();
        }
    }

    private void startAsyncPopulation(String jsonPath) {
        loadingOverlay.setVisibility(View.VISIBLE);
        loadingLogo.startAnimation(complexAnim);
        new Thread(() -> {
            String content = readFile(jsonPath);
            if (content == null) {
                runOnUiThread(() -> {
                    loadingOverlay.setVisibility(View.GONE);
        loadingLogo.clearAnimation();
                    Toast.makeText(this, "Failed to read results file", Toast.LENGTH_SHORT).show();
                });
                return;
            }
            rawJson = content;
            try {
                JSONArray array = new JSONArray(rawJson);
                int total = array.length();
                int chunkSize = 40;
                
                for (int i = 0; i < total; i += chunkSize) {
                    final int currentStart = i;
                    final int currentEnd = Math.min(i + chunkSize, total);
                    final List<TableRow> batchRows = new ArrayList<>();
                    
                    for (int j = currentStart; j < currentEnd; j++) {
                        JSONObject obj = array.getJSONObject(j);
                        batchRows.add(createRowFromData(obj));
                    }
                    
                    runOnUiThread(() -> {
                        for (TableRow row : batchRows) locTable.addView(row);
                        if (currentEnd == total) {
                            loadingOverlay.setVisibility(View.GONE);
        loadingLogo.clearAnimation();
                        }
                    });
                    
                    // Yield slightly to keep UI responsive
                    Thread.sleep(20);
                }
                
                if (total == 0) {
                    runOnUiThread(() -> {
                        loadingOverlay.setVisibility(View.GONE);
        loadingLogo.clearAnimation();
                        Toast.makeText(this, "Analysis yielded zero overlaps", Toast.LENGTH_SHORT).show();
                    });
                }
            } catch (Exception e) {
                runOnUiThread(() -> {
                    loadingOverlay.setVisibility(View.GONE);
        loadingLogo.clearAnimation();
                    Toast.makeText(this, "Display error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }

    private String readFile(String path) {
        try {
            File file = new File(path);
            FileInputStream fis = new FileInputStream(file);
            byte[] data = new byte[(int) file.length()];
            int read = fis.read(data);
            fis.close();
            return new String(data, 0, read, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private TableRow createRowFromData(JSONObject obj) {
        TableRow row = new TableRow(this);
        row.setPadding(4, 4, 4, 4);
        row.addView(createCell(obj.optString("Time", "N/A")));
        row.addView(createCell(obj.optString("A_Party", "N/A")));
        row.addView(createCell(obj.optString("B_Party", "N/A")));
        row.addView(createCell(obj.optString("LAC", "N/A")));
        row.addView(createCell(obj.optString("Cell", "N/A")));
        row.addView(createCell(obj.optString("BTS_Loc", "N/A")));
        row.addView(createCell(obj.optString("Reason", "N/A")));
        return row;
    }

    private TextView createCell(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setPadding(8, 8, 8, 8);
        android.util.TypedValue typedValue = new android.util.TypedValue();
        getTheme().resolveAttribute(R.attr.primaryTextColor, typedValue, true);
        tv.setTextColor(typedValue.data);
        return tv;
    }

    private void exportToPdf() {
        if (rawJson == null) return;
        PdfDocument pdfDocument = new PdfDocument();
        PdfDocument.PageInfo pageInfo = new PdfDocument.PageInfo.Builder(842, 595, 1).create();
        PdfDocument.Page page = pdfDocument.startPage(pageInfo);
        Canvas canvas = page.getCanvas();
        Paint paint = new Paint();
        
        drawHeader(canvas, paint);
        
        paint.setTextSize(10f);
        paint.setFakeBoldText(false);
        float y = 100;
        
        try {
            JSONArray array = new JSONArray(rawJson);
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.getJSONObject(i);
                canvas.drawText(obj.optString("Time", "N/A"), 40, y, paint);
                canvas.drawText(obj.optString("A_Party", "N/A"), 140, y, paint);
                canvas.drawText(obj.optString("B_Party", "N/A"), 240, y, paint);
                canvas.drawText(obj.optString("LAC", "N/A"), 340, y, paint);
                canvas.drawText(obj.optString("Cell", "N/A"), 400, y, paint);
                String bts = obj.optString("BTS_Loc", "N/A");
                if (bts.length() > 40) bts = bts.substring(0, 37) + "...";
                canvas.drawText(bts, 460, y, paint);
                canvas.drawText(obj.optString("Reason", "N/A"), 700, y, paint);
                
                y += 15;
                if (y > 550) {
                    pdfDocument.finishPage(page);
                    page = pdfDocument.startPage(pageInfo);
                    canvas = page.getCanvas();
                    drawHeader(canvas, paint);
                    y = 100;
                }
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }

        pdfDocument.finishPage(page);
        File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "CDR_Reports");
        if (!dir.exists()) dir.mkdirs();
        
        String fileName = "SameLocationAnalysis_" + System.currentTimeMillis() + ".pdf";
        File file = new File(dir, fileName);
        
        try {
            pdfDocument.writeTo(new FileOutputStream(file));
            Toast.makeText(this, "PDF Exported Successfully", Toast.LENGTH_SHORT).show();
            openPdf(file);
        } catch (IOException e) {
            Toast.makeText(this, "Export Failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        } finally {
            pdfDocument.close();
        }
    }

    private void drawHeader(Canvas canvas, Paint paint) {
        paint.setTextSize(14f);
        paint.setFakeBoldText(true);
        canvas.drawText("Same Location Analysis Report", 40, 40, paint);
        
        paint.setTextSize(10f);
        paint.setFakeBoldText(true);
        float y = 80;
        canvas.drawText("Time", 40, y, paint);
        canvas.drawText("A Party", 140, y, paint);
        canvas.drawText("B Party", 240, y, paint);
        canvas.drawText("LAC", 340, y, paint);
        canvas.drawText("Cell", 400, y, paint);
        canvas.drawText("BTS Loc", 460, y, paint);
        canvas.drawText("Reason", 700, y, paint);
        paint.setFakeBoldText(false);
    }

    private void openPdf(File file) {
        Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".provider", file);
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, "application/pdf");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(intent, "Open PDF"));
    }
}
