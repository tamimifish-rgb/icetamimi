# ثلج نقي — Pure Ice (Android)

Native Android app (Kotlin + Jetpack Compose) for the Pure Ice distribution system.
It is a rewrite of the web app in `naqi-ice-tamimi` against the **same backend**: the
same database, the same server rules and the same staff accounts. Nothing on the
server was changed for it.

The whole interface is Arabic and right-to-left, and it is laid out to look the same as
the web app: same font (Cairo), icons, colours, spacing and screens. `tools/web-reference`
renders the published web app and the Android screens on the same sample data and
compares them block by block; run it after any UI change (see its README and PARITY.md).

## What is in the app

| Role | Screens |
| --- | --- |
| Distributor | Today (campaign, stock, load approval), sale invoice, debt collection, customers (shared directory, new customer), freezer movement, expenses, truck maintenance, campaign closing, invoice view (WhatsApp / print), invoice cancel-edit requests |
| Supervisor | Trucks and campaigns, closings review, reports, route map, customers, invoice change review |
| Administrator | Dashboard, products, regions, trucks, customers, freezers, employees and PINs, company settings, plus the supervisor screens |

How it behaves:

- **Works without internet.** Every distributor record is saved on the phone first
  (SQLite outbox) with its own UUID, then uploaded. Invoices and closings go through
  the server's atomic functions; a retry can never create a second copy. A record
  leaves the phone only after the server snapshot includes it, and is then archived
  on the phone rather than deleted.
- **One account's data never mixes with another's.** The outbox and cache are keyed by
  the signed-in employee; an account switch aborts any upload or refresh in flight.
- **Route tracking with the screen off.** While a distributor has an open campaign, a
  foreground service (with a visible notification) records a point at most once a
  minute straight into the outbox. It uses the phone's own location providers, so
  Google Play services are not required. It stops when the closing is sent, the
  campaign closes or the employee signs out.
- **Location gate.** Distributors must allow location before sales, collections and
  load approval. Only an invoice may be saved "without location", and only when
  permission is granted but no fix arrived for two minutes.
- **Sign-in** is username + 6-digit PIN. The PIN is checked by the web app's hosted
  `staffLogin` function (the keyed PIN derivation and attempt throttling exist only
  there); the phone receives ordinary session tokens.

## Build

Requirements: JDK 17 or newer, Android SDK Platform 35 and Build-Tools 35.

```sh
./gradlew :app:testDebugUnitTest   # unit tests (rules, sign-in protocol, sync)
./gradlew :app:assembleDebug       # app/build/outputs/apk/debug/app-debug.apk
```

Or open the folder in Android Studio and run the `app` configuration.

### Release and updating the installed app

The package is `com.tamimi.naqiice`, the same as the earlier builds, with
`versionCode 217` (3.6.0). An update installs **over** the existing app only when it is signed
with the same key. Since 3.1.1 that is the key created on 2026-11-18 (`pureice-release.jks`,
alias `pureice`, certificate SHA-256 `0f7235a3…eef6a26e`). Keep the key file and its password
outside this repository; the owner holds them.

The key used for 3.1.0 and earlier is lost, so moving a phone from 3.1.0 or earlier to 3.1.1 or
later means uninstalling the old app once: first open it online and let everything sync
(anything still waiting on the phone is deleted with it), then install the new version and sign
in again. From 3.1.1 on, versions install over each other as normal updates.

#### Releasing from a phone (GitHub Actions)

`.github/workflows/release-apk.yml` runs the unit tests, builds the release APK, signs it, checks
that the signing certificate is the one above, and attaches `pureice-<version>-<code>.apk` to a
**draft** release (visible only to people with write access; the repository is public).

1. Raise `versionCode` (and `versionName`) in `app/build.gradle.kts`.
2. GitHub app or github.com → **Actions** → **Release APK** → **Run workflow**.
3. When it finishes, open **Releases**, then the draft, and download the APK.

It needs two repository secrets (Settings → Secrets and variables → Actions):
`SIGNING_KEYSTORE_BASE64` (the key file, base64-encoded) and `SIGNING_KEYSTORE_PASSWORD`.

## Project layout

```
app/src/main/java/com/tamimi/naqiice/
  core/      pure rules: money, stock, debt, dates, phones, location and tracking policy
  data/      Api (REST/RPC/Auth/Storage), StaffLogin, Store (SQLite outbox + cache),
             Sync (upload + snapshot + reconciliation), Derived (what screens show), Repo
  tracking/  location helper and the foreground tracking service
  ui/        Compose screens
app/src/test/  JVM unit tests, and screen tests that render every screen (Robolectric)
tools/web-reference/  web-vs-native comparison harness and the parity method
```

Backend settings (project URL, publishable key, receipts bucket, site origin) are the
nonsecret release values of the web app and live in `data/Api.kt` (`Backend`). No
secret key is in this repository or in the app.

## Things to know

- **Sign-in depends on the published web app.** The app calls the `staffLogin` server
  function at `https://naqi-ice-tamimi.lovable.app`. Its address is derived from the
  function's file and export name (`src/lib/staff.functions.ts`, `staffLogin`), so it
  stays the same across normal publishes; renaming or moving that function in the web
  project would break sign-in here until `StaffLogin.FUNCTION_ID` is updated. The same
  applies to `createEmployee` and `setStaffPin` used by the employees screen.
- **Photos** (receipts, odometer, deposit slips) are saved upright as JPEG, at most
  1600 px, before upload, to suit slow connections.
- Tracking continues only while the app's process is alive. Force-stopping the app or
  restarting the phone stops it until the app is opened again.

## Returnable boxes and customer locations (3.1.0)

Each box of مجروش أبو ١٠ carries a sticker with a serial (`TM-ICE-000001`) and a QR code holding
exactly that serial. The app tracks where every box is: not scanned yet, factory, a truck, a
customer, elsewhere, damaged or written off.

- Supervisor: الشاحنات → الصناديق → "تحميل على شاحنة" / "إعادة إلى المصنع" (scan many).
- Distributor: on the invoice, scans only the boxes the customer keeps and any empties taken
  back; the customer page lists the customer's boxes and has "استلام فارغة" / "تسليم صندوق".
- Any box: scan it to see where it is recorded and its history; managers can set its place.
- After an invoice for a customer with no saved location (GPS fix within 50 m and one minute),
  the distributor is asked to confirm they are at the customer's place; yes saves the location.

Scanning uses CameraX + ZXing on the phone (offline, no Google services); a number can always be
typed instead. Box moves and location confirmations go through the same offline outbox as
invoices (`box_move`, `customer_location`) but never hold a closing back.

The server side is `backend/90-boxes-and-customer-location.sql`; everything above stays hidden
until `pureice_box_support()` exists on the server. See `backend/README.md`.
