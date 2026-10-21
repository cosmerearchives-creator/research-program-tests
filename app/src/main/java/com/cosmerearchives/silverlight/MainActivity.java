package com.cosmerearchives.silverlight;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.view.View;
import androidx.webkit.WebViewAssetLoader;
import androidx.webkit.WebViewClientCompat;
import org.json.JSONArray;
import org.json.JSONObject;
import java.net.HttpURLConnection;
import java.net.URL;

public class MainActivity extends Activity {
    private static final String RELEASES_API = "https://api.github.com/repos/cosmerearchives-creator/research-program-tests/releases/latest";
    private static final int PICK_FILE = 502;
    private static final int INSTALL_PERMISSION = 503;
    private WebView webView;
    private ValueCallback<Uri[]> fileCallback;
    private DownloadManager manager;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private long downloadId = -1;
    private Uri readyApk;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        manager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
        webView = new WebView(this);
        setContentView(webView);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);
        WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this)).build();
        webView.setWebViewClient(new WebViewClientCompat() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                return loader.shouldInterceptRequest(req.getUrl());
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = cb;
                try { startActivityForResult(params.createIntent(), PICK_FILE); return true; }
                catch (Exception e) { fileCallback = null; cb.onReceiveValue(null); return false; }
            }
        });
        webView.loadUrl("https://appassets.androidplatform.net/assets/index.html");
        checkUpdates();
    }

    private void checkUpdates() {
        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(RELEASES_API).openConnection();
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(12000);
                conn.setRequestProperty("Accept", "application/vnd.github+json");
                conn.setRequestProperty("User-Agent", "Silverlight-Android");
                if (conn.getResponseCode() != 200) return;
                byte[] bytes = conn.getInputStream().readAllBytes();
                JSONObject release = new JSONObject(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
                String tag = release.optString("tag_name", "");
                if (!tag.matches("v0\\.7\\.[0-9]+")) return;
                long number = Long.parseLong(tag.substring("v0.7.".length()));
                long installed = Build.VERSION.SDK_INT >= 28
                    ? getPackageManager().getPackageInfo(getPackageName(), 0).getLongVersionCode()
                    : getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
                long candidate = 700000L + number;
                if (candidate <= installed) return;
                JSONArray assets = release.optJSONArray("assets");
                if (assets == null) return;
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject asset = assets.getJSONObject(i);
                    if (!"Silverlight.apk".equals(asset.optString("name"))) continue;
                    String link = asset.optString("browser_download_url", "");
                    if (!link.startsWith("https://github.com/cosmerearchives-creator/research-program-tests/releases/download/")) continue;
                    runOnUiThread(() -> new AlertDialog.Builder(this)
                        .setTitle("Silverlight update available")
                        .setMessage("Version " + tag + " is available. Download and install it? Android will ask you to confirm installation.")
                        .setNegativeButton("Later", null)
                        .setPositiveButton("Download", (d,w) -> downloadUpdate(link, tag))
                        .show());
                    return;
                }
            } catch (Exception ignored) { /* Offline: simply skip this launch's check. */ }
            finally { if (conn != null) conn.disconnect(); }
        }).start();
    }

    private void downloadUpdate(String link, String tag) {
        try {
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(link))
                .setTitle("Silverlight " + tag)
                .setDescription("Android app update")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, "Silverlight-" + tag + ".apk");
            downloadId = manager.enqueue(request);
            pollDownload(0);
        } catch (Exception e) { alert("Download could not be started. You can download the APK from GitHub Releases."); }
    }

    private void pollDownload(int attempts) {
        if (attempts > 1200 || downloadId < 0) return;
        handler.postDelayed(() -> {
            DownloadManager.Query query = new DownloadManager.Query().setFilterById(downloadId);
            try (Cursor c = manager.query(query)) {
                if (c != null && c.moveToFirst()) {
                    int status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                    if (status == DownloadManager.STATUS_SUCCESSFUL) {
                        readyApk = manager.getUriForDownloadedFile(downloadId);
                        if (readyApk != null) openInstaller();
                        return;
                    }
                    if (status == DownloadManager.STATUS_FAILED) { alert("Update download failed. Try GitHub Releases."); return; }
                }
            } catch (Exception e) { return; }
            pollDownload(attempts + 1);
        }, 1500);
    }

    private void openInstaller() {
        if (readyApk == null) return;
        if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
            new AlertDialog.Builder(this)
                .setTitle("Allow Silverlight updates")
                .setMessage("Android requires permission to install APKs from this app. You can enable it for Silverlight in system settings.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Open settings", (d,w) -> startActivityForResult(
                    new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())), INSTALL_PERMISSION))
                .show();
            return;
        }
        try {
            Intent install = new Intent(Intent.ACTION_VIEW);
            install.setDataAndType(readyApk, "application/vnd.android.package-archive");
            install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(install);
        } catch (Exception e) {
            alert("Android could not open the package installer. Download the APK manually from GitHub Releases.");
        }
    }

    private void alert(String message) {
        new AlertDialog.Builder(this).setTitle("Silverlight updater").setMessage(message).setPositiveButton("OK", null).show();
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_FILE && fileCallback != null) {
            fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data));
            fileCallback = null;
        } else if (requestCode == INSTALL_PERMISSION && (Build.VERSION.SDK_INT < 26 || getPackageManager().canRequestPackageInstalls())) {
            openInstaller();
        }
    }
    @Override public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }
}
