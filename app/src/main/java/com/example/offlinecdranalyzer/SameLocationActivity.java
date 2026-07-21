package com.example.offlinecdranalyzer;

import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
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
import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class SameLocationActivity extends AppCompatActivity {

    private TableLayout locTable;
    private String rawJson;
    private View loadingOverlay;
    private ImageView loadingLogo;
    private Animation complexAnim;
    
    private TextView txtStartDate, txtEndDate;
    private Calendar filterStart = null;
    private Calendar filterEnd = null;
    private List<String> sourceFilePaths;

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
        ImageButton btnExportExcel = findViewById(R.id.btnExportExcelLoc);
        
        txtStartDate = findViewById(R.id.txtStartDate);
        txtEndDate = findViewById(R.id.txtEndDate);
        ImageButton btnRefine = findViewById(R.id.btnRefineTimeline);

        // Add Header Row Immediately
        addTableHeader();

        String jsonPath = getIntent().getStringExtra("loc_json_path");
        sourceFilePaths = getIntent().getStringArrayListExtra("file_paths");
        
        btnBack.setOnClickListener(v -> finish());
        btnExportPdf.setOnClickListener(v -> exportToPdf());
        btnExportExcel.setOnClickListener(v -> exportToExcel());

        txtStartDate.setOnClickListener(v -> showDateTimePicker(true));
        txtEndDate.setOnClickListener(v -> showDateTimePicker(false));
        btnRefine.setOnClickListener(v -> refineAnalysis());

        if (jsonPath != null) {
            startAsyncPopulation(jsonPath);
        } else {
            Toast.makeText(this, "No data path received", Toast.LENGTH_SHORT).show();
        }
    }

    private void showDateTimePicker(boolean isStart) {
        Calendar current = Calendar.getInstance();
        new DatePickerDialog(this, (view, year, month, dayOfMonth) -> {
            Calendar cal = Calendar.getInstance();
            cal.set(Calendar.YEAR, year);
            cal.set(Calendar.MONTH, month);
            cal.set(Calendar.DAY_OF_MONTH, dayOfMonth);

            new TimePickerDialog(this, (v, hour, minute) -> {
                cal.set(Calendar.HOUR_OF_DAY, hour);
                cal.set(Calendar.MINUTE, minute);
                cal.set(Calendar.SECOND, 0);

                SimpleDateFormat sdf = new SimpleDateFormat("dd/MM/yy HH:mm", Locale.US);
                if (isStart) {
                    filterStart = cal;
                    txtStartDate.setText(sdf.format(cal.getTime()));
                } else {
                    filterEnd = cal;
                    txtEndDate.setText(sdf.format(cal.getTime()));
                }
            }, 0, 0, true).show();
        }, current.get(Calendar.YEAR), current.get(Calendar.MONTH), current.get(Calendar.DAY_OF_MONTH)).show();
    }

    private void refineAnalysis() {
        if (sourceFilePaths == null || sourceFilePaths.isEmpty()) {
            Toast.makeText(this, "Source files missing", Toast.LENGTH_SHORT).show();
            return;
        }

        loadingOverlay.setVisibility(View.VISIBLE);
        loadingLogo.startAnimation(complexAnim);
        locTable.removeAllViews();
        addTableHeader();

        new Thread(() -> {
            try {
                Python py = Python.getInstance();
                PyObject pyModule = py.getModule("index");
                
                String startTs = null, endTs = null;
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
                if (filterStart != null) startTs = sdf.format(filterStart.getTime());
                if (filterEnd != null) endTs = sdf.format(filterEnd.getTime());

                // Note: We need a slight modification to our same_location_analysis in python 
                // to support start/end TS. For now we will filter the existing rawJson or 
                // re-run engine if required.
                
                // Let's filter the existing rawJson in memory for instant refinement 
                // unless we want a full re-process. Filtering is faster.
                JSONArray original = new JSONArray(rawJson);
                JSONArray filtered = new JSONArray();
                
                SimpleDateFormat parseSdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
                
                for (int i = 0; i < original.length(); i++) {
                    JSONObject obj = original.getJSONObject(i);
                    String timeStr = obj.optString("Time");
                    try {
                        Date date = parseSdf.parse(timeStr);
                        if (date == null) {
                            filtered.put(obj); // Keep if unparseable? Or skip?
                            continue;
                        }
                        long ts = date.getTime();
                        boolean match = true;
                        if (filterStart != null && ts < filterStart.getTimeInMillis()) match = false;
                        if (filterEnd != null && ts > filterEnd.getTimeInMillis()) match = false;
                        if (match) filtered.put(obj);
                    } catch (Exception e) { 
                        // If no filter is set, keep everything. If filter is set, skip errors.
                        if (filterStart == null && filterEnd == null) filtered.put(obj);
                    }
                }

                String filteredJson = filtered.toString();
                runOnUiThread(() -> {
                    rawJson = filteredJson;
                    populateTableFromArray(filtered);
                });

            } catch (Exception e) {
                runOnUiThread(() -> {
                    loadingOverlay.setVisibility(View.GONE);
                    Toast.makeText(this, "Refinement error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }

    private void populateTableFromArray(JSONArray array) {
        try {
            int total = array.length();
            if (total == 0) {
                loadingOverlay.setVisibility(View.GONE);
                Toast.makeText(this, "No overlaps in this range", Toast.LENGTH_SHORT).show();
                return;
            }

            for (int i = 0; i < total; i++) {
                locTable.addView(createRowFromData(array.getJSONObject(i), i));
            }
            loadingOverlay.setVisibility(View.GONE);
            loadingLogo.clearAnimation();
        } catch (Exception e) {
            loadingOverlay.setVisibility(View.GONE);
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
                        batchRows.add(createRowFromData(obj, j));
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
        if (path == null) return null;
        try {
            File file = new File(path);
            if (!file.exists()) return null;
            java.io.FileInputStream fis = new java.io.FileInputStream(file);
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int len;
            while ((len = fis.read(buffer)) != -1) {
                bos.write(buffer, 0, len);
            }
            fis.close();
            return bos.toString("UTF-8");
        } catch (Exception e) {
            return null;
        }
    }

    private void addTableHeader() {
        // Clear everything first to ensure only one header exists
        locTable.removeAllViews();
        
        TableRow header = new TableRow(this);
        header.setBackgroundColor(Color.parseColor("#2C3E50"));
        header.setPadding(0, 12, 0, 12);

        header.addView(createHeaderCell("Time"));
        header.addView(createHeaderCell("A Party"));
        header.addView(createHeaderCell("B Party"));
        header.addView(createHeaderCell("LAC"));
        header.addView(createHeaderCell("Cell"));
        header.addView(createHeaderCell("BTS Loc"));
        header.addView(createHeaderCell("Reason"));

        locTable.addView(header);
    }

    private TextView createHeaderCell(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setPadding(12, 12, 12, 12);
        tv.setGravity(android.view.Gravity.CENTER);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(13);
        tv.setTypeface(null, android.graphics.Typeface.BOLD);
        return tv;
    }

    private TableRow createRowFromData(JSONObject obj, int rowIndex) {
        TableRow row = new TableRow(this);
        row.setPadding(0, 4, 0, 4);
        
        // Transparent grey stripe: safe for both Light and Dark themes
        if (rowIndex % 2 == 1) {
            row.setBackgroundColor(Color.argb(20, 128, 128, 128));
        }

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
        tv.setPadding(12, 8, 12, 8);
        tv.setTextSize(11);
        
        // Ensure text is always visible regardless of theme
        android.util.TypedValue typedValue = new android.util.TypedValue();
        if (getTheme().resolveAttribute(R.attr.primaryTextColor, typedValue, true)) {
            tv.setTextColor(typedValue.data);
        } else {
            tv.setTextColor(Color.BLACK); // Fallback
        }
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
        float btsX = 460;
        float btsWidth = 350;
        
        try {
            JSONArray array = new JSONArray(rawJson);
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.getJSONObject(i);
                
                String bts = obj.optString("BTS_Loc", "N/A");
                List<String> btsLines = wrapText(bts, btsWidth, paint);
                
                float rowHeight = Math.max(15, btsLines.size() * 12);
                
                if (y + rowHeight > 550) {
                    pdfDocument.finishPage(page);
                    page = pdfDocument.startPage(pageInfo);
                    canvas = page.getCanvas();
                    drawHeader(canvas, paint);
                    y = 100;
                }

                canvas.drawText(obj.optString("Time", "N/A"), 40, y, paint);
                canvas.drawText(obj.optString("A_Party", "N/A"), 140, y, paint);
                canvas.drawText(obj.optString("B_Party", "N/A"), 240, y, paint);
                canvas.drawText(obj.optString("LAC", "N/A"), 340, y, paint);
                canvas.drawText(obj.optString("Cell", "N/A"), 400, y, paint);
                
                float lineY = y;
                for (String line : btsLines) {
                    canvas.drawText(line, btsX, lineY, paint);
                    lineY += 12;
                }
                
                y += rowHeight + 8;
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

    private void exportToExcel() {
        if (rawJson == null) return;
        
        loadingOverlay.setVisibility(View.VISIBLE);
        loadingLogo.startAnimation(complexAnim);
        
        new Thread(() -> {
            try {
                File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "CDR_Reports");
                if (!dir.exists()) dir.mkdirs();
                
                String fileName = "SameLocationAnalysis_" + System.currentTimeMillis() + ".xlsx";
                File file = new File(dir, fileName);
                
                Python py = Python.getInstance();
                PyObject pyModule = py.getModule("index");
                PyObject result = pyModule.callAttr("export_same_location_to_excel", rawJson, file.getAbsolutePath());
                
                Map<PyObject, PyObject> resultMap = result.asMap();
                PyObject pyStatus = resultMap.get(py.getBuiltins().get("str").call("status"));
                String status = (pyStatus != null) ? pyStatus.toString() : "error";
                
                runOnUiThread(() -> {
                    loadingOverlay.setVisibility(View.GONE);
                    loadingLogo.clearAnimation();
                    if ("success".equals(status)) {
                        Toast.makeText(this, "Excel Exported Successfully", Toast.LENGTH_SHORT).show();
                        openExcelFile(file.getAbsolutePath());
                    } else {
                        PyObject pyMsg = resultMap.get(py.getBuiltins().get("str").call("message"));
                        String msg = (pyMsg != null) ? pyMsg.toString() : "Unknown error";
                        Toast.makeText(this, "Export Failed: " + msg, Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    loadingOverlay.setVisibility(View.GONE);
                    loadingLogo.clearAnimation();
                    Toast.makeText(this, "System Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }

    private void openExcelFile(String path) {
        try {
            File file = new File(path);
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".provider", file);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(Intent.createChooser(intent, "Open Excel"));
        } catch (Exception e) {
            Toast.makeText(this, "Error opening file: " + e.getMessage(), Toast.LENGTH_LONG).show();
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
        canvas.drawText("BTS Location (Full Address)", 460, y, paint);
        paint.setFakeBoldText(false);
    }

    private List<String> wrapText(String text, float width, Paint paint) {
        List<String> result = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            result.add("");
            return result;
        }
        
        String[] words = text.split(" ");
        StringBuilder line = new StringBuilder();
        for (String word : words) {
            if (paint.measureText(line + word) <= width) {
                line.append(word).append(" ");
            } else {
                if (line.length() > 0) {
                    result.add(line.toString().trim());
                    line = new StringBuilder();
                }
                
                if (paint.measureText(word) > width) {
                    int start = 0;
                    while (start < word.length()) {
                        int count = paint.breakText(word, start, word.length(), true, width, null);
                        result.add(word.substring(start, start + count));
                        start += count;
                    }
                } else {
                    line.append(word).append(" ");
                }
            }
        }
        if (line.length() > 0) result.add(line.toString().trim());
        return result;
    }

    private void openPdf(File file) {
        Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".provider", file);
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, "application/pdf");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(intent, "Open PDF"));
    }
}
