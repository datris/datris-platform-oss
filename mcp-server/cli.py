#!/usr/bin/env python3
"""
Datris CLI — Command-line interface for the Datris Data Platform via MCP.

Usage:
    pip install datris-mcp-server
    datris pipelines
    datris ingest data.csv --pipeline my_data --dest postgres
    datris query "SELECT * FROM my_data LIMIT 10"
    datris delete my_data
"""

import asyncio
import base64
import json
import os
import sys
import time

import click
import httpx
from httpx_sse import aconnect_sse

MCP_URL = os.getenv("MCP_SERVER_URL", "http://localhost:3000/sse")

# ── MCP Client (lightweight, sync-wrapped) ────────────────────────────

_endpoint = None
_post_client = None
_sse_client = None
_responses = None
_reader_task = None
_sse_cm = None
_msg_id = 0


def _next_id():
    global _msg_id
    _msg_id += 1
    return _msg_id


async def _connect():
    global _endpoint, _post_client, _sse_client, _responses, _reader_task, _sse_cm

    _sse_client = httpx.AsyncClient(timeout=httpx.Timeout(5, read=300))
    _post_client = httpx.AsyncClient(timeout=30, limits=httpx.Limits(max_keepalive_connections=0))
    _responses = asyncio.Queue()

    _sse_cm = aconnect_sse(_sse_client, "GET", MCP_URL)
    sse = await _sse_cm.__aenter__()

    async def _read():
        global _endpoint
        try:
            async for event in sse.aiter_sse():
                if event.event == "endpoint":
                    base = MCP_URL.rsplit("/", 1)[0]
                    _endpoint = base + event.data
                elif event.event == "message":
                    await _responses.put(json.loads(event.data))
        except (asyncio.CancelledError, Exception):
            pass

    _reader_task = asyncio.create_task(_read())

    for _ in range(50):
        if _endpoint:
            break
        await asyncio.sleep(0.1)
    if not _endpoint:
        raise ConnectionError("No endpoint from MCP server")

    # Initialize
    init_id = _next_id()
    await _post_client.post(_endpoint, json={
        "jsonrpc": "2.0", "id": init_id,
        "method": "initialize",
        "params": {"protocolVersion": "2024-11-05", "capabilities": {}, "clientInfo": {"name": "datris-cli", "version": "1.5.5"}},
    })
    await asyncio.wait_for(_responses.get(), 10)
    await _post_client.post(_endpoint, json={"jsonrpc": "2.0", "method": "notifications/initialized"})


async def _call_tool(name, arguments=None):
    if not _endpoint:
        await _connect()

    call_id = _next_id()
    await _post_client.post(_endpoint, json={
        "jsonrpc": "2.0", "id": call_id,
        "method": "tools/call",
        "params": {"name": name, "arguments": arguments or {}},
    })

    while True:
        try:
            resp = await asyncio.wait_for(_responses.get(), 120)
        except asyncio.TimeoutError:
            return {"error": f"Timeout: {name}"}
        if resp.get("id") == call_id:
            break

    if "error" in resp:
        return {"error": resp["error"].get("message", str(resp["error"]))}

    content = resp.get("result", {}).get("content", [])
    text = "\n".join(b["text"] for b in content if b.get("type") == "text")
    try:
        return json.loads(text)
    except (json.JSONDecodeError, TypeError):
        return {"text": text}


async def _disconnect():
    if _reader_task:
        _reader_task.cancel()
    if _sse_cm:
        try:
            await _sse_cm.__aexit__(None, None, None)
        except Exception:
            pass
    if _sse_client:
        await _sse_client.aclose()
    if _post_client:
        await _post_client.aclose()


def mcp(name, args=None):
    """Synchronous wrapper for MCP tool calls."""
    return asyncio.get_event_loop().run_until_complete(_call_tool(name, args))


def b64_file(path):
    with open(path, "rb") as f:
        return base64.b64encode(f.read()).decode()


# ── CLI Commands ──────────────────────────────────────────────────────

@click.group()
@click.version_option(version="1.5.5")
def cli():
    """Datris CLI — The Agent-Native Data Platform"""
    pass


@cli.command()
@click.option("--json", "json_output", is_flag=True, default=False, help="Return raw JSON")
def pipelines(json_output):
    """List all registered pipelines."""
    result = mcp("list_pipelines")
    if json_output:
        click.echo(json.dumps(result, indent=2))
        return
    if isinstance(result, dict) and result.get("pipelines") == []:
        click.echo("No pipelines registered.")
        return
    if isinstance(result, list):
        for p in result:
            name = p.get("name", "unknown")
            dest = ""
            d = p.get("destination", {})
            if d.get("database", {}).get("usePostgres"): dest = "→ PostgreSQL"
            elif d.get("database", {}).get("useMongoDB"): dest = "→ MongoDB"
            elif d.get("pgvector"): dest = "→ pgvector"
            elif d.get("qdrant"): dest = "→ Qdrant"
            elif d.get("weaviate"): dest = "→ Weaviate"
            elif d.get("milvus"): dest = "→ Milvus"
            elif d.get("chroma"): dest = "→ Chroma"
            click.echo(f"  {name} {dest}")
    else:
        click.echo(json.dumps(result, indent=2)[:500])


@cli.command()
@click.argument("file", type=click.Path(exists=True))
@click.option("--pipeline", "-p", default=None, help="Pipeline name (default: derived from filename)")
@click.option("--dest", "-d", default="postgres", type=click.Choice(["postgres", "mongodb", "qdrant", "weaviate", "milvus", "chroma", "pgvector"]), help="Destination type")
@click.option("--table", "-t", default=None, help="Table/collection name (default: pipeline name)")
@click.option("--database", default="datris", help="Database name")
@click.option("--ai-validate", default=None, help="AI data quality rule (plain English, e.g. 'all prices must be positive')")
@click.option("--ai-transform", default=None, help="AI transformation instruction (plain English, e.g. 'convert dates to YYYY/MM/DD')")
@click.option("--ai-analyze", default=None, help="Ask a question about the data after ingestion (plain English)")
@click.option("--json", "json_output", is_flag=True, default=False, help="Return raw JSON")
def ingest(file, pipeline, dest, table, database, ai_validate, ai_transform, ai_analyze, json_output):
    """Create a pipeline and ingest a data file."""
    content = b64_file(file)
    filename = os.path.basename(file)

    # Auto-derive pipeline name from filename if not specified
    if not pipeline:
        pipeline = os.path.splitext(filename)[0].lower().replace("-", "_").replace(" ", "_")

    # Build args
    args = {
        "content": content,
        "filename": filename,
        "pipeline": pipeline,
        "destination": dest,
    }
    if table:
        args["table"] = table
    if database != "datris":
        args["database"] = database
    if ai_validate:
        args["codegen_rule"] = ai_validate
    if ai_transform:
        args["codegen_transform"] = ai_transform

    # Create pipeline
    click.echo(f"  Creating pipeline '{pipeline}' → {dest}...")
    result = mcp("create_pipeline", args)
    if result.get("error"):
        click.echo(f"  Error: {result['error'][:200]}")
        sys.exit(1)
    click.echo(f"  ✓ Pipeline created")

    if ai_validate:
        click.echo(f"  ✓ AI validation: {ai_validate}")
    if ai_transform:
        click.echo(f"  ✓ AI transformation: {ai_transform}")

    # Upload data
    click.echo(f"  Uploading {filename}...")
    upload_result = mcp("upload_data", {"content": content, "filename": filename, "pipeline": pipeline})
    if upload_result.get("error"):
        click.echo(f"  Error: {upload_result['error'][:200]}")
        sys.exit(1)
    token = upload_result.get("pipelineToken", "")
    click.echo(f"  ✓ Uploaded (token: {token[:36]})")

    # Wait for completion
    click.echo(f"  Waiting...")
    completed = False
    for _ in range(30):
        time.sleep(2)
        status = mcp("get_job_status", {"pipeline_name": pipeline})
        if isinstance(status, list) and len(status) > 0:
            s = status[0].get("status", "")
            if s in ("success", "completed"):
                click.echo(f"  ✓ Done ({status[0].get('totalTime', '')})")
                completed = True
                break
            elif s == "error":
                click.echo(f"  ✗ Failed")
                sys.exit(1)
        elif isinstance(status, dict) and "text" in status:
            if "STILL RUNNING" in status.get("text", ""):
                continue
            elif "FAILED" in status.get("text", ""):
                click.echo(f"  ✗ Failed")
                sys.exit(1)

    if not completed:
        click.echo(f"  ⚠ Timeout")
        return

    # Run AI analysis if requested
    if ai_analyze:
        table_name = table or pipeline
        _run_analyze(ai_analyze, table_name, dest, json_output)


def _run_analyze(question, table, dest, json_output, top_k=5):
    """Shared analyze logic for ingest --ai-analyze and datris analyze."""
    click.echo(f"  Analyzing: {question}")

    if dest == "postgres":
        query_result = mcp("query_natural", {"question": question, "table": table})
        if json_output:
            click.echo(json.dumps(query_result, indent=2))
            return
        results = query_result.get("results", [])
        sql = query_result.get("sql", "")
        if sql:
            click.echo(f"  SQL: {sql}")
        if not results:
            click.echo("  No results found.")
            return
        context = json.dumps(results, indent=2)
        click.echo(f"  Generating AI answer...")
        answer_result = mcp("ai_answer", {"query": question, "context": context})
        answer = answer_result.get("answer", answer_result.get("text", str(answer_result)))
        click.echo(f"\n  {answer}")

    elif dest == "mongodb":
        query_result = mcp("query_mongodb", {"collection": table, "limit": 100})
        if json_output:
            click.echo(json.dumps(query_result, indent=2))
            return
        results = query_result.get("results", [])
        if not results:
            click.echo("  No results found.")
            return
        context = json.dumps(results, indent=2)
        click.echo(f"  Generating AI answer...")
        answer_result = mcp("ai_answer", {"query": question, "context": context})
        answer = answer_result.get("answer", answer_result.get("text", str(answer_result)))
        click.echo(f"\n  {answer}")

    else:
        # Vector store — search → ai_answer
        tool_map = {
            "qdrant": ("search_qdrant", "collection"),
            "weaviate": ("search_weaviate", "class_name"),
            "milvus": ("search_milvus", "collection"),
            "chroma": ("search_chroma", "collection"),
            "pgvector": ("search_pgvector", "table"),
        }
        tool, key = tool_map[dest]
        search_result = mcp(tool, {"query": question, key: table, "top_k": top_k})
        if json_output:
            click.echo(json.dumps(search_result, indent=2))
            return
        results = search_result.get("results", [])
        if not results:
            click.echo("  No results found.")
            return
        click.echo(f"  ✓ Found {len(results)} relevant chunk(s)")
        context = "\n\n".join(r.get("text", str(r)) for r in results)
        click.echo(f"  Generating AI answer...")
        answer_result = mcp("ai_answer", {"query": question, "context": context})
        answer = answer_result.get("answer", answer_result.get("text", str(answer_result)))
        click.echo(f"\n  {answer}")


@cli.command()
@click.argument("question")
@click.option("--table", "-t", required=True, help="Table/collection name")
@click.option("--dest", "-d", default="postgres", type=click.Choice(["postgres", "mongodb", "qdrant", "weaviate", "milvus", "chroma", "pgvector"]), help="Data source type")
@click.option("--top-k", "-k", default=5, help="Number of search results (vector stores only)")
@click.option("--json", "json_output", is_flag=True, default=False, help="Return raw JSON instead of AI narrative")
def analyze(question, table, dest, top_k, json_output):
    """Ask a question about your data using AI."""
    _run_analyze(question, table, dest, json_output, top_k)


@cli.command()
@click.argument("sql")
@click.option("--limit", default=100, help="Max rows")
@click.option("--json", "json_output", is_flag=True, default=False, help="Return raw JSON")
def query(sql, limit, json_output):
    """Execute a read-only SQL query."""
    result = mcp("query_postgres", {"sql": sql, "limit": limit})
    if json_output:
        click.echo(json.dumps(result, indent=2))
        return
    results = result.get("results", [])
    count = result.get("count", 0)
    if not results:
        click.echo("No results.")
        return
    cols = list(results[0].keys())
    click.echo("  " + " | ".join(cols))
    click.echo("  " + "-+-".join("-" * max(len(c), 10) for c in cols))
    for row in results:
        vals = [str(row.get(c, ""))[:30] for c in cols]
        click.echo("  " + " | ".join(vals))
    click.echo(f"\n  {count} row(s)")


@cli.command()
@click.argument("question")
@click.option("--store", "-s", default="pgvector", type=click.Choice(["qdrant", "weaviate", "milvus", "chroma", "pgvector"]), help="Vector store to search")
@click.option("--collection", "-c", required=True, help="Collection/table name")
@click.option("--top-k", "-k", default=5, help="Number of results")
@click.option("--json", "json_output", is_flag=True, default=False, help="Return raw JSON")
def search(question, store, collection, top_k, json_output):
    """Semantic search across a vector database."""
    tool_map = {
        "qdrant": ("search_qdrant", "collection"),
        "weaviate": ("search_weaviate", "class_name"),
        "milvus": ("search_milvus", "collection"),
        "chroma": ("search_chroma", "collection"),
        "pgvector": ("search_pgvector", "table"),
    }
    tool, key = tool_map[store]
    args = {"query": question, key: collection, "top_k": top_k}

    click.echo(f"  Searching {store}/{collection}...")
    result = mcp(tool, args)
    if json_output:
        click.echo(json.dumps(result, indent=2))
        return
    results = result.get("results", [])
    count = result.get("count", 0)

    if not results:
        click.echo("  No results found.")
        return

    for i, r in enumerate(results):
        score = r.get("_score", "")
        text = r.get("text", str(r))[:200]
        score_str = f" (score: {score:.3f})" if isinstance(score, (int, float)) else ""
        click.echo(f"\n  [{i+1}]{score_str}")
        click.echo(f"  {text}")

    click.echo(f"\n  {count} result(s)")


@cli.command("query-mongo")
@click.argument("collection")
@click.option("--filter", "-f", "mongo_filter", default="{}", help="MongoDB filter JSON")
@click.option("--projection", default=None, help="MongoDB projection JSON")
@click.option("--limit", default=20, help="Max documents")
@click.option("--json", "json_output", is_flag=True, default=False, help="Return raw JSON")
def query_mongo(collection, mongo_filter, projection, limit, json_output):
    """Query a MongoDB collection."""
    args = {"collection": collection, "limit": limit}
    try:
        args["filter"] = json.loads(mongo_filter)
    except json.JSONDecodeError:
        click.echo(f"  Error: Invalid filter JSON: {mongo_filter}")
        sys.exit(1)
    if projection:
        try:
            args["projection"] = json.loads(projection)
        except json.JSONDecodeError:
            click.echo(f"  Error: Invalid projection JSON: {projection}")
            sys.exit(1)

    result = mcp("query_mongodb", args)
    if json_output:
        click.echo(json.dumps(result, indent=2))
        return
    results = result.get("results", [])
    count = result.get("count", 0)

    if not results:
        click.echo("No results.")
        return

    for doc in results:
        click.echo(f"  {json.dumps(doc, indent=2)[:300]}")
    click.echo(f"\n  {count} document(s)")


@cli.command()
@click.argument("pipeline_name")
@click.option("--json", "json_output", is_flag=True, default=False, help="Return raw JSON")
def status(pipeline_name, json_output):
    """Get job status for a pipeline."""
    result = mcp("get_job_status", {"pipeline_name": pipeline_name})
    if json_output:
        click.echo(json.dumps(result, indent=2))
        return
    if isinstance(result, list):
        for job in result[:5]:
            s = job.get("status", "unknown")
            t = job.get("totalTime", "")
            p = job.get("pipeline", "")
            icon = "✓" if s in ("success", "completed") else "✗" if s == "error" else "…"
            click.echo(f"  {icon} {p} — {s} ({t})")
    elif isinstance(result, dict) and "text" in result:
        click.echo(result["text"][:500])


@cli.command()
@click.argument("pipeline_name")
@click.option("--keep-data", is_flag=True, help="Keep destination data")
@click.option("--json", "json_output", is_flag=True, default=False, help="Return raw JSON")
def delete(pipeline_name, keep_data, json_output):
    """Delete a pipeline and its data."""
    result = mcp("delete_pipeline", {"pipeline": pipeline_name})
    if json_output:
        click.echo(json.dumps(result, indent=2))
        return
    click.echo(f"  ✓ Pipeline '{pipeline_name}' deleted" + (" (data kept)" if keep_data else ""))


@cli.command()
@click.option("--json", "json_output", is_flag=True, default=False, help="Return raw JSON")
def health(json_output):
    """Check backend service health."""
    result = mcp("check_service_health")
    if json_output:
        click.echo(json.dumps(result, indent=2))
        return
    if isinstance(result, dict):
        for svc, info in result.items():
            s = info.get("status", "unknown") if isinstance(info, dict) else info
            icon = "✓" if s == "up" else "✗" if s == "down" else "○"
            click.echo(f"  {icon} {svc}: {s}")


@cli.command()
@click.option("--json", "json_output", is_flag=True, default=False, help="Return raw JSON")
def secrets(json_output):
    """List all secrets (requires Datris REST API)."""
    import requests
    datris_url = os.getenv("DATRIS_URL", "http://localhost:8080")
    try:
        resp = requests.get(f"{datris_url}/api/v1/secrets", timeout=10)
        data = resp.json()
        if json_output:
            click.echo(json.dumps(data, indent=2))
            return
        for name in data:
            click.echo(f"  {name}")
    except Exception as e:
        click.echo(f"  Error: {e}")


@cli.command()
@click.option("--json", "json_output", is_flag=True, default=False, help="Return raw JSON")
def taps(json_output):
    """List all taps."""
    result = mcp("list_taps")
    if json_output:
        click.echo(json.dumps(result, indent=2))
        return
    if isinstance(result, list):
        if not result:
            click.echo("No taps created.")
            return
        for t in result:
            name = t.get("name", "unknown")
            pipeline = t.get("targetPipeline", "")
            status = t.get("lastRunStatus", "never")
            cron = t.get("cronExpression", "")
            records = t.get("lastRunRecordCount", 0)
            schedule = f" [{cron}]" if cron else ""
            click.echo(f"  {name} → {pipeline}{schedule}  ({status}, {records} records)")
    else:
        click.echo(json.dumps(result, indent=2)[:500])


@cli.group()
def tap():
    """Manage taps (create, run, delete)."""
    pass


@tap.command("create")
@click.argument("description")
@click.option("--pipeline", "-p", required=True, help="Target pipeline name")
@click.option("--name", "-n", default=None, help="Tap name (default: derived from pipeline)")
@click.option("--cron", default=None, help="CRON expression for scheduling (Quartz format)")
@click.option("--json", "json_output", is_flag=True, default=False, help="Return raw JSON")
def tap_create(description, pipeline, name, cron, json_output):
    """Create a tap from a plain-English description."""
    tap_name = name or f"{pipeline}-tap"
    click.echo(f"  Generating script for tap '{tap_name}'...")

    args = {
        "name": tap_name,
        "description": description,
        "target_pipeline": pipeline,
    }
    if cron:
        args["cron_expression"] = cron

    result = mcp("create_tap", args)
    if json_output:
        click.echo(json.dumps(result, indent=2))
        return
    if isinstance(result, dict) and result.get("error"):
        click.echo(f"  Error: {result['error'][:200]}")
        sys.exit(1)
    click.echo(f"  ✓ Tap '{tap_name}' created → {pipeline}")
    if cron:
        click.echo(f"  ✓ Schedule: {cron}")


@tap.command("run")
@click.argument("name")
@click.option("--json", "json_output", is_flag=True, default=False, help="Return raw JSON")
def tap_run(name, json_output):
    """Run a tap manually."""
    click.echo(f"  Running tap '{name}'...")
    result = mcp("run_tap", {"name": name})
    if json_output:
        click.echo(json.dumps(result, indent=2))
        return
    if isinstance(result, dict):
        if result.get("error"):
            click.echo(f"  Error: {result['error'][:200]}")
            sys.exit(1)
        status = result.get("status", "unknown")
        records = result.get("recordCount", 0)
        click.echo(f"  ✓ {status} — {records} records fetched")
    else:
        click.echo(f"  {result}")


@tap.command("delete")
@click.argument("name")
def tap_delete(name):
    """Delete a tap."""
    result = mcp("delete_tap", {"name": name})
    if isinstance(result, dict) and result.get("error"):
        click.echo(f"  Error: {result['error'][:200]}")
        sys.exit(1)
    click.echo(f"  ✓ Tap '{name}' deleted")


@cli.command()
@click.option("--json", "json_output", is_flag=True, default=False, help="Return raw JSON")
def version(json_output):
    """Get server version."""
    result = mcp("get_version")
    if json_output:
        click.echo(json.dumps(result, indent=2))
        return
    click.echo(f"  Server: {result.get('text', result) if isinstance(result, dict) else result}")
    click.echo(f"  CLI: 1.5.5")


def main():
    try:
        cli()
    finally:
        try:
            asyncio.get_event_loop().run_until_complete(_disconnect())
        except Exception:
            pass


if __name__ == "__main__":
    main()
