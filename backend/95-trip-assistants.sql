-- Tamimi Ice (live Supabase project) — assistant distributors on a campaign.
-- Used by the native Android app. Run once, as a single script.
--
-- A manager adds another active distributor to an open campaign as its assistant. The assistant
-- sells, collects, records expenses, adds customers and moves boxes from his own phone, against the
-- campaign's approved stock; the server keeps the stock whole across both phones. Approving the load
-- and sending the closing stay with the campaign's main distributor, who hands in all the cash.
--
-- CHANGES EXISTING FUNCTIONS: the eight below now accept "the campaign's main distributor or one of
-- its assistants" where they accepted only the main distributor. Each change is that one condition;
-- the rest of each function is the live definition of 2026-11-19, unchanged. With no assistants
-- added, every one of them behaves exactly as before, so the web app is unaffected. One read policy
-- is added on pureice_deposits (campaign members see the campaign's deposits).
-- Undo: backend/95-trip-assistants-rollback.sql. Tests: backend/test/run.sh backend/95-trip-assistants.sql
BEGIN;

CREATE TABLE public.pureice_trip_assistants (
 trip_id uuid NOT NULL REFERENCES public.trips(id),
 user_id uuid NOT NULL REFERENCES public.profiles(id),
 added_by uuid NOT NULL REFERENCES public.profiles(id),
 added_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY (trip_id, user_id)
);
CREATE INDEX pureice_trip_assistants_user ON public.pureice_trip_assistants(user_id);
REVOKE ALL ON public.pureice_trip_assistants FROM PUBLIC, anon, authenticated;
GRANT SELECT ON public.pureice_trip_assistants TO authenticated;
GRANT ALL ON public.pureice_trip_assistants TO service_role;
ALTER TABLE public.pureice_trip_assistants ENABLE ROW LEVEL SECURITY;
CREATE POLICY active_account_backstop ON public.pureice_trip_assistants AS RESTRICTIVE FOR ALL TO authenticated
  USING ((SELECT public.active_staff(auth.uid()))) WITH CHECK ((SELECT public.active_staff(auth.uid())));
-- Managers, the assistant himself, and the campaign's main distributor.
CREATE POLICY member_or_manager_read ON public.pureice_trip_assistants FOR SELECT TO authenticated
  USING ((SELECT public.is_manager(auth.uid())) OR user_id = (SELECT auth.uid())
         OR EXISTS (SELECT 1 FROM public.trips t WHERE t.id = trip_id AND t.distributor_id = (SELECT auth.uid())));

-- Whether _uid works on the campaign: its main distributor or one of its assistants. Internal: called
-- by the functions below, never directly by the app.
CREATE FUNCTION public.pureice_trip_member(_trip uuid, _uid uuid) RETURNS boolean
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public AS
$$ SELECT _uid IS NOT NULL AND (EXISTS (SELECT 1 FROM public.trips WHERE id = _trip AND distributor_id = _uid)
   OR EXISTS (SELECT 1 FROM public.pureice_trip_assistants WHERE trip_id = _trip AND user_id = _uid)) $$;
REVOKE ALL ON FUNCTION public.pureice_trip_member(uuid, uuid) FROM PUBLIC, anon, authenticated;

-- The app shows the assistant screens only when this answers 1.
CREATE FUNCTION public.pureice_trip_assistant_support() RETURNS integer
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public AS
$$ SELECT CASE WHEN public.is_staff(auth.uid()) THEN 1 ELSE 0 END $$;
REVOKE ALL ON FUNCTION public.pureice_trip_assistant_support() FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.pureice_trip_assistant_support() TO authenticated;

-- The caller's campaign that is not closed yet, as {trip, role: main|assistant, assistants: [user ids]},
-- or null. A campaign he leads comes before one he assists.
CREATE FUNCTION public.pureice_my_open_trip() RETURNS jsonb
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public AS $$
 SELECT jsonb_build_object('trip', to_jsonb(t),
   'role', CASE WHEN t.distributor_id = auth.uid() THEN 'main' ELSE 'assistant' END,
   'assistants', coalesce((SELECT jsonb_agg(a.user_id ORDER BY a.added_at, a.user_id) FROM public.pureice_trip_assistants a WHERE a.trip_id = t.id), '[]'::jsonb))
 FROM public.trips t
 WHERE public.is_staff(auth.uid()) AND t.status <> 'closed' AND public.pureice_trip_member(t.id, auth.uid())
 ORDER BY (t.distributor_id = auth.uid()) DESC, t.created_at DESC
 LIMIT 1 $$;
REVOKE ALL ON FUNCTION public.pureice_my_open_trip() FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.pureice_my_open_trip() TO authenticated;

-- Managers: add (p_on) or remove an assistant on an open campaign. The assistant must be an active
-- distributor, not the campaign's main distributor, and on no other open campaign. No one is added
-- once the closing is sent. Repeating a call is accepted. Returns whether he is now an assistant.
CREATE FUNCTION public.pureice_set_trip_assistant(p_trip uuid, p_user uuid, p_on boolean) RETURNS boolean
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public AS $$
DECLARE t public.trips;
BEGIN
 IF NOT public.is_manager(auth.uid()) THEN RAISE EXCEPTION 'للمشرف أو المدير فقط' USING ERRCODE = '42501'; END IF;
 IF p_trip IS NULL OR p_user IS NULL OR p_on IS NULL THEN RAISE EXCEPTION 'طلب غير صحيح'; END IF;
 PERFORM pg_advisory_xact_lock(hashtextextended('trip-assistant:' || p_user::text, 0));
 SELECT * INTO t FROM public.trips WHERE id = p_trip FOR UPDATE;
 IF NOT FOUND OR t.status <> 'open' THEN RAISE EXCEPTION 'الحملة غير مفتوحة'; END IF;
 IF NOT p_on THEN
  DELETE FROM public.pureice_trip_assistants WHERE trip_id = p_trip AND user_id = p_user;
  RETURN false;
 END IF;
 IF EXISTS (SELECT 1 FROM public.pureice_trip_assistants WHERE trip_id = p_trip AND user_id = p_user) THEN RETURN true; END IF;
 IF EXISTS (SELECT 1 FROM public.closings WHERE trip_id = p_trip) THEN RAISE EXCEPTION 'أُرسل إغلاق الحملة؛ لا يضاف مساعد'; END IF;
 IF NOT public.has_role(p_user, 'distributor') THEN RAISE EXCEPTION 'المساعد يجب أن يكون موزعاً نشطاً'; END IF;
 IF p_user = t.distributor_id THEN RAISE EXCEPTION 'الموزع الرئيسي لا يكون مساعداً في حملته'; END IF;
 IF EXISTS (SELECT 1 FROM public.trips o WHERE o.status = 'open' AND o.id <> p_trip AND public.pureice_trip_member(o.id, p_user)) THEN
  RAISE EXCEPTION 'الموزع يعمل في حملة مفتوحة أخرى';
 END IF;
 INSERT INTO public.pureice_trip_assistants(trip_id, user_id, added_by) VALUES (p_trip, p_user, auth.uid());
 RETURN true;
END $$;
REVOKE ALL ON FUNCTION public.pureice_set_trip_assistant(uuid, uuid, boolean) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.pureice_set_trip_assistant(uuid, uuid, boolean) TO authenticated;

-- The campaign's main distributor and its assistants see each other's deposits on it, so each
-- phone's expected cash matches the server's (finance_expected subtracts every deposit on the
-- campaign). Until now each distributor saw only his own.
CREATE POLICY campaign_member_read ON public.pureice_deposits FOR SELECT TO authenticated
  USING (public.finance_trip_scope(trip_id));

-- Reading a campaign (trips, loads, snapshot, stock): its main distributor or an assistant.
CREATE OR REPLACE FUNCTION public.finance_trip_scope(_trip uuid)
 RETURNS boolean
 LANGUAGE sql
 STABLE SECURITY DEFINER
 SET search_path TO 'pg_catalog', 'public'
AS $function$ SELECT public.is_manager(auth.uid()) OR (public.has_role(auth.uid(),'distributor') AND public.pureice_trip_member(_trip,auth.uid())) $function$;

-- Customers on the truck of an open campaign the caller works on.
CREATE OR REPLACE FUNCTION public.finance_customer_scope(_customer uuid)
 RETURNS boolean
 LANGUAGE sql
 STABLE SECURITY DEFINER
 SET search_path TO 'pg_catalog', 'public'
AS $function$ SELECT public.is_manager(auth.uid()) OR (public.has_role(auth.uid(),'distributor') AND EXISTS(SELECT 1 FROM public.customers c JOIN public.trips t ON t.status='open' AND public.pureice_trip_member(t.id,auth.uid()) WHERE c.id=_customer AND c.truck_id=t.truck_id)) $function$;

-- Field records (sales, collections, expenses, boxes) on an open campaign the caller works on.
-- approve_trip_loads, guard_trip_loads and submit_closing keep their own main-distributor checks.
CREATE OR REPLACE FUNCTION public.finance_lock_trip(p_trip uuid, p_frozen_ok boolean DEFAULT false)
 RETURNS trips
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'pg_catalog', 'public'
AS $function$ DECLARE t public.trips; BEGIN
IF NOT public.is_staff(auth.uid()) THEN RAISE EXCEPTION 'الحساب غير نشط' USING ERRCODE='42501'; END IF;
SELECT * INTO t FROM public.trips WHERE id=p_trip FOR UPDATE;
IF NOT FOUND OR NOT public.finance_trip_scope(p_trip) THEN RAISE EXCEPTION 'الحملة غير مصرح بها' USING ERRCODE='42501'; END IF;
IF t.status<>'open' OR (NOT p_frozen_ok AND EXISTS(SELECT 1 FROM public.closings WHERE trip_id=t.id)) THEN RAISE EXCEPTION 'الحملة مغلقة أو أرسل إغلاقها'; END IF;
IF NOT public.is_manager(auth.uid()) AND NOT public.pureice_trip_member(t.id,auth.uid()) THEN RAISE EXCEPTION 'الموزع غير معين للحملة'; END IF;
RETURN t; END $function$;

-- A distributor adds customers only on the truck of an open campaign he works on.
CREATE OR REPLACE FUNCTION public.finance_field_scope_backstop()
 RETURNS trigger
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'pg_catalog', 'public'
AS $function$ DECLARE t public.trips;c public.customers; BEGIN IF TG_TABLE_NAME='customers' AND TG_OP='INSERT' AND NOT public.is_manager(auth.uid()) THEN SELECT * INTO t FROM public.trips WHERE status='open' AND truck_id=NEW.truck_id AND public.pureice_trip_member(id,auth.uid()) FOR UPDATE; IF NOT FOUND OR EXISTS(SELECT 1 FROM public.closings WHERE trip_id=t.id) THEN RAISE EXCEPTION 'الحملة غير متاحة'; END IF; ELSIF TG_TABLE_NAME='freezer_events' THEN IF NEW.customer_id IS NOT NULL THEN SELECT * INTO c FROM public.customers WHERE id=NEW.customer_id; SELECT * INTO t FROM public.trips WHERE id=NEW.trip_id; IF NOT public.is_manager(auth.uid()) AND (c.id IS NULL OR NOT c.active OR NOT public.pureice_directory_customer(c.id)) THEN RAISE EXCEPTION 'العميل لا يتبع شاحنة الحملة'; END IF; END IF; ELSIF TG_TABLE_NAME='expenses' THEN IF NEW.receipt_path IS NOT NULL AND NEW.receipt_path NOT LIKE auth.uid()::text||'/%' OR NEW.odometer_photo_path IS NOT NULL AND NEW.odometer_photo_path NOT LIKE auth.uid()::text||'/%' THEN RAISE EXCEPTION 'الصورة تخص حساباً آخر'; END IF; IF NEW.category='وقود' AND nullif(btrim(NEW.odometer_photo_path),'') IS NULL THEN RAISE EXCEPTION 'صورة العداد مطلوبة'; END IF; ELSIF TG_TABLE_NAME='maintenance_jobs' THEN IF NEW.receipt_path IS NOT NULL AND NEW.receipt_path NOT LIKE auth.uid()::text||'/%' THEN RAISE EXCEPTION 'الصورة تخص حساباً آخر'; END IF; END IF; RETURN NEW; END $function$;

-- Same rule in the customers guard.
CREATE OR REPLACE FUNCTION public.finance_guard_customer()
 RETURNS trigger
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'pg_catalog', 'public'
AS $function$ BEGIN
IF TG_OP='INSERT' THEN
IF NOT public.is_staff(auth.uid()) THEN RAISE EXCEPTION 'الحساب غير نشط'; END IF;
IF NOT public.is_manager(auth.uid()) AND (NEW.credit_limit<>0 OR NOT EXISTS(SELECT 1 FROM public.trips WHERE status='open' AND truck_id=NEW.truck_id AND public.pureice_trip_member(trips.id,auth.uid()) AND NOT EXISTS(SELECT 1 FROM public.closings WHERE trip_id=trips.id))) THEN RAISE EXCEPTION 'العميل لا يتبع الحملة أو حد الائتمان غير مصرح'; END IF;NEW.created_by:=auth.uid(); END IF;
IF NOT public.finance_number_ok(NEW.credit_limit) OR NEW.credit_limit<0 THEN RAISE EXCEPTION 'حد الائتمان غير صحيح'; END IF;RETURN NEW;END $function$;

-- Invoices: the main distributor or an assistant, from the campaign's approved stock.
CREATE OR REPLACE FUNCTION public.save_invoice(p_invoice jsonb)
 RETURNS sales
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'pg_catalog', 'public'
AS $function$
DECLARE t public.trips;c public.customers;v record;j jsonb;oldreq record;sid uuid:=(p_invoice->>'id')::uuid;cid uuid:=(p_invoice->>'customer_id')::uuid;tid uuid:=(p_invoice->>'trip_id')::uuid;total_value numeric:=0;cash numeric;q numeric;price numeric;expected_price numeric;result public.sales;
BEGIN
IF NOT public.is_staff(auth.uid()) OR sid IS NULL THEN RAISE EXCEPTION 'الحساب غير نشط'; END IF;
PERFORM pg_advisory_xact_lock(hashtextextended('invoice:'||sid::text,0));
SELECT * INTO oldreq FROM public.finance_invoice_requests WHERE id=sid;
IF FOUND THEN IF oldreq.actor<>auth.uid() OR oldreq.request IS DISTINCT FROM p_invoice THEN RAISE EXCEPTION 'تعارض معرف الفاتورة' USING ERRCODE='23505'; END IF; SELECT * INTO result FROM public.sales WHERE id=sid; RETURN result; END IF;
IF EXISTS(SELECT 1 FROM public.sales WHERE id=sid) THEN RAISE EXCEPTION 'فاتورة قديمة تتطلب مراجعة قبل إعادة إرسالها' USING ERRCODE='23505'; END IF;
t:=public.finance_lock_trip(tid);
IF NOT public.pureice_trip_member(t.id,auth.uid()) OR t.loading_approved_at IS NULL THEN RAISE EXCEPTION 'اعتماد التحميل وتعيين الموزع مطلوبان'; END IF;
IF p_invoice ? 'truck_id' AND (p_invoice->>'truck_id')::uuid IS DISTINCT FROM t.truck_id THEN RAISE EXCEPTION 'الشاحنة غير صحيحة'; END IF;
IF (p_invoice->>'created_by')::uuid IS DISTINCT FROM auth.uid() THEN RAISE EXCEPTION 'مالك الفاتورة غير صحيح'; END IF;
SELECT * INTO c FROM public.customers WHERE id=cid FOR UPDATE;
IF NOT FOUND OR NOT c.active OR NOT public.pureice_directory_customer(cid) THEN RAISE EXCEPTION 'العميل غير متاح'; END IF;
IF jsonb_typeof(p_invoice->'items') IS DISTINCT FROM 'array' OR jsonb_array_length(p_invoice->'items') NOT BETWEEN 1 AND 500 THEN RAISE EXCEPTION 'أصناف الفاتورة غير صحيحة'; END IF;
IF (SELECT count(*)<>count(DISTINCT x->>'id') FROM jsonb_array_elements(p_invoice->'items') x) THEN RAISE EXCEPTION 'معرف صنف مكرر'; END IF;
FOR v IN SELECT DISTINCT (x->>'variant_id')::uuid AS id FROM jsonb_array_elements(p_invoice->'items') x ORDER BY 1 LOOP
PERFORM 1 FROM public.products p JOIN public.product_variants pv ON pv.product_id=p.id WHERE pv.id=v.id AND pv.active AND p.active FOR SHARE OF pv,p;
IF NOT FOUND THEN RAISE EXCEPTION 'الصنف غير نشط'; END IF; END LOOP;
FOR j IN SELECT value FROM jsonb_array_elements(p_invoice->'items') LOOP
q:=(j->>'qty')::numeric;price:=(j->>'unit_price')::numeric;
IF NOT public.finance_number_ok(q) OR q<=0 OR NOT public.finance_number_ok(price) OR price<=0 OR j->>'id' IS NULL THEN RAISE EXCEPTION 'الكمية والسعر يجب أن يكونا موجبين'; END IF;
IF j ? 'sale_id' AND (j->>'sale_id')::uuid IS DISTINCT FROM sid THEN RAISE EXCEPTION 'الصنف يتبع فاتورة أخرى'; END IF;
SELECT coalesce((SELECT cp.price FROM public.customer_prices cp WHERE cp.customer_id=cid AND cp.variant_id=(j->>'variant_id')::uuid),pv.price) INTO expected_price FROM public.product_variants pv WHERE pv.id=(j->>'variant_id')::uuid;
IF price IS DISTINCT FROM expected_price THEN RAISE EXCEPTION 'تغير السعر؛ راجع الفاتورة'; END IF;
total_value:=total_value+round(q*price); END LOOP;
FOR v IN SELECT (x->>'variant_id')::uuid AS id,sum((x->>'qty')::numeric) AS qty FROM jsonb_array_elements(p_invoice->'items') x GROUP BY 1 LOOP
IF v.qty>public.finance_remaining(tid,v.id) THEN RAISE EXCEPTION 'المخزون المعتمد غير كاف'; END IF; END LOOP;
cash:=(p_invoice->>'cash_paid')::numeric;
IF NOT public.finance_number_ok(cash) OR cash<0 OR cash>total_value THEN RAISE EXCEPTION 'النقد غير صحيح'; END IF;
IF NOT public.finance_number_ok(c.credit_limit) OR c.credit_limit<0 OR (total_value-cash>0 AND (c.credit_limit>0 AND public.finance_balance(cid)+total_value-cash>c.credit_limit)) THEN RAISE EXCEPTION 'تجاوز حد الائتمان'; END IF;
INSERT INTO public.sales(id,invoice_no,trip_id,truck_id,customer_id,total,cash_paid,credit_amount,lat,lng,notes,created_by,created_at)
VALUES(sid,coalesce(nullif(p_invoice->>'invoice_no',''),'INV-'||sid::text),tid,t.truck_id,cid,total_value,cash,total_value-cash,(p_invoice->>'lat')::float8,(p_invoice->>'lng')::float8,p_invoice->>'notes',auth.uid(),coalesce((p_invoice->>'created_at')::timestamptz,now())) RETURNING * INTO result;
INSERT INTO public.sale_items(id,sale_id,variant_id,qty,unit_price,line_total) SELECT (x->>'id')::uuid,sid,(x->>'variant_id')::uuid,(x->>'qty')::numeric,(x->>'unit_price')::numeric,round((x->>'qty')::numeric*(x->>'unit_price')::numeric) FROM jsonb_array_elements(p_invoice->'items') x;
INSERT INTO public.finance_invoice_requests VALUES(sid,auth.uid(),p_invoice); RETURN result; END $function$;

-- Box deliveries and collections on a campaign the caller works on.
CREATE OR REPLACE FUNCTION public.pureice_box_move(p_request jsonb, p_sent_at timestamp with time zone DEFAULT NULL::timestamp with time zone)
 RETURNS jsonb
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'pg_catalog', 'public'
AS $function$
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
  IF NOT FOUND OR NOT public.pureice_trip_member(t.id, auth.uid()) THEN RAISE EXCEPTION 'الحملة ليست لهذا الحساب' USING ERRCODE = '42501'; END IF;
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
END $function$;

-- The truck of the caller's open campaign (from 94), now also for assistants; his own campaign first.
CREATE OR REPLACE FUNCTION public.pureice_my_truck()
 RETURNS uuid
 LANGUAGE sql
 STABLE SECURITY DEFINER
 SET search_path TO 'pg_catalog', 'public'
AS $function$ SELECT truck_id FROM public.trips WHERE status = 'open' AND public.pureice_trip_member(id, auth.uid()) ORDER BY (distributor_id = auth.uid()) DESC, created_at DESC LIMIT 1 $function$;

COMMIT;
