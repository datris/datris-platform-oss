#!/usr/bin/env python3
"""
MCP Server Test Script

Tests all MCP tools against a running pipeline instance.
Requires: pipeline running on localhost:8080 (or set PIPELINE_URL env var)

Usage:
    cd helpers/test-mcp-server
    pip install requests python-dotenv
    python app.py
"""

import json
import os
import sys
import tempfile
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
TEST_PIPELINE = "mcp_test_pipeline"
TEST_MONGO_PIPELINE = "mcp_test_mongodb"
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

        # Check for error responses
        if parsed and isinstance(parsed, dict) and "error" in parsed:
            print(f"  FAIL  {name}")
            print(f"        {result[:200]}")
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
        print(json.dumps(parsed, indent=2))
    except (json.JSONDecodeError, TypeError):
        print(result)


def create_test_csv():
    """Create a temporary CSV file with stock price data for testing."""
    content = "symbol,date,open,high,low,close,volume\nAAPL,2026-01-02,150.0,155.0,149.0,154.0,1000000\nGOOG,2026-01-02,100.0,105.0,99.0,104.0,500000\nMSFT,2026-01-02,310.0,315.0,308.0,312.0,750000\n"
    path = os.path.join(tempfile.gettempdir(), "mcp_test_data.csv")
    with open(path, "w") as f:
        f.write(content)
    return path


def create_test_json():
    """Create a temporary JSON file with stock price data for MongoDB testing."""
    data = [
        {"symbol": "AAPL", "date": "2026-01-02", "open": 150.0, "high": 155.0, "low": 149.0, "close": 154.0, "volume": 1000000},
        {"symbol": "GOOG", "date": "2026-01-02", "open": 100.0, "high": 105.0, "low": 99.0, "close": 104.0, "volume": 500000},
        {"symbol": "MSFT", "date": "2026-01-02", "open": 310.0, "high": 315.0, "low": 308.0, "close": 312.0, "volume": 750000}
    ]
    path = os.path.join(tempfile.gettempdir(), "mcp_test_data.json")
    with open(path, "w") as f:
        json.dump(data, f)
    return path


def main():
    global PASSED, FAILED

    print("=" * 60)
    print("  MCP Server Tool Tests")
    print(f"  Pipeline URL: {PIPELINE_URL}")
    print("=" * 60)
    print()

    # 1. Get version — verify connectivity
    print("[1] Connectivity")
    result = test("get_version", "get_version")
    if result is None:
        print("\n  Pipeline not reachable. Is it running?")
        sys.exit(1)

    # 2. Service health check
    print("\n[2] Service health check")
    result = test("check_service_health", "check_service_health")
    if result:
        try:
            health = json.loads(result)
            for svc, status in health.items():
                s = status.get("status", "unknown") if isinstance(status, dict) else status
                print(f"        {svc}: {s}")
        except (json.JSONDecodeError, TypeError):
            pass

    # 3. List pipelines
    print("\n[3] List pipelines")
    test("list_pipelines", "list_pipelines")

    # ================================================================
    # Metadata Discovery
    # ================================================================
    print("\n" + "=" * 60)
    print("  Metadata Discovery")
    print("=" * 60)

    # 4. PostgreSQL metadata
    print("\n[4] PostgreSQL metadata discovery")
    test("list_postgres_databases", "list_postgres_databases")
    test("list_postgres_schemas", "list_postgres_schemas", {"database": "datris"})
    test("list_postgres_tables", "list_postgres_tables", {"database": "datris", "schema": "public"})
    test("list_postgres_tables (vector only)", "list_postgres_tables", {"database": "datris", "schema": "public", "vector_only": True})

    # 5. MongoDB metadata
    print("\n[5] MongoDB metadata discovery")
    test("list_mongodb_databases", "list_mongodb_databases")
    test("list_mongodb_collections", "list_mongodb_collections")

    # ================================================================
    # Ingest stock_price CSV → PostgreSQL
    # ================================================================
    print("\n" + "=" * 60)
    print("  Ingest Stock Prices: CSV → PostgreSQL")
    print("=" * 60)

    # 6. Create PostgreSQL pipeline
    print("\n[6] Create PostgreSQL pipeline")
    pg_config = {
        "name": TEST_PIPELINE,
        "source": {
            "schemaProperties": {
                "fields": [
                    {"name": "symbol", "type": "string"},
                    {"name": "date", "type": "string"},
                    {"name": "open", "type": "double"},
                    {"name": "high", "type": "double"},
                    {"name": "low", "type": "double"},
                    {"name": "close", "type": "double"},
                    {"name": "volume", "type": "int"}
                ]
            },
            "fileAttributes": {
                "csvAttributes": {
                    "delimiter": ",",
                    "header": True,
                    "encoding": "UTF-8"
                }
            }
        },
        "destination": {
            "database": {
                "dbName": "datris",
                "schema": "public",
                "table": "mcp_test_stock_price",
                "usePostgres": True
            }
        }
    }
    test("create_pipeline (postgres)", "create_pipeline", {"config": pg_config})

    # 7. Get pipeline config back
    print("\n[7] Get pipeline config")
    test("get_pipeline", "get_pipeline", {"pipeline": TEST_PIPELINE})

    # 8. Upload CSV stock data
    print("\n[8] Upload CSV → PostgreSQL")
    csv_path = create_test_csv()
    result = test("upload_file (csv→postgres)", "upload_file", {"file_path": csv_path, "pipeline": TEST_PIPELINE})

    # 9. Wait for ingestion and check status
    print("\n[9] Wait for PostgreSQL ingestion")
    if result:
        print("        Waiting 10 seconds for ingestion...")
        time.sleep(10)
        test("get_job_status (postgres)", "get_job_status", {"pipeline_name": TEST_PIPELINE})

    # 10. Inspect table columns
    print("\n[10] Inspect ingested table columns")
    test("list_postgres_columns", "list_postgres_columns", {"database": "datris", "schema": "public", "table": "mcp_test_stock_price"})

    # 11. Query the ingested stock data from PostgreSQL
    print("\n[11] Query stock prices from PostgreSQL")
    pg_sql = "SELECT * FROM public.mcp_test_stock_price"
    print(f"        SQL: {pg_sql}")
    result = test("query_postgres", "query_postgres", {"sql": pg_sql})
    dump_results(result)

    # ================================================================
    # Ingest stock_price JSON → MongoDB
    # ================================================================
    print("\n" + "=" * 60)
    print("  Ingest Stock Prices: JSON → MongoDB")
    print("=" * 60)

    # 12. Create MongoDB pipeline
    print("\n[12] Create MongoDB pipeline")
    mongo_config = {
        "name": TEST_MONGO_PIPELINE,
        "source": {
            "schemaProperties": {
                "fields": [
                    {"name": "_json", "type": "string"}
                ]
            },
            "fileAttributes": {
                "jsonAttributes": {
                    "everyRowContainsObject": False,
                    "encoding": "UTF-8"
                }
            }
        },
        "destination": {
            "database": {
                "dbName": "datris",
                "table": "mcp_test_stock_price",
                "useMongoDB": True
            }
        }
    }
    test("create_pipeline (mongodb)", "create_pipeline", {"config": mongo_config})

    # 13. Upload JSON stock data
    print("\n[13] Upload JSON → MongoDB")
    json_path = create_test_json()
    result = test("upload_file (json→mongodb)", "upload_file", {"file_path": json_path, "pipeline": TEST_MONGO_PIPELINE})

    # 14. Wait for ingestion and check status
    print("\n[14] Wait for MongoDB ingestion")
    if result:
        print("        Waiting 10 seconds for ingestion...")
        time.sleep(10)
        test("get_job_status (mongodb)", "get_job_status", {"pipeline_name": TEST_MONGO_PIPELINE})

    # 15. Query the ingested stock data from MongoDB
    print("\n[15] Query stock prices from MongoDB")
    print("        Collection: mcp_test_stock_price")
    result = test("query_mongodb", "query_mongodb", {"collection": "mcp_test_stock_price", "limit": 10})
    dump_results(result)

    # ================================================================
    # Profile & Generate Schema
    # ================================================================
    print("\n" + "-" * 60)
    print("  Profile & Generate Schema")
    print("-" * 60)

    # 16. Profile data
    print("\n[16] Profile data")
    test("profile_data", "profile_data", {"file_path": csv_path})

    # 17. Generate schema
    print("\n[17] Generate schema")
    test("generate_schema", "generate_schema", {"file_path": csv_path, "pipeline": "mcp_generated_test"})

    # ================================================================
    # Cleanup structured pipelines
    # ================================================================
    print("\n[18] Cleanup structured pipelines")
    test("delete_pipeline (postgres)", "delete_pipeline", {"pipeline": TEST_PIPELINE})
    test("delete_pipeline (mongodb)", "delete_pipeline", {"pipeline": TEST_MONGO_PIPELINE})
    os.unlink(csv_path)
    os.unlink(json_path)

    # ================================================================
    # Vector Database: Ingest + Search (pgvector)
    # ================================================================
    print("\n" + "=" * 60)
    print("  Vector Database: PDF → pgvector")
    print("=" * 60)

    # 19. Create pgvector pipeline
    print("\n[19] Create pgvector pipeline")
    pgvector_config = {
        "name": TEST_PGVECTOR_PIPELINE,
        "source": {
            "fileAttributes": {
                "unstructuredAttributes": {
                    "fileExtension": "pdf",
                    "preserveFilename": True
                }
            }
        },
        "destination": {
            "pgvector": {
                "tableName": "mcp_test_vectors",
                "schemaName": "public",
                "chunking": {
                    "strategy": "recursive",
                    "chunkSize": 500,
                    "chunkOverlap": 50
                },
                "metadata": {
                    "company": "Apple Inc",
                    "document_type": "10-Q"
                },
                "embeddingSecretName": "oss/embedding",
                "postgresSecretName": "oss/pgvector"
            }
        }
    }
    test("create_pipeline (pgvector)", "create_pipeline", {"config": pgvector_config})

    # 20. Upload PDF to pgvector
    print("\n[20] Upload PDF to pgvector")
    if os.path.exists(PDF_PATH):
        result = test("upload_file (pdf→pgvector)", "upload_file", {"file_path": PDF_PATH, "pipeline": TEST_PGVECTOR_PIPELINE})

        # 21. Wait for processing then check status
        print("\n[21] Wait for pgvector ingestion")
        if result:
            print("        Waiting 15 seconds for ingestion...")
            time.sleep(15)
            test("get_job_status (pgvector)", "get_job_status", {"pipeline_name": TEST_PGVECTOR_PIPELINE})

        # 22. Search pgvector
        print("\n[22] Search pgvector")
        print("        Query: What was Apple's revenue?")
        result = test("search_pgvector", "search_pgvector", {"query": "What was Apple's revenue?", "table": "mcp_test_vectors", "top_k": 3})
        dump_results(result)

        # 23. AI Answer (RAG)
        print("\n[23] AI Answer (RAG)")
        if result:
            try:
                parsed = json.loads(result)
                results = parsed.get("results", [])
                context = "\n".join(r.get("text", "") for r in results if r.get("text"))
                if context:
                    test("ai_answer", "ai_answer", {"query": "What was Apple's revenue?", "context": context})
                else:
                    print("  SKIP  No text in search results for ai_answer test")
            except (json.JSONDecodeError, TypeError):
                print("  SKIP  Could not parse search results for ai_answer test")
        else:
            print("  SKIP  No search results for ai_answer test")
    else:
        print(f"  SKIP  PDF not found at {PDF_PATH}")

    # 24. Cleanup pgvector pipeline
    print("\n[24] Cleanup pgvector pipeline")
    test("delete_pipeline (pgvector)", "delete_pipeline", {"pipeline": TEST_PGVECTOR_PIPELINE})

    # Summary
    print()
    print("=" * 60)
    print(f"  Results: {PASSED} passed, {FAILED} failed")
    print("=" * 60)

    sys.exit(1 if FAILED > 0 else 0)


if __name__ == "__main__":
    main()
