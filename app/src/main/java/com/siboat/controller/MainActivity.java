package com.siboat.controller;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
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

import java.util.Locale;

public class MainActivity extends AppCompatActivity {
    private static final int CAMERA_REQUEST=1001;
    private static final int FILE_REQUEST=1002;
    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;
    private PermissionRequest pendingPermissionRequest;
    private UsbSerialBridge usbBridge;
    private BleBridge bleBridge;
    // Real system bar sizes in CSS px (dp = physical px / density). The dashboard pulls them through
    // SIBOAT_ANDROID_INSETS and they are pushed again after every page load and every inset change.
    private volatile float insTop, insRight, insBottom, insLeft;

    public class InsetsBridge {
        @JavascriptInterface public String get(){
            return String.format(Locale.US,"{\"top\":%.2f,\"right\":%.2f,\"bottom\":%.2f,\"left\":%.2f}",insTop,insRight,insBottom,insLeft);
        }
    }

    private void pushInsets(){
        if(webView==null) return;
        webView.evaluateJavascript(String.format(Locale.US,"window.setSiboatSafeAreaInsets&&window.setSiboatSafeAreaInsets(%.2f,%.2f,%.2f,%.2f);",insTop,insRight,insBottom,insLeft),null);
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override protected void onCreate(@Nullable Bundle savedInstanceState){
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(),false);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        webView=new WebView(this); setContentView(webView);
        usbBridge=new UsbSerialBridge(this,webView);
        webView.addJavascriptInterface(usbBridge,"SIBOAT_ANDROID_USB");
        bleBridge=new BleBridge(this,webView);
        webView.addJavascriptInterface(bleBridge,"SIBOAT_ANDROID_BLE");
        webView.addJavascriptInterface(new InsetsBridge(),"SIBOAT_ANDROID_INSETS");
        ViewCompat.setOnApplyWindowInsetsListener(webView,(v,insets)->{
            Insets i=insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            float d=getResources().getDisplayMetrics().density; if(d<=0f) d=1f;
            insTop=i.top/d; insRight=i.right/d; insBottom=i.bottom/d; insLeft=i.left/d;
            v.post(()->pushInsets());
            return insets;
        });
        ViewCompat.requestApplyInsets(webView);
        WebView.setWebContentsDebuggingEnabled(false);
        WebViewAssetLoader assetLoader=new WebViewAssetLoader.Builder().addPathHandler("/assets/",new WebViewAssetLoader.AssetsPathHandler(this)).build();
        WebSettings s=webView.getSettings();
        s.setJavaScriptEnabled(true);s.setDomStorageEnabled(true);s.setDatabaseEnabled(true);s.setAllowFileAccess(false);s.setAllowContentAccess(true);s.setMediaPlaybackRequiresUserGesture(false);s.setJavaScriptCanOpenWindowsAutomatically(true);s.setSupportMultipleWindows(false);s.setBuiltInZoomControls(false);s.setDisplayZoomControls(false);s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        CookieManager.getInstance().setAcceptCookie(true);CookieManager.getInstance().setAcceptThirdPartyCookies(webView,true);
        webView.setWebViewClient(new WebViewClient(){
            @Override public WebResourceResponse shouldInterceptRequest(WebView view,WebResourceRequest request){return assetLoader.shouldInterceptRequest(request.getUrl());}
            @Override public WebResourceResponse shouldInterceptRequest(WebView view,String url){return assetLoader.shouldInterceptRequest(Uri.parse(url));}
            @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request){return false;}
            @Override public void onPageFinished(WebView view,String url){super.onPageFinished(view,url);pushInsets();}
        });
        webView.setWebChromeClient(new WebChromeClient(){
            @Override public void onPermissionRequest(final PermissionRequest request){runOnUiThread(()->{boolean camera=false;for(String r:request.getResources()){if(PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r)){camera=true;break;}}if(camera&&ContextCompat.checkSelfPermission(MainActivity.this,Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED){pendingPermissionRequest=request;ActivityCompat.requestPermissions(MainActivity.this,new String[]{Manifest.permission.CAMERA},CAMERA_REQUEST);}else request.grant(request.getResources());});}
            @Override public boolean onShowFileChooser(WebView w,ValueCallback<Uri[]> cb,FileChooserParams p){if(filePathCallback!=null)filePathCallback.onReceiveValue(null);filePathCallback=cb;Intent intent;try{intent=p.createIntent();}catch(Exception e){intent=new Intent(Intent.ACTION_OPEN_DOCUMENT);intent.addCategory(Intent.CATEGORY_OPENABLE);intent.setType("*/*");}try{startActivityForResult(intent,FILE_REQUEST);}catch(Exception e){filePathCallback=null;return false;}return true;}
        });
        webView.loadUrl("https://appassets.androidplatform.net/assets/index.html");
    }

    @Override public void onRequestPermissionsResult(int requestCode,@NonNull String[] permissions,@NonNull int[] grantResults){super.onRequestPermissionsResult(requestCode,permissions,grantResults);if(bleBridge!=null&&bleBridge.handlePermissionResult(requestCode,grantResults))return;if(requestCode==CAMERA_REQUEST&&pendingPermissionRequest!=null){if(grantResults.length>0&&grantResults[0]==PackageManager.PERMISSION_GRANTED)pendingPermissionRequest.grant(pendingPermissionRequest.getResources());else pendingPermissionRequest.deny();pendingPermissionRequest=null;}}
    @Override protected void onActivityResult(int requestCode,int resultCode,@Nullable Intent data){super.onActivityResult(requestCode,resultCode,data);if(requestCode==FILE_REQUEST&&filePathCallback!=null){Uri[] results=null;if(resultCode==Activity.RESULT_OK&&data!=null){if(data.getClipData()!=null){int count=data.getClipData().getItemCount();results=new Uri[count];for(int i=0;i<count;i++)results[i]=data.getClipData().getItemAt(i).getUri();}else if(data.getData()!=null)results=new Uri[]{data.getData()};}filePathCallback.onReceiveValue(results);filePathCallback=null;}}
    @Override public void onBackPressed(){if(usbBridge!=null&&usbBridge.isConnected()){usbBridge.disconnect();return;}if(webView!=null&&webView.canGoBack())webView.goBack();else super.onBackPressed();}
    @Override protected void onDestroy(){if(bleBridge!=null)bleBridge.destroy();if(usbBridge!=null)usbBridge.destroy();if(webView!=null){webView.loadUrl("about:blank");webView.stopLoading();webView.removeJavascriptInterface("SIBOAT_ANDROID_USB");webView.destroy();}super.onDestroy();}
                                                                                                                                                                                                                                                                                                                                                                             }
