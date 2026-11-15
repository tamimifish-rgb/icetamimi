# Pure Ice API for AI agents (Hermes Agent and other MCP clients)

`pureice_mcp.py` is a small MCP server. It lets an AI agent such as
[Hermes Agent](https://hermes-agent.nousresearch.com) answer questions about Pure Ice data,
for example "كم مبيعات اليوم؟", "who owes the most?" or "which closings are waiting for review?".

It uses the same API as the Android app:

- **Sign-in.** The agent signs in with a staff **username + 6-digit PIN** through the hosted
  `staffLogin` function, exactly like the phone does. It then uses ordinary Supabase session
  tokens and refreshes them by itself. No service key is needed and none is stored.
- **Permissions.** The database's row-level security decides what the agent can see, based
  on the role of the account you give it. A *supervisor* account can read everything
  managers need. A *distributor* account sees only that distributor's own data.
- **Read-only, enforced in two places.** No tool writes anything. On top of that, every
  request the server sends passes a guard (`check_read_only`) that lets through only:
  `GET`/`HEAD` on the allowlisted business tables, the sign-in call and the token refresh.
  Inserts, updates, deletes, server functions (`/rpc/...`, such as `save_invoice` or
  `review_closing`), file storage, PIN or password changes, and any other host are refused
  before anything leaves the machine. The tests check each of these.

## Tools

| Tool | What it answers |
| --- | --- |
| `whoami` | Which account the agent is signed in as, and its roles |
| `sales_summary` | Sales, cash vs credit, debt collected, expenses and deposits for a day or a date range, per truck |
| `list_sales` / `get_invoice` | Invoices in a period, and one invoice with its line items and change requests |
| `find_customers` / `customer_statement` | Customer search by name or phone; balance, recent invoices and payments, freezers and special prices |
| `customer_debts` | Who owes money, largest first, and who is over their credit limit |
| `list_trips` / `trip_details` | Open or closed trips (campaigns): stock, loads, sales, expenses and the cash closing |
| `pending_reviews` | Closings, invoice change requests and loads waiting for a supervisor |
| `products_and_prices`, `freezers` | Price list, and freezers by status and holder |
| `query_table` | Advanced read-only PostgREST query, for anything the other tools don't cover |

Dates are business days in Asia/Aden (UTC+3), the same as the app. Each date can be
`YYYY-MM-DD`, `today` or `yesterday`.

## Setup

1. **Create a staff account for the agent.** In the app go to Administrator → Employees.
   Add an employee named **سليم** with username `saleem` and the *supervisor* role,
   then set a PIN. Using a separate account keeps the agent's sign-ins visible and lets you
   revoke its access without affecting anyone else.

2. **Install the server** on the machine where Hermes runs (Python 3.10 or newer):

   ```sh
   mkdir -p ~/pureice-mcp && cp pureice_mcp.py requirements.txt ~/pureice-mcp/
   cd ~/pureice-mcp
   python3 -m venv .venv && .venv/bin/pip install -r requirements.txt
   ```

3. **Connect it to Hermes.** Add the block from `hermes-config.example.yaml` to
   `~/.hermes/config.yaml`, replacing the paths, username and PIN with your own. Then run
   `/reload-mcp` in a Hermes chat, or restart Hermes. Hermes starts the server as a
   subprocess and passes the credentials through `env`.

4. **Test it.** Ask Hermes: *"Use pureice whoami, then give me today's sales summary."*

### Running it as a URL instead (optional)

```sh
PUREICE_USERNAME=saleem PUREICE_PIN=123456 .venv/bin/python pureice_mcp.py --http
# serves http://127.0.0.1:8765/mcp
```

In Hermes, use `url: "http://127.0.0.1:8765/mcp"` in place of `command`/`args`. The HTTP
endpoint itself has **no password**: anyone who can reach it can read the data as the
agent's account. Keep it on `127.0.0.1`, or put it behind an authenticating proxy, never
directly on the internet. `PUREICE_MCP_HOST` and `PUREICE_MCP_PORT` change the address.

## Tests

```sh
pip install -r requirements.txt
python test_pureice_mcp.py
```

The sign-in tests use the same byte-for-byte vectors as the app's `StaffLoginTest.kt`. If
the web project renames `staffLogin` or moves it out of `src/lib/staff.functions.ts`, update
both places.

## Keeping the account itself read-only

The guard makes *this server* read-only. The PIN in the Hermes config could still be used
in the app by anyone who has it, with the full powers of its role. So:

- keep `~/.hermes/config.yaml` private (`chmod 600`) and use a PIN no person uses;
- don't give the agent's account a truck, so it can never be the distributor on a trip;
- if the PIN may have leaked, change it or deactivate the account in Administrator →
  Employees. The agent stops working at once, and nothing else is affected.
