-- Tamimi Ice (live Supabase project) — truck service reminders and problem reports.
-- Used by the native Android app only. Run once, as a single script. Additive: nothing existing changes.
--
-- Managers keep a service plan per truck (every N km and/or N days) and record each service; the
-- app warns when a truck is due. Distributors report problems with the truck of their open campaign
-- (with a photo); managers follow each report from open to fixed.
--
-- Services go in their own table, not maintenance_jobs: a maintenance job must belong to an open
-- campaign (finance_lock_trip), while a workshop service usually happens outside one.
-- Tests: backend/test/run.sh backend/94-truck-care.sql
BEGIN;

CREATE TABLE public.pureice_truck_service_plan (
 truck_id uuid PRIMARY KEY REFERENCES public.trucks(id),
 interval_km integer CHECK (interval_km > 0),
 interval_days integer CHECK (interval_days > 0),
 last_service_km numeric CHECK (last_service_km >= 0),
 last_service_date date,
 updated_by uuid REFERENCES public.profiles(id),
 updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE public.pureice_truck_services (
 id uuid PRIMARY KEY,
 truck_id uuid NOT NULL REFERENCES public.trucks(id),
 km numeric NOT NULL CHECK (km >= 0),
 service_date date NOT NULL,
 cost numeric NOT NULL DEFAULT 0 CHECK (cost >= 0),
 note text,
 created_by uuid NOT NULL REFERENCES public.profiles(id),
 created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX pureice_truck_services_truck ON public.pureice_truck_services(truck_id, service_date);

CREATE TABLE public.pureice_truck_issues (
 id uuid PRIMARY KEY,                                  -- generated on the phone
 truck_id uuid NOT NULL REFERENCES public.trucks(id),
 trip_id uuid REFERENCES public.trips(id),
 title text NOT NULL CHECK (btrim(title) <> ''),
 details text,
 severity text NOT NULL DEFAULT 'normal' CHECK (severity IN ('normal', 'urgent')),
 photo_path text,
 status text NOT NULL DEFAULT 'open' CHECK (status IN ('open', 'in_progress', 'fixed')),
 reported_by uuid NOT NULL REFERENCES public.profiles(id),
 reported_at timestamptz NOT NULL DEFAULT now(),
 resolved_by uuid REFERENCES public.profiles(id),
 resolved_at timestamptz,
 resolution_note text
);
CREATE INDEX pureice_truck_issues_truck ON public.pureice_truck_issues(truck_id, status);

DO $$
DECLARE t text;
BEGIN
 FOREACH t IN ARRAY ARRAY['pureice_truck_service_plan', 'pureice_truck_services', 'pureice_truck_issues'] LOOP
  EXECUTE format('REVOKE ALL ON public.%I FROM PUBLIC, anon, authenticated', t);
  EXECUTE format('GRANT SELECT ON public.%I TO authenticated', t);
  EXECUTE format('GRANT ALL ON public.%I TO service_role', t);
  EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t);
  EXECUTE format('CREATE POLICY active_account_backstop ON public.%I AS RESTRICTIVE FOR ALL TO authenticated USING ((SELECT public.active_staff(auth.uid()))) WITH CHECK ((SELECT public.active_staff(auth.uid())))', t);
 END LOOP;
END $$;

-- The truck of the caller's open campaign, or null.
CREATE FUNCTION public.pureice_my_truck() RETURNS uuid
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public AS
$$ SELECT truck_id FROM public.trips WHERE distributor_id = auth.uid() AND status = 'open' ORDER BY created_at DESC LIMIT 1 $$;
REVOKE ALL ON FUNCTION public.pureice_my_truck() FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.pureice_my_truck() TO authenticated;

CREATE POLICY staff_read ON public.pureice_truck_service_plan FOR SELECT TO authenticated
  USING ((SELECT public.is_staff(auth.uid())));
CREATE POLICY manager_read ON public.pureice_truck_services FOR SELECT TO authenticated
  USING ((SELECT public.is_manager(auth.uid())));
CREATE POLICY own_truck_or_manager_read ON public.pureice_truck_issues FOR SELECT TO authenticated
  USING ((SELECT public.is_manager(auth.uid())) OR truck_id = (SELECT public.pureice_my_truck()));

-- The app shows the maintenance screens only when this answers 1.
CREATE FUNCTION public.pureice_truck_care_support() RETURNS integer
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public AS
$$ SELECT CASE WHEN public.is_staff(auth.uid()) THEN 1 ELSE 0 END $$;
REVOKE ALL ON FUNCTION public.pureice_truck_care_support() FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.pureice_truck_care_support() TO authenticated;

-- The truck's latest known odometer: fuel expense readings or the last service, whichever is higher.
-- Managers for any truck; a distributor for the truck of his open campaign.
CREATE FUNCTION public.pureice_truck_km(p_truck uuid) RETURNS numeric
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = pg_catalog, public AS $$
BEGIN
 IF NOT (public.is_manager(auth.uid()) OR (public.is_staff(auth.uid()) AND p_truck = public.pureice_my_truck())) THEN
  RAISE EXCEPTION 'غير مصرح' USING ERRCODE = '42501';
 END IF;
 RETURN greatest(
  (SELECT max(odometer) FROM public.expenses WHERE truck_id = p_truck),
  (SELECT last_service_km FROM public.pureice_truck_service_plan WHERE truck_id = p_truck));
END $$;
REVOKE ALL ON FUNCTION public.pureice_truck_km(uuid) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.pureice_truck_km(uuid) TO authenticated;

-- Managers: service every p_interval_km and/or p_interval_days (at least one).
CREATE FUNCTION public.pureice_set_service_plan(p_truck uuid, p_interval_km integer, p_interval_days integer)
RETURNS public.pureice_truck_service_plan
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public AS $$
DECLARE r public.pureice_truck_service_plan;
BEGIN
 IF NOT public.is_manager(auth.uid()) THEN RAISE EXCEPTION 'غير مصرح' USING ERRCODE = '42501'; END IF;
 IF NOT EXISTS (SELECT 1 FROM public.trucks WHERE id = p_truck) THEN RAISE EXCEPTION 'الشاحنة غير موجودة' USING ERRCODE = 'P0002'; END IF;
 IF coalesce(p_interval_km, 0) <= 0 AND coalesce(p_interval_days, 0) <= 0
    OR p_interval_km <= 0 OR p_interval_days <= 0 THEN
  RAISE EXCEPTION 'حدد عدد الكيلومترات أو الأيام بين كل صيانة' USING ERRCODE = '22023';
 END IF;
 INSERT INTO public.pureice_truck_service_plan(truck_id, interval_km, interval_days, updated_by)
 VALUES (p_truck, p_interval_km, p_interval_days, auth.uid())
 ON CONFLICT (truck_id) DO UPDATE SET interval_km = EXCLUDED.interval_km, interval_days = EXCLUDED.interval_days,
   updated_by = EXCLUDED.updated_by, updated_at = now()
 RETURNING * INTO r;
 RETURN r;
END $$;
REVOKE ALL ON FUNCTION public.pureice_set_service_plan(uuid, integer, integer) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.pureice_set_service_plan(uuid, integer, integer) TO authenticated;

-- Managers: a service done at p_km on p_date; it becomes the plan's last service. Idempotent on p_id.
CREATE FUNCTION public.pureice_record_service(p_id uuid, p_truck uuid, p_km numeric, p_date date, p_cost numeric, p_note text)
RETURNS public.pureice_truck_services
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public AS $$
DECLARE r public.pureice_truck_services; plan public.pureice_truck_service_plan; note text := nullif(btrim(p_note), '');
BEGIN
 IF NOT public.is_manager(auth.uid()) THEN RAISE EXCEPTION 'غير مصرح' USING ERRCODE = '42501'; END IF;
 SELECT * INTO r FROM public.pureice_truck_services WHERE id = p_id;
 IF FOUND THEN
  IF r.truck_id = p_truck AND r.km = p_km AND r.service_date = p_date AND r.cost = coalesce(p_cost, 0) AND r.note IS NOT DISTINCT FROM note THEN RETURN r; END IF;
  RAISE EXCEPTION 'تعارض معرف السجل' USING ERRCODE = '23505';
 END IF;
 IF p_id IS NULL OR p_km IS NULL OR p_km < 0 OR p_date IS NULL OR coalesce(p_cost, 0) < 0 THEN
  RAISE EXCEPTION 'بيانات الصيانة غير صحيحة' USING ERRCODE = '22023';
 END IF;
 IF NOT EXISTS (SELECT 1 FROM public.trucks WHERE id = p_truck) THEN RAISE EXCEPTION 'الشاحنة غير موجودة' USING ERRCODE = 'P0002'; END IF;
 SELECT * INTO plan FROM public.pureice_truck_service_plan WHERE truck_id = p_truck FOR UPDATE;
 IF FOUND AND (p_km < plan.last_service_km OR p_date < plan.last_service_date) THEN
  RAISE EXCEPTION 'الصيانة أقدم من آخر صيانة مسجلة' USING ERRCODE = '22023';
 END IF;
 INSERT INTO public.pureice_truck_services(id, truck_id, km, service_date, cost, note, created_by)
 VALUES (p_id, p_truck, p_km, p_date, coalesce(p_cost, 0), note, auth.uid()) RETURNING * INTO r;
 INSERT INTO public.pureice_truck_service_plan(truck_id, last_service_km, last_service_date, updated_by)
 VALUES (p_truck, p_km, p_date, auth.uid())
 ON CONFLICT (truck_id) DO UPDATE SET last_service_km = EXCLUDED.last_service_km, last_service_date = EXCLUDED.last_service_date,
   updated_by = EXCLUDED.updated_by, updated_at = now();
 RETURN r;
END $$;
REVOKE ALL ON FUNCTION public.pureice_record_service(uuid, uuid, numeric, date, numeric, text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.pureice_record_service(uuid, uuid, numeric, date, numeric, text) TO authenticated;

-- Staff: report a truck problem. A distributor only for the truck of his open campaign. The photo
-- (optional) must be in the reporter's folder. Request: {id, truck_id, title, details, severity, photo_path}.
CREATE FUNCTION public.pureice_report_truck_issue(p_request jsonb) RETURNS public.pureice_truck_issues
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public AS $$
DECLARE
 uid uuid := auth.uid();
 rid uuid; tid uuid;
 title text := nullif(btrim(p_request->>'title'), '');
 details text := nullif(btrim(p_request->>'details'), '');
 severity text := coalesce(nullif(p_request->>'severity', ''), 'normal');
 photo text := nullif(btrim(p_request->>'photo_path'), '');
 trip uuid;
 r public.pureice_truck_issues;
BEGIN
 IF NOT public.is_staff(uid) THEN RAISE EXCEPTION 'الحساب غير نشط' USING ERRCODE = '42501'; END IF;
 BEGIN rid := (p_request->>'id')::uuid; tid := (p_request->>'truck_id')::uuid;
 EXCEPTION WHEN others THEN RAISE EXCEPTION 'بيانات البلاغ غير صحيحة' USING ERRCODE = '22023'; END;
 SELECT * INTO r FROM public.pureice_truck_issues WHERE id = rid;
 IF FOUND THEN
  IF r.reported_by = uid AND r.truck_id = tid AND r.title = title AND r.details IS NOT DISTINCT FROM details
     AND r.severity = severity AND r.photo_path IS NOT DISTINCT FROM photo THEN RETURN r; END IF;
  RAISE EXCEPTION 'تعارض معرف البلاغ' USING ERRCODE = '23505';
 END IF;
 IF rid IS NULL OR tid IS NULL OR title IS NULL OR severity NOT IN ('normal', 'urgent') THEN
  RAISE EXCEPTION 'اكتب عنوان المشكلة' USING ERRCODE = '22023';
 END IF;
 IF photo IS NOT NULL AND photo NOT LIKE uid::text || '/%' THEN RAISE EXCEPTION 'الصورة تخص حساباً آخر' USING ERRCODE = '42501'; END IF;
 IF NOT public.is_manager(uid) AND tid IS DISTINCT FROM public.pureice_my_truck() THEN
  RAISE EXCEPTION 'البلاغ لشاحنة حملتك المفتوحة فقط' USING ERRCODE = '42501';
 END IF;
 IF NOT EXISTS (SELECT 1 FROM public.trucks WHERE id = tid) THEN RAISE EXCEPTION 'الشاحنة غير موجودة' USING ERRCODE = 'P0002'; END IF;
 SELECT id INTO trip FROM public.trips WHERE truck_id = tid AND status = 'open' ORDER BY created_at DESC LIMIT 1;
 INSERT INTO public.pureice_truck_issues(id, truck_id, trip_id, title, details, severity, photo_path, reported_by)
 VALUES (rid, tid, trip, title, details, severity, photo, uid) RETURNING * INTO r;
 RETURN r;
END $$;
REVOKE ALL ON FUNCTION public.pureice_report_truck_issue(jsonb) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.pureice_report_truck_issue(jsonb) TO authenticated;

-- Managers: move a report to in_progress or fixed (fixed records who, when and how).
CREATE FUNCTION public.pureice_update_truck_issue(p_id uuid, p_status text, p_note text) RETURNS public.pureice_truck_issues
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public AS $$
DECLARE r public.pureice_truck_issues;
BEGIN
 IF NOT public.is_manager(auth.uid()) THEN RAISE EXCEPTION 'غير مصرح' USING ERRCODE = '42501'; END IF;
 IF p_status NOT IN ('open', 'in_progress', 'fixed') OR p_status IS NULL THEN RAISE EXCEPTION 'حالة غير صحيحة' USING ERRCODE = '22023'; END IF;
 UPDATE public.pureice_truck_issues SET status = p_status,
   resolved_by = CASE WHEN p_status = 'fixed' THEN auth.uid() END,
   resolved_at = CASE WHEN p_status = 'fixed' THEN now() END,
   resolution_note = CASE WHEN p_status = 'fixed' THEN nullif(btrim(p_note), '') END
 WHERE id = p_id RETURNING * INTO r;
 IF NOT FOUND THEN RAISE EXCEPTION 'البلاغ غير موجود' USING ERRCODE = 'P0002'; END IF;
 RETURN r;
END $$;
REVOKE ALL ON FUNCTION public.pureice_update_truck_issue(uuid, text, text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.pureice_update_truck_issue(uuid, text, text) TO authenticated;

COMMIT;
