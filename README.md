# Batch Nutrition — Android V1

Local-first batch nutrition tracking for a personal ARM64 Android phone (Android 8+).
Create foods, add ingredient quantities to a named dish, then tap **Finished** when
it is gone. No cooked weight, meal categories, pre-portioned containers, account, OCR, or backend.

## Compose UI

The application UI is Kotlin Jetpack Compose with Material 3: shared dark color
scheme, typography, component shapes, scaffold, navigation bar, cards, fields,
sliders, chips, dialogs and date/time controls. The XML theme configures only the
host window. CameraX PreviewView uses AndroidView interop; camera actions are Compose.
The old Java widget activity has been removed.
Rendered test previews are in [docs/screenshots](docs/screenshots/README.md).

`TrackerViewModel` keeps navigation and form/draft state in SavedStateHandle.
Screens and shared components are split under `ui/`. `TrackerRepository` contains
the existing Java accounting/persistence queries, `FoodLookup` handles explicit
archived API lookup, and `BatchOperations` edits recorded facts without UI code.
Database schema 4, backup schemas 1–4, package identity and nutrition snapshots are
preserved. Returning to the app refreshes the as-of calculations.

Model attribution for AI-assisted commits is required by [AGENTS.md](AGENTS.md).
Release publication always requires user approval.

## App icon

A smiling rice-bowl mascot is packaged as an adaptive launcher icon with Android
13+ monochrome/themed support. The full-color art was generated with built-in
imagegen and is bundled locally; no image requests happen at runtime. Source,
prompt and rendered preview are documented in [docs/icon](docs/icon/README.md).

## Current interface

The app uses a dark theme throughout. Today shows calories, protein and fiber;
History and Stats are directly accessible. Unknown nutrients display as — and
partial known totals as ≥, rather than implying missing nutrition is zero.

Add ingredient opens the camera immediately, with Saved foods and Manual food
fallbacks. Done cooking saves the dish. The portion slider covers 1–8 servings in
quarter-serving steps; type quantities above 8 or below 1 when needed.

History combines logged dish portions, quick foods and calorie-only entries into a
newest-first timeline. Each tile has a date group, 24-hour time, name, dish share
where applicable, calories, protein and fiber. Scrolling loads more rows in batches
of 25 with Compose LazyColumn. Models are built in memory from database records;
the UI is paginated rather than issuing a separate SQL query for each page.
Legacy batch completion summaries are labeled as estimates, not invented meals.

Stats shows per-nutrient daily averages and days meeting editable user goals for
7- or 30-day windows, including today so far. Only days with actual intake entries
count: a direct food, positive dish portion, or calorie-only record. Zero-calorie
foods count too; saved foods, cooking, and inferred legacy dates do not.
Missing-nutrient days are excluded from that nutrient's average and shown as unknown.
Goals are current user-defined minimums, with sodium treated as an upper limit;
they are not personalized RDAs. A logged day may still contain an incomplete food log.

Energy balance offers Days (one calendar week, Sunday–Saturday by default), Weeks
(eight weeks), and Months (one calendar year). Browse with arrows, jump using the
range/date picker, or scroll the wider charts. Tap a bar to select its period and
use View food log to inspect its entries. Toggle intake/deficit; surplus bars extend
below zero. Coverage always shows logged days versus calendar days in the range.
Averages and intake/expenditure/balance totals include only logged days. Today's
expenditure accrues through the same as-of instant; historical days use their effective
expenditure rates and full local calendar days, including daylight-saving transitions.
Empty days do not generate a deficit. The full-week target is separate.
`LoggedHistory` is a rebuildable calculation over the existing authoritative records;
no stored inferred history is needed. Legacy linear allocations can
contribute on eligible dates and are labeled when present; they never establish
new logged dates by themselves.

Tapping a dish portion in History opens its ingredient contributions, scaled to
that recorded percentage, with a Whole dish toggle. Calories, protein, fiber and
ingredient quantities use the saved snapshots. Edit portion remains a separate action.

Lookup and manual entry now support optional fiber, vitamins A/C/D/E/K/B1/B2/B3/
B6/B9/B12 and calcium, iron, magnesium, potassium, zinc, selenium and sodium.
Mass values are stored in grams and converted to g/mg/µg for display and goal entry.
Open Food Facts [_100g/_serving fields](https://github.com/openfoodfacts/openfoodfacts-server/blob/main/html/data-fields.txt)
use normalized mass values. Missing nutrients stay absent. Goals are stored with
settings and included in backups. Old snapshots remain unchanged when saved food
nutrition is updated; rescan to obtain optional values for future ingredients.

## Expenditure

Today compares expenditure accrued so far with intake recorded so far. Resting
metabolic rate starts at **1,766 kcal/day** and is editable with an effective date.
Resting accrues by elapsed fraction of the local calendar day (including 23/25-hour
DST days). Previous dates keep their effective resting rate. Digestion, steps and
extra activity are added as recorded; this is not a full-day forecast.

Expand **Spent so far** on Today for Resting / Food digestion / Steps / Extra activity.
**Daily details & activity**, **Settings → Steps & activity**, or a selected daily chart
bar opens the dated detail screen. Add activity calories with a date, additional kcal
and optional description; multiple entries can be edited or deleted. These are only
calories above resting and beyond movement already counted through steps.

Digestion uses the existing per-day macro allocations without reducing food calories:
`protein × 4 × 0.25 + carbs × 4 × 0.075 + fat × 9 × 0.02`.
These fixed coefficients are approximations within the ranges discussed by
[Westerterp (2004)](https://pmc.ncbi.nlm.nih.gov/articles/PMC524030/), not individual
measurements. They live in `ExpenditureCoefficients.java`, together with the requested
30 kcal per 1,000 steps and a user-chosen 8% digestion fallback for calorie-only logs;
none are configurable in Settings. A 1,100 kcal quick log adds 88 kcal of estimated
digestion. The fallback applies only to that entry instead of macro-based TEF, never
on top of it or to the whole day's calories. Its protein/carbs/fat remain unknown in
nutrition summaries. Foods with saved macros retain the macro calculation even if
label calories differ slightly from 4/4/9 arithmetic. Any unresolved missing macros
outside calorie-only entries still leave digestion incomplete. The breakdown labels
the fallback explicitly. Changing, backdating or deleting a quick log recalculates
only its day's fallback; food calories are never changed.

Steps use AndroidX Health Connect 1.1.0. Only READ_STEPS is requested, in the foreground;
no writes, raw-record sums, custom counter, active-energy import or background permission.
`aggregate(AggregateRequest(metrics = setOf(StepsRecord.COUNT_TOTAL), ...))` queries all
origins together, relying on Health Connect's priority-based deduplication as described
in the official [aggregate](https://developer.android.com/health-and-fitness/health-connect/aggregate-data)
and [read](https://developer.android.com/health-and-fitness/health-connect/read-data) guides.
Android 14+ uses the platform module; older supported phones may need Health Connect
installed. Samsung Health/watch data reaches the app only through Health Connect.

Launch/resume refreshes today and up to 30 recent dates since the new model began;
viewing a date and Refresh steps also requery that date. Queries cover the whole local
calendar day, including today, to match the daily Health Connect view. Only resting
expenditure is scaled by elapsed time; step totals are used directly. The observation
timestamp is still bounded to the check time. Existing clipped current-day caches
are marked stale until refreshed. Last checked means this app read Health Connect,
not that the upstream watch just synced.
Each local date/time-zone key
is replaced, not incremented. Cache entries retain the last successful count, sync time,
observed-through instant, query end and latest attempt/status. A successful measured zero is
separate from no data. Failure or revoked permission preserves saved inputs while
marking estimates incomplete/stale. A previous day's partial sync stays incomplete
until a read covers the whole day. Food logging works without Health Connect.

SQLite v3→v4 adds a separate step cache. On first use, a recorded model-start date and
new resting-rate input are added transactionally. Old total-expenditure `rate` records
are preserved; dates before the boundary retain their old accounting. No old total
rate is relabeled as resting. Portable backups include resting rates and extra activities
as authoritative records, plus a separate validated `cachedSteps` section to preserve
measured history across reinstalls and Health Connect's historical-read limits. Restored
step snapshots are flagged stale until refreshed; permissions/connection are rechecked.
No derived TEF or expenditure totals are stored. Old backups (schemas 1–3) still restore.

Weekly/cumulative balance and nutrient averages continue to count only days with food
intake logged. An activity-only day does not establish a logged food day. The full-week
deficit goal remains separate; the new resting-only intake guide excludes digestion
and activity rather than inventing future expenditure.

## Daily use

- Home shows three tiles: today’s estimated intake, today’s estimated deficit/surplus,
  and estimated weekly deficit/surplus from Sunday through now.
- Tap a home balance tile, the chart icon, or Energy balance in Stats for period
  charts, logged-day averages, food-log drill-down and cumulative balance. Charts
  default to Sunday weeks and honor the configurable week-start setting; the home
  weekly tile always starts Sunday.
- Set your own expenditure in **Settings (gear icon)**. Initial assumption is explicitly
  1,766 kcal/day resting. Set its effective date, tracking start, weekly deficit goal,
  week start and expected duration. Existing total-expenditure history remains separate.
- **Quick log** opens the camera. Scan a barcode, enter a barcode manually, or use
  **Add calories** for a calorie-only record (for example, 120 kcal). These records
  have unknown macros; the summary labels macro totals as incomplete when relevant.
  They can be edited/deleted in History. The When control opens date and 24-hour
  time pickers for both new and existing calorie-only logs. Backdating updates
  the same record and moves its intake to the chosen day without duplication;
  leaving the timestamp unchanged preserves its original instant. Saved foods are in the Foods tab. Lookup uses Open Food Facts. Review
  the product's nutrition basis and save; incomplete nutrition requires manual entry.
  Product identity starts fresh on every lookup. Arbitrary QR URLs are never visited.
- Scanned foods open a nutrition-label page, preferring the package serving size and
  per-serving nutrient fields from Open Food Facts. Values are rounded for display;
  calculations retain their precision. Choose servings, grams or milliliters with a
  slider and editable quantity; selected calories/macros update live. The dark portion
  page puts controls first, with nutrition rows below;
  the confirmation action remains fixed at the bottom and Back returns to the previous screen. Grams/volume
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
  Tap an ingredient to correct its quantity without adding a duplicate.
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

## Raw lookup archive

Each successful JSON API lookup retains the complete decoded UTF-8 response text,
including fields the current app does not understand. A separate SQLite `lookups`
table stores a stable ID, requested barcode (as text, preserving leading zeros),
retrieval time, request URL, raw payload and SHA-256 checksum. Food records and
ingredient snapshots carry only `lookupId` references; they do not duplicate raw
payloads. Editable nutrition stays separate from the original provider response.
This is a source archive, not a cache of inferred intake or aggregates.

Backup schemas 3 and 4 include the archive and validates checksums, unique lookup IDs,
barcode/reference consistency and payload JSON before replacing data transactionally.
Older backups without archive references still restore. The raw response limit is
1 MiB per request and portable backup limit is 64 MiB; oversized data fails explicitly.
Only explicit product lookups access the network. Error pages/non-JSON HTTP errors
are not archived. JSON unknown-product responses from successful requests are kept.
No images are downloaded or saved; image URLs within API JSON remain plain text.

Responses retrieved during manual backup enrichment reflect their new retrieval
time, not the original scan. Historical ingredient quantities, corrections and
consumption records are preserved when missing nutrients are explicitly backfilled.
Future nutrient extraction can use archived fields without another API request.

## Accounting model

SQLite `nutrition.db` schema version 4 stores authoritative JSON documents in a
structured records table with stable IDs and explicit kinds: food, batch, direct,
settings, effective-dated legacy/resting rates, and extra activity. Calorie-only records store an instant
and energy without fabricated nutrient values. Batch eating events carry stable IDs
and timestamps, and are included in backups. System Back navigates through prior screens, retaining in-memory inputs and scroll
position. Saving returns to the parent screen and clears intermediate entry screens;
only Back from home exits. A cooking draft is restored after activity recreation.

SQLiteOpenHelper migrates older databases by adding the kind index, raw-lookup table and step cache without changing food facts;
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

Resting expenditure accrues by elapsed fraction of the local calendar day, including
23/25-hour days. Effective-date rates preserve earlier assumptions. Nothing accrues
before tracking start. See Expenditure above for the component calculation and legacy
boundary. Current-week balance is only week-to-date, never an achieved full-week goal.

## Backup and privacy

**Settings → Export backup** writes a portable UTF-8 JSON file with backup schema 4
(restore also accepts schemas 1, 2 and 3) and
authoritative records/settings using Android's document picker. **Restore** validates
types, quantities, dates, policy, IDs, snapshots and timestamps before a transactional
replacement. It replaces ALL tracker data after confirmation; it does not merge.
Repeated restoration cannot duplicate records. Export first to preserve current data.
Keep backups private: they contain food/activity history, saved step totals and settings. No signing keys or
personal backups belong in Git.

No analytics, photos, remote uploads or accounts. Only explicitly requested barcode
lookups contact `world.openfoodfacts.org`. Open Food Facts community data is licensed
under ODbL; see https://world.openfoodfacts.org/data. Missing values never become zero.

## Build here

The ignored `.android-toolchain/` contains Temurin JDK 17, Android SDK platform 36,
build tools 35.0.0, command-line tools and Gradle's cache. Run:

```sh
./scripts/build-android.sh
# Other tasks:
./scripts/build-android.sh :app:lintDebug
```

Installable APK: `app/build/outputs/apk/release/app-release.apk`. Run
`./scripts/build-installable.sh` for the optimized APK, unit tests and release lint.
R8 strips unused code/resources; the release variant excludes debug preview tooling
and is non-debuggable. Personal sideload builds reuse the existing locally generated
debug signing identity, allowing an in-place update. No signing keys are committed.
For development, `./scripts/build-android.sh` still produces a normal debug APK at
`app/build/outputs/apk/debug/app-debug.apk`. Java 17, Kotlin 2.0.21, Gradle 8.11.1, AGP 8.10.1, Compose Material 3, CameraX,
bundled ML Kit barcode scanner. No OCR dependency.

For another Linux x86_64 machine, install a JDK 17 at `.android-toolchain/jdk` and
Google Android command-line tools at `.android-toolchain/sdk/cmdline-tools/latest`:

```sh
source scripts/android-env.sh
sdkmanager --sdk_root="$ANDROID_HOME" --licenses
sdkmanager --sdk_root="$ANDROID_HOME" 'platform-tools' 'platforms;android-36' 'build-tools;35.0.0'
printf 'sdk.dir=%s\n' "$ANDROID_HOME" > local.properties
./scripts/build-android.sh
```

Alternatively set Java/SDK paths normally and run `./gradlew :app:assembleDebug :app:testDebugUnitTest`.
Network access is required for initial dependency downloads. Android Studio is optional.

## Validation

The test suite includes pure accounting/barcode/nutrient tests, actual SQLite
upgrade and backup round-trip tests under Robolectric, and Compose interaction
tests for navigation, activity recreation, calorie logging, ingredient editing,
portion entry and completion. No network API calls are needed by these tests.

Compose previews are rendered through Robolectric's native graphics mode using
synthetic test data; they are not physical-device screenshots. Generated images
are under `app/build/outputs/compose-screenshots/`. The dialog flow uses a 320dp
profile because of Robolectric issue #8460; other screen checks use 411dp.

Camera hardware, permission UX on the Pixel and the system document picker still
need physical-device testing. Local build and APK validation results are recorded
in BUILD_REPORT.txt.
