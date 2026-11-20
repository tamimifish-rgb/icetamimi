-- Tests for backend/96-shared-charges-and-map.sql. Run with: backend/test/run.sh backend/96-shared-charges-and-map.sql
-- Accounts: a0… admin, 50… supervisor, d1… / d2… / d4… distributors, d3… inactive.
-- Campaign 1 (11…1, open): d1 leads, d2 assists. 10 bags of v1 (100 each) and 4 of v2 (50 each)
--   loaded; d1 sold 6 of v1 for 600 cash; d1's closing c…1 is sent: counted 299, 301 short.
-- Campaign 2 (closed): d4 leads, d2 and d1 assist; approved closing c…2, 100 short.
-- Campaign 3 (closed): d1 alone; approved closing c…3, 50 short.
-- Campaign 4 (open): d4; closing c…4 already approved while 2 bags are still on the truck.
-- Campaign 5 (open): d4; closing c…5 sent, nothing left on the truck.
SET session_replication_role = replica;
INSERT INTO public.products(id, name) VALUES ('c0000000-0000-0000-0000-000000000001', 'ثلج');
INSERT INTO public.product_variants(id, product_id, name, price) VALUES
 ('c1000000-0000-0000-0000-000000000001', 'c0000000-0000-0000-0000-000000000001', 'كيس', 100),
 ('c1000000-0000-0000-0000-000000000002', 'c0000000-0000-0000-0000-000000000001', 'قالب', 50);
INSERT INTO public.customers(id, name, truck_id) VALUES ('cc000000-0000-0000-0000-000000000001', 'بقالة', '71000000-0000-0000-0000-000000000000');
INSERT INTO public.trips(id, truck_id, distributor_id, status, loading_approved_at) VALUES
 ('11000000-0000-0000-0000-000000000001', '71000000-0000-0000-0000-000000000000', 'd1000000-0000-0000-0000-000000000000', 'open', now()),
 ('11000000-0000-0000-0000-000000000002', '72000000-0000-0000-0000-000000000000', 'd4000000-0000-0000-0000-000000000000', 'closed', now()),
 ('11000000-0000-0000-0000-000000000003', '72000000-0000-0000-0000-000000000000', 'd1000000-0000-0000-0000-000000000000', 'closed', now()),
 ('11000000-0000-0000-0000-000000000004', '72000000-0000-0000-0000-000000000000', 'd4000000-0000-0000-0000-000000000000', 'open', now()),
 ('11000000-0000-0000-0000-000000000005', '72000000-0000-0000-0000-000000000000', 'd4000000-0000-0000-0000-000000000000', 'open', now());
INSERT INTO public.pureice_trip_assistants(trip_id, user_id, added_by, added_at) VALUES
 ('11000000-0000-0000-0000-000000000001', 'd2000000-0000-0000-0000-000000000000', '50000000-0000-0000-0000-000000000000', now()),
 ('11000000-0000-0000-0000-000000000002', 'd2000000-0000-0000-0000-000000000000', '50000000-0000-0000-0000-000000000000', now()),
 ('11000000-0000-0000-0000-000000000002', 'd1000000-0000-0000-0000-000000000000', '50000000-0000-0000-0000-000000000000', now());
INSERT INTO public.trip_loads(trip_id, variant_id, qty, kind, approved_at) VALUES
 ('11000000-0000-0000-0000-000000000001', 'c1000000-0000-0000-0000-000000000001', 10, 'load', now()),
 ('11000000-0000-0000-0000-000000000001', 'c1000000-0000-0000-0000-000000000002', 4, 'load', now()),
 ('11000000-0000-0000-0000-000000000004', 'c1000000-0000-0000-0000-000000000001', 2, 'load', now());
INSERT INTO public.sales(id, trip_id, truck_id, customer_id, total, cash_paid, credit_amount, created_by, created_at) VALUES
 ('5a000000-0000-0000-0000-000000000001', '11000000-0000-0000-0000-000000000001', '71000000-0000-0000-0000-000000000000', 'cc000000-0000-0000-0000-000000000001', 600, 600, 0, 'd1000000-0000-0000-0000-000000000000', now());
INSERT INTO public.sale_items(id, sale_id, variant_id, qty, unit_price, line_total) VALUES
 ('5b000000-0000-0000-0000-000000000001', '5a000000-0000-0000-0000-000000000001', 'c1000000-0000-0000-0000-000000000001', 6, 100, 600);
INSERT INTO public.closings(id, trip_id, expected_cash, counted_cash, difference, status, created_by) VALUES
 ('c5000000-0000-0000-0000-000000000001', '11000000-0000-0000-0000-000000000001', 600, 299, -301, 'submitted', 'd1000000-0000-0000-0000-000000000000'),
 ('c5000000-0000-0000-0000-000000000002', '11000000-0000-0000-0000-000000000002', 1000, 900, -100, 'approved', 'd4000000-0000-0000-0000-000000000000'),
 ('c5000000-0000-0000-0000-000000000003', '11000000-0000-0000-0000-000000000003', 500, 450, -50, 'approved', 'd1000000-0000-0000-0000-000000000000'),
 ('c5000000-0000-0000-0000-000000000004', '11000000-0000-0000-0000-000000000004', 0, 0, 0, 'approved', 'd4000000-0000-0000-0000-000000000000'),
 ('c5000000-0000-0000-0000-000000000005', '11000000-0000-0000-0000-000000000005', 0, 0, 0, 'submitted', 'd4000000-0000-0000-0000-000000000000');
INSERT INTO public.gps_points(id, trip_id, truck_id, lat, lng, recorded_at, created_by) VALUES
 ('9e000000-0000-0000-0000-000000000001', '11000000-0000-0000-0000-000000000001', '71000000-0000-0000-0000-000000000000', 14.53, 49.12, now() - interval '2 hours', 'd1000000-0000-0000-0000-000000000000'),
 ('9e000000-0000-0000-0000-000000000002', '11000000-0000-0000-0000-000000000001', '71000000-0000-0000-0000-000000000000', 14.54, 49.13, now() - interval '1 hour', 'd1000000-0000-0000-0000-000000000000'),
 ('9e000000-0000-0000-0000-000000000003', '11000000-0000-0000-0000-000000000001', '71000000-0000-0000-0000-000000000000', 14.55, 49.14, now(), 'd1000000-0000-0000-0000-000000000000');
SET session_replication_role = DEFAULT;
GRANT INSERT ON public.trip_loads TO authenticated; -- as on the live project: managers record returns directly

CREATE FUNCTION t.as(uid uuid) RETURNS void LANGUAGE sql AS $$ SELECT set_config('request.jwt.claim.sub', uid::text, true) $$;
CREATE FUNCTION t.settle(closing text) RETURNS text LANGUAGE sql AS $$ SELECT format('SELECT public.pureice_settle_missing_stock(%L::uuid)', closing) $$;
CREATE FUNCTION t.decide(closing text) RETURNS text LANGUAGE sql AS $$ SELECT format('SELECT public.pureice_decide_shortage(%L::uuid, true, NULL)', closing) $$;
CREATE FUNCTION t.owed(staff uuid, k text, closing text) RETURNS numeric LANGUAGE sql AS $$
 SELECT sum(amount) FROM public.pureice_staff_ledger WHERE staff_id = staff AND kind = k AND closing_id = closing::uuid $$;

DO $$
DECLARE adm uuid := 'a0000000-0000-0000-0000-000000000000'; sup uuid := '50000000-0000-0000-0000-000000000000';
        d1 uuid := 'd1000000-0000-0000-0000-000000000000'; d2 uuid := 'd2000000-0000-0000-0000-000000000000';
        d4 uuid := 'd4000000-0000-0000-0000-000000000000';
        trip1 text := '11000000-0000-0000-0000-000000000001';
        c1 text := 'c5000000-0000-0000-0000-000000000001'; c2 text := 'c5000000-0000-0000-0000-000000000002';
        c3 text := 'c5000000-0000-0000-0000-000000000003'; c4 text := 'c5000000-0000-0000-0000-000000000004';
        c5 text := 'c5000000-0000-0000-0000-000000000005';
        v1 text := 'c1000000-0000-0000-0000-000000000001'; v2 text := 'c1000000-0000-0000-0000-000000000002';
        summary jsonb;
BEGIN
  PERFORM t.as(d2);
  PERFORM t.check(public.pureice_missing_stock_support() = 1 AND public.pureice_map_extras_support() = 1, 'both support functions answer 1 for staff');
  PERFORM t.refused(sup, format('SELECT * FROM public.pureice_split_charge(%L, %L, 100)', trip1, d1), 'the split helper is internal');

  -- Missing returns: the supervisor records what came back, then settles the rest.
  PERFORM t.ok(sup, format('INSERT INTO public.trip_loads(trip_id, variant_id, qty, kind) VALUES (%L, %L, 4, %L)', trip1, v2, 'return'), 'the supervisor records all 4 of v2 returned');
  PERFORM t.refused(sup, format('SELECT public.review_closing(%L, %L, NULL)', c1, 'approved'), 'approving with 4 bags of v1 unaccounted for');
  PERFORM t.refused(d1, t.settle(c1), 'the main distributor settling');
  PERFORM t.refused(d2, t.settle(c1), 'the assistant settling');
  PERFORM t.ok(sup, t.settle(c1), 'the supervisor settles the missing returns');
  PERFORM t.check((SELECT qty = 4 AND unit_price = 100 AND value = 400 FROM public.pureice_missing_stock WHERE closing_id = c1::uuid AND variant_id = v1::uuid)
    AND (SELECT count(*) FROM public.pureice_missing_stock WHERE closing_id = c1::uuid) = 1, '4 bags of v1 missing, valued at 400; v2 is complete');
  PERFORM t.check((SELECT count(*) FROM public.trip_loads WHERE trip_id = trip1::uuid AND kind = 'damaged' AND qty = 4 AND created_by = sup) = 1, 'they leave the truck as a damaged row by the supervisor');
  PERFORM t.check(t.owed(d1, 'stock_shortage', c1) = 200 AND t.owed(d2, 'stock_shortage', c1) = 200, 'charged 200 to d1 and 200 to his assistant d2');
  PERFORM t.ok(sup, t.settle(c1), 'settling again is accepted');
  PERFORM t.as(sup);
  summary := public.pureice_settle_missing_stock(c1::uuid);
  PERFORM t.check((summary->>'total')::numeric = 400 AND jsonb_array_length(summary->'shares') = 2 AND jsonb_array_length(summary->'items') = 1, 'it returns the same settlement: 400 in two shares');
  PERFORM t.check((SELECT count(*) FROM public.pureice_staff_ledger WHERE kind = 'stock_shortage') = 2, 'still two charges after the repeat');
  PERFORM t.check(t.visible(d2, 'SELECT * FROM public.pureice_missing_stock') = 1, 'the assistant sees the campaign''s missing bags');
  PERFORM t.check(t.visible(d4, 'SELECT * FROM public.pureice_missing_stock') = 0, 'another distributor does not');
  PERFORM t.ok(sup, format('SELECT public.review_closing(%L, %L, NULL)', c1, 'approved'), 'now the closing can be approved');
  PERFORM t.ok(sup, t.settle(c1), 'settling after approval returns what was settled');
  PERFORM t.refused(sup, t.settle(c4), 'settling an approved closing');
  PERFORM t.refused(sup, t.settle(c5), 'settling a closing with nothing missing');

  -- Cash shortages, split equally.
  PERFORM t.ok(sup, t.decide(c1), 'the supervisor charges campaign 1''s 301 shortage');
  PERFORM t.check(t.owed(d1, 'shortage', c1) = 150.50 AND t.owed(d2, 'shortage', c1) = 150.50, '150.50 each for d1 and d2');
  PERFORM t.ok(sup, t.decide(c1), 'repeating the decision is accepted');
  PERFORM t.check((SELECT count(*) FROM public.pureice_staff_ledger WHERE kind = 'shortage' AND closing_id = c1::uuid) = 2, 'still two shares');
  PERFORM t.ok(adm, t.decide(c2), 'the admin charges campaign 2''s 100 shortage, three people');
  PERFORM t.check(t.owed(d4, 'shortage', c2) = 33.34 AND t.owed(d2, 'shortage', c2) = 33.33 AND t.owed(d1, 'shortage', c2) = 33.33,
    '33.34 for the main distributor d4, 33.33 for each assistant: 100 exactly');
  PERFORM t.ok(sup, t.decide(c3), 'a shortage with no assistant');
  PERFORM t.check(t.owed(d1, 'shortage', c3) = 50 AND (SELECT count(*) FROM public.pureice_staff_ledger WHERE closing_id = c3::uuid) = 1, 'all 50 on the main distributor, as before');
  PERFORM t.refused(d1, t.decide(c2), 'a distributor deciding');
  PERFORM t.check(t.visible(d2, 'SELECT * FROM public.pureice_staff_ledger') = 3, 'the assistant sees only his own three entries');

  -- The ledger rules.
  BEGIN
    INSERT INTO public.pureice_staff_ledger(id, staff_id, kind, amount, created_by) VALUES (gen_random_uuid(), d1, 'stock_shortage', 10, sup);
    RAISE EXCEPTION 'FAIL: a missing-return charge without a closing was accepted';
  EXCEPTION WHEN check_violation THEN RAISE NOTICE 'ok refused: a missing-return charge without a closing';
  END;
  BEGIN
    INSERT INTO public.pureice_staff_ledger(id, staff_id, kind, amount, closing_id, created_by) VALUES (gen_random_uuid(), d1, 'shortage', 1, c1::uuid, sup);
    RAISE EXCEPTION 'FAIL: a second shortage row for the same person and closing was accepted';
  EXCEPTION WHEN unique_violation THEN RAISE NOTICE 'ok refused: a second shortage row for the same person and closing';
  END;

  -- The route and the customers' products.
  PERFORM t.as(d2);
  PERFORM t.check((SELECT count(*) FROM public.pureice_trip_route(trip1::uuid)) = 3, 'the assistant reads the campaign''s 3 route points');
  PERFORM t.check((SELECT lat FROM public.pureice_trip_route(trip1::uuid) LIMIT 1) = 14.53, 'oldest first');
  PERFORM t.as(d4);
  PERFORM t.check((SELECT count(*) FROM public.pureice_trip_route(trip1::uuid)) = 0, 'another distributor reads none');
  PERFORM t.as(sup);
  PERFORM t.check((SELECT count(*) FROM public.pureice_trip_route(trip1::uuid)) = 3, 'the supervisor reads them');
  PERFORM t.as(d2);
  PERFORM t.check(EXISTS (SELECT 1 FROM public.pureice_customer_products() WHERE customer_id = 'cc000000-0000-0000-0000-000000000001' AND variant_id = v1::uuid)
    AND NOT EXISTS (SELECT 1 FROM public.pureice_customer_products() WHERE variant_id = v2::uuid), 'customer 1 bought v1, nobody bought v2');
  PERFORM t.as('d3000000-0000-0000-0000-000000000000');
  PERFORM t.check(NOT EXISTS (SELECT 1 FROM public.pureice_customer_products()), 'an inactive account gets nothing');
END $$;
