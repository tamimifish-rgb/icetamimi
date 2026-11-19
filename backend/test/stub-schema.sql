-- A minimal stand-in for the live Tamimi Ice schema, enough to test backend/9N-*.sql scripts
-- on a scratch Postgres 16. The helper functions are copied from the live project (2026-11-19);
-- tables carry only the columns the scripts under test read or write.
CREATE ROLE anon NOLOGIN;
CREATE ROLE authenticated NOLOGIN;
CREATE ROLE service_role NOLOGIN;
CREATE SCHEMA auth;
CREATE FUNCTION auth.uid() RETURNS uuid LANGUAGE sql STABLE AS
$$ SELECT nullif(current_setting('request.jwt.claim.sub', true), '')::uuid $$;
GRANT USAGE ON SCHEMA auth TO authenticated, anon;
GRANT USAGE ON SCHEMA public TO authenticated, anon;

CREATE TYPE public.app_role AS ENUM ('admin', 'supervisor', 'distributor');
CREATE TABLE public.profiles (id uuid PRIMARY KEY, full_name text, active boolean NOT NULL DEFAULT true, truck_id uuid);
CREATE TABLE public.user_roles (id uuid PRIMARY KEY DEFAULT gen_random_uuid(), user_id uuid NOT NULL, role public.app_role NOT NULL);

CREATE FUNCTION public.active_staff(_id uuid) RETURNS boolean LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public AS
$$ SELECT EXISTS(SELECT 1 FROM profiles WHERE id=_id AND active) $$;
CREATE FUNCTION public.has_role(_user_id uuid, _role app_role) RETURNS boolean LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public AS
$$ SELECT public.active_staff(_user_id) AND EXISTS(SELECT 1 FROM user_roles WHERE user_id=_user_id AND role=_role) $$;
CREATE FUNCTION public.is_manager(_user_id uuid) RETURNS boolean LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public AS
$$ SELECT public.active_staff(_user_id) AND EXISTS(SELECT 1 FROM user_roles WHERE user_id=_user_id AND role IN ('admin','supervisor')) $$;
CREATE FUNCTION public.is_staff(_user_id uuid) RETURNS boolean LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public AS
$$ SELECT public.active_staff(_user_id) AND EXISTS(SELECT 1 FROM user_roles WHERE user_id=_user_id) $$;
GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA public TO authenticated;

CREATE TABLE public.trucks (id uuid PRIMARY KEY, name text, active boolean NOT NULL DEFAULT true);
CREATE TABLE public.trips (
 id uuid PRIMARY KEY, truck_id uuid REFERENCES public.trucks(id), trip_date date NOT NULL DEFAULT current_date,
 status text NOT NULL DEFAULT 'open', distributor_id uuid, loading_approved_at timestamptz, closed_at timestamptz,
 created_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE public.closings (
 id uuid PRIMARY KEY, trip_id uuid NOT NULL REFERENCES public.trips(id), expected_cash numeric NOT NULL DEFAULT 0,
 counted_cash numeric NOT NULL, difference numeric NOT NULL DEFAULT 0, notes text, status text NOT NULL DEFAULT 'submitted',
 reviewed_by uuid, reviewed_at timestamptz, created_by uuid NOT NULL, created_at timestamptz NOT NULL DEFAULT now(),
 review_note text, resubmitted_at timestamptz);
GRANT SELECT ON public.trips, public.closings, public.trucks, public.profiles, public.user_roles TO authenticated;

-- Fixed test accounts: an admin, a supervisor, two distributors, an inactive distributor, and an id with no account.
INSERT INTO public.profiles(id, full_name, active) VALUES
 ('a0000000-0000-0000-0000-000000000000', 'admin', true), ('50000000-0000-0000-0000-000000000000', 'supervisor', true),
 ('d1000000-0000-0000-0000-000000000000', 'distributor one', true), ('d2000000-0000-0000-0000-000000000000', 'distributor two', true),
 ('d3000000-0000-0000-0000-000000000000', 'inactive', false);
INSERT INTO public.user_roles(user_id, role) VALUES
 ('a0000000-0000-0000-0000-000000000000', 'admin'), ('50000000-0000-0000-0000-000000000000', 'supervisor'),
 ('d1000000-0000-0000-0000-000000000000', 'distributor'), ('d2000000-0000-0000-0000-000000000000', 'distributor'),
 ('d3000000-0000-0000-0000-000000000000', 'distributor');
INSERT INTO public.trucks(id, name) VALUES ('71000000-0000-0000-0000-000000000000', 'truck one'), ('72000000-0000-0000-0000-000000000000', 'truck two');

-- Test helpers: run SQL as an account, and expect it to succeed or to be refused.
CREATE SCHEMA t;
CREATE FUNCTION t.as_user(uid uuid, sql text) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
  PERFORM set_config('request.jwt.claim.sub', coalesce(uid::text, ''), true);
  EXECUTE 'SET LOCAL ROLE authenticated';
  BEGIN
    EXECUTE sql;
  EXCEPTION WHEN others THEN
    EXECUTE 'RESET ROLE';
    RAISE;
  END;
  EXECUTE 'RESET ROLE';
END $$;
CREATE FUNCTION t.ok(uid uuid, sql text, label text) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
  PERFORM t.as_user(uid, sql);
  RAISE NOTICE 'ok: %', label;
EXCEPTION WHEN others THEN
  RAISE EXCEPTION 'FAIL: % was refused: %', label, SQLERRM;
END $$;
CREATE FUNCTION t.refused(uid uuid, sql text, label text) RETURNS void LANGUAGE plpgsql AS $$
DECLARE ran boolean := false;
BEGIN
  BEGIN
    PERFORM t.as_user(uid, sql);
    ran := true;
  EXCEPTION WHEN others THEN
    RAISE NOTICE 'ok refused: % (%)', label, SQLERRM;
  END;
  IF ran THEN RAISE EXCEPTION 'FAIL: % should have been refused', label; END IF;
END $$;
CREATE FUNCTION t.check(cond boolean, label text) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
  IF cond IS NOT TRUE THEN RAISE EXCEPTION 'FAIL: %', label; END IF;
  RAISE NOTICE 'ok: %', label;
END $$;
-- Rows an account can see through row level security.
CREATE FUNCTION t.visible(uid uuid, sql text) RETURNS bigint LANGUAGE plpgsql AS $$
DECLARE n bigint;
BEGIN
  PERFORM set_config('request.jwt.claim.sub', coalesce(uid::text, ''), true);
  EXECUTE 'SET LOCAL ROLE authenticated';
  EXECUTE 'SELECT count(*) FROM (' || sql || ') x' INTO n;
  EXECUTE 'RESET ROLE';
  RETURN n;
END $$;
