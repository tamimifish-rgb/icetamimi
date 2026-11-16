-- Tamimi Ice (live Supabase project) — returnable box tracking + customer location capture.
-- Used by the native Android app only. Run once, as a single script.
--
-- Additive: no existing table, policy, trigger or function is changed. The only write to an
-- existing table is pureice_set_customer_location filling customers.lat/lng (and updated_at)
-- while both are still empty.
BEGIN;

CREATE TABLE public.pureice_boxes (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 serial text NOT NULL UNIQUE CHECK (serial ~ '^TM-ICE-[0-9]{6}$'),
 status text NOT NULL DEFAULT 'new' CHECK (status IN ('new','factory','truck','customer','elsewhere','damaged','disposed')),
 truck_id uuid REFERENCES public.trucks(id),
 customer_id uuid REFERENCES public.customers(id),
 notes text,
 moved_at timestamptz,                            -- scan time of the state shown; null = never scanned
 updated_at timestamptz NOT NULL DEFAULT now(),
 updated_by uuid REFERENCES public.profiles(id),
 CHECK ((status = 'truck') = (truck_id IS NOT NULL)),
 CHECK ((status = 'customer') = (customer_id IS NOT NULL))
);
CREATE INDEX pureice_boxes_truck ON public.pureice_boxes(truck_id) WHERE truck_id IS NOT NULL;
CREATE INDEX pureice_boxes_customer ON public.pureice_boxes(customer_id) WHERE customer_id IS NOT NULL;

CREATE TABLE public.pureice_box_events (
 id uuid PRIMARY KEY,                            -- generated on the phone; first event id = batch id
 batch_id uuid NOT NULL,
 box_id uuid NOT NULL REFERENCES public.pureice_boxes(id),
 action text NOT NULL CHECK (action IN ('load','unload','deliver','collect','damage','dispose','elsewhere','restore')),
 status text NOT NULL CHECK (status IN ('factory','truck','customer','elsewhere','damaged','disposed')),
 truck_id uuid REFERENCES public.trucks(id) ON DELETE SET NULL,
 customer_id uuid REFERENCES public.customers(id) ON DELETE SET NULL,
 trip_id uuid REFERENCES public.trips(id),
 sale_id uuid,                                   -- no FK: an approved cancellation deletes the invoice row
 from_status text NOT NULL, from_truck_id uuid, from_customer_id uuid,
 applied boolean NOT NULL,                       -- false: a newer state existed, logged only
 unexpected boolean NOT NULL DEFAULT false,
 notes text, lat double precision, lng double precision,
 created_by uuid NOT NULL REFERENCES public.profiles(id),
 created_at timestamptz NOT NULL,                -- phone clock at the scan
 event_at timestamptz NOT NULL,                  -- server-normalised scan time, used for ordering
 recorded_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX pureice_box_events_box ON public.pureice_box_events(box_id, event_at DESC);
CREATE INDEX pureice_box_events_batch ON public.pureice_box_events(batch_id);
CREATE INDEX pureice_box_events_customer ON public.pureice_box_events(customer_id);
CREATE INDEX pureice_box_events_actor ON public.pureice_box_events(created_by, recorded_at);

CREATE TABLE public.pureice_box_variants (
 variant_id uuid PRIMARY KEY REFERENCES public.product_variants(id) ON DELETE CASCADE,
 created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE public.pureice_customer_locations (
 id uuid PRIMARY KEY,
 customer_id uuid NOT NULL REFERENCES public.customers(id) ON DELETE CASCADE,
 sale_id uuid,
 lat double precision NOT NULL CHECK (lat BETWEEN -90 AND 90),
 lng double precision NOT NULL CHECK (lng BETWEEN -180 AND 180),
 accuracy real,
 applied boolean NOT NULL,
 created_by uuid NOT NULL REFERENCES public.profiles(id),
 created_at timestamptz NOT NULL,
 recorded_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX pureice_customer_locations_customer ON public.pureice_customer_locations(customer_id);

DO $$ DECLARE t text; BEGIN
 FOREACH t IN ARRAY ARRAY['pureice_boxes','pureice_box_events','pureice_box_variants','pureice_customer_locations'] LOOP
  EXECUTE format('REVOKE ALL ON public.%I FROM PUBLIC, anon, authenticated', t);
  EXECUTE format('GRANT SELECT ON public.%I TO authenticated', t);
  EXECUTE format('GRANT ALL ON public.%I TO service_role', t);
  EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t);
  EXECUTE format('CREATE POLICY active_account_backstop ON public.%I AS RESTRICTIVE FOR ALL TO authenticated USING (public.active_staff(auth.uid())) WITH CHECK (public.active_staff(auth.uid()))', t);
 END LOOP;
END $$;
GRANT INSERT, DELETE ON public.pureice_box_variants TO authenticated;

CREATE POLICY box_read ON public.pureice_boxes FOR SELECT TO authenticated USING (public.is_staff(auth.uid()));
CREATE POLICY box_event_read ON public.pureice_box_events FOR SELECT TO authenticated
 USING (public.is_staff(auth.uid()) AND (created_by = auth.uid() OR public.is_manager(auth.uid())));
CREATE POLICY box_variant_read ON public.pureice_box_variants FOR SELECT TO authenticated USING (public.is_staff(auth.uid()));
CREATE POLICY box_variant_insert ON public.pureice_box_variants FOR INSERT TO authenticated WITH CHECK (public.has_role(auth.uid(), 'admin'));
CREATE POLICY box_variant_delete ON public.pureice_box_variants FOR DELETE TO authenticated USING (public.has_role(auth.uid(), 'admin'));
CREATE POLICY customer_location_read ON public.pureice_customer_locations FOR SELECT TO authenticated
 USING (public.is_staff(auth.uid()) AND (created_by = auth.uid() OR public.is_manager(auth.uid())));

INSERT INTO public.pureice_boxes(serial) SELECT 'TM-ICE-' || lpad(n::text, 6, '0') FROM generate_series(1, 210) n;

CREATE FUNCTION public.pureice_box_support() RETURNS integer
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public AS $$
 SELECT CASE WHEN public.is_staff(auth.uid()) THEN 1 ELSE 0 END $$;
REVOKE ALL ON FUNCTION public.pureice_box_support() FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.pureice_box_support() TO authenticated;

CREATE FUNCTION public.pureice_box_add(p_from integer, p_to integer) RETURNS integer
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public AS $$
DECLARE n integer;
BEGIN
 IF NOT public.has_role(auth.uid(), 'admin') THEN RAISE EXCEPTION 'إضافة الصناديق للمدير فقط' USING ERRCODE = '42501'; END IF;
 IF p_from IS NULL OR p_to IS NULL OR p_from < 1 OR p_to > 999999 OR p_to < p_from OR p_to - p_from >= 1000 THEN
  RAISE EXCEPTION 'نطاق أرقام الصناديق غير صحيح'; END IF;
 INSERT INTO public.pureice_boxes(serial, updated_by)
 SELECT 'TM-ICE-' || lpad(g::text, 6, '0'), auth.uid() FROM generate_series(p_from, p_to) g ON CONFLICT (serial) DO NOTHING;
 GET DIAGNOSTICS n = ROW_COUNT;
 RETURN n;
END $$;
REVOKE ALL ON FUNCTION public.pureice_box_add(integer, integer) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.pureice_box_add(integer, integer) TO authenticated;

CREATE FUNCTION public.pureice_box_move(p_request jsonb, p_sent_at timestamptz DEFAULT NULL) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public AS $$
DECLARE bid uuid := (p_request->>'id')::uuid; act text := p_request->>'action';
 tid uuid := (p_request->>'trip_id')::uuid; truck uuid := (p_request->>'truck_id')::uuid;
 cust uuid := (p_request->>'customer_id')::uuid; sid uuid := (p_request->>'sale_id')::uuid;
 made timestamptz := (p_request->>'created_at')::timestamptz;
 note text := nullif(btrim(coalesce(p_request->>'notes', '')), '');
 la float8 := (p_request->>'lat')::float8; lo float8 := (p_request->>'lng')::float8;
 evt timestamptz; t public.trips; b public.pureice_boxes; old public.pureice_box_events;
 j jsonb; eid uuid; st text; odd boolean; fresh boolean;
 n_applied integer := 0; n_late integer := 0; n_odd integer := 0;
BEGIN
 IF NOT public.is_staff(auth.uid()) OR bid IS NULL OR (p_request->>'created_by')::uuid IS DISTINCT FROM auth.uid() THEN
  RAISE EXCEPTION 'الحساب غير مصرح' USING ERRCODE = '42501'; END IF;
 IF act IS NULL OR act NOT IN ('load','unload','deliver','collect','damage','dispose','elsewhere','restore') THEN
  RAISE EXCEPTION 'حركة الصندوق غير صحيحة'; END IF;
 IF jsonb_typeof(p_request->'boxes') IS DISTINCT FROM 'array' OR jsonb_array_length(p_request->'boxes') NOT BETWEEN 1 AND 500 THEN
  RAISE EXCEPTION 'قائمة الصناديق غير صحيحة'; END IF;
 IF (SELECT count(*) <> count(DISTINCT x->>'id') OR count(*) <> count(DISTINCT upper(btrim(x->>'serial')))
     FROM jsonb_array_elements(p_request->'boxes') x) THEN RAISE EXCEPTION 'صندوق مكرر في الطلب'; END IF;
 IF (p_request->'boxes'->0->>'id')::uuid IS DISTINCT FROM bid THEN RAISE EXCEPTION 'معرف الطلب غير صحيح'; END IF;
 IF (la IS NULL) <> (lo IS NULL) OR (la IS NOT NULL AND NOT (la BETWEEN -90 AND 90 AND lo BETWEEN -180 AND 180)) THEN
  RAISE EXCEPTION 'إحداثيات غير صحيحة'; END IF;
 IF length(note) > 500 THEN RAISE EXCEPTION 'الملاحظة طويلة جداً'; END IF;
 PERFORM pg_advisory_xact_lock(hashtextextended('box-move:' || bid::text, 0));
 SELECT * INTO old FROM public.pureice_box_events WHERE id = bid;
 IF FOUND THEN
  IF old.created_by IS DISTINCT FROM auth.uid() OR old.batch_id <> bid OR old.action IS DISTINCT FROM act THEN
   RAISE EXCEPTION 'تعارض معرف حركة الصناديق' USING ERRCODE = '23505'; END IF;
  RETURN jsonb_build_object('batch_id', bid, 'repeat', true);
 END IF;
 evt := least(coalesce(made, now()), now());
 IF made IS NOT NULL AND p_sent_at IS NOT NULL THEN evt := now() - greatest(p_sent_at - made, interval '0'); END IF;
 IF public.is_manager(auth.uid()) THEN
  IF act IN ('load','collect') THEN
   IF truck IS NULL OR NOT EXISTS(SELECT 1 FROM public.trucks WHERE id = truck) THEN RAISE EXCEPTION 'اختر الشاحنة'; END IF;
   SELECT id INTO tid FROM public.trips WHERE truck_id = truck AND status = 'open';
  ELSE truck := NULL; tid := NULL;
  END IF;
 ELSIF public.has_role(auth.uid(), 'distributor') AND act IN ('deliver','collect','damage') THEN
  SELECT * INTO t FROM public.trips WHERE id = tid;
  IF NOT FOUND OR t.distributor_id IS DISTINCT FROM auth.uid() THEN RAISE EXCEPTION 'الحملة ليست لهذا الحساب' USING ERRCODE = '42501'; END IF;
  IF t.status <> 'open' AND (t.closed_at IS NULL OR evt > t.closed_at) THEN
   RAISE EXCEPTION 'الحملة مغلقة؛ حركة الصناديق عند المشرف' USING ERRCODE = '42501'; END IF;
  truck := t.truck_id;
 ELSE
  RAISE EXCEPTION 'هذه الحركة للمشرف أو المدير فقط' USING ERRCODE = '42501';
 END IF;
 IF act NOT IN ('deliver','collect') THEN cust := NULL; END IF;
 IF act = 'deliver' AND cust IS NULL THEN RAISE EXCEPTION 'اختر العميل'; END IF;
 IF cust IS NOT NULL AND (NOT EXISTS(SELECT 1 FROM public.customers WHERE id = cust) OR NOT public.pureice_directory_customer(cust)) THEN
  RAISE EXCEPTION 'العميل غير متاح'; END IF;
 FOR j IN SELECT value FROM jsonb_array_elements(p_request->'boxes') ORDER BY upper(btrim(value->>'serial')) LOOP
  eid := (j->>'id')::uuid;
  IF EXISTS(SELECT 1 FROM public.pureice_box_events WHERE id = eid) THEN
   RAISE EXCEPTION 'تعارض معرف حركة الصناديق' USING ERRCODE = '23505'; END IF;
  SELECT * INTO b FROM public.pureice_boxes WHERE serial = upper(btrim(j->>'serial')) FOR UPDATE;
  IF NOT FOUND THEN RAISE EXCEPTION 'صندوق غير معروف: %', coalesce(j->>'serial', ''); END IF;
  st := CASE WHEN act IN ('load','collect') THEN 'truck' WHEN act IN ('unload','restore') THEN 'factory'
             WHEN act = 'deliver' THEN 'customer' WHEN act = 'damage' THEN 'damaged'
             WHEN act = 'dispose' THEN 'disposed' ELSE 'elsewhere' END;
  odd := b.status <> 'new' AND CASE act
   WHEN 'load' THEN b.status <> 'factory'
   WHEN 'unload' THEN b.status <> 'truck'
   WHEN 'deliver' THEN b.status <> 'truck' OR (truck IS NOT NULL AND b.truck_id IS DISTINCT FROM truck)
   WHEN 'collect' THEN b.status <> 'customer' OR (cust IS NOT NULL AND b.customer_id IS DISTINCT FROM cust)
   WHEN 'restore' THEN b.status NOT IN ('damaged','elsewhere','disposed')
   ELSE b.status IN ('damaged','disposed') END;
  fresh := b.moved_at IS NULL OR evt >= b.moved_at;
  INSERT INTO public.pureice_box_events(id, batch_id, box_id, action, status, truck_id, customer_id, trip_id, sale_id,
   from_status, from_truck_id, from_customer_id, applied, unexpected, notes, lat, lng, created_by, created_at, event_at)
  VALUES (eid, bid, b.id, act, st, truck, CASE WHEN act = 'collect' THEN coalesce(cust, b.customer_id) ELSE cust END, tid, sid,
   b.status, b.truck_id, b.customer_id, fresh, odd, note, la, lo, auth.uid(), coalesce(made, now()), evt);
  IF fresh THEN
   UPDATE public.pureice_boxes SET status = st,
    truck_id = CASE WHEN st = 'truck' THEN truck END,
    customer_id = CASE WHEN st = 'customer' THEN cust END,
    notes = CASE WHEN st IN ('damaged','disposed','elsewhere') THEN note END,
    moved_at = evt, updated_at = now(), updated_by = auth.uid()
   WHERE id = b.id;
   n_applied := n_applied + 1;
  ELSE n_late := n_late + 1;
  END IF;
  IF odd THEN n_odd := n_odd + 1; END IF;
 END LOOP;
 RETURN jsonb_build_object('batch_id', bid, 'applied', n_applied, 'late', n_late, 'unexpected', n_odd);
END $$;
REVOKE ALL ON FUNCTION public.pureice_box_move(jsonb, timestamptz) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.pureice_box_move(jsonb, timestamptz) TO authenticated;

CREATE FUNCTION public.pureice_set_customer_location(p_request jsonb) RETURNS public.pureice_customer_locations
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public AS $$
DECLARE rid uuid := (p_request->>'id')::uuid; cid uuid := (p_request->>'customer_id')::uuid;
 sid uuid := (p_request->>'sale_id')::uuid; la float8 := (p_request->>'lat')::float8; lo float8 := (p_request->>'lng')::float8;
 acc real := (p_request->>'accuracy')::real; c public.customers; r public.pureice_customer_locations; ok boolean;
BEGIN
 IF NOT public.is_staff(auth.uid()) OR rid IS NULL OR cid IS NULL OR (p_request->>'created_by')::uuid IS DISTINCT FROM auth.uid() THEN
  RAISE EXCEPTION 'الحساب غير مصرح' USING ERRCODE = '42501'; END IF;
 IF la IS NULL OR lo IS NULL OR NOT (la BETWEEN -90 AND 90 AND lo BETWEEN -180 AND 180) OR (la = 0 AND lo = 0) THEN
  RAISE EXCEPTION 'إحداثيات غير صحيحة'; END IF;
 IF acc IS NOT NULL AND NOT (acc >= 0 AND acc <= 100000) THEN acc := NULL; END IF;
 SELECT * INTO c FROM public.customers WHERE id = cid FOR UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION 'العميل غير موجود'; END IF;
 SELECT * INTO r FROM public.pureice_customer_locations WHERE id = rid;
 IF FOUND THEN
  IF r.created_by IS DISTINCT FROM auth.uid() OR r.customer_id <> cid THEN RAISE EXCEPTION 'تعارض معرف الطلب' USING ERRCODE = '23505'; END IF;
  RETURN r;
 END IF;
 ok := c.lat IS NULL AND c.lng IS NULL AND (public.is_manager(auth.uid())
  OR EXISTS(SELECT 1 FROM public.sales s WHERE s.id = sid AND s.created_by = auth.uid() AND s.customer_id = cid));
 IF ok THEN UPDATE public.customers SET lat = la, lng = lo, updated_at = now() WHERE id = cid; END IF;
 INSERT INTO public.pureice_customer_locations(id, customer_id, sale_id, lat, lng, accuracy, applied, created_by, created_at)
 VALUES (rid, cid, sid, la, lo, acc, ok, auth.uid(), coalesce((p_request->>'created_at')::timestamptz, now()))
 RETURNING * INTO r;
 RETURN r;
END $$;
REVOKE ALL ON FUNCTION public.pureice_set_customer_location(jsonb) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.pureice_set_customer_location(jsonb) TO authenticated;

COMMIT;
