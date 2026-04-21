"""
agent/tap_definitions.py

The 4 taps this example provisions on the Datris platform. Each entry
describes a Python `fetch()` script (BYO code, not AI-generated) that
the platform runs in its own Docker sandbox and pushes into a pipeline.

Design notes:
- BYO code over instruction-generated scripts — the example needs
  deterministic, reproducible behavior.
- No CRON — `enabled=False`, `cron=None`. The agent drives refreshes via
  MCP `run_tap`, so the platform scheduler never auto-fires.
- `secret_fields` is the list of env var names to pull from the agent's
  local environment and push into Datris via `create_tap_secret`.
  The tap script reads them with `os.environ.get(...)` at runtime.
"""

# ── Tap fetch() scripts ──────────────────────────────────────────────────────

FRED_SCRIPT = '''
import os
from datetime import datetime

import requests


def fetch():
    api_key = os.environ.get("FRED_API_KEY", "")
    if not api_key:
        return []

    series = ["DGS10", "VIXCLS", "CPIAUCSL", "BAMLC0A0CM", "DFF", "UNRATE"]
    rows = []

    for sid in series:
        try:
            resp = requests.get(
                "https://api.stlouisfed.org/fred/series/observations",
                params={
                    "series_id": sid,
                    "api_key": api_key,
                    "file_type": "json",
                    "sort_order": "desc",
                    "limit": 5,
                },
                timeout=30,
            )
            resp.raise_for_status()
            for obs in resp.json().get("observations", []):
                raw = obs["value"]
                rows.append({
                    "series_id": sid,
                    "date": obs["date"],
                    "value": None if raw == "." else raw,
                    "fetched_at": datetime.utcnow().isoformat(),
                })
        except Exception as e:
            print(f"FRED fetch failed for {sid}: {e}")

    return rows
'''.lstrip()


EQUITIES_SCRIPT = '''
import os
from datetime import datetime

# yfinance is not pre-installed in the tap runtime — it's added via pip
# on first run and cached in the Docker volume for subsequent runs.
import yfinance as yf


def fetch():
    tickers = ["SPY", "QQQ", "TLT", "GLD", "XLE", "IWM"]
    period = "5d"

    # Honor test limit so test_tap finishes quickly during dev
    limit = int(os.environ.get("DATRIS_TAP_TEST_LIMIT", "0")) or None

    rows = []
    for ticker in tickers:
        try:
            df = yf.download(ticker, period=period, progress=False, auto_adjust=True)
            if df.empty:
                continue
            if hasattr(df.columns, "levels"):
                df.columns = df.columns.get_level_values(0)
            for date_idx, row in df.iterrows():
                rows.append({
                    "ticker": ticker,
                    "date": str(date_idx.date()),
                    "open": round(float(row["Open"]), 2),
                    "high": round(float(row["High"]), 2),
                    "low": round(float(row["Low"]), 2),
                    "close": round(float(row["Close"]), 2),
                    "volume": int(row["Volume"]),
                    "fetched_at": datetime.utcnow().isoformat(),
                })
                if limit and len(rows) >= limit:
                    return rows
        except Exception as e:
            print(f"yfinance fetch failed for {ticker}: {e}")

    return rows
'''.lstrip()


CRYPTO_SCRIPT = '''
from datetime import datetime

import requests


def fetch():
    coins = ["bitcoin", "ethereum", "solana"]
    rows = []

    try:
        resp = requests.get(
            "https://api.coingecko.com/api/v3/coins/markets",
            params={
                "vs_currency": "usd",
                "ids": ",".join(coins),
                "order": "market_cap_desc",
                "sparkline": "false",
            },
            timeout=30,
        )
        resp.raise_for_status()
        for coin in resp.json():
            rows.append({
                "coin_id": coin["id"],
                "symbol": coin["symbol"].upper(),
                "current_price_usd": coin["current_price"],
                "market_cap_usd": coin["market_cap"],
                "total_volume_usd": coin["total_volume"],
                "price_change_24h_pct": coin.get("price_change_percentage_24h"),
                "last_updated": coin.get("last_updated", ""),
                "fetched_at": datetime.utcnow().isoformat(),
            })
    except Exception as e:
        print(f"CoinGecko fetch failed: {e}")

    return rows
'''.lstrip()


SEC_SCRIPT = '''
from datetime import datetime

import requests


def fetch():
    tickers = ["AAPL", "MSFT", "GOOGL", "JPM", "XOM"]
    form_types = ["10-K", "10-Q"]
    rows = []

    headers = {"User-Agent": "DatrisAgent/1.0 (datris.ai)"}

    for ticker in tickers:
        try:
            resp = requests.get(
                "https://efts.sec.gov/LATEST/search-index",
                params={
                    "q": ticker,
                    "dateRange": "custom",
                    "startdt": "2024-01-01",
                    "forms": ",".join(form_types),
                },
                headers=headers,
                timeout=30,
            )
            if resp.status_code != 200:
                continue
            for hit in resp.json().get("hits", {}).get("hits", [])[:3]:
                src = hit.get("_source", {})
                rows.append({
                    "ticker": ticker,
                    "form_type": src.get("forms", ""),
                    "filed_date": src.get("file_date", ""),
                    "company_name": (
                        src.get("display_names", [""])[0]
                        if src.get("display_names") else ""
                    ),
                    "filing_url": (
                        f"https://www.sec.gov/Archives/edgar/data/"
                        f"{src.get('file_num', '')}"
                    ),
                    "fetched_at": datetime.utcnow().isoformat(),
                })
        except Exception as e:
            print(f"SEC EDGAR fetch failed for {ticker}: {e}")

    return rows
'''.lstrip()


# ── Sample CSVs (used to bootstrap the pipeline schema via create_pipeline) ──
# The platform infers schema from the header + first row. Values are placeholder;
# they're overwritten the first time the tap runs for real.

FRED_SAMPLE_CSV = (
    "series_id,date,value,fetched_at\n"
    "DGS10,2026-04-21,4.26,2026-04-21T19:24:13\n"
)

EQUITIES_SAMPLE_CSV = (
    "ticker,date,open,high,low,close,volume,fetched_at\n"
    "SPY,2026-04-21,500.00,505.00,499.00,503.00,50000000,2026-04-21T19:24:13\n"
)

CRYPTO_SAMPLE_CSV = (
    "coin_id,symbol,current_price_usd,market_cap_usd,total_volume_usd,"
    "price_change_24h_pct,last_updated,fetched_at\n"
    "bitcoin,BTC,60000,1200000000000,30000000000,1.5,"
    "2026-04-21T00:00:00Z,2026-04-21T19:24:13\n"
)

SEC_SAMPLE_CSV = (
    "ticker,form_type,filed_date,company_name,filing_url,fetched_at\n"
    "AAPL,10-K,2025-11-01,Apple Inc.,https://www.sec.gov/Archives/edgar/data/000/"
    ",2026-04-21T19:24:13\n"
)


# ── Tap specs ────────────────────────────────────────────────────────────────

TAPS: list[dict] = [
    {
        "name": "fred_tap",
        "pipeline": "fred_data",
        "description": (
            "FRED macro indicators — 10Y yield, VIX, CPI, credit spreads, "
            "fed funds, unemployment."
        ),
        "script": FRED_SCRIPT,
        "sample_csv": FRED_SAMPLE_CSV,
        "packages": [],
        "secret_name": "market-macro-fred",
        "secret_fields": ["FRED_API_KEY"],
    },
    {
        "name": "equities_tap",
        "pipeline": "equities",
        "description": (
            "Equity/ETF OHLCV via yfinance — SPY, QQQ, TLT, GLD, XLE, IWM "
            "(last 5 trading days)."
        ),
        "script": EQUITIES_SCRIPT,
        "sample_csv": EQUITIES_SAMPLE_CSV,
        "packages": ["yfinance"],
        "secret_name": None,
        "secret_fields": [],
    },
    {
        "name": "crypto_tap",
        "pipeline": "crypto",
        "description": "Crypto prices from CoinGecko — BTC, ETH, SOL.",
        "script": CRYPTO_SCRIPT,
        "sample_csv": CRYPTO_SAMPLE_CSV,
        "packages": [],
        "secret_name": None,
        "secret_fields": [],
    },
    {
        "name": "sec_tap",
        "pipeline": "sec_filings",
        "description": (
            "Recent 10-K / 10-Q filings from SEC EDGAR for AAPL, MSFT, "
            "GOOGL, JPM, XOM."
        ),
        "script": SEC_SCRIPT,
        "sample_csv": SEC_SAMPLE_CSV,
        "packages": [],
        "secret_name": None,
        "secret_fields": [],
    },
]


# Pipeline name → tap name. Used by the scheduler to decide which tap
# to run when refreshing a pipeline.
PIPELINE_TO_TAP: dict[str, str] = {t["pipeline"]: t["name"] for t in TAPS}
