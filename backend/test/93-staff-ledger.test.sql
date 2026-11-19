-- Tests for backend/93-staff-ledger.sql. Run with: backend/test/run.sh backend/93-staff-ledger.sql
-- Accounts (stub-schema.sql): a0… admin, 50… supervisor, d1… / d2… distributors, d3… inactive.
INSERT INTO public.trips(id, truck_id, distributor_id, status) VALUES
 ('11000000-0000-0000-0000-000000000001', '71000000-0000-0000-0000-000000000000', 'd1000000-0000-0000-0000-000000000000', 'closed'),
 ('11000000-0000-0000-0000-000000000002', '72000000-0000-0000-0000-000000000000', 'd2000000-0000-0000-0000-000000000000', 'closed'),
 ('11000000-0000-0000-0000-000000000003', '71000000-0000-0000-0000-000000000000', 'd1000000-0000-0000-0000-000000000000', 'closed'),
 ('11000000-0000-0000-0000-000000000004', '71000000-0000-0000-0000-000000000000', 'd1000000-0000-0000-0000-000000000000', 'open'),
 ('11000000-0000-0000-0000-000000000005', '72000000-0000-0000-0000-000000000000', 'd2000000-0000-0000-0000-000000000000', 'closed');
INSERT INTO public.closings(id, trip_id, expected_cash, counted_cash, difference, status, created_by) VALUES
 -- d1 approved 2,000 short; d2 approved 500 short; d1 approved with a 300 overage; d1 still submitted 1,000 short; d2 approved even.
 ('c1000000-0000-0000-0000-000000000001', '11000000-0000-0000-0000-000000000001', 10000, 8000, -2000, 'approved', 'd1000000-0000-0000-0000-000000000000'),
 ('c1000000-0000-0000-0000-000000000002', '11000000-0000-0000-0000-000000000002', 5000, 4500, -500, 'approved', 'd2000000-0000-0000-0000-000000000000'),
 ('c1000000-0000-0000-0000-000000000003', '11000000-0000-0000-0000-000000000003', 7000, 7300, 300, 'approved', 'd1000000-0000-0000-0000-000000000000'),
 ('c1000000-0000-0000-0000-000000000004', '11000000-0000-0000-0000-000000000004', 9000, 8000, -1000, 'submitted', 'd1000000-0000-0000-0000-000000000000'),
 ('c1000000-0000-0000-0000-000000000005', '11000000-0000-0000-0000-000000000005', 3000, 3000, 0, 'approved', 'd2000000-0000-0000-0000-000000000000');

CREATE FUNCTION t.decide(closing text, charge boolean, note text DEFAULT NULL) RETURNS text LANGUAGE sql AS $$
 SELECT format('SELECT public.pureice_decide_shortage(%L::uuid, %L::boolean, %L)', closing, charge, note)
$$;
CREATE FUNCTION t.entry(id text, staff text, kind text, amount numeric, note text DEFAULT NULL) RETURNS text LANGUAGE sql AS $$
 SELECT format('SELECT public.pureice_add_staff_entry(%L::uuid, %L::uuid, %L, %L::numeric, %L)', id, staff, kind, amount, note)
$$;

DO $$
DECLARE adm uuid := 'a0000000-0000-0000-0000-000000000000'; sup uuid := '50000000-0000-0000-0000-000000000000';
        d1 uuid := 'd1000000-0000-0000-0000-000000000000'; d2 uuid := 'd2000000-0000-0000-0000-000000000000';
        d3 uuid := 'd3000000-0000-0000-0000-000000000000';
BEGIN
  PERFORM set_config('request.jwt.claim.sub', sup::text, true);
  PERFORM t.check((SELECT public.pureice_staff_ledger_support()) = 1, 'support function answers 1 for staff');

  -- Deciding a shortage.
  PERFORM t.refused(d1, t.decide('c1000000-0000-0000-0000-000000000001', true), 'a distributor deciding his own shortage');
  PERFORM t.refused(d3, t.decide('c1000000-0000-0000-0000-000000000001', true), 'an inactive account deciding');
  PERFORM t.ok(sup, t.decide('c1000000-0000-0000-0000-000000000001', true, 'نقص في الصندوق'), 'the supervisor charges d1''s 2,000 shortage');
  PERFORM t.check((SELECT count(*) FROM public.pureice_staff_ledger WHERE staff_id = d1 AND kind = 'shortage' AND amount = 2000) = 1, 'the charge is a +2,000 shortage entry for d1');
  PERFORM t.check((SELECT charged FROM public.pureice_shortage_decision WHERE closing_id = 'c1000000-0000-0000-0000-000000000001'), 'the decision is recorded as charged');
  PERFORM t.ok(sup, t.decide('c1000000-0000-0000-0000-000000000001', true, 'نقص في الصندوق'), 'repeating the same decision is accepted');
  PERFORM t.check((SELECT count(*) FROM public.pureice_staff_ledger) = 1, 'still one ledger entry after the repeat');
  PERFORM t.refused(adm, t.decide('c1000000-0000-0000-0000-000000000001', false), 'reversing a decision');

  PERFORM t.ok(adm, t.decide('c1000000-0000-0000-0000-000000000002', false, 'تتحمله الشركة'), 'the admin absorbs d2''s 500 shortage');
  PERFORM t.check((SELECT count(*) FROM public.pureice_staff_ledger WHERE staff_id = d2) = 0, 'an absorbed shortage adds no ledger entry');
  PERFORM t.check((SELECT NOT charged FROM public.pureice_shortage_decision WHERE closing_id = 'c1000000-0000-0000-0000-000000000002'), 'the decision is recorded as absorbed');

  PERFORM t.refused(sup, t.decide('c1000000-0000-0000-0000-000000000003', true), 'charging an overage');
  PERFORM t.refused(sup, t.decide('c1000000-0000-0000-0000-000000000005', true), 'charging an even closing');
  PERFORM t.refused(sup, t.decide('c1000000-0000-0000-0000-000000000004', true), 'charging a closing not yet approved');
  PERFORM t.refused(sup, t.decide('c1000000-0000-0000-0000-0000000000ff', true), 'a closing that does not exist');

  -- Salary deductions and adjustments: the admin only.
  PERFORM t.ok(adm, t.entry('e1000000-0000-0000-0000-000000000001', d1::text, 'deduction', 1500, 'خصم راتب نوفمبر'), 'the admin records a 1,500 salary deduction');
  PERFORM t.check((SELECT amount FROM public.pureice_staff_ledger WHERE id = 'e1000000-0000-0000-0000-000000000001') = -1500, 'a deduction is stored as a negative amount');
  PERFORM t.ok(adm, t.entry('e1000000-0000-0000-0000-000000000001', d1::text, 'deduction', 1500, 'خصم راتب نوفمبر'), 'repeating the same entry is accepted');
  PERFORM t.check((SELECT count(*) FROM public.pureice_staff_ledger WHERE staff_id = d1) = 2, 'still two entries for d1 after the repeat');
  PERFORM t.refused(adm, t.entry('e1000000-0000-0000-0000-000000000001', d1::text, 'deduction', 900), 'reusing an entry id with other values');
  PERFORM t.ok(adm, t.entry('e1000000-0000-0000-0000-000000000002', d1::text, 'adjustment', -200, 'تسوية'), 'the admin records a -200 adjustment');
  PERFORM t.check((SELECT sum(amount) FROM public.pureice_staff_ledger WHERE staff_id = d1) = 300, 'd1 owes 2,000 - 1,500 - 200 = 300');
  PERFORM t.refused(sup, t.entry('e1000000-0000-0000-0000-000000000003', d1::text, 'deduction', 100), 'a supervisor recording a deduction');
  PERFORM t.refused(d1, t.entry('e1000000-0000-0000-0000-000000000004', d1::text, 'deduction', 100), 'a distributor recording a deduction');
  PERFORM t.refused(adm, t.entry('e1000000-0000-0000-0000-000000000005', d1::text, 'shortage', 100), 'recording a shortage by hand');
  PERFORM t.refused(adm, t.entry('e1000000-0000-0000-0000-000000000006', d1::text, 'deduction', 0), 'a zero deduction');
  PERFORM t.refused(adm, t.entry('e1000000-0000-0000-0000-000000000007', d1::text, 'deduction', -50), 'a negative deduction');
  PERFORM t.refused(adm, t.entry('e1000000-0000-0000-0000-000000000008', 'e0000000-0000-0000-0000-000000000000', 'deduction', 100), 'an entry for an unknown account');
  PERFORM t.refused(d1, format('INSERT INTO public.pureice_staff_ledger(id, staff_id, kind, amount, created_by) VALUES (gen_random_uuid(), %L, %L, -9999, %L)', d1, 'deduction', d1), 'writing the ledger directly');

  -- Who sees what.
  PERFORM t.check(t.visible(d1, 'SELECT * FROM public.pureice_staff_ledger') = 3, 'd1 sees his own three entries');
  PERFORM t.check(t.visible(d2, 'SELECT * FROM public.pureice_staff_ledger') = 0, 'd2 sees none of d1''s entries');
  PERFORM t.check(t.visible(sup, 'SELECT * FROM public.pureice_staff_ledger') = 3, 'the supervisor sees every entry');
  PERFORM t.check(t.visible(d3, 'SELECT * FROM public.pureice_staff_ledger') = 0, 'an inactive account sees none');
  PERFORM t.check(t.visible(d1, 'SELECT * FROM public.pureice_shortage_decision') = 1, 'd1 sees the decision on his own closing');
  PERFORM t.check(t.visible(sup, 'SELECT * FROM public.pureice_shortage_decision') = 2, 'the supervisor sees every decision');
END $$;
