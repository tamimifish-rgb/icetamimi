-- Undoes 91-performance.sql: the 38 policies return to their definitions of 2026-11-17 and
-- the three indexes are dropped. Run once, as a single script.
BEGIN;

DROP INDEX IF EXISTS public.gps_points_trip_id;
DROP INDEX IF EXISTS public.payments_trip_id;
DROP INDEX IF EXISTS public.expenses_trip_id;

ALTER POLICY active_account_backstop ON public.closings
  USING (public.active_staff(auth.uid()))
  WITH CHECK (public.active_staff(auth.uid()));

ALTER POLICY "distributor resubmit flagged" ON public.closings
  USING (((created_by = auth.uid()) AND (status = 'flagged'::text)))
  WITH CHECK (((created_by = auth.uid()) AND (status = 'submitted'::text)));

ALTER POLICY "manager update" ON public.closings
  USING (public.is_manager(auth.uid()));

ALTER POLICY "own or manager read" ON public.closings
  USING ((public.active_staff(auth.uid()) AND ((created_by = auth.uid()) OR public.is_manager(auth.uid()))));

ALTER POLICY "staff insert own" ON public.closings
  WITH CHECK (((created_by = auth.uid()) AND public.is_staff(auth.uid())));

ALTER POLICY active_account_backstop ON public.customer_prices
  USING (public.active_staff(auth.uid()))
  WITH CHECK (public.active_staff(auth.uid()));

ALTER POLICY "admin write del" ON public.customer_prices
  USING (public.has_role(auth.uid(), 'admin'::public.app_role));

ALTER POLICY "admin write ins" ON public.customer_prices
  WITH CHECK (public.has_role(auth.uid(), 'admin'::public.app_role));

ALTER POLICY "admin write upd" ON public.customer_prices
  USING (public.has_role(auth.uid(), 'admin'::public.app_role));

ALTER POLICY active_account_backstop ON public.customers
  USING (public.active_staff(auth.uid()))
  WITH CHECK (public.active_staff(auth.uid()));

ALTER POLICY "admin delete" ON public.customers
  USING (public.has_role(auth.uid(), 'admin'::public.app_role));

ALTER POLICY "manager update" ON public.customers
  USING (public.is_manager(auth.uid()));

ALTER POLICY "staff insert" ON public.customers
  WITH CHECK (public.is_staff(auth.uid()));

ALTER POLICY active_account_backstop ON public.expenses
  USING (public.active_staff(auth.uid()))
  WITH CHECK (public.active_staff(auth.uid()));

ALTER POLICY "manager update" ON public.expenses
  USING (public.is_manager(auth.uid()));

ALTER POLICY "own or manager read" ON public.expenses
  USING ((public.active_staff(auth.uid()) AND ((created_by = auth.uid()) OR public.is_manager(auth.uid()))));

ALTER POLICY "staff insert own" ON public.expenses
  WITH CHECK (((created_by = auth.uid()) AND public.is_staff(auth.uid())));

ALTER POLICY active_account_backstop ON public.gps_points
  USING (public.active_staff(auth.uid()))
  WITH CHECK (public.active_staff(auth.uid()));

ALTER POLICY "manager update" ON public.gps_points
  USING (public.is_manager(auth.uid()));

ALTER POLICY "own or manager read" ON public.gps_points
  USING ((public.active_staff(auth.uid()) AND ((created_by = auth.uid()) OR public.is_manager(auth.uid()))));

ALTER POLICY "staff insert own" ON public.gps_points
  WITH CHECK (((created_by = auth.uid()) AND public.is_staff(auth.uid())));

ALTER POLICY active_account_backstop ON public.payments
  USING (public.active_staff(auth.uid()))
  WITH CHECK (public.active_staff(auth.uid()));

ALTER POLICY "manager update" ON public.payments
  USING (public.is_manager(auth.uid()));

ALTER POLICY "own or manager read" ON public.payments
  USING ((public.active_staff(auth.uid()) AND ((created_by = auth.uid()) OR public.is_manager(auth.uid()))));

ALTER POLICY "staff insert own" ON public.payments
  WITH CHECK (((created_by = auth.uid()) AND public.is_staff(auth.uid())));

ALTER POLICY active_account_backstop ON public.sale_items
  USING (public.active_staff(auth.uid()))
  WITH CHECK (public.active_staff(auth.uid()));

ALTER POLICY "insert items" ON public.sale_items
  WITH CHECK ((EXISTS ( SELECT 1
   FROM public.sales s
  WHERE ((s.id = sale_items.sale_id) AND (s.created_by = auth.uid())))));

ALTER POLICY "read items" ON public.sale_items
  USING ((public.active_staff(auth.uid()) AND (EXISTS ( SELECT 1
   FROM public.sales s
  WHERE ((s.id = sale_items.sale_id) AND ((s.created_by = auth.uid()) OR public.is_manager(auth.uid())))))));

ALTER POLICY active_account_backstop ON public.sales
  USING (public.active_staff(auth.uid()))
  WITH CHECK (public.active_staff(auth.uid()));

ALTER POLICY "manager update" ON public.sales
  USING (public.is_manager(auth.uid()));

ALTER POLICY "own or manager read" ON public.sales
  USING ((public.active_staff(auth.uid()) AND ((created_by = auth.uid()) OR public.is_manager(auth.uid()))));

ALTER POLICY "staff insert own" ON public.sales
  WITH CHECK (((created_by = auth.uid()) AND public.is_staff(auth.uid())));

ALTER POLICY active_account_backstop ON public.trip_loads
  USING (public.active_staff(auth.uid()))
  WITH CHECK (public.active_staff(auth.uid()));

ALTER POLICY "manager del" ON public.trip_loads
  USING (public.is_manager(auth.uid()));

ALTER POLICY "manager ins" ON public.trip_loads
  WITH CHECK (public.is_manager(auth.uid()));

ALTER POLICY active_account_backstop ON public.trips
  USING (public.active_staff(auth.uid()))
  WITH CHECK (public.active_staff(auth.uid()));

ALTER POLICY "manager ins" ON public.trips
  WITH CHECK (public.is_manager(auth.uid()));

ALTER POLICY "manager upd" ON public.trips
  USING (public.is_manager(auth.uid()));

COMMIT;
