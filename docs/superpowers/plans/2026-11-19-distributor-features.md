# Pure Ice: Distributor Profile, Closing Handover, Maintenance, Assistants and Customer Map Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. On approval, copy this file to `docs/superpowers/plans/2026-11-19-distributor-features.md` in the repo.

**Goal:** Add the five features the owner asked for: a distributor profile with sales reports and a salary-deduction account for charged shortages; a cash / bank-transfer / both handover choice at closing with a payment-slip upload; truck service reminders and problem reports; assistant distributors who sell from their own phones; and a distributor map of customers marking who was invoiced today.

**Architecture:** The Android app is offline-first (SQLite outbox, idempotent UUIDs, `data/Sync.kt`) over the shared live Supabase database. Server additions are numbered SQL files in `backend/`, each tested on a scratch Postgres 16 like `91-performance.sql` and applied as a Supabase migration only after the owner approves. Each feature's screens stay hidden until its `pureice_*_support()` function answers `1`. Parts A–E are independent. Each ends with a release: raise `versionCode` and sign with the new key.

**Tech Stack:** Kotlin, Jetpack Compose, osmdroid 6.1.20, OkHttp, kotlinx.serialization, SQLite, Supabase (PostgREST, RPC, Storage bucket `pureice-receipts`), JUnit and Robolectric screen tests.

**Spec:** the owner's request of 2026-11-19, plus these answers:
- Android only.
- The supervisor decides each time whether a shortage is charged.
- The assistant sells on his own phone.
- Maintenance means service reminders plus problem reports.

## Global Constraints

- **Android only.** The web app (Lovable "Naqi Ice Tamimi") is not changed. Server rules must still hold when the web app calls the old RPCs.
- **Server changes:** `backend/9N-<name>.sql`, one transaction, with a `backend/9N-<name>-rollback.sql` whenever an existing object changes.
  - New tables: revoke defaults, give `authenticated` `SELECT` only, RLS on, and all writes through `SECURITY DEFINER` functions with `SET search_path = pg_catalog, public`.
  - Use the once-per-query form `(SELECT auth.uid())` in policies (see `91-performance.sql`).
- **Test before applying:** each SQL file runs on a scratch Postgres 16, as in `backend/README.md` § 91, with role tests (admin, supervisor, two distributors, inactive, unknown). It is applied live only after the owner approves; then record it in `backend/README.md`.
- **Offline distributor writes** use the outbox: a new kind needs an entry in `KIND_LABELS` (`ui/Shell.kt`), in `SyncEngine.tables` or the push branch (`data/Sync.kt`), and evidence-based archiving in `fetchRef`.
- **UI:** Arabic RTL. Use only the shared parts in `ui/Common.kt` (`IceCard`, `StatGrid`, `Banner`, `Btn`, `T.*`). Money uses existing formatting. These are native-only screens, so no web parity is needed; follow the boxes precedent (`app/src/test/fixtures/boxes.json`, a `Fixture.<feature>` flag).
- **Tests:** `./gradlew :app:testDebugUnitTest` stays green. Every new screen gets a `ScreenHarness` test.
- **Releases:** raise `versionCode` in `app/build.gradle.kts` (currently 212). Sign with `pureice-release.jks` (cert SHA-256 `0f7235a3…eef6a26e`) or the "Release APK" workflow.

## Review Focus

1. **A transfer handover made offline** queues after the closing and must never upload before the server has that closing. Test: `handoverWaitsForItsClosing` (Task B2).
2. **A shortage approved from the web app** (old `review_closing`, no charge choice) must appear as "undecided", and a manager must be able to decide it later from the phone. The same goes for the 8 differences already approved. Test: `undecidedShortageIsListed` (Task C2).
3. **The main distributor and the assistant selling the last stock offline on two phones:** the server rejects the later invoice. That phone must show the existing blocked state ("المخزون غير كاف") with the record kept, not lost. Test: `assistantSaleRejectedForStockStaysBlocked` (Task E2).
4. **An assistant's phone** must never offer load approval or closing, and must not record route points (one tracker per truck). Test: `assistantCannotCloseOrApprove` (Task E3).
5. **Customers without a location** must not break the map. Show the count of unplaced customers, and an empty state when none have a location. Test: `customersWithoutLocationAreCounted` (Task A1).

---

## Part A: Customer map for distributors (no server change)

### Task A1: Pin model

**Files:** Create `app/src/main/java/com/tamimi/naqiice/core/CustomerMap.kt`. Test `app/src/test/java/com/tamimi/naqiice/CustomerMapTest.kt`.

**Interfaces, produces:**
- `data class CustomerPin(val id: String, val name: String, val lat: Double, val lng: Double, val visited: Boolean)`
- `data class CustomerPins(val pins: List<CustomerPin>, val unplaced: Int, val visited: Int)`
- `fun customerPins(customers: List<Row>, campaignSales: List<Row>): CustomerPins`

Rules:
- "Visited" means a sale for that customer in the current campaign (`Snapshot.day.sales`), including sales still waiting on the phone.
- Customers with null `lat` or `lng` are counted in `unplaced`.

- [ ] Write tests `pinsOnlyForLocatedCustomers`, `customerWithCampaignSaleIsVisited`, `pendingSaleCountsAsVisited` and `customersWithoutLocationAreCounted` (3 customers, 1 without location: `pins.size == 2`, `unplaced == 1`).
- [ ] Run them to see them fail. Implement. Run them to see them pass. Commit.

### Task A2: Map screen

**Files:**
- Modify `ui/TripMap.kt`: add `enum class StopStyle { Sale, Customer, Visited }`; change `MapStop` to add `style: StopStyle = StopStyle.Sale` and `customerId: String? = null`.
- Create `ui/CustomerMap.kt`.
- Modify `ui/Common.kt` (`Screen.CustomerMap`), `ui/Shell.kt` (`ScreenContent`, guard field roles) and `ui/Distributor.kt` (a "خريطة العملاء" button on `CustomersScreen` and in `MoreScreen`).
- Test `app/src/test/java/com/tamimi/naqiice/CustomerMapScreenTest.kt`.

**Interfaces, consumes:** `customerPins` (Task A1) and `Snapshot.customers` / `Snapshot.day.sales`.

**Behaviour:**
- Marker colours: grey for not visited, green for visited.
- Tapping a marker opens `Screen.Customer(id)`.
- The legend reads "تمت زيارتهم X من Y" and "بلا موقع: N".
- Fit the view to all pins.
- Tiles need internet; markers still draw offline.

- [ ] Test `mapShowsVisitedCount`: with the fixture (3 placed, 1 visited), the legend text contains "1 من 3".
- [ ] Test `emptyStateWhenNoLocations`.
- [ ] Implement until both pass.
- [ ] Commit, then release 3.2.0 (213).

---

## Part B: Closing handover (cash / bank transfer / both, with slip)

### Task B1: Server `backend/92-closing-handover.sql`

**Produces:**
- Table `pureice_closing_handover(closing_id uuid PK REFERENCES closings, trip_id uuid NOT NULL, cash_amount numeric NOT NULL CHECK (>=0), transfer_amount numeric NOT NULL CHECK (>=0), slip_path text, created_by uuid NOT NULL, created_at timestamptz, updated_at timestamptz)`.
  - Read policy: own rows (`created_by`) or a manager.
- `pureice_save_closing_handover(p_request jsonb) RETURNS pureice_closing_handover`. The request carries `closing_id`, `cash_amount`, `transfer_amount` and `slip_path`. It raises when:
  - the caller isn't the closing's `created_by`;
  - the closing status isn't `submitted` or `flagged`;
  - `cash_amount + transfer_amount <> counted_cash`;
  - `transfer_amount > 0` without a `slip_path` starting `<uid>/`.

  Otherwise it upserts. A repeat with the same values returns the row.
- `pureice_closing_handover_support() RETURNS int`.

- [ ] Write the scratch-Postgres test script. It must cover: a valid upsert, a sum mismatch, a missing slip, someone else's closing, an approved closing, and an idempotent repeat.
- [ ] Commit, and ask the owner to approve applying it.

### Task B2: Phone side

**Files:**
- Create `core/Handover.kt`: `enum class HandoverMethod { Cash, Transfer, Both }` and `fun handoverError(counted: Double, method: HandoverMethod, cash: Double, transfer: Double, hasSlip: Boolean): String?`. It returns `null` when valid, otherwise an Arabic message.
- Modify `ui/Field.kt` `ClosingScreen` to add the method chips "نقد" / "إيداع بنكي أو حوالة" / "الاثنان", the amounts, and the slip picker.
- Modify `ui/Device.kt` to add `SlipPick(path, onChange)`, which accepts a camera photo, a gallery photo or a PDF. Images go through `Photos.import`. PDFs are copied as-is, up to 10 MB, and refused above that.
- Modify `data/Sync.kt`:
  - Add outbox kind `closing_handover`, pushed through the RPC after the slip uploads with the correct content type (`image/jpeg` or `application/pdf`).
  - Rank it after `closing`, so it depends on the closing as blocked/uploaded.
  - Archive it when `pureice_closing_handover?closing_id=in.(…)` has it.
- Modify `ui/Shell.kt` `KIND_LABELS`.

**Behaviour:**
- **Cash:** `cash = counted`, `transfer = 0`, no slip.
- **Transfer:** `transfer = counted`, slip required.
- **Both:** both amounts entered, their sum must equal counted, slip required.
- A flagged closing resubmits its handover under the same `closing_id`.

- [ ] Write tests `handoverErrorRules` (sum mismatch, missing slip, cash-only valid) and `handoverWaitsForItsClosing` (SyncTest: closing blocked means the handover RPC is not called).
- [ ] Write `handoverArchivedOnServerCopy` and `pdfSlipUploadsAsPdf`.
- [ ] Write the screen test `closingTransferNeedsSlip`.
- [ ] Implement until they pass. Commit.

### Task B3: Supervisor sees the handover

**Files:** Modify `ui/Supervisor.kt` `ClosingsScreen`: show the method, the amounts and a slip button. Generalize `DepositSlipButton` so a PDF opens in the browser through `signedUrl`. Test `SupervisorScreensTest.closingShowsHandover`.

- [ ] Test, implement, commit, release 3.3.0.

---

## Part C: Shortage account and distributor profile

### Task C1: Server `backend/93-staff-ledger.sql`

**Produces:**
- Table `pureice_staff_ledger(id uuid PK, staff_id uuid NOT NULL REFERENCES profiles, kind text CHECK (kind IN ('shortage','deduction','adjustment')), amount numeric NOT NULL, closing_id uuid UNIQUE REFERENCES closings, note text, created_by uuid NOT NULL, created_at timestamptz)`.
  - The sign convention: a positive amount means the staff member owes the company. A shortage is `+abs(difference)`; a deduction from salary is negative.
  - Read: own or manager.
- Table `pureice_shortage_decision(closing_id uuid PK REFERENCES closings, charged boolean NOT NULL, note text, decided_by uuid, decided_at timestamptz)`.
- `pureice_decide_shortage(p_closing uuid, p_charge boolean, p_note text)`, managers only. It requires the closing to be `approved` with `difference < 0` and no decision yet. It inserts the decision and, if charged, the ledger `shortage` row for `closings.created_by`.
  - It is separate from `review_closing` on purpose. Approvals from the web app, and the 8 differences already approved, remain decidable later.
- `pureice_add_staff_entry(p_id uuid, p_staff uuid, p_kind text, p_amount numeric, p_note text)`: admin only, kinds `deduction` and `adjustment`, idempotent on `p_id`.
- `pureice_staff_ledger_support() RETURNS int`.

- [ ] Write the scratch test: a manager charges, a manager absorbs, a second decision is refused, a distributor can't decide, a distributor reads only his own rows, the admin records a deduction.
- [ ] Commit, and ask the owner to approve applying it.

### Task C2: Decision on approval and "undecided" list

**Files:** Modify `ui/Supervisor.kt` `ClosingsScreen`.
- After approving a closing with `difference < 0`, a dialog offers "تحميل العجز على الموزع" (charge) or "تتحمله الشركة" (absorb). It calls `pureice_decide_shortage`.
- A section "عجز بانتظار القرار" lists approved shortages that have no decision row.

- [ ] Write tests `undecidedShortageIsListed` and `approveShortageAsksToCharge`.
- [ ] Implement. Commit.

### Task C3: Profile screen

**Files:**
- Create `core/StaffSummary.kt`:
  - `data class StaffSummary(val sales: Double, val invoices: Int, val cashSales: Double, val creditSales: Double, val collected: Double, val campaigns: Int, val shortages: Double, val balance: Double)`
  - `fun staffSummary(sales: List<Row>, payments: List<Row>, closings: List<Row>, ledger: List<Row>): StaffSummary`
- Create `ui/StaffProfile.kt` with `Screen.StaffProfile(id)`. It has:
  - a date range (reuse the Reports range control);
  - a `StatGrid`;
  - a per-day sales list;
  - closings with their difference and decision (charged / absorbed / undecided);
  - ledger entries and the balance "المستحق للخصم من الراتب";
  - for an admin, a "تسجيل خصم من الراتب" button calling `pureice_add_staff_entry`.

  Data loads online with `rememberLoaded`: `sales?created_by=eq.`, `payments?created_by=eq.`, `closings?created_by=eq.`, `pureice_staff_ledger?staff_id=eq.`.
- Entry points:
  - managers: tap a card in `AdminUsersScreen` and the distributor name in `TripsScreen`;
  - the distributor: "حسابي" in `MoreScreen` (own profile, read-only).

- [ ] Write tests `staffSummaryTotals` (cash and credit split, balance = shortages + deductions) and the screen tests `profileShowsBalance` and `distributorSeesOnlyOwnProfile`.
- [ ] Implement. Commit. Release 3.4.0.

---

## Part D: Truck service reminders and problem reports

### Task D1: Server `backend/94-truck-care.sql`

**Produces:**
- Table `pureice_truck_service_plan(truck_id uuid PK, interval_km int, interval_days int, last_service_km numeric, last_service_date date, updated_by uuid, updated_at timestamptz)`.
- Table `pureice_truck_issues(id uuid PK, truck_id uuid NOT NULL, trip_id uuid, title text NOT NULL, details text, severity text CHECK (IN ('normal','urgent')), photo_path text, status text CHECK (IN ('open','in_progress','fixed')) DEFAULT 'open', reported_by uuid, reported_at timestamptz, resolved_by uuid, resolved_at timestamptz, resolution_note text)`.
- RPCs:
  - `pureice_set_service_plan(p_truck, p_interval_km, p_interval_days)`, managers only.
  - `pureice_record_service(p_truck, p_km, p_date, p_cost, p_note)`, managers only. It updates the plan's last service and inserts a `maintenance_jobs` row so costs reach Reports.
  - `pureice_report_truck_issue(p_request jsonb)`, any staff member, idempotent on id. A distributor may only report for the truck of his open trip.
  - `pureice_update_truck_issue(p_id, p_status, p_note)`, managers only.
  - `pureice_truck_care_support()`.
- Current km = the greatest of `expenses.odometer` for the truck and `last_service_km`.

- [ ] Write the scratch test for every role path. Commit, and ask for approval.

### Task D2: Due calculation

**Files:** Create `core/TruckCare.kt`:
- `enum class ServiceState { Ok, Soon, Due, NoPlan }`
- `fun serviceState(intervalKm: Int?, intervalDays: Int?, lastKm: Double?, lastDate: LocalDate?, currentKm: Double?, today: LocalDate): ServiceState`

**Rules:** due when km or days reach the interval; soon within 10% of it.

- [ ] Write test `serviceStateRules`: 5,000 km plan, 4,600 driven = Soon; 5,000 = Due; 90-day plan at day 91 = Due; no plan = NoPlan.
- [ ] Implement. Commit.

### Task D3: Distributor side

**Files:**
- Create `ui/TruckIssue.kt`: a report form (title, details, urgent toggle, photo through `PhotoPick`) that queues outbox kind `truck_issue`. Push through the RPC in `data/Sync.kt` and archive on its server copy.
- A Today-screen banner "الشاحنة تحتاج صيانة" when the state is Due.
- The truck's open issues list.

- [ ] Write tests `truckIssueQueuesOffline` (SyncTest) and `todayShowsServiceDueBanner`.
- [ ] Implement. Commit.

### Task D4: Manager screen "صيانة الشاحنات"

**Files:** Create `ui/TruckCare.kt` with `Screen.TruckCare`. One card per truck showing:
- the service state chip;
- current and next-due km and date;
- open issues, with status actions;
- recent `maintenance_jobs` with costs;
- the buttons "تعديل خطة الصيانة" and "تسجيل صيانة".

Entries: a header action on `TripsScreen` and a tile in `AdminSetupScreen`.

- [ ] Write tests `truckCareListsDueTruckFirst` and `markIssueFixed`.
- [ ] Implement. Commit. Release 3.5.0.

---

## Part E: Assistant distributor (sells on his own phone)

### Task E1: Server `backend/95-trip-assistants.sql` and rollback

**Produces:**
- Table `pureice_trip_assistants(trip_id uuid, user_id uuid, added_by uuid, added_at timestamptz, PRIMARY KEY (trip_id, user_id))`.
- `pureice_trip_member(_trip uuid, _uid uuid) RETURNS boolean`: true for the trip's `distributor_id` or one of its assistants.
- `pureice_set_trip_assistant(p_trip uuid, p_user uuid, p_on boolean)`, managers only. The user must be an active distributor, not the main distributor, and in no other open trip as main or assistant. The trip must be open.
- `pureice_my_open_trip() RETURNS jsonb`: `{trip, role: 'main'|'assistant'}` or null.
- `pureice_trip_assistant_support()`.
- **Change these existing functions** to accept members (`pureice_trip_member`) instead of `distributor_id = auth.uid()`:
  - `finance_trip_scope`, `finance_customer_scope`;
  - `finance_field_scope_backstop` and `finance_guard_customer` (look the open trip up by membership and truck);
  - `finance_lock_trip` (non-closing path), `save_invoice`, `pureice_box_move`.
- **Keep main-only:** `approve_trip_loads`, `guard_trip_loads`, `submit_closing`.
- The rollback file restores the current definitions. Capture them with `pg_get_functiondef` when writing the file.

- [ ] Write the scratch test.
  - The assistant can: save an invoice, collect a payment, add an expense, add a customer, move a box, and read the trip and its snapshot.
  - The assistant cannot: approve loads or submit the closing.
  - An outsider can do none of these.
  - The main distributor is unchanged.
- [ ] Commit, and ask for approval. This one changes existing live functions.

### Task E2: Sync

**Files:**
- `data/Sync.kt` `fetchRef`: when supported, find the trip through `pureice_my_open_trip()` and store `tripRole` in `ref`; fall back to the current query otherwise.
- `data/Derived.kt`: `Snapshot.tripRole: String?`; `ownOpenTrip` becomes membership; add `canCloseOrApprove = tripRole == "main"`.

- [ ] Write tests `assistantFindsHisTrip` and `assistantSaleRejectedForStockStaysBlocked` (fake server rejects with "المخزون غير كاف": state `blocked`, record kept).
- [ ] Implement. Commit.

### Task E3: Phone screens

**Files:**
- `ui/Distributor.kt` Today: the assistant sees a "مساعد في حملة …" banner and his own cash total "سلّم النقد للموزع الرئيسي". Load approval and closing are hidden.
- `ui/Field.kt`: `requireCampaign` allows an assistant except for closing.
- `tracking/TrackingService.kt`: track only when `tripRole == "main"`.
- `ui/Supervisor.kt` `TripScreen`: an "المساعدون" section with add/remove (picker of free active distributors) through `pureice_set_trip_assistant`. `TripsScreen` shows assistant names.

- [ ] Write tests `assistantCannotCloseOrApprove`, `assistantDoesNotTrack` and `supervisorAddsAssistant`.
- [ ] Implement. Commit. Release 3.6.0.

---

## Verification (each part)

1. `./gradlew :app:testDebugUnitTest` passes in full, and `:app:assembleRelease` builds.
2. The SQL file passes its scratch-Postgres role tests. After the owner approves: apply it, re-run the advisors (no new security findings), and smoke-test as a real distributor and supervisor in a rolled-back transaction.
3. Install the signed APK on one phone, walk through the feature, then roll it out.

## Execution

**Recommended: Native, one part at a time**, in the order A → B → C → D → E.
- The tasks inside a part share interfaces closely (core function, then sync, then screens).
- Every server part stops for the owner's approval before touching the live database.

A fresh reviewer then checks each part's branch before its PR is merged.
