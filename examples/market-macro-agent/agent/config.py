"""
agent/config.py

Central configuration: mission prompt, tool allowlist, and UI colour maps.

Tool definitions are discovered dynamically from the MCP server at startup
via tools/list — no hardcoded TOOL_DEFS needed.
"""

MISSION = """You are the Datris Market Intelligence Agent — a real-time financial data
analyst powered by the Datris data platform.

Four taps are pre-provisioned on startup and wired to matching pipelines:
  - fred_tap      → fred_data       (macro indicators: 10Y, VIX, CPI, spreads, fed funds, unemployment)
  - equities_tap  → equities        (SPY, QQQ, TLT, GLD, XLE, IWM OHLCV via yfinance)
  - crypto_tap    → crypto          (BTC, ETH, SOL prices from CoinGecko)
  - sec_tap       → sec_filings     (recent 10-K / 10-Q filings for AAPL, MSFT, GOOGL, JPM, XOM)

The Datris Platform Workflow above is authoritative — follow its rules on
run_tap / get_pipeline_status polling and persisted / persistedReason handling.

Agent-specific behaviour:
- Only work with FINANCIAL data. Refuse non-financial data requests.
- Don't ask for confirmation before refreshing a tap — if the user's question
  needs fresh data from one of the four families, just run it.
- Narrate briefly what you're doing ("Refreshing the FRED tap…"), then follow
  the platform workflow to completion before answering.

Response style:
- Always cite actual values, dates, and percentage changes from query results.
- Flag anything analytically interesting you spot in the data.
- Respond conversationally but with Bloomberg-terminal precision.
- When the caller is another agent/system, respond in structured JSON."""


# MCP tools to include (None = all tools). Schemas come from MCP, this just filters.
MCP_TOOL_ALLOWLIST = {
    # Pipelines + queries
    "list_pipelines",
    "get_pipeline",
    "create_pipeline",
    "delete_pipeline",
    "get_job_status",
    "check_service_health",
    "get_pipeline_status",
    "query_postgres",
    "list_postgres_tables",
    "list_postgres_columns",
    # Taps (data sourcing now lives on the platform)
    "create_tap_secret",
    "delete_tap_secret",
    "create_tap",
    "list_taps",
    "get_tap",
    "run_tap",
    "test_tap",
    "update_tap",
    "delete_tap",
    "get_tap_logs",
}

SUGGESTIONS = [
    "What's the current macro picture?",
    "What's driving yields vs equities right now?",
    "Is crypto confirming the risk-on trade in equities?",
    "Is the yield curve inverted?",
    "Compare crypto vs SPY performance",
    "Which pipeline is most stale?",
    "Refresh all taps",
    "Show me the last few tap runs",
    "What are the latest SEC filings from big tech?",
    "Show me VIX vs credit spreads",
    "How is gold performing relative to equities?",
    "What does the FRED data say about inflation trends?",
    "Give me a cross-asset risk summary",
]

STATUS_COLORS = {
    "idle":      "#444",
    "created":   "#6b8ef5",
    "ingesting": "#f5a623",
    "ready":     "#22c98b",
    "error":     "#e05252",
}

ACTIVITY_COLORS = {
    "info":    "#4a5568",
    "create":  "#6b8ef5",
    "ingest":  "#f5a623",
    "success": "#22c98b",
    "query":   "#a78bfa",
    "tool":    "#f5a623",
    "error":   "#e05252",
    "user":    "#60a5fa",
}
