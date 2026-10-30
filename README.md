# Batch Nutrition — Android V1

Local-first batch nutrition tracking for a personal ARM64 Android phone (Android 8+).
Create foods, add ingredient quantities to a named dish, then tap **Finished** when
it is gone. No cooked weight, meal categories, portions, account, OCR, or backend.

## Daily use

- Home shows three tiles: today’s estimated intake, today’s estimated deficit/surplus,
  and estimated weekly deficit/surplus from Sunday through now.
- More → Nutrition details contains cumulative balance, macros, previous weeks and targets.
  The detailed weekly view follows your configurable week-start setting; the home tile always starts Sunday.
- Set your own expenditure in **Settings & backup**. Initial assumption is explicitly
  2000 kcal/day. Set tracking start, weekly deficit goal, week start and expected duration.
- **Scan food** or enter a barcode in **Foods**. Lookup uses Open Food Facts. Review
  the product's nutrition basis and save; incomplete nutrition requires manual entry.
  Product identity starts fresh on every lookup. Arbitrary QR URLs are never visited.
- Foods may use grams, milliliters, or servings. Enter quantities in the food's exact
  basis unit. There is no implicit ml-to-gram or serving-to-gram conversion. To use
  another basis, create a food with the label's valid nutrition for that basis.
- Create a dish from saved foods. Each ingredient retains a complete nutrition snapshot.
  Edit ingredient quantities by removing and adding the ingredient again.
- **Finished** is one tap and uses your existing share (initially 100%). Details allow
  changing share, correcting timestamps, undoing completion, editing, duplicating,
  and confirmed deletion. Duplicate batches have independent IDs/snapshots and 100% share.
- For a packaged/simple food, choose **Log food now** in Foods and enter its quantity.
- Completed records remain in History. Saved foods and all accounting work offline.

## Accounting model

SQLite `nutrition.db` schema version 2 stores authoritative JSON documents in a
structured records table with stable IDs and explicit kinds: food, batch, direct,
settings and effective-dated expenditure rate. SQLiteOpenHelper migrates version 1 to 2 by adding a kind index without changing facts;
unsupported future migrations fail rather than discarding data. The prototype's
separate `audit.db` is not imported or changed. No audit logs or images are collected.

Batch facts: ingredient quantities and nutrition snapshots, actual availability,
nullable actual completion, and consumed share. Direct food records are instantaneous.
Policy settings are distinct from these facts: expected duration and `linear-v1` ID.
`Estimator` is a pure replaceable calculation. No inferred consumption, meals,
allocations, or aggregate caches are stored.

Completed batches allocate total × share evenly across their actual interval;
zero-duration intervals allocate once at completion. Unfinished batches allocate
linearly across the expected duration (48 hours initially), clipped at the queried
as-of time and capped at total × share. They remain provisional indefinitely until
explicitly finished. Completion replaces the provisional calculation, so previous
weeks may reconcile. Intervals and calendar views are half-open; timestamps are epoch
milliseconds interpreted using the device's local timezone. Local calendar boundaries
include DST. Changing device timezone changes calendar views, not recorded instants.

Daily expenditure accrues by elapsed fraction of the local calendar day, including
23/25-hour days. Effective-date rates preserve earlier assumptions. Nothing accrues
before tracking start. Full-week target sums effective daily rates for seven days
minus the weekly goal; with a constant rate this is `7 × rate − goal`. Future rate
assumptions carry forward until another effective date. Current-week balance is only
week-to-date, never an achieved full-week goal.

## Backup and privacy

**Settings & backup → Export** writes a portable UTF-8 JSON file with schema version
and authoritative records/settings using Android's document picker. **Restore** validates
types, quantities, dates, policy, IDs, snapshots and timestamps before a transactional
replacement. It replaces ALL tracker data after confirmation; it does not merge.
Repeated restoration cannot duplicate records. Export first to preserve current data.
Keep backups private: they contain food history and settings. No signing keys or
personal backups belong in Git.

No analytics, photos, remote uploads or accounts. Only explicitly requested barcode
lookups contact `world.openfoodfacts.org`. Open Food Facts community data is licensed
under ODbL; see https://world.openfoodfacts.org/data. Missing values never become zero.

## Build here

The ignored `.android-toolchain/` contains Temurin JDK 17, Android SDK platform 35,
build tools 35.0.0, command-line tools and Gradle's cache. Run:

```sh
./scripts/build-android.sh
# Other tasks:
./scripts/build-android.sh :app:lintDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`. Debug signing is generated locally;
no keys are committed. Java 17, Gradle 8.9, AGP 8.7.3, native Android views, CameraX,
bundled ML Kit barcode scanner. No OCR dependency.

For another Linux x86_64 machine, install a JDK 17 at `.android-toolchain/jdk` and
Google Android command-line tools at `.android-toolchain/sdk/cmdline-tools/latest`:

```sh
source scripts/android-env.sh
sdkmanager --sdk_root="$ANDROID_HOME" --licenses
sdkmanager --sdk_root="$ANDROID_HOME" 'platform-tools' 'platforms;android-35' 'build-tools;35.0.0'
printf 'sdk.dir=%s\n' "$ANDROID_HOME" > local.properties
./scripts/build-android.sh
```

Alternatively set Java/SDK paths normally and run `./gradlew :app:assembleDebug :app:testDebugUnitTest`.
Network access is required for initial dependency downloads. Android Studio is optional.

## Validation limits

Automated tests cover barcode extraction, allocation clipping/reconciliation, weekly
boundary partitioning, instantaneous records, invalid intervals, DST, expenditure
start dates and effective-date changes. Build/lint results are recorded in
`BUILD_REPORT.txt`. Camera behavior, SQLite/backup document-picker flows and usability
still need physical-device testing; this environment has no connected Android device.
