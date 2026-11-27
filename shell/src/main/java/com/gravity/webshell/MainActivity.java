package com.gravity.webshell;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.webkit.ConsoleMessage;
import android.webkit.MimeTypeMap;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.FrameLayout;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs a web app bundled under assets/www. Pages are served from https://appassets.androidplatform.net
 * (intercepted, never fetched from the network) so relative URLs, fetch(), ES modules and
 * localStorage behave as on a web server. File inputs open the system picker (e.g. to choose
 * songs or photos); other links open in the browser.
 *
 * The page sits between the status bar and the navigation bar (and above the keyboard), never under
 * them: Android 15 draws apps behind the bars, so the page is padded by the bars' size, and the bars
 * take the page's background color with icons that stay readable on it.
 */
public class MainActivity extends Activity {
    private static final String TAG = "GravityWebShell";
    private static final String HOST = "appassets.androidplatform.net";
    private static final int PICK_FILES = 41;

    private WebView web;
    private FrameLayout root;
    private ValueCallback<Uri[]> pendingFiles;

    /** The page's background color (body, else html, else the first color of a gradient), as CSS. */
    private static final String PAGE_COLOR_JS =
        "(function(){function bg(e){if(!e)return '';var s=getComputedStyle(e);var c=s.backgroundColor;" +
        "if(c&&c!=='transparent'&&c!=='rgba(0, 0, 0, 0)')return c;var m=(s.backgroundImage||'').match(/rgba?\\([^)]*\\)|#[0-9a-fA-F]{3,8}/);" +
        "return m?m[0]:'';}return bg(document.body)||bg(document.documentElement)||'rgb(255, 255, 255)';})()";

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.WHITE);
        if (Build.VERSION.SDK_INT >= 30) {
            // Draw behind the bars on every Android version from 11 on (15 does it anyway), then keep the page out from under them.
            getWindow().setDecorFitsSystemWindows(false);
            getWindow().setStatusBarColor(Color.TRANSPARENT);
            getWindow().setNavigationBarColor(Color.TRANSPARENT);
            root.setOnApplyWindowInsetsListener((v, insets) -> {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                Insets ime = insets.getInsets(WindowInsets.Type.ime());
                v.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom));
                Log.i(TAG, "insets " + bars.left + "," + bars.top + "," + bars.right + "," + Math.max(bars.bottom, ime.bottom));
                return WindowInsets.CONSUMED;
            });
        }
        web = new WebView(this);
        web.setBackgroundColor(Color.WHITE);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return serve(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (HOST.equals(uri.getHost())) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, uri));
                } catch (Exception e) {
                    Log.w(TAG, "No app can open " + uri);
                }
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                Log.i(TAG, "loaded " + url + " title=" + view.getTitle());
                view.evaluateJavascript(PAGE_COLOR_JS, value -> matchBars(value));
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (pendingFiles != null) pendingFiles.onReceiveValue(null);
                pendingFiles = callback;
                Intent intent = params.createIntent();
                if (params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) {
                    intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                }
                try {
                    startActivityForResult(intent, PICK_FILES);
                    return true;
                } catch (Exception e) {
                    pendingFiles = null;
                    return false;
                }
            }

            @Override
            public boolean onConsoleMessage(ConsoleMessage m) {
                Log.i(TAG, "console " + m.messageLevel() + ": " + m.message());
                return true;
            }
        });
        root.addView(web, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);
        if (state != null) web.restoreState(state);
        else web.loadUrl("https://" + HOST + "/index.html");
    }

    private static final Pattern RGB = Pattern.compile("rgba?\\(\\s*(\\d+)[,\\s]+(\\d+)[,\\s]+(\\d+)");
    private static final Pattern HEX = Pattern.compile("#([0-9a-fA-F]{3}|[0-9a-fA-F]{6})\\b");

    /** Colors the space behind the status and navigation bars like the page, with readable bar icons. */
    private void matchBars(String value) {
        if (value == null) return;
        String css = value.replace("\"", "").trim();
        int color;
        Matcher m = RGB.matcher(css);
        Matcher h = HEX.matcher(css);
        if (m.find()) {
            color = Color.rgb(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
        } else if (h.find()) {
            String hex = h.group(1);
            if (hex.length() == 3) hex = "" + hex.charAt(0) + hex.charAt(0) + hex.charAt(1) + hex.charAt(1) + hex.charAt(2) + hex.charAt(2);
            color = Color.parseColor("#" + hex);
        } else {
            return;
        }
        root.setBackgroundColor(color);
        boolean light = (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) > 150;
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                int bits = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                c.setSystemBarsAppearance(light ? bits : 0, bits);
            }
        } else if (Build.VERSION.SDK_INT >= 26) {
            getWindow().setStatusBarColor(color);
            getWindow().setNavigationBarColor(color);
            int flags = getWindow().getDecorView().getSystemUiVisibility();
            int lightBits = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            getWindow().getDecorView().setSystemUiVisibility(light ? (flags | lightBits) : (flags & ~lightBits));
        }
        Log.i(TAG, "bars colored " + String.format(Locale.ROOT, "#%06x", color & 0xFFFFFF) + (light ? " (dark icons)" : " (light icons)"));
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_FILES || pendingFiles == null) return;
        Uri[] result = null;
        if (resultCode == RESULT_OK && data != null) {
            ClipData clip = data.getClipData();
            if (clip != null && clip.getItemCount() > 0) {
                result = new Uri[clip.getItemCount()];
                for (int i = 0; i < clip.getItemCount(); i++) result[i] = clip.getItemAt(i).getUri();
            } else if (data.getData() != null) {
                result = new Uri[]{data.getData()};
            }
        }
        pendingFiles.onReceiveValue(result);
        pendingFiles = null;
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (web != null) web.saveState(out);
    }

    @Override
    protected void onDestroy() {
        if (web != null) web.destroy();
        super.onDestroy();
    }

    private WebResourceResponse serve(Uri uri) {
        if (!HOST.equals(uri.getHost())) return null;
        String path = uri.getPath();
        if (path == null || path.isEmpty() || path.equals("/")) path = "/index.html";
        path = path.substring(1);
        if (path.endsWith("/")) path += "index.html";
        if (path.contains("..")) return notFound();
        try {
            InputStream in = getAssets().open("www/" + path);
            String mime = mimeFor(path);
            WebResourceResponse r = new WebResourceResponse(mime, mime.startsWith("text/") || mime.contains("javascript") || mime.contains("json") ? "utf-8" : null, in);
            r.setResponseHeaders(Collections.singletonMap("Cache-Control", "no-cache"));
            return r;
        } catch (IOException e) {
            return notFound();
        }
    }

    private static WebResourceResponse notFound() {
        return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found", Collections.emptyMap(), new ByteArrayInputStream(new byte[0]));
    }

    private static String mimeFor(String path) {
        String ext = path.substring(path.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        switch (ext) {
            case "html": case "htm": return "text/html";
            case "js": case "mjs": return "text/javascript";
            case "css": return "text/css";
            case "json": case "map": return "application/json";
            case "svg": return "image/svg+xml";
            case "wasm": return "application/wasm";
            case "webmanifest": return "application/manifest+json";
            default:
                String m = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
                return m != null ? m : "application/octet-stream";
        }
    }
}
