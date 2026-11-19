-- Tests for backend/94-truck-care.sql. Run with: backend/test/run.sh backend/94-truck-care.sql
-- Accounts (stub-schema.sql): a0… admin, 50… supervisor, d1… / d2… distributors, d3… inactive.
-- Truck one (71…) is on d1's open campaign; truck two (72…) is on no open campaign.
INSERT INTO public.trips(id, truck_id, distributor_id, status) VALUES
 ('11000000-0000-0000-0000-000000000001', '71000000-0000-0000-0000-000000000000', 'd1000000-0000-0000-0000-000000000000', 'open');
INSERT INTO public.expenses(truck_id, odometer) VALUES
 ('71000000-0000-0000-0000-000000000000', 120400), ('71000000-0000-0000-0000-000000000000', 121050), ('72000000-0000-0000-0000-000000000000', 88000);

CREATE FUNCTION t.issue(id text, truck text, title text, slip text DEFAULT NULL) RETURNS text LANGUAGE sql AS $$
 SELECT format('SELECT public.pureice_report_truck_issue(%L::jsonb)', jsonb_build_object(
   'id', id, 'truck_id', truck, 'title', title, 'details', 'صوت عند الفرامل', 'severity', 'urgent', 'photo_path', slip)::text)
$$;

DO $$
DECLARE adm uuid := 'a0000000-0000-0000-0000-000000000000'; sup uuid := '50000000-0000-0000-0000-000000000000';
        d1 uuid := 'd1000000-0000-0000-0000-000000000000'; d2 uuid := 'd2000000-0000-0000-0000-000000000000';
        d3 uuid := 'd3000000-0000-0000-0000-000000000000';
        t1 text := '71000000-0000-0000-0000-000000000000'; t2 text := '72000000-0000-0000-0000-000000000000';
BEGIN
  PERFORM set_config('request.jwt.claim.sub', d1::text, true);
  PERFORM t.check((SELECT public.pureice_truck_care_support()) = 1, 'support function answers 1 for staff');

  -- Service plans and services: managers only.
  PERFORM t.ok(sup, format('SELECT public.pureice_set_service_plan(%L, 5000, 90)', t1), 'the supervisor sets truck one''s plan: every 5,000 km or 90 days');
  PERFORM t.refused(d1, format('SELECT public.pureice_set_service_plan(%L, 5000, 90)', t1), 'a distributor setting a plan');
  PERFORM t.refused(sup, format('SELECT public.pureice_set_service_plan(%L, 0, NULL)', t1), 'a plan with no positive interval');
  PERFORM t.refused(sup, format('SELECT public.pureice_set_service_plan(%L, 5000, 90)', 'e0000000-0000-0000-0000-000000000000'), 'a plan for an unknown truck');
  PERFORM t.ok(adm, format('SELECT public.pureice_record_service(%L, %L, 118000, %L, 25000, %L)', 'f1000000-0000-0000-0000-000000000001', t1, '2026-10-01', 'تغيير زيت'), 'the admin records a service at 118,000 km');
  PERFORM t.check((SELECT last_service_km = 118000 AND last_service_date = '2026-10-01' AND interval_km = 5000 FROM public.pureice_truck_service_plan WHERE truck_id = t1::uuid), 'the service updates the plan and keeps its intervals');
  PERFORM t.ok(adm, format('SELECT public.pureice_record_service(%L, %L, 118000, %L, 25000, %L)', 'f1000000-0000-0000-0000-000000000001', t1, '2026-10-01', 'تغيير زيت'), 'repeating the same service is accepted');
  PERFORM t.check((SELECT count(*) FROM public.pureice_truck_services) = 1, 'still one service after the repeat');
  PERFORM t.refused(sup, format('SELECT public.pureice_record_service(%L, %L, 117000, %L, 1, NULL)', 'f1000000-0000-0000-0000-000000000002', t1, '2026-09-01'), 'a service older than the last one');
  PERFORM t.refused(d1, format('SELECT public.pureice_record_service(%L, %L, 119000, %L, 1, NULL)', 'f1000000-0000-0000-0000-000000000003', t1, '2026-10-05'), 'a distributor recording a service');
  PERFORM t.ok(sup, format('SELECT public.pureice_record_service(%L, %L, 88000, %L, 0, NULL)', 'f1000000-0000-0000-0000-000000000004', t2, '2026-11-01'), 'a service for a truck with no plan yet');
  PERFORM t.check((SELECT last_service_km = 88000 AND interval_km IS NULL FROM public.pureice_truck_service_plan WHERE truck_id = t2::uuid), 'it starts a plan row with no intervals');

  -- Current odometer: the latest reading from fuel expenses or the last service.
  PERFORM set_config('request.jwt.claim.sub', d1::text, true);
  PERFORM t.check((SELECT public.pureice_truck_km(t1::uuid)) = 121050, 'd1 reads his truck''s km: 121,050');
  PERFORM t.refused(d1, format('SELECT public.pureice_truck_km(%L)', t2), 'd1 reading a truck he is not driving');
  PERFORM set_config('request.jwt.claim.sub', sup::text, true);
  PERFORM t.check((SELECT public.pureice_truck_km(t2::uuid)) = 88000, 'a supervisor reads any truck''s km');

  -- Problem reports: a distributor for the truck of his open campaign; managers for any truck.
  PERFORM t.ok(d1, t.issue('b1000000-0000-0000-0000-000000000001', t1, 'الفرامل', d1::text || '/issue-1.jpg'), 'd1 reports an urgent brake problem on his truck');
  PERFORM t.check((SELECT status = 'open' AND reported_by = d1 AND trip_id = '11000000-0000-0000-0000-000000000001' FROM public.pureice_truck_issues), 'the report is open and tied to his campaign');
  PERFORM t.ok(d1, t.issue('b1000000-0000-0000-0000-000000000001', t1, 'الفرامل', d1::text || '/issue-1.jpg'), 'repeating the same report is accepted');
  PERFORM t.refused(d1, t.issue('b1000000-0000-0000-0000-000000000001', t1, 'شيء آخر'), 'reusing a report id with other values');
  PERFORM t.refused(d1, t.issue('b1000000-0000-0000-0000-000000000002', t2, 'الإطار'), 'd1 reporting for a truck he is not driving');
  PERFORM t.refused(d2, t.issue('b1000000-0000-0000-0000-000000000003', t1, 'الإطار'), 'd2 with no open campaign reporting');
  PERFORM t.refused(d1, t.issue('b1000000-0000-0000-0000-000000000004', t1, 'الإطار', d2::text || '/x.jpg'), 'a photo in someone else''s folder');
  PERFORM t.refused(d1, t.issue('b1000000-0000-0000-0000-000000000005', t1, '  '), 'a report without a title');
  PERFORM t.refused(d3, t.issue('b1000000-0000-0000-0000-000000000006', t1, 'الإطار'), 'an inactive account reporting');
  PERFORM t.ok(sup, t.issue('b1000000-0000-0000-0000-000000000007', t2, 'التبريد'), 'a supervisor reports for any truck');

  -- Following a report up: managers only, open → in progress → fixed.
  PERFORM t.refused(d1, format('SELECT public.pureice_update_truck_issue(%L, %L, NULL)', 'b1000000-0000-0000-0000-000000000001', 'fixed'), 'a distributor closing his own report');
  PERFORM t.ok(sup, format('SELECT public.pureice_update_truck_issue(%L, %L, NULL)', 'b1000000-0000-0000-0000-000000000001', 'in_progress'), 'the supervisor starts work on it');
  PERFORM t.ok(sup, format('SELECT public.pureice_update_truck_issue(%L, %L, %L)', 'b1000000-0000-0000-0000-000000000001', 'fixed', 'غيرنا الفحمات'), 'and marks it fixed');
  PERFORM t.check((SELECT status = 'fixed' AND resolved_by = sup AND resolution_note = 'غيرنا الفحمات' AND resolved_at IS NOT NULL FROM public.pureice_truck_issues WHERE id = 'b1000000-0000-0000-0000-000000000001'), 'fixed records who, when and how');
  PERFORM t.refused(sup, format('SELECT public.pureice_update_truck_issue(%L, %L, NULL)', 'b1000000-0000-0000-0000-000000000001', 'closed'), 'an unknown status');

  -- Who sees what.
  PERFORM t.check(t.visible(d1, 'SELECT * FROM public.pureice_truck_issues') = 1, 'd1 sees the report for his truck only');
  PERFORM t.check(t.visible(d2, 'SELECT * FROM public.pureice_truck_issues') = 0, 'd2 with no campaign sees none');
  PERFORM t.check(t.visible(sup, 'SELECT * FROM public.pureice_truck_issues') = 2, 'a supervisor sees every report');
  PERFORM t.check(t.visible(d1, 'SELECT * FROM public.pureice_truck_service_plan') = 2, 'staff see the service plans');
  PERFORM t.check(t.visible(d1, 'SELECT * FROM public.pureice_truck_services') = 0, 'a distributor does not see service costs');
  PERFORM t.check(t.visible(sup, 'SELECT * FROM public.pureice_truck_services') = 2, 'a supervisor sees every service');
  PERFORM t.check(t.visible(d3, 'SELECT * FROM public.pureice_truck_service_plan') = 0, 'an inactive account sees nothing');
  PERFORM t.refused(d1, format('INSERT INTO public.pureice_truck_issues(id, truck_id, title, reported_by) VALUES (gen_random_uuid(), %L, %L, %L)', t1, 'x', d1), 'writing reports directly');
END $$;
