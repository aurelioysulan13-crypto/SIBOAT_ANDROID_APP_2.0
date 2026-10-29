# SIBOAT Android

This project packages the SIBOAT HTML dashboard as an Android application using a native Android WebView container.

## Main dashboard

The dashboard is bundled at:

`app/src/main/assets/index.html`

The Android container provides:

- JavaScript and DOM storage support
- local HTTP access for ESP32 communication
- WebSocket support for the ESP32 telemetry/control path
- Android camera permission handling
- Android file-picker support for HTML file inputs
- Android USB serial support through `SIBOAT_ANDROID_USB`
- Android BLE support through `SIBOAT_ANDROID_BLE`

## Important

The supplied HTML references `vendor/supabase.min.js` and then falls back to the Supabase CDN. No unverified Supabase library copy is invented or bundled here. Internet access is therefore required for the CDN fallback when the local vendor file is absent.

The BLE bridge uses the UUIDs already present in the supplied dashboard. The physical ESP32 firmware must actually implement that BLE service and characteristics for Bluetooth control to work.

## GitHub Actions

The workflow in `.github/workflows/build-apk.yml` builds `app-debug.apk` with Java 17, Gradle 8.9, and Android Gradle Plugin 8.7.3. The resulting APK is uploaded as the `SIBOAT-debug-apk` artifact.
