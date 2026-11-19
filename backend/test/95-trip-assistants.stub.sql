-- Live objects that backend/95-trip-assistants.sql changes or calls, copied from the live project on
-- 2026-11-19 (function bodies verbatim; tables carry their columns without constraints). Loaded by
-- run.sh after stub-schema.sql. Adds a fourth distributor, d4….
INSERT INTO public.profiles(id, full_name, active) VALUES ('d4000000-0000-0000-0000-000000000000', 'distributor four', true);
INSERT INTO public.user_roles(user_id, role) VALUES ('d4000000-0000-0000-0000-000000000000', 'distributor');

ALTER TABLE public.trips ADD COLUMN IF NOT EXISTS id uuid DEFAULT gen_random_uuid();
ALTER TABLE public.trips ADD COLUMN IF NOT EXISTS truck_id uuid;
ALTER TABLE public.trips ADD COLUMN IF NOT EXISTS trip_date date DEFAULT CURRENT_DATE;
ALTER TABLE public.trips ADD COLUMN IF NOT EXISTS status text DEFAULT 'open'::text;
ALTER TABLE public.trips ADD COLUMN IF NOT EXISTS opened_by uuid DEFAULT auth.uid();
ALTER TABLE public.trips ADD COLUMN IF NOT EXISTS created_at timestamp with time zone DEFAULT now();
ALTER TABLE public.trips ADD COLUMN IF NOT EXISTS distributor_id uuid;
ALTER TABLE public.trips ADD COLUMN IF NOT EXISTS loading_approved_at timestamp with time zone;
ALTER TABLE public.trips ADD COLUMN IF NOT EXISTS closed_at timestamp with time zone;
ALTER TABLE public.expenses ADD COLUMN IF NOT EXISTS id uuid;
ALTER TABLE public.expenses ADD COLUMN IF NOT EXISTS truck_id uuid;
ALTER TABLE public.expenses ADD COLUMN IF NOT EXISTS trip_id uuid;
ALTER TABLE public.expenses ADD COLUMN IF NOT EXISTS category text;
ALTER TABLE public.expenses ADD COLUMN IF NOT EXISTS amount numeric;
ALTER TABLE public.expenses ADD COLUMN IF NOT EXISTS notes text;
ALTER TABLE public.expenses ADD COLUMN IF NOT EXISTS created_by uuid DEFAULT auth.uid();
ALTER TABLE public.expenses ADD COLUMN IF NOT EXISTS created_at timestamp with time zone DEFAULT now();
ALTER TABLE public.expenses ADD COLUMN IF NOT EXISTS receipt_path text;
ALTER TABLE public.expenses ADD COLUMN IF NOT EXISTS odometer numeric;
ALTER TABLE public.expenses ADD COLUMN IF NOT EXISTS odometer_photo_path text;
CREATE TABLE public.customers (id uuid PRIMARY KEY DEFAULT gen_random_uuid(), name text, phone text, region_id uuid, location_notes text, lat double precision, lng double precision, credit_limit numeric DEFAULT 0, truck_id uuid, active boolean DEFAULT true, created_by uuid, created_at timestamp with time zone DEFAULT now(), updated_at timestamp with time zone DEFAULT now());
CREATE TABLE public.products (id uuid PRIMARY KEY DEFAULT gen_random_uuid(), name text, active boolean DEFAULT true, sort integer DEFAULT 0, updated_at timestamp with time zone DEFAULT now());
CREATE TABLE public.product_variants (id uuid PRIMARY KEY DEFAULT gen_random_uuid(), product_id uuid, name text, unit text DEFAULT 'حبة'::text, price numeric DEFAULT 0, active boolean DEFAULT true, sort integer DEFAULT 0, updated_at timestamp with time zone DEFAULT now());
CREATE TABLE public.customer_prices (id uuid PRIMARY KEY DEFAULT gen_random_uuid(), customer_id uuid, variant_id uuid, price numeric, updated_at timestamp with time zone DEFAULT now());
CREATE TABLE public.sales (id uuid PRIMARY KEY, invoice_no text, trip_id uuid, truck_id uuid, customer_id uuid, total numeric DEFAULT 0, cash_paid numeric DEFAULT 0, credit_amount numeric DEFAULT 0, lat double precision, lng double precision, notes text, created_by uuid DEFAULT auth.uid(), created_at timestamp with time zone DEFAULT now(), synced_at timestamp with time zone DEFAULT now());
CREATE TABLE public.sale_items (id uuid PRIMARY KEY, sale_id uuid, variant_id uuid, qty numeric, unit_price numeric, line_total numeric);
CREATE TABLE public.finance_invoice_requests (id uuid PRIMARY KEY, actor uuid, request jsonb);
CREATE TABLE public.payments (id uuid PRIMARY KEY, customer_id uuid, truck_id uuid, trip_id uuid, amount numeric, notes text, created_by uuid DEFAULT auth.uid(), created_at timestamp with time zone DEFAULT now());
CREATE TABLE public.trip_loads (id uuid PRIMARY KEY DEFAULT gen_random_uuid(), trip_id uuid, variant_id uuid, qty numeric, kind text DEFAULT 'load'::text, created_by uuid DEFAULT auth.uid(), created_at timestamp with time zone DEFAULT now(), approved_at timestamp with time zone, approved_by uuid);
CREATE TABLE public.pureice_boxes (id uuid PRIMARY KEY DEFAULT gen_random_uuid(), serial text, status text DEFAULT 'new'::text, truck_id uuid, customer_id uuid, notes text, moved_at timestamp with time zone, updated_at timestamp with time zone DEFAULT now(), updated_by uuid);
CREATE TABLE public.pureice_box_events (id uuid PRIMARY KEY, batch_id uuid, box_id uuid, action text, status text, truck_id uuid, customer_id uuid, trip_id uuid, sale_id uuid, from_status text, from_truck_id uuid, from_customer_id uuid, applied boolean, unexpected boolean DEFAULT false, notes text, lat double precision, lng double precision, created_by uuid, created_at timestamp with time zone, event_at timestamp with time zone, recorded_at timestamp with time zone DEFAULT now());
CREATE TABLE public.pureice_deposits (id uuid PRIMARY KEY, kind text, trip_id uuid, created_by uuid, amount numeric, slip_path text, request jsonb, created_at timestamp with time zone DEFAULT now());
CREATE TABLE public.gps_points (id uuid PRIMARY KEY, trip_id uuid, truck_id uuid, lat double precision, lng double precision, recorded_at timestamp with time zone DEFAULT now(), created_by uuid DEFAULT auth.uid());
CREATE TABLE public.freezer_events (id uuid PRIMARY KEY, freezer_id uuid, event_type text, customer_id uuid, notes text, created_by uuid DEFAULT auth.uid(), created_at timestamp with time zone DEFAULT now(), trip_id uuid);
CREATE TABLE public.maintenance_jobs (id uuid PRIMARY KEY, truck_id uuid, freezer_id uuid, title text, cost numeric DEFAULT 0, notes text, status text DEFAULT 'open'::text, created_by uuid DEFAULT auth.uid(), created_at timestamp with time zone DEFAULT now(), receipt_path text, job_date date DEFAULT CURRENT_DATE, trip_id uuid);
CREATE TABLE public.freezers (id uuid PRIMARY KEY, customer_id uuid);

CREATE OR REPLACE FUNCTION public.finance_number_ok(n numeric)
 RETURNS boolean
 LANGUAGE sql
 IMMUTABLE
 SET search_path TO 'pg_catalog'
AS $function$ SELECT n IS NOT NULL AND n::text NOT IN ('NaN','Infinity','-Infinity') $function$;

CREATE OR REPLACE FUNCTION public.finance_balance(p_customer uuid)
 RETURNS numeric
 LANGUAGE sql
 STABLE SECURITY DEFINER
 SET search_path TO 'pg_catalog', 'public'
AS $function$ SELECT coalesce((SELECT sum(credit_amount) FROM public.sales WHERE customer_id=p_customer),0)-coalesce((SELECT sum(amount) FROM public.payments WHERE customer_id=p_customer),0) $function$;

CREATE OR REPLACE FUNCTION public.finance_remaining(p_trip uuid, p_variant uuid)
 RETURNS numeric
 LANGUAGE sql
 STABLE SECURITY DEFINER
 SET search_path TO 'pg_catalog', 'public'
AS $function$ SELECT coalesce((SELECT sum(CASE WHEN kind='load' AND approved_at IS NOT NULL THEN qty WHEN kind IN ('return','damaged') THEN -qty ELSE 0 END) FROM public.trip_loads WHERE trip_id=p_trip AND variant_id=p_variant),0)-coalesce((SELECT sum(i.qty) FROM public.sale_items i JOIN public.sales s ON s.id=i.sale_id WHERE s.trip_id=p_trip AND i.variant_id=p_variant),0) $function$;

CREATE OR REPLACE FUNCTION public.finance_expected(p_trip uuid)
 RETURNS numeric
 LANGUAGE sql
 STABLE SECURITY DEFINER
 SET search_path TO 'pg_catalog', 'public'
AS $function$SELECT ( SELECT coalesce((SELECT sum(cash_paid) FROM public.sales WHERE trip_id=p_trip),0)+coalesce((SELECT sum(amount) FROM public.payments WHERE trip_id=p_trip),0)-coalesce((SELECT sum(amount) FROM public.expenses WHERE trip_id=p_trip),0) ) - coalesce((SELECT sum(amount) FROM public.pureice_deposits WHERE trip_id=p_trip),0)$function$;

CREATE OR REPLACE FUNCTION public.finance_trip_scope(_trip uuid)
 RETURNS boolean
 LANGUAGE sql
 STABLE SECURITY DEFINER
 SET search_path TO 'pg_catalog', 'public'
AS $function$ SELECT public.is_manager(auth.uid()) OR (public.has_role(auth.uid(),'distributor') AND EXISTS(SELECT 1 FROM public.trips WHERE id=_trip AND distributor_id=auth.uid())) $function$;

CREATE OR REPLACE FUNCTION public.finance_customer_scope(_customer uuid)
 RETURNS boolean
 LANGUAGE sql
 STABLE SECURITY DEFINER
 SET search_path TO 'pg_catalog', 'public'
AS $function$ SELECT public.is_manager(auth.uid()) OR (public.has_role(auth.uid(),'distributor') AND EXISTS(SELECT 1 FROM public.customers c JOIN public.trips t ON t.distributor_id=auth.uid() AND t.status='open' WHERE c.id=_customer AND c.truck_id=t.truck_id)) $function$;

CREATE OR REPLACE FUNCTION public.pureice_directory_customer(_customer uuid)
 RETURNS boolean
 LANGUAGE sql
 STABLE SECURITY DEFINER
 SET search_path TO 'pg_catalog', 'public'
AS $function$
SELECT public.is_manager(auth.uid())
    OR public.finance_customer_scope(_customer)
    OR (public.has_role(auth.uid(),'distributor') AND EXISTS(SELECT 1 FROM public.customers WHERE id=_customer AND active)) $function$;

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
IF NOT public.is_manager(auth.uid()) AND t.distributor_id IS DISTINCT FROM auth.uid() THEN RAISE EXCEPTION 'الموزع غير معين للحملة'; END IF;
RETURN t; END $function$;

CREATE OR REPLACE FUNCTION public.finance_guard_field()
 RETURNS trigger
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'pg_catalog', 'public'
AS $function$
DECLARE t public.trips;c public.customers;previous jsonb;f public.freezers;
BEGIN
IF TG_OP<>'INSERT' THEN RAISE EXCEPTION 'السجلات المالية لا تعدل بعد حفظها'; END IF;
IF NEW.created_by IS DISTINCT FROM auth.uid() OR NOT public.is_staff(auth.uid()) THEN RAISE EXCEPTION 'الحساب غير مصرح'; END IF;
PERFORM pg_advisory_xact_lock(hashtextextended(TG_TABLE_NAME||':'||NEW.id::text,0));
EXECUTE format('SELECT to_jsonb(p) FROM public.%I p WHERE id=$1',TG_TABLE_NAME) INTO previous USING NEW.id;
IF previous IS NOT NULL THEN IF (previous-'created_at') IS DISTINCT FROM (to_jsonb(NEW)-'created_at') THEN RAISE EXCEPTION 'تعارض معرف السجل'; END IF; RETURN NULL; END IF;
t:=public.finance_lock_trip(NEW.trip_id);
IF TG_TABLE_NAME<>'freezer_events' THEN IF NEW.truck_id IS DISTINCT FROM t.truck_id THEN RAISE EXCEPTION 'الشاحنة لا تتبع الحملة'; END IF; END IF;
IF TG_TABLE_NAME='payments' THEN
SELECT * INTO c FROM public.customers WHERE id=NEW.customer_id FOR UPDATE;
IF NOT FOUND OR NOT public.pureice_directory_customer(c.id) THEN RAISE EXCEPTION 'العميل غير مصرح'; END IF;
IF NOT public.finance_number_ok(NEW.amount) OR NEW.amount<=0 OR NEW.amount>public.finance_balance(c.id) THEN RAISE EXCEPTION 'التحصيل غير صحيح أو يتجاوز الدين'; END IF;
ELSIF TG_TABLE_NAME='expenses' THEN
IF NOT public.finance_number_ok(NEW.amount) OR NEW.amount<=0 OR nullif(btrim(NEW.category),'') IS NULL THEN RAISE EXCEPTION 'المصروف غير صحيح'; END IF;
IF NEW.category='وقود' AND (NEW.odometer IS NULL OR NEW.odometer_photo_path IS NULL) THEN RAISE EXCEPTION 'قراءة وصورة العداد مطلوبتان'; END IF;
IF NEW.odometer IS NOT NULL AND (NOT public.finance_number_ok(NEW.odometer) OR NEW.odometer<0) THEN RAISE EXCEPTION 'قراءة العداد غير صحيحة'; END IF;
ELSIF TG_TABLE_NAME='gps_points' THEN
IF NOT (NEW.lat BETWEEN -90 AND 90 AND NEW.lng BETWEEN -180 AND 180) THEN RAISE EXCEPTION 'إحداثيات غير صحيحة'; END IF;
ELSIF TG_TABLE_NAME='maintenance_jobs' THEN
IF NEW.freezer_id IS NOT NULL OR NOT public.finance_number_ok(NEW.cost) OR NEW.cost<0 OR nullif(btrim(NEW.title),'') IS NULL THEN RAISE EXCEPTION 'الصيانة للشاحنة فقط والتكلفة غير سالبة'; END IF;
ELSIF TG_TABLE_NAME='freezer_events' THEN
SELECT * INTO f FROM public.freezers WHERE id=NEW.freezer_id FOR UPDATE;
IF NOT FOUND OR NEW.event_type NOT IN ('place','move','remove','maintenance','condition') THEN RAISE EXCEPTION 'حركة الثلاجة غير صحيحة'; END IF;
IF NOT public.is_manager(auth.uid()) AND f.customer_id IS NOT NULL AND NOT public.pureice_directory_customer(f.customer_id) THEN RAISE EXCEPTION 'الثلاجة غير مصرح بها'; END IF;
IF NEW.event_type IN ('place','move') AND (NEW.customer_id IS NULL OR NOT public.pureice_directory_customer(NEW.customer_id)) THEN RAISE EXCEPTION 'العميل غير مصرح'; END IF;
END IF; RETURN NEW; END $function$;

CREATE OR REPLACE FUNCTION public.finance_field_scope_backstop()
 RETURNS trigger
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'pg_catalog', 'public'
AS $function$ DECLARE t public.trips;c public.customers; BEGIN IF TG_TABLE_NAME='customers' AND TG_OP='INSERT' AND NOT public.is_manager(auth.uid()) THEN SELECT * INTO t FROM public.trips WHERE distributor_id=auth.uid() AND status='open' AND truck_id=NEW.truck_id FOR UPDATE; IF NOT FOUND OR EXISTS(SELECT 1 FROM public.closings WHERE trip_id=t.id) THEN RAISE EXCEPTION 'الحملة غير متاحة'; END IF; ELSIF TG_TABLE_NAME='freezer_events' THEN IF NEW.customer_id IS NOT NULL THEN SELECT * INTO c FROM public.customers WHERE id=NEW.customer_id; SELECT * INTO t FROM public.trips WHERE id=NEW.trip_id; IF NOT public.is_manager(auth.uid()) AND (c.id IS NULL OR NOT c.active OR NOT public.pureice_directory_customer(c.id)) THEN RAISE EXCEPTION 'العميل لا يتبع شاحنة الحملة'; END IF; END IF; ELSIF TG_TABLE_NAME='expenses' THEN IF NEW.receipt_path IS NOT NULL AND NEW.receipt_path NOT LIKE auth.uid()::text||'/%' OR NEW.odometer_photo_path IS NOT NULL AND NEW.odometer_photo_path NOT LIKE auth.uid()::text||'/%' THEN RAISE EXCEPTION 'الصورة تخص حساباً آخر'; END IF; IF NEW.category='وقود' AND nullif(btrim(NEW.odometer_photo_path),'') IS NULL THEN RAISE EXCEPTION 'صورة العداد مطلوبة'; END IF; ELSIF TG_TABLE_NAME='maintenance_jobs' THEN IF NEW.receipt_path IS NOT NULL AND NEW.receipt_path NOT LIKE auth.uid()::text||'/%' THEN RAISE EXCEPTION 'الصورة تخص حساباً آخر'; END IF; END IF; RETURN NEW; END $function$;

CREATE OR REPLACE FUNCTION public.finance_guard_customer()
 RETURNS trigger
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'pg_catalog', 'public'
AS $function$ BEGIN
IF TG_OP='INSERT' THEN
IF NOT public.is_staff(auth.uid()) THEN RAISE EXCEPTION 'الحساب غير نشط'; END IF;
IF NOT public.is_manager(auth.uid()) AND (NEW.credit_limit<>0 OR NOT EXISTS(SELECT 1 FROM public.trips WHERE distributor_id=auth.uid() AND status='open' AND truck_id=NEW.truck_id AND NOT EXISTS(SELECT 1 FROM public.closings WHERE trip_id=trips.id))) THEN RAISE EXCEPTION 'العميل لا يتبع الحملة أو حد الائتمان غير مصرح'; END IF;NEW.created_by:=auth.uid(); END IF;
IF NOT public.finance_number_ok(NEW.credit_limit) OR NEW.credit_limit<0 THEN RAISE EXCEPTION 'حد الائتمان غير صحيح'; END IF;RETURN NEW;END $function$;

CREATE OR REPLACE FUNCTION public.guard_trip_loads()
 RETURNS trigger
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'pg_catalog', 'public'
AS $function$
DECLARE t public.trips; BEGIN
t:=public.finance_lock_trip(CASE WHEN TG_OP='DELETE' THEN OLD.trip_id ELSE NEW.trip_id END,true);
IF TG_OP='DELETE' THEN IF NOT public.is_manager(auth.uid()) OR (OLD.kind='load' AND OLD.approved_at IS NOT NULL) THEN RAISE EXCEPTION 'لا يمكن حذف التحميل المعتمد'; END IF; RETURN OLD; END IF;
IF TG_OP='UPDATE' THEN IF (to_jsonb(NEW)-'approved_at'-'approved_by') IS DISTINCT FROM (to_jsonb(OLD)-'approved_at'-'approved_by') OR OLD.approved_at IS NOT NULL OR OLD.kind<>'load' OR t.distributor_id IS DISTINCT FROM auth.uid() THEN RAISE EXCEPTION 'اعتماد غير صحيح'; END IF; RETURN NEW; END IF;
IF NOT public.is_manager(auth.uid()) OR NEW.kind NOT IN ('load','return','damaged') OR NOT public.finance_number_ok(NEW.qty) OR NEW.qty<=0 THEN RAISE EXCEPTION 'تحميل غير صحيح'; END IF;
IF NEW.kind='load' AND EXISTS(SELECT 1 FROM public.closings WHERE trip_id=t.id) THEN RAISE EXCEPTION 'التحميل متوقف بعد الإغلاق'; END IF;
IF NEW.kind<>'load' AND NEW.qty>public.finance_remaining(NEW.trip_id,NEW.variant_id) THEN RAISE EXCEPTION 'المرتجع أو التالف يتجاوز المتبقي'; END IF;
NEW.created_by:=auth.uid();NEW.created_at:=now();NEW.approved_at:=CASE WHEN NEW.kind='load' THEN NULL ELSE now() END;NEW.approved_by:=NULL; RETURN NEW; END $function$;

CREATE OR REPLACE FUNCTION public.approve_trip_loads(_trip uuid)
 RETURNS integer
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'pg_catalog', 'public'
AS $function$ DECLARE t public.trips;n integer; BEGIN t:=public.finance_lock_trip(_trip);IF t.distributor_id IS DISTINCT FROM auth.uid() THEN RAISE EXCEPTION 'الموزع المعين فقط يعتمد'; END IF;UPDATE public.trip_loads SET approved_at=now(),approved_by=auth.uid() WHERE trip_id=_trip AND kind='load' AND approved_at IS NULL;GET DIAGNOSTICS n=ROW_COUNT;IF n=0 THEN RAISE EXCEPTION 'لا يوجد تحميل للاعتماد'; END IF;UPDATE public.trips SET loading_approved_at=coalesce(loading_approved_at,now()) WHERE id=_trip;RETURN n;END $function$;

CREATE OR REPLACE FUNCTION public.submit_closing(p_id uuid, p_trip uuid, p_counted numeric, p_notes text DEFAULT NULL::text)
 RETURNS closings
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'pg_catalog', 'public'
AS $function$
DECLARE t public.trips;c public.closings;e numeric;BEGIN
SELECT * INTO t FROM public.trips WHERE id=p_trip FOR UPDATE;
IF NOT public.is_staff(auth.uid()) OR t.distributor_id IS DISTINCT FROM auth.uid() THEN RAISE EXCEPTION 'الموزع المعين فقط يرسل الإغلاق'; END IF;
SELECT * INTO c FROM public.closings WHERE trip_id=p_trip FOR UPDATE;
IF FOUND AND c.id=p_id AND c.created_by=auth.uid() AND c.status IN ('submitted','approved') AND c.counted_cash=p_counted AND c.notes IS NOT DISTINCT FROM p_notes THEN RETURN c; END IF;
t:=public.finance_lock_trip(p_trip,true);
IF NOT public.finance_number_ok(p_counted) OR p_counted<0 THEN RAISE EXCEPTION 'النقد المعدود غير صحيح'; END IF;
IF c.id IS NOT NULL THEN
IF c.id<>p_id OR c.created_by<>auth.uid() OR c.status<>'flagged' THEN RAISE EXCEPTION 'الإغلاق غير قابل للتعديل'; END IF;
e:=public.finance_expected(p_trip);UPDATE public.closings SET counted_cash=p_counted,expected_cash=e,difference=p_counted-e,notes=p_notes,status='submitted',resubmitted_at=now(),reviewed_by=NULL,reviewed_at=NULL,review_note=NULL WHERE id=c.id RETURNING * INTO c;
ELSE

IF EXISTS(SELECT 1 FROM public.trip_loads WHERE trip_id=p_trip AND kind='load' AND approved_at IS NULL) OR t.loading_approved_at IS NULL THEN
 IF p_counted<>0 OR EXISTS(SELECT 1 FROM public.trip_loads WHERE trip_id=p_trip)
 OR EXISTS(SELECT 1 FROM public.sales WHERE trip_id=p_trip)
 OR EXISTS(SELECT 1 FROM public.payments WHERE trip_id=p_trip)
 OR EXISTS(SELECT 1 FROM public.expenses WHERE trip_id=p_trip)
 OR EXISTS(SELECT 1 FROM public.maintenance_jobs WHERE trip_id=p_trip AND cost<>0)
 OR public.finance_expected(p_trip)<>0
 THEN RAISE EXCEPTION 'يجب اعتماد التحميل أولاً'; END IF;
END IF;

e:=public.finance_expected(p_trip);INSERT INTO public.closings(id,trip_id,expected_cash,counted_cash,difference,notes,created_by) VALUES(p_id,p_trip,e,p_counted,p_counted-e,p_notes,auth.uid()) RETURNING * INTO c;
END IF;RETURN c;END $function$;

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
IF t.distributor_id IS DISTINCT FROM auth.uid() OR t.loading_approved_at IS NULL THEN RAISE EXCEPTION 'اعتماد التحميل وتعيين الموزع مطلوبان'; END IF;
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
END $function$;

CREATE OR REPLACE FUNCTION public.pureice_my_truck()
 RETURNS uuid
 LANGUAGE sql
 STABLE SECURITY DEFINER
 SET search_path TO 'pg_catalog', 'public'
AS $function$ SELECT truck_id FROM public.trips WHERE distributor_id = auth.uid() AND status = 'open' ORDER BY created_at DESC LIMIT 1 $function$;

CREATE OR REPLACE FUNCTION public.campaign_stock(p_trip uuid)
 RETURNS TABLE(trip_id uuid, variant_id uuid, loaded numeric, sold numeric, returned numeric, damaged numeric)
 LANGUAGE sql
 STABLE SECURITY DEFINER
 SET search_path TO 'pg_catalog', 'public'
AS $function$ SELECT p_trip,v.id,coalesce((SELECT sum(qty) FROM public.trip_loads WHERE trip_id=p_trip AND variant_id=v.id AND kind='load' AND approved_at IS NOT NULL),0),coalesce((SELECT sum(i.qty) FROM public.sale_items i JOIN public.sales s ON s.id=i.sale_id WHERE s.trip_id=p_trip AND i.variant_id=v.id),0),coalesce((SELECT sum(qty) FROM public.trip_loads WHERE trip_id=p_trip AND variant_id=v.id AND kind='return'),0),coalesce((SELECT sum(qty) FROM public.trip_loads WHERE trip_id=p_trip AND variant_id=v.id AND kind='damaged'),0) FROM public.product_variants v WHERE public.finance_trip_scope(p_trip) $function$;

CREATE OR REPLACE FUNCTION public.my_committed_operations()
 RETURNS TABLE(id uuid)
 LANGUAGE sql
 STABLE SECURITY DEFINER
 SET search_path TO 'pg_catalog', 'public'
AS $function$ SELECT id FROM public.sales WHERE created_by=auth.uid() AND public.is_staff(auth.uid()) UNION ALL SELECT id FROM public.payments WHERE created_by=auth.uid() AND public.is_staff(auth.uid()) UNION ALL SELECT id FROM public.expenses WHERE created_by=auth.uid() AND public.is_staff(auth.uid()) UNION ALL SELECT id FROM public.maintenance_jobs WHERE created_by=auth.uid() AND public.is_staff(auth.uid()) UNION ALL SELECT id FROM public.freezer_events WHERE created_by=auth.uid() AND public.is_staff(auth.uid()) UNION ALL SELECT id FROM public.gps_points WHERE created_by=auth.uid() AND public.is_staff(auth.uid()) UNION ALL SELECT id FROM public.closings WHERE created_by=auth.uid() AND public.is_staff(auth.uid()) UNION ALL SELECT id FROM public.customers WHERE created_by=auth.uid() AND public.is_staff(auth.uid()) $function$;

CREATE OR REPLACE FUNCTION public.customer_ledger()
 RETURNS TABLE(customer_id uuid, balance numeric, total_sales numeric, month_sales numeric, last_sale_at timestamp with time zone)
 LANGUAGE sql
 STABLE SECURITY DEFINER
 SET search_path TO 'pg_catalog', 'public'
AS $function$
SELECT c.id,public.finance_balance(c.id),
 coalesce((SELECT sum(total) FROM public.sales s WHERE s.customer_id=c.id AND (public.is_manager(auth.uid()) OR s.created_by=auth.uid())),0),
 coalesce((SELECT sum(total) FROM public.sales s WHERE s.customer_id=c.id AND (public.is_manager(auth.uid()) OR s.created_by=auth.uid()) AND s.created_at>=date_trunc('month',now() AT TIME ZONE 'Asia/Aden') AT TIME ZONE 'Asia/Aden'),0),
 (SELECT max(created_at) FROM public.sales s WHERE s.customer_id=c.id AND (public.is_manager(auth.uid()) OR s.created_by=auth.uid()))
FROM public.customers c WHERE public.is_staff(auth.uid()) AND public.pureice_directory_customer(c.id) $function$;

CREATE OR REPLACE FUNCTION public.accounting_snapshot(p_trip uuid)
 RETURNS jsonb
 LANGUAGE sql
 STABLE SECURITY DEFINER
 SET search_path TO 'pg_catalog', 'public'
AS $function$ SELECT jsonb_build_object('balances',coalesce((SELECT jsonb_agg(r) FROM public.customer_ledger() r),'[]'::jsonb),'stock',coalesce((SELECT jsonb_agg(r) FROM public.campaign_stock(p_trip) r),'[]'::jsonb),'includedIds',coalesce((SELECT jsonb_agg(id) FROM public.my_committed_operations()),'[]'::jsonb),'sales',coalesce((SELECT jsonb_agg(to_jsonb(s)||jsonb_build_object('sale_items',coalesce((SELECT jsonb_agg(i) FROM public.sale_items i WHERE i.sale_id=s.id),'[]'::jsonb))) FROM public.sales s WHERE s.trip_id=p_trip AND public.finance_trip_scope(p_trip)),'[]'::jsonb),'payments',coalesce((SELECT jsonb_agg(p) FROM public.payments p WHERE p.trip_id=p_trip AND public.finance_trip_scope(p_trip)),'[]'::jsonb),'expenses',coalesce((SELECT jsonb_agg(e) FROM public.expenses e WHERE e.trip_id=p_trip AND public.finance_trip_scope(p_trip)),'[]'::jsonb),'closing',(SELECT to_jsonb(c) FROM public.closings c WHERE c.trip_id=p_trip AND public.finance_trip_scope(p_trip))) WHERE public.is_staff(auth.uid()) $function$;

CREATE TRIGGER finance_customer BEFORE INSERT OR UPDATE ON public.customers FOR EACH ROW EXECUTE FUNCTION finance_guard_customer();
CREATE TRIGGER finance_field BEFORE INSERT OR DELETE OR UPDATE ON public.expenses FOR EACH ROW EXECUTE FUNCTION finance_guard_field();
CREATE TRIGGER finance_field BEFORE INSERT OR DELETE OR UPDATE ON public.gps_points FOR EACH ROW EXECUTE FUNCTION finance_guard_field();
CREATE TRIGGER finance_field BEFORE INSERT OR DELETE OR UPDATE ON public.payments FOR EACH ROW EXECUTE FUNCTION finance_guard_field();
CREATE TRIGGER finance_scope_backstop BEFORE INSERT ON public.customers FOR EACH ROW EXECUTE FUNCTION finance_field_scope_backstop();
CREATE TRIGGER finance_scope_backstop BEFORE INSERT ON public.expenses FOR EACH ROW EXECUTE FUNCTION finance_field_scope_backstop();
CREATE TRIGGER guard_trip_loads BEFORE INSERT OR DELETE OR UPDATE ON public.trip_loads FOR EACH ROW EXECUTE FUNCTION guard_trip_loads();

-- As on the live project: campaigns are read through finance_trip_scope; field tables take inserts
-- (their guards decide); deposits are read by their owner or a manager; functions are callable by
-- signed-in accounts.
ALTER TABLE public.trips ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.pureice_deposits ENABLE ROW LEVEL SECURITY;
CREATE POLICY deposit_read ON public.pureice_deposits FOR SELECT TO authenticated USING (is_staff(auth.uid()) AND ((created_by = auth.uid()) OR is_manager(auth.uid())));
GRANT SELECT ON public.pureice_deposits TO authenticated;
CREATE POLICY finance_read ON public.trips FOR SELECT TO authenticated USING (public.finance_trip_scope(id));
GRANT SELECT, INSERT ON public.customers, public.sales, public.sale_items, public.payments, public.expenses, public.gps_points TO authenticated;
GRANT SELECT ON public.products, public.product_variants, public.customer_prices, public.trip_loads, public.pureice_boxes TO authenticated;
GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA public TO authenticated;

-- The changed functions' bodies before 95, for the rollback test.
CREATE TABLE t.original AS SELECT p.proname, md5(p.prosrc) AS src FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
 WHERE n.nspname = 'public' AND p.proname IN ('finance_trip_scope', 'finance_customer_scope', 'finance_lock_trip', 'finance_field_scope_backstop', 'finance_guard_customer', 'save_invoice', 'pureice_box_move', 'pureice_my_truck');
