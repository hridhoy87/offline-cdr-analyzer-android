/**
 * Lines 1-52: Imports for Android UI, Chaquopy Python integration, and Java utilities.
 * Lines 54-80: MainActivity Class Definition -> Manages the lifecycle of the dashboard, 
 *              including file selection, Python engine execution, and result display.
 * Lines 82-250: onCreate & UI Setup -> Initializes views, registers the file picker launcher, 
 *               and sets up consolidated click listeners for analysis and search.
 * Lines 252-300: runPythonEngine -> Spawns a background thread to execute the 'process_cdr_data' 
 *                 Python module, managing the complex loading animation and error handling.
 * Lines 302-420: populateResults -> Parses the intelligence metrics returned from Python, 
 *                 updates the dashboard UI with HTML-formatted summaries, and handles 
 *                 the unified "Tap to Share" logic for the entire results card.
 * Lines 422-480: performCdrSearch & showSearchResultsDialog -> Executes the global 
 *                 cross-column search engine and displays results in a scrollable, shareable dialog.
 * Lines 482-620: showPeekDialog & UI Helpers -> Manages the intelligence preview table 
 *                 with dual-axis scrolling and formatted clipboard/sharing features.
 * Lines 622-685: Lifecycle & Cache Management -> Handles app state refreshing and cleaning 
 *                 temporary file artifacts from the cache directory.
 **/
package com.example.offlinecdranalyzer;

import android.app.Activity;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.text.Html;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintManager;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.view.inputmethod.InputMethodManager;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Iterator;
import android.widget.ScrollView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends AppCompatActivity {

    private EditText locationInput, searchCdrInput;
    private TextView statusText;
    private View loadingContainer;
    private ImageView loadingLogo;
    private View rippleEffect;
    private SwipeRefreshLayout swipeRefreshLayout;
    private android.widget.ScrollView mainScrollView;
    private View resultsContainer;
    private TextView summaryAParties, summaryTopThree, summaryNightStays, summaryCommonBParties;
    private View summaryCardContent;
    private TextView badgeImei, badgeMultiSim, badgeNightRoutine;
    private LinearLayout heatmapContainer;
    private Button btnOpenReport, btnTakeAPeek, btnLinkAnalysis, btnSameLocation;
    private ImageButton btnSearchCdr;
    private View locProgressContainer;
    private ProgressBar locProgressBar;
    private TextView locProgressText;
    private ImageView locLoadingIcon;

    private List<String> selectedFilePaths = new ArrayList<>();
    private String lastGeneratedReportPath = null;
    private String lastGraphData = null;
    private String lastSameLocationJson = null;
    private String lastSpatialSummary = null;
    private String lastPreviewJson = null;
    private String lastSummaryHtml = null;
    
    private Calendar filterStartCal = null;
    private Calendar filterEndCal = null;
    private java.util.HashMap<String, String> aliasMap = new java.util.HashMap<>();

    private Thread backgroundAnalysisThread = null;
    private boolean isAnalysisReady = false;
    private boolean isAnalysisRunning = false;
    private boolean isWaitingForAnalysis = false;
    private int currentAnalysisProgress = 0;

    private static final long INACTIVITY_TIMEOUT = 30 * 60 * 1000; // 30 minutes
    private Handler inactivityHandler = new Handler(Looper.getMainLooper());
    private Runnable inactivityRunnable = () -> {
        Toast.makeText(this, R.string.session_expired, Toast.LENGTH_LONG).show();
        refreshAppState();
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
        
        setContentView(R.layout.activity_main);

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            final WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        }

        if (!Python.isStarted()) {
            Python.start(new AndroidPlatform(this));
        }

        loadAliasesFromJson();
        cleanCache();
        ensureReportDirectory();
        showWelcomeDialog();

        locationInput = findViewById(R.id.locationInput);
        statusText = findViewById(R.id.statusText);
        loadingContainer = findViewById(R.id.loadingContainer);
        loadingLogo = findViewById(R.id.loadingLogo);
        rippleEffect = findViewById(R.id.rippleEffect);
        swipeRefreshLayout = findViewById(R.id.swipeRefreshLayout);
        mainScrollView = findViewById(R.id.mainScrollView);
        resultsContainer = findViewById(R.id.resultsContainer);
        searchCdrInput = findViewById(R.id.searchCdrInput);
        btnSearchCdr = findViewById(R.id.btnSearchCdr);
        summaryAParties = findViewById(R.id.summaryAParties);
        summaryTopThree = findViewById(R.id.summaryTopThree);
        summaryNightStays = findViewById(R.id.summaryNightStays);
        summaryCommonBParties = findViewById(R.id.summaryCommonBParties);
        summaryCardContent = findViewById(R.id.summaryCardContent);
        badgeImei = findViewById(R.id.badgeImei);
        badgeMultiSim = findViewById(R.id.badgeMultiSim);
        badgeNightRoutine = findViewById(R.id.badgeNightRoutine);
        heatmapContainer = findViewById(R.id.heatmapContainer);
        locProgressContainer = findViewById(R.id.locProgressContainer);
        locProgressBar = findViewById(R.id.locProgressBar);
        locProgressText = findViewById(R.id.locProgressText);
        locLoadingIcon = findViewById(R.id.locLoadingIcon);

        Button btnSelectExcel = findViewById(R.id.btnSelectExcel);
        Button btnSelectPdf = findViewById(R.id.btnSelectPdf);
        Button btnProcess = findViewById(R.id.btnProcess);
        Button btnRefresh = findViewById(R.id.btnRefresh);
        btnOpenReport = findViewById(R.id.btnOpenReport);
        btnTakeAPeek = findViewById(R.id.btnTakeAPeek);
        btnLinkAnalysis = findViewById(R.id.btnLinkAnalysis);
        btnSameLocation = findViewById(R.id.btnSameLocation);
        ImageButton btnMenu = findViewById(R.id.btnMenu);
        Button btnCropLauncher = findViewById(R.id.btnCropLauncher);

        btnSameLocation.setOnClickListener(v -> {
            hideKeyboard(v);
            runSameLocationAnalysis();
        });

        btnCropLauncher.setOnClickListener(v -> {
            startActivity(new Intent(this, CropActivity.class));
        });

        btnMenu.setOnClickListener(v -> {
            PopupMenu popup = new PopupMenu(MainActivity.this, v);
            popup.getMenuInflater().inflate(R.menu.main_menu, popup.getMenu());
            popup.setOnMenuItemClickListener(item -> {
                if (item.getItemId() == R.id.action_about) {
                    showAboutDialog();
                    return true;
                } else if (item.getItemId() == R.id.action_edit_aliases) {
                    showEditAliasesDialog();
                    return true;
                } else if (item.getItemId() == R.id.action_update) {
                    showUpdateBanner();
                    return true;
                } else if (item.getItemId() == R.id.action_save_pdf) {
                    generatePdfReport();
                    return true;
                }
                return false;
            });
            popup.show();
        });

        btnTakeAPeek.setOnClickListener(v -> {
            hideKeyboard(v);
            showPeekDialog();
        });

        btnLinkAnalysis.setOnClickListener(v -> {
            hideKeyboard(v);
            if (lastGraphData != null) {
                try {
                    File tempFile = new File(getCacheDir(), "link_graph_data.json");
                    FileOutputStream fos = new FileOutputStream(tempFile);
                    fos.write(lastGraphData.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    fos.close();
                    
                    Intent intent = new Intent(this, LinkAnalysisActivity.class);
                    intent.putExtra("graph_data_path", tempFile.getAbsolutePath());
                    startActivity(intent);
                } catch (Exception e) {
                    Toast.makeText(this, "Error preparing graph data: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                }
            }
        });

        btnSearchCdr.setOnClickListener(v -> {
            hideKeyboard(v);
            String query = searchCdrInput.getText().toString().trim();
            if (query.isEmpty()) {
                Toast.makeText(this, R.string.enter_search_terms, Toast.LENGTH_SHORT).show();
                return;
            }
            performCdrSearch(query);
        });

        swipeRefreshLayout.setEnabled(true);
        swipeRefreshLayout.setOnRefreshListener(() -> {
            refreshAppState();
            swipeRefreshLayout.setRefreshing(false);
        });

        mainScrollView.getViewTreeObserver().addOnScrollChangedListener(() -> {
            if (loadingContainer.getVisibility() != View.VISIBLE) {
                swipeRefreshLayout.setEnabled(mainScrollView.getScrollY() == 0);
            } else {
                swipeRefreshLayout.setEnabled(false);
            }
        });

        btnRefresh.setOnClickListener(v -> {
            hideKeyboard(v);
            refreshAppState();
        });

        ActivityResultLauncher<Intent> filePickerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                        resultsContainer.setVisibility(View.GONE);
                        if (result.getData().getClipData() != null) {
                            int count = result.getData().getClipData().getItemCount();
                            for (int i = 0; i < count; i++) {
                                Uri uri = result.getData().getClipData().getItemAt(i).getUri();
                                String path = copyUriToCache(uri);
                                if (path != null) selectedFilePaths.add(path);
                            }
                        } else if (result.getData().getData() != null) {
                            String path = copyUriToCache(result.getData().getData());
                            if (path != null) selectedFilePaths.add(path);
                        }
                        statusText.setText(getString(R.string.staged_success, selectedFilePaths.size()));
                    }
                }
        );

        btnSelectExcel.setOnClickListener(v -> {
            hideKeyboard(v);
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            filePickerLauncher.launch(intent);
        });

        btnSelectPdf.setOnClickListener(v -> {
            hideKeyboard(v);
            new AlertDialog.Builder(this)
                    .setTitle("PDF Processing Note")
                    .setMessage(R.string.pdf_speed_warning)
                    .setPositiveButton("Continue", (dialog, which) -> {
                        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                        intent.setType("application/pdf");
                        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                        filePickerLauncher.launch(intent);
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        });

        btnProcess.setOnClickListener(v -> {
            hideKeyboard(v);
            if (selectedFilePaths.isEmpty()) {
                statusText.setText(R.string.error_select_files_first);
                return;
            }
            ensureReportDirectory();
            showTimelineOptionDialog();
        });

        btnOpenReport.setOnClickListener(v -> {
            if (lastGeneratedReportPath != null) {
                openExcelFile(lastGeneratedReportPath);
            }
        });

        handleAutoProcessIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleAutoProcessIntent(intent);
    }

    private void handleAutoProcessIntent(Intent intent) {
        if (intent != null && intent.hasExtra("auto_process_file")) {
            String filePath = intent.getStringExtra("auto_process_file");
            if (filePath != null) {
                refreshAppState();
                selectedFilePaths.clear();
                selectedFilePaths.add(filePath);
                statusText.setText(getString(R.string.staged_success, 1));
                
                filterStartCal = null;
                filterEndCal = null;
                runPythonEngine();
            }
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_about) {
            showAboutDialog();
            return true;
        } else if (item.getItemId() == R.id.action_edit_aliases) {
            showEditAliasesDialog();
            return true;
        } else if (item.getItemId() == R.id.action_save_pdf) {
            generatePdfReport();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void generatePdfReport() {
        if (!isAnalysisReady || lastGeneratedReportPath == null) {
            Toast.makeText(this, "Please run analysis first", Toast.LENGTH_SHORT).show();
            return;
        }

        loadingContainer.setVisibility(View.VISIBLE);
        statusText.setText("Preparing Forensic PDF...");
        
        Animation complexAnim = AnimationUtils.loadAnimation(this, R.anim.complex_loader);
        loadingLogo.startAnimation(complexAnim);
        rippleEffect.startAnimation(complexAnim);

        new Thread(() -> {
            try {
                Python py = Python.getInstance();
                PyObject pyModule = py.getModule("pdf_report_writer");
                
                // 1. Prepare Case Name
                String caseName = "FORENSIC_" + new SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(new Date());
                
                // 2. Prepare Summary HTML
                String summary = lastSummaryHtml != null ? lastSummaryHtml : "No Summary Available";

                // 3. Prepare Preview Rows (already in lastPreviewRows)
                
                // 4. Read Same Location JSON from temp file
                String sameLocJson = "[]";
                if (lastSameLocationJson != null) {
                    File file = new File(lastSameLocationJson);
                    if (file.exists()) {
                        byte[] encoded = java.nio.file.Files.readAllBytes(file.toPath());
                        sameLocJson = new String(encoded, java.nio.charset.StandardCharsets.UTF_8);
                    }
                }

                // 5. CDR Names
                List<String> cdrNames = new ArrayList<>();
                for (String p : selectedFilePaths) {
                    cdrNames.add(new File(p).getName());
                }

                String locReq = locationInput.getText().toString().trim();
                if (locReq.isEmpty()) locReq = "All Locations";
                
                String timeline = "Complete History";
                if (filterStartCal != null && filterEndCal != null) {
                    SimpleDateFormat sdf = new SimpleDateFormat("dd MMM yy HH:mm", Locale.US);
                    timeline = sdf.format(filterStartCal.getTime()) + " to " + sdf.format(filterEndCal.getTime());
                }

                // Call Python to generate HTML
                PyObject htmlResult = pyModule.callAttr("generate_case_report_html",
                        caseName,
                        summary,
                        lastGraphData, // Pass unified JSON string
                        sameLocJson,
                        aliasMap,
                        locReq,
                        timeline,
                        cdrNames.toArray(new String[0]),
                        lastSpatialSummary
                );

                String finalHtml = htmlResult.toString();

                runOnUiThread(() -> {
                    loadingContainer.setVisibility(View.GONE);
                    loadingLogo.clearAnimation();
                    rippleEffect.clearAnimation();
                    printHtmlToPdf(finalHtml, caseName);
                });

            } catch (Exception e) {
                Log.e("PDF_GEN", "Error: ", e);
                runOnUiThread(() -> {
                    loadingContainer.setVisibility(View.GONE);
                    loadingLogo.clearAnimation();
                    rippleEffect.clearAnimation();
                    Toast.makeText(this, "Failed to generate PDF: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private void printHtmlToPdf(String htmlContent, String jobName) {
        WebView webView = new WebView(this);
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                createWebPrintJob(view, jobName);
            }
        });

        // Load HTML with base URL to allow internal resources if needed
        webView.loadDataWithBaseURL(null, htmlContent, "text/html", "utf-8", null);
    }

    private void createWebPrintJob(WebView webView, String jobName) {
        PrintManager printManager = (PrintManager) this.getSystemService(Context.PRINT_SERVICE);
        PrintDocumentAdapter printAdapter = webView.createPrintDocumentAdapter(jobName);
        
        PrintAttributes attributes = new PrintAttributes.Builder()
                .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                .setResolution(new PrintAttributes.Resolution("pdf", "pdf", 600, 600))
                .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
                .build();

        printManager.print(jobName, printAdapter, attributes);
    }

    private void showEditAliasesDialog() {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_edit_aliases, null);
        EditText editNumber = dialogView.findViewById(R.id.editNumber);
        EditText editAlias = dialogView.findViewById(R.id.editAlias);
        Button btnAdd = dialogView.findViewById(R.id.btnAddAlias);
        LinearLayout containerList = dialogView.findViewById(R.id.containerAliasList);

        // Track editing state
        final String[] editingNumber = {null};

        updateAliasDisplayList(containerList, editNumber, editAlias, btnAdd, editingNumber);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(dialogView)
                .setPositiveButton("Done", (d, w) -> hideKeyboard(editNumber))
                .create();

        btnAdd.setOnClickListener(v -> {
            String num = editNumber.getText().toString().trim();
            String alias = editAlias.getText().toString().trim();

            if (num.isEmpty() || alias.isEmpty()) {
                Toast.makeText(this, "Enter valid number and alias", Toast.LENGTH_SHORT).show();
                return;
            }

            if (editingNumber[0] != null && !editingNumber[0].equals(num)) {
                aliasMap.remove(editingNumber[0]);
            }

            aliasMap.put(num, alias);
            saveAliasesToJson();
            
            editingNumber[0] = null;
            btnAdd.setText("+");
            editNumber.setText("");
            editAlias.setText("");
            hideKeyboard(v);
            
            updateAliasDisplayList(containerList, editNumber, editAlias, btnAdd, editingNumber);
            Toast.makeText(this, "Alias saved", Toast.LENGTH_SHORT).show();
        });

        dialog.show();
    }

    private void updateAliasDisplayList(LinearLayout container, EditText editNum, EditText editAl, Button btnAdd, String[] editingNumber) {
        container.removeAllViews();
        if (aliasMap.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("No aliases defined.");
            empty.setPadding(20, 20, 20, 20);
            container.addView(empty);
            return;
        }

        for (Map.Entry<String, String> entry : aliasMap.entrySet()) {
            View itemView = getLayoutInflater().inflate(R.layout.item_alias, null);
            TextView txtName = itemView.findViewById(R.id.txtAliasName);
            TextView txtNum = itemView.findViewById(R.id.txtAliasNumber);
            ImageButton btnEdit = itemView.findViewById(R.id.btnEditAlias);
            ImageButton btnDelete = itemView.findViewById(R.id.btnDeleteAlias);

            String number = entry.getKey();
            String alias = entry.getValue();

            txtName.setText(alias);
            txtNum.setText(number);

            btnEdit.setOnClickListener(v -> {
                editNum.setText(number);
                editAl.setText(alias);
                editingNumber[0] = number;
                btnAdd.setText("Update");
                editNum.requestFocus();
            });

            btnDelete.setOnClickListener(v -> {
                new AlertDialog.Builder(this)
                        .setTitle("Delete Alias")
                        .setMessage("Are you sure you want to delete alias for " + number + "?")
                        .setPositiveButton("Delete", (d, w) -> {
                            aliasMap.remove(number);
                            saveAliasesToJson();
                            updateAliasDisplayList(container, editNum, editAl, btnAdd, editingNumber);
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            });

            container.addView(itemView);
        }
    }

    private void saveAliasesToJson() {
        try {
            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "CDR_Reports");
            if (!dir.exists()) dir.mkdirs();
            File file = new File(dir, "aliases_metadata.json");
            
            JSONObject json = new JSONObject();
            for (Map.Entry<String, String> entry : aliasMap.entrySet()) {
                json.put(entry.getKey(), entry.getValue());
            }
            
            FileOutputStream fos = new FileOutputStream(file);
            fos.write(json.toString(4).getBytes());
            fos.close();
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "Error saving aliases: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void loadAliasesFromJson() {
        try {
            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "CDR_Reports");
            if (!dir.exists()) dir.mkdirs();
            File file = new File(dir, "aliases_metadata.json");

            String str = null;
            if (file.exists()) {
                java.io.FileInputStream fis = new java.io.FileInputStream(file);
                byte[] data = new byte[(int) file.length()];
                fis.read(data);
                fis.close();
                str = new String(data, "UTF-8");
            } else {
                // If it doesn't exist in Documents, try to load the default from assets
                try {
                    java.io.InputStream is = getAssets().open("aliases_metadata.json");
                    int size = is.available();
                    byte[] buffer = new byte[size];
                    is.read(buffer);
                    is.close();
                    str = new String(buffer, "UTF-8");
                    // Save it to Documents immediately for future persistence
                    saveAliasesToJson(); 
                } catch (Exception assetEx) {
                    return; // No assets file either
                }
            }

            if (str != null) {
                JSONObject json = new JSONObject(str);
                aliasMap.clear();
                Iterator<String> keys = json.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    aliasMap.put(key, json.getString(key));
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void showAboutDialog() {
        String capabilities = getString(R.string.about_capabilities);
        new AlertDialog.Builder(this)
                .setTitle(R.string.about_title)
                .setMessage(Html.fromHtml(capabilities, Html.FROM_HTML_MODE_COMPACT))
                .setPositiveButton(R.string.close, null)
                .show();
    }

    private void showUpdateBanner() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.update_title)
                .setMessage(R.string.update_message)
                .setPositiveButton(R.string.ok, null)
                .show();
    }

    private void showWelcomeDialog() {
        String message = getString(R.string.welcome_message);
        new AlertDialog.Builder(this)
                .setTitle(R.string.welcome_title)
                .setMessage(Html.fromHtml(message, Html.FROM_HTML_MODE_COMPACT))
                .setPositiveButton(R.string.btn_got_it, null)
                .show();
    }

    private void ensureReportDirectory() {
        File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "CDR_Reports");
        if (!dir.exists()) dir.mkdirs();
    }

    private void showTimelineOptionDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Timeline Analysis")
                .setMessage("Analyze for a particular timeline?")
                .setPositiveButton("Yes", (dialog, which) -> {
                    filterStartCal = Calendar.getInstance();
                    filterEndCal = Calendar.getInstance();
                    showStartDateTimePicker();
                })
                .setNegativeButton("No", (dialog, which) -> {
                    filterStartCal = null;
                    filterEndCal = null;
                    runPythonEngine();
                })
                .show();
    }

    private void showStartDateTimePicker() {
        new DatePickerDialog(this, (view, year, month, dayOfMonth) -> {
            filterStartCal.set(Calendar.YEAR, year);
            filterStartCal.set(Calendar.MONTH, month);
            filterStartCal.set(Calendar.DAY_OF_MONTH, dayOfMonth);
            
            new TimePickerDialog(this, (view1, hourOfDay, minute) -> {
                filterStartCal.set(Calendar.HOUR_OF_DAY, hourOfDay);
                filterStartCal.set(Calendar.MINUTE, minute);
                filterStartCal.set(Calendar.SECOND, 0);
                showEndDateTimePicker();
            }, filterStartCal.get(Calendar.HOUR_OF_DAY), filterStartCal.get(Calendar.MINUTE), true).show();
            
        }, filterStartCal.get(Calendar.YEAR), filterStartCal.get(Calendar.MONTH), filterStartCal.get(Calendar.DAY_OF_MONTH)).show();
    }

    private void showEndDateTimePicker() {
        new DatePickerDialog(this, (view, year, month, dayOfMonth) -> {
            filterEndCal.set(Calendar.YEAR, year);
            filterEndCal.set(Calendar.MONTH, month);
            filterEndCal.set(Calendar.DAY_OF_MONTH, dayOfMonth);
            
            new TimePickerDialog(this, (view1, hourOfDay, minute) -> {
                filterEndCal.set(Calendar.HOUR_OF_DAY, hourOfDay);
                filterEndCal.set(Calendar.MINUTE, minute);
                filterEndCal.set(Calendar.SECOND, 59);
                runPythonEngine();
            }, filterEndCal.get(Calendar.HOUR_OF_DAY), filterEndCal.get(Calendar.MINUTE), true).show();
            
        }, filterEndCal.get(Calendar.YEAR), filterEndCal.get(Calendar.MONTH), filterEndCal.get(Calendar.DAY_OF_MONTH)).show();
    }

    private void runPythonEngine() {
        statusText.setText(R.string.status_processing);
        loadingContainer.setVisibility(View.VISIBLE);
        swipeRefreshLayout.setEnabled(false);
        resultsContainer.setVisibility(View.GONE);

        Animation complexAnim = AnimationUtils.loadAnimation(this, R.anim.complex_loader);
        complexAnim.setAnimationListener(new Animation.AnimationListener() {
            @Override public void onAnimationStart(Animation animation) {}
            @Override public void onAnimationRepeat(Animation animation) {}
            @Override
            public void onAnimationEnd(Animation animation) {
                if (loadingContainer.getVisibility() == View.VISIBLE) {
                    loadingLogo.startAnimation(complexAnim);
                    rippleEffect.startAnimation(complexAnim);
                }
            }
        });
        loadingLogo.startAnimation(complexAnim);
        rippleEffect.startAnimation(complexAnim);

        new Thread(() -> {
            try {
                Python py = Python.getInstance();
                PyObject pyModule = py.getModule("index");
                String location = locationInput.getText().toString();
                
                File reportDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "CDR_Reports");
                if (!reportDir.exists()) {
                    if (!reportDir.mkdirs()) {
                        runOnUiThread(() -> statusText.setText("Error: Could not create Documents/CDR_Reports"));
                    }
                }
                String outputDir = reportDir.getAbsolutePath();

                String startTs = null;
                String endTs = null;
                if (filterStartCal != null && filterEndCal != null) {
                    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
                    startTs = sdf.format(filterStartCal.getTime());
                    endTs = sdf.format(filterEndCal.getTime());
                }

                String[] pathsArray = selectedFilePaths.toArray(new String[0]);
                PyObject result = pyModule.callAttr("process_cdr_data", pathsArray, location, outputDir, startTs, endTs);
                Map<PyObject, PyObject> resultMap = result.asMap();
                String status = resultMap.get(py.getBuiltins().get("str").call("status")).toString();

                runOnUiThread(() -> {
                    loadingLogo.clearAnimation();
                    rippleEffect.clearAnimation();
                    loadingContainer.setVisibility(View.GONE);
                    swipeRefreshLayout.setEnabled(mainScrollView.getScrollY() == 0);
                    if ("success".equals(status)) {
                        populateResults(resultMap);
                        // Silent Background Pre-processing for Same Location Analysis
                        startSilentSameLocationAnalysis();
                    } else {
                        statusText.setText(getString(R.string.error_prefix, resultMap.get(py.getBuiltins().get("str").call("message")).toString()));
                        statusText.setTextColor(Color.RED);
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    loadingLogo.clearAnimation();
                    rippleEffect.clearAnimation();
                    loadingContainer.setVisibility(View.GONE);
                    swipeRefreshLayout.setEnabled(mainScrollView.getScrollY() == 0);
                    statusText.setText(getString(R.string.engine_failure, e.getMessage()));
                });
            }
        }).start();
    }

    private void populateResults(Map<PyObject, PyObject> result) {
        try {
            Python py = Python.getInstance();
            lastGeneratedReportPath = result.get(py.getBuiltins().get("str").call("output_path")).toString();
            Map<PyObject, PyObject> metrics = result.get(py.getBuiltins().get("str").call("metrics")).asMap();
            
            lastPreviewJson = metrics.get(py.getBuiltins().get("str").call("preview_rows_json")).toString();

            List<PyObject> topBList = metrics.get(py.getBuiltins().get("str").call("top_b_parties")).asList();
            StringBuilder topBHtml = new StringBuilder(), topBPlain = new StringBuilder();
            SimpleDateFormat inputFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
            SimpleDateFormat outputFormat = new SimpleDateFormat("ddHHmm MMM yy", Locale.US);

            for (PyObject item : topBList) {
                Map<PyObject, PyObject> itemMap = item.asMap();
                String bParty = itemMap.get(py.getBuiltins().get("str").call("b_party")).toString();
                String freq = itemMap.get(py.getBuiltins().get("str").call("frequency")).toString();
                String lastCalled = itemMap.get(py.getBuiltins().get("str").call("last_called")).toString(), tinyDate = lastCalled;
                try {
                    Date date = inputFormat.parse(lastCalled);
                    if (date != null) tinyDate = outputFormat.format(date);
                } catch (Exception e) {
                    if (lastCalled.length() >= 10) tinyDate = lastCalled.substring(5, 10);
                }
                String line = tinyDate + " | " + freq + " | " + bParty;
                topBHtml.append(line).append("<br/>");
                topBPlain.append(line).append("\n");
            }

            String rawCommonB = metrics.get(py.getBuiltins().get("str").call("common_b_parties")).toString();
            String rawNightStays = metrics.get(py.getBuiltins().get("str").call("night_stays")).toString();

            summaryAParties.setText(Html.fromHtml(getString(R.string.summary_a_parties, metrics.get(py.getBuiltins().get("str").call("a_parties"))), Html.FROM_HTML_MODE_COMPACT));
            summaryTopThree.setText(Html.fromHtml(getString(R.string.summary_top_b_parties, topBHtml.toString()), Html.FROM_HTML_MODE_COMPACT));

            StringBuilder nightStaysHtml = new StringBuilder(getString(R.string.summary_night_stays_title));
            StringBuilder nightStaysPlain = new StringBuilder(getString(R.string.summary_night_stays_plain_title));
            if (rawNightStays.contains(" | ")) {
                String[] stays = rawNightStays.split(" \\| ");
                char listChar = 'a';
                for (String stay : stays) {
                    nightStaysHtml.append(listChar).append(". ").append(stay).append("<br>");
                    nightStaysPlain.append(listChar).append(". ").append(stay).append("\n");
                    listChar++;
                }
            } else {
                nightStaysHtml.append(rawNightStays);
                nightStaysPlain.append(rawNightStays);
            }
            summaryNightStays.setText(Html.fromHtml(nightStaysHtml.toString(), Html.FROM_HTML_MODE_COMPACT));
            summaryCommonBParties.setText(Html.fromHtml(getString(R.string.summary_common_b_parties, rawCommonB), Html.FROM_HTML_MODE_COMPACT));

            lastSummaryHtml = "<b>A-Parties:</b> " + metrics.get(py.getBuiltins().get("str").call("a_parties")).toString() + "<br/><br/>" +
                              "<b>Top B-Parties:</b><br/>" + topBHtml.toString() + "<br/>" +
                              "<b>Night Stays:</b><br/>" + nightStaysHtml.toString() + "<br/>" +
                              "<b>Common Contacts:</b> " + rawCommonB;

            summaryCardContent.setOnClickListener(v -> {
                String aParties = metrics.get(py.getBuiltins().get("str").call("a_parties")).toString();
                String commonB = (!"None".equalsIgnoreCase(rawCommonB) && !"N/A (Single File Uploaded)".equalsIgnoreCase(rawCommonB)) ? rawCommonB.replace(", ", ",\n") : rawCommonB;
                String fullSummary = "🎯 A Parties:\n" + aParties + "\n\n🔥 Top Bps:\n" + topBPlain + "\n\n🌙 " + nightStaysPlain + "\n🔗 Common Bps:\n" + commonB;
                ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.summary_clipboard_label), fullSummary));
                shareText(getString(R.string.share_summary_title), fullSummary);
                Toast.makeText(this, R.string.summary_share_toast, Toast.LENGTH_SHORT).show();
            });

            String imei = metrics.get(py.getBuiltins().get("str").call("imei_swappers")).toString();
            badgeImei.setText(imei.contains(getString(R.string.imei_swappers_prefix)) ? "🚨 " + imei : "🛡️ " + imei);
            String multiSim = metrics.get(py.getBuiltins().get("str").call("multi_sim")).toString();
            badgeMultiSim.setText(multiSim.contains(getString(R.string.multi_sim_prefix)) ? "⚠️ " + multiSim : "🛡️ " + multiSim);
            badgeNightRoutine.setText("🕒 " + metrics.get(py.getBuiltins().get("str").call("night_routine")));
            lastGraphData = metrics.get(py.getBuiltins().get("str").call("graph_data")).toString();

            btnLinkAnalysis.setVisibility(View.VISIBLE);
            btnSameLocation.setVisibility(View.VISIBLE);

            // Render Temporal Heatmaps for each A-party
            heatmapContainer.removeAllViews();
            Map<PyObject, PyObject> hourlyActivity = metrics.get(py.getBuiltins().get("str").call("hourly_activity")).asMap();
            for (Map.Entry<PyObject, PyObject> entry : hourlyActivity.entrySet()) {
                String aParty = entry.getKey().toString();
                Map<PyObject, PyObject> hourDist = entry.getValue().asMap();
                renderTemporalHeatmap(aParty, hourDist);
            }

            resultsContainer.setVisibility(View.VISIBLE);
            statusText.setText(R.string.report_compiled_success);
            statusText.setTextColor(Color.parseColor("#2C3E50"));
        } catch (Exception e) {
            statusText.setText(getString(R.string.display_error, e.getMessage()));
        }
    }

    private void startSilentSameLocationAnalysis() {
        if (selectedFilePaths.isEmpty()) return;
        
        isAnalysisReady = false;
        isAnalysisRunning = true;
        currentAnalysisProgress = 0;
        lastSameLocationJson = null;

        backgroundAnalysisThread = new Thread(() -> {
            try {
                Python py = Python.getInstance();
                PyObject pyModule = py.getModule("index");
                String[] pathsArray = selectedFilePaths.toArray(new String[0]);
                
                // Define a progress callback for Python
                ProgressListener listener = progress -> {
                    currentAnalysisProgress = progress;
                    runOnUiThread(() -> {
                        if (locProgressContainer.getVisibility() == View.VISIBLE) {
                            locProgressBar.setProgress(progress);
                            locProgressText.setText(progress + "%");
                            
                            // Ensure animation is running if visible
                            if (locLoadingIcon.getAnimation() == null) {
                                Animation pulse = AnimationUtils.loadAnimation(this, R.anim.pulse);
                                locLoadingIcon.startAnimation(pulse);
                            }
                        }
                    });
                };

                PyObject locResult = pyModule.callAttr("same_location_analysis", pathsArray, listener);
                Map<PyObject, PyObject> locMap = locResult.asMap();
                
                PyObject pyStatus = locMap.get(py.getBuiltins().get("str").call("status"));
                String status = (pyStatus != null) ? pyStatus.toString() : "error";
                
                PyObject pyData = locMap.get(py.getBuiltins().get("str").call("data"));
                String dataJson = (pyData != null) ? pyData.toString() : "[]";
                
                PyObject pySummary = locMap.get(py.getBuiltins().get("str").call("summary"));
                lastSpatialSummary = (pySummary != null) ? pySummary.toString() : null;

                if ("success".equals(status) && dataJson.length() > 5) {
                    File tempFile = new File(getCacheDir(), "same_loc_results.json");
                    FileOutputStream fos = new FileOutputStream(tempFile);
                    fos.write(dataJson.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    fos.close();
                    lastSameLocationJson = tempFile.getAbsolutePath();
                    isAnalysisReady = true;
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                isAnalysisRunning = false;
                runOnUiThread(() -> {
                    locLoadingIcon.clearAnimation();
                    if (isAnalysisReady) {
                        locProgressBar.setProgress(100);
                        locProgressText.setText("100%");
                        Toast.makeText(MainActivity.this, "📍 Movement Analysis Ready!", Toast.LENGTH_SHORT).show();
                        // Change button look to indicate readiness if you like
                        btnSameLocation.setText("📍 View Movement Analysis (Ready)");
                    } else {
                        locProgressContainer.setVisibility(View.GONE);
                    }
                });
            }
        });
        backgroundAnalysisThread.start();
    }

    // Interface for Chaquopy progress reporting
    public interface ProgressListener {
        void onProgress(int progress);
    }

    private void runSameLocationAnalysis() {
        if (isAnalysisReady && lastSameLocationJson != null) {
            // Standard launch
            btnSameLocation.setEnabled(false);
            new Handler(Looper.getMainLooper()).postDelayed(() -> btnSameLocation.setEnabled(true), 1000);

            Intent intent = new Intent(this, SameLocationActivity.class);
            intent.putExtra("loc_json_path", lastSameLocationJson);
            intent.putStringArrayListExtra("file_paths", new ArrayList<>(selectedFilePaths));
            startActivity(intent);
            return;
        }

        if (isAnalysisRunning) {
            // If already waiting, don't start another wait thread
            if (locProgressContainer.getVisibility() == View.VISIBLE) {
                Toast.makeText(this, "Still calculating movement overlaps...", Toast.LENGTH_SHORT).show();
                return;
            }

            // Show the progress container but DON'T auto-launch activity when done.
            // This prevents "UI Hijacking" if the user is doing something else.
            locProgressContainer.setVisibility(View.VISIBLE);
            locProgressBar.setProgress(currentAnalysisProgress);
            locProgressText.setText(currentAnalysisProgress + "%");
            
            Animation pulse = AnimationUtils.loadAnimation(this, R.anim.pulse);
            locLoadingIcon.startAnimation(pulse);
            
            Toast.makeText(this, "Movement analysis is running in background. Button will activate when ready.", Toast.LENGTH_LONG).show();
            return;
        }

        if (selectedFilePaths.size() < 2) {
            Toast.makeText(this, "Requires at least 2 CDRs for movement correlation.", Toast.LENGTH_SHORT).show();
            return;
        }
        
        // If not running and not ready, start it
        startSilentSameLocationAnalysis();
        locProgressContainer.setVisibility(View.VISIBLE);
    }

    private void performCdrSearch(String query) {
        statusText.setText(R.string.status_searching);
        loadingContainer.setVisibility(View.VISIBLE);
        swipeRefreshLayout.setEnabled(false);
        Animation complexAnim = AnimationUtils.loadAnimation(this, R.anim.complex_loader);
        complexAnim.setAnimationListener(new Animation.AnimationListener() {
            @Override public void onAnimationStart(Animation animation) {}
            @Override public void onAnimationRepeat(Animation animation) {}
            @Override
            public void onAnimationEnd(Animation animation) {
                if (loadingContainer.getVisibility() == View.VISIBLE) {
                    loadingLogo.startAnimation(complexAnim);
                    rippleEffect.startAnimation(complexAnim);
                }
            }
        });
        loadingLogo.startAnimation(complexAnim);
        rippleEffect.startAnimation(complexAnim);

        new Thread(() -> {
            try {
                Python py = Python.getInstance();
                PyObject result = py.getModule("index").callAttr("search_cdr_data", selectedFilePaths.toArray(new String[0]), query);
                Map<PyObject, PyObject> resultMap = result.asMap();
                String status = resultMap.get(py.getBuiltins().get("str").call("status")).toString();
                runOnUiThread(() -> {
                    loadingLogo.clearAnimation();
                    rippleEffect.clearAnimation();
                    loadingContainer.setVisibility(View.GONE);
                    swipeRefreshLayout.setEnabled(mainScrollView.getScrollY() == 0);
                    if ("success".equals(status)) {
                        showSearchResultsDialog(resultMap.get(py.getBuiltins().get("str").call("summary_html")).toString());
                    } else {
                        Toast.makeText(this, getString(R.string.search_error, resultMap.get(py.getBuiltins().get("str").call("message"))), Toast.LENGTH_LONG).show();
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    loadingLogo.clearAnimation();
                    rippleEffect.clearAnimation();
                    loadingContainer.setVisibility(View.GONE);
                    swipeRefreshLayout.setEnabled(mainScrollView.getScrollY() == 0);
                    Toast.makeText(this, getString(R.string.search_engine_failure, e.getMessage()), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private void showSearchResultsDialog(String summaryHtml) {
        TextView textView = new TextView(this);
        textView.setText(Html.fromHtml(summaryHtml, Html.FROM_HTML_MODE_COMPACT));
        textView.setPadding(60, 48, 60, 48);
        textView.setTextSize(14);
        textView.setLineSpacing(0, 1.2f);
        
        android.util.TypedValue typedValue = new android.util.TypedValue();
        getTheme().resolveAttribute(R.attr.primaryTextColor, typedValue, true);
        textView.setTextColor(typedValue.data);
        
        textView.setOnClickListener(v -> {
            String plainText = textView.getText().toString();
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.clipboard_label), plainText));
            shareText(getString(R.string.share_results_title), plainText);
            Toast.makeText(this, R.string.share_results_toast, Toast.LENGTH_SHORT).show();
        });

        androidx.core.widget.NestedScrollView scrollView = new androidx.core.widget.NestedScrollView(this);
        scrollView.addView(textView);
        
        new AlertDialog.Builder(this)
                .setTitle(R.string.search_results_title)
                .setView(scrollView)
                .setPositiveButton(R.string.close, null)
                .show();
    }

    private void openExcelFile(String path) {
        try {
            File file = new File(path);
            if (!file.exists()) {
                Toast.makeText(this, "File does not exist: " + path, Toast.LENGTH_LONG).show();
                return;
            }
            if (file.length() == 0) {
                Toast.makeText(this, "File is empty: " + path, Toast.LENGTH_LONG).show();
                return;
            }
            
            Uri uri = androidx.core.content.FileProvider.getUriForFile(this, getPackageName() + ".provider", file);
            
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            
            // Check if there is an app to handle the intent
            if (intent.resolveActivity(getPackageManager()) != null) {
                startActivity(intent);
            } else {
                Toast.makeText(this, "No Excel viewer found. Please install one (e.g., Microsoft Excel or Google Sheets).", Toast.LENGTH_LONG).show();
                // Copy path to clipboard so user can find it manually
                ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                clipboard.setPrimaryClip(ClipData.newPlainText("Report Path", path));
                statusText.setText("File saved at: " + path + " (Path copied to clipboard)");
            }
        } catch (Exception e) {
            statusText.setText("Error opening file: " + e.getMessage());
            Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
            e.printStackTrace();
        }
    }

    private void showPeekDialog() {
        if (lastPreviewJson == null || lastPreviewJson.length() < 5) {
            Toast.makeText(this, R.string.no_data_to_preview, Toast.LENGTH_SHORT).show();
            return;
        }

        View dialogView = getLayoutInflater().inflate(R.layout.dialog_peek, null);
        TableLayout tableLayout = dialogView.findViewById(R.id.peekTable);
        View peekScroll = dialogView.findViewById(R.id.peekScroll);
        View peekLoading = dialogView.findViewById(R.id.peekLoadingContainer);
        Button btnClose = dialogView.findViewById(R.id.btnClosePeek);
        ImageButton btnCopy = dialogView.findViewById(R.id.btnCopyPeek);

        btnCopy.setOnClickListener(v -> {
            try {
                JSONArray array = new JSONArray(lastPreviewJson);
                StringBuilder sb = new StringBuilder("Dt | Ap | Bp | Freq\n---------------------\n");
                for (int i = 0; i < array.length(); i++) {
                    JSONObject row = array.getJSONObject(i);
                    sb.append(row.optString("dt")).append(" | ")
                      .append(row.optString("ap")).append(" | ")
                      .append(row.optString("bp")).append(" | ")
                      .append(row.optString("freq")).append("\n");
                }
                shareText(getString(R.string.peek_share_title), sb.toString());
            } catch (Exception e) {
                Toast.makeText(this, "Copy failed", Toast.LENGTH_SHORT).show();
            }
        });

        // Add Header Row
        TableRow header = new TableRow(this);
        header.setBackgroundColor(Color.parseColor("#2C3E50"));
        header.addView(createHeaderCell("Time"));
        header.addView(createHeaderCell("Ap"));
        header.addView(createHeaderCell("Bp"));
        header.addView(createHeaderCell("Freq"));
        tableLayout.addView(header);

        AlertDialog dialog = new AlertDialog.Builder(this).setView(dialogView).create();
        btnClose.setOnClickListener(v1 -> dialog.dismiss());
        dialog.show();

        final Handler handler = new Handler(Looper.getMainLooper());

        new Thread(() -> {
            try {
                // Forced 2.5 second loading state as requested
                Thread.sleep(2500);

                JSONArray array = new JSONArray(lastPreviewJson);
                final int totalRows = array.length();
                final int chunkSize = 50;

                for (int i = 0; i < totalRows; i += chunkSize) {
                    if (!dialog.isShowing()) break;

                    final int start = i;
                    final int end = Math.min(i + chunkSize, totalRows);
                    final List<TableRow> batch = new ArrayList<>();

                    for (int j = start; j < end; j++) {
                        JSONObject rowData = array.getJSONObject(j);
                        TableRow tableRow = new TableRow(MainActivity.this);
                        tableRow.setBackgroundColor(j % 2 == 0 ? Color.WHITE : Color.parseColor("#F5F5F5"));
                        tableRow.addView(createTableCell(rowData.optString("dt")));
                        tableRow.addView(createTableCell(rowData.optString("ap")));
                        tableRow.addView(createTableCell(rowData.optString("bp")));
                        tableRow.addView(createTableCell(rowData.optString("freq")));
                        batch.add(tableRow);
                    }

                    handler.post(() -> {
                        if (start == 0) {
                            peekLoading.setVisibility(View.GONE);
                            peekScroll.setVisibility(View.VISIBLE);
                        }
                        for (TableRow row : batch) tableLayout.addView(row);
                    });

                    // Yield slightly to keep system smooth
                    Thread.sleep(25);
                }
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (dialog.isShowing()) {
                        peekLoading.setVisibility(View.GONE);
                        Toast.makeText(this, "Peek error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }).start();
    }

    private TextView createHeaderCell(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setPadding(16, 16, 16, 16);
        tv.setGravity(android.view.Gravity.CENTER);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(13);
        tv.setTypeface(null, android.graphics.Typeface.BOLD);
        return tv;
    }

    private TextView createTableCell(String text) {
        TextView textView = new TextView(this);
        textView.setText(text);
        textView.setPadding(16, 16, 16, 16);
        textView.setGravity(android.view.Gravity.CENTER);
        textView.setTextSize(12);
        textView.setTextColor(Color.BLACK);

        return textView;
    }

    private void shareText(String title, String text) {
        Intent sendIntent = new Intent(Intent.ACTION_SEND);
        sendIntent.putExtra(Intent.EXTRA_TEXT, text);
        sendIntent.setType("text/plain");
        startActivity(Intent.createChooser(sendIntent, title));
    }

    private void refreshAppState() {
        if (backgroundAnalysisThread != null && backgroundAnalysisThread.isAlive()) {
            backgroundAnalysisThread.interrupt();
        }
        isAnalysisReady = false;
        isAnalysisRunning = false;
        currentAnalysisProgress = 0;
        if (locProgressContainer != null) locProgressContainer.setVisibility(View.GONE);
        if (btnSameLocation != null) btnSameLocation.setText("📍 Do a same location analysis");

        locationInput.setText(""); searchCdrInput.setText(""); selectedFilePaths.clear();
        lastGeneratedReportPath = null; lastPreviewJson = null; lastGraphData = null;
        lastSummaryHtml = null; lastSpatialSummary = null;
        resultsContainer.setVisibility(View.GONE); loadingContainer.setVisibility(View.GONE);
        btnLinkAnalysis.setVisibility(View.GONE); btnSameLocation.setVisibility(View.GONE);
        loadingLogo.clearAnimation(); rippleEffect.clearAnimation();
        heatmapContainer.removeAllViews();
        statusText.setText(R.string.status_select_files);
        android.util.TypedValue typedValue = new android.util.TypedValue();
        getTheme().resolveAttribute(R.attr.secondaryTextColor, typedValue, true);
        statusText.setTextColor(typedValue.data);
    }

    private void cleanCache() {
        File cacheDir = getCacheDir();
        if (cacheDir.exists()) {
            File[] files = cacheDir.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f.getName().startsWith("temp_cdr_") || 
                        f.getName().startsWith("crop_") || 
                        f.getName().equals("same_loc_results.json") ||
                        f.getName().equals("link_graph_data.json")) {
                        f.delete();
                    }
                }
            }
        }
    }

    private void renderTemporalHeatmap(String aParty, Map<PyObject, PyObject> hourDist) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(0, 10, 0, 10);

        TextView title = new TextView(this);
        title.setText("⏰ Hourly Activity: " + aParty);
        title.setTextSize(14);
        title.setTextColor(Color.parseColor("#2C3E50"));
        title.setPadding(0, 0, 0, 8);
        layout.addView(title);

        LinearLayout barContainer = new LinearLayout(this);
        barContainer.setOrientation(LinearLayout.HORIZONTAL);
        barContainer.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 40));

        int maxVal = 0;
        for (PyObject val : hourDist.values()) maxVal = Math.max(maxVal, val.toInt());

        for (int i = 0; i < 24; i++) {
            View segment = new View(this);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
            params.setMargins(1, 0, 1, 0);
            segment.setLayoutParams(params);

            int count = hourDist.get(Python.getInstance().getBuiltins().get("str").call(String.valueOf(i))).toInt();
            if (count == 0) {
                segment.setBackgroundColor(Color.parseColor("#EDF2F7"));
            } else {
                float ratio = (float) count / maxVal;
                int color;
                if (ratio < 0.5f) {
                    // Interpolate Green (#27AE60) to Yellow (#F1C40F)
                    color = interpolateColor(0x27AE60, 0xF1C40F, ratio * 2);
                } else {
                    // Interpolate Yellow (#F1C40F) to Red (#E74C3C)
                    color = interpolateColor(0xF1C40F, 0xE74C3C, (ratio - 0.5f) * 2);
                }
                segment.setBackgroundColor(color);
            }
            barContainer.addView(segment);
        }
        layout.addView(barContainer);

        // Accurate labels: 00, 06, 12, 18
        LinearLayout labelLayout = new LinearLayout(this);
        labelLayout.setOrientation(LinearLayout.HORIZONTAL);
        labelLayout.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        for (int i = 0; i < 24; i++) {
            TextView label = new TextView(this);
            label.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            label.setTextSize(9);
            label.setTextColor(Color.GRAY);
            label.setGravity(android.view.Gravity.START);

            if (i == 0) label.setText("00");
            else if (i == 6) label.setText("06");
            else if (i == 12) label.setText("12");
            else if (i == 18) label.setText("18");
            
            labelLayout.addView(label);
        }
        layout.addView(labelLayout);

        heatmapContainer.addView(layout);
    }

    private int interpolateColor(int color1, int color2, float fraction) {
        int r1 = (color1 >> 16) & 0xFF;
        int g1 = (color1 >> 8) & 0xFF;
        int b1 = color1 & 0xFF;
        int r2 = (color2 >> 16) & 0xFF;
        int g2 = (color2 >> 8) & 0xFF;
        int b2 = color2 & 0xFF;
        int r = (int) (r1 + (r2 - r1) * fraction);
        int g = (int) (g1 + (g2 - g1) * fraction);
        int b = (int) (b1 + (b2 - b1) * fraction);
        return Color.rgb(r, g, b);
    }

    private void hideKeyboard(View view) {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
    }

    private String copyUriToCache(Uri uri) {
        try {
            String extension = ".xlsx";
            String type = getContentResolver().getType(uri);
            if (type != null && type.equals("application/pdf")) {
                extension = ".pdf";
            }
            
            File tempFile = new File(getCacheDir(), "temp_cdr_" + System.currentTimeMillis() + extension);
            InputStream inputStream = getContentResolver().openInputStream(uri);
            if (inputStream == null) return null;
            FileOutputStream outputStream = new FileOutputStream(tempFile);
            byte[] buffer = new byte[8192]; int length;
            while ((length = inputStream.read(buffer)) > 0) outputStream.write(buffer, 0, length);
            outputStream.flush(); outputStream.close(); inputStream.close();
            return tempFile.getAbsolutePath();
        } catch (Exception e) { return null; }
    }
}
