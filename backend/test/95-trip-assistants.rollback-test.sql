-- After backend/95-trip-assistants-rollback.sql: the eight functions are back to their live bodies of
-- 2026-11-19 (as loaded by 95-trip-assistants.stub.sql) and the assistant objects are gone.
DO $$
BEGIN
  PERFORM t.check((SELECT count(*) FROM t.original) = 8, 'eight changed functions were recorded before 95');
  PERFORM t.check(NOT EXISTS (
    SELECT 1 FROM t.original o JOIN pg_proc p ON p.proname = o.proname JOIN pg_namespace n ON n.oid = p.pronamespace AND n.nspname = 'public'
    WHERE md5(p.prosrc) <> o.src), 'every changed function has its original body again');
  PERFORM t.check(to_regclass('public.pureice_trip_assistants') IS NULL, 'the assistants table is gone');
  PERFORM t.check(NOT EXISTS (SELECT 1 FROM pg_proc WHERE proname IN ('pureice_trip_member', 'pureice_my_open_trip', 'pureice_set_trip_assistant', 'pureice_trip_assistant_support')),
    'the assistant functions are gone');
  -- The campaign's main distributor still works; the sales already saved still count.
  PERFORM t.ok('d1000000-0000-0000-0000-000000000000',
    'SELECT public.finance_trip_scope(''11000000-0000-0000-0000-000000000001'')', 'the main distributor reads his campaign');
  PERFORM set_config('request.jwt.claim.sub', 'd2000000-0000-0000-0000-000000000000', true);
  PERFORM t.check(NOT public.finance_trip_scope('11000000-0000-0000-0000-000000000001'), 'the former assistant no longer reads it');
  PERFORM t.check((SELECT count(*) FROM public.sales WHERE trip_id = '11000000-0000-0000-0000-000000000001') = 3, 'the three saved sales stay');
END $$;
