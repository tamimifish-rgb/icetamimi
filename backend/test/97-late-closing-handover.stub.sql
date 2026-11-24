-- backend/92-closing-handover.sql as applied (the live pureice_save_closing_handover matched it by md5
-- on 2026-11-24). Generated; loaded by run.sh after stub-schema.sql.

CREATE TABLE public.pureice_closing_handover (
 closing_id uuid PRIMARY KEY REFERENCES public.closings(id) ON DELETE CASCADE,
 trip_id uuid NOT NULL REFERENCES public.trips(id),
 cash_amount numeric NOT NULL CHECK (cash_amount >= 0),
 transfer_amount numeric NOT NULL CHECK (transfer_amount >= 0),
 slip_path text CHECK (transfer_amount = 0 OR slip_path IS NOT NULL),
 created_by uuid NOT NULL REFERENCES public.profiles(id),
 created_at timestamptz NOT NULL DEFAULT now(),
 updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX pureice_closing_handover_trip ON public.pureice_closing_handover(trip_id);

REVOKE ALL ON public.pureice_closing_handover FROM PUBLIC, anon, authenticated;
GRANT SELECT ON public.pureice_closing_handover TO authenticated;
GRANT ALL ON public.pureice_closing_handover TO service_role;
ALTER TABLE public.pureice_closing_handover ENABLE ROW LEVEL SECURITY;
CREATE POLICY active_account_backstop ON public.pureice_closing_handover AS RESTRICTIVE FOR ALL TO authenticated
  USING ((SELECT public.active_staff(auth.uid()))) WITH CHECK ((SELECT public.active_staff(auth.uid())));
CREATE POLICY own_or_manager_read ON public.pureice_closing_handover FOR SELECT TO authenticated
  USING (created_by = (SELECT auth.uid()) OR (SELECT public.is_manager(auth.uid())));

-- The app shows the handover choice only when this answers 1.
CREATE FUNCTION public.pureice_closing_handover_support() RETURNS integer
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public AS
$$ SELECT CASE WHEN public.is_staff(auth.uid()) THEN 1 ELSE 0 END $$;
REVOKE ALL ON FUNCTION public.pureice_closing_handover_support() FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.pureice_closing_handover_support() TO authenticated;

-- Saves or corrects the handover of the caller's own closing, while it is not yet approved.
-- Request: {closing_id, cash_amount, transfer_amount, slip_path}. Cash plus transfer must equal the
-- counted cash; a transfer needs a slip in the caller's folder. Repeating a stored request returns it.
CREATE FUNCTION public.pureice_save_closing_handover(p_request jsonb) RETURNS public.pureice_closing_handover
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public AS $$
DECLARE
 uid uuid := auth.uid();
 cid uuid;
 cash numeric;
 transfer numeric;
 slip text := nullif(btrim(p_request->>'slip_path'), '');
 c public.closings;
 r public.pureice_closing_handover;
BEGIN
 IF NOT public.is_staff(uid) THEN RAISE EXCEPTION 'الحساب غير نشط' USING ERRCODE = '42501'; END IF;
 BEGIN
  cid := (p_request->>'closing_id')::uuid;
  cash := (p_request->>'cash_amount')::numeric;
  transfer := (p_request->>'transfer_amount')::numeric;
 EXCEPTION WHEN others THEN
  RAISE EXCEPTION 'بيانات التسليم غير صحيحة' USING ERRCODE = '22023';
 END;
 IF cid IS NULL OR cash IS NULL OR transfer IS NULL OR cash < 0 OR transfer < 0 THEN
  RAISE EXCEPTION 'بيانات التسليم غير صحيحة' USING ERRCODE = '22023';
 END IF;
 IF transfer = 0 THEN slip := NULL; END IF;

 SELECT * INTO c FROM public.closings WHERE id = cid FOR UPDATE;
 IF NOT FOUND OR c.created_by IS DISTINCT FROM uid THEN
  RAISE EXCEPTION 'الإغلاق ليس لهذا الحساب' USING ERRCODE = '42501';
 END IF;

 SELECT * INTO r FROM public.pureice_closing_handover WHERE closing_id = cid;
 IF FOUND AND r.cash_amount = cash AND r.transfer_amount = transfer AND r.slip_path IS NOT DISTINCT FROM slip THEN
  RETURN r;
 END IF;
 IF c.status NOT IN ('submitted', 'flagged') THEN
  RAISE EXCEPTION 'تم اعتماد الإغلاق؛ لا يمكن تعديل طريقة التسليم' USING ERRCODE = '55000';
 END IF;
 IF cash + transfer <> c.counted_cash THEN
  RAISE EXCEPTION 'مجموع النقد والإيداع يجب أن يساوي النقد المعدود' USING ERRCODE = '22023';
 END IF;
 IF transfer > 0 AND (slip IS NULL OR slip NOT LIKE uid::text || '/%') THEN
  RAISE EXCEPTION 'أرفق صورة أو ملف إشعار الإيداع' USING ERRCODE = '22023';
 END IF;

 INSERT INTO public.pureice_closing_handover(closing_id, trip_id, cash_amount, transfer_amount, slip_path, created_by)
 VALUES (cid, c.trip_id, cash, transfer, slip, uid)
 ON CONFLICT (closing_id) DO UPDATE
   SET cash_amount = EXCLUDED.cash_amount, transfer_amount = EXCLUDED.transfer_amount,
       slip_path = EXCLUDED.slip_path, updated_at = now()
 RETURNING * INTO r;
 RETURN r;
END $$;
REVOKE ALL ON FUNCTION public.pureice_save_closing_handover(jsonb) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.pureice_save_closing_handover(jsonb) TO authenticated;


-- The function's body before 97, for the rollback test.
CREATE TABLE t.original97 AS SELECT md5(prosrc) AS v FROM pg_proc WHERE proname = 'pureice_save_closing_handover';
