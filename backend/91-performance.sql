-- Tamimi Ice (live Supabase project) — faster access rules and campaign indexes.
-- Run once, as a single script (it is one transaction). Rollback: 91-performance-rollback.sql.
--
-- Changes existing objects, unlike 90-*: no behaviour changes, only speed.
--
-- 1. Indexes on trip_id for route points, payments and expenses. The route map and campaign
--    totals read these by campaign; without an index every read scans the whole table.
--
-- 2. 38 row level security policies on the 10 busiest tables, rewritten so the checks that
--    depend only on the signed-in user run once per query instead of once per row
--    (Supabase linter 0003 auth_rls_initplan):
--      auth.uid()                  ->  (SELECT auth.uid())
--      is_manager(auth.uid()) etc. ->  (SELECT public.is_manager(auth.uid()))
--    Each expression is otherwise identical to the live one (generated from pg_policies on
--    2026-11-17). Checks that read the row, such as created_by = ..., still run per row.
--    ALTER POLICY keeps each policy's name, command, roles and permissive/restrictive kind.
--
-- The base schema lives with the web app (naqi-ice-tamimi/migration/tamimi-ice/sql): copy the
-- same rewrite there, or re-running those scripts brings the slower rules back.

-- After: Supabase's performance advisor (linter 0003 auth_rls_initplan) lists none of the
-- 10 tables, and the three indexes exist:
--   select indexname from pg_indexes
--    where indexname in ('gps_points_trip_id','payments_trip_id','expenses_trip_id');  -- 3 rows

BEGIN;

-- 1. Indexes
CREATE INDEX IF NOT EXISTS gps_points_trip_id ON public.gps_points(trip_id);
CREATE INDEX IF NOT EXISTS payments_trip_id ON public.payments(trip_id);
CREATE INDEX IF NOT EXISTS expenses_trip_id ON public.expenses(trip_id);

-- 2. Access rules, by table
ALTER POLICY active_account_backstop ON public.closings
  USING ((SELECT public.active_staff(auth.uid())))
  WITH CHECK ((SELECT public.active_staff(auth.uid())));

ALTER POLICY "distributor resubmit flagged" ON public.closings
  USING (((created_by = (SELECT auth.uid())) AND (status = 'flagged'::text)))
  WITH CHECK (((created_by = (SELECT auth.uid())) AND (status = 'submitted'::text)));

ALTER POLICY "manager update" ON public.closings
  USING ((SELECT public.is_manager(auth.uid())));

ALTER POLICY "own or manager read" ON public.closings
  USING (((SELECT public.active_staff(auth.uid())) AND ((created_by = (SELECT auth.uid())) OR (SELECT public.is_manager(auth.uid())))));

ALTER POLICY "staff insert own" ON public.closings
  WITH CHECK (((created_by = (SELECT auth.uid())) AND (SELECT public.is_staff(auth.uid()))));

ALTER POLICY active_account_backstop ON public.customer_prices
  USING ((SELECT public.active_staff(auth.uid())))
  WITH CHECK ((SELECT public.active_staff(auth.uid())));

ALTER POLICY "admin write del" ON public.customer_prices
  USING ((SELECT public.has_role(auth.uid(), 'admin'::public.app_role)));

ALTER POLICY "admin write ins" ON public.customer_prices
  WITH CHECK ((SELECT public.has_role(auth.uid(), 'admin'::public.app_role)));

ALTER POLICY "admin write upd" ON public.customer_prices
  USING ((SELECT public.has_role(auth.uid(), 'admin'::public.app_role)));

ALTER POLICY active_account_backstop ON public.customers
  USING ((SELECT public.active_staff(auth.uid())))
  WITH CHECK ((SELECT public.active_staff(auth.uid())));

ALTER POLICY "admin delete" ON public.customers
  USING ((SELECT public.has_role(auth.uid(), 'admin'::public.app_role)));

ALTER POLICY "manager update" ON public.customers
  USING ((SELECT public.is_manager(auth.uid())));

ALTER POLICY "staff insert" ON public.customers
  WITH CHECK ((SELECT public.is_staff(auth.uid())));

ALTER POLICY active_account_backstop ON public.expenses
  USING ((SELECT public.active_staff(auth.uid())))
  WITH CHECK ((SELECT public.active_staff(auth.uid())));

ALTER POLICY "manager update" ON public.expenses
  USING ((SELECT public.is_manager(auth.uid())));

ALTER POLICY "own or manager read" ON public.expenses
  USING (((SELECT public.active_staff(auth.uid())) AND ((created_by = (SELECT auth.uid())) OR (SELECT public.is_manager(auth.uid())))));

ALTER POLICY "staff insert own" ON public.expenses
  WITH CHECK (((created_by = (SELECT auth.uid())) AND (SELECT public.is_staff(auth.uid()))));

ALTER POLICY active_account_backstop ON public.gps_points
  USING ((SELECT public.active_staff(auth.uid())))
  WITH CHECK ((SELECT public.active_staff(auth.uid())));

ALTER POLICY "manager update" ON public.gps_points
  USING ((SELECT public.is_manager(auth.uid())));

ALTER POLICY "own or manager read" ON public.gps_points
  USING (((SELECT public.active_staff(auth.uid())) AND ((created_by = (SELECT auth.uid())) OR (SELECT public.is_manager(auth.uid())))));

ALTER POLICY "staff insert own" ON public.gps_points
  WITH CHECK (((created_by = (SELECT auth.uid())) AND (SELECT public.is_staff(auth.uid()))));

ALTER POLICY active_account_backstop ON public.payments
  USING ((SELECT public.active_staff(auth.uid())))
  WITH CHECK ((SELECT public.active_staff(auth.uid())));

ALTER POLICY "manager update" ON public.payments
  USING ((SELECT public.is_manager(auth.uid())));

ALTER POLICY "own or manager read" ON public.payments
  USING (((SELECT public.active_staff(auth.uid())) AND ((created_by = (SELECT auth.uid())) OR (SELECT public.is_manager(auth.uid())))));

ALTER POLICY "staff insert own" ON public.payments
  WITH CHECK (((created_by = (SELECT auth.uid())) AND (SELECT public.is_staff(auth.uid()))));

ALTER POLICY active_account_backstop ON public.sale_items
  USING ((SELECT public.active_staff(auth.uid())))
  WITH CHECK ((SELECT public.active_staff(auth.uid())));

ALTER POLICY "insert items" ON public.sale_items
  WITH CHECK ((EXISTS ( SELECT 1
   FROM public.sales s
  WHERE ((s.id = sale_items.sale_id) AND (s.created_by = (SELECT auth.uid()))))));

ALTER POLICY "read items" ON public.sale_items
  USING (((SELECT public.active_staff(auth.uid())) AND (EXISTS ( SELECT 1
   FROM public.sales s
  WHERE ((s.id = sale_items.sale_id) AND ((s.created_by = (SELECT auth.uid())) OR (SELECT public.is_manager(auth.uid()))))))));

ALTER POLICY active_account_backstop ON public.sales
  USING ((SELECT public.active_staff(auth.uid())))
  WITH CHECK ((SELECT public.active_staff(auth.uid())));

ALTER POLICY "manager update" ON public.sales
  USING ((SELECT public.is_manager(auth.uid())));

ALTER POLICY "own or manager read" ON public.sales
  USING (((SELECT public.active_staff(auth.uid())) AND ((created_by = (SELECT auth.uid())) OR (SELECT public.is_manager(auth.uid())))));

ALTER POLICY "staff insert own" ON public.sales
  WITH CHECK (((created_by = (SELECT auth.uid())) AND (SELECT public.is_staff(auth.uid()))));

ALTER POLICY active_account_backstop ON public.trip_loads
  USING ((SELECT public.active_staff(auth.uid())))
  WITH CHECK ((SELECT public.active_staff(auth.uid())));

ALTER POLICY "manager del" ON public.trip_loads
  USING ((SELECT public.is_manager(auth.uid())));

ALTER POLICY "manager ins" ON public.trip_loads
  WITH CHECK ((SELECT public.is_manager(auth.uid())));

ALTER POLICY active_account_backstop ON public.trips
  USING ((SELECT public.active_staff(auth.uid())))
  WITH CHECK ((SELECT public.active_staff(auth.uid())));

ALTER POLICY "manager ins" ON public.trips
  WITH CHECK ((SELECT public.is_manager(auth.uid())));

ALTER POLICY "manager upd" ON public.trips
  USING ((SELECT public.is_manager(auth.uid())));

COMMIT;
