-- Tests for backend/97-late-closing-handover.sql. Run with: backend/test/run.sh backend/97-late-closing-handover.sql
-- Accounts (stub-schema.sql): a0… admin, 50… supervisor, d1… / d2… distributors.
-- d1's closings: c…1 sent (9,000), c…2 approved (7,000) whose handover never arrived, c…3 approved
-- (5,000) that already has its handover. d2's closing c…4 approved.
INSERT INTO public.trips(id, truck_id, distributor_id, status) VALUES
 ('11000000-0000-0000-0000-000000000001', '71000000-0000-0000-0000-000000000000', 'd1000000-0000-0000-0000-000000000000', 'open'),
 ('11000000-0000-0000-0000-000000000002', '71000000-0000-0000-0000-000000000000', 'd1000000-0000-0000-0000-000000000000', 'closed'),
 ('11000000-0000-0000-0000-000000000003', '71000000-0000-0000-0000-000000000000', 'd1000000-0000-0000-0000-000000000000', 'closed'),
 ('11000000-0000-0000-0000-000000000004', '72000000-0000-0000-0000-000000000000', 'd2000000-0000-0000-0000-000000000000', 'closed');
INSERT INTO public.closings(id, trip_id, counted_cash, status, created_by) VALUES
 ('c1000000-0000-0000-0000-000000000001', '11000000-0000-0000-0000-000000000001', 9000, 'submitted', 'd1000000-0000-0000-0000-000000000000'),
 ('c1000000-0000-0000-0000-000000000002', '11000000-0000-0000-0000-000000000002', 7000, 'approved', 'd1000000-0000-0000-0000-000000000000'),
 ('c1000000-0000-0000-0000-000000000003', '11000000-0000-0000-0000-000000000003', 5000, 'approved', 'd1000000-0000-0000-0000-000000000000'),
 ('c1000000-0000-0000-0000-000000000004', '11000000-0000-0000-0000-000000000004', 3000, 'approved', 'd2000000-0000-0000-0000-000000000000');
INSERT INTO public.pureice_closing_handover(closing_id, trip_id, cash_amount, transfer_amount, slip_path, created_by) VALUES
 ('c1000000-0000-0000-0000-000000000003', '11000000-0000-0000-0000-000000000003', 5000, 0, NULL, 'd1000000-0000-0000-0000-000000000000');

CREATE FUNCTION t.handover(closing text, cash numeric, transfer numeric, slip text) RETURNS text LANGUAGE sql AS $$
 SELECT format('SELECT public.pureice_save_closing_handover(%L::jsonb)',
   jsonb_build_object('closing_id', closing, 'cash_amount', cash, 'transfer_amount', transfer, 'slip_path', slip)::text)
$$;

DO $$
DECLARE d1 uuid := 'd1000000-0000-0000-0000-000000000000'; d2 uuid := 'd2000000-0000-0000-0000-000000000000';
        k1 text := 'c1000000-0000-0000-0000-000000000001'; k2 text := 'c1000000-0000-0000-0000-000000000002';
        k3 text := 'c1000000-0000-0000-0000-000000000003'; k4 text := 'c1000000-0000-0000-0000-000000000004';
        slip text := 'd1000000-0000-0000-0000-000000000000/handover-k2.jpg';
BEGIN
  -- A late first handover for an approved closing is recorded, with the usual checks.
  PERFORM t.refused(d1, t.handover(k2, 6000, 0, NULL), 'a late handover whose sum is not the counted cash');
  PERFORM t.refused(d1, t.handover(k2, 2000, 5000, NULL), 'a late transfer without a slip');
  PERFORM t.refused(d1, t.handover(k2, 2000, 5000, 'd2000000-0000-0000-0000-000000000000/x.jpg'), 'a late transfer with someone else''s slip');
  PERFORM t.refused(d2, t.handover(k2, 2000, 5000, slip), 'someone else''s approved closing');
  PERFORM t.ok(d1, t.handover(k2, 2000, 5000, slip), 'd1''s late handover for his approved closing is recorded');
  PERFORM t.check((SELECT cash_amount = 2000 AND transfer_amount = 5000 AND slip_path = slip FROM public.pureice_closing_handover WHERE closing_id = k2::uuid), 'with its amounts and slip');
  PERFORM t.ok(d1, t.handover(k2, 2000, 5000, slip), 'sending it again is accepted');
  -- Once recorded, an approved closing's handover cannot change.
  PERFORM t.refused(d1, t.handover(k2, 7000, 0, NULL), 'changing it after approval');
  PERFORM t.refused(d1, t.handover(k3, 0, 5000, slip), 'changing a handover recorded before approval');
  PERFORM t.ok(d1, t.handover(k3, 5000, 0, NULL), 'repeating the recorded one is accepted');
  -- A sent closing behaves as before.
  PERFORM t.ok(d1, t.handover(k1, 9000, 0, NULL), 'a handover for a sent closing');
  PERFORM t.ok(d1, t.handover(k1, 4000, 5000, 'd1000000-0000-0000-0000-000000000000/handover-k1.jpg'), 'and its correction before approval');
  PERFORM t.check((SELECT transfer_amount = 5000 FROM public.pureice_closing_handover WHERE closing_id = k1::uuid), 'the correction replaced it');
  PERFORM t.ok(d2, t.handover(k4, 3000, 0, NULL), 'd2''s late cash handover');
  PERFORM t.check((SELECT count(*) FROM public.pureice_closing_handover) = 4, 'four handovers in all');
END $$;
