package com.example.offlinecdranalyzer;

import android.app.Activity;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Map;

public class CropActivity extends AppCompatActivity {

    private EditText cropLocationInput;
    private TextView cropStatusText;
    private Button btnStartDate, btnStartTime, btnEndDate, btnEndTime, btnOpenCroppedExcel;
    private Calendar startCal, endCal;
    private List<String> selectedFiles = new ArrayList<>();
    private String lastCroppedPath = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        
        setContentView(R.layout.activity_crop);

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            final WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        }

        cropLocationInput = findViewById(R.id.cropLocationInput);
        cropStatusText = findViewById(R.id.cropStatusText);
        btnStartDate = findViewById(R.id.btnStartDate);
        btnStartTime = findViewById(R.id.btnStartTime);
        btnEndDate = findViewById(R.id.btnEndDate);
        btnEndTime = findViewById(R.id.btnEndTime);
        
        startCal = Calendar.getInstance();
        endCal = Calendar.getInstance();

        ImageButton btnBack = findViewById(R.id.btnBackCrop);
        btnBack.setOnClickListener(v -> finish());

        btnStartDate.setOnClickListener(v -> showDatePicker(startCal, btnStartDate));
        btnStartTime.setOnClickListener(v -> showTimePicker(startCal, btnStartTime));
        btnEndDate.setOnClickListener(v -> showDatePicker(endCal, btnEndDate));
        btnEndTime.setOnClickListener(v -> showTimePicker(endCal, btnEndTime));

        btnOpenCroppedExcel = findViewById(R.id.btnOpenCroppedExcel);
        btnOpenCroppedExcel.setOnClickListener(v -> {
            if (lastCroppedPath != null) openExcelFile(lastCroppedPath);
        });

        ActivityResultLauncher<Intent> picker = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    if (result.getData().getClipData() != null) {
                        int count = result.getData().getClipData().getItemCount();
                        for (int i = 0; i < count; i++) {
                            String path = copyToCache(result.getData().getClipData().getItemAt(i).getUri());
                            if (path != null) selectedFiles.add(path);
                        }
                    } else if (result.getData().getData() != null) {
                        String path = copyToCache(result.getData().getData());
                        if (path != null) selectedFiles.add(path);
                    }
                    cropStatusText.setText("Staged " + selectedFiles.size() + " files for cropping.");
                }
            }
        );

        findViewById(R.id.btnSelectExcelCrop).setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            picker.launch(intent);
        });

        findViewById(R.id.btnSelectPdfCrop).setOnClickListener(v -> {
            new AlertDialog.Builder(this)
                    .setTitle("PDF Processing Note")
                    .setMessage(R.string.pdf_speed_warning)
                    .setPositiveButton("Continue", (dialog, which) -> {
                        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                        intent.setType("application/pdf");
                        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                        picker.launch(intent);
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        });

        findViewById(R.id.btnProcessCrop).setOnClickListener(v -> runCropEngine());
    }

    private void showDatePicker(Calendar cal, Button btn) {
        new DatePickerDialog(this, (view, year, month, dayOfMonth) -> {
            cal.set(Calendar.YEAR, year);
            cal.set(Calendar.MONTH, month);
            cal.set(Calendar.DAY_OF_MONTH, dayOfMonth);
            btn.setText(dayOfMonth + "/" + (month + 1) + "/" + year);
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show();
    }

    private void showTimePicker(Calendar cal, Button btn) {
        new TimePickerDialog(this, (view, hourOfDay, minute) -> {
            cal.set(Calendar.HOUR_OF_DAY, hourOfDay);
            cal.set(Calendar.MINUTE, minute);
            btn.setText(String.format("%02d:%02d", hourOfDay, minute));
        }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), true).show();
    }

    private void runCropEngine() {
        if (selectedFiles.isEmpty()) {
            Toast.makeText(this, "Select files first", Toast.LENGTH_SHORT).show();
            return;
        }
        
        btnOpenCroppedExcel.setVisibility(View.GONE);
        cropStatusText.setText("Processing...");
        new Thread(() -> {
            try {
                Python py = Python.getInstance();
                PyObject pyModule = py.getModule("index");
                
                String startTs = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(startCal.getTime());
                String endTs = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(endCal.getTime());
                String location = cropLocationInput.getText().toString();
                
                File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "CDR_Reports");
                if (!dir.exists()) dir.mkdirs();
                
                PyObject result = pyModule.callAttr("crop_cdr_data", 
                    selectedFiles.toArray(new String[0]), location, startTs, endTs, dir.getAbsolutePath());
                
                Map<PyObject, PyObject> resultMap = result.asMap();
                String status = resultMap.get(py.getBuiltins().get("str").call("status")).toString();
                
                runOnUiThread(() -> {
                    if ("success".equals(status)) {
                        lastCroppedPath = resultMap.get(py.getBuiltins().get("str").call("output_path")).toString();
                        cropStatusText.setText("Saved: " + new File(lastCroppedPath).getName());
                        btnOpenCroppedExcel.setVisibility(View.VISIBLE);
                        Toast.makeText(this, "Cropped file exported to Documents/CDR_Reports", Toast.LENGTH_LONG).show();
                        
                        showProcessDialog();
                    } else {
                        cropStatusText.setText("Error: " + resultMap.get(py.getBuiltins().get("str").call("message")));
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> cropStatusText.setText("Failure: " + e.getMessage()));
            }
        }).start();
    }

    private void showProcessDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Process CDR")
                .setMessage("Want to process after cropping?")
                .setPositiveButton("Yes", (dialog, which) -> {
                    Intent intent = new Intent(this, MainActivity.class);
                    intent.putExtra("auto_process_file", lastCroppedPath);
                    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    startActivity(intent);
                    finish();
                })
                .setNegativeButton("No", null)
                .show();
    }

    private void openExcelFile(String path) {
        try {
            File file = new File(path);
            if (!file.exists()) {
                Toast.makeText(this, "File does not exist: " + path, Toast.LENGTH_LONG).show();
                return;
            }
            Uri uri = androidx.core.content.FileProvider.getUriForFile(this, getPackageName() + ".provider", file);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (intent.resolveActivity(getPackageManager()) != null) {
                startActivity(intent);
            } else {
                Toast.makeText(this, "No Excel viewer found. Path copied to clipboard.", Toast.LENGTH_LONG).show();
                android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Cropped Report Path", path));
            }
        } catch (Exception e) {
            Toast.makeText(this, "Error opening file: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private String copyToCache(Uri uri) {
        try {
            String extension = ".xlsx";
            String type = getContentResolver().getType(uri);
            if (type != null && type.equals("application/pdf")) {
                extension = ".pdf";
            }
            
            File tempFile = new File(getCacheDir(), "temp_cdr_crop_" + System.currentTimeMillis() + extension);
            InputStream is = getContentResolver().openInputStream(uri);
            FileOutputStream fos = new FileOutputStream(tempFile);
            byte[] buffer = new byte[8192]; int len;
            while ((len = is.read(buffer)) > 0) fos.write(buffer, 0, len);
            fos.close(); is.close();
            return tempFile.getAbsolutePath();
        } catch (Exception e) { return null; }
    }
}
