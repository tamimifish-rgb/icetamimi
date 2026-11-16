# Database additions for the Android app

The app talks to the live "Tamimi Ice" Supabase project. Its base schema lives with the web app
(`naqi-ice-tamimi/migration/tamimi-ice/sql`). Additions that only the Android app uses are kept here.

| File | What it adds | Detected by the app through |
|---|---|---|
| `90-boxes-and-customer-location.sql` | Returnable box tracking (`pureice_boxes`, `pureice_box_events`, `pureice_box_variants`, `pureice_box_move`, `pureice_box_add`) and customer location capture (`pureice_customer_locations`, `pureice_set_customer_location`) | `pureice_box_support()` |

Rules these files follow:

- Additive only: no existing table, policy, trigger or function is changed.
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
