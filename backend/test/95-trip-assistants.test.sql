-- Tests for backend/95-trip-assistants.sql. Run with: backend/test/run.sh backend/95-trip-assistants.sql
-- Accounts (stub-schema.sql and 95-trip-assistants.stub.sql): a0… admin, 50… supervisor,
-- d1… / d2… / d4… distributors, d3… inactive.
-- Campaign one (11…1) is d1's on truck one: 10 units loaded and approved, 5 more loaded and not yet
-- approved. Campaign two (11…2) is d4's on truck two. Box B-1 is on truck one.
SET session_replication_role = replica; -- set the scene without the field guards
INSERT INTO public.products(id, name) VALUES ('c0000000-0000-0000-0000-000000000001', 'ثلج');
INSERT INTO public.product_variants(id, product_id, name, price) VALUES ('c1000000-0000-0000-0000-000000000001', 'c0000000-0000-0000-0000-000000000001', 'كيس', 100);
INSERT INTO public.customers(id, name, truck_id) VALUES ('cc000000-0000-0000-0000-000000000001', 'بقالة', '71000000-0000-0000-0000-000000000000');
INSERT INTO public.trips(id, truck_id, distributor_id, status, loading_approved_at) VALUES
 ('11000000-0000-0000-0000-000000000001', '71000000-0000-0000-0000-000000000000', 'd1000000-0000-0000-0000-000000000000', 'open', now()),
 ('11000000-0000-0000-0000-000000000002', '72000000-0000-0000-0000-000000000000', 'd4000000-0000-0000-0000-000000000000', 'open', now());
INSERT INTO public.trip_loads(trip_id, variant_id, qty, kind, approved_at) VALUES
 ('11000000-0000-0000-0000-000000000001', 'c1000000-0000-0000-0000-000000000001', 10, 'load', now()),
 ('11000000-0000-0000-0000-000000000001', 'c1000000-0000-0000-0000-000000000001', 5, 'load', NULL);
INSERT INTO public.pureice_deposits(id, kind, trip_id, created_by, amount) VALUES ('de000000-0000-0000-0000-000000000001', 'sale', '11000000-0000-0000-0000-000000000001', 'd2000000-0000-0000-0000-000000000000', 0);
INSERT INTO public.pureice_boxes(id, serial, status, truck_id) VALUES ('b0000000-0000-0000-0000-000000000001', 'B-1', 'truck', '71000000-0000-0000-0000-000000000000');
SET session_replication_role = DEFAULT;

-- An invoice for customer one on truck one: qty bags at 100, cash paid.
CREATE FUNCTION t.invoice(id text, trip text, by uuid, qty int, cash int) RETURNS text LANGUAGE sql AS $$
 SELECT format('SELECT public.save_invoice(%L::jsonb)', jsonb_build_object(
   'id', id, 'trip_id', trip, 'truck_id', '71000000-0000-0000-0000-000000000000', 'customer_id', 'cc000000-0000-0000-0000-000000000001',
   'created_by', by, 'cash_paid', cash,
   'items', jsonb_build_array(jsonb_build_object('id', md5(id || 'item')::uuid, 'variant_id', 'c1000000-0000-0000-0000-000000000001', 'qty', qty, 'unit_price', 100)))::text)
$$;
CREATE FUNCTION t.expense(id text, trip text, by uuid) RETURNS text LANGUAGE sql AS $$
 SELECT format('INSERT INTO public.expenses(id, truck_id, trip_id, category, amount, created_by) VALUES (%L, %L, %L, %L, 50, %L)',
   id, '71000000-0000-0000-0000-000000000000', trip, 'غداء', by)
$$;
CREATE FUNCTION t.as(uid uuid) RETURNS void LANGUAGE sql AS $$ SELECT set_config('request.jwt.claim.sub', uid::text, true) $$;

DO $$
DECLARE adm uuid := 'a0000000-0000-0000-0000-000000000000'; sup uuid := '50000000-0000-0000-0000-000000000000';
        d1 uuid := 'd1000000-0000-0000-0000-000000000000'; d2 uuid := 'd2000000-0000-0000-0000-000000000000';
        d3 uuid := 'd3000000-0000-0000-0000-000000000000'; d4 uuid := 'd4000000-0000-0000-0000-000000000000';
        trip1 text := '11000000-0000-0000-0000-000000000001'; trip2 text := '11000000-0000-0000-0000-000000000002';
        add text := 'SELECT public.pureice_set_trip_assistant(%L, %L, true)';
        drop_ text := 'SELECT public.pureice_set_trip_assistant(%L, %L, false)';
        snap jsonb;
BEGIN
  PERFORM t.as(d2);
  PERFORM t.check((SELECT public.pureice_trip_assistant_support()) = 1, 'support function answers 1 for staff');
  PERFORM t.check((SELECT public.pureice_my_open_trip()) IS NULL, 'd2 has no campaign before being added');

  -- Adding an assistant: managers only, an active distributor who is free.
  PERFORM t.refused(d1, format(add, trip1, d2), 'the main distributor adding an assistant');
  PERFORM t.refused(d2, 'INSERT INTO public.pureice_trip_assistants(trip_id, user_id, added_by) VALUES (''11000000-0000-0000-0000-000000000001'', ''d2000000-0000-0000-0000-000000000000'', ''d2000000-0000-0000-0000-000000000000'')', 'writing the assistants table directly');
  PERFORM t.ok(sup, format(add, trip1, d2), 'the supervisor adds d2 to campaign one');
  PERFORM t.ok(sup, format(add, trip1, d2), 'adding him again is accepted');
  PERFORM t.check((SELECT count(*) FROM public.pureice_trip_assistants) = 1 AND (SELECT added_by FROM public.pureice_trip_assistants) = sup, 'one assistant row, added by the supervisor');
  PERFORM t.refused(sup, format(add, trip1, d1), 'the main distributor as his own assistant');
  PERFORM t.refused(sup, format(add, trip1, d3), 'an inactive distributor');
  PERFORM t.refused(sup, format(add, trip1, adm), 'an account that is not a distributor');
  PERFORM t.refused(sup, format(add, trip1, d4), 'd4, the main distributor of another open campaign');
  PERFORM t.refused(adm, format(add, trip2, d2), 'd2 on a second campaign while assisting campaign one');
  PERFORM t.refused(sup, format(add, 'e0000000-0000-0000-0000-000000000000', d4), 'an unknown campaign');
  PERFORM t.refused(d1, 'SELECT public.pureice_trip_member(''11000000-0000-0000-0000-000000000001'', ''d2000000-0000-0000-0000-000000000000'')', 'the membership helper is internal');

  -- Who sees the assistant row.
  PERFORM t.check(t.visible(d2, 'SELECT * FROM public.pureice_trip_assistants') = 1, 'the assistant sees his row');
  PERFORM t.check(t.visible(d1, 'SELECT * FROM public.pureice_trip_assistants') = 1, 'the main distributor sees his assistant');
  PERFORM t.check(t.visible(sup, 'SELECT * FROM public.pureice_trip_assistants') = 1, 'the supervisor sees it');
  PERFORM t.check(t.visible(d4, 'SELECT * FROM public.pureice_trip_assistants') = 0, 'another distributor does not');

  -- Finding the campaign.
  PERFORM t.as(d2);
  PERFORM t.check((SELECT public.pureice_my_open_trip()->>'role') = 'assistant' AND (SELECT public.pureice_my_open_trip()->'trip'->>'id') = trip1, 'd2 finds campaign one as assistant');
  PERFORM t.check((SELECT public.pureice_my_truck()) = '71000000-0000-0000-0000-000000000000', 'd2''s truck is truck one');
  PERFORM t.as(d1);
  PERFORM t.check((SELECT public.pureice_my_open_trip()->>'role') = 'main' AND (SELECT public.pureice_my_open_trip()->'assistants') = jsonb_build_array(d2), 'd1 finds campaign one as main, with d2 as assistant');
  PERFORM t.as(d4);
  PERFORM t.check((SELECT public.pureice_my_open_trip()->'trip'->>'id') = trip2, 'd4 still finds his own campaign');

  -- The assistant sells from the same approved stock; the server keeps the stock whole.
  PERFORM t.ok(d2, t.invoice('5a000000-0000-0000-0000-000000000001', trip1, d2, 6, 200), 'd2 sells 6 bags on campaign one (200 cash, 400 credit)');
  PERFORM t.refused(d1, t.invoice('5a000000-0000-0000-0000-000000000002', trip1, d1, 5, 500), 'd1 selling 5 more of the 4 left (offline on the other phone)');
  PERFORM t.ok(d1, t.invoice('5a000000-0000-0000-0000-000000000003', trip1, d1, 4, 400), 'd1 sells the 4 left');
  PERFORM t.refused(d4, t.invoice('5a000000-0000-0000-0000-000000000004', trip1, d4, 1, 100), 'an outsider selling on campaign one');

  -- Collections, expenses, customers and boxes.
  PERFORM t.ok(d2, format('INSERT INTO public.payments(id, customer_id, truck_id, trip_id, amount, created_by) VALUES (%L, %L, %L, %L, 150, %L)',
    '9a000000-0000-0000-0000-000000000001', 'cc000000-0000-0000-0000-000000000001', '71000000-0000-0000-0000-000000000000', trip1, d2), 'd2 collects 150 of the debt');
  PERFORM t.ok(d2, t.expense('ea000000-0000-0000-0000-000000000001', trip1, d2), 'd2 records a 50 expense');
  PERFORM t.refused(d4, t.expense('ea000000-0000-0000-0000-000000000002', trip1, d4), 'an outsider recording an expense on campaign one');
  PERFORM t.ok(d2, format('INSERT INTO public.customers(id, name, truck_id) VALUES (%L, %L, %L)', 'cc000000-0000-0000-0000-000000000002', 'مطعم', '71000000-0000-0000-0000-000000000000'), 'd2 adds a customer on truck one');
  PERFORM t.refused(d4, format('INSERT INTO public.customers(id, name, truck_id) VALUES (%L, %L, %L)', 'cc000000-0000-0000-0000-000000000003', 'مقهى', '71000000-0000-0000-0000-000000000000'), 'an outsider adding a customer on truck one');
  PERFORM t.ok(d2, format('SELECT public.pureice_box_move(%L::jsonb)', jsonb_build_object('id', 'ba000000-0000-0000-0000-000000000001', 'action', 'deliver', 'trip_id', trip1,
    'customer_id', 'cc000000-0000-0000-0000-000000000001', 'created_by', d2, 'boxes', jsonb_build_array(jsonb_build_object('id', 'ba000000-0000-0000-0000-000000000001', 'serial', 'B-1')))),
    'd2 delivers box B-1');
  PERFORM t.check((SELECT status = 'customer' FROM public.pureice_boxes WHERE serial = 'B-1'), 'the box is now with the customer');
  PERFORM t.refused(d4, format('SELECT public.pureice_box_move(%L::jsonb)', jsonb_build_object('id', 'ba000000-0000-0000-0000-000000000002', 'action', 'collect', 'trip_id', trip1,
    'customer_id', 'cc000000-0000-0000-0000-000000000001', 'created_by', d4, 'boxes', jsonb_build_array(jsonb_build_object('id', 'ba000000-0000-0000-0000-000000000002', 'serial', 'B-1')))),
    'an outsider moving a box on campaign one');

  -- Both phones see the whole campaign: every sale and the stock left.
  PERFORM t.as(d1);
  snap := public.accounting_snapshot(trip1::uuid);
  PERFORM t.check(jsonb_array_length(snap->'sales') = 2 AND jsonb_array_length(snap->'payments') = 1 AND jsonb_array_length(snap->'expenses') = 1, 'd1''s snapshot holds both sales, d2''s collection and expense');
  PERFORM t.as(d2);
  snap := public.accounting_snapshot(trip1::uuid);
  PERFORM t.check(jsonb_array_length(snap->'sales') = 2, 'd2''s snapshot holds both sales');
  PERFORM t.check((SELECT sold FROM public.campaign_stock(trip1::uuid)) = 10, 'd2 sees all 10 bags sold');
  PERFORM t.as(d4);
  snap := public.accounting_snapshot(trip1::uuid);
  PERFORM t.check(jsonb_array_length(snap->'sales') = 0, 'an outsider''s snapshot of campaign one is empty');
  PERFORM t.check(t.visible(d2, format('SELECT * FROM public.trips WHERE id = %L', trip1)) = 1, 'd2 reads campaign one');
  PERFORM t.check(t.visible(d1, 'SELECT * FROM public.pureice_deposits') = 1, 'd1 sees d2''s deposit on his campaign');
  PERFORM t.check(t.visible(d4, 'SELECT * FROM public.pureice_deposits') = 0, 'an outsider does not');

  -- Load approval and closing stay with the main distributor.
  PERFORM t.refused(d2, format('SELECT public.approve_trip_loads(%L)', trip1), 'the assistant approving the load');
  PERFORM t.ok(d1, format('SELECT public.approve_trip_loads(%L)', trip1), 'd1 approves the 5 new bags');
  PERFORM t.refused(d2, format('SELECT public.submit_closing(%L, %L, 0, NULL)', 'c5000000-0000-0000-0000-000000000002', trip1), 'the assistant submitting the closing');

  -- Removing the assistant: his next records are refused; adding him back restores them.
  PERFORM t.ok(sup, format(drop_, trip1, d2), 'the supervisor removes d2');
  PERFORM t.ok(sup, format(drop_, trip1, d2), 'removing him again is accepted');
  PERFORM t.as(d2);
  PERFORM t.check((SELECT public.pureice_my_open_trip()) IS NULL, 'd2 has no campaign once removed');
  PERFORM t.refused(d2, t.invoice('5a000000-0000-0000-0000-000000000005', trip1, d2, 1, 100), 'd2 selling after removal');
  PERFORM t.ok(adm, format(add, trip1, d2), 'the admin adds d2 back');
  PERFORM t.ok(d2, t.invoice('5a000000-0000-0000-0000-000000000005', trip1, d2, 1, 100), 'd2 sells again');

  -- Closing: the expected cash includes the assistant's: 200 + 400 + 100 cash, + 150 collected, − 50 expense.
  PERFORM t.ok(d1, format('SELECT public.submit_closing(%L, %L, 800, NULL)', 'c5000000-0000-0000-0000-000000000001', trip1), 'd1 submits the closing with 800 counted');
  PERFORM t.check((SELECT expected_cash = 800 AND difference = 0 FROM public.closings WHERE trip_id = trip1::uuid), 'expected cash 800 includes the assistant''s cash');
  PERFORM t.refused(d2, t.invoice('5a000000-0000-0000-0000-000000000006', trip1, d2, 1, 100), 'the assistant selling after the closing is sent');
  PERFORM t.ok(sup, format(drop_, trip1, d2), 'removing after the closing is sent is accepted');
  PERFORM t.refused(sup, format(add, trip1, d2), 'adding after the closing is sent');

  -- The main distributor of an open campaign is unchanged.
  PERFORM t.ok(d4, format('INSERT INTO public.customers(id, name, truck_id) VALUES (%L, %L, %L)', 'cc000000-0000-0000-0000-000000000004', 'فرن', '72000000-0000-0000-0000-000000000000'), 'd4 still adds customers on his own truck');
END $$;
