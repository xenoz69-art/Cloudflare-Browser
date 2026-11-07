# Cloudflare Browser (Android)

A lightweight Android browser prototype built with Java, Android System WebView, and an asset-hosted HTML/CSS/JavaScript interface. Cloudflare DNS-over-HTTPS is optional and routes through an app-local HTTP proxy only when WebView proxy override is supported and applied.

## Build

Requirements: JDK 17, Android SDK platform 35, Android build tools, and Gradle 8.9 or compatible with Android Gradle Plugin 8.7.3. This repository does not currently contain a Gradle Wrapper; use Android Studio or an installed Gradle 8.9 (`gradle assembleDebug`) after provisioning the Android SDK. Minimum Android API is 21; current target API is 35.

The current Termux environment has no JDK, Gradle, Android SDK, adb or emulator, so no APK is included and build/device verification is pending. See `TESTING_REPORT.md` before treating this prototype as a release build.

## Product and security notes

Read `PRD.md`, `ARCHITECTURE.md`, `DESIGN_SYSTEM.md`, `SKILLS_AUDIT.md`, `IMPLEMENTATION_PLAN.md`, and `SECURITY_REPORT.md`. DNS mode is opt-in. If the WebView proxy feature is unavailable, it is reported unsupported. When DNS mode is requested, failed startup blocks navigation instead of silently using the system resolver. The indicator reports component status and observed proxy requests; it does not promise complete coverage of all WebView protocols.

## Checks

Run local static checks with `node tools/check_ui.mjs`, `node tools/check_contrast.mjs`, and `python tools/check_project.py`. GitHub Actions runs the JVM tests, builds the debug APK, uploads it as an artifact, and runs WebView instrumentation on an API 35 emulator. The workflow result is required before treating those tests as passed; it does not replace physical-device checks.
