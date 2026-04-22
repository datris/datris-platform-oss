# Datris Market Intelligence Agent

A real-time financial data pipeline agent powered by the [Datris](https://datris.ai) data platform.
**100% Python** — FastAPI backend, vanilla JS frontend, no Node.js required.

The agent acts as a pipeline orchestrator: it connects to your Datris MCP server,
provisions four platform-owned **taps** (FRED, equities, crypto, SEC filings),
triggers them to refresh data, and answers market questions grounded in actual
numbers — all visible live in the browser. The Datris platform itself does the
fetching; the agent just decides when and what to refresh.

This example lives in the [datris-platform-oss](https://github.com/datris/datris-platform-oss) repo under `examples/market-macro-agent`.

## Quick start

```bash
# 1. Clone the platform repo (includes this example)
git clone https://github.com/datris/datris-platform-oss.git
cd datris-platform-oss

# 2. Start the Datris platform
docker compose up -d

# 3. Switch to the example directory
cd examples/market-macro-agent

# 4. Create and activate a virtual environment
python -m venv .venv
source .venv/bin/activate      # Windows: .venv\Scripts\activate

# 5. Install dependencies
pip install -r requirements.txt

# 6. Set your API keys
cp .env.example .env
#    → edit .env and paste your ANTHROPIC_API_KEY
#    → add FRED_API_KEY for macro data

# 7. Run the agent
uvicorn main:app --reload --port 8001
```

Open **http://localhost:8001** — no build step, no npm.

## Project structure

```
examples/market-macro-agent/
├── main.py                  # FastAPI app + browser UI (served as HTML)
├── requirements.txt
├── .env.example
│
└── agent/
    ├── __init__.py
    ├── config.py            # Mission prompt, MCP tool allowlist, suggestions, colours
    ├── mcp_client.py        # MCP SSE client — connect, discover tools + resources, call tools
    ├── tap_definitions.py   # The 4 tap scripts (BYO Python) pushed to the platform on startup
    ├── tap_provisioning.py  # Idempotent startup routine: create_tap_secret + create_tap
    ├── pipeline_store.py    # Thread-safe registry + SSE broadcast
    ├── executor.py          # Routes tool calls to MCP server, reflects tap activity in UI
    ├── loop.py              # Agentic loop — yields SSE events
    └── scheduler.py         # Background refresh — calls run_tap on an interval
```

## Architecture

```
Browser (vanilla JS)
  │
  ├── GET  /stream/state  (SSE, persistent)
  │         Pipeline tile updates, activity feed, row counts
  │
  └── POST /chat          (SSE, per-message)
            tool_start / tool_end / partial_text / answer / error
              │
              └── agent/loop.py
                    │
                    ├── Anthropic API (Claude)
                    │     ↕ tool_use / end_turn
                    │
                    ├── agent/executor.py
                    │     └── MCP tools → Datris MCP Server (port 3000)
                    │           (run_tap, get_pipeline_status, query_postgres,
                    │            list_taps, get_tap_logs, …)
                    │
                    └── agent/scheduler.py → run_tap every N minutes

              ┌──────────────────────────────────────┐
              │ Datris Platform                      │
              │                                      │
              │  Tap runner (Docker sandbox)         │
              │    fred_tap → FRED API               │
              │    equities_tap → yfinance           │
              │    crypto_tap → CoinGecko            │
              │    sec_tap → SEC EDGAR               │
              │           │                          │
              │           ▼                          │
              │   Pipelines (Postgres destinations)  │
              └──────────────────────────────────────┘
```

## How it works

1. **On startup**:
   - Connects to the Datris MCP server via SSE.
   - Pushes `FRED_API_KEY` from `.env` into Datris as a tap secret (`create_tap_secret`).
     The tap script reads it as an env var at runtime — the agent never keeps secrets
     around after handoff.
   - Provisions four taps (`fred_tap`, `equities_tap`, `crypto_tap`, `sec_tap`), each
     wired to its own pipeline. Idempotent: re-running against an existing install
     is a no-op.
   - Taps are created with scheduling disabled — the agent's internal loop drives
     refreshes so we don't double-fire.
2. **User asks a question**: the agent decides which data family is relevant.
3. **Refresh**: the agent calls `run_tap`. The platform runs the Python `fetch()`
   inside a Docker sandbox and submits records into the pipeline. The response
   carries a `publisherToken` and `persisted: true` on success.
4. **Wait for ingestion**: ingestion is async. The agent polls `get_pipeline_status`
   with the `publisherToken` until every row's `state` is `end` or `error`. This
   rule — plus `persisted` / `persistedReason` handling — comes from the MCP
   server's `instructions` payload, which the agent loads at connect time and
   feeds into the LLM's system prompt.
5. **Query**: the agent queries the pipeline via `query_postgres` and answers
   grounded in actual numbers.
6. **Background refresh**: every `REFRESH_INTERVAL_MINUTES` the scheduler triggers
   `run_tap` for every pipeline that's been exercised at least once.
7. **Tap health**: ask about failures and the agent will call `get_tap_logs` to
   report the last runs and any errors.

## Free data sources

| Source      | What it provides                          | Auth         |
|-------------|-------------------------------------------|--------------|
| FRED        | Macro series — yields, VIX, CPI, spreads  | [Free API key](https://fred.stlouisfed.org/docs/api/api_key.html) |
| yfinance    | Equity / ETF OHLCV                        | None         |
| CoinGecko   | Crypto prices, market cap                 | None (30/min)|
| SEC EDGAR   | 10-K / 10-Q filings                       | None (10/s)  |

## Suggested demo queries

1. `"What's the current macro picture?"` — runs the FRED + equities taps, then queries
2. `"Refresh all taps"` — triggers `run_tap` across all four data families
3. `"Is crypto confirming the risk-on trade in equities?"` — cross-pipeline query
4. `"Show me the last few tap runs"` — exercises `get_tap_logs` live

## Environment variables

| Variable                   | Default                        | Description              |
|----------------------------|--------------------------------|--------------------------|
| `ANTHROPIC_API_KEY`        | —                              | **Required**             |
| `MODEL`                    | `claude-sonnet-4-6`            | Claude model             |
| `PORT`                     | `8001`                         | uvicorn port             |
| `MCP_SERVER_URL`           | `http://localhost:3000/sse`    | Datris MCP server SSE    |
| `FRED_API_KEY`             | —                              | [Get one free](https://fred.stlouisfed.org/docs/api/api_key.html) |
| `REFRESH_INTERVAL_MINUTES` | `15`                           | Background refresh cycle |

## Requirements

- Python 3.11+
- [Datris Platform](https://github.com/datris/datris-platform-oss) running via Docker
- Anthropic API key
