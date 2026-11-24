-- After backend/97-late-closing-handover-rollback.sql: the 92 body again; late handovers already recorded stay.
DO $$
BEGIN
  PERFORM t.check((SELECT md5(prosrc) FROM pg_proc WHERE proname = 'pureice_save_closing_handover') = (SELECT v FROM t.original97), 'pureice_save_closing_handover has its 92 body again');
  PERFORM t.refused('d1000000-0000-0000-0000-000000000000', format('SELECT public.pureice_save_closing_handover(%L::jsonb)',
    jsonb_build_object('closing_id', 'c1000000-0000-0000-0000-000000000002', 'cash_amount', 7000, 'transfer_amount', 0, 'slip_path', NULL)::text), 'after the undo, an approved closing''s handover is refused again');
  PERFORM t.check((SELECT count(*) FROM public.pureice_closing_handover) = 4, 'the handovers recorded stay');
END $$;
