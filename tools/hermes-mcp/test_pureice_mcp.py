"""Run: python -m pytest tools/hermes-mcp  (or python tools/hermes-mcp/test_pureice_mcp.py)"""
import asyncio
import base64
import json
import os
import sys
import time

import httpx

sys.path.insert(0, os.path.dirname(__file__))
import pureice_mcp as p  # noqa: E402


# Same vectors as app/src/test/java/com/tamimi/naqiice/StaffLoginTest.kt
def test_function_id_matches_published_site():
    assert p.STAFF_LOGIN_ID == "022a1df5d42d28cd61c85b21d129ca8c6424eba99733d6723f70c295b64c6d77"


def test_request_matches_web_serializer():
    assert json.dumps(p.encode_login("ali", "123456"), separators=(",", ":")) == (
        '{"t":10,"i":0,"p":{"k":["data"],"v":[{"t":10,"i":1,"p":{"k":["username","credential"],'
        '"v":[{"t":1,"s":"ali"},{"t":1,"s":"123456"}]},"o":0}]},"o":0}')


def test_body_matches_web_client():
    assert p.login_body("ali", '12<3"456') == (
        '{"t":{"t":10,"i":0,"p":{"k":["data"],"v":[{"t":10,"i":1,"p":{"k":["username","credential"],'
        '"v":[{"t":1,"s":"ali"},{"t":1,"s":"12\\\\x3C3\\\\\\"456"}]},"o":0}]},"o":0},"f":127,"m":[]}')


def test_escapes():
    assert p.seroval_escape('a"b\\c<d') == 'a\\"b\\\\c\\x3Cd'
    assert p.seroval_unescape('a\\"b\\\\c\\x3Cd\\n') == 'a"b\\c<d\n'


def test_reads_tokens_and_errors():
    ok = '{"t":10,"i":0,"p":{"k":["result","error","context"],"v":[{"t":10,"i":1,"p":{"k":["access_token","refresh_token"],"v":[{"t":1,"s":"A.B.C"},{"t":1,"s":"R"}]},"o":0},{"t":2,"s":1},{"t":2,"s":1}]},"o":0}'
    assert p.parse_login_reply(200, ok) == ("A.B.C", "R")
    bad = '{"t":10,"i":0,"p":{"k":["result","error","context"],"v":[{"t":2,"s":1},{"t":13,"i":1,"s":0,"m":"بيانات الدخول غير صحيحة","p":{"k":["stack"],"v":[{"t":1,"s":"Error"}]}},{"t":2,"s":1}]},"o":0}'
    for status, text in [(500, bad), (403, "Forbidden"), (200, "<html></html>"), (200, "")]:
        try:
            p.parse_login_reply(status, text)
            raise AssertionError("accepted an unreadable reply")
        except p.PureIceError:
            pass


def test_username_digits_normalized():
    assert p.normalize_username(" Ali٣۴ ") == "ali34"


def test_day_range_uses_aden_midnight():
    lo, hi, _, _ = p.day_range("2026-11-15", None)
    assert lo == "created_at=gte.2026-11-14T21%3A00%3A00%2B00%3A00"
    assert hi == "created_at=lt.2026-11-15T21%3A00%3A00%2B00%3A00"


# ---- end-to-end against a fake backend ------------------------------------------------

def _jwt(exp):
    payload = base64.urlsafe_b64encode(json.dumps({"exp": exp}).encode()).decode().rstrip("=")
    return f"h.{payload}.s"


def _fake_backend(calls):
    access = _jwt(time.time() + 3600)
    login_reply = json.dumps({"t": 10, "i": 0, "p": {"k": ["result", "error", "context"], "v": [
        {"t": 10, "i": 1, "p": {"k": ["access_token", "refresh_token"], "v": [{"t": 1, "s": access}, {"t": 1, "s": "R"}]}, "o": 0},
        {"t": 2, "s": 1}, {"t": 2, "s": 1}]}, "o": 0})
    tables = {
        "trucks": [{"id": "t1", "name": "شاحنة 1", "code": "T1"}],
        "sales": [{"truck_id": "t1", "total": 100, "cash_paid": 60, "credit_amount": 40}],
        "payments": [{"truck_id": "t1", "amount": 25}],
        "expenses": [{"truck_id": "t1", "amount": 10, "category": "fuel"}],
        "pureice_deposits": [],
    }

    def handler(request: httpx.Request):
        calls.append(request)
        if "/_serverFn/" in request.url.path:
            assert request.headers["Origin"] == p.SITE
            assert json.loads(request.content)["t"]["p"]["v"][0]["p"]["v"][0]["s"] == "agent"
            return httpx.Response(200, text=login_reply)
        if request.url.path == "/auth/v1/user":
            assert request.headers["Authorization"] == f"Bearer {access}"
            return httpx.Response(200, json={"id": "u1"})
        assert request.method == "GET", "the MCP server must never write"
        assert request.headers["apikey"] == p.PUBLISHABLE_KEY
        table = request.url.path.rsplit("/", 1)[-1]
        return httpx.Response(200, json=tables.get(table, []))

    return httpx.Client(transport=httpx.MockTransport(handler))


def test_sales_summary_end_to_end():
    calls = []
    p._client = p.Client("Agent", "123456", http=_fake_backend(calls))
    p._lookup_cache.clear()
    out = p.sales_summary("2026-11-15")
    assert out["sales_total"] == 100 and out["cash_sales"] == 60 and out["credit_sales"] == 40
    assert out["debt_collected"] == 25 and out["expenses"] == 10
    assert out["by_truck"]["شاحنة 1"]["invoices"] == 1
    logins = [c for c in calls if "/_serverFn/" in c.url.path]
    assert len(logins) == 1, "signs in once and reuses the token"


def test_query_table_rejects_unlisted_tables():
    try:
        p.query_table("staff_login_attempts")
        raise AssertionError("read a blocked table")
    except p.PureIceError:
        pass


def test_guard_allows_only_reads_and_sign_in():
    sb, site = p.SUPABASE_URL, p.SITE
    allowed = [
        ("GET", f"{sb}/rest/v1/sales?select=*"),
        ("HEAD", f"{sb}/rest/v1/customers?select=id"),
        ("GET", f"{sb}/auth/v1/user"),
        ("POST", f"{sb}/auth/v1/token?grant_type=refresh_token"),
        ("POST", f"{site}/_serverFn/{p.STAFF_LOGIN_ID}"),
    ]
    blocked = [
        ("POST", f"{sb}/rest/v1/sales"),                      # insert
        ("PATCH", f"{sb}/rest/v1/customers?id=eq.1"),         # update
        ("DELETE", f"{sb}/rest/v1/payments?id=eq.1"),         # delete
        ("PUT", f"{sb}/rest/v1/trips"),
        ("POST", f"{sb}/rest/v1/rpc/save_invoice"),           # server functions
        ("GET", f"{sb}/rest/v1/rpc/review_closing?p_id=1"),
        ("GET", f"{sb}/rest/v1/staff_login_attempts"),        # not allowlisted
        ("POST", f"{sb}/storage/v1/object/pureice-receipts/x"),
        ("POST", f"{sb}/auth/v1/logout"),
        ("PUT", f"{sb}/auth/v1/user"),                        # change password/email
        ("POST", f"{sb}/auth/v1/token?grant_type=password"),
        ("POST", f"{site}/_serverFn/{p._sha256('src/lib/staff.functions.ts--setStaffPin_createServerFn_handler')}"),
        ("GET", "https://example.com/rest/v1/sales"),
    ]
    for m, u in allowed:
        p.check_read_only(m, u)
    for m, u in blocked:
        try:
            p.check_read_only(m, u)
            raise AssertionError(f"guard let through {m} {u}")
        except p.ReadOnlyViolation:
            pass


def test_guard_runs_on_every_request():
    sent = []
    http = httpx.Client(transport=httpx.MockTransport(lambda r: sent.append(r) or httpx.Response(201)))
    c = p.Client("agent", "123456", http=http)
    try:
        c.http.post(f"{p.SUPABASE_URL}/rest/v1/sales", json={"total": 1})
        raise AssertionError("write reached the network")
    except p.ReadOnlyViolation:
        pass
    assert sent == [], "nothing may be sent"
    assert not hasattr(c, "rpc")


def test_tools_are_registered():
    names = {t.name for t in asyncio.run(p.mcp.list_tools())}
    assert {"whoami", "sales_summary", "list_sales", "get_invoice", "find_customers", "customer_debts",
            "customer_statement", "list_trips", "trip_details", "pending_reviews", "products_and_prices",
            "freezers", "query_table"} <= names


if __name__ == "__main__":
    for name, fn in list(globals().items()):
        if name.startswith("test_"):
            fn(); print("ok", name)
