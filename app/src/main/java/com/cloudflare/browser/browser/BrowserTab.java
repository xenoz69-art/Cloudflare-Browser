package com.cloudflare.browser.browser;

import android.webkit.WebView;

public final class BrowserTab {
    public final String id;
    public final boolean privateTab;
    public final String profileName;
    public WebView webView;
    public String url = "";
    public String title = "Tab baru";
    public int progress;
    public long requestCountAtNavigation;
    public boolean webViewProxyObserved;
    public boolean tlsValidated;

    public BrowserTab(String id, boolean privateTab, String profileName) {
        this.id = id;
        this.privateTab = privateTab;
        this.profileName = profileName;
    }
}
