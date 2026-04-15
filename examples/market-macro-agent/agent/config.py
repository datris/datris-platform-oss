"""
agent/config.py

Central configuration: mission prompt, pipeline seed, and UI colour maps.

Tool definitions are discovered dynamically from the MCP server at startup
via tools/list — no hardcoded TOOL_DEFS needed.
"""

MISSION = """You are the Datris Market Intelligence Agent — a real-time financial data
pipeline operator and analyst powered by the Datris data platform.

You have access to tools discovered from the Datris MCP server. These tools let you
create pipelines, upload data, monitor jobs, and query results.

You also have a local tool called "ingest_data" that fetches live market data from
public APIs and uploads it to Datris automatically.

Use the MCP tools to manage Datris pipelines. The tool descriptions and MCP resources
explain how to create pipelines, upload data, monitor jobs, and query results.
Always check get_job_status after uploading data to verify ingestion completed.

IMPORTANT — Intelligent data acquisition:
- When a user asks a financial question that requires data you don't have yet,
  immediately fetch and ingest it. Do NOT ask for confirmation — just do it.
- Briefly mention what you're fetching (e.g. "Pulling crypto prices from CoinGecko...")
  then proceed with the full workflow: create → ingest → query → answer.
- Only acquire FINANCIAL data. Do not attempt to fetch non-financial data.

Rules:
- Always cite actual values, dates, and percentage changes from query results
- Flag anything analytically interesting you spot in the data
- Respond conversationally but with Bloomberg-terminal precision
- When the caller is another agent/system, respond in structured JSON"""

# Local tool that the agent adds alongside MCP tools
INGEST_TOOL_DEF = {
    "name": "ingest_data",
    "description": (
        "Fetch live market data from a public API source. Returns a data_id reference "
        "and filename. Pass data_id to create_pipeline, generate_schema, and upload_data — "
        "the actual content is resolved server-side automatically.\n\n"
        "VALID SOURCE NAMES (use exactly one of these):\n"
        "- 'equities' or 'yfinance': Fetches OHLCV data for SPY, QQQ, TLT, GLD, XLE, IWM via yfinance\n"
        "- 'crypto' or 'coingecko': Fetches current prices for bitcoin, ethereum, solana via CoinGecko\n"
        "- 'fred': Fetches macro indicators (10Y yield, VIX, CPI, credit spreads, fed funds, unemployment) from FRED\n"
        "- 'sec' or 'sec_edgar': Fetches recent SEC filings (10-K, 10-Q) for AAPL, MSFT, GOOGL, JPM, XOM\n\n"
        "Do NOT invent source names. Use ONLY the names listed above."
    ),
    "input_schema": {
        "type": "object",
        "properties": {
            "source": {
                "type": "string",
                "enum": ["equities", "yfinance", "crypto", "coingecko", "fred", "sec", "sec_edgar"],
                "description": "Data source name: 'equities', 'crypto', 'fred', or 'sec'",
            },
        },
        "required": ["source"],
    },
}

# MCP tools to include (None = all tools). Schemas come from MCP, this just filters.
MCP_TOOL_ALLOWLIST = {
    "list_pipelines",
    "get_pipeline",
    "create_pipeline",
    "delete_pipeline",
    "upload_data",
    "generate_schema",
    "get_job_status",
    "check_service_health",
    "query_postgres",
    "list_postgres_tables",
    "list_postgres_columns",
}

SUGGESTIONS = [
    "What's the current macro picture?",
    "What's driving yields vs equities right now?",
    "Is crypto confirming the risk-on trade in equities?",
    "Is the yield curve inverted?",
    "Compare crypto vs SPY performance",
    "Which pipeline is most stale?",
    "Refresh all pipelines",
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
