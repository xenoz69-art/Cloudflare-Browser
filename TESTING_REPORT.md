# Testing report

## Environment

Audited in Termux on Android 15 / API 35. Available: Node.js 26.13.0, npm 12.2.2, Python 3.14.10, Git 2.56, curl. Missing: Java/JDK, Gradle, Android SDK/platform tools, `adb`, emulator, Chrome/Chromium, and the `belt` agent-browser command. Native compilation and Android execution cannot run locally.

## Checks run in this workspace

- `node tools/check_ui.mjs` — passed: JavaScript syntax, required screens/actions, dark theme/reduced-motion declarations, and no remote scripts/images.
- `node tools/check_contrast.mjs` — passed: minimum tested contrast ratio 4.78:1.
- `python tools/check_project.py` — passed: Android XML parsing, UI ID references, and delimiter structure for main, unit-test, and instrumentation Java sources.
- Python XML parsing of Android manifest and resource XML — passed.
- `node --check app/src/main/assets/browser/app.js` — passed.

These are static checks only. The Java/JUnit source tests added in this change have **not** been compiled or run in Termux.

## Automated tests configured for GitHub Actions (not yet executed)

- `gradle testDebugUnitTest assembleDebug` builds the debug APK and runs JVM tests.
- `gradle connectedDebugAndroidTest` on an API 35 Google APIs emulator runs an Android WebView integration test. It checks HTTP and WebSocket traversal via `ProxyController`, fake DoH hostname resolution, WebSocket echo, and proxy tunnel counters. It skips if that emulator's WebView lacks proxy override.
- The workflow uploads the debug APK as an artifact after the build job succeeds.

Unit-test sources cover DoH transport fallback and cache TTL, SERVFAIL versus NXDOMAIN, malformed response IDs, HTTP chunked framing/trailers, WebSocket handshake/frame relay, opaque CONNECT byte relay, request smuggling rejection, and destination address filtering. Source presence is not evidence that these tests pass; check the GitHub Actions run before relying on them.

## Still unverified

- Java/Gradle compilation, dependency resolution, APK packaging/install, Android lint, and all JUnit/instrumented test outcomes.
- Actual TLS handshake and HTTPS page rendering through CONNECT. The local CONNECT unit test relays TLS-like opaque bytes; it does not perform a TLS handshake.
- WebView proxy behavior and WebSocket support on physical devices or Android versions outside the CI emulator.
- DNS fallback over the real network, resolver timeouts, DNS-leak behavior under device VPN/private DNS settings, and complete WebView traffic coverage.
- UI rendering, accessibility, and interaction matrix in a real browser.

No feature is marked successful based solely on an unexecuted test. This remains an unbuilt prototype until CI and device validation complete.
