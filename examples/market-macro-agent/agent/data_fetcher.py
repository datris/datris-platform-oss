"""
agent/data_fetcher.py

Fetches live financial data from public APIs and returns base64-encoded CSV
content ready for MCP upload_data.

Each fetch function returns (base64_content: str, filename: str).
"""

import base64
import csv
import io
import logging
import os
from datetime import datetime

import httpx

log = logging.getLogger("datris.fetcher")


def _to_base64_csv(rows: list[dict], filename: str) -> tuple[str, str]:
    """Convert a list of dicts to a base64-encoded CSV string."""
    if not rows:
        return base64.b64encode(b"").decode(), filename
    buf = io.StringIO()
    writer = csv.DictWriter(buf, fieldnames=rows[0].keys())
    writer.writeheader()
    writer.writerows(rows)
    content = base64.b64encode(buf.getvalue().encode()).decode()
    return content, filename


# ── FRED ──────────────────────────────────────────────────────────────────────

async def fetch_fred(
    series: list[str] | None = None,
) -> tuple[str, str]:
    """
    Fetch latest observations from the FRED API.

    Requires FRED_API_KEY env var. Falls back to a small default set of macro series.
    """
    api_key = os.environ.get("FRED_API_KEY", "")
    if not api_key:
        log.warning("FRED_API_KEY not set — returning empty dataset")
        return _to_base64_csv([], "fred_macro.csv")

    series = series or ["DGS10", "VIXCLS", "CPIAUCSL", "BAMLC0A0CM", "DFF", "UNRATE"]
    rows: list[dict] = []

    async with httpx.AsyncClient(timeout=30) as client:
        for sid in series:
            try:
                resp = await client.get(
                    "https://api.stlouisfed.org/fred/series/observations",
                    params={
                        "series_id": sid,
                        "api_key": api_key,
                        "file_type": "json",
                        "sort_order": "desc",
                        "limit": 5,
                    },
                )
                resp.raise_for_status()
                for obs in resp.json().get("observations", []):
                    # FRED returns the literal string "." for missing
                    # observations. Convert to None so csv.DictWriter emits an
                    # empty field, which Postgres COPY treats as NULL.
                    raw = obs["value"]
                    rows.append({
                        "series_id": sid,
                        "date": obs["date"],
                        "value": None if raw == "." else raw,
                        "fetched_at": datetime.utcnow().isoformat(),
                    })
            except Exception as e:
                log.error("FRED fetch failed for %s: %s", sid, e)

    log.info("FRED: fetched %d observations across %d series", len(rows), len(series))
    return _to_base64_csv(rows, "fred_macro.csv")


# ── Equities (yfinance) ──────────────────────────────────────────────────────

async def fetch_equities(
    tickers: list[str] | None = None,
    period: str = "5d",
) -> tuple[str, str]:
    """
    Fetch recent OHLCV data for equities/ETFs via yfinance.

    yfinance is synchronous so we run it in the default executor.
    """
    import asyncio

    tickers = tickers or ["SPY", "QQQ", "TLT", "GLD", "XLE", "IWM"]

    def _download():
        import yfinance as yf
        rows: list[dict] = []
        for ticker in tickers:
            try:
                df = yf.download(ticker, period=period, progress=False, auto_adjust=True)
                if df.empty:
                    continue
                # Flatten MultiIndex columns if present
                if hasattr(df.columns, 'levels'):
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
            except Exception as e:
                log.error("yfinance fetch failed for %s: %s", ticker, e)
        return rows

    loop = asyncio.get_event_loop()
    rows = await loop.run_in_executor(None, _download)
    log.info("Equities: fetched %d rows for %d tickers", len(rows), len(tickers))
    return _to_base64_csv(rows, "equities.csv")


# ── Crypto (CoinGecko) ───────────────────────────────────────────────────────

async def fetch_crypto(
    coins: list[str] | None = None,
) -> tuple[str, str]:
    """
    Fetch current crypto prices from the CoinGecko free API.
    """
    coins = coins or ["bitcoin", "ethereum", "solana"]
    rows: list[dict] = []

    async with httpx.AsyncClient(timeout=30) as client:
        try:
            resp = await client.get(
                "https://api.coingecko.com/api/v3/coins/markets",
                params={
                    "vs_currency": "usd",
                    "ids": ",".join(coins),
                    "order": "market_cap_desc",
                    "sparkline": "false",
                },
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
            log.error("CoinGecko fetch failed: %s", e)

    log.info("Crypto: fetched %d coins", len(rows))
    return _to_base64_csv(rows, "crypto.csv")


# ── SEC EDGAR ─────────────────────────────────────────────────────────────────

async def fetch_sec_filings(
    tickers: list[str] | None = None,
    form_types: list[str] | None = None,
) -> tuple[str, str]:
    """
    Fetch recent SEC filings from the EDGAR full-text search API.
    """
    tickers = tickers or ["AAPL", "MSFT", "GOOGL", "JPM", "XOM"]
    form_types = form_types or ["10-K", "10-Q"]
    rows: list[dict] = []

    headers = {"User-Agent": "DatrisAgent/1.0 (datris.ai)"}

    async with httpx.AsyncClient(timeout=30, headers=headers) as client:
        for ticker in tickers:
            try:
                resp = await client.get(
                    "https://efts.sec.gov/LATEST/search-index",
                    params={
                        "q": ticker,
                        "dateRange": "custom",
                        "startdt": "2024-01-01",
                        "forms": ",".join(form_types),
                    },
                )
                if resp.status_code == 200:
                    data = resp.json()
                    for hit in data.get("hits", {}).get("hits", [])[:3]:
                        src = hit.get("_source", {})
                        rows.append({
                            "ticker": ticker,
                            "form_type": src.get("forms", ""),
                            "filed_date": src.get("file_date", ""),
                            "company_name": src.get("display_names", [""])[0] if src.get("display_names") else "",
                            "filing_url": f"https://www.sec.gov/Archives/edgar/data/{src.get('file_num', '')}",
                            "fetched_at": datetime.utcnow().isoformat(),
                        })
            except Exception as e:
                log.error("SEC EDGAR fetch failed for %s: %s", ticker, e)

    log.info("SEC: fetched %d filings for %d tickers", len(rows), len(tickers))
    return _to_base64_csv(rows, "sec_filings.csv")


# ── Router ────────────────────────────────────────────────────────────────────

FETCHERS = {
    "fred": fetch_fred,
    "yfinance": fetch_equities,
    "equities": fetch_equities,
    "coingecko": fetch_crypto,
    "crypto": fetch_crypto,
    "sec_edgar": fetch_sec_filings,
    "sec": fetch_sec_filings,
}

# Fuzzy aliases: map common LLM-generated names to valid fetcher keys
_SOURCE_ALIASES: dict[str, str] = {
    # Equities / indices
    "spy": "equities", "qqq": "equities", "tlt": "equities", "gld": "equities",
    "sp500": "equities", "sp500_index": "equities", "s&p500": "equities",
    "s&p_500": "equities", "s&p": "equities", "spx": "equities",
    "nasdaq": "equities", "nasdaq_index": "equities", "nasdaq_composite": "equities",
    "dow": "equities", "dow_jones": "equities", "dow_jones_index": "equities",
    "djia": "equities", "stocks": "equities", "stock": "equities",
    "index": "equities", "indices": "equities", "etf": "equities", "etfs": "equities",
    "iwm": "equities", "xle": "equities", "equity": "equities",
    # Crypto
    "bitcoin": "crypto", "btc": "crypto", "ethereum": "crypto", "eth": "crypto",
    "solana": "crypto", "sol": "crypto", "cryptocurrency": "crypto",
    "cryptocurrencies": "crypto", "coin": "crypto", "coins": "crypto",
    # FRED / macro
    "macro": "fred", "treasury": "fred", "yields": "fred", "yield": "fred",
    "us_treasury_yields": "fred", "treasury_yields": "fred",
    "vix": "fred", "vix_volatility": "fred", "volatility": "fred",
    "cpi": "fred", "inflation": "fred", "unemployment": "fred",
    "fed_funds": "fred", "interest_rates": "fred", "rates": "fred",
    "credit_spreads": "fred", "spreads": "fred",
    "dgs10": "fred", "10y": "fred", "10_year": "fred",
    # Commodities (mapped to equities since yfinance handles commodity ETFs)
    "gold": "equities", "gold_prices": "equities", "crude_oil": "equities",
    "crude_oil_wti": "equities", "oil": "equities", "commodities": "equities",
    "usd": "fred", "usd_index": "fred", "dollar": "fred",
    # SEC
    "filings": "sec", "sec_filings": "sec", "edgar": "sec",
}


def _resolve_source(source: str) -> str:
    """Resolve a source name, trying exact match first then fuzzy aliases."""
    key = source.lower().strip()
    if key in FETCHERS:
        return key
    resolved = _SOURCE_ALIASES.get(key)
    if resolved:
        log.info("Resolved source alias '%s' → '%s'", source, resolved)
        return resolved
    raise ValueError(
        f"Unknown data source: '{source}'. "
        f"Valid sources: equities, crypto, fred, sec"
    )


async def fetch_source(source: str, **kwargs) -> tuple[str, str]:
    """Fetch data for a named source. Returns (base64_content, filename)."""
    key = _resolve_source(source)
    return await FETCHERS[key](**kwargs)
