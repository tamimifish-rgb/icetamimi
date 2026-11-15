"""Pure Ice MCP server: lets an AI agent (Hermes Agent, Claude, ...) read the Pure Ice
distribution data through the same API the Android and web apps use.

The agent signs in as an ordinary staff account (username + 6-digit PIN), through the
hosted `staffLogin` function, exactly like the phone. The database's own row-level
security then decides what that account may see, so give the agent its own account
with the narrowest role that answers your questions (supervisor is usually enough).

Read-only, enforced twice: no tool writes, and every outgoing request passes a guard
(`check_read_only`) that allows only GET on allowlisted tables plus the sign-in and
token-refresh calls. Inserts, updates, deletes, RPC functions and storage are refused
before they leave the machine.

Settings (environment variables):
    PUREICE_USERNAME   staff username for the agent's account (required)
    PUREICE_PIN        that account's 6-digit PIN (required)
    PUREICE_URL, PUREICE_KEY, PUREICE_SITE   override the backend (defaults: production)

Run:  python pureice_mcp.py          (stdio transport, what Hermes launches)
      python pureice_mcp.py --http   (streamable HTTP on 127.0.0.1:8765/mcp)
"""
from __future__ import annotations

import base64
import hashlib
import json
import os
import re
import sys
import threading
import time
from datetime import date, datetime, timedelta, timezone
from typing import Any
from urllib.parse import quote

import httpx
try:  # MCP Python SDK 2.x
    from mcp.server.mcpserver import MCPServer as FastMCP
    _SDK2 = True
except ImportError:  # 1.x
    from mcp.server.fastmcp import FastMCP
    _SDK2 = False

# ---- backend (the same nonsecret release values as app/.../data/Api.kt) ---------------

SUPABASE_URL = os.environ.get("PUREICE_URL", "https://monijxkifttaokcnyuoa.supabase.co").rstrip("/")
PUBLISHABLE_KEY = os.environ.get("PUREICE_KEY", "sb_publishable_G9Lw2cAeP6q71d9_-Rg1OA_TFGVOWRP")
SITE = os.environ.get("PUREICE_SITE", "https://naqi-ice-tamimi.lovable.app").rstrip("/")
BACKEND_MODE = "tamimi"
ADEN = timezone(timedelta(hours=3))  # Asia/Aden, no DST; the business day used everywhere

# Tables the generic `query_table` tool may read. Auth, throttling and staging tables are
# deliberately absent.
READABLE_TABLES = {
    "customers", "customer_balances", "customer_prices", "sales", "sale_items", "payments",
    "trips", "trip_loads", "trip_stock", "closings", "expenses", "maintenance_jobs",
    "trucks", "regions", "products", "product_variants", "freezers", "freezer_models",
    "freezer_events", "profiles", "user_roles", "pureice_deposits", "pureice_invoice_changes",
    "pureice_company_settings", "gps_points",
}
# Also read by the name lookups (already in READABLE_TABLES today; kept explicit).
LOOKUP_TABLES = {"trucks", "profiles", "product_variants", "regions", "customers"}


class PureIceError(Exception):
    pass


class ReadOnlyViolation(PureIceError):
    pass


def check_read_only(method: str, url: str) -> None:
    """The only requests this server may ever send. Data is read with GET on an allowlisted
    table; the two POSTs are sign-in and token refresh. Anything else (insert, update,
    delete, RPC functions, storage, other Auth calls) is refused before it leaves."""
    u = httpx.URL(url)
    origin = f"{u.scheme}://{u.host}"
    path, method = u.path, method.upper()
    if origin == SITE and method == "POST" and path == f"/_serverFn/{STAFF_LOGIN_ID}":
        return
    if origin == SUPABASE_URL:
        if method == "GET" and path == "/auth/v1/user":
            return
        if method == "POST" and path == "/auth/v1/token" and u.params.get("grant_type") == "refresh_token" and len(u.params) == 1:
            return
        if method in ("GET", "HEAD") and path.startswith("/rest/v1/"):
            table = path[len("/rest/v1/"):]
            if table in READABLE_TABLES or table in LOOKUP_TABLES:
                return
    raise ReadOnlyViolation(f"Blocked by the read-only guard: {method} {origin}{path}")


def enforce_read_only(request: httpx.Request) -> None:
    check_read_only(request.method, str(request.url))


# ---- staffLogin wire protocol (mirrors data/StaffLogin.kt; see StaffLoginTest) --------

def _sha256(text: str) -> str:
    return hashlib.sha256(text.encode()).hexdigest()


STAFF_LOGIN_ID = _sha256("src/lib/staff.functions.ts--staffLogin_createServerFn_handler")

_ESCAPES = {'"': '\\"', "\\": "\\\\", "\n": "\\n", "\r": "\\r", "\b": "\\b", "\t": "\\t",
            "\f": "\\f", "<": "\\x3C", " ": "\\u2028", " ": "\\u2029"}


def seroval_escape(value: str) -> str:
    return "".join(_ESCAPES.get(ch, ch) for ch in value)


def seroval_unescape(value: str) -> str:
    if "\\" not in value:
        return value
    out, i = [], 0
    simple = {"n": "\n", "r": "\r", "b": "\b", "t": "\t", "f": "\f"}
    while i < len(value):
        ch = value[i]
        if ch != "\\" or i + 1 >= len(value):
            out.append(ch); i += 1; continue
        nxt = value[i + 1]
        if nxt in simple:
            out.append(simple[nxt]); i += 2
        elif nxt == "x" and i + 3 < len(value):
            out.append(chr(int(value[i + 2:i + 4], 16))); i += 4
        elif nxt == "u" and i + 5 < len(value):
            out.append(chr(int(value[i + 2:i + 6], 16))); i += 6
        else:
            out.append(nxt); i += 2
    return "".join(out)


def _string_node(value: str) -> dict:
    return {"t": 1, "s": seroval_escape(value)}


def _object_node(index: int, entries: list[tuple[str, dict]]) -> dict:
    return {"t": 10, "i": index, "p": {"k": [k for k, _ in entries], "v": [v for _, v in entries]}, "o": 0}


def encode_login(username: str, pin: str) -> dict:
    inner = _object_node(1, [("username", _string_node(username)), ("credential", _string_node(pin))])
    return _object_node(0, [("data", inner)])


def login_body(username: str, pin: str) -> str:
    return json.dumps({"t": encode_login(username, pin), "f": 127, "m": []}, separators=(",", ":"), ensure_ascii=False)


class _ServerError:
    def __init__(self, message: str):
        self.message = message


def seroval_decode(node: Any) -> Any:
    if not isinstance(node, dict):
        return None
    kind = node.get("t")
    if kind == 0:
        try:
            return float(node.get("s"))
        except (TypeError, ValueError):
            return None
    if kind == 1:
        return seroval_unescape(node.get("s") or "")
    if kind == 2:
        return {2: True, 3: False}.get(node.get("s"))
    if kind == 9:
        return [seroval_decode(x) for x in node.get("a") or []]
    if kind in (10, 11):
        p = node.get("p") or {}
        keys, values = p.get("k") or [], p.get("v") or []
        return {seroval_unescape(k): (seroval_decode(values[i]) if i < len(values) else None) for i, k in enumerate(keys)}
    if kind in (13, 14):
        return _ServerError(seroval_unescape(node.get("m") or ""))
    return None


def parse_login_reply(status: int, text: str) -> tuple[str, str]:
    first = next((line.strip() for line in text.splitlines() if line.strip()), "")
    start = first.find("{")
    try:
        root = json.loads(first[start:] if start > 0 else first)
    except ValueError:
        root = None
    decoded = seroval_decode(root) if isinstance(root, dict) else None
    error = decoded.get("error") if isinstance(decoded, dict) else decoded
    if isinstance(error, _ServerError):
        raise PureIceError(error.message or "Sign-in failed (wrong username/PIN or too many attempts)")
    result = decoded.get("result") if isinstance(decoded, dict) else None
    access = result.get("access_token") if isinstance(result, dict) else None
    refresh = result.get("refresh_token") if isinstance(result, dict) else None
    if not access or not refresh:
        raise PureIceError(f"Sign-in failed (HTTP {status}): wrong username/PIN, too many attempts, or the login service is unreachable")
    return access, refresh


def normalize_username(value: str) -> str:
    # Arabic-Indic and Persian digits -> ASCII, as core/Calc.kt normalizeDigits does.
    table = {ord(c): str(i) for i, c in enumerate("٠١٢٣٤٥٦٧٨٩")}
    table.update({ord(c): str(i) for i, c in enumerate("۰۱۲۳۴۵۶۷۸۹")})
    return value.translate(table).strip().lower()


def jwt_expiry(token: str) -> float:
    try:
        payload = token.split(".")[1]
        payload += "=" * (-len(payload) % 4)
        return float(json.loads(base64.urlsafe_b64decode(payload))["exp"])
    except Exception:
        return 0.0


# ---- API client -----------------------------------------------------------------------

class Client:
    def __init__(self, username: str, pin: str, http: httpx.Client | None = None):
        self.username = normalize_username(username)
        self.pin = pin.strip()
        self.http = http or httpx.Client(timeout=40)
        # Every request this client sends passes the read-only guard first.
        self.http.event_hooks.setdefault("request", []).append(enforce_read_only)
        self._lock = threading.Lock()
        self._access: str | None = None
        self._refresh: str | None = None
        self._expires = 0.0
        self.user_id: str | None = None

    def _login(self) -> None:
        r = self.http.post(
            f"{SITE}/_serverFn/{STAFF_LOGIN_ID}",
            content=login_body(self.username, self.pin).encode(),
            headers={
                "content-type": "application/json",
                "x-tsr-serverFn": "true",
                "accept": "application/x-tss-framed, application/x-ndjson, application/json",
                "x-pureice-backend": BACKEND_MODE,
                "Origin": SITE,
            },
        )
        self._adopt(*parse_login_reply(r.status_code, r.text))

    def _adopt(self, access: str, refresh: str) -> None:
        self._access, self._refresh, self._expires = access, refresh, jwt_expiry(access)
        user = self._raw("GET", "/auth/v1/user", bearer=access)
        self.user_id = user.get("id")

    def _try_refresh(self) -> bool:
        if not self._refresh:
            return False
        try:
            o = self._raw("POST", "/auth/v1/token?grant_type=refresh_token", json_body={"refresh_token": self._refresh})
        except PureIceError:
            return False
        if not o.get("access_token"):
            return False
        self._access = o["access_token"]
        self._refresh = o.get("refresh_token") or self._refresh
        self._expires = jwt_expiry(self._access)
        return True

    def token(self) -> str:
        with self._lock:
            if self._access and self._expires - time.time() > 60:
                return self._access
            if not self._try_refresh():
                self._login()
            return self._access  # type: ignore[return-value]

    def _raw(self, method: str, path: str, bearer: str | None = None, json_body: Any = None, headers: dict | None = None) -> Any:
        h = {"apikey": PUBLISHABLE_KEY}
        if bearer:
            h["Authorization"] = f"Bearer {bearer}"
        if headers:
            h.update(headers)
        r = self.http.request(method, SUPABASE_URL + path, json=json_body, headers=h)
        if r.status_code >= 400:
            try:
                o = r.json()
                msg = o.get("message") or o.get("msg") or o.get("error_description") or r.text[:300]
            except ValueError:
                msg = r.text[:300]
            raise PureIceError(f"HTTP {r.status_code}: {msg}")
        return r.json() if r.content else None

    def select(self, table: str, query: str) -> list[dict]:
        return self._raw("GET", f"/rest/v1/{table}?{query}", bearer=self.token()) or []

    def select_all(self, table: str, query: str, cap: int = 5000) -> list[dict]:
        rows: list[dict] = []
        while len(rows) < cap:
            page = self.select(table, f"{query}&offset={len(rows)}&limit=500")
            rows.extend(page)
            if len(page) < 500:
                break
        return rows


# ---- helpers --------------------------------------------------------------------------

def enc(v: str) -> str:
    return quote(str(v), safe="")


def num(v: Any) -> float:
    try:
        return float(v or 0)
    except (TypeError, ValueError):
        return 0.0


def aden_today() -> date:
    return datetime.now(ADEN).date()


def parse_day(value: str | None, default: date) -> date:
    if not value:
        return default
    v = value.strip().lower()
    if v in ("today", "اليوم"):
        return aden_today()
    if v in ("yesterday", "أمس", "امس"):
        return aden_today() - timedelta(days=1)
    try:
        return date.fromisoformat(normalize_username(v))
    except ValueError:
        raise PureIceError(f"Dates must be YYYY-MM-DD, 'today' or 'yesterday' (got {value!r})")


def day_range(date_from: str | None, date_to: str | None) -> tuple[str, str, date, date]:
    """created_at bounds for whole business days in Asia/Aden, inclusive of date_to."""
    start = parse_day(date_from, aden_today())
    end = parse_day(date_to, start)
    if end < start:
        start, end = end, start
    lo = datetime.combine(start, datetime.min.time(), ADEN).astimezone(timezone.utc).isoformat()
    hi = datetime.combine(end + timedelta(days=1), datetime.min.time(), ADEN).astimezone(timezone.utc).isoformat()
    return f"created_at=gte.{enc(lo)}", f"created_at=lt.{enc(hi)}", start, end


def aden_time(iso: str | None) -> str | None:
    if not iso:
        return iso
    try:
        return datetime.fromisoformat(iso.replace("Z", "+00:00")).astimezone(ADEN).strftime("%Y-%m-%d %H:%M")
    except ValueError:
        return iso


def by_id(rows: list[dict], label: str) -> dict[str, Any]:
    return {r["id"]: r.get(label) for r in rows if r.get("id")}


def in_list(ids) -> str:
    return ",".join(sorted({str(i) for i in ids if i}))


# ---- server ---------------------------------------------------------------------------

mcp = FastMCP(
    "pureice",
    instructions=(
        "Read-only access to Pure Ice (ثلج نقي), an ice distribution business: trucks go out on "
        "daily trips (campaigns / حملات) with a distributor, sell to customers (sales invoices), "
        "collect debt payments, record expenses, then submit a closing (cash count) that a "
        "supervisor reviews. Amounts are in the company's local currency. Dates are business days "
        "in Asia/Aden (UTC+3). Answer the user in the language they use (usually Arabic). "
        "Prefer the specific tools; use query_table only when none fits."
    ),
)

_client: Client | None = None
_lookup_cache: dict[str, tuple[float, dict]] = {}


def client() -> Client:
    global _client
    if _client is None:
        user, pin = os.environ.get("PUREICE_USERNAME"), os.environ.get("PUREICE_PIN")
        if not user or not pin:
            raise PureIceError("Set PUREICE_USERNAME and PUREICE_PIN for the agent's staff account")
        _client = Client(user, pin)
    return _client


def lookup(name: str) -> dict:
    """Small name maps (trucks, staff, products, regions), cached for 10 minutes."""
    hit = _lookup_cache.get(name)
    if hit and time.time() - hit[0] < 600:
        return hit[1]
    c = client()
    if name == "trucks":
        value = by_id(c.select_all("trucks", "select=id,name,code&order=id"), "name")
    elif name == "staff":
        value = by_id(c.select_all("profiles", "select=id,full_name&order=id"), "full_name")
    elif name == "variants":
        rows = c.select_all("product_variants", "select=id,name,unit,products(name)&order=id")
        value = {r["id"]: " ".join(x for x in [(r.get("products") or {}).get("name"), r.get("name")] if x) for r in rows}
    elif name == "regions":
        value = by_id(c.select_all("regions", "select=id,name&order=id"), "name")
    else:
        raise KeyError(name)
    _lookup_cache[name] = (time.time(), value)
    return value


def customer_names(ids) -> dict:
    ids = in_list(ids)
    if not ids:
        return {}
    return by_id(client().select_all("customers", f"select=id,name&id=in.({ids})&order=id"), "name")


@mcp.tool()
def whoami() -> dict:
    """The staff account this agent is signed in as, and its roles (decides what it can see)."""
    c = client()
    c.token()
    profile = c.select("profiles", f"select=id,full_name,username,truck_id&id=eq.{c.user_id}&limit=1")
    roles = [r.get("role") for r in c.select("user_roles", f"select=role&user_id=eq.{c.user_id}")]
    return {"profile": profile[0] if profile else None, "roles": roles, "today_aden": aden_today().isoformat()}


@mcp.tool()
def sales_summary(date_from: str = "today", date_to: str | None = None, truck: str | None = None) -> dict:
    """Totals for a period: invoices, sales value, cash vs credit, debt collected, expenses,
    bank deposits, broken down by truck. Dates YYYY-MM-DD or 'today'/'yesterday';
    date_to defaults to date_from. `truck` filters by truck name or code (partial match)."""
    c = client()
    lo, hi, start, end = day_range(date_from, date_to)
    trucks = c.select_all("trucks", "select=id,name,code&order=id")
    truck_ids = None
    if truck:
        t = truck.strip().lower()
        truck_ids = {x["id"] for x in trucks if t in (x.get("name") or "").lower() or t in (x.get("code") or "").lower()}
        if not truck_ids:
            return {"error": f"No truck matches {truck!r}", "trucks": [x.get("name") for x in trucks]}
    flt = f"&truck_id=in.({in_list(truck_ids)})" if truck_ids else ""
    sales = c.select_all("sales", f"select=truck_id,total,cash_paid,credit_amount&{lo}&{hi}{flt}&order=created_at,id")
    payments = c.select_all("payments", f"select=truck_id,amount&{lo}&{hi}{flt}&order=created_at,id")
    expenses = c.select_all("expenses", f"select=truck_id,amount,category&{lo}&{hi}{flt}&order=created_at,id")
    try:
        deposits = c.select_all("pureice_deposits", f"select=kind,amount&{lo}&{hi}&order=id")
    except PureIceError:
        deposits = []
    names = {x["id"]: x.get("name") for x in trucks}
    per: dict[str, dict] = {}
    for s in sales:
        p = per.setdefault(names.get(s.get("truck_id"), "?"), {"invoices": 0, "sales": 0.0, "cash": 0.0, "credit": 0.0, "collected": 0.0, "expenses": 0.0})
        p["invoices"] += 1; p["sales"] += num(s.get("total")); p["cash"] += num(s.get("cash_paid")); p["credit"] += num(s.get("credit_amount"))
    for x in payments:
        per.setdefault(names.get(x.get("truck_id"), "?"), {"invoices": 0, "sales": 0.0, "cash": 0.0, "credit": 0.0, "collected": 0.0, "expenses": 0.0})["collected"] += num(x.get("amount"))
    for x in expenses:
        per.setdefault(names.get(x.get("truck_id"), "?"), {"invoices": 0, "sales": 0.0, "cash": 0.0, "credit": 0.0, "collected": 0.0, "expenses": 0.0})["expenses"] += num(x.get("amount"))
    dep: dict[str, float] = {}
    for d in deposits:
        dep[d.get("kind") or "?"] = dep.get(d.get("kind") or "?", 0.0) + num(d.get("amount"))
    return {
        "period": {"from": start.isoformat(), "to": end.isoformat(), "timezone": "Asia/Aden"},
        "invoices": len(sales),
        "sales_total": sum(num(s.get("total")) for s in sales),
        "cash_sales": sum(num(s.get("cash_paid")) for s in sales),
        "credit_sales": sum(num(s.get("credit_amount")) for s in sales),
        "debt_collected": sum(num(x.get("amount")) for x in payments),
        "expenses": sum(num(x.get("amount")) for x in expenses),
        "deposits_by_kind": dep,
        "by_truck": per,
    }


@mcp.tool()
def list_sales(date_from: str = "today", date_to: str | None = None, customer_id: str | None = None,
               trip_id: str | None = None, limit: int = 50) -> list[dict]:
    """Sale invoices in a period (newest first) with customer, truck and seller names.
    Optionally filter by customer_id or trip_id (from other tools). limit max 200."""
    c = client()
    lo, hi, _, _ = day_range(date_from, date_to)
    q = f"select=id,invoice_no,created_at,customer_id,truck_id,trip_id,total,cash_paid,credit_amount,notes,created_by&{lo}&{hi}"
    if customer_id:
        q += f"&customer_id=eq.{enc(customer_id)}"
    if trip_id:
        q += f"&trip_id=eq.{enc(trip_id)}"
    rows = c.select("sales", q + f"&order=created_at.desc&limit={max(1, min(limit, 200))}")
    cust, trucks, staff = customer_names(r.get("customer_id") for r in rows), lookup("trucks"), lookup("staff")
    return [{
        "id": r["id"], "invoice_no": r.get("invoice_no"), "time": aden_time(r.get("created_at")),
        "customer": cust.get(r.get("customer_id")), "customer_id": r.get("customer_id"),
        "truck": trucks.get(r.get("truck_id")), "seller": staff.get(r.get("created_by")), "trip_id": r.get("trip_id"),
        "total": num(r.get("total")), "cash": num(r.get("cash_paid")), "credit": num(r.get("credit_amount")), "notes": r.get("notes"),
    } for r in rows]


@mcp.tool()
def get_invoice(invoice_no: str | None = None, sale_id: str | None = None) -> dict:
    """One sale invoice with its line items, by invoice number or sale id."""
    if not invoice_no and not sale_id:
        raise PureIceError("Give invoice_no or sale_id")
    c = client()
    f = f"id=eq.{enc(sale_id)}" if sale_id else f"invoice_no=eq.{enc(invoice_no)}"
    rows = c.select("sales", f"select=*,sale_items(*)&{f}&limit=1")
    if not rows:
        return {"error": "Invoice not found (or not visible to this account)"}
    s = rows[0]
    variants = lookup("variants")
    items = [{"product": variants.get(i.get("variant_id")), "qty": num(i.get("qty")), "unit_price": num(i.get("unit_price")),
              "line_total": num(i.get("line_total"))} for i in s.pop("sale_items", []) or []]
    changes = c.select("pureice_invoice_changes", f"select=kind,reason,status,created_at,review_note&sale_id=eq.{s['id']}&order=created_at.desc")
    return {**s, "time": aden_time(s.get("created_at")), "customer": customer_names([s.get("customer_id")]).get(s.get("customer_id")),
            "truck": lookup("trucks").get(s.get("truck_id")), "seller": lookup("staff").get(s.get("created_by")),
            "items": items, "change_requests": changes}


@mcp.tool()
def find_customers(query: str, limit: int = 20) -> list[dict]:
    """Search customers by part of the name or phone number. Returns ids, region, balance."""
    c = client()
    q = normalize_username(query).replace("*", "").replace(",", " ").replace("(", " ").replace(")", " ")
    like = enc(f"*{q}*")
    rows = c.select("customers", f"select=id,name,phone,region_id,truck_id,credit_limit,active,location_notes"
                                 f"&or=(name.ilike.{like},phone.ilike.{like})&order=name&limit={max(1, min(limit, 100))}")
    bal = {}
    if rows:
        bal = {b["customer_id"]: num(b.get("balance")) for b in c.select("customer_balances", f"select=customer_id,balance&customer_id=in.({in_list(r['id'] for r in rows)})")}
    regions, trucks = lookup("regions"), lookup("trucks")
    return [{**r, "region": regions.get(r.get("region_id")), "truck": trucks.get(r.get("truck_id")), "balance": bal.get(r["id"], 0.0)} for r in rows]


@mcp.tool()
def customer_debts(min_balance: float = 0.01, region: str | None = None, limit: int = 30) -> dict:
    """Customers who owe money, largest debt first, with credit limit and whether it is exceeded.
    `region` filters by region name (partial match)."""
    c = client()
    balances = [b for b in c.select_all("customer_balances", "select=customer_id,balance&order=customer_id") if num(b.get("balance")) >= min_balance]
    balances.sort(key=lambda b: -num(b.get("balance")))
    custs = {}
    for chunk in range(0, len(balances), 150):
        ids = in_list(b["customer_id"] for b in balances[chunk:chunk + 150])
        for r in c.select_all("customers", f"select=id,name,phone,region_id,credit_limit&id=in.({ids})&order=id"):
            custs[r["id"]] = r
    regions = lookup("regions")
    out = []
    for b in balances:
        r = custs.get(b["customer_id"], {})
        reg = regions.get(r.get("region_id"))
        if region and region.strip().lower() not in (reg or "").lower():
            continue
        limit_v = num(r.get("credit_limit"))
        out.append({"customer_id": b["customer_id"], "name": r.get("name"), "phone": r.get("phone"), "region": reg,
                    "balance": num(b.get("balance")), "credit_limit": limit_v or None,
                    "over_limit": bool(limit_v) and num(b.get("balance")) > limit_v})
    return {"customers_with_debt": len(out), "total_debt": sum(x["balance"] for x in out), "top": out[:max(1, min(limit, 200))]}


@mcp.tool()
def customer_statement(customer_id: str) -> dict:
    """A customer's profile, current balance, last 30 invoices, last 30 payments and freezers."""
    c = client()
    cid = enc(customer_id)
    rows = c.select("customers", f"select=*&id=eq.{cid}&limit=1")
    if not rows:
        return {"error": "Customer not found (or not visible to this account)"}
    bal = c.select("customer_balances", f"select=balance&customer_id=eq.{cid}")
    sales = c.select("sales", f"select=invoice_no,created_at,total,cash_paid,credit_amount&customer_id=eq.{cid}&order=created_at.desc&limit=30")
    pays = c.select("payments", f"select=amount,created_at,notes&customer_id=eq.{cid}&order=created_at.desc&limit=30")
    freezers = c.select("freezers", f"select=serial,status,condition,freezer_models(name)&customer_id=eq.{cid}")
    prices = c.select("customer_prices", f"select=variant_id,price&customer_id=eq.{cid}")
    variants = lookup("variants")
    for s in sales:
        s["created_at"] = aden_time(s.get("created_at"))
    for p in pays:
        p["created_at"] = aden_time(p.get("created_at"))
    return {"customer": {**rows[0], "region": lookup("regions").get(rows[0].get("region_id"))},
            "balance": num(bal[0].get("balance")) if bal else 0.0, "recent_invoices": sales, "recent_payments": pays,
            "freezers": freezers, "special_prices": [{"product": variants.get(p.get("variant_id")), "price": num(p.get("price"))} for p in prices]}


@mcp.tool()
def list_trips(status: str = "open", date_from: str | None = None, date_to: str | None = None, limit: int = 30) -> list[dict]:
    """Trips (campaigns / حملات): status 'open' (not closed), 'closed' or 'all'; optional trip_date range.
    Each row has truck, distributor, whether loading was approved, and sales so far."""
    c = client()
    q = "select=id,truck_id,distributor_id,trip_date,status,loading_approved_at,created_at,closed_at"
    if status == "open":
        q += "&status=neq.closed"
    elif status == "closed":
        q += "&status=eq.closed"
    if date_from:
        q += f"&trip_date=gte.{parse_day(date_from, aden_today()).isoformat()}"
    if date_to or date_from:
        q += f"&trip_date=lte.{parse_day(date_to or date_from, aden_today()).isoformat()}"
    trips = c.select("trips", q + f"&order=trip_date.desc,created_at.desc&limit={max(1, min(limit, 200))}")
    totals: dict[str, list[float]] = {}
    if trips:
        for s in c.select_all("sales", f"select=trip_id,total&trip_id=in.({in_list(t['id'] for t in trips)})&order=id"):
            t = totals.setdefault(s["trip_id"], [0, 0.0]); t[0] += 1; t[1] += num(s.get("total"))
    trucks, staff = lookup("trucks"), lookup("staff")
    return [{"trip_id": t["id"], "date": t.get("trip_date"), "status": t.get("status"), "truck": trucks.get(t.get("truck_id")),
             "distributor": staff.get(t.get("distributor_id")), "loading_approved": bool(t.get("loading_approved_at")),
             "invoices": totals.get(t["id"], [0, 0])[0], "sales_total": totals.get(t["id"], [0, 0.0])[1],
             "closed_at": aden_time(t.get("closed_at"))} for t in trips]


@mcp.tool()
def trip_details(trip_id: str) -> dict:
    """Everything about one trip: stock per product (loaded/sold/returned/damaged), loads,
    sales totals, debt collected, expenses and the closing (expected vs counted cash)."""
    c = client()
    tid = enc(trip_id)
    trips = c.select("trips", f"select=*&id=eq.{tid}&limit=1")
    if not trips:
        return {"error": "Trip not found (or not visible to this account)"}
    variants, trucks, staff = lookup("variants"), lookup("trucks"), lookup("staff")
    stock = c.select("trip_stock", f"select=*&trip_id=eq.{tid}")
    loads = c.select("trip_loads", f"select=variant_id,qty,kind,created_at,approved_at&trip_id=eq.{tid}&order=created_at")
    sales = c.select_all("sales", f"select=total,cash_paid,credit_amount&trip_id=eq.{tid}&order=id")
    pays = c.select("payments", f"select=amount&trip_id=eq.{tid}")
    exps = c.select("expenses", f"select=category,amount,notes,created_at&trip_id=eq.{tid}")
    closing = c.select("closings", f"select=expected_cash,counted_cash,difference,status,notes,review_note,created_at,reviewed_at&trip_id=eq.{tid}&limit=1")
    t = trips[0]
    return {
        "trip": {**t, "truck": trucks.get(t.get("truck_id")), "distributor": staff.get(t.get("distributor_id"))},
        "stock": [{**s, "product": variants.get(s.get("variant_id"))} for s in stock],
        "loads": [{**l, "product": variants.get(l.get("variant_id")), "approved": bool(l.get("approved_at"))} for l in loads],
        "invoices": len(sales), "sales_total": sum(num(s.get("total")) for s in sales),
        "cash_sales": sum(num(s.get("cash_paid")) for s in sales), "credit_sales": sum(num(s.get("credit_amount")) for s in sales),
        "debt_collected": sum(num(p.get("amount")) for p in pays),
        "expenses": exps, "expenses_total": sum(num(e.get("amount")) for e in exps),
        "closing": closing[0] if closing else None,
    }


@mcp.tool()
def pending_reviews() -> dict:
    """What is waiting for a supervisor: submitted closings, invoice change/cancel requests,
    and truck loads not yet approved."""
    c = client()
    trucks, staff = lookup("trucks"), lookup("staff")
    closings = c.select("closings", "select=trip_id,expected_cash,counted_cash,difference,created_at,created_by,trips(trip_date,truck_id)&status=eq.submitted&order=created_at")
    changes = c.select("pureice_invoice_changes", "select=invoice_no,kind,reason,created_at,requested_by&status=eq.pending&order=created_at")
    loads = c.select("trip_loads", "select=trip_id,qty,created_at&approved_at=is.null&order=created_at&limit=200")
    return {
        "closings_to_review": [{"trip_id": x.get("trip_id"), "date": (x.get("trips") or {}).get("trip_date"),
                                "truck": trucks.get((x.get("trips") or {}).get("truck_id")), "by": staff.get(x.get("created_by")),
                                "expected_cash": num(x.get("expected_cash")), "counted_cash": num(x.get("counted_cash")),
                                "difference": num(x.get("difference"))} for x in closings],
        "invoice_change_requests": [{**x, "requested_by": staff.get(x.get("requested_by")), "created_at": aden_time(x.get("created_at"))} for x in changes],
        "unapproved_load_lines": len(loads), "trips_with_unapproved_loads": sorted({x.get("trip_id") for x in loads}),
    }


@mcp.tool()
def products_and_prices() -> list[dict]:
    """Active products with their sizes/variants and standard prices."""
    rows = client().select_all("product_variants", "select=id,name,unit,price,active,products(name)&active=eq.true&order=sort,id")
    return [{"variant_id": r["id"], "product": (r.get("products") or {}).get("name"), "variant": r.get("name"),
             "unit": r.get("unit"), "price": num(r.get("price"))} for r in rows]


@mcp.tool()
def freezers(status: str | None = None) -> dict:
    """Freezers (ثلاجات) with model, status and the customer holding them; optional status filter."""
    c = client()
    q = "select=serial,status,condition,notes,customer_id,freezer_models(name)&order=serial"
    if status:
        q += f"&status=eq.{enc(status)}"
    rows = c.select_all("freezers", q)
    names = customer_names(r.get("customer_id") for r in rows)
    counts: dict[str, int] = {}
    for r in rows:
        counts[r.get("status") or "?"] = counts.get(r.get("status") or "?", 0) + 1
        r["customer"] = names.get(r.get("customer_id"))
        r["model"] = (r.pop("freezer_models", None) or {}).get("name")
    return {"count_by_status": counts, "freezers": rows}


@mcp.tool()
def query_table(table: str, select: str = "*", filters: str = "", order: str = "", limit: int = 50) -> list[dict]:
    """Advanced read of any business table with PostgREST syntax, for questions the other tools
    don't cover. Example: table='expenses', select='category,amount,created_at',
    filters='category=eq.fuel&created_at=gte.2026-11-01', order='created_at.desc'.
    Tables: customers, customer_balances, customer_prices, sales, sale_items, payments, trips,
    trip_loads, trip_stock, closings, expenses, maintenance_jobs, trucks, regions, products,
    product_variants, freezers, freezer_models, freezer_events, profiles, user_roles,
    pureice_deposits, pureice_invoice_changes, pureice_company_settings, gps_points.
    Times are stored in UTC. limit max 500."""
    if table not in READABLE_TABLES:
        raise PureIceError(f"Table {table!r} is not readable here. Allowed: {', '.join(sorted(READABLE_TABLES))}")
    if re.search(r"(^|&)(limit|offset)=", filters):
        raise PureIceError("Use the limit argument instead of limit/offset in filters")
    q = f"select={enc(select).replace('%2C', ',').replace('%28', '(').replace('%29', ')').replace('%2A', '*')}"
    if filters:
        q += "&" + filters.lstrip("&?")
    if order:
        q += f"&order={order}"
    return client().select(table, q + f"&limit={max(1, min(limit, 500))}")


def main() -> None:
    if "--http" in sys.argv:
        host = os.environ.get("PUREICE_MCP_HOST", "127.0.0.1")
        port = int(os.environ.get("PUREICE_MCP_PORT", "8765"))
        if _SDK2:
            mcp.run(transport="streamable-http", host=host, port=port)
        else:
            mcp.settings.host, mcp.settings.port = host, port
            mcp.run(transport="streamable-http")
    else:
        mcp.run()


if __name__ == "__main__":
    main()
