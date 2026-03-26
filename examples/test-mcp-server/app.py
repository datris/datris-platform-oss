#!/usr/bin/env python3
"""
MCP Server Tool Tests

Tests all MCP tools against a running Datris instance.
Requires: Datris running on localhost:8080 (or set PIPELINE_URL env var)

Usage:
    cd examples/test-mcp-server
    pip install -r requirements.txt
    python app.py
"""

import base64
import json
import os
import sys
import time

import requests
from dotenv import load_dotenv

load_dotenv()

PIPELINE_URL = os.getenv("PIPELINE_URL", "http://localhost:8080")
PIPELINE_API_KEY = os.getenv("PIPELINE_API_KEY", "")

# Add mcp-server to path so we can import the dispatch function
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "mcp-server"))
from server import _dispatch

PASSED = 0
FAILED = 0
TEST_CSV_PIPELINE = "mcp_test_csv"
TEST_JSON_PIPELINE = "mcp_test_json"
TEST_PGVECTOR_PIPELINE = "mcp_test_pgvector"
PDF_PATH = os.path.join(os.path.dirname(__file__), "..", "..", "test-scripts", "files", "apple-10-Q-jan-30-2026.pdf")


def test(name, tool, args=None):
    """Run a single MCP tool test."""
    global PASSED, FAILED
    if args is None:
        args = {}
    try:
        result = _dispatch(tool, args)
        parsed = None
        try:
            parsed = json.loads(result)
        except (json.JSONDecodeError, TypeError):
            pass

        if parsed and isinstance(parsed, dict) and "error" in parsed:
            print(f"  FAIL  {name}")
            print(f"        {result[:300]}")
            FAILED += 1
            return None

        # Check for exception stack traces
        if result and "Exception" in result:
            print(f"  FAIL  {name}")
            print(f"        {result[:300]}")
            FAILED += 1
            return None

        print(f"  PASS  {name}")
        if result and len(result) < 300:
            print(f"        {result}")
        elif result:
            print(f"        ({len(result)} chars)")
        PASSED += 1
        return result
    except Exception as e:
        print(f"  FAIL  {name}: {e}")
        FAILED += 1
        return None


def dump_results(result):
    """Pretty-print a result payload."""
    if not result:
        return
    try:
        parsed = json.loads(result)
        print(json.dumps(parsed, indent=2)[:500])
    except (json.JSONDecodeError, TypeError):
        print(result[:500])


def b64(text):
    """Encode text as base64."""
    return base64.b64encode(text.encode()).decode()


def b64_file(path):
    """Encode file as base64."""
    with open(path, "rb") as f:
        return base64.b64encode(f.read()).decode()


CSV_DATA = "symbol,date,open,high,low,close,volume\nAAPL,2026-01-02,150.0,155.0,149.0,154.0,1000000\nGOOG,2026-01-02,100.0,105.0,99.0,104.0,500000\nMSFT,2026-01-02,310.0,315.0,308.0,312.0,750000\n"

JSON_DATA = json.dumps([
    {"symbol": "AAPL", "date": "2026-01-02", "open": 150.0, "close": 154.0, "volume": 1000000},
    {"symbol": "GOOG", "date": "2026-01-02", "open": 100.0, "close": 104.0, "volume": 500000},
])


def wait_for_job(pipeline_name, max_wait=20):
    """Poll job status until complete or timeout."""
    for i in range(max_wait // 2):
        time.sleep(2)
        result = _dispatch("get_job_status", {"pipeline_name": pipeline_name})
        try:
            data = json.loads(result)
            if isinstance(data, list) and len(data) > 0:
                status = data[0].get("status", "")
                if status in ("success", "completed"):
                    print(f"        Job completed ({(i+1)*2}s)")
                    return True
                elif status == "error":
                    print(f"        Job failed ({(i+1)*2}s)")
                    return False
        except (json.JSONDecodeError, TypeError):
            pass
    print(f"        Timeout after {max_wait}s")
    return False


def main():
    global PASSED, FAILED

    print("=" * 60)
    print("  MCP Server Tool Tests")
    print(f"  Pipeline URL: {PIPELINE_URL}")
    print("=" * 60)
    print()

    # ================================================================
    # 1. Connectivity
    # ================================================================
    print("[1] Connectivity")
    result = test("get_version", "get_version")
    if result is None:
        print("\n  Pipeline not reachable. Is it running?")
        sys.exit(1)

    # ================================================================
    # 2. Service Health
    # ================================================================
    print("\n[2] Service health")
    result = test("check_service_health", "check_service_health")
    if result:
        try:
            health = json.loads(result)
            for svc, status in health.items():
                s = status.get("status", "unknown") if isinstance(status, dict) else status
                print(f"        {svc}: {s}")
        except (json.JSONDecodeError, TypeError):
            pass

    # ================================================================
    # 3. List Pipelines
    # ================================================================
    print("\n[3] List pipelines")
    test("list_pipelines", "list_pipelines")

    # ================================================================
    # 4. Metadata Discovery — PostgreSQL
    # ================================================================
    print("\n" + "=" * 60)
    print("  Metadata Discovery")
    print("=" * 60)

    print("\n[4] PostgreSQL metadata")
    test("list_postgres_databases", "list_postgres_databases")
    test("list_postgres_schemas", "list_postgres_schemas", {"database": "datris"})
    test("list_postgres_tables", "list_postgres_tables", {"database": "datris", "schema": "public"})
    test("list_postgres_tables (vector only)", "list_postgres_tables", {"database": "datris", "schema": "public", "vector_only": True})

    # ================================================================
    # 5. Metadata Discovery — MongoDB
    # ================================================================
    print("\n[5] MongoDB metadata")
    test("list_mongodb_databases", "list_mongodb_databases")
    test("list_mongodb_collections", "list_mongodb_collections")

    # ================================================================
    # 6. Metadata Discovery — Vector Stores
    # ================================================================
    print("\n[6] Vector store metadata")
    test("list_pgvector_collections", "list_pgvector_collections")
    test("list_qdrant_collections", "list_qdrant_collections")
    test("list_weaviate_classes", "list_weaviate_classes")
    test("list_milvus_collections", "list_milvus_collections")
    test("list_chroma_collections", "list_chroma_collections")

    # ================================================================
    # 7. Create Pipeline: CSV → PostgreSQL (atomic — content-based)
    # ================================================================
    print("\n" + "=" * 60)
    print("  Ingest: CSV → PostgreSQL")
    print("=" * 60)

    print("\n[7] Create pipeline (CSV → PostgreSQL)")
    test("create_pipeline (csv)", "create_pipeline", {
        "content": b64(CSV_DATA),
        "filename": "stock_prices.csv",
        "pipeline": TEST_CSV_PIPELINE,
        "destination": "postgres",
        "table": "mcp_test_stock_prices"
    })

    # 8. Verify pipeline was created
    print("\n[8] Verify pipeline")
    test("get_pipeline", "get_pipeline", {"pipeline": TEST_CSV_PIPELINE})

    # 9. Upload data
    print("\n[9] Upload CSV data")
    result = test("upload_data (csv)", "upload_data", {
        "content": b64(CSV_DATA),
        "filename": "stock_prices.csv",
        "pipeline": TEST_CSV_PIPELINE
    })

    # 10. Wait and check status
    print("\n[10] Wait for ingestion")
    if result:
        wait_for_job(TEST_CSV_PIPELINE)
        test("get_job_status (by name)", "get_job_status", {"pipeline_name": TEST_CSV_PIPELINE})

    # 11. Inspect columns
    print("\n[11] Inspect table columns")
    test("list_postgres_columns", "list_postgres_columns", {
        "database": "datris", "schema": "public", "table": "mcp_test_stock_prices"
    })

    # 12. Query data
    print("\n[12] Query PostgreSQL")
    result = test("query_postgres", "query_postgres", {"sql": "SELECT * FROM mcp_test_stock_prices"})
    dump_results(result)

    # ================================================================
    # 13. Create Pipeline: JSON → MongoDB (atomic)
    # ================================================================
    print("\n" + "=" * 60)
    print("  Ingest: JSON → MongoDB")
    print("=" * 60)

    print("\n[13] Create pipeline (JSON → MongoDB)")
    test("create_pipeline (json)", "create_pipeline", {
        "content": b64(JSON_DATA),
        "filename": "stock_prices.json",
        "pipeline": TEST_JSON_PIPELINE,
        "destination": "mongodb",
        "table": "mcp_test_stocks"
    })

    # 14. Upload JSON data
    print("\n[14] Upload JSON data")
    result = test("upload_data (json)", "upload_data", {
        "content": b64(JSON_DATA),
        "filename": "stock_prices.json",
        "pipeline": TEST_JSON_PIPELINE
    })

    # 15. Wait and check
    print("\n[15] Wait for MongoDB ingestion")
    if result:
        wait_for_job(TEST_JSON_PIPELINE)

    # 16. Query MongoDB
    print("\n[16] Query MongoDB")
    result = test("query_mongodb", "query_mongodb", {"collection": "mcp_test_stocks", "limit": 10})
    dump_results(result)

    # ================================================================
    # 17. Profile Data
    # ================================================================
    print("\n" + "=" * 60)
    print("  Profile Data")
    print("=" * 60)

    print("\n[17] Profile CSV data")
    test("profile_data", "profile_data", {
        "content": b64(CSV_DATA),
        "filename": "stock_prices.csv"
    })

    # ================================================================
    # 18. Upload Config
    # ================================================================
    print("\n[18] Upload config (validation schema)")
    schema = json.dumps({"type": "object", "properties": {"symbol": {"type": "string"}}})
    test("upload_config", "upload_config", {
        "content": b64(schema),
        "filename": "test_schema.json",
        "type": "validation-schema"
    })

    # ================================================================
    # 19. Update Secret
    # ================================================================
    print("\n[19] Update secret")
    test("update_secret", "update_secret", {
        "name": "ollama",
        "fields": {"endpoint": "http://localhost:11434/v1/chat/completions", "model": "qwen2.5:14b-instruct", "apiKey": ""}
    })

    # ================================================================
    # 20. Kill Job (test with dummy token — should handle gracefully)
    # ================================================================
    print("\n[20] Kill job (dummy token)")
    test("kill_job", "kill_job", {"pipeline_token": "00000000-0000-0000-0000-000000000000"})

    # ================================================================
    # 21. Cleanup structured pipelines
    # ================================================================
    print("\n" + "=" * 60)
    print("  Cleanup")
    print("=" * 60)

    print("\n[21] Delete pipelines (with destination cleanup)")
    test("delete_pipeline (csv)", "delete_pipeline", {"pipeline": TEST_CSV_PIPELINE})
    test("delete_pipeline (json)", "delete_pipeline", {"pipeline": TEST_JSON_PIPELINE})

    # ================================================================
    # 22. Vector Database: PDF → pgvector
    # ================================================================
    print("\n" + "=" * 60)
    print("  Vector Database: PDF → pgvector")
    print("=" * 60)

    if os.path.exists(PDF_PATH):
        print("\n[22] Create pgvector pipeline")
        test("create_pipeline (pgvector)", "create_pipeline", {
            "content": b64("text\nSample document for schema detection\n"),
            "filename": "sample.csv",
            "pipeline": TEST_PGVECTOR_PIPELINE,
            "destination": "pgvector",
            "table": "mcp_test_vectors"
        })

        # Note: pgvector pipeline needs unstructured config, but create_pipeline
        # auto-detects from the content. For PDF, we'd need the old-style config.
        # For now, test the search tools with existing data if available.

        print("\n[23] Search pgvector (if data exists)")
        result = test("search_pgvector", "search_pgvector", {
            "query": "What was Apple's revenue?",
            "table": "mcp_test_vectors",
            "top_k": 3
        })

        if result:
            print("\n[24] AI Answer (RAG)")
            try:
                parsed = json.loads(result)
                results = parsed.get("results", [])
                context = "\n".join(r.get("text", "") for r in results if r.get("text"))
                if context:
                    test("ai_answer", "ai_answer", {"query": "What was Apple's revenue?", "context": context})
                else:
                    print("  SKIP  No text in search results")
            except (json.JSONDecodeError, TypeError):
                print("  SKIP  Could not parse search results")

        print("\n[25] Cleanup pgvector pipeline")
        test("delete_pipeline (pgvector)", "delete_pipeline", {"pipeline": TEST_PGVECTOR_PIPELINE})
    else:
        print(f"  SKIP  PDF not found at {PDF_PATH}")
        print("        Skipping vector database tests")

    # ================================================================
    # Summary
    # ================================================================
    print()
    print("=" * 60)
    print(f"  Results: {PASSED} passed, {FAILED} failed")
    print("=" * 60)

    sys.exit(1 if FAILED > 0 else 0)


if __name__ == "__main__":
    main()
