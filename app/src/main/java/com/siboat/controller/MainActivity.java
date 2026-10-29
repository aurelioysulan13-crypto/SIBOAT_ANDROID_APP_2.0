package com.siboat.controller;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.webkit.CookieManager;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.webkit.WebViewAssetLoader;

public class MainActivity extends AppCompatActivity {
    private static final int CAMERA_REQUEST = 1001;
    private static final int FILE_REQUEST = 1002;
    private static final int BLUETOOTH_REQUEST = 1003;
    private static final int LOCATION_REQUEST = 1004;

    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;
    private PermissionRequest pendingPermissionRequest;
    private UsbSerialBridge usbBridge;
    private BleBridge bleBridge;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);

        webView = new WebView(this);
        setContentView(webView);

        usbBridge = new UsbSerialBridge(this, webView);
        bleBridge = new BleBridge(this, webView);

        webView.addJavascriptInterface(usbBridge, "SIBOAT_ANDROID_USB");
        webView.addJavascriptInterface(bleBridge, "SIBOAT_ANDROID_BLE");

        ViewCompat.setOnApplyWindowInsetsListener(webView, (v, insets) -> {
            Insets i = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars()
                            | WindowInsetsCompat.Type.displayCutout()
            );

            v.post(() -> webView.evaluateJavascript(
                    "window.setSiboatSafeAreaInsets && window.setSiboatSafeAreaInsets("
                            + i.top + "," + i.right + "," + i.bottom + "," + i.left + ");",
                    null
            ));

            return insets;
        });

        ViewCompat.requestApplyInsets(webView);

        WebView.setWebContentsDebuggingEnabled(false);

        WebViewAssetLoader assetLoader =
                new WebViewAssetLoader.Builder()
                        .addPathHandler(
                                "/assets/",
                                new WebViewAssetLoader.AssetsPathHandler(this)
                        )
                        .build();

        WebSettings settings = webView.getSettings();

        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setSupportMultipleWindows(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setLoadsImagesAutomatically(true);
        settings.setUseWideViewPort(false);
        settings.setLoadWithOverviewMode(false);

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        webView.setBackgroundColor(Color.TRANSPARENT);

        webView.setWebViewClient(new WebViewClient() {

            @Override
            public WebResourceResponse shouldInterceptRequest(
                    WebView view,
                    WebResourceRequest request
            ) {
                return assetLoader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            @SuppressWarnings("deprecation")
            public WebResourceResponse shouldInterceptRequest(
                    WebView view,
                    String url
            ) {
                return assetLoader.shouldInterceptRequest(Uri.parse(url));
            }

            @Override
            public boolean shouldOverrideUrlLoading(
                    WebView view,
                    WebResourceRequest request
            ) {
                return false;
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {

            @Override
            public void onPermissionRequest(
                    final PermissionRequest request
            ) {
                runOnUiThread(() -> {

                    boolean camera = false;

                    for (String resource : request.getResources()) {
                        if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource)) {
                            camera = true;
                            break;
                        }
                    }

                    if (camera
                            && ContextCompat.checkSelfPermission(
                            MainActivity.this,
                            Manifest.permission.CAMERA
                    ) != PackageManager.PERMISSION_GRANTED) {

                        pendingPermissionRequest = request;

                        ActivityCompat.requestPermissions(
                                MainActivity.this,
                                new String[]{Manifest.permission.CAMERA},
                                CAMERA_REQUEST
                        );

                    } else {
                        request.grant(request.getResources());
                    }
                });
            }

            @Override
            public boolean onShowFileChooser(
                    WebView webView,
                    ValueCallback<Uri[]> callback,
                    FileChooserParams params
            ) {

                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(null);
                }

                filePathCallback = callback;

                Intent intent;

                try {
                    intent = params.createIntent();
                } catch (Exception e) {
                    intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("*/*");
                }

                try {
                    startActivityForResult(intent, FILE_REQUEST);
                } catch (Exception e) {
                    filePathCallback = null;
                    return false;
                }

                return true;
            }
        });

        webView.loadUrl(
                "https://appassets.androidplatform.net/assets/index.html"
        );

        requestBluetoothPermissionsIfNeeded();
    }

    private void requestBluetoothPermissionsIfNeeded() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {

            boolean scan =
                    ContextCompat.checkSelfPermission(
                            this,
                            Manifest.permission.BLUETOOTH_SCAN
                    ) == PackageManager.PERMISSION_GRANTED;

            boolean connect =
                    ContextCompat.checkSelfPermission(
                            this,
                            Manifest.permission.BLUETOOTH_CONNECT
                    ) == PackageManager.PERMISSION_GRANTED;

            if (!scan || !connect) {

                ActivityCompat.requestPermissions(
                        this,
                        new String[]{
                                Manifest.permission.BLUETOOTH_SCAN,
                                Manifest.permission.BLUETOOTH_CONNECT
                        },
                        BLUETOOTH_REQUEST
                );
            }

        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {

            boolean location =
                    ContextCompat.checkSelfPermission(
                            this,
                            Manifest.permission.ACCESS_FINE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED;

            if (!location) {

                ActivityCompat.requestPermissions(
                        this,
                        new String[]{
                                Manifest.permission.ACCESS_FINE_LOCATION
                        },
                        LOCATION_REQUEST
                );
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            @NonNull String[] permissions,
            @NonNull int[] grantResults
    ) {

        super.onRequestPermissionsResult(
                requestCode,
                permissions,
                grantResults
        );

        if (requestCode == CAMERA_REQUEST
                && pendingPermissionRequest != null) {

            if (grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED) {

                pendingPermissionRequest.grant(
                        pendingPermissionRequest.getResources()
                );

            } else {
                pendingPermissionRequest.deny();
            }

            pendingPermissionRequest = null;
        }
    }

    @Override
    protected void onActivityResult(
            int requestCode,
            int resultCode,
            @Nullable Intent data
    ) {

        super.onActivityResult(
                requestCode,
                resultCode,
                data
        );

        if (requestCode == FILE_REQUEST
                && filePathCallback != null) {

            Uri[] results = null;

            if (resultCode == Activity.RESULT_OK
                    && data != null) {

                if (data.getClipData() != null) {

                    int count =
                            data.getClipData().getItemCount();

                    results = new Uri[count];

                    for (int i = 0; i < count; i++) {
                        results[i] =
                                data.getClipData()
                                        .getItemAt(i)
                                        .getUri();
                    }

                } else if (data.getData() != null) {

                    results =
                            new Uri[]{
                                    data.getData()
                            };
                }
            }

            filePathCallback.onReceiveValue(results);
            filePathCallback = null;
        }
    }

    @Override
    public void onBackPressed() {

        if (bleBridge != null
                && bleBridge.isConnected()) {

            bleBridge.disconnect();
            return;
        }

        if (usbBridge != null
                && usbBridge.isConnected()) {

            usbBridge.disconnect();
            return;
        }

        if (webView != null
                && webView.canGoBack()) {

            webView.goBack();

        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {

        if (bleBridge != null) {
            bleBridge.destroy();
        }

        if (usbBridge != null) {
            usbBridge.destroy();
        }

        if (webView != null) {

            webView.loadUrl("about:blank");
            webView.stopLoading();

            webView.removeJavascriptInterface(
                    "SIBOAT_ANDROID_BLE"
            );

            webView.removeJavascriptInterface(
                    "SIBOAT_ANDROID_USB"
            );

            webView.destroy();
        }

        super.onDestroy();
    }
                            }
