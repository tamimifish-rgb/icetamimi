-- Undoes backend/96-shared-charges-and-map.sql: restores pureice_decide_shortage and the ledger
-- constraints of 93, and removes the missing-stock table and the new functions.
--
-- Balances are kept: entries 96 made that the old rules do not allow become adjustments for the same
-- person and amount (missing-return charges, and the assistants' shares of a split shortage; the
-- main distributor's share stays the closing's shortage). Bags written off as missing stay written
-- off ('damaged' load rows).
BEGIN;

UPDATE public.pureice_staff_ledger SET kind = 'adjustment', closing_id = NULL,
       note = concat_ws(' — ', 'نقص في المرتجع', nullif(note, 'نقص في المرتجع'))
 WHERE kind = 'stock_shortage';
UPDATE public.pureice_staff_ledger l SET kind = 'adjustment', closing_id = NULL,
       note = concat_ws(' — ', 'حصة من عجز إغلاق', l.note)
  FROM public.closings c
 WHERE l.kind = 'shortage' AND c.id = l.closing_id AND l.staff_id <> c.created_by;

ALTER TABLE public.pureice_staff_ledger
  DROP CONSTRAINT pureice_staff_ledger_closing_staff_kind_key,
  DROP CONSTRAINT pureice_staff_ledger_stock_positive,
  DROP CONSTRAINT pureice_staff_ledger_closing_check,
  DROP CONSTRAINT pureice_staff_ledger_kind_check,
  ADD CONSTRAINT pureice_staff_ledger_kind_check CHECK (kind IN ('shortage', 'deduction', 'adjustment')),
  ADD CONSTRAINT pureice_staff_ledger_check CHECK ((kind = 'shortage') = (closing_id IS NOT NULL)),
  ADD CONSTRAINT pureice_staff_ledger_closing_id_key UNIQUE (closing_id);

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
  VALUES (gen_random_uuid(), c.created_by, 'shortage', abs(c.difference), p_closing, note, uid);
 END IF;
 RETURN d;
END $$;

DROP FUNCTION public.pureice_settle_missing_stock(uuid);
DROP FUNCTION public.pureice_missing_stock_summary(uuid);
DROP FUNCTION public.pureice_missing_stock_support();
DROP FUNCTION public.pureice_split_charge(uuid, uuid, numeric);
DROP FUNCTION public.pureice_trip_route(uuid);
DROP FUNCTION public.pureice_customer_products();
DROP FUNCTION public.pureice_map_extras_support();
DROP TABLE public.pureice_missing_stock;

COMMIT;
