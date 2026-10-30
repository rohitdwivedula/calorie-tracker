# Label Audit — Android field-test app

An intentionally small Android app for testing product lookup and raw nutrition-label OCR. No nutrient parsing, calorie diary, account, or API key is required.

## Install and test

1. Install `LabelAudit.apk` on an ARM64 Android phone (Android 8+). Android may ask you to allow installation from the app used to open the APK. This is a debug-signed test build, not a Play Store release.
2. Open **Label Audit**, and grant camera permission.
3. Optionally enter a sample name such as `tomatoes`. Keep that name for the matching barcode and label photo.
4. Tap **Scan barcode / QR code**. Aim at a UPC/EAN grocery barcode. It captures automatically, saves an original barcode photo, and queries Open Food Facts. Numeric QR codes, Open Food Facts product links, and GS1 Digital Links are supported. Arbitrary QR links are saved as unsupported; the app does not visit them.
5. Tap **Photograph nutrition label**. Tap the preview to focus, then **Take label photo**. OCR runs on-device and saves its raw output in SQLite. The original camera JPEG is retained unchanged. Here “original” means the camera's JPEG, not sensor RAW/DNG.
6. Repeat for 5–10 products. Tap any saved record to review its result and open its photo.
7. Tap **Export logs (.tar.gz)** and choose a location in Android's file picker. Alternatively use **Share last export** after making the export. Attach the archive to the conversation for an audit.

The archive is a snapshot: export again after capturing more samples. Export never clears records. Do not uninstall or clear app storage before exporting; doing so removes the local database and photos. Cloud backup is disabled.

## Data collected

- Barcode: raw decoded string, ML Kit format, sample name, timestamp, optional original capture, extracted product code, exact request URL/method, HTTP status/headers, complete decoded UTF-8 response body, elapsed time, and failures.
- OCR: original JPEG, sample name, raw text, text blocks, lines, elements, bounding boxes, corner points, available line/element confidence and angle, recognized language, input dimensions/rotation, timing, and failures. No nutrition fields are inferred.
- Export manifest: app version, Android version, device model, counts, sizes and SHA-256 checksums. No advertising ID, location, account information, or gallery access is collected by app code. Bundled Google SDKs remain governed by Google's SDK terms.
- Data is stored in app-private SQLite (`audit.db`) and files. Photos are never uploaded by this app. Product codes go to `world.openfoodfacts.org`; sharing exports is explicitly user initiated.

## Archive layout

```text
manifest.json                 schema, device, counts and file hashes
logs.jsonl                    one complete record per line
records/<uuid>.json           same records, pretty-printed
responses/<uuid>.json         exact API response text (can be non-JSON on HTTP errors)
ocr/<uuid>.txt                 raw recognized text
photos/<uuid>.jpg             original captures, referenced by record.photo
README.txt                    format notes
```

A record's `sample` associates separately captured barcode and label events. Each scan remains distinct even if the same product is scanned again. Photos left by interrupted captures are also exported so no original is silently lost. If a referenced photo is missing, export fails rather than silently producing an incomplete archive. Interrupted pending records are marked on next process start. The database itself is not included; `logs.jsonl` provides a portable snapshot of every stored record.

## Implementation

Java 17; native Android views; CameraX 1.4.1; bundled ML Kit barcode scanning 17.3.0 and Latin text recognition 16.0.1; SQLiteOpenHelper; HttpURLConnection; streaming USTAR + gzip. No web backend or FatSecret credentials. Target SDK 34, compile SDK 35, minimum SDK 26. ARM64 build intended for the Pixel 10. GPS capture is not requested.

Open Food Facts product endpoint: `/api/v2/product/{code}.json`. Complete responses are retained so the audit can inspect fields not yet surfaced in the UI. Product data is community maintained; missing data is never treated as zero. Attribution: [Open Food Facts](https://world.openfoodfacts.org/data), ODbL. This is an audit prototype; review the provider's current requirements before wider distribution.

## Build

Install JDK 17, Android SDK platform 35 and build-tools 35.0.0. Set `ANDROID_HOME` or create your own untracked `local.properties` with `sdk.dir=...`.

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest
```

APK: `app/build/outputs/apk/debug/app-debug.apk`.

## Test signing

Signing keys are excluded from Git. Debug builds use the Android Gradle plugin’s local debug keystore. To update an installed test APK without uninstalling and losing captures, the build must use the same signing key as that APK. No FatSecret client credentials are included.

## Verification limits

The supplied build report describes compilation and automated checks. Camera quality, on-device ML Kit performance, and Android save/share behavior must still be exercised on the physical Pixel; no claim of physical-device testing is made. This prototype retains raw outputs for that purpose.
