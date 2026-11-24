-- Tamimi Ice (live Supabase project) — a closing handover that arrives after its closing was approved.
-- Used by the native Android app. Run once, as a single script.
--
-- The phone sends the handover (cash / transfer / both, with the slip) right after the closing. If
-- the slip uploads slowly or the phone goes offline and the supervisor approves the closing first,
-- 92 refused the handover for good ("تم اعتماد الإغلاق؛ لا يمكن تعديل طريقة التسليم") and it stayed
-- blocked on the phone. Now a FIRST handover for an approved closing is recorded, with the same
-- checks (owner, cash + transfer = counted cash, slip in the owner's folder); changing an existing
-- handover after approval is still refused.
--
-- CHANGES EXISTING FUNCTION pureice_save_closing_handover: one condition; the rest is the 92 body.
-- Undo: backend/97-late-closing-handover-rollback.sql. Tests: backend/test/run.sh backend/97-late-closing-handover.sql
BEGIN;

CREATE OR REPLACE FUNCTION public.pureice_save_closing_handover(p_request jsonb) RETURNS public.pureice_closing_handover
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
 -- After approval the method can no longer change; a first handover that reaches the server late
 -- (a slow slip upload, the phone offline) is still recorded.
 IF c.status NOT IN ('submitted', 'flagged') AND r.closing_id IS NOT NULL THEN
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

COMMIT;
