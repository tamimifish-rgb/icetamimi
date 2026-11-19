-- Tamimi Ice (live Supabase project) — each employee's account: charged shortages and salary deductions.
-- Used by the native Android app only. Run once, as a single script. Additive: nothing existing changes.
--
-- When a supervisor approves a closing that is short of cash, he decides whether the shortage is
-- charged to the distributor or absorbed by the company. A charged shortage is added to the
-- distributor's account; the admin records what is deducted from his salary. The decision is kept
-- apart from review_closing, so closings approved from the web app (and those approved before this
-- script) can still be decided later. Tests: backend/test/run.sh backend/93-staff-ledger.sql
--
-- Amounts: positive = the employee owes the company. A shortage adds abs(difference); a salary
-- deduction is stored negative; an adjustment may be either sign.
BEGIN;

CREATE TABLE public.pureice_staff_ledger (
 id uuid PRIMARY KEY,
 staff_id uuid NOT NULL REFERENCES public.profiles(id),
 kind text NOT NULL CHECK (kind IN ('shortage', 'deduction', 'adjustment')),
 amount numeric NOT NULL CHECK (amount <> 0),
 closing_id uuid UNIQUE REFERENCES public.closings(id),
 note text,
 created_by uuid NOT NULL REFERENCES public.profiles(id),
 created_at timestamptz NOT NULL DEFAULT now(),
 CHECK ((kind = 'shortage') = (closing_id IS NOT NULL)),
 CHECK (kind <> 'shortage' OR amount > 0),
 CHECK (kind <> 'deduction' OR amount < 0)
);
CREATE INDEX pureice_staff_ledger_staff ON public.pureice_staff_ledger(staff_id, created_at);

CREATE TABLE public.pureice_shortage_decision (
 closing_id uuid PRIMARY KEY REFERENCES public.closings(id),
 charged boolean NOT NULL,
 note text,
 decided_by uuid NOT NULL REFERENCES public.profiles(id),
 decided_at timestamptz NOT NULL DEFAULT now()
);

DO $$
DECLARE t text;
BEGIN
 FOREACH t IN ARRAY ARRAY['pureice_staff_ledger', 'pureice_shortage_decision'] LOOP
  EXECUTE format('REVOKE ALL ON public.%I FROM PUBLIC, anon, authenticated', t);
  EXECUTE format('GRANT SELECT ON public.%I TO authenticated', t);
  EXECUTE format('GRANT ALL ON public.%I TO service_role', t);
  EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t);
  EXECUTE format('CREATE POLICY active_account_backstop ON public.%I AS RESTRICTIVE FOR ALL TO authenticated USING ((SELECT public.active_staff(auth.uid()))) WITH CHECK ((SELECT public.active_staff(auth.uid())))', t);
 END LOOP;
END $$;
CREATE POLICY own_or_manager_read ON public.pureice_staff_ledger FOR SELECT TO authenticated
  USING (staff_id = (SELECT auth.uid()) OR (SELECT public.is_manager(auth.uid())));
CREATE POLICY own_or_manager_read ON public.pureice_shortage_decision FOR SELECT TO authenticated
  USING ((SELECT public.is_manager(auth.uid()))
    OR EXISTS (SELECT 1 FROM public.closings c WHERE c.id = closing_id AND c.created_by = (SELECT auth.uid())));

-- The app shows the account and the charge decision only when this answers 1.
CREATE FUNCTION public.pureice_staff_ledger_support() RETURNS integer
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public AS
$$ SELECT CASE WHEN public.is_staff(auth.uid()) THEN 1 ELSE 0 END $$;
REVOKE ALL ON FUNCTION public.pureice_staff_ledger_support() FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.pureice_staff_ledger_support() TO authenticated;

-- Managers: charge an approved closing's shortage to its distributor, or absorb it. Once only;
-- repeating the same decision returns it.
CREATE FUNCTION public.pureice_decide_shortage(p_closing uuid, p_charge boolean, p_note text)
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
  VALUES (gen_random_uuid(), c.created_by, 'shortage', abs(c.difference), p_closing, note, uid);
 END IF;
 RETURN d;
END $$;
REVOKE ALL ON FUNCTION public.pureice_decide_shortage(uuid, boolean, text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.pureice_decide_shortage(uuid, boolean, text) TO authenticated;

-- Admin: a salary deduction (p_amount > 0, stored negative) or an adjustment (either sign).
-- The id comes from the phone; repeating the same entry returns it.
CREATE FUNCTION public.pureice_add_staff_entry(p_id uuid, p_staff uuid, p_kind text, p_amount numeric, p_note text)
RETURNS public.pureice_staff_ledger
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public AS $$
DECLARE
 uid uuid := auth.uid();
 amount numeric;
 note text := nullif(btrim(p_note), '');
 r public.pureice_staff_ledger;
BEGIN
 IF NOT public.has_role(uid, 'admin') THEN RAISE EXCEPTION 'للمدير فقط' USING ERRCODE = '42501'; END IF;
 IF p_id IS NULL OR p_kind NOT IN ('deduction', 'adjustment') OR p_amount IS NULL OR p_amount = 0
    OR (p_kind = 'deduction' AND p_amount < 0) THEN
  RAISE EXCEPTION 'بيانات القيد غير صحيحة' USING ERRCODE = '22023';
 END IF;
 IF NOT EXISTS (SELECT 1 FROM public.profiles WHERE id = p_staff) THEN
  RAISE EXCEPTION 'الموظف غير موجود' USING ERRCODE = 'P0002';
 END IF;
 amount := CASE WHEN p_kind = 'deduction' THEN -p_amount ELSE p_amount END;
 SELECT * INTO r FROM public.pureice_staff_ledger WHERE id = p_id;
 IF FOUND THEN
  IF r.staff_id = p_staff AND r.kind = p_kind AND r.amount = amount AND r.note IS NOT DISTINCT FROM note THEN RETURN r; END IF;
  RAISE EXCEPTION 'تعارض معرف القيد' USING ERRCODE = '23505';
 END IF;
 INSERT INTO public.pureice_staff_ledger(id, staff_id, kind, amount, note, created_by)
 VALUES (p_id, p_staff, p_kind, amount, note, uid) RETURNING * INTO r;
 RETURN r;
END $$;
REVOKE ALL ON FUNCTION public.pureice_add_staff_entry(uuid, uuid, text, numeric, text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.pureice_add_staff_entry(uuid, uuid, text, numeric, text) TO authenticated;

COMMIT;
