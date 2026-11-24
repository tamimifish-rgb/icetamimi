# Database additions for the Android app

The app talks to the live "Tamimi Ice" Supabase project. Its base schema lives with the web app
(`naqi-ice-tamimi/migration/tamimi-ice/sql`). Additions that only the Android app uses are kept here.

| File | What it adds | Detected by the app through |
|---|---|---|
| `90-boxes-and-customer-location.sql` | Returnable box tracking (`pureice_boxes`, `pureice_box_events`, `pureice_box_variants`, `pureice_box_move`, `pureice_box_add`) and customer location capture (`pureice_customer_locations`, `pureice_set_customer_location`) | `pureice_box_support()` |
| `91-performance.sql` | Speed only: `trip_id` indexes on `gps_points`, `payments` and `expenses`, and the 38 access rules on the 10 busiest tables rewritten to check the signed-in user once per query instead of once per row. Undone by `91-performance-rollback.sql` | — (no behaviour change) |
| `92-closing-handover.sql` | How a closing's counted cash reaches the company: cash, deposit/transfer, or both, with the slip (`pureice_closing_handover`, `pureice_save_closing_handover`) | `pureice_closing_handover_support()` |
| `93-staff-ledger.sql` | Each employee's account: who carries an approved closing's shortage (`pureice_shortage_decision`, `pureice_decide_shortage`) and charged shortages, salary deductions and adjustments (`pureice_staff_ledger`, `pureice_add_staff_entry`) | `pureice_staff_ledger_support()` |
| `94-truck-care.sql` | Truck service plans and recorded services (managers), the truck's current odometer, and problem reports from distributors followed from open to fixed (`pureice_truck_service_plan`, `pureice_truck_services`, `pureice_truck_issues` and their functions) | `pureice_truck_care_support()` |
| `95-trip-assistants.sql` | Assistant distributors on a campaign (`pureice_trip_assistants`, `pureice_set_trip_assistant`, `pureice_my_open_trip`); **changes eight existing functions** (main distributor → main or assistant) and adds a deposits read policy for campaign members. Undo: `95-trip-assistants-rollback.sql` | `pureice_trip_assistant_support()` |
| `96-shared-charges-and-map.sql` | Charged shortages split equally with assistants; missing returns settled and charged before approval (`pureice_missing_stock`, `pureice_settle_missing_stock`); the campaign's route and customers' products for the map. **Changes `pureice_decide_shortage` and the ledger constraints.** Undo: `96-shared-charges-and-map-rollback.sql` | `pureice_missing_stock_support()`, `pureice_map_extras_support()` |
| `97-late-closing-handover.sql` | A first closing handover that reaches the server after the closing was approved is recorded (it was refused for good); changing one after approval is still refused. **Changes one condition in `pureice_save_closing_handover`.** Undo: `97-late-closing-handover-rollback.sql` | (uses 92's) |

Rules these files follow:

- Additive only: no existing table, policy, trigger or function is changed. The one exception is
  `91-performance.sql`, which rewrites existing access rules for speed without changing what they
  allow, and comes with a rollback script. The same rewrite belongs in the web repo's scripts.
- The app works unchanged against a database without them; the feature's screens stay hidden
  until the support function answers `1`.
- New tables: default privileges are revoked, `authenticated` gets `SELECT` only, row level
  security is on, and all writes go through `SECURITY DEFINER` functions.

## Applying

Applied to the live project on 2026-11-16 as migration `pureice_boxes_and_customer_location`
(with مجروش أبو ١٠ registered as the boxed variant). The steps below are for another database.

Run the file once as a single script (it is one transaction). Before: check that no
`pureice_box*` / `pureice_customer_locations` object exists. After:

```sql
select count(*), count(*) filter (where status = 'new') from public.pureice_boxes;  -- 210, 210
select relname, relrowsecurity from pg_class
 where relname in ('pureice_boxes','pureice_box_events','pureice_box_variants','pureice_customer_locations');
```

Then mark the product sold in boxes (admin, in the app: الإعدادات → إعداد الصناديق), or:

```sql
insert into public.pureice_box_variants(variant_id) select id from public.product_variants where name = '<the boxed variant>';
```

## Testing a change

The script was tested against a scratch Postgres 16 loaded with the web repo's
`10/50/60/70/80` scripts plus small stubs for Supabase's `auth`/`storage` schemas and roles,
calling the functions as each role (`set local role authenticated` +
`set_config('request.jwt.claim.sub', …)`): permissions, repeated requests, late scans, a phone
with a wrong clock, and the location rules.

## 91-performance.sql

Applied to the live project on 2026-11-17 as migration `pureice_performance_indexes_and_rls_initplan`,
after checking that the live rules still matched the ones the file was generated from. Checked after:
the three `*_trip_id` indexes exist; all 38 rules use the once-per-query form; a real distributor and
a real manager each see exactly the `sales`, `sale_items` and `gps_points` rows they saw before; and
the route map's query uses `gps_points_trip_id`.

The performance advisor still lists 25 of the rules under `auth_rls_initplan`: those whose checks are
only helper calls, such as `(SELECT public.is_manager(auth.uid()))`. Its text match looks for
`select auth.uid()` literally; `EXPLAIN` shows these run once per query (`InitPlan`) like the rest.

Tested on a scratch Postgres 16 with the live helper functions (`active_staff`, `has_role`,
`is_manager`, `is_staff`), a stub `auth.uid()` and all 38 rules as they are live. An admin, a
supervisor, two distributors, a deactivated distributor and an unknown account each read, inserted
(as themselves and as someone else), updated and deleted on every table, plus the flagged-closing
resubmit: 312 results, identical before the script, after it and after the rollback. The rollback
restores every rule's text exactly.

## 92-closing-handover.sql

Applied to the live project on 2026-11-19 as migration `pureice_closing_handover`, after the owner
approved it. Tests: `backend/test/run.sh backend/92-closing-handover.sql`, 25 checks covering every
account kind, including sums that don't add up, missing or foreign slips, approved closings and
repeated requests.

Checked on the live project after applying, in a rolled-back transaction:
- a real distributor gets `1` from the support function;
- a change to his approved closing is refused;
- a supervisor cannot write a distributor's handover;
- both see only the rows they should.

The security advisor's only new entries are the two functions, listed under "signed-in users can
execute a SECURITY DEFINER function". That is intended: both check the caller themselves.

`backend/test/` is the harness for every script from 92 on:
- `stub-schema.sql` holds the live helper functions and the tables these scripts touch;
- `run.sh` starts a throwaway Postgres 16, applies one script, and runs its `.test.sql` as each kind of account.

## 93-staff-ledger.sql

Applied to the live project on 2026-11-19 as migration `pureice_staff_ledger`, after the owner
approved it. Tests: `backend/test/run.sh backend/93-staff-ledger.sql`, 36 checks.

Checked on the live project after applying, in a rolled-back transaction that left no rows behind:
- a real distributor gets `1` from the support function, cannot decide a shortage, and sees only his own entries;
- a real supervisor can charge a real approved shortage, but cannot charge an even closing or record a salary deduction (admin only).

Four approved closings were short and waited for a decision at that point. The security advisor's
only new entries are the three functions, as intended.

## 94-truck-care.sql

Applied to the live project on 2026-11-19 as migration `pureice_truck_care`, after the owner
approved it. Tests: `backend/test/run.sh backend/94-truck-care.sql`, 39 checks.

Checked on the live project after applying, in a rolled-back transaction that left no rows behind:
- a real distributor gets `1` from the support function, reads his own truck's odometer but not another truck's, can report a problem for his own truck, cannot set a service plan, and sees only his own truck's reports;
- a real supervisor can set a plan and read any truck's odometer.

The security advisor's only new entries are the seven functions, as intended.

Services recorded by managers are kept in `pureice_truck_services`, not `maintenance_jobs`: a
maintenance job must belong to an open campaign. Reports' maintenance and profit tabs do not include
them yet.

## 95-trip-assistants.sql

Applied to the live project on 2026-11-19 as migration `pureice_trip_assistants`, after the owner
approved it. Tests: `backend/test/run.sh backend/95-trip-assistants.sql`: 57 checks, then 7 after
`95-trip-assistants-rollback.sql` restores the original functions. The stub
`backend/test/95-trip-assistants.stub.sql` holds the live tables and functions the script changes or
calls, copied verbatim from the live project.

Unlike 90–94 this script changes existing functions: `finance_trip_scope`, `finance_customer_scope`,
`finance_lock_trip`, `finance_field_scope_backstop`, `finance_guard_customer`, `save_invoice`,
`pureice_box_move` and `pureice_my_truck` (from 94). Each change is one condition ("the campaign's
distributor" → "the campaign's distributor or one of its assistants"); the rest of each function is
the live definition, copied before applying and confirmed unchanged by checksum just before. With no
assistant added they behave as before. `approve_trip_loads`, `guard_trip_loads` and `submit_closing`
keep their own main-distributor checks. One read policy is added on `pureice_deposits`
(`campaign_member_read`), so both phones see the campaign's deposits.

Checked on the live project after applying:
- the twelve new and changed function bodies match the tested ones exactly (md5 of each body);
- in a rolled-back transaction with a real open campaign, its distributor, a free distributor and a
  supervisor: the distributor cannot add an assistant; the supervisor can; the assistant gets `1`
  from the support function, finds the campaign as `assistant`, reads it, its truck and all 13 of its
  sales, may write field records (as `save_invoice` runs it), cannot approve the load or send the
  closing, and cannot call the membership helper; another distributor cannot write on the campaign;
  the main distributor finds it as `main` with the assistant listed; after removal the assistant no
  longer reads it. No rows were left behind.

The security advisor's only new entries are the three functions the app calls
(`pureice_trip_assistant_support`, `pureice_my_open_trip`, `pureice_set_trip_assistant`).

Not covered: a manager can still open a new campaign for a distributor who assists another open
campaign (`guard_trips` is unchanged). His phone then finds the campaign he leads first.

## 96-shared-charges-and-map.sql

Applied to the live project on 2026-11-20 as migration `pureice_shared_charges_and_map`, after the
owner approved it. Tests: `backend/test/run.sh backend/96-shared-charges-and-map.sql`: 37 checks, then
6 after the rollback. The stub `backend/test/96-shared-charges-and-map.stub.sql` is generated from the
95 stub, the bodies of 93 and 95, and `review_closing` copied verbatim from the live project.

Changes: `pureice_decide_shortage` (only its charge branch: one `shortage` row per person, split by
`pureice_split_charge`), and the `pureice_staff_ledger` constraints (kind `stock_shortage`; one row per
closing, person and kind). The ledger held no entries when this was applied. The rollback turns rows
the old rules would refuse into adjustments for the same person and amount, so balances are kept.

Missing returns: `pureice_settle_missing_stock` writes the bags still on the truck off as a `damaged`
load row (through the existing guard, so `review_closing` and the web app see the stock as zero),
values them at `product_variants.price`, and charges the value equally.

Checked on the live project after applying:
- the eight new and changed function bodies match the tested ones exactly (md5 of each body);
- in a rolled-back transaction with real accounts, truck, product and customer, the following
  happened in order:
  - a supervisor opened a campaign, loaded 5 bags and added an assistant;
  - the main distributor approved the load, sold 1 bag and sent a closing 10 short;
  - 2 bags were recorded as returned;
  - approval was refused while 2 bags were unaccounted for;
  - the settlement charged 10,000 (2 × 5,000) as 5,000 to each;
  - approval then succeeded;
  - the cash shortage was charged 5 to each;
  - the assistant could read the route and 271 product-history pairs.
- No rows were left behind.

The security advisor's only new entries are the five functions the app calls
(`pureice_settle_missing_stock`, `pureice_missing_stock_support`, `pureice_trip_route`,
`pureice_customer_products`, `pureice_map_extras_support`).

## 97-late-closing-handover.sql

Applied to the live project on 2026-11-24 as migration `pureice_late_closing_handover`, after the
owner approved it. Tests: `backend/test/run.sh backend/97-late-closing-handover.sql`: 15 checks, then
3 after the rollback.

Why: أمين المشجري's closing `44891a67` (23 Nov, counted 101,000) was approved 2½ minutes after it
reached the server; its handover (a transfer with a slip photo) reached the server only afterwards,
and 92 refused it for good ("تم اعتماد الإغلاق؛ لا يمكن تعديل طريقة التسليم"), leaving it blocked on
his phone. Before applying, the live function matched 92 by md5 (`ad7c4709…`).

Checked on the live project after applying:
- the function body matches the tested one (md5 `05bb92f5…`);
- his phone synced at 14:56:17, seconds later, and the handover is now recorded: 101,000 by
  transfer, with the slip;
- in a rolled-back transaction, a different handover for that closing is refused as a change after
  approval, and another distributor is refused.

The security advisor is unchanged.

