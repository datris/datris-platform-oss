# Datris Market Intelligence Agent

A real-time financial data pipeline agent powered by the [Datris](https://datris.ai) data platform.
**100% Python** — FastAPI backend, vanilla JS frontend, no Node.js required.

Claude acts as an agentic pipeline operator: it connects to your Datris MCP server,
discovers available tools and resources, fetches live market data from public APIs,
creates pipelines, ingests data, and answers market questions grounded in actual
numbers — all visible live in the browser.

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
#    → optionally add FRED_API_KEY for macro data

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
    ├── config.py            # Mission prompt, tool allowlist, suggestions, colours
    ├── mcp_client.py        # MCP SSE client — connect, discover tools + resources, call tools
    ├── data_fetcher.py      # Live data from FRED, yfinance, CoinGecko, SEC EDGAR
    ├── pipeline_store.py    # Thread-safe registry + SSE broadcast
    ├── executor.py          # Routes tool calls to MCP server + data fetcher
    ├── loop.py              # Agentic loop — yields SSE events
    └── scheduler.py         # Background refresh scheduler
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
                    │     ├── MCP tools → Datris MCP Server (port 3000)
                    │     └── ingest_data → data_fetcher.py (cached server-side)
                    │
                    └── agent/scheduler.py (background refresh)
```

## How it works

1. **On startup**: Connects to Datris MCP server via SSE, discovers tools via `tools/list`,
   reads MCP resources (Pipeline Configuration Reference) via `resources/read`
2. **User asks a question**: Claude determines which data sources are needed
3. **Data acquisition**: Agent fetches live data from public APIs, caches it server-side,
   and uses MCP tools (`generate_schema` → `create_pipeline` → `upload_data`) to ingest
4. **Pipeline management**: Claude monitors jobs via `get_job_status`, queries results via
   `query_postgres` — all through MCP tools discovered dynamically
5. **Intelligent acquisition**: If the user asks about data the agent doesn't have,
   the agent automatically fetches and ingests it — no confirmation needed
6. **Background refresh**: Active pipelines are automatically refreshed on a configurable timer

## Free data sources

| Source      | What it provides                          | Auth         |
|-------------|-------------------------------------------|--------------|
| FRED        | Macro series — yields, VIX, CPI, spreads  | [Free API key](https://fred.stlouisfed.org/docs/api/api_key.html) |
| yfinance    | Equity / ETF OHLCV                        | None         |
| CoinGecko   | Crypto prices, market cap                 | None (30/min)|
| SEC EDGAR   | 10-K / 10-Q filings                       | None (10/s)  |

## Suggested demo queries

1. `"What's the current macro picture?"` — creates pipelines, fetches live FRED + equity data
2. `"Refresh all pipelines"` — re-fetches all active data sources
3. `"Is crypto confirming the risk-on trade in equities?"` — cross-pipeline query
4. `"Which pipeline is most stale?"` — exercises `list_pipelines` live

## Environment variables

| Variable                   | Default                        | Description              |
|----------------------------|--------------------------------|--------------------------|
| `ANTHROPIC_API_KEY`        | —                              | **Required**             |
| `MODEL`                    | `claude-haiku-4-5-20251001`    | Claude model             |
| `PORT`                     | `8001`                         | uvicorn port             |
| `MCP_SERVER_URL`           | `http://localhost:3000/sse`    | Datris MCP server SSE    |
| `FRED_API_KEY`             | —                              | [Get one free](https://fred.stlouisfed.org/docs/api/api_key.html) |
| `REFRESH_INTERVAL_MINUTES` | `15`                           | Background refresh cycle |

## Requirements

- Python 3.11+
- [Datris Platform](https://github.com/datris/datris-platform-oss) running via Docker
- Anthropic API key
