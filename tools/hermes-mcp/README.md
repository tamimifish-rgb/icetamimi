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
- **Read-only.** None of the tools can create, change or delete records. The `query_table`
  tool can read only the business tables on its allowlist; it cannot reach auth or login
  tables.

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
   Add an employee such as "AI Agent" with username `ai_agent` and the *supervisor* role,
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
PUREICE_USERNAME=ai_agent PUREICE_PIN=123456 .venv/bin/python pureice_mcp.py --http
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

## Adding write actions later

Write actions (recording a payment, approving a closing, and so on) are left out on purpose,
because an agent misreading a request would create real financial records. If you want
them, add them one at a time. Each should call the same server function the app uses
(`save_invoice`, `review_closing`, ...) with a client-generated UUID so retries stay
idempotent, and Hermes should be set to ask for your confirmation before it calls them.
