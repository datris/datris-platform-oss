#!/usr/bin/env python3
"""
Datris MCP Server

A thin REST API client that exposes the Datris platform as MCP tools so AI agents
(Claude, Cursor, etc.) can natively interact with the platform — discover data,
create pipelines, upload files, monitor jobs, search vector databases, query
structured data, and answer questions with AI.

Usage:
    pip install -r requirements.txt

    # stdio mode (for Claude Desktop / Claude Code)
    python server.py

    # SSE mode (for Docker / remote agents)
    python server.py --sse --port 3000
"""

import argparse
import json
import os
from typing import Any

import requests
from dotenv import load_dotenv
from mcp.server import Server
from mcp.types import Resource, Tool, TextContent

load_dotenv()

PIPELINE_URL = os.getenv("PIPELINE_URL", "http://localhost:8080")
PIPELINE_API_KEY = os.getenv("PIPELINE_API_KEY", "")

server = Server("datris", instructions="""\
Datris is the first AI Agent-Native Data Platform. It ingests, validates, transforms, and routes data to databases, message queues, and vector stores — all driven by pipeline configurations that AI agents can create and manage programmatically.

A pipeline config has two required sections: source and destination. Keep configs simple: source + destination only.

NEVER rules:
  - NEVER use profile_data to determine how to generate a pipeline configuration
  - NEVER add dataQuality or transformation sections unless explicitly requested
  - If data quality is needed, use codegen_rule on create_pipeline (plain-English validation instruction)
  - If transformation is needed, use codegen_transform on create_pipeline (plain-English transformation instruction)

Required workflow:
  1. Check existing pipelines: call list_pipelines. If no pipelines exist, you MUST create one before querying — do NOT skip to querying database tables. If a pipeline exists, data may already be in the destination — use metadata tools to discover and query it directly. Only re-ingest if data is stale.
  2. Create a pipeline: call create_pipeline with your sample data (base64-encoded), pipeline name, and destination type. The schema is auto-detected from the data — you do not construct pipeline configs manually.
  3. Ingest data: call upload_data with base64-encoded content and the pipeline name
  4. Monitor: call get_job_status and poll until status is COMPLETED or FAILED. You MUST wait for COMPLETED before querying.
  5. If FAILED: read the error message from get_job_status. Fix the issue (e.g., delete the pipeline, re-create with corrected parameters, re-upload).
  6. Query & search: use query_postgres, query_mongodb for structured data; search_qdrant, search_pgvector, etc. for vector search
  7. RAG: pass search results as context to ai_answer with the user's question

Do NOT call check_service_health as part of the normal workflow — it is slow. Only use it for diagnostics if something fails.
Do NOT call update_secret unless you need to configure AI provider keys and they are not already set.
""")


def _headers():
    """Build request headers."""
    h = {"Content-Type": "application/json"}
    if PIPELINE_API_KEY:
        h["x-api-key"] = PIPELINE_API_KEY
    return h


def _call(method, path, **kwargs):
    """Make an HTTP request to the pipeline API."""
    url = f"{PIPELINE_URL}{path}"
    try:
        resp = getattr(requests, method)(url, headers=_headers(), timeout=300, **kwargs)
        return resp.text
    except requests.RequestException as e:
        return json.dumps({"error": str(e)})


def _upload(path, file_path, data=None):
    """Upload a file via multipart POST to the pipeline API (local file path)."""
    with open(file_path, "rb") as f:
        files = {"file": (os.path.basename(file_path), f)}
        h = {}
        if PIPELINE_API_KEY:
            h["x-api-key"] = PIPELINE_API_KEY
        resp = requests.post(
            f"{PIPELINE_URL}{path}",
            headers=h,
            files=files,
            data=data or {},
            timeout=300
        )
        return resp.text


def _upload_content(path, content_b64, filename, data=None):
    """Upload base64-encoded content via multipart POST to the pipeline API."""
    import base64
    import tempfile

    # Fix missing base64 padding
    padded = content_b64 + "=" * (-len(content_b64) % 4)
    file_bytes = base64.b64decode(padded)
    with tempfile.NamedTemporaryFile(delete=False, suffix=f"_{filename}") as tmp:
        tmp.write(file_bytes)
        tmp_path = tmp.name
    try:
        with open(tmp_path, "rb") as f:
            files = {"file": (filename, f)}
            h = {}
            if PIPELINE_API_KEY:
                h["x-api-key"] = PIPELINE_API_KEY
            resp = requests.post(
                f"{PIPELINE_URL}{path}",
                headers=h,
                files=files,
                data=data or {},
                timeout=300
            )
            return resp.text
    finally:
        os.unlink(tmp_path)


# ---------------------------------------------------------------------------
# MCP Resources
# ---------------------------------------------------------------------------

PIPELINE_CONFIG_REFERENCE = """\
# Datris Pipeline Configuration Reference

## Required Workflow — Follow These Steps

1. Call `list_pipelines` to check if the pipeline already exists.
2. If it exists, data may already be in the destination. Use metadata tools (`list_postgres_tables`, `list_mongodb_collections`, `list_qdrant_collections`, etc.) to discover it and query/search directly. Only re-ingest if the data is stale or needs updating.
3. If the pipeline does not exist, call `create_pipeline` with your sample data (base64-encoded), pipeline name, and destination type. The schema is auto-detected from the data.
5. Call `check_service_health` to verify the target destination service is available.
6. Call `create_pipeline` with the config.
7. Call `upload_data` with your data (base64-encoded) and the pipeline name.
8. Call `get_job_status` to monitor processing. Poll until status is COMPLETED or FAILED.
9. Query or search the data: `query_postgres`, `query_mongodb`, `search_qdrant`, `search_pgvector`, etc.
10. For RAG: pass search results as context to `ai_answer` with the user's question.

---

## Config Structure

Pass this JSON to `create_pipeline`. Only `name`, `source`, and `destination` are required. For reliable data sources, do NOT add dataQuality, transformation, or preprocessor sections — keep it simple. Only add those sections if the data source is unreliable or you have a specific processing requirement.

```json
{
  "name": "pipeline_name",
  "source": { ... },
  "preprocessor": { ... },
  "dataQuality": { ... },
  "transformation": { ... },
  "destination": { ... }
}
```

## Source

Set `source.fileAttributes` to one of these (create_pipeline does this automatically):

### fileAttributes — choose one

**csvAttributes** — use for CSV/TSV files:
```json
"csvAttributes": {
  "delimiter": ",",
  "header": true,
  "encoding": "UTF-8"
}
```

**jsonAttributes** — for JSON files:
```json
"jsonAttributes": {
  "everyRowContainsObject": false,
  "encoding": "UTF-8"
}
```

**xmlAttributes** — for XML files:
```json
"xmlAttributes": {
  "everyRowContainsObject": false,
  "encoding": "UTF-8"
}
```

**xlsAttributes** — for Excel files:
```json
"xlsAttributes": {
  "worksheet": 0,
  "tempCsvFileDelimiter": ","
}
```

**unstructuredAttributes** — use for PDFs, DOCX, TXT. Must use a vector DB destination:
```json
"unstructuredAttributes": {
  "fileExtension": "pdf",
  "preserveFilename": true
}
```

### schemaProperties — set field names and types (create_pipeline does this automatically, omit for unstructured)

```json
"schemaProperties": {
  "fields": [
    {"name": "column_name", "type": "string"},
    {"name": "price", "type": "double"},
    {"name": "quantity", "type": "int"}
  ]
}
```

Supported field types: `string`, `int`, `bigint`, `float`, `double`, `boolean`, `date`, `timestamp`

### streamAttributes (optional, for streaming sources like Kafka)

```json
"streamAttributes": {
  "type": "kafka"
}
```

### databaseAttributes (optional, for database pull sources)

```json
"databaseAttributes": {
  "type": "postgres",
  "postgresSecretsName": "oss/postgres",
  "database": "mydb",
  "schema": "public",
  "table": "source_table",
  "cronExpression": "0 0 * * *",
  "timestampFieldName": "updated_at",
  "includeFields": ["col1", "col2"],
  "sqlOverride": "SELECT * FROM source_table WHERE active = true"
}
```

## Preprocessor (optional)

Set this to call an external REST endpoint before processing. Use for custom validation or enrichment.

```json
"preprocessor": {
  "endpoint": "https://my-service.example.com/preprocess",
  "bearerToken": "token123",
  "apiKey": "key123",
  "timeoutMs": 300000,
  "async": false
}
```

## Data Quality (optional — only if explicitly requested)

Use `codegen_rule` on `create_pipeline` for AI-powered validation. Datris generates a Python script from the instruction and runs it locally.

For JSON/XML schema validation, use `validationSchema` (upload schema file first with `upload_config`).

For CSV header validation, use `validateFileHeader: true`.

## Transformation (optional — only if explicitly requested)

Use `codegen_transform` on `create_pipeline` for AI-powered transformation. Datris generates a Python script from the instruction and runs it locally.

Example config (for reference only — agents should use create_pipeline params, not construct configs):

```json
"transformation": {
  "aiTransformation": {
    "instruction": "convert all dates to YYYY-MM-DD format"
  }
}
```

## Destination (required — set one)

Call `check_service_health` first to verify the target service is available.

### database — PostgreSQL (use for structured CSV data)

```json
"destination": {
  "database": {
    "dbName": "datris",
    "schema": "public",
    "table": "my_table",
    "usePostgres": true,
    "keyFields": ["id"],
    "truncateBeforeWrite": false,
    "manageTableManually": false
  }
}
```

### database — MongoDB (use for JSON data)

```json
"destination": {
  "database": {
    "dbName": "datris",
    "table": "my_collection",
    "useMongoDB": true
  }
}
```

### objectStore — S3/MinIO

```json
"destination": {
  "objectStore": {
    "prefixKey": "data/output/",
    "fileFormat": "parquet",
    "writeMode": "overwrite",
    "partitionBy": ["date", "region"]
  }
}
```

File formats: `parquet`, `csv`, `json`, `orc`
Write modes: `overwrite`, `append`, `ignore`, `error`

### kafka

```json
"destination": {
  "kafka": {
    "topic": "my-topic",
    "keyField": "id"
  }
}
```

### activeMQ

```json
"destination": {
  "activeMQ": {
    "queueName": "my-queue"
  }
}
```

### restEndpoint

```json
"destination": {
  "restEndpoint": {
    "endpoint": "https://my-service.example.com/ingest",
    "bearerToken": "token123",
    "timeoutMs": 300000
  }
}
```

### Vector databases — use for RAG / semantic search with unstructured files (PDF, DOCX, TXT)

All vector DB destinations require chunking and embedding config. Call `check_service_health` to see which vector DBs are available.

**qdrant:**
```json
"destination": {
  "qdrant": {
    "collectionName": "my_documents",
    "chunking": {"strategy": "recursive", "chunkSize": 500, "chunkOverlap": 50},
    "metadata": {"source": "annual_reports", "year": "2026"},
    "embeddingSecretName": "oss/embedding",
    "qdrantSecretName": "oss/qdrant"
  }
}
```

**weaviate:**
```json
"destination": {
  "weaviate": {
    "className": "MyDocuments",
    "chunking": {"strategy": "recursive", "chunkSize": 500, "chunkOverlap": 50},
    "metadata": {"source": "annual_reports"},
    "embeddingSecretName": "oss/embedding",
    "weaviateSecretName": "oss/weaviate"
  }
}
```

**milvus:**
```json
"destination": {
  "milvus": {
    "collectionName": "my_documents",
    "chunking": {"strategy": "recursive", "chunkSize": 500, "chunkOverlap": 50},
    "metadata": {"source": "annual_reports"},
    "embeddingSecretName": "oss/embedding",
    "milvusSecretName": "oss/milvus"
  }
}
```

**chroma:**
```json
"destination": {
  "chroma": {
    "collectionName": "my_documents",
    "chunking": {"strategy": "recursive", "chunkSize": 500, "chunkOverlap": 50},
    "metadata": {"source": "annual_reports"},
    "embeddingSecretName": "oss/embedding",
    "chromaSecretName": "oss/chroma"
  }
}
```

**pgvector:**
```json
"destination": {
  "pgvector": {
    "tableName": "my_documents",
    "schemaName": "public",
    "chunking": {"strategy": "recursive", "chunkSize": 500, "chunkOverlap": 50},
    "metadata": {"source": "annual_reports"},
    "embeddingSecretName": "oss/embedding",
    "postgresSecretName": "oss/pgvector"
  }
}
```

## Example Configs — for reference only (create_pipeline generates these automatically)

### CSV → PostgreSQL with AI data quality

```json
{
  "name": "stock_prices",
  "source": {
    "fileAttributes": {
      "csvAttributes": {"delimiter": ",", "header": true, "encoding": "UTF-8"}
    },
    "schemaProperties": {
      "fields": [
        {"name": "symbol", "type": "string"},
        {"name": "date", "type": "string"},
        {"name": "close", "type": "double"},
        {"name": "volume", "type": "int"}
      ]
    }
  },
  "dataQuality": {
    "aiRule": {
      "instruction": "All price columns must be positive. Volume must be a positive integer.",
      "onFailureIsError": false
    }
  },
  "destination": {
    "database": {
      "dbName": "datris",
      "schema": "public",
      "table": "stock_prices",
      "usePostgres": true
    }
  }
}
```

### PDF → pgvector for RAG

```json
{
  "name": "financial_docs",
  "source": {
    "fileAttributes": {
      "unstructuredAttributes": {"fileExtension": "pdf", "preserveFilename": true}
    }
  },
  "destination": {
    "pgvector": {
      "tableName": "financial_documents",
      "schemaName": "public",
      "chunking": {"strategy": "recursive", "chunkSize": 500, "chunkOverlap": 50},
      "metadata": {"company": "Acme Corp", "document_type": "10-K"},
      "embeddingSecretName": "oss/embedding",
      "postgresSecretName": "oss/pgvector"
    }
  }
}
```

### JSON → MongoDB

```json
{
  "name": "events",
  "source": {
    "fileAttributes": {
      "jsonAttributes": {"everyRowContainsObject": false, "encoding": "UTF-8"}
    },
    "schemaProperties": {
      "fields": [{"name": "_json", "type": "string"}]
    }
  },
  "destination": {
    "database": {
      "dbName": "datris",
      "table": "events",
      "useMongoDB": true
    }
  }
}
```
"""


@server.list_resources()
async def list_resources():
    return [
        Resource(
            uri="datris://pipeline-config-reference",
            name="Pipeline Configuration Reference",
            description="Complete reference for building Datris pipeline configurations. Covers all source types (CSV, JSON, XML, PDF), AI-powered data quality (CodeGen), AI transformations (CodeGen), and all destination types (PostgreSQL, MongoDB, Kafka, vector databases). Read this before using create_pipeline.",
            mimeType="text/plain",
        )
    ]


@server.read_resource()
async def read_resource(uri):
    if str(uri) == "datris://pipeline-config-reference":
        return PIPELINE_CONFIG_REFERENCE
    raise ValueError(f"Unknown resource: {uri}")


# ---------------------------------------------------------------------------
# MCP Tools
# ---------------------------------------------------------------------------

@server.list_tools()
async def list_tools():
    return [
        # --- Pipeline Management ---
        Tool(
            name="list_pipelines",
            description="List all registered pipeline configurations. Each pipeline defines a complete data processing flow: source format and schema, AI-powered data quality and transformations, and destination (database, message queue, or vector store).",
            inputSchema={
                "type": "object",
                "properties": {},
            }
        ),
        Tool(
            name="get_pipeline",
            description="Get a specific pipeline configuration by name. Returns the full JSON config including source, dataQuality, transformation, preprocessor, and destination sections.",
            inputSchema={
                "type": "object",
                "properties": {
                    "pipeline": {
                        "type": "string",
                        "description": "Pipeline name"
                    }
                },
                "required": ["pipeline"]
            }
        ),
        Tool(
            name="create_pipeline",
            description="Create a pipeline by sending sample data. The schema is auto-detected from the data — you do not need to specify field names or types. Just provide the data, pipeline name, and destination type.",
            inputSchema={
                "type": "object",
                "properties": {
                    "content": {
                        "type": "string",
                        "description": "Base64-encoded sample data file content"
                    },
                    "filename": {
                        "type": "string",
                        "description": "Filename (e.g., data.csv, report.json, orders.xml)"
                    },
                    "pipeline": {
                        "type": "string",
                        "description": "Pipeline name"
                    },
                    "destination": {
                        "type": "string",
                        "enum": ["postgres", "mongodb", "qdrant", "weaviate", "milvus", "chroma", "pgvector"],
                        "description": "Destination type (default: postgres for CSV, mongodb for JSON/XML)"
                    },
                    "table": {
                        "type": "string",
                        "description": "Destination table or collection name (default: pipeline name)"
                    },
                    "database": {
                        "type": "string",
                        "description": "Destination database name (default: datris)"
                    },
                    "delimiter": {
                        "type": "string",
                        "description": "CSV delimiter (default: comma)"
                    },
                    "header": {
                        "type": "boolean",
                        "description": "Whether CSV has a header row (default: true)"
                    },
                    "codegen_rule": {
                        "type": "string",
                        "description": "Optional data quality validation rule as a plain-English instruction. Only add when the user explicitly requests validation. Datris will generate a Python validation script from this instruction and run it locally against all data. Example: 'Validate that all dates are YYYY-MM-DD format and all email addresses are valid'"
                    },
                    "codegen_transform": {
                        "type": "string",
                        "description": "Optional transformation instruction as a plain-English description. Only add when the user explicitly requests transformation. Datris will generate a Python script from this instruction and run it locally to transform all data. Example: 'Convert all date columns to YYYY/MM/DD format and uppercase all name fields'"
                    }
                },
                "required": ["content", "filename", "pipeline"]
            }
        ),
        Tool(
            name="delete_pipeline",
            description="Delete a registered pipeline configuration by name. This removes the config but does not delete any data already processed by the pipeline.",
            inputSchema={
                "type": "object",
                "properties": {
                    "pipeline": {
                        "type": "string",
                        "description": "Pipeline name to delete"
                    }
                },
                "required": ["pipeline"]
            }
        ),
        Tool(
            name="upload_data",
            description="Upload data to a registered pipeline for processing. Send the file content as a base64-encoded string. The pipeline's rules are applied: schema validation, data quality checks, transformations, then routing to the configured destination. Returns a pipeline token for tracking job status via get_job_status.",
            inputSchema={
                "type": "object",
                "properties": {
                    "content": {
                        "type": "string",
                        "description": "Base64-encoded file content"
                    },
                    "filename": {
                        "type": "string",
                        "description": "Filename (e.g., data.csv, report.json, orders.xml)"
                    },
                    "pipeline": {
                        "type": "string",
                        "description": "Pipeline name to process the data with"
                    }
                },
                "required": ["content", "filename", "pipeline"]
            }
        ),
        Tool(
            name="get_job_status",
            description="Get job status. Query by pipeline_token (from upload_data) for detailed status of a specific job, or by pipeline_name for a paginated summary of all jobs for that pipeline. Status values: RUNNING, COMPLETED, FAILED, CANCELLED. IMPORTANT: If status is RUNNING, you MUST keep polling (wait a few seconds and call again) until status changes to COMPLETED or FAILED. Do NOT proceed to query/search until the job is COMPLETED. If FAILED, read the error message and fix the issue.",
            inputSchema={
                "type": "object",
                "properties": {
                    "pipeline_token": {
                        "type": "string",
                        "description": "Pipeline token returned from upload_file"
                    },
                    "pipeline_name": {
                        "type": "string",
                        "description": "Pipeline name to get latest status for"
                    },
                    "page": {
                        "type": "integer",
                        "description": "Page number for paginated results (default: 1)"
                    }
                }
            }
        ),
        Tool(
            name="kill_job",
            description="Kill a running pipeline job by its pipeline token. The job thread will be interrupted and the job marked as cancelled.",
            inputSchema={
                "type": "object",
                "properties": {
                    "pipeline_token": {
                        "type": "string",
                        "description": "Pipeline token of the running job to kill"
                    }
                },
                "required": ["pipeline_token"]
            }
        ),
        Tool(
            name="profile_data",
            description="Send data and use AI to generate a comprehensive data profile: summary statistics per column, data quality issues detected, and suggested validation rules. Use the suggested aiRule when building a pipeline's dataQuality section.",
            inputSchema={
                "type": "object",
                "properties": {
                    "content": {
                        "type": "string",
                        "description": "Base64-encoded file content"
                    },
                    "filename": {
                        "type": "string",
                        "description": "Filename (e.g., sample.csv)"
                    },
                    "delimiter": {
                        "type": "string",
                        "description": "CSV delimiter (default: comma)"
                    },
                    "header": {
                        "type": "boolean",
                        "description": "Whether CSV has a header row (default: true)"
                    },
                    "sample_size": {
                        "type": "integer",
                        "description": "Number of rows to sample for profiling (default: 200)"
                    }
                },
                "required": ["content", "filename"]
            }
        ),
        Tool(
            name="get_version",
            description="Get the Datris server version.",
            inputSchema={
                "type": "object",
                "properties": {},
            }
        ),
        Tool(
            name="check_service_health",
            description="Check which backend services are up, down, or not configured. Returns the health status of PostgreSQL, MongoDB, MinIO, ActiveMQ, Kafka, and any configured vector databases (Qdrant, Weaviate, Milvus, Chroma, pgvector). Call this before attempting search or query operations to know which services are available.",
            inputSchema={
                "type": "object",
                "properties": {},
            }
        ),
        # --- Vector Database Search Tools ---
        Tool(
            name="search_qdrant",
            description="Semantic search across a Qdrant vector database collection. Takes a natural language query, generates an embedding, and returns the most similar document chunks with similarity scores. For RAG: pass the returned text to ai_answer with the user's question.",
            inputSchema={
                "type": "object",
                "properties": {
                    "query": {"type": "string", "description": "Natural language search query"},
                    "collection": {"type": "string", "description": "Qdrant collection name (default: financial_documents)"},
                    "top_k": {"type": "integer", "description": "Number of results to return (default: 5)"},
                },
                "required": ["query"]
            }
        ),
        Tool(
            name="search_weaviate",
            description="Semantic search across a Weaviate vector database class. Takes a natural language query, generates an embedding, and returns the most similar document chunks with similarity scores. For RAG: pass the returned text to ai_answer with the user's question.",
            inputSchema={
                "type": "object",
                "properties": {
                    "query": {"type": "string", "description": "Natural language search query"},
                    "class_name": {"type": "string", "description": "Weaviate class name (default: FinancialDocuments)"},
                    "top_k": {"type": "integer", "description": "Number of results to return (default: 5)"},
                },
                "required": ["query"]
            }
        ),
        Tool(
            name="search_milvus",
            description="Semantic search across a Milvus vector database collection. Takes a natural language query, generates an embedding, and returns the most similar document chunks with similarity scores. For RAG: pass the returned text to ai_answer with the user's question.",
            inputSchema={
                "type": "object",
                "properties": {
                    "query": {"type": "string", "description": "Natural language search query"},
                    "collection": {"type": "string", "description": "Milvus collection name (default: financial_documents)"},
                    "top_k": {"type": "integer", "description": "Number of results to return (default: 5)"},
                },
                "required": ["query"]
            }
        ),
        Tool(
            name="search_pgvector",
            description="Semantic search across a PostgreSQL pgvector table using cosine distance. Takes a natural language query, generates an embedding, and returns the most similar document chunks with similarity scores. Use list_postgres_tables with vector_only=true to discover available pgvector tables. For RAG: pass the returned text to ai_answer.",
            inputSchema={
                "type": "object",
                "properties": {
                    "query": {"type": "string", "description": "Natural language search query"},
                    "table": {"type": "string", "description": "Table name (default: financial_documents)"},
                    "schema": {"type": "string", "description": "PostgreSQL schema (default: public)"},
                    "top_k": {"type": "integer", "description": "Number of results to return (default: 5)"},
                },
                "required": ["query"]
            }
        ),
        Tool(
            name="search_chroma",
            description="Semantic search across a Chroma vector database collection. Takes a natural language query, generates an embedding, and returns the most similar document chunks with similarity scores. For RAG: pass the returned text to ai_answer with the user's question.",
            inputSchema={
                "type": "object",
                "properties": {
                    "query": {"type": "string", "description": "Natural language search query"},
                    "collection": {"type": "string", "description": "Chroma collection name (default: financial_documents)"},
                    "top_k": {"type": "integer", "description": "Number of results to return (default: 5)"},
                },
                "required": ["query"]
            }
        ),
        # --- Database Query Tools ---
        Tool(
            name="query_postgres",
            description="Execute a read-only SQL SELECT query against PostgreSQL. Use the metadata discovery tools (list_postgres_databases, list_postgres_schemas, list_postgres_tables, list_postgres_columns) first to explore available data before constructing queries. Only SELECT is allowed; LIMIT is auto-appended if missing.",
            inputSchema={
                "type": "object",
                "properties": {
                    "sql": {"type": "string", "description": "SQL SELECT query to execute"},
                    "limit": {"type": "integer", "description": "Maximum rows to return (default: 100)"},
                },
                "required": ["sql"]
            }
        ),
        Tool(
            name="query_mongodb",
            description="Query a MongoDB collection with optional filter and projection. Use list_mongodb_databases and list_mongodb_collections first to discover available data. Returns matching documents as JSON.",
            inputSchema={
                "type": "object",
                "properties": {
                    "collection": {"type": "string", "description": "MongoDB collection name"},
                    "filter": {"type": "object", "description": "MongoDB query filter (default: {})"},
                    "projection": {"type": "object", "description": "Fields to include/exclude (default: all fields)"},
                    "limit": {"type": "integer", "description": "Maximum documents to return (default: 20)"},
                },
                "required": ["collection"]
            }
        ),
        Tool(
            name="query_natural",
            description="Ask a question in natural language about data in a PostgreSQL table. The AI generates a SQL query from the question and table schema, executes it, and returns the results. Use this instead of writing SQL manually.",
            inputSchema={
                "type": "object",
                "properties": {
                    "question": {"type": "string", "description": "Natural language question about the data"},
                    "table": {"type": "string", "description": "PostgreSQL table name to query"},
                    "schema": {"type": "string", "description": "PostgreSQL schema (default: public)"},
                    "database": {"type": "string", "description": "Database name (default: datris)"},
                    "limit": {"type": "integer", "description": "Maximum rows to return (default: 100)"},
                },
                "required": ["question", "table"]
            }
        ),
        # --- Metadata Discovery Tools ---
        Tool(
            name="list_postgres_databases",
            description="List all PostgreSQL databases available in the Datris platform. Use this as the first step when exploring what data has been ingested into PostgreSQL destinations.",
            inputSchema={
                "type": "object",
                "properties": {},
            }
        ),
        Tool(
            name="list_postgres_schemas",
            description="List all schemas in a PostgreSQL database. Schemas organize tables within a database (e.g., 'public', 'analytics'). Use after list_postgres_databases to drill into a specific database.",
            inputSchema={
                "type": "object",
                "properties": {
                    "database": {"type": "string", "description": "Database name (default: datris)"},
                },
            }
        ),
        Tool(
            name="list_postgres_tables",
            description="List all tables in a PostgreSQL schema. Set vector_only=true to show only pgvector embedding tables, or false (default) to show regular data tables. Use after list_postgres_schemas.",
            inputSchema={
                "type": "object",
                "properties": {
                    "database": {"type": "string", "description": "Database name (default: datris)"},
                    "schema": {"type": "string", "description": "Schema name (default: public)"},
                    "vector_only": {"type": "boolean", "description": "If true, only return tables with an embedding column (pgvector tables). Default: false"},
                },
            }
        ),
        Tool(
            name="list_postgres_columns",
            description="List all columns and their data types for a specific PostgreSQL table. Use this to understand table structure before writing a query_postgres SQL query.",
            inputSchema={
                "type": "object",
                "properties": {
                    "database": {"type": "string", "description": "Database name (default: datris)"},
                    "schema": {"type": "string", "description": "Schema name (default: public)"},
                    "table": {"type": "string", "description": "Table name"},
                },
                "required": ["table"]
            }
        ),
        Tool(
            name="list_mongodb_databases",
            description="List all MongoDB databases available in the Datris platform. Use this as the first step when exploring what data has been ingested into MongoDB destinations.",
            inputSchema={
                "type": "object",
                "properties": {},
            }
        ),
        Tool(
            name="list_mongodb_collections",
            description="List MongoDB collections. If database is specified, lists collections in that database. If omitted, lists all collections across all databases in 'db.collection' format.",
            inputSchema={
                "type": "object",
                "properties": {
                    "database": {"type": "string", "description": "Database name (optional; omit to list from all databases)"},
                },
            }
        ),
        # --- Vector Store Metadata ---
        Tool(
            name="list_qdrant_collections",
            description="List all collections in the Qdrant vector database. Use this to discover available collections before running search_qdrant.",
            inputSchema={
                "type": "object",
                "properties": {},
            }
        ),
        Tool(
            name="list_weaviate_classes",
            description="List all classes in the Weaviate vector database. Use this to discover available classes before running search_weaviate.",
            inputSchema={
                "type": "object",
                "properties": {},
            }
        ),
        Tool(
            name="list_milvus_collections",
            description="List all collections in the Milvus vector database. Use this to discover available collections before running search_milvus.",
            inputSchema={
                "type": "object",
                "properties": {},
            }
        ),
        Tool(
            name="list_chroma_collections",
            description="List all collections in the Chroma vector database. Use this to discover available collections before running search_chroma.",
            inputSchema={
                "type": "object",
                "properties": {},
            }
        ),
        Tool(
            name="list_pgvector_collections",
            description="List all pgvector tables (tables with an embedding column) in PostgreSQL. Use this to discover available collections before running search_pgvector.",
            inputSchema={
                "type": "object",
                "properties": {},
            }
        ),
        # --- AI Tools ---
        Tool(
            name="ai_answer",
            description="Ask the Datris AI to answer a question based on provided context. Ideal for RAG workflows: first retrieve relevant chunks using a search tool (search_qdrant, search_pgvector, etc.), then pass the retrieved text as context along with the user's question to get a synthesized answer.",
            inputSchema={
                "type": "object",
                "properties": {
                    "query": {"type": "string", "description": "The question to answer"},
                    "context": {"type": "string", "description": "Context text to base the answer on (e.g., retrieved document chunks)"},
                },
                "required": ["query", "context"]
            }
        ),
        # --- Configuration Tools ---
        Tool(
            name="upload_config",
            description="Upload a configuration file to the Datris platform. Supports 'validation-schema' (JSON Schema files used in pipeline dataQuality schema validation). Send the file content as base64.",
            inputSchema={
                "type": "object",
                "properties": {
                    "content": {"type": "string", "description": "Base64-encoded file content"},
                    "filename": {"type": "string", "description": "Filename (e.g., schema.json, transform.js)"},
                    "type": {"type": "string", "enum": ["validation-schema"], "description": "Config file type: 'validation-schema' for JSON Schema"},
                },
                "required": ["content", "filename", "type"]
            }
        ),
        # --- Secrets ---
        Tool(
            name="update_secret",
            description="Update an AI provider secret in the Datris platform. Use this to configure your AI API keys so Datris can use AI features (data profiling, schema generation, AI transformations, RAG). Only AI-related secrets can be updated: anthropic, openai, ollama, embedding.",
            inputSchema={
                "type": "object",
                "properties": {
                    "name": {
                        "type": "string",
                        "enum": ["anthropic", "openai", "ollama", "embedding"],
                        "description": "Secret name: anthropic, openai, ollama, or embedding"
                    },
                    "fields": {
                        "type": "object",
                        "description": "Key-value fields to set. Typical fields: endpoint (API URL), model (model name), apiKey (API key)"
                    },
                },
                "required": ["name", "fields"]
            }
        ),
    ]


@server.call_tool()
async def call_tool(name: str, arguments: dict[str, Any]) -> list[TextContent]:
    try:
        result = _dispatch(name, arguments)
        return [TextContent(type="text", text=result)]
    except Exception as e:
        return [TextContent(type="text", text=json.dumps({"error": str(e)}))]


def _dispatch(name: str, args: dict) -> str:
    # --- Pipeline Management ---
    if name == "list_pipelines":
        result = _call("get", "/api/v1/pipelines")
        try:
            pipelines = json.loads(result)
            if not pipelines or (isinstance(pipelines, list) and len(pipelines) == 0):
                return json.dumps({"pipelines": [], "message": "No pipelines exist. You MUST create a pipeline before you can ingest or query data. Call create_pipeline with your sample data (base64-encoded), pipeline name, and destination type."})
        except (json.JSONDecodeError, TypeError):
            pass
        return result

    elif name == "get_pipeline":
        return _call("get", "/api/v1/pipeline", params={"pipeline": args["pipeline"]})

    elif name == "create_pipeline":
        # Step 1: Generate schema from sample data
        pipeline_name = args["pipeline"]
        gen_data = {"pipeline": pipeline_name, "allStrings": "true"}
        if args.get("delimiter"):
            gen_data["delimiter"] = args["delimiter"]
        header = args.get("header", True)
        gen_data["header"] = str(header).lower()
        gen_result = _upload_content("/api/v1/pipeline/generate", args["content"], args["filename"], gen_data)

        # Step 2: Parse the generated config and set destination
        try:
            config = json.loads(gen_result)
        except json.JSONDecodeError:
            return json.dumps({"error": "Failed to generate schema: " + gen_result})

        table_name = args.get("table", pipeline_name)
        db_name = args.get("database", "datris")
        dest_type = args.get("destination", "")

        # Auto-detect destination if not specified
        if not dest_type:
            filename = args.get("filename", "").lower()
            if filename.endswith(".json"):
                dest_type = "mongodb"
            elif filename.endswith(".xml"):
                dest_type = "postgres"
            elif filename.endswith(".pdf") or filename.endswith(".docx") or filename.endswith(".txt"):
                dest_type = "pgvector"
            else:
                dest_type = "postgres"

        # Build destination
        dest = {}
        if dest_type == "postgres":
            dest["database"] = {"dbName": db_name, "schema": "public", "table": table_name, "usePostgres": True}
        elif dest_type == "mongodb":
            dest["database"] = {"dbName": db_name, "table": table_name, "useMongoDB": True}
        elif dest_type == "qdrant":
            dest["qdrant"] = {"collectionName": table_name, "embeddingSecretName": "oss/embedding", "qdrantSecretName": "oss/qdrant", "chunking": {"strategy": "recursive", "chunkSize": 500, "chunkOverlap": 50}}
        elif dest_type == "weaviate":
            dest["weaviate"] = {"className": table_name, "embeddingSecretName": "oss/embedding", "weaviateSecretName": "oss/weaviate", "chunking": {"strategy": "recursive", "chunkSize": 500, "chunkOverlap": 50}}
        elif dest_type == "milvus":
            dest["milvus"] = {"collectionName": table_name, "embeddingSecretName": "oss/embedding", "milvusSecretName": "oss/milvus", "chunking": {"strategy": "recursive", "chunkSize": 500, "chunkOverlap": 50}}
        elif dest_type == "chroma":
            dest["chroma"] = {"collectionName": table_name, "embeddingSecretName": "oss/embedding", "chromaSecretName": "oss/chroma", "chunking": {"strategy": "recursive", "chunkSize": 500, "chunkOverlap": 50}}
        elif dest_type == "pgvector":
            dest["pgvector"] = {"tableName": table_name, "schemaName": "public", "embeddingSecretName": "oss/embedding", "postgresSecretName": "oss/pgvector", "chunking": {"strategy": "recursive", "chunkSize": 500, "chunkOverlap": 50}}
        else:
            dest["database"] = {"dbName": db_name, "schema": "public", "table": table_name, "usePostgres": True}

        config["destination"] = dest

        # Step 2b: Add optional CodeGen data quality rule
        if args.get("codegen_rule"):
            config["dataQuality"] = {"aiRule": {"instruction": args["codegen_rule"], "onFailureIsError": True}}

        # Step 2c: Add optional CodeGen transformation
        if args.get("codegen_transform"):
            config["transformation"] = {"aiTransformation": {"instruction": args["codegen_transform"]}}

        # Step 3: Register the pipeline
        create_result = _call("post", "/api/v1/pipeline", json=config)

        # Check if registration failed
        if create_result and ("Exception" in create_result or "error" in create_result.lower()):
            return json.dumps({"error": "Failed to register pipeline: " + create_result[:500]})

        # Verify the pipeline was actually created by reading it back
        verify = _call("get", "/api/v1/pipeline", params={"pipeline": pipeline_name})
        if verify and "not configured" in verify.lower():
            return json.dumps({"error": "Pipeline registration failed silently. Generated config may be invalid.", "config": str(config)[:500]})

        actual_name = config.get("name", pipeline_name)
        return json.dumps({"status": "Pipeline created", "pipeline": actual_name, "destination": dest_type, "table": table_name})

    elif name == "delete_pipeline":
        return _call("delete", "/api/v1/pipeline", params={"pipeline": args["pipeline"]})

    elif name == "upload_data":
        data = {"pipeline": args["pipeline"]}
        result = _upload_content("/api/v1/pipeline/upload", args["content"], args["filename"], data)
        token = result.strip() if result else ""
        if token and not token.startswith("{"):
            return json.dumps({"pipelineToken": token, "message": "Upload successful. Use this pipelineToken with get_job_status to monitor processing."})
        return result

    elif name == "get_job_status":
        params = {}
        if args.get("pipeline_token"):
            params["pipelinetoken"] = args["pipeline_token"]
        if args.get("pipeline_name"):
            params["pipelinename"] = args["pipeline_name"]
        if args.get("page"):
            params["page"] = args["page"]
        result = _call("get", "/api/v1/pipeline/status", params=params)
        try:
            data = json.loads(result)
            # Detect status from either detail (state field) or summary (status field)
            status = None
            if isinstance(data, list) and len(data) > 0:
                last = data[-1] if isinstance(data[-1], dict) else data[0]
                # Detail view uses "state" (begin/processing/end/error)
                # Summary view uses "status" (processing/success/completed/error)
                status = last.get("status") or last.get("state")
            elif isinstance(data, dict):
                status = data.get("status") or data.get("state")

            if status:
                s = status.lower()
                if s in ("processing", "begin"):
                    return "STILL RUNNING — you MUST call get_job_status again in a few seconds. Do NOT proceed to query/search until job is complete.\n\n" + result
                elif s == "error":
                    return "JOB FAILED — read the error details below. Delete the pipeline and recreate if needed.\n\n" + result
        except (json.JSONDecodeError, TypeError, KeyError):
            pass
        return result

    elif name == "kill_job":
        payload = {"pipelineToken": args["pipeline_token"]}
        return _call("post", "/api/v1/job/kill", json=payload)

    elif name == "profile_data":
        data = {}
        if args.get("delimiter"):
            data["delimiter"] = args["delimiter"]
        if args.get("header") is not None:
            data["header"] = str(args["header"]).lower()
        if args.get("sample_size"):
            data["sampleSize"] = str(args["sample_size"])
        return _upload_content("/api/v1/pipeline/profile", args["content"], args["filename"], data)

    elif name == "get_version":
        return _call("get", "/api/v1/version")

    elif name == "check_service_health":
        return _call("get", "/api/v1/health/services")

    # --- Vector Database Search (via REST API) ---
    elif name == "search_qdrant":
        payload = {"query": args["query"]}
        if args.get("collection"):
            payload["collection"] = args["collection"]
        if args.get("top_k"):
            payload["topK"] = args["top_k"]
        return _call("post", "/api/v1/search/qdrant", json=payload)

    elif name == "search_weaviate":
        payload = {"query": args["query"]}
        if args.get("class_name"):
            payload["className"] = args["class_name"]
        if args.get("top_k"):
            payload["topK"] = args["top_k"]
        return _call("post", "/api/v1/search/weaviate", json=payload)

    elif name == "search_milvus":
        payload = {"query": args["query"]}
        if args.get("collection"):
            payload["collection"] = args["collection"]
        if args.get("top_k"):
            payload["topK"] = args["top_k"]
        return _call("post", "/api/v1/search/milvus", json=payload)

    elif name == "search_pgvector":
        payload = {"query": args["query"]}
        if args.get("table"):
            payload["table"] = args["table"]
        if args.get("schema"):
            payload["schema"] = args["schema"]
        if args.get("top_k"):
            payload["topK"] = args["top_k"]
        return _call("post", "/api/v1/search/pgvector", json=payload)

    elif name == "search_chroma":
        payload = {"query": args["query"]}
        if args.get("collection"):
            payload["collection"] = args["collection"]
        if args.get("top_k"):
            payload["topK"] = args["top_k"]
        return _call("post", "/api/v1/search/chroma", json=payload)

    # --- Database Queries (via REST API) ---
    elif name == "query_postgres":
        payload = {"sql": args["sql"]}
        if args.get("limit"):
            payload["limit"] = args["limit"]
        return _call("post", "/api/v1/query/postgres", json=payload)

    elif name == "query_mongodb":
        payload = {"collection": args["collection"]}
        if args.get("filter"):
            payload["filter"] = args["filter"]
        if args.get("projection"):
            payload["projection"] = args["projection"]
        if args.get("limit"):
            payload["limit"] = args["limit"]
        return _call("post", "/api/v1/query/mongodb", json=payload)

    elif name == "query_natural":
        payload = {"question": args["question"], "table": args["table"]}
        if args.get("schema"):
            payload["schema"] = args["schema"]
        if args.get("database"):
            payload["database"] = args["database"]
        if args.get("limit"):
            payload["limit"] = args["limit"]
        return _call("post", "/api/v1/query/natural", json=payload)

    # --- Metadata Discovery (via REST API) ---
    elif name == "list_postgres_databases":
        return _call("get", "/api/v1/metadata/postgres/databases")

    elif name == "list_postgres_schemas":
        params = {}
        if args.get("database"):
            params["database"] = args["database"]
        return _call("get", "/api/v1/metadata/postgres/schemas", params=params)

    elif name == "list_postgres_tables":
        params = {}
        if args.get("database"):
            params["database"] = args["database"]
        if args.get("schema"):
            params["schema"] = args["schema"]
        if args.get("vector_only") is not None:
            params["vectorOnly"] = str(args["vector_only"]).lower()
        return _call("get", "/api/v1/metadata/postgres/tables", params=params)

    elif name == "list_postgres_columns":
        params = {"table": args["table"]}
        if args.get("database"):
            params["database"] = args["database"]
        if args.get("schema"):
            params["schema"] = args["schema"]
        return _call("get", "/api/v1/metadata/postgres/columns", params=params)

    elif name == "list_mongodb_databases":
        return _call("get", "/api/v1/metadata/mongodb/databases")

    elif name == "list_mongodb_collections":
        params = {}
        if args.get("database"):
            params["database"] = args["database"]
        return _call("get", "/api/v1/metadata/mongodb/collections", params=params)

    # --- Vector Store Metadata ---
    elif name == "list_qdrant_collections":
        return _call("get", "/api/v1/metadata/qdrant/collections")

    elif name == "list_weaviate_classes":
        return _call("get", "/api/v1/metadata/weaviate/classes")

    elif name == "list_milvus_collections":
        return _call("get", "/api/v1/metadata/milvus/collections")

    elif name == "list_chroma_collections":
        return _call("get", "/api/v1/metadata/chroma/collections")

    elif name == "list_pgvector_collections":
        return _call("get", "/api/v1/metadata/postgres/tables", params={"vectorOnly": "true"})

    # --- AI ---
    elif name == "ai_answer":
        payload = {"query": args["query"], "context": args["context"]}
        return _call("post", "/api/v1/ai/answer", json=payload)

    # --- Config ---
    elif name == "upload_config":
        data = {"type": args["type"]}
        return _upload_content("/api/v1/config/upload", args["content"], args["filename"], data)

    # --- Secrets ---
    elif name == "update_secret":
        allowed = {"anthropic", "openai", "ollama", "embedding"}
        secret_name = args["name"]
        if secret_name not in allowed:
            return json.dumps({"error": f"Only these secrets can be updated: {', '.join(sorted(allowed))}"})
        return _call("put", f"/api/v1/secrets/{secret_name}", json=args["fields"])

    else:
        return json.dumps({"error": f"Unknown tool: {name}"})


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

async def run_stdio():
    from mcp.server.stdio import stdio_server
    async with stdio_server() as (read_stream, write_stream):
        await server.run(read_stream, write_stream, server.create_initialization_options())


async def run_sse(port: int):
    from mcp.server.sse import SseServerTransport
    import uvicorn

    sse = SseServerTransport("/messages")

    async def app(scope, receive, send):
        if scope["type"] == "http":
            path = scope.get("path", "")
            if path == "/sse":
                async with sse.connect_sse(scope, receive, send) as streams:
                    await server.run(streams[0], streams[1], server.create_initialization_options())
                return
            elif path == "/messages":
                await sse.handle_post_message(scope, receive, send)
                return
        # 404 for anything else
        await send({"type": "http.response.start", "status": 404, "headers": []})
        await send({"type": "http.response.body", "body": b"Not Found"})

    config = uvicorn.Config(app, host="0.0.0.0", port=port)
    srv = uvicorn.Server(config)
    await srv.serve()


def main():
    import asyncio

    parser = argparse.ArgumentParser(description="Datris MCP Server")
    parser.add_argument("--sse", action="store_true", help="Run in SSE mode (default: stdio)")
    parser.add_argument("--port", type=int, default=3000, help="SSE port (default: 3000)")
    args = parser.parse_args()

    if args.sse:
        asyncio.run(run_sse(args.port))
    else:
        asyncio.run(run_stdio())


if __name__ == "__main__":
    main()
