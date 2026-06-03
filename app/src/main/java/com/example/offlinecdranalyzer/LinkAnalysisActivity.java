package com.example.offlinecdranalyzer;

import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ImageButton;
import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.FileOutputStream;
import java.io.IOException;

public class LinkAnalysisActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Fullscreen Immersive Mode
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
        if (graphData != null) {
            try {
                FileOutputStream fos = openFileOutput("graph_data.json", MODE_PRIVATE);
                fos.write(graphData.getBytes());
                fos.close();
                Log.d("LinkAnalysis", "JSON saved to: " + getFilesDir() + "/graph_data.json");
            } catch (IOException e) {
                e.printStackTrace();
            }
        }


        //Dump JSON in logcat
//        android.util.Log.v("======>","+++++INBOUND JSON++++++");
//        android.util.Log.d("LinkAnalysis", "Inbound JSON: " + graphData);

        // Pretty print the JSON
//        try {
//            if (graphData != null) {
//                JSONObject jsonObject = new JSONObject(graphData);
//                String prettyJson = jsonObject.toString(2); // 2 spaces indentation
//                android.util.Log.v("======>","+++++INBOUND JSON++++++");
//                android.util.Log.d("LinkAnalysis", "Inbound JSON (pretty):\n" + prettyJson);
//            } else {
//                android.util.Log.d("LinkAnalysis", "Inbound JSON is null");
//            }
//        } catch (JSONException e) {
//            android.util.Log.e("LinkAnalysis", "Failed to parse JSON", e);
//        }

        WebView webView = findViewById(R.id.webViewGraph);
        ImageButton btnBack = findViewById(R.id.btnBack);

        btnBack.setOnClickListener(v -> finish());

        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setAllowFileAccess(true);
        webSettings.setAllowContentAccess(true);
        webSettings.setDomStorageEnabled(true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                if (graphData != null) {
                    // Inject the data into the JS function
                    view.evaluateJavascript("renderOfflineData('" + graphData.replace("'", "\\'") + "')", null);
                }
            }
        });

        webView.loadUrl("file:///android_asset/link_analysis.html");
    }
}