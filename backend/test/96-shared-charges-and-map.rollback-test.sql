-- After backend/96-shared-charges-and-map-rollback.sql: pureice_decide_shortage and the ledger
-- constraints are as before 96, the new objects are gone, and everyone still owes what he owed.
DO $$
DECLARE d1 uuid := 'd1000000-0000-0000-0000-000000000000'; d2 uuid := 'd2000000-0000-0000-0000-000000000000';
        d4 uuid := 'd4000000-0000-0000-0000-000000000000';
BEGIN
  PERFORM t.check((SELECT v FROM t.original96 WHERE k = 'fn:pureice_decide_shortage') =
    (SELECT md5(p.prosrc) FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace WHERE n.nspname = 'public' AND p.proname = 'pureice_decide_shortage'),
    'pureice_decide_shortage has its original body again');
  PERFORM t.check(NOT EXISTS (
    (SELECT 'con:' || conname, pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid = 'public.pureice_staff_ledger'::regclass)
    EXCEPT (SELECT k, v FROM t.original96 WHERE k LIKE 'con:%')) AND NOT EXISTS (
    (SELECT k, v FROM t.original96 WHERE k LIKE 'con:%')
    EXCEPT (SELECT 'con:' || conname, pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid = 'public.pureice_staff_ledger'::regclass)),
    'the ledger constraints are exactly the original ones');
  PERFORM t.check(to_regclass('public.pureice_missing_stock') IS NULL AND NOT EXISTS (SELECT 1 FROM pg_proc WHERE proname IN
    ('pureice_settle_missing_stock', 'pureice_missing_stock_summary', 'pureice_missing_stock_support', 'pureice_split_charge',
     'pureice_trip_route', 'pureice_customer_products', 'pureice_map_extras_support')), 'the new table and functions are gone');
  PERFORM t.check((SELECT sum(amount) FROM public.pureice_staff_ledger WHERE staff_id = d1) = 433.83
    AND (SELECT sum(amount) FROM public.pureice_staff_ledger WHERE staff_id = d2) = 383.83
    AND (SELECT sum(amount) FROM public.pureice_staff_ledger WHERE staff_id = d4) = 33.34, 'every balance is unchanged');
  PERFORM t.check((SELECT count(*) FROM public.pureice_staff_ledger WHERE kind = 'shortage') = 3, 'one shortage per closing, on its main distributor');
  PERFORM t.check((SELECT count(*) FROM public.trip_loads WHERE kind = 'damaged' AND qty = 4) = 1, 'the written-off bags stay written off');
END $$;
