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
- **Quick log** opens the camera. Scan a barcode, enter a barcode manually, or use
  **Add calories** for a calorie-only record (for example, 120 kcal). These records
  have unknown macros; the summary labels macro totals as incomplete when relevant.
  They can be edited/deleted in History. Saved foods are under More. Lookup uses Open Food Facts. Review
  the product's nutrition basis and save; incomplete nutrition requires manual entry.
  Product identity starts fresh on every lookup. Arbitrary QR URLs are never visited.
- Scanned foods open a nutrition-label page, preferring the package serving size and
  per-serving nutrient fields from Open Food Facts. Values are rounded for display;
  calculations retain their precision. Choose servings, grams or milliliters with a
  slider and editable quantity; selected calories/macros update live. The dark portion
  page puts controls first, with a macro calorie split and nutrition rows below;
  Cancel/Save remain fixed at the bottom. Grams/volume
  controls appear only with an explicit per-serving conversion. No ml-to-g assumption.
- Explicit g/ml amounts in serving-size text take precedence over structured unit
  metadata (which can conflict). Ambiguous fractions/decimal-comma text require a
  correction rather than a guessed conversion. A missing serving size falls back
  to the API's nutrition basis. Missing nutrients require correction, never zero.
- Edit nutrition / serving size is a secondary action. Corrections allow optional
  grams or ml per serving. Saved foods without serving metadata still work in their
  original units; rescan to retrieve current serving information. Existing batch
  snapshots are unaffected.
- Create a dish and tap Add ingredient: scan a barcode, enter a food manually, or choose
  a saved food. Save the food and enter its quantity to return to your dish. The dish
  name and ingredients are retained while scanning/adding foods. Availability defaults
  to the time you save; Change start time is an optional date/time picker.
  Each ingredient retains a complete nutrition snapshot.
  Edit ingredient quantities by removing and adding the ingredient again.
- **Ate** asks for a share of the whole dish. A slider snaps to common amounts
  (10%, ¼, ⅓, ½, ⅔, ¾, and the remaining share); a percentage field accepts finer
  amounts. Nutrition for every ingredient is scaled together. Exact thirds remain
  exact internally even when displayed as 33.33%.
- **Finished** asks to log the remaining share at the current time. Earlier portions
  never change. If all of your share is already logged, it only marks completion.
- New dishes use explicit portions from creation: no intake accrues until a portion
  is recorded. Existing dishes retain their original linear accounting until Ate or
  Finished switches them to explicit portions, preserving old history until you act.
- Dish details let you correct/remove portions and timestamps. You cannot log beyond
  your share of the batch. Undoing completion removes its automatically logged
  remaining portion and reopens the batch. Duplication copies ingredients, not logs.
- Editing ingredients intentionally updates that batch's nutrition totals; edits to
  the saved food library never rewrite existing ingredient snapshots.
- For a packaged/simple food, choose **Log food now** in Foods and enter its quantity.
- Completed records remain in History. Saved foods and all accounting work offline.

## Accounting model

SQLite `nutrition.db` schema version 2 stores authoritative JSON documents in a
structured records table with stable IDs and explicit kinds: food, batch, direct,
settings and effective-dated expenditure rate. Calorie-only records store an instant
and energy without fabricated nutrient values. Batch eating events carry stable IDs
and timestamps, and are included in backups. System Back navigates through prior screens, retaining in-memory inputs and scroll
position. Saving returns to the parent screen and clears intermediate entry screens;
only Back from home exits. A cooking draft is restored after activity recreation.

SQLiteOpenHelper migrates version 1 to 2 by adding a kind index without changing facts;
unsupported future migrations fail rather than discarding data. The prototype's
separate `audit.db` is not imported or changed. No audit logs or images are collected.

Batch facts: ingredient quantities and nutrition snapshots, actual availability,
nullable actual completion, consumed-share ceiling, and recorded portions (stable ID,
actual timestamp, fraction of the whole batch, and whether Ate or Finished created
it). Direct food and calorie-only records are instantaneous. Explicit portions are
allocated once at their recorded times and never inferred or redistributed. Removing
all portions from an explicit batch leaves zero intake rather than reactivating a
linear estimate. Batch completion has no extra consumption charge beyond the
confirmed remaining-portion record.

The pure `RecordedPortions` calculator validates shares, computes remaining share,
and aggregates recorded amounts over half-open date ranges. `Estimator` retains
`linear-v1` for legacy batches and its duration setting. Completed legacy batches
allocate over their actual interval; unfinished legacy batches remain provisional.
No inferred consumption, meals, allocations, or aggregate caches are persisted.

Calendar views use epoch-millisecond facts and the device timezone, including DST.
Changing the device timezone changes calendar views, not recorded instants.

Daily expenditure accrues by elapsed fraction of the local calendar day, including
23/25-hour days. Effective-date rates preserve earlier assumptions. Nothing accrues
before tracking start. Full-week target sums effective daily rates for seven days
minus the weekly goal; with a constant rate this is `7 × rate − goal`. Future rate
assumptions carry forward until another effective date. Current-week balance is only
week-to-date, never an achieved full-week goal.

## Backup and privacy

**Settings & backup → Export** writes a portable UTF-8 JSON file with backup schema 2
(restore also accepts schema 1) and
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

Automated tests cover barcode extraction, legacy allocation clipping/reconciliation, explicit portion limits and remaining
shares, navigation history, weekly boundaries, instantaneous records, invalid
intervals, DST, expenditure
start dates and effective-date changes. Build/lint results are recorded in
`BUILD_REPORT.txt`. Camera behavior, SQLite/backup document-picker flows and usability
still need physical-device testing; this environment has no connected Android device.
