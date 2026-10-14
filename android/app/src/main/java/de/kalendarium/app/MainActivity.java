package de.kalendarium.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.webkit.GeolocationPermissions;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/* Kalendarium als echte Android-App: zeigt die Web-App und plant Erinnerungen
   als echte Android-Wecker, damit das Handy zuverlässig vibriert. */
public class MainActivity extends Activity {
    static final String HOME = "https://slesta21.github.io/kalendarium/";
    WebView web;
    ValueCallback<Uri[]> fileCb;
    GeolocationPermissions.Callback geoCb;
    String geoOrigin;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        Notify.ensureChannels(this);
        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#0B0D17"));
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setGeolocationEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUserAgentString(s.getUserAgentString() + " KalendariumApp/1");
        web.addJavascriptInterface(new Bridge(this), "KalNative");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if ("https".equals(u.getScheme()) && "slesta21.github.io".equals(u.getHost())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception e) { /* keine App dafür */ }
                return true;
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback cb) {
                if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) { cb.invoke(origin, true, true); return; }
                geoCb = cb; geoOrigin = origin;
                requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, 2);
            }
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (fileCb != null) fileCb.onReceiveValue(null);
                fileCb = cb;
                try { startActivityForResult(p.createIntent(), 3); } catch (Exception e) { fileCb = null; return false; }
                return true;
            }
        });

        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }

        String start = HOME;
        Intent i = getIntent();
        if (i != null && i.getData() != null) start = i.getData().toString();
        if (saved != null) web.restoreState(saved); else web.loadUrl(start);
    }

    @Override
    protected void onResume() { super.onResume(); Sense.ensure(this); if (web != null) web.evaluateJavascript("window.dispatchEvent(new Event('kalresume'))", null); }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        if (i != null && i.getData() != null) web.loadUrl(i.getData().toString());
    }

    @Override
    protected void onSaveInstanceState(Bundle out) { super.onSaveInstanceState(out); web.saveState(out); }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        if (code == 7 && web != null) web.evaluateJavascript("window.dispatchEvent(new Event('kalperm'))", null);
        if (code == 2 && geoCb != null) { geoCb.invoke(geoOrigin, res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED, true); geoCb = null; }
    }

    @Override
    protected void onActivityResult(int code, int result, Intent data) {
        if (code == 3 && fileCb != null) { fileCb.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result, data)); fileCb = null; return; }
        super.onActivityResult(code, result, data);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() { if (web != null && web.canGoBack()) web.goBack(); else moveTaskToBack(true); }
}
