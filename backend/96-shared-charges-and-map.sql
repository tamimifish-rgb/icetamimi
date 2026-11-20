-- Tamimi Ice (live Supabase project) — shortages shared with assistants, missing returns charged,
-- and data for the distributor's map. Used by the native Android app. Run once, as a single script.
--
-- 1. A charged cash shortage is split equally among the campaign's main distributor and its
--    assistants (before: all on the main distributor). Without assistants nothing changes.
-- 2. When the bags returned at the factory don't match, a supervisor settles the missing quantity
--    before approving: it is written off the truck (as a 'damaged' load row, so the web app and
--    review_closing see the stock as zero), valued at the product's price, and charged equally to
--    the main distributor and the assistants as 'stock_shortage' entries in their accounts.
-- 3. The campaign's route for its main distributor and assistants, and which products each customer
--    has bought in the last year, for the map's filters.
--
-- CHANGES EXISTING OBJECTS: pureice_decide_shortage (only its charge branch) and the constraints of
-- pureice_staff_ledger (a closing may now carry one shortage row per person, and a new kind).
-- Undo: backend/96-shared-charges-and-map-rollback.sql. Tests: backend/test/run.sh backend/96-shared-charges-and-map.sql
BEGIN;

-- One row per person and kind for a closing; a new kind for missing returns.
ALTER TABLE public.pureice_staff_ledger
  DROP CONSTRAINT pureice_staff_ledger_closing_id_key,
  DROP CONSTRAINT pureice_staff_ledger_check,
  DROP CONSTRAINT pureice_staff_ledger_kind_check,
  ADD CONSTRAINT pureice_staff_ledger_kind_check CHECK (kind IN ('shortage', 'stock_shortage', 'deduction', 'adjustment')),
  ADD CONSTRAINT pureice_staff_ledger_closing_check CHECK ((kind IN ('shortage', 'stock_shortage')) = (closing_id IS NOT NULL)),
  ADD CONSTRAINT pureice_staff_ledger_stock_positive CHECK (kind <> 'stock_shortage' OR amount > 0),
  ADD CONSTRAINT pureice_staff_ledger_closing_staff_kind_key UNIQUE (closing_id, staff_id, kind);

-- p_total split equally among the campaign's main distributor (p_main) and its assistants, in
-- shares of 0.01 that add up exactly; the main distributor takes the remainder. Internal.
CREATE FUNCTION public.pureice_split_charge(p_trip uuid, p_main uuid, p_total numeric)
RETURNS TABLE(staff_id uuid, amount numeric)
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public AS $$
 WITH people AS (
   SELECT p_main AS staff_id, true AS main
   UNION ALL
   SELECT a.user_id, false FROM public.pureice_trip_assistants a WHERE a.trip_id = p_trip AND a.user_id <> p_main),
 share AS (SELECT trunc(p_total / count(*), 2) AS each, count(*) AS n FROM people)
 SELECT p.staff_id, CASE WHEN p.main THEN p_total - share.each * (share.n - 1) ELSE share.each END
 FROM people p, share $$;
REVOKE ALL ON FUNCTION public.pureice_split_charge(uuid, uuid, numeric) FROM PUBLIC, anon, authenticated;

-- Managers: charge an approved closing's shortage (now split with the assistants), or absorb it.
CREATE OR REPLACE FUNCTION public.pureice_decide_shortage(p_closing uuid, p_charge boolean, p_note text)
RETURNS public.pureice_shortage_decision
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public AS $$
DECLARE
 uid uuid := auth.uid();
 c public.closings;
 d public.pureice_shortage_decision;
 note text := nullif(btrim(p_note), '');
BEGIN
 IF NOT public.is_manager(uid) THEN RAISE EXCEPTION 'غير مصرح' USING ERRCODE = '42501'; END IF;
 IF p_closing IS NULL OR p_charge IS NULL THEN RAISE EXCEPTION 'بيانات القرار غير صحيحة' USING ERRCODE = '22023'; END IF;
 SELECT * INTO c FROM public.closings WHERE id = p_closing FOR UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION 'الإغلاق غير موجود' USING ERRCODE = 'P0002'; END IF;
 SELECT * INTO d FROM public.pureice_shortage_decision WHERE closing_id = p_closing;
 IF FOUND THEN
  IF d.charged = p_charge THEN RETURN d; END IF;
  RAISE EXCEPTION 'تم اتخاذ القرار في هذا العجز من قبل' USING ERRCODE = '55000';
 END IF;
 IF c.status <> 'approved' THEN RAISE EXCEPTION 'اعتمد الإغلاق أولاً' USING ERRCODE = '55000'; END IF;
 IF c.difference >= 0 THEN RAISE EXCEPTION 'لا يوجد عجز في هذا الإغلاق' USING ERRCODE = '22023'; END IF;

 INSERT INTO public.pureice_shortage_decision(closing_id, charged, note, decided_by)
 VALUES (p_closing, p_charge, note, uid) RETURNING * INTO d;
 IF p_charge THEN
  INSERT INTO public.pureice_staff_ledger(id, staff_id, kind, amount, closing_id, note, created_by)
  SELECT gen_random_uuid(), s.staff_id, 'shortage', s.amount, p_closing, note, uid
  FROM public.pureice_split_charge(c.trip_id, c.created_by, abs(c.difference)) s WHERE s.amount > 0;
 END IF;
 RETURN d;
END $$;

-- Bags missing from the returns, written off and charged at settlement.
CREATE TABLE public.pureice_missing_stock (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 closing_id uuid NOT NULL REFERENCES public.closings(id),
 trip_id uuid NOT NULL REFERENCES public.trips(id),
 variant_id uuid NOT NULL REFERENCES public.product_variants(id),
 qty numeric NOT NULL CHECK (qty > 0),
 unit_price numeric NOT NULL CHECK (unit_price >= 0),
 value numeric NOT NULL CHECK (value >= 0),
 load_id uuid NOT NULL REFERENCES public.trip_loads(id),
 created_by uuid NOT NULL REFERENCES public.profiles(id),
 created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE (closing_id, variant_id)
);
CREATE INDEX pureice_missing_stock_trip ON public.pureice_missing_stock(trip_id);
REVOKE ALL ON public.pureice_missing_stock FROM PUBLIC, anon, authenticated;
GRANT SELECT ON public.pureice_missing_stock TO authenticated;
GRANT ALL ON public.pureice_missing_stock TO service_role;
ALTER TABLE public.pureice_missing_stock ENABLE ROW LEVEL SECURITY;
CREATE POLICY active_account_backstop ON public.pureice_missing_stock AS RESTRICTIVE FOR ALL TO authenticated
  USING ((SELECT public.active_staff(auth.uid()))) WITH CHECK ((SELECT public.active_staff(auth.uid())));
-- Managers, and the campaign's main distributor and assistants.
CREATE POLICY campaign_read ON public.pureice_missing_stock FOR SELECT TO authenticated
  USING (public.finance_trip_scope(trip_id));

-- What was settled for a closing: {items: [{variant_id, qty, unit_price, value}], total, shares: [{staff_id, amount}]}. Internal.
CREATE FUNCTION public.pureice_missing_stock_summary(p_closing uuid) RETURNS jsonb
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public AS $$
 SELECT jsonb_build_object(
  'items', coalesce((SELECT jsonb_agg(jsonb_build_object('variant_id', m.variant_id, 'qty', m.qty, 'unit_price', m.unit_price, 'value', m.value) ORDER BY m.variant_id)
                     FROM public.pureice_missing_stock m WHERE m.closing_id = p_closing), '[]'::jsonb),
  'total', coalesce((SELECT sum(m.value) FROM public.pureice_missing_stock m WHERE m.closing_id = p_closing), 0),
  'shares', coalesce((SELECT jsonb_agg(jsonb_build_object('staff_id', l.staff_id, 'amount', l.amount) ORDER BY l.amount DESC, l.staff_id)
                      FROM public.pureice_staff_ledger l WHERE l.closing_id = p_closing AND l.kind = 'stock_shortage'), '[]'::jsonb)) $$;
REVOKE ALL ON FUNCTION public.pureice_missing_stock_summary(uuid) FROM PUBLIC, anon, authenticated;

-- Managers, before approving a sent closing: every bag still on the truck after the returns and
-- damaged were recorded is written off as missing, valued at the product's price, and charged
-- equally to the main distributor and the assistants. Repeating it returns what was settled.
CREATE FUNCTION public.pureice_settle_missing_stock(p_closing uuid) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public AS $$
DECLARE
 uid uuid := auth.uid();
 c public.closings;
 t public.trips;
 v record;
 lid uuid;
 price numeric;
 total numeric;
BEGIN
 IF NOT public.is_manager(uid) THEN RAISE EXCEPTION 'غير مصرح' USING ERRCODE = '42501'; END IF;
 IF p_closing IS NULL THEN RAISE EXCEPTION 'بيانات غير صحيحة' USING ERRCODE = '22023'; END IF;
 SELECT * INTO c FROM public.closings WHERE id = p_closing;
 IF NOT FOUND THEN RAISE EXCEPTION 'الإغلاق غير موجود' USING ERRCODE = 'P0002'; END IF;
 SELECT * INTO t FROM public.trips WHERE id = c.trip_id FOR UPDATE;
 SELECT * INTO c FROM public.closings WHERE id = p_closing FOR UPDATE;

 IF NOT EXISTS (SELECT 1 FROM public.product_variants pv WHERE public.finance_remaining(t.id, pv.id) > 0) THEN
  IF EXISTS (SELECT 1 FROM public.pureice_missing_stock WHERE closing_id = p_closing) THEN
   RETURN public.pureice_missing_stock_summary(p_closing);
  END IF;
  RAISE EXCEPTION 'لا يوجد نقص في المرتجع' USING ERRCODE = '22023';
 END IF;
 IF c.status NOT IN ('submitted', 'flagged') OR t.status <> 'open' THEN
  RAISE EXCEPTION 'الإغلاق معتمد أو الحملة مغلقة' USING ERRCODE = '55000';
 END IF;
 IF EXISTS (SELECT 1 FROM public.pureice_missing_stock WHERE closing_id = p_closing) THEN
  RAISE EXCEPTION 'سُجل نقص هذا الإغلاق من قبل؛ سجّل المرتجع المتبقي' USING ERRCODE = '55000';
 END IF;

 FOR v IN SELECT pv.id, pv.price, public.finance_remaining(t.id, pv.id) AS left_qty
          FROM public.product_variants pv WHERE public.finance_remaining(t.id, pv.id) > 0 ORDER BY pv.id LOOP
  lid := gen_random_uuid();
  -- Through the existing load guard, as the manager: the bags leave the truck's stock.
  INSERT INTO public.trip_loads(id, trip_id, variant_id, qty, kind) VALUES (lid, t.id, v.id, v.left_qty, 'damaged');
  price := coalesce(v.price, 0);
  INSERT INTO public.pureice_missing_stock(closing_id, trip_id, variant_id, qty, unit_price, value, load_id, created_by)
  VALUES (p_closing, t.id, v.id, v.left_qty, price, round(v.left_qty * price, 2), lid, uid);
 END LOOP;

 SELECT sum(value) INTO total FROM public.pureice_missing_stock WHERE closing_id = p_closing;
 IF total > 0 THEN
  INSERT INTO public.pureice_staff_ledger(id, staff_id, kind, amount, closing_id, note, created_by)
  SELECT gen_random_uuid(), s.staff_id, 'stock_shortage', s.amount, p_closing, 'نقص في المرتجع', uid
  FROM public.pureice_split_charge(t.id, c.created_by, total) s WHERE s.amount > 0;
 END IF;
 RETURN public.pureice_missing_stock_summary(p_closing);
END $$;
REVOKE ALL ON FUNCTION public.pureice_settle_missing_stock(uuid) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.pureice_settle_missing_stock(uuid) TO authenticated;

-- The supervisor screens show the settlement only when this answers 1.
CREATE FUNCTION public.pureice_missing_stock_support() RETURNS integer
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public AS
$$ SELECT CASE WHEN public.is_staff(auth.uid()) THEN 1 ELSE 0 END $$;
REVOKE ALL ON FUNCTION public.pureice_missing_stock_support() FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.pureice_missing_stock_support() TO authenticated;

-- The campaign's route, for managers and the campaign's main distributor and assistants (an
-- assistant cannot read the main distributor's points directly).
CREATE FUNCTION public.pureice_trip_route(p_trip uuid) RETURNS TABLE(lat double precision, lng double precision, recorded_at timestamptz)
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public AS $$
 SELECT g.lat, g.lng, g.recorded_at FROM public.gps_points g
 WHERE g.trip_id = p_trip AND public.is_staff(auth.uid()) AND public.finance_trip_scope(p_trip)
 ORDER BY g.recorded_at, g.id
 LIMIT 5000 $$;
REVOKE ALL ON FUNCTION public.pureice_trip_route(uuid) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.pureice_trip_route(uuid) TO authenticated;

-- Which products each customer bought in the last year (any distributor), for the map's filter.
CREATE FUNCTION public.pureice_customer_products() RETURNS TABLE(customer_id uuid, variant_id uuid)
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public AS $$
 WITH pairs AS (
   SELECT DISTINCT s.customer_id, i.variant_id FROM public.sales s JOIN public.sale_items i ON i.sale_id = s.id
   WHERE s.customer_id IS NOT NULL AND s.created_at > now() - interval '365 days')
 SELECT p.customer_id, p.variant_id FROM pairs p
 WHERE public.is_staff(auth.uid()) AND public.pureice_directory_customer(p.customer_id) $$;
REVOKE ALL ON FUNCTION public.pureice_customer_products() FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.pureice_customer_products() TO authenticated;

-- The map shows the route and the product filter only when this answers 1.
CREATE FUNCTION public.pureice_map_extras_support() RETURNS integer
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public AS
$$ SELECT CASE WHEN public.is_staff(auth.uid()) THEN 1 ELSE 0 END $$;
REVOKE ALL ON FUNCTION public.pureice_map_extras_support() FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.pureice_map_extras_support() TO authenticated;

COMMIT;
