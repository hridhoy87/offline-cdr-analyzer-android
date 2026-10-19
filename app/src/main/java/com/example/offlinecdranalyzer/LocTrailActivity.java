package com.example.offlinecdranalyzer;

import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;

public class LocTrailActivity extends AppCompatActivity {

    private WebView webView;
    private View loadingOverlay;
    private ImageView loadingLogo;
    private String rawLocTrailData = null;

    private static final long INACTIVITY_TIMEOUT = 30 * 60 * 1000;
    private final Handler inactivityHandler = new Handler(Looper.getMainLooper());
    private final Runnable inactivityRunnable = () -> {
        Toast.makeText(this, R.string.session_expired_affinity, Toast.LENGTH_LONG).show();
        finishAffinity();
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

        setContentView(R.layout.activity_loc_trail);

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            final WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        }

        ImageButton btnBack = findViewById(R.id.btnBackLocTrail);
        btnBack.setOnClickListener(v -> finish());

        loadingOverlay = findViewById(R.id.loadingOverlayLocTrail);
        loadingLogo = findViewById(R.id.loadingLogoLocTrail);

        Animation complexAnim = AnimationUtils.loadAnimation(this, R.anim.complex_loader);
        if (loadingLogo != null) loadingLogo.startAnimation(complexAnim);

        String dataPath = getIntent().getStringExtra("loc_trail_path");
        if (dataPath != null) {
            rawLocTrailData = readDataFromFile(dataPath);
        }

        webView = findViewById(R.id.webViewLocTrail);
        setupWebView();
    }

    private void setupWebView() {
        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setAllowFileAccess(true);
        webSettings.setAllowContentAccess(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setGeolocationEnabled(true);
        webSettings.setCacheMode(WebSettings.LOAD_DEFAULT);
        webView.setBackgroundColor(Color.parseColor("#0b1120"));

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, android.webkit.GeolocationPermissions.Callback callback) {
                callback.invoke(origin, true, false);
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
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
                if (rawLocTrailData != null) {
                    try {
                        String encoded = Base64.encodeToString(rawLocTrailData.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
                        view.evaluateJavascript("window.renderLocTrail(decodeURIComponent(escape(window.atob('" + encoded + "'))))", null);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }

                if (loadingOverlay != null) {
                    loadingOverlay.animate().alpha(0f).setDuration(400).withEndAction(() -> {
                        if (loadingLogo != null) loadingLogo.clearAnimation();
                        loadingOverlay.setVisibility(View.GONE);
                    });
                }
            }
        });

        setupWebViewTouchHandling(webView);
        webView.loadUrl("file:///android_asset/loc_trail.html");
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private void setupWebViewTouchHandling(WebView webView) {
        webView.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_MOVE:
                    v.getParent().requestDisallowInterceptTouchEvent(true);
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.getParent().requestDisallowInterceptTouchEvent(false);
                    break;
            }
            return false;
        });
    }

    private String readDataFromFile(String path) {
        if (path == null) return null;
        try {
            File file = new File(path);
            if (!file.exists()) return null;
            FileInputStream fis = new FileInputStream(file);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int len;
            while ((len = fis.read(buffer)) != -1) {
                bos.write(buffer, 0, len);
            }
            fis.close();
            return bos.toString("UTF-8");
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
}
