package com.cloudflare.browser.security;

import android.net.Uri;
import java.net.IDN;
import java.util.Locale;

public final class UrlPolicy {
    private UrlPolicy() { }

    public static Uri parseWebUrl(String value) {
        if (value == null) return null;
        String candidate = value.trim();
        if (candidate.isEmpty() || candidate.length() > 8192 || candidate.indexOf('\n') >= 0 || candidate.indexOf('\r') >= 0 || candidate.indexOf('\u0000') >= 0) return null;
        Uri parsed = Uri.parse(candidate);
        String scheme = parsed.getScheme();
        boolean schemeAdded = false;
        if (scheme == null) {
            schemeAdded = true;
            for (int index = 0; index < candidate.length(); index++) if (Character.isWhitespace(candidate.charAt(index))) return null;
            parsed = Uri.parse("https://" + candidate);
            scheme = parsed.getScheme();
        }
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) return null;
        if (parsed.getUserInfo() != null) return null;
        String host = parsed.getHost();
        if (host == null || host.isEmpty()) return null;
        // Typed text without a scheme and without a dot or colon (e.g. "pulau88") is a search query, not a hostname.
        if (schemeAdded && host.indexOf('.') < 0 && host.indexOf(':') < 0) return null;
        try { IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES); }
        catch (IllegalArgumentException error) { return null; }
        return parsed;
    }

    public static String searchUrl(String query, String engine) {
        String encoded = Uri.encode(query == null ? "" : query.trim());
        String selected = engine == null ? "duckduckgo" : engine.toLowerCase(Locale.ROOT);
        if ("google".equals(selected)) return "https://www.google.com/search?q=" + encoded;
        if ("bing".equals(selected)) return "https://www.bing.com/search?q=" + encoded;
        return "https://duckduckgo.com/?q=" + encoded;
    }
}
