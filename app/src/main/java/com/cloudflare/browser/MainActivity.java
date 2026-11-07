package com.cloudflare.browser;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.os.Environment;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.core.content.FileProvider;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.webkit.ProxyConfig;
import androidx.webkit.ProxyController;
import androidx.webkit.ProfileStore;
import androidx.webkit.WebViewAssetLoader;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import com.cloudflare.browser.browser.BrowserTab;
import com.cloudflare.browser.dns.CloudflareDohResolver;
import com.cloudflare.browser.proxy.LocalHttpProxy;
import com.cloudflare.browser.security.UrlPolicy;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class MainActivity extends Activity {
    private static final String APP_ORIGIN = "https://appassets.androidplatform.net";
    private static final String PREFS = "browser_preferences";
    private static final int MAX_TABS = 8;
    private final LinkedHashMap<String, BrowserTab> tabs = new LinkedHashMap<>();
    private final Map<Long, DownloadRecord> downloads = new LinkedHashMap<>();
    private final AtomicLong nextDownloadId = new AtomicLong(1);
    private final ExecutorService background = Executors.newFixedThreadPool(4);
    private LinearLayout root;
    private WebView chrome;
    private FrameLayout pageHost;
    private TextView errorPanel;
    private BrowserTab activeTab;
    private LocalHttpProxy localProxy, downloadProxy;
    private CloudflareDohResolver resolver;
    private boolean dnsEnabled, proxyApplied, proxySupported, chromeReady;
    private boolean historyEnabled = true, javascriptEnabled = true, popupsEnabled, thirdPartyCookies, restoreSession, preferSecondary;
    private boolean privateProfilesSupported, switchingProxy, pendingStartupRestore;
    private volatile boolean activityDestroyed;
    private String privateProfileName;
    private String shellScreen = "home";
    private String pendingStartupUrl = "";

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        applySystemBars(false);
        readPreferences();
        restoreDownloadHistory();
        buildViews();
        createChrome();
        inspectWebViewFeatures();
        if (dnsEnabled) {
            pendingStartupRestore = true;
            enableDns();
        } else finishStartupTabs(true);
    }

    private void readPreferences() {
        android.content.SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        dnsEnabled = prefs.getBoolean("dns_enabled", false);
        historyEnabled = prefs.getBoolean("history_enabled", true);
        javascriptEnabled = prefs.getBoolean("javascript_enabled", true);
        popupsEnabled = prefs.getBoolean("popups_enabled", false);
        thirdPartyCookies = prefs.getBoolean("third_party_cookies", false);
        restoreSession = prefs.getBoolean("restore_session", false);
        preferSecondary = prefs.getBoolean("secondary_first", false);
    }

    private void buildViews() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(245, 241, 233));
        chrome = new WebView(this);
        pageHost = new FrameLayout(this);
        pageHost.setBackgroundColor(Color.rgb(245, 241, 233));
        errorPanel = new TextView(this);
        errorPanel.setTextColor(Color.rgb(40, 39, 36));
        errorPanel.setTextSize(16);
        errorPanel.setGravity(Gravity.CENTER);
        errorPanel.setPadding(dp(28), dp(24), dp(28), dp(24));
        errorPanel.setBackgroundColor(Color.rgb(245, 241, 233));
        errorPanel.setVisibility(View.GONE);
        pageHost.addView(errorPanel, new FrameLayout.LayoutParams(-1, -1));
        root.addView(chrome, new LinearLayout.LayoutParams(-1, 0, 1));
        root.addView(pageHost, new LinearLayout.LayoutParams(-1, 0, 0));
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(0, bars.top, 0, bars.bottom);
            return insets;
        });
        setContentView(root);
        ViewCompat.requestApplyInsets(root);
    }

    private void createChrome() {
        WebViewAssetLoader assetLoader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();
        WebSettings settings = chrome.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setSupportMultipleWindows(false);
        chrome.addJavascriptInterface(new NativeBridge(), "Native");
        chrome.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onJsAlert(WebView view, String url, String message, android.webkit.JsResult result) {
                if (!APP_ORIGIN.equals(Uri.parse(url).getScheme() + "://" + Uri.parse(url).getAuthority())) { result.cancel(); return true; }
                new AlertDialog.Builder(MainActivity.this).setMessage(message).setPositiveButton("OK", (dialog, which) -> result.confirm())
                        .setOnCancelListener(dialog -> result.cancel()).show();
                return true;
            }
            @Override public boolean onJsConfirm(WebView view, String url, String message, android.webkit.JsResult result) {
                if (!APP_ORIGIN.equals(Uri.parse(url).getScheme() + "://" + Uri.parse(url).getAuthority())) { result.cancel(); return true; }
                new AlertDialog.Builder(MainActivity.this).setMessage(message).setPositiveButton("Lanjut", (dialog, which) -> result.confirm())
                        .setNegativeButton("Batal", (dialog, which) -> result.cancel()).setOnCancelListener(dialog -> result.cancel()).show();
                return true;
            }
            @Override public boolean onJsPrompt(WebView view, String url, String message, String defaultValue, android.webkit.JsPromptResult result) {
                if (!APP_ORIGIN.equals(Uri.parse(url).getScheme() + "://" + Uri.parse(url).getAuthority())) { result.cancel(); return true; }
                EditText input = new EditText(MainActivity.this); input.setSingleLine(true); input.setText(defaultValue);
                new AlertDialog.Builder(MainActivity.this).setTitle(message).setView(input)
                        .setPositiveButton("Simpan", (dialog, which) -> result.confirm(input.getText().toString()))
                        .setNegativeButton("Batal", (dialog, which) -> result.cancel()).setOnCancelListener(dialog -> result.cancel()).show();
                return true;
            }
        });
        chrome.setWebViewClient(new WebViewClient() {
            @Override public android.webkit.WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return assetLoader.shouldInterceptRequest(request.getUrl());
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                return !APP_ORIGIN.equals(uri.getScheme() + "://" + uri.getAuthority());
            }
            @Override public void onPageFinished(WebView view, String url) {
                chromeReady = url.startsWith(APP_ORIGIN + "/assets/");
                emit("startup", getStartupState().toString());
                if (!pendingStartupUrl.isEmpty()) {
                    emit("startupUrl", JSONObject.quote(pendingStartupUrl));
                    pendingStartupUrl = "";
                }
            }
        });
        chrome.loadUrl(APP_ORIGIN + "/assets/browser/index.html");
    }

    private void inspectWebViewFeatures() {
        proxySupported = WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE);
        privateProfilesSupported = WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE);
    }

    private void finishStartupTabs(boolean allowRestore) {
        pendingStartupRestore = false;
        if (allowRestore) restoreSession();
        if (tabs.isEmpty()) {
            String homepage = allowRestore ? getSharedPreferences(PREFS, MODE_PRIVATE).getString("homepage", "") : "";
            Uri homepageUri = UrlPolicy.parseWebUrl(homepage);
            createTab(false, homepageUri == null ? "" : homepageUri.toString());
        }
        showHome();
        if (allowRestore && activeTab != null && !activeTab.url.isEmpty()) {
            pendingStartupUrl = activeTab.url;
            if (chromeReady) {
                emit("startupUrl", JSONObject.quote(pendingStartupUrl));
                pendingStartupUrl = "";
            }
        }
    }

    private void restoreSession() {
        if (!restoreSession) return;
        JSONArray stored;
        try { stored = new JSONArray(getSharedPreferences(PREFS, MODE_PRIVATE).getString("session", "[]")); }
        catch (JSONException error) { return; }
        for (int index = 0; index < stored.length() && index < MAX_TABS; index++) {
            JSONObject row = stored.optJSONObject(index);
            if (row == null) continue;
            Uri uri = UrlPolicy.parseWebUrl(row.optString("url", ""));
            if (uri != null) createTab(false, uri.toString());
        }
    }

    private BrowserTab createTab(boolean privateTab, String url) {
        if (tabs.size() >= MAX_TABS) {
            notifyUi("limit", "Maksimal 8 tab dapat dibuka bersamaan.");
            return null;
        }
        if (privateTab && !WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            notifyUi("privateUnsupported", "WebView pada perangkat ini belum mendukung profil terpisah. Tab privat tidak dibuka.");
            return null;
        }
        if (privateTab && privateProfileName == null) privateProfileName = "cf-private-" + UUID.randomUUID();
        String profile = privateTab ? privateProfileName : "cf-regular";
        BrowserTab previous = activeTab;
        BrowserTab tab = new BrowserTab(UUID.randomUUID().toString(), privateTab, profile);
        if (previous != null && previous.webView != null) previous.webView.setVisibility(View.GONE);
        try {
            tab.webView = new WebView(this);
            if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) WebViewCompat.setProfile(tab.webView, profile);
            configureSiteView(tab);
            tabs.put(tab.id, tab);
            pageHost.addView(tab.webView, 0, new FrameLayout.LayoutParams(-1, -1));
            tab.webView.setVisibility(View.GONE);
            activeTab = tab;
            showBrowserMode();
            if (url != null && !url.isEmpty()) navigate(tab, url);
            publishTabs();
            persistSession();
            return tab;
        } catch (RuntimeException error) {
            if (tab.webView != null) tab.webView.destroy();
            tabs.remove(tab.id);
            activeTab = previous;
            if (previous != null) previous.webView.setVisibility(View.VISIBLE);
            notifyUi("error", "WebView tidak dapat dibuat: " + safeMessage(error));
            return null;
        }
    }

    private void configureSiteView(BrowserTab tab) {
        WebSettings settings = tab.webView.getSettings();
        settings.setJavaScriptEnabled(javascriptEnabled);
        settings.setTextZoom(getSharedPreferences(PREFS, MODE_PRIVATE).getInt("text_zoom", 100));
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setJavaScriptCanOpenWindowsAutomatically(popupsEnabled);
        settings.setSupportMultipleWindows(popupsEnabled);
        settings.setSaveFormData(!tab.privateTab);
        settings.setCacheMode(tab.privateTab ? WebSettings.LOAD_NO_CACHE : WebSettings.LOAD_DEFAULT);
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE))
                WebViewCompat.getProfile(tab.webView).getCookieManager().setAcceptThirdPartyCookies(tab.webView, !tab.privateTab && thirdPartyCookies);
            else CookieManager.getInstance().setAcceptThirdPartyCookies(tab.webView, !tab.privateTab && thirdPartyCookies);
        } catch (RuntimeException ignored) { CookieManager.getInstance().setAcceptThirdPartyCookies(tab.webView, !tab.privateTab && thirdPartyCookies); }
        tab.webView.setDownloadListener(new BrowserDownloadListener(tab));
        tab.webView.setWebViewClient(new SiteClient(tab));
        tab.webView.setWebChromeClient(new SiteChromeClient(tab));
    }

    private void navigate(BrowserTab tab, String value) {
        Uri uri = UrlPolicy.parseWebUrl(value);
        if (uri == null) {
            notifyUi("error", "Alamat tidak valid. Hanya alamat HTTP dan HTTPS yang dapat dibuka.");
            return;
        }
        if (dnsEnabled && (!proxyApplied || localProxy == null || !localProxy.isRunning())) {
            notifyUi("dnsBlocked", "Jalur Cloudflare DNS tidak siap. Navigasi dihentikan agar tidak memakai resolver perangkat secara diam-diam.");
            return;
        }
        tab.url = uri.toString();
        tab.tlsValidated = false;
        tab.requestCountAtNavigation = localProxy == null ? 0 : localProxy.getRequestCount();
        tab.webViewProxyObserved = false;
        errorPanel.setVisibility(View.GONE);
        tab.webView.setVisibility(View.VISIBLE);
        tab.webView.loadUrl(tab.url);
        showBrowserMode();
        publishTabs();
    }

    private void selectTab(String id) {
        BrowserTab next = tabs.get(id);
        if (next == null) return;
        if (activeTab != null) activeTab.webView.setVisibility(View.GONE);
        activeTab = next;
        activeTab.webView.setVisibility(View.VISIBLE);
        errorPanel.setVisibility(View.GONE);
        showBrowserMode();
        sendPageState(activeTab);
        publishTabs();
    }

    private void closeTab(String id) {
        BrowserTab tab = tabs.remove(id);
        if (tab == null) return;
        boolean wasActive = tab == activeTab;
        pageHost.removeView(tab.webView);
        tab.webView.stopLoading();
        tab.webView.destroy();
        if (wasActive) {
            activeTab = tabs.isEmpty() ? null : new ArrayList<>(tabs.values()).get(tabs.size() - 1);
            if (activeTab == null) createTab(false, "");
            else selectTab(activeTab.id);
        }
        if (tab.privateTab && noPrivateTabs()) deletePrivateProfile();
        publishTabs();
        persistSession();
    }

    private boolean noPrivateTabs() {
        for (BrowserTab tab : tabs.values()) if (tab.privateTab) return false;
        return true;
    }

    private void deletePrivateProfile() {
        if (privateProfileName == null) return;
        String expired = privateProfileName;
        privateProfileName = null;
        try { ProfileStore.getInstance().deleteProfile(expired); }
        catch (RuntimeException error) { notifyUi("privacy", "Profil privat akan dihapus oleh WebView, tetapi belum dapat dibersihkan saat ini."); }
    }

    private void persistSession() {
        if (!restoreSession) return;
        JSONArray rows = new JSONArray();
        for (BrowserTab tab : tabs.values()) {
            if (tab.privateTab || tab.url.isEmpty()) continue;
            JSONObject row = new JSONObject();
            try { row.put("url", tab.url); rows.put(row); }
            catch (JSONException ignored) { }
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("session", rows.toString()).apply();
    }

    private void publishTabs() {
        JSONArray rows = new JSONArray();
        for (BrowserTab tab : tabs.values()) {
            JSONObject row = new JSONObject();
            try {
                row.put("id", tab.id); row.put("title", tab.title); row.put("url", tab.url);
                row.put("private", tab.privateTab); row.put("active", tab == activeTab); row.put("progress", tab.progress);
                rows.put(row);
            } catch (JSONException ignored) { }
        }
        emit("tabs", rows.toString());
    }

    private JSONObject getStartupState() {
        JSONObject state = new JSONObject();
        try {
            state.put("dnsEnabled", dnsEnabled); state.put("proxySupported", proxySupported);
            state.put("proxyApplied", proxyApplied); state.put("proxyRunning", localProxy != null && localProxy.isRunning());
            state.put("historyEnabled", historyEnabled); state.put("javascriptEnabled", javascriptEnabled);
            state.put("popupsEnabled", popupsEnabled); state.put("thirdPartyCookies", thirdPartyCookies); state.put("restoreSession", restoreSession);
            state.put("privateProfilesSupported", privateProfilesSupported); state.put("secondaryFirst", preferSecondary);
            state.put("cacheCount", resolver == null ? 0 : resolver.cachedHostCount());
            state.put("requestCount", localProxy == null ? 0 : localProxy.getRequestCount());
            state.put("tunnelCount", localProxy == null ? 0 : localProxy.getTunnelCount());
            state.put("trafficObserved", activeTab != null && activeTab.webViewProxyObserved);
            android.content.SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
            state.put("dohReachable", prefs.getBoolean("doh_reachable", false));
            state.put("dnsQuerySucceeded", prefs.getBoolean("dns_query_succeeded", false));
            state.put("dnsSuccessAt", prefs.getLong("dns_success_at", 0));
            state.put("dnsLatencyMs", prefs.getLong("dns_latency_ms", -1));
            state.put("homepage", prefs.getString("homepage", ""));
            state.put("reducedMotion", prefs.getBoolean("reduced_motion", false));
            state.put("textZoom", prefs.getInt("text_zoom", 100));
            state.put("startupUrl", activeTab == null ? "" : activeTab.url);
        } catch (JSONException ignored) { }
        return state;
    }

    private void emit(String type, String json) {
        if (!chromeReady || chrome == null) return;
        String typeValue = JSONObject.quote(type);
        String payloadValue = JSONObject.quote(json == null ? "{}" : json);
        runOnUiThread(() -> chrome.evaluateJavascript("window.CFApp&&window.CFApp.onNativeEvent(" + typeValue + "," + payloadValue + ")", null));
    }

    private void notifyUi(String type, String message) {
        emit(type, message);
        runOnUiThread(() -> Toast.makeText(this, message, Toast.LENGTH_LONG).show());
    }

    private void sendPageState(BrowserTab tab) {
        if (tab == null) return;
        JSONObject row = new JSONObject();
        try {
            row.put("id", tab.id); row.put("title", tab.title); row.put("url", tab.url);
            row.put("progress", tab.progress); row.put("private", tab.privateTab);
            row.put("secure", tab.tlsValidated && tab.url.startsWith("https://")); row.put("proxyObserved", tab.webViewProxyObserved);
        } catch (JSONException ignored) { }
        emit("page", row.toString());
    }

    private void showBrowserMode() {
        runOnUiThread(() -> {
            if (root == null) return;
            root.updateViewLayout(chrome, new LinearLayout.LayoutParams(-1, dp(186)));
            root.updateViewLayout(pageHost, new LinearLayout.LayoutParams(-1, 0, 1));
            pageHost.setVisibility(View.VISIBLE);
            if (activeTab != null) activeTab.webView.setVisibility(View.VISIBLE);
            sendPageState(activeTab);
        });
    }

    private void showHome() {
        runOnUiThread(() -> {
            if (root == null) return;
            for (BrowserTab tab : tabs.values()) tab.webView.setVisibility(View.GONE);
            errorPanel.setVisibility(View.GONE);
            pageHost.setVisibility(View.GONE);
            root.updateViewLayout(chrome, new LinearLayout.LayoutParams(-1, 0, 1));
            root.updateViewLayout(pageHost, new LinearLayout.LayoutParams(-1, 0, 0));
            emit("home", getStartupState().toString());
        });
    }

    private void enableDns() {
        if (switchingProxy || activityDestroyed) return;
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            dnsEnabled = false; proxyApplied = false; savePreference("dns_enabled", false);
            notifyUi("dnsUnsupported", "Perangkat ini tidak mendukung WebView proxy override. DNS Cloudflare tetap nonaktif.");
            emit("dns", getStartupState().toString());
            if (pendingStartupRestore) finishStartupTabs(false);
            return;
        }
        switchingProxy = true;
        background.execute(() -> {
            try {
                resolver = new CloudflareDohResolver(preferSecondary);
                LocalHttpProxy startingProxy = new LocalHttpProxy(resolver);
                int port = startingProxy.start();
                if (activityDestroyed) { startingProxy.close(); return; }
                localProxy = startingProxy;
                ProxyConfig config = new ProxyConfig.Builder().removeImplicitRules()
                        .addProxyRule("http://127.0.0.1:" + port).build();
                ProxyController.getInstance().setProxyOverride(config, command -> runOnUiThread(command), () -> runOnUiThread(() -> {
                    if (activityDestroyed) {
                        ProxyController.getInstance().clearProxyOverride(Runnable::run, startingProxy::close);
                        return;
                    }
                    switchingProxy = false; proxyApplied = true; dnsEnabled = true; savePreference("dns_enabled", true);
                    emit("dns", getStartupState().toString());
                    if (pendingStartupRestore) finishStartupTabs(true);
                    else if (activeTab != null && !activeTab.url.isEmpty()) navigate(activeTab, activeTab.url);
                }));
            } catch (Exception error) {
                switchingProxy = false; proxyApplied = false; dnsEnabled = false;
                if (localProxy != null) localProxy.close();
                localProxy = null; resolver = null; savePreference("dns_enabled", false);
                notifyUi("dnsError", "Proxy DNS tidak dapat dijalankan: " + safeMessage(error));
                emit("dns", getStartupState().toString());
                if (pendingStartupRestore) finishStartupTabs(false);
            }
        });
    }

    private void changeSecondary(boolean enabled) {
        if (activityDestroyed) return;
        preferSecondary = enabled;
        savePreference("secondary_first", enabled);
        if (!dnsEnabled || switchingProxy) return;
        if (!proxySupported || !proxyApplied) {
            disableDns();
            enableDns();
            return;
        }
        switchingProxy = true;
        ProxyController.getInstance().clearProxyOverride(command -> runOnUiThread(command), () -> runOnUiThread(() -> {
            proxyApplied = false;
            if (localProxy != null) localProxy.close();
            localProxy = null;
            resolver = null;
            switchingProxy = false;
            enableDns();
        }));
    }

    private void disableDns() {
        if (switchingProxy || activityDestroyed) return;
        if (!proxySupported || !proxyApplied) {
            dnsEnabled = false; proxyApplied = false; savePreference("dns_enabled", false);
            if (localProxy != null) localProxy.close();
            localProxy = null; emit("dns", getStartupState().toString()); return;
        }
        switchingProxy = true;
        ProxyController.getInstance().clearProxyOverride(command -> runOnUiThread(command), () -> runOnUiThread(() -> {
            switchingProxy = false; proxyApplied = false; dnsEnabled = false; savePreference("dns_enabled", false);
            if (localProxy != null) localProxy.close();
            localProxy = null; resolver = null; emit("dns", getStartupState().toString());
        }));
    }

    private void testDoh() {
        background.execute(() -> {
            long start = System.currentTimeMillis();
            try {
                CloudflareDohResolver test = resolver != null ? resolver : new CloudflareDohResolver(preferSecondary);
                CloudflareDohResolver.DnsResult result = test.resolve("cloudflare.com");
                JSONObject report = new JSONObject();
                long latency = System.currentTimeMillis() - start;
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("doh_reachable", true)
                        .putBoolean("dns_query_succeeded", true).putLong("dns_success_at", System.currentTimeMillis())
                        .putLong("dns_latency_ms", latency).apply();
                report.put("ok", true); report.put("latencyMs", latency);
                report.put("addresses", new JSONArray(result.addresses)); report.put("resolver", result.resolver);
                report.put("cache", result.fromCache); report.put("ttl", result.ttlSeconds);
                emit("dnsTest", report.toString());
            } catch (Exception error) {
                JSONObject report = new JSONObject();
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("doh_reachable", false)
                        .putBoolean("dns_query_succeeded", false).apply();
                try { report.put("ok", false); report.put("error", safeMessage(error)); }
                catch (JSONException ignored) { }
                emit("dnsTest", report.toString());
            }
        });
    }

    private void restoreDownloadHistory() {
        JSONArray stored;
        try { stored = new JSONArray(getSharedPreferences(PREFS, MODE_PRIVATE).getString("downloads", "[]")); }
        catch (JSONException error) { return; }
        File directory = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (directory == null) directory = new File(getFilesDir(), "downloads");
        for (int index = 0; index < stored.length() && index < 100; index++) {
            JSONObject row = stored.optJSONObject(index);
            if (row == null || row.optBoolean("private")) continue;
            long id = row.optLong("id");
            if (id <= 0) continue;
            DownloadRecord record = new DownloadRecord(id, row.optString("url"), row.optString("mime", "application/octet-stream"), false, false, null);
            record.state = row.optString("state", "error");
            if ("active".equals(record.state)) { record.state = "error"; record.error = "Unduhan terhenti saat aplikasi ditutup."; }
            else record.error = row.optString("error", "");
            record.progress = row.optInt("progress", 0);
            record.bytesRead = row.optLong("bytes", 0);
            String path = row.optString("path", "");
            if (!path.isEmpty()) {
                try {
                    File file = new File(path).getCanonicalFile();
                    if (file.getPath().startsWith(directory.getCanonicalPath() + File.separator) && file.isFile()) record.file = file;
                } catch (IOException ignored) { }
            }
            if ("done".equals(record.state) && record.file == null) { record.state = "error"; record.error = "File unduhan tidak ditemukan."; }
            synchronized (downloads) { downloads.put(id, record); }
            nextDownloadId.set(Math.max(nextDownloadId.get(), id + 1));
        }
    }

    private void startDownload(BrowserTab tab, String url, String userAgent, String disposition, String mime) {
        Uri uri = UrlPolicy.parseWebUrl(url);
        if (uri == null) { notifyUi("downloadError", "Alamat unduhan tidak valid."); return; }
        DownloadRecord record = new DownloadRecord(nextDownloadId.getAndIncrement(), url, mime, tab.privateTab,
                dnsEnabled, dnsEnabled ? resolver : null);
        synchronized (downloads) { downloads.put(record.id, record); }
        emitDownloads();
        background.execute(() -> downloadFile(tab, record, userAgent, disposition));
    }

    private void downloadFile(BrowserTab tab, DownloadRecord record, String userAgent, String disposition) {
        HttpURLConnection connection = null;
        try {
            Uri uri = UrlPolicy.parseWebUrl(record.url);
            if (uri == null) throw new IOException("Invalid download URL");
            String requestUrl = uri.toString();
            int status = -1;
            for (int redirect = 0; redirect <= 8; redirect++) {
                uri = UrlPolicy.parseWebUrl(requestUrl);
                if (uri == null) throw new IOException("Invalid download redirect");
                URL url = new URL(uri.toString());
                if (record.requiresDnsProxy) {
                    int port = ensureDownloadProxy(record.dohResolver);
                    connection = (HttpURLConnection) url.openConnection(new Proxy(Proxy.Type.HTTP,
                            new InetSocketAddress("127.0.0.1", port)));
                } else if (!dnsEnabled) connection = (HttpURLConnection) url.openConnection();
                else throw new IOException("Cloudflare proxy is not ready; download stopped to prevent resolver fallback");
                connection.setConnectTimeout(12000); connection.setReadTimeout(20000); connection.setInstanceFollowRedirects(false);
                if (userAgent != null) connection.setRequestProperty("User-Agent", userAgent);
                String cookie = cookieForTab(tab, uri.toString());
                if (cookie != null && !cookie.isEmpty()) connection.setRequestProperty("Cookie", cookie);
                connection.connect();
                status = connection.getResponseCode();
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    String location = connection.getHeaderField("Location");
                    connection.disconnect(); connection = null;
                    if (location == null || redirect == 8) throw new IOException("Download redirect limit exceeded");
                    URL next = new URL(url, location);
                    Uri redirected = UrlPolicy.parseWebUrl(next.toString());
                    if (redirected == null) throw new IOException("Unsafe download redirect");
                    if ("https".equalsIgnoreCase(uri.getScheme()) && "http".equalsIgnoreCase(redirected.getScheme()))
                        throw new IOException("HTTPS download redirect to HTTP was blocked");
                    requestUrl = redirected.toString();
                    continue;
                }
                break;
            }
            if (status < 200 || status >= 300) throw new IOException("Server returned HTTP " + status);
            long total;
            try { total = Long.parseLong(connection.getHeaderField("Content-Length")); }
            catch (RuntimeException error) { total = -1; }
            if (total > 200L * 1024 * 1024) throw new IOException("Unduhan melebihi batas 200 MiB");
            File directory = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            if (directory == null) directory = new File(getFilesDir(), "downloads");
            if (!directory.exists() && !directory.mkdirs()) throw new IOException("Download folder could not be created");
            record.file = uniqueFile(directory, safeFilename(disposition, uri.getLastPathSegment()));
            try (InputStream input = connection.getInputStream(); FileOutputStream output = new FileOutputStream(record.file)) {
                byte[] buffer = new byte[16384]; int count; long read = 0, lastProgressUpdate = 0;
                while ((count = input.read(buffer)) >= 0) {
                    if (record.cancelled.get()) throw new IOException("Download cancelled");
                    read += count;
                    if (read > 200L * 1024 * 1024) throw new IOException("Unduhan melebihi batas 200 MiB");
                    output.write(buffer, 0, count); record.bytesRead = read;
                    record.progress = total > 0 ? (int) Math.min(99, read * 100 / total) : -1;
                    long now = System.currentTimeMillis();
                    if (now - lastProgressUpdate >= 500) { emitDownloads(); lastProgressUpdate = now; }
                }
            }
            record.progress = 100; record.state = "done";
        } catch (Exception error) {
            record.state = record.cancelled.get() ? "cancelled" : "error"; record.error = safeMessage(error);
            if (record.file != null && record.file.exists()) record.file.delete();
        } finally {
            if (connection != null) connection.disconnect();
            emitDownloads();
            closeDownloadProxyIfIdle();
        }
    }

    private synchronized int ensureDownloadProxy(CloudflareDohResolver source) throws IOException {
        if (source == null) throw new IOException("Cloudflare resolver is not ready; download stopped");
        if (downloadProxy == null || !downloadProxy.isRunning()) {
            downloadProxy = new LocalHttpProxy(source);
            downloadProxy.start();
        }
        return downloadProxy.getPort();
    }

    private synchronized void closeDownloadProxyIfIdle() {
        synchronized (downloads) {
            for (DownloadRecord record : downloads.values()) if ("active".equals(record.state)) return;
        }
        if (downloadProxy != null) downloadProxy.close();
        downloadProxy = null;
    }

    private String cookieForTab(BrowserTab tab, String url) {
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) return WebViewCompat.getProfile(tab.webView).getCookieManager().getCookie(url);
        } catch (RuntimeException ignored) { }
        return CookieManager.getInstance().getCookie(url);
    }

    private static String safeFilename(String disposition, String path) {
        String name = null;
        if (disposition != null) {
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("filename\\*?=(?:UTF-8''|\")?([^;\"]+)", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(disposition);
            if (matcher.find()) name = matcher.group(1).trim();
        }
        if (name == null || name.isEmpty()) name = path;
        if (name == null || name.isEmpty()) name = "download";
        name = name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").replace("..", "_").trim();
        if (name.isEmpty()) name = "download";
        return name.length() > 120 ? name.substring(name.length() - 120) : name;
    }

    private static File uniqueFile(File directory, String name) throws IOException {
        File file = new File(directory, name);
        if (!file.getCanonicalPath().startsWith(directory.getCanonicalPath() + File.separator)) throw new IOException("Invalid download path");
        if (!file.exists()) return file;
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name, extension = dot > 0 ? name.substring(dot) : "";
        for (int index = 1; index < 1000; index++) {
            file = new File(directory, stem + " (" + index + ")" + extension);
            if (!file.exists()) return file;
        }
        throw new IOException("Too many files with that name");
    }

    private void openDownload(long id, boolean share) {
        DownloadRecord record;
        synchronized (downloads) { record = downloads.get(id); }
        if (record == null || record.file == null || !record.file.isFile()) { notifyUi("downloadError", "File unduhan tidak ditemukan."); return; }
        try {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".downloads", record.file);
            Intent intent = new Intent(share ? Intent.ACTION_SEND : Intent.ACTION_VIEW);
            intent.setDataAndType(uri, record.mime);
            if (share) intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, share ? "Bagikan file" : "Buka file"));
        } catch (Exception error) { notifyUi("downloadError", "Tidak ada aplikasi untuk membuka file ini."); }
    }

    private void emitDownloads() {
        JSONArray rows = new JSONArray();
        JSONArray persisted = new JSONArray();
        synchronized (downloads) {
            for (DownloadRecord record : downloads.values()) {
                JSONObject row = new JSONObject();
                try {
                    row.put("id", record.id); row.put("url", record.url); row.put("mime", record.mime);
                    row.put("name", record.file == null ? "Menyiapkan unduhan" : record.file.getName());
                    row.put("progress", record.progress); row.put("state", record.state); row.put("error", record.error);
                    row.put("private", record.privateDownload); row.put("bytes", record.bytesRead);
                    if (record.file != null) row.put("path", record.file.getAbsolutePath());
                    rows.put(row);
                    if (!record.privateDownload) persisted.put(row);
                } catch (JSONException ignored) { }
            }
        }
        JSONArray bounded = new JSONArray();
        for (int index = Math.max(0, persisted.length() - 100); index < persisted.length(); index++) bounded.put(persisted.opt(index));
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("downloads", bounded.toString()).apply();
        emit("downloads", rows.toString());
    }

    private void clearBrowsingData() {
        for (BrowserTab tab : tabs.values()) {
            tab.webView.clearCache(true); tab.webView.clearFormData(); tab.webView.clearHistory();
            try {
                if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
                    WebViewCompat.getProfile(tab.webView).getWebStorage().deleteAllData();
                    WebViewCompat.getProfile(tab.webView).getCookieManager().removeAllCookies(null);
                }
            } catch (RuntimeException ignored) { }
        }
        CookieManager.getInstance().removeAllCookies(null);
        WebStorage storage = WebStorage.getInstance();
        storage.getOrigins(origins -> {
            for (Object key : origins.keySet()) {
                if (key instanceof String && !APP_ORIGIN.equals(key)) storage.deleteOrigin((String) key);
            }
            emit("dataCleared", "{}");
        });
        if (resolver != null) resolver.clearCache();
    }

    private void savePreference(String key, boolean value) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(key, value).apply();
    }

    private void applySystemBars(boolean dark) {
        int surface = dark ? Color.rgb(25, 25, 27) : Color.rgb(245, 241, 233);
        int foreground = dark ? Color.rgb(241, 238, 231) : Color.rgb(41, 42, 40);
        if (root != null) root.setBackgroundColor(surface);
        if (pageHost != null) pageHost.setBackgroundColor(surface);
        if (errorPanel != null) { errorPanel.setBackgroundColor(surface); errorPanel.setTextColor(foreground); }
        boolean lightStatusBar = !dark && Build.VERSION.SDK_INT >= 23;
        boolean lightNavigationBar = !dark && Build.VERSION.SDK_INT >= 26;
        getWindow().setStatusBarColor(dark || lightStatusBar ? surface : foreground);
        getWindow().setNavigationBarColor(dark || lightNavigationBar ? surface : foreground);
        int flags = 0;
        if (lightStatusBar) flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        if (lightNavigationBar) flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        getWindow().getDecorView().setSystemUiVisibility(flags);
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty() ? error.getClass().getSimpleName() : message;
    }

    @Override public void onBackPressed() {
        if (!"home".equals(shellScreen) && !"browser".equals(shellScreen)) {
            chrome.evaluateJavascript("window.CFApp&&window.CFApp.handleBack()", null);
            return;
        }
        if ("browser".equals(shellScreen)) {
            if (activeTab != null && activeTab.webView.canGoBack()) { activeTab.webView.goBack(); return; }
            shellScreen = "home";
            chrome.evaluateJavascript("window.CFApp&&window.CFApp.showHome()", null);
            showHome();
            return;
        }
        super.onBackPressed();
    }

    @Override protected void onPause() {
        super.onPause(); chrome.onPause();
        for (BrowserTab tab : tabs.values()) tab.webView.onPause();
    }

    @Override protected void onResume() {
        super.onResume(); chrome.onResume();
        for (BrowserTab tab : tabs.values()) tab.webView.onResume();
    }

    @Override protected void onDestroy() {
        activityDestroyed = true;
        LocalHttpProxy proxyToClose = localProxy;
        if (proxySupported) {
            try {
                ProxyController.getInstance().clearProxyOverride(Runnable::run, () -> {
                    if (proxyToClose != null) proxyToClose.close();
                });
            } catch (RuntimeException ignored) {
                if (proxyToClose != null) proxyToClose.close();
            }
        } else if (proxyToClose != null) {
            proxyToClose.close();
        }
        for (BrowserTab tab : tabs.values()) { tab.webView.stopLoading(); tab.webView.destroy(); }
        if (privateProfileName != null) deletePrivateProfile();
        if (downloadProxy != null) downloadProxy.close();
        background.shutdownNow();
        super.onDestroy();
    }

    private final class SiteClient extends WebViewClient {
        private final BrowserTab tab;
        SiteClient(BrowserTab tab) { this.tab = tab; }

        @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();
            String scheme = uri.getScheme();
            if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
                if (uri.getHost() == null || uri.getUserInfo() != null) return true;
                if (dnsEnabled && (!proxyApplied || localProxy == null || !localProxy.isRunning())) {
                    notifyUi("dnsBlocked", "Jalur Cloudflare DNS tidak siap, navigasi dihentikan."); return true;
                }
                return false;
            }
            if ("mailto".equalsIgnoreCase(scheme) || "tel".equalsIgnoreCase(scheme)) {
                try { startActivity(Intent.createChooser(new Intent(Intent.ACTION_VIEW, uri), "Buka dengan")); }
                catch (Exception ignored) { notifyUi("error", "Tidak ada aplikasi untuk tautan ini."); }
            }
            return true;
        }

        @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
            tab.url = url; tab.progress = 0; tab.tlsValidated = false;
            sendPageState(tab); publishTabs();
        }

        @Override public void onPageFinished(WebView view, String url) {
            tab.url = url;
            tab.tlsValidated = url != null && url.startsWith("https://");
            if (proxyApplied && localProxy != null && localProxy.getRequestCount() > tab.requestCountAtNavigation) tab.webViewProxyObserved = true;
            sendPageState(tab); publishTabs(); persistSession();
            if (!tab.privateTab && historyEnabled) {
                JSONObject visit = new JSONObject();
                try { visit.put("url", url); visit.put("title", tab.title); visit.put("time", System.currentTimeMillis()); }
                catch (JSONException ignored) { }
                emit("visit", visit.toString());
            }
        }

        @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            if (request.isForMainFrame() && activeTab == tab) showError("Halaman tidak dapat dibuka",
                    error == null ? "Periksa alamat atau koneksi, lalu coba lagi." : String.valueOf(error.getDescription()));
        }

        @Override public void onReceivedSslError(WebView view, SslErrorHandler handler, android.net.http.SslError error) {
            handler.cancel();
            if (activeTab == tab) showError("Sertifikat situs tidak valid", "Koneksi dihentikan. Pemeriksaan sertifikat TLS tetap aktif.");
        }
    }

    private void showError(String title, String detail) {
        runOnUiThread(() -> {
            errorPanel.setText(title + "\n\n" + detail + "\n\nPeriksa alamat atau koneksi, lalu coba lagi.");
            if (activeTab == null) return;
            errorPanel.setVisibility(View.VISIBLE);
            activeTab.webView.setVisibility(View.GONE);
            emit("pageError", JSONObject.quote(detail));
        });
    }

    private final class SiteChromeClient extends WebChromeClient {
        private final BrowserTab tab;
        SiteChromeClient(BrowserTab tab) { this.tab = tab; }
        @Override public void onProgressChanged(WebView view, int progress) {
            tab.progress = progress; sendPageState(tab); publishTabs();
        }
        @Override public void onReceivedTitle(WebView view, String title) {
            if (title != null && !title.trim().isEmpty()) tab.title = title.trim();
            sendPageState(tab); publishTabs();
        }
        @Override public boolean onCreateWindow(WebView view, boolean isDialog, boolean userGesture, android.os.Message resultMsg) {
            if (!popupsEnabled || !userGesture) return false;
            BrowserTab newTab = createTab(tab.privateTab, "");
            if (newTab == null) return false;
            android.webkit.WebView.WebViewTransport transport = (android.webkit.WebView.WebViewTransport) resultMsg.obj;
            transport.setWebView(newTab.webView); resultMsg.sendToTarget(); return true;
        }
    }

    private final class BrowserDownloadListener implements DownloadListener {
        private final BrowserTab tab;
        BrowserDownloadListener(BrowserTab tab) { this.tab = tab; }
        @Override public void onDownloadStart(String url, String userAgent, String disposition, String mime, long size) {
            startDownload(tab, url, userAgent, disposition, mime == null ? "application/octet-stream" : mime);
        }
    }

    private final class NativeBridge {
        @android.webkit.JavascriptInterface public String initialState() { return getStartupState().toString(); }
        @android.webkit.JavascriptInterface public void call(String action, String payload) {
            if (action == null || action.length() > 80 || (payload != null && payload.length() > 2 * 1024 * 1024)) {
                notifyUi("error", "Permintaan aplikasi melebihi batas yang diizinkan.");
                return;
            }
            JSONObject args;
            try { args = new JSONObject(payload == null ? "{}" : payload); }
            catch (JSONException error) { notifyUi("error", "Permintaan aplikasi tidak valid."); return; }
            runOnUiThread(() -> handleAction(action, args));
        }
    }

    private void handleAction(String action, JSONObject args) {
        if (action == null) return;
        switch (action) {
            case "open": {
                String raw = args.optString("url", "").trim();
                Uri uri = UrlPolicy.parseWebUrl(raw);
                if (uri == null) {
                    if (raw.isEmpty()) return;
                    String engine = getSharedPreferences(PREFS, MODE_PRIVATE).getString("search_engine", "duckduckgo");
                    if (raw.contains(" ") || raw.indexOf('.') < 0) raw = UrlPolicy.searchUrl(raw, engine);
                    else { notifyUi("error", "Alamat tidak valid."); return; }
                } else raw = uri.toString();
                if (activeTab == null) createTab(false, raw); else navigate(activeTab, raw);
                break;
            }
            case "newTab": createTab(args.optBoolean("private", false), args.optString("url", "")); break;
            case "closeTab": closeTab(args.optString("id", activeTab == null ? "" : activeTab.id)); break;
            case "selectTab": selectTab(args.optString("id", "")); break;
            case "duplicateTab": if (activeTab != null) createTab(activeTab.privateTab, activeTab.url); break;
            case "home": shellScreen = "home"; showHome(); break;
            case "shellScreen": {
                shellScreen = args.optString("name", "home");
                if ("browser".equals(shellScreen)) showBrowserMode(); else showHome();
                break;
            }
            case "back":
                if (activeTab != null && activeTab.webView.canGoBack()) activeTab.webView.goBack();
                else { shellScreen = "home"; showHome(); emit("navigateHome", "{}"); }
                break;
            case "forward": if (activeTab != null && activeTab.webView.canGoForward()) activeTab.webView.goForward(); break;
            case "reload": if (activeTab != null) { errorPanel.setVisibility(View.GONE); activeTab.webView.setVisibility(View.VISIBLE); activeTab.webView.reload(); } break;
            case "stop": if (activeTab != null) activeTab.webView.stopLoading(); break;
            case "toggleDns": if (args.optBoolean("enabled")) enableDns(); else disableDns(); break;
            case "testDns": testDoh(); break;
            case "clearDnsCache":
                if (resolver != null) resolver.clearCache();
                if (downloadProxy != null) downloadProxy.clearDnsCache();
                emit("dns", getStartupState().toString());
                break;
            case "preferSecondary": changeSecondary(args.optBoolean("enabled")); break;
            case "historyEnabled": historyEnabled = args.optBoolean("enabled"); savePreference("history_enabled", historyEnabled); emit("settings", getStartupState().toString()); break;
            case "javascriptEnabled":
                javascriptEnabled = args.optBoolean("enabled"); savePreference("javascript_enabled", javascriptEnabled);
                for (BrowserTab tab : tabs.values()) tab.webView.getSettings().setJavaScriptEnabled(javascriptEnabled);
                emit("settings", getStartupState().toString()); break;
            case "popupsEnabled":
                popupsEnabled = args.optBoolean("enabled"); savePreference("popups_enabled", popupsEnabled);
                for (BrowserTab tab : tabs.values()) { tab.webView.getSettings().setJavaScriptCanOpenWindowsAutomatically(popupsEnabled); tab.webView.getSettings().setSupportMultipleWindows(popupsEnabled); }
                emit("settings", getStartupState().toString()); break;
            case "thirdPartyCookies":
                thirdPartyCookies = args.optBoolean("enabled"); savePreference("third_party_cookies", thirdPartyCookies);
                for (BrowserTab tab : tabs.values()) {
                    try {
                        if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) WebViewCompat.getProfile(tab.webView).getCookieManager().setAcceptThirdPartyCookies(tab.webView, !tab.privateTab && thirdPartyCookies);
                        else CookieManager.getInstance().setAcceptThirdPartyCookies(tab.webView, !tab.privateTab && thirdPartyCookies);
                    } catch (RuntimeException ignored) { }
                }
                emit("settings", getStartupState().toString()); break;
            case "textZoom": {
                int zoom = Math.max(75, Math.min(150, args.optInt("value", 100)));
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt("text_zoom", zoom).apply();
                for (BrowserTab tab : tabs.values()) tab.webView.getSettings().setTextZoom(zoom);
                break;
            }
            case "reducedMotion": savePreference("reduced_motion", args.optBoolean("enabled")); break;
            case "setHomepage": {
                String raw = args.optString("url", "").trim();
                Uri uri = raw.isEmpty() ? null : UrlPolicy.parseWebUrl(raw);
                if (!raw.isEmpty() && uri == null) { notifyUi("error", "Homepage harus berupa alamat HTTP atau HTTPS."); break; }
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("homepage", uri == null ? "" : uri.toString()).apply();
                emit("settings", getStartupState().toString());
                break;
            }
            case "restoreSession": restoreSession = args.optBoolean("enabled"); savePreference("restore_session", restoreSession); persistSession(); emit("settings", getStartupState().toString()); break;
            case "clearData": clearBrowsingData(); break;
            case "listTabs": publishTabs(); break;
            case "listDownloads": emitDownloads(); break;
            case "cancelDownload": {
                DownloadRecord record;
                synchronized (downloads) { record = downloads.get(args.optLong("id")); }
                if (record != null && "active".equals(record.state)) record.cancelled.set(true);
                break;
            }
            case "retryDownload": {
                DownloadRecord record;
                synchronized (downloads) { record = downloads.get(args.optLong("id")); }
                if (record != null && "error".equals(record.state) && activeTab != null) startDownload(activeTab, record.url, null, null, record.mime);
                break;
            }
            case "openDownload": openDownload(args.optLong("id"), false); break;
            case "shareDownload": openDownload(args.optLong("id"), true); break;
            case "setTheme": applySystemBars("dark".equals(args.optString("theme"))); break;
            case "bookmarkCurrent": {
                if (activeTab != null) {
                    JSONObject current = new JSONObject();
                    try { current.put("url", activeTab.url); current.put("title", activeTab.title); emit("currentBookmark", current.toString()); }
                    catch (JSONException ignored) { }
                }
                break;
            }
            case "exportBookmarks": {
                String json = args.optString("json", "[]");
                if (json.length() > 2 * 1024 * 1024) { notifyUi("error", "Data bookmark terlalu besar untuk dibagikan."); break; }
                Intent intent = new Intent(Intent.ACTION_SEND);
                intent.setType("text/plain");
                intent.putExtra(Intent.EXTRA_SUBJECT, "Bookmark Cloudflare Browser (JSON)");
                intent.putExtra(Intent.EXTRA_TEXT, json);
                startActivity(Intent.createChooser(intent, "Ekspor bookmark"));
                break;
            }
            case "setSearchEngine": {
                String engine = args.optString("engine", "duckduckgo");
                if (!engine.equals("duckduckgo") && !engine.equals("google") && !engine.equals("bing")) engine = "duckduckgo";
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("search_engine", engine).apply();
                break;
            }
            case "diagnostics": emit("dns", getStartupState().toString()); break;
            default: notifyUi("error", "Aksi tidak dikenal.");
        }
    }

    private static final class DownloadRecord {
        final long id;
        final String url, mime;
        final boolean privateDownload, requiresDnsProxy;
        final CloudflareDohResolver dohResolver;
        final AtomicBoolean cancelled = new AtomicBoolean();
        volatile File file;
        volatile int progress;
        volatile long bytesRead;
        volatile String state = "active", error = "";
        DownloadRecord(long id, String url, String mime, boolean privateDownload, boolean requiresDnsProxy, CloudflareDohResolver resolver) {
            this.id = id; this.url = url; this.mime = mime; this.privateDownload = privateDownload;
            this.requiresDnsProxy = requiresDnsProxy; this.dohResolver = resolver;
        }
    }
}
