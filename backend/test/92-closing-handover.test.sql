-- Tests for backend/92-closing-handover.sql. Run with: backend/test/run.sh backend/92-closing-handover.sql
-- Accounts (stub-schema.sql): a0… admin, 50… supervisor, d1… / d2… distributors, d3… inactive.
INSERT INTO public.trips(id, truck_id, distributor_id, status) VALUES
 ('11000000-0000-0000-0000-000000000001', '71000000-0000-0000-0000-000000000000', 'd1000000-0000-0000-0000-000000000000', 'open'),
 ('11000000-0000-0000-0000-000000000002', '72000000-0000-0000-0000-000000000000', 'd2000000-0000-0000-0000-000000000000', 'open'),
 ('11000000-0000-0000-0000-000000000003', '71000000-0000-0000-0000-000000000000', 'd1000000-0000-0000-0000-000000000000', 'closed'),
 ('11000000-0000-0000-0000-000000000004', '72000000-0000-0000-0000-000000000000', 'd3000000-0000-0000-0000-000000000000', 'open');
INSERT INTO public.closings(id, trip_id, counted_cash, status, created_by) VALUES
 ('c1000000-0000-0000-0000-000000000001', '11000000-0000-0000-0000-000000000001', 9000, 'submitted', 'd1000000-0000-0000-0000-000000000000'),
 ('c1000000-0000-0000-0000-000000000002', '11000000-0000-0000-0000-000000000002', 5000, 'submitted', 'd2000000-0000-0000-0000-000000000000'),
 ('c1000000-0000-0000-0000-000000000003', '11000000-0000-0000-0000-000000000003', 7000, 'approved', 'd1000000-0000-0000-0000-000000000000'),
 ('c1000000-0000-0000-0000-000000000004', '11000000-0000-0000-0000-000000000004', 100, 'submitted', 'd3000000-0000-0000-0000-000000000000');

-- The request the phone sends.
CREATE FUNCTION t.handover(closing text, cash numeric, transfer numeric, slip text) RETURNS text LANGUAGE sql AS $$
 SELECT format('SELECT public.pureice_save_closing_handover(%L::jsonb)',
   jsonb_build_object('closing_id', closing, 'cash_amount', cash, 'transfer_amount', transfer, 'slip_path', slip)::text)
$$;

DO $$
DECLARE d1 uuid := 'd1000000-0000-0000-0000-000000000000'; d2 uuid := 'd2000000-0000-0000-0000-000000000000';
        d3 uuid := 'd3000000-0000-0000-0000-000000000000'; sup uuid := '50000000-0000-0000-0000-000000000000';
        k1 text := 'c1000000-0000-0000-0000-000000000001';
        slip text := 'd1000000-0000-0000-0000-000000000000/handover-c1.jpg';
BEGIN
  PERFORM set_config('request.jwt.claim.sub', d1::text, true);
  PERFORM t.check((SELECT public.pureice_closing_handover_support()) = 1, 'support function answers 1 for staff');
  PERFORM set_config('request.jwt.claim.sub', 'e0000000-0000-0000-0000-000000000000', true);
  PERFORM t.check((SELECT public.pureice_closing_handover_support()) = 0, 'support function answers 0 for an unknown account');

  PERFORM t.ok(d1, t.handover(k1, 9000, 0, NULL), 'cash-only handover for own closing');
  PERFORM t.check((SELECT count(*) FROM public.pureice_closing_handover) = 1, 'one handover row');
  PERFORM t.check((SELECT trip_id FROM public.pureice_closing_handover) = '11000000-0000-0000-0000-000000000001', 'trip taken from the closing');
  PERFORM t.ok(d1, t.handover(k1, 9000, 0, NULL), 'the same request again is accepted');
  PERFORM t.check((SELECT count(*) FROM public.pureice_closing_handover) = 1, 'still one row after the repeat');

  PERFORM t.ok(d1, t.handover(k1, 4000, 5000, slip), 'split between cash and a transfer with a slip');
  PERFORM t.check((SELECT cash_amount = 4000 AND transfer_amount = 5000 AND slip_path = slip FROM public.pureice_closing_handover), 'the split replaced the cash-only handover');

  PERFORM t.refused(d1, t.handover(k1, 4000, 4000, slip), 'amounts that do not add up to the counted cash');
  PERFORM t.refused(d1, t.handover(k1, 0, 9000, NULL), 'a transfer without a slip');
  PERFORM t.refused(d1, t.handover(k1, 0, 9000, 'd2000000-0000-0000-0000-000000000000/x.jpg'), 'a slip in someone else''s folder');
  PERFORM t.refused(d1, t.handover(k1, -1000, 10000, slip), 'a negative amount');
  PERFORM t.refused(d2, t.handover(k1, 9000, 0, NULL), 'a handover for someone else''s closing');
  PERFORM t.refused(sup, t.handover(k1, 9000, 0, NULL), 'a supervisor writing a distributor''s handover');
  PERFORM t.refused(d1, t.handover('c1000000-0000-0000-0000-000000000003', 7000, 0, NULL), 'a handover for an approved closing');
  PERFORM t.refused(d1, t.handover('c1000000-0000-0000-0000-0000000000ff', 0, 0, NULL), 'a closing that does not exist');
  PERFORM t.refused(d3, t.handover('c1000000-0000-0000-0000-000000000004', 100, 0, NULL), 'an inactive account');
  PERFORM t.refused(d1, format('INSERT INTO public.pureice_closing_handover(closing_id, trip_id, cash_amount, transfer_amount, created_by) VALUES (%L, %L, 9000, 0, %L)', k1, '11000000-0000-0000-0000-000000000001', d1), 'writing the table directly');

  UPDATE public.closings SET status = 'flagged' WHERE id = k1::uuid;
  PERFORM t.ok(d1, t.handover(k1, 0, 9000, slip), 'a flagged closing''s handover can be corrected');
  PERFORM t.check((SELECT transfer_amount = 9000 FROM public.pureice_closing_handover), 'the correction is stored');

  PERFORM t.ok(d2, t.handover('c1000000-0000-0000-0000-000000000002', 5000, 0, NULL), 'the second distributor saves his own');
  PERFORM t.check(t.visible(d1, 'SELECT * FROM public.pureice_closing_handover') = 1, 'a distributor sees only his own handover');
  PERFORM t.check(t.visible(sup, 'SELECT * FROM public.pureice_closing_handover') = 2, 'a supervisor sees every handover');
  PERFORM t.check(t.visible(d3, 'SELECT * FROM public.pureice_closing_handover') = 0, 'an inactive account sees none');
END $$;
