# Architecture

## Runtime

`MainActivity` applies system-bar insets for Android 15 edge-to-edge behavior and owns the native frame, the authored app shell WebView, site WebViews, tabs, downloads, and feature state. The shell is loaded from AndroidX `WebViewAssetLoader` at `https://appassets.androidplatform.net/assets/browser/`. Its narrow `Native` bridge only exists on this packaged, authored shell. Site WebViews have no JavaScript bridge, deny file/content URL access, disallow mixed content, and cancel certificate errors.

The shell calls typed actions and receives JSON events. It uses `textContent` for site-derived title/URL strings and stores user bookmarks/history/preferences locally. The shell does not load third-party scripts, fonts, or image assets.

## Optional DNS route

When explicitly enabled and supported at runtime:

1. `CloudflareDohResolver` connects to the literal Cloudflare resolver IPv4 address on port 443, with TLS SNI and certificate hostname validation for `cloudflare-dns.com`.
2. It POSTs a DNS wire-format message with `Content-Type` and `Accept: application/dns-message` to `/dns-query`, parses A and AAAA answers, and caches addresses for the minimum positive answer TTL.
3. `LocalHttpProxy` binds to `127.0.0.1` on an ephemeral port, accepts bounded concurrent loopback clients, resolves hostnames using the DoH component, blocks local/reserved destinations, and supports HTTP proxy requests plus raw HTTPS `CONNECT` tunnels.
4. AndroidX `ProxyController` receives an explicit proxy rule after `removeImplicitRules()`. The app waits for its callback before marking override applied or allowing navigation when the setting is enabled.
5. CONNECT traffic passes through without TLS interception. WebView retains normal certificate validation.

The resolver diagnostic, override callback, proxy running state, and proxy request counters are distinct statuses. A counter increase during a page load is evidence of observed proxy traffic, not proof that every WebView protocol, WebSocket, service worker, or request path is covered. WebView proxy override is process-wide. The feature affects this app's WebViews, not Android or other applications. If the runtime feature is unavailable or startup fails, DNS remains off and the UI reports the condition; there is no transparent direct fallback.

Downloads use a separate loopback proxy instance while DNS mode is enabled, so download requests cannot be mistaken for observed WebView traffic. Each redirect re-resolves cookies for its destination, is capped at eight hops, and HTTPS-to-HTTP downgrades are blocked. A download started in DNS mode keeps its DoH route even if settings change during the transfer. In DNS-off mode downloads use Android's normal networking stack.

## Private tabs

Private tabs use a separate AndroidX WebView profile only when `MULTI_PROFILE` is present. Their URL is excluded from session restore and visit history; profile cookies/storage are cleared when the last private tab closes. If unsupported, private-tab creation is rejected. Device WebView profile deletion is asynchronous and is not a guarantee of physical media erasure.

## Limits / release blockers

The HTTP proxy accepts bounded chunked request bodies (up to 64 MiB), rejects ambiguous Content-Length plus Transfer-Encoding framing, and relays WebSocket upgrades and CONNECT tunnels bidirectionally. These paths have test sources, but those tests and the Android build have not yet run in this environment. The CONNECT test checks opaque byte forwarding, not a real TLS handshake. Do not describe this implementation as complete DNS protection or release-ready until GitHub Actions and the PRD network matrix pass on Android devices.
