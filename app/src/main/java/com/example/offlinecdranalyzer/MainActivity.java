package com.example.offlinecdranalyzer;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.text.Html;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.PopupMenu;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;
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
import java.util.ArrayList;
import java.util.List;
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
    private TextView badgeImei, badgeMultiSim, badgeNightRoutine;
    private Button btnOpenReport, btnTakeAPeek;
    private ImageButton btnSearchCdr;

    private List<String> selectedFilePaths = new ArrayList<>();
    private String lastGeneratedReportPath = null;
    private List<Map<PyObject, PyObject>> lastPreviewRows = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Initialize Chaquopy Python Runtime
        if (!Python.isStarted()) {
            Python.start(new AndroidPlatform(this));
        }

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

        badgeImei = findViewById(R.id.badgeImei);
        badgeMultiSim = findViewById(R.id.badgeMultiSim);
        badgeNightRoutine = findViewById(R.id.badgeNightRoutine);

        Button btnSelectFiles = findViewById(R.id.btnSelectFiles);
        Button btnProcess = findViewById(R.id.btnProcess);
        Button btnRefresh = findViewById(R.id.btnRefresh);
        btnOpenReport = findViewById(R.id.btnOpenReport);
        btnTakeAPeek = findViewById(R.id.btnTakeAPeek);
        ImageButton btnMenu = findViewById(R.id.btnMenu);

        // Custom Popup Menu logic
        btnMenu.setOnClickListener(v -> {
            PopupMenu popup = new PopupMenu(MainActivity.this, v);
            popup.getMenuInflater().inflate(R.menu.main_menu, popup.getMenu());
            popup.setOnMenuItemClickListener(item -> {
                if (item.getItemId() == R.id.action_about) {
                    showAboutDialog();
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

        btnSearchCdr.setOnClickListener(v -> {
            hideKeyboard(v);
            String query = searchCdrInput.getText().toString().trim();
            if (query.isEmpty()) {
                Toast.makeText(this, "Please enter search terms", Toast.LENGTH_SHORT).show();
                return;
            }
            performCdrSearch(query);
        });

        // Pull-to-refresh logic
        swipeRefreshLayout.setEnabled(true);
        swipeRefreshLayout.setOnRefreshListener(() -> {
            refreshAppState();
            swipeRefreshLayout.setRefreshing(false);
        });

        // Fix: Only allow SwipeRefresh when ScrollView is at the very top
        mainScrollView.getViewTreeObserver().addOnScrollChangedListener(() -> {
            swipeRefreshLayout.setEnabled(mainScrollView.getScrollY() == 0);
        });

        btnRefresh.setOnClickListener(v -> {
            hideKeyboard(v);
            refreshAppState();
        });

        // Native Android File Picker Registry
        ActivityResultLauncher<Intent> filePickerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                        selectedFilePaths.clear();
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
                        statusText.setText(selectedFilePaths.size() + " spreadsheet(s) staged successfully.");
                    }
                }
        );

        btnSelectFiles.setOnClickListener(v -> {
            hideKeyboard(v);
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            filePickerLauncher.launch(intent);
        });

        btnProcess.setOnClickListener(v -> {
            hideKeyboard(v);
            if (selectedFilePaths.isEmpty()) {
                statusText.setText("Error: Please select files first.");
                return;
            }
            runPythonEngine();
        });

        btnOpenReport.setOnClickListener(v -> {
            if (lastGeneratedReportPath != null) {
                openExcelFile(lastGeneratedReportPath);
            }
        });
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
        }
        return super.onOptionsItemSelected(item);
    }

    private void showAboutDialog() {
        String capabilities = "• <b>Cross-File Correlation:</b> Detects common contacts across multiple CDR sheets.<br>" +
                "• <b>Hardware Tracking:</b> Identifies IMEI swapping and multi-SIM usage patterns.<br>" +
                "• <b>Geospatial Intelligence:</b> Analyzes location patterns for night stays.<br>" +
                "• <b>Temporal Analysis:</b> Profiles deep-night operational windows.<br>" +
                "• <b>Automated Reporting:</b> Generates styled Excel reports with day/night color coding.<br>" +
                "• <b>Privacy First:</b> All processing is done locally on your device.";

        new AlertDialog.Builder(this)
                .setTitle("About Me & App Capabilities")
                .setMessage(Html.fromHtml(capabilities, Html.FROM_HTML_MODE_COMPACT))
                .setPositiveButton("Close", null)
                .show();
    }

    private void showWelcomeDialog() {
        String message = "<b>ক।</b> শুধু র‍্যাবের সিডিআর গুলোর উপর কাজ করা যায় যেখানে ১৩ টি কলাম বিদ্যমান।<br><br>" +
                "<b>খ।</b> একসাথে একাধিক সিডিআর এর উপর কাজ করা যায়।<br><br>" +
                "<b>গ।</b> আউটপুট একটি এক্সেল যাতে দিনের সময় ও রাতের সময় হাইলাইট করা আছে।<br><br>" +
                "<b>ঘ।</b> বি পার্টি লোকেশন এর কয়েক অক্ষর লিখলেই হবে যেমনঃ Com or Comilla, Dhak or Dhaka";

        new AlertDialog.Builder(this)
                .setTitle("👋 সিডিআর অ্যানালাইজার ড্যাশবোর্ড")
                .setMessage(Html.fromHtml(message, Html.FROM_HTML_MODE_COMPACT))
                .setPositiveButton("✅ বুঝেছি (Got It)", null)
                .show();
    }

    private void runPythonEngine() {
        statusText.setText("Processing calculation matrices...");
        loadingContainer.setVisibility(View.VISIBLE);
        resultsContainer.setVisibility(View.GONE);

        // Start Complex Animation
        Animation complexAnim = AnimationUtils.loadAnimation(this, R.anim.complex_loader);
        complexAnim.setAnimationListener(new Animation.AnimationListener() {
            @Override public void onAnimationStart(Animation animation) {}
            @Override public void onAnimationRepeat(Animation animation) {}
            @Override
            public void onAnimationEnd(Animation animation) {
                // Repeat if still loading
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
                String outputDir = getExternalFilesDir(null).getAbsolutePath();
                String[] pathsArray = selectedFilePaths.toArray(new String[0]);

                PyObject result = pyModule.callAttr("process_cdr_data", pathsArray, location, outputDir);
                Map<PyObject, PyObject> resultMap = result.asMap();

                String status = resultMap.get(py.getBuiltins().get("str").call("status")).toString();

                runOnUiThread(() -> {
                    loadingLogo.clearAnimation();
                    rippleEffect.clearAnimation();
                    loadingContainer.setVisibility(View.GONE);
                    if ("success".equals(status)) {
                        populateResults(resultMap);
                    } else {
                        statusText.setText("Error: " + resultMap.get(py.getBuiltins().get("str").call("message")).toString());
                        statusText.setTextColor(Color.RED);
                    }
                });

            } catch (Exception e) {
                runOnUiThread(() -> {
                    loadingLogo.clearAnimation();
                    rippleEffect.clearAnimation();
                    loadingContainer.setVisibility(View.GONE);
                    statusText.setText("Engine failure: " + e.getMessage());
                });
            }
        }).start();
    }

    private void populateResults(Map<PyObject, PyObject> result) {
        try {
            Python py = Python.getInstance();
            lastGeneratedReportPath = result.get(py.getBuiltins().get("str").call("output_path")).toString();
            Map<PyObject, PyObject> metrics = result.get(py.getBuiltins().get("str").call("metrics")).asMap();

            // Store preview data
            lastPreviewRows = new ArrayList<>();
            List<PyObject> rows = metrics.get(py.getBuiltins().get("str").call("preview_rows")).asList();
            for (PyObject row : rows) {
                lastPreviewRows.add(row.asMap());
            }

            String rawTopTargets = metrics.get(py.getBuiltins().get("str").call("top_three")).toString();
            String rawCommonB = metrics.get(py.getBuiltins().get("str").call("common_b_parties")).toString();
            String rawNightStays = metrics.get(py.getBuiltins().get("str").call("night_stays")).toString();

            summaryAParties.setText(Html.fromHtml("🎯 <b>A Parties:</b> " + metrics.get(py.getBuiltins().get("str").call("a_parties")), Html.FROM_HTML_MODE_COMPACT));
            summaryTopThree.setText(Html.fromHtml("🔥 <b>Top B parties (Click to share):</b><br>" + rawTopTargets, Html.FROM_HTML_MODE_COMPACT));
            
            // Click to share logic for Top B parties
            summaryTopThree.setOnClickListener(v -> {
                String copyText = rawTopTargets.replace(", ", ",\n") + ",";
                shareText("Top B parties", copyText);
            });

            // Format Night Stays as a list
            StringBuilder nightStaysHtml = new StringBuilder("🌙 <b>Top 5 Night Stays:</b><br>");
            if (rawNightStays.contains(" | ")) {
                String[] stays = rawNightStays.split(" \\| ");
                char listChar = 'a';
                for (String stay : stays) {
                    nightStaysHtml.append(listChar).append(". ").append(stay).append("<br>");
                    listChar++;
                }
            } else {
                nightStaysHtml.append(rawNightStays);
            }
            summaryNightStays.setText(Html.fromHtml(nightStaysHtml.toString(), Html.FROM_HTML_MODE_COMPACT));

            summaryCommonBParties.setText(Html.fromHtml("🔗 <b>Common B Parties (Click to share):</b><br>" + rawCommonB, Html.FROM_HTML_MODE_COMPACT));

            // Click to share logic for Common B Parties
            summaryCommonBParties.setOnClickListener(v -> {
                if (!"None".equalsIgnoreCase(rawCommonB) && !"N/A (Single File Uploaded)".equalsIgnoreCase(rawCommonB)) {
                    String copyText = rawCommonB.replace(", ", ",\n") + ",";
                    shareText("Common B Parties", copyText);
                } else {
                    Toast.makeText(this, "No common B parties to share", Toast.LENGTH_SHORT).show();
                }
            });

            String imei = metrics.get(py.getBuiltins().get("str").call("imei_swappers")).toString();
            badgeImei.setText(imei.contains("IMEI Swappers:") ? "🚨 " + imei : "🛡️ " + imei);

            String multiSim = metrics.get(py.getBuiltins().get("str").call("multi_sim")).toString();
            badgeMultiSim.setText(multiSim.contains("Multi-SIM Burner Hardware:") ? "⚠️ " + multiSim : "🛡️ " + multiSim);

            badgeNightRoutine.setText("🕒 " + metrics.get(py.getBuiltins().get("str").call("night_routine")).toString());

            resultsContainer.setVisibility(View.VISIBLE);
            statusText.setText("Intelligence Report Compiled Successfully.");
            statusText.setTextColor(Color.parseColor("#2C3E50"));

        } catch (Exception e) {
            statusText.setText("Display Error: " + e.getMessage());
        }
    }

    private void performCdrSearch(String query) {
        statusText.setText("Searching in CDR database...");
        loadingContainer.setVisibility(View.VISIBLE);

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
                String[] pathsArray = selectedFilePaths.toArray(new String[0]);

                PyObject result = pyModule.callAttr("search_cdr_data", pathsArray, query);
                Map<PyObject, PyObject> resultMap = result.asMap();

                String status = resultMap.get(py.getBuiltins().get("str").call("status")).toString();

                runOnUiThread(() -> {
                    loadingLogo.clearAnimation();
                    rippleEffect.clearAnimation();
                    loadingContainer.setVisibility(View.GONE);
                    if ("success".equals(status)) {
                        String summaryHtml = resultMap.get(py.getBuiltins().get("str").call("summary_html")).toString();
                        showSearchResultsDialog(summaryHtml);
                    } else {
                        Toast.makeText(this, "Search Error: " + resultMap.get(py.getBuiltins().get("str").call("message")), Toast.LENGTH_LONG).show();
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    loadingLogo.clearAnimation();
                    rippleEffect.clearAnimation();
                    loadingContainer.setVisibility(View.GONE);
                    Toast.makeText(this, "Search Engine Failure: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private void showSearchResultsDialog(String summaryHtml) {
        // Create a custom view for the dialog to handle click listeners
        TextView textView = new TextView(this);
        textView.setText(Html.fromHtml(summaryHtml, Html.FROM_HTML_MODE_COMPACT));
        textView.setPadding(48, 40, 48, 40);
        textView.setTextSize(14);

        // Explicitly resolve primary text color from theme to avoid blue/invisible text
        android.util.TypedValue typedValue = new android.util.TypedValue();
        getTheme().resolveAttribute(R.attr.primaryTextColor, typedValue, true);
        textView.setTextColor(typedValue.data);

        // Click to Copy & Share entire result
        textView.setOnClickListener(v -> {
            String plainText = textView.getText().toString();
            
            // 1. Copy to clipboard
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText("CDR Search Result", plainText);
            clipboard.setPrimaryClip(clip);
            
            // 2. Open Share Sheet
            shareText("CDR Search Result", plainText);
            
            Toast.makeText(this, "Result copied and sharing opened", Toast.LENGTH_SHORT).show();
        });

        // Wrap in ScrollView in case results are long
        androidx.core.widget.NestedScrollView scrollView = new androidx.core.widget.NestedScrollView(this);
        scrollView.addView(textView);

        new AlertDialog.Builder(this)
                .setTitle("🔍 CDR Search Results (Tap to Share)")
                .setView(scrollView)
                .setPositiveButton("Close", null)
                .show();
    }

    private void openExcelFile(String path) {
        File file = new File(path);
        Uri uri = androidx.core.content.FileProvider.getUriForFile(this, getPackageName() + ".provider", file);
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(intent);
        } catch (Exception e) {
            statusText.setText("Could not open Excel app. File saved at: " + path);
        }
    }

    private void showPeekDialog() {
        if (lastPreviewRows == null || lastPreviewRows.isEmpty()) {
            Toast.makeText(this, "No data to preview", Toast.LENGTH_SHORT).show();
            return;
        }

        View dialogView = getLayoutInflater().inflate(R.layout.dialog_peek, null);
        TableLayout tableLayout = dialogView.findViewById(R.id.peekTable);
        Button btnClose = dialogView.findViewById(R.id.btnClosePeek);

        Python py = Python.getInstance();
        PyObject strDt = py.getBuiltins().get("str").call("dt");
        PyObject strBp = py.getBuiltins().get("str").call("bp");
        PyObject strFreq = py.getBuiltins().get("str").call("freq");
        PyObject strLoc = py.getBuiltins().get("str").call("loc");

        int rowCount = 0;
        for (Map<PyObject, PyObject> row : lastPreviewRows) {
            rowCount++;
            TableRow tableRow = new TableRow(this);
            tableRow.setBackgroundColor(Color.WHITE);
            
            // Add Dt
            tableRow.addView(createTableCell(row.get(strDt).toString()));
            // Add Bp
            tableRow.addView(createTableCell(row.get(strBp).toString()));
            // Add Freq
            tableRow.addView(createTableCell(row.get(strFreq).toString()));
            
            // Add Loc (Last 15 chars)
            String loc = row.get(strLoc).toString();
            if (loc.length() > 15) {
                loc = "..." + loc.substring(loc.length() - 15);
            }
            tableRow.addView(createTableCell(loc));

            tableLayout.addView(tableRow);
        }

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(dialogView)
                .create();

        btnClose.setOnClickListener(v1 -> dialog.dismiss());
        dialog.show();
    }

    private TextView createTableCell(String text) {
        TextView textView = new TextView(this);
        textView.setText(text);
        textView.setPadding(8, 8, 8, 8);
        textView.setGravity(android.view.Gravity.CENTER);
        textView.setTextColor(Color.BLACK);
        textView.setBackgroundResource(android.R.drawable.editbox_background_normal);
        return textView;
    }

    private void shareText(String title, String text) {
        Intent sendIntent = new Intent();
        sendIntent.setAction(Intent.ACTION_SEND);
        sendIntent.putExtra(Intent.EXTRA_TEXT, text);
        sendIntent.setType("text/plain");

        Intent shareIntent = Intent.createChooser(sendIntent, title);
        startActivity(shareIntent);
    }

    private void refreshAppState() {
        // Clear inputs
        locationInput.setText("");
        searchCdrInput.setText("");
        selectedFilePaths.clear();
        lastGeneratedReportPath = null;
        lastPreviewRows = null;

        // Reset UI visibility
        resultsContainer.setVisibility(View.GONE);
        loadingContainer.setVisibility(View.GONE);
        loadingLogo.clearAnimation();
        rippleEffect.clearAnimation();

        // Reset status text
        statusText.setText("Select files to begin.");
        android.util.TypedValue typedValue = new android.util.TypedValue();
        getTheme().resolveAttribute(R.attr.secondaryTextColor, typedValue, true);
        statusText.setTextColor(typedValue.data);

        // Optionally clear cache
        File cacheDir = getCacheDir();
        if (cacheDir.exists()) {
            File[] files = cacheDir.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f.getName().startsWith("temp_cdr_")) f.delete();
                }
            }
        }
    }

    /**
     * Copies a Content URI to a temporary file in the app cache so Python can read it.
     */
    private void hideKeyboard(View view) {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
        }
    }

    private String copyUriToCache(Uri uri) {
        try {
            String fileName = "temp_cdr_" + System.currentTimeMillis() + ".xlsx";
            File tempFile = new File(getCacheDir(), fileName);
            InputStream inputStream = getContentResolver().openInputStream(uri);
            if (inputStream == null) return null;
            FileOutputStream outputStream = new FileOutputStream(tempFile);
            byte[] buffer = new byte[8192];
            int length;
            while ((length = inputStream.read(buffer)) > 0) {
                outputStream.write(buffer, 0, length);
            }
            outputStream.flush();
            outputStream.close();
            inputStream.close();
            return tempFile.getAbsolutePath();
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
}