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

A pipeline defines a complete data processing flow:
  - Source: file format (CSV, JSON, XML), schema with field names and types
  - Data Quality: validation rules including regex patterns, AI-powered rules, and JSON Schema validation
  - Transformation: deduplication, trimming, AI transforms, and JavaScript row functions
  - Preprocessor: optional REST endpoint called before processing
  - Destination: PostgreSQL, MongoDB, Kafka, ActiveMQ, REST endpoint, or vector databases (Qdrant, Weaviate, Milvus, Chroma, pgvector) with embedding configuration

Recommended workflow for agents:
  1. Discover existing data: use metadata tools (list_postgres_databases, list_postgres_tables, etc.) to explore what's available
  2. Create a pipeline: use generate_schema to auto-create from a sample file, or create_pipeline manually
  3. Profile data: use profile_data to get AI-suggested data quality rules before ingestion
  4. Ingest data: use upload_file to process files through the pipeline
  5. Monitor: use get_job_status to track processing (status: RUNNING, COMPLETED, FAILED, CANCELLED)
  6. Query & search: use query tools for structured data, search tools for semantic/vector search
  7. RAG: combine search results with ai_answer for AI-powered question answering
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
    """Upload a file via multipart POST to the pipeline API."""
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


# ---------------------------------------------------------------------------
# MCP Resources
# ---------------------------------------------------------------------------

PIPELINE_CONFIG_REFERENCE = """\
# Datris Pipeline Configuration Reference

A pipeline defines a complete data processing flow. The config JSON has these top-level sections:

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

Defines the input file format and schema.

### fileAttributes (choose one)

**csvAttributes** — for CSV files:
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

**unstructuredAttributes** — for PDFs, DOCX, TXT (used with vector DB destinations):
```json
"unstructuredAttributes": {
  "fileExtension": "pdf",
  "preserveFilename": true
}
```

### schemaProperties (required for structured data, omit for unstructured)

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

A REST endpoint called before processing each file. Use for custom validation or enrichment.

```json
"preprocessor": {
  "endpoint": "https://my-service.example.com/preprocess",
  "bearerToken": "token123",
  "apiKey": "key123",
  "timeoutSeconds": 300,
  "async": false
}
```

## Data Quality (optional)

Validation rules applied to data before transformation and destination.

### columnRules — regex or function-based validation per column

```json
"columnRules": [
  {
    "columnName": "email",
    "function": "regex",
    "parameter": "^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\\\.[a-zA-Z]{2,}$",
    "onFailureIsError": true,
    "description": "Must be a valid email"
  },
  {
    "columnName": "price",
    "function": "regex",
    "parameter": "^[0-9]+(\\\\.[0-9]+)?$",
    "onFailureIsError": false,
    "description": "Must be a positive number"
  }
]
```

### aiRule — AI-powered validation using natural language

```json
"aiRule": {
  "instruction": "All price columns must be positive and not exceed $1,000,000. Volume must be a positive integer.",
  "onFailureIsError": false,
  "sample": true,
  "sampleSize": 200
}
```

### validationSchema — JSON Schema file reference

```json
"validationSchema": "my-schema.json"
```

Upload the schema file first using the `upload_config` tool with type "validation-schema".

### validateFileHeader — check CSV headers match schema

```json
"validateFileHeader": true
```

## Transformation (optional)

Transform data after validation, before writing to destination.

### Basic transformations

```json
"transformation": {
  "trimColumnWhitespace": true,
  "deduplicate": true
}
```

### rowFunctions — JavaScript-based row transformations

```json
"rowFunctions": [
  {
    "function": "javascript",
    "parameters": ["transform.js"]
  }
]
```

Upload the JS file first using the `upload_config` tool with type "javascript".

### aiTransformation — AI-powered data transformation

```json
"aiTransformation": {
  "instruction": "Convert all date values from MM/DD/YYYY to YYYY-MM-DD format",
  "sample": true,
  "sampleSize": 200
}
```

## Destination (required — choose one)

### database — PostgreSQL

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

### database — MongoDB

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
    "timeoutSeconds": 300
  }
}
```

### Vector databases (for RAG / semantic search)

All vector DB destinations share a common structure with chunking and embedding config. Use with unstructured source files (PDF, DOCX, TXT).

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

## Complete Examples

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
            description="Complete reference for building Datris pipeline configurations. Covers all source types (CSV, JSON, XML, PDF), data quality rules (regex, AI), transformations, and all destination types (PostgreSQL, MongoDB, Kafka, vector databases). Read this before using create_pipeline.",
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
            description="List all registered pipeline configurations. Each pipeline defines a complete data processing flow: source format and schema, data quality rules (regex + AI), transformations, and destination (database, message queue, or vector store).",
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
            description="Create or update a pipeline configuration. The config JSON must include 'name' and typically includes: source (fileAttributes, schemaProperties with field definitions), dataQuality (column rules with regex, AI rules, schema validation), transformation (deduplication, trimming, AI transforms, row functions), and destination (database, objectStore, kafka, activeMQ, restEndpoint, or vector DB like qdrant/weaviate/milvus/chroma/pgvector). Read the 'Pipeline Configuration Reference' resource for full field details and examples. Use generate_schema to auto-create a config from a sample file.",
            inputSchema={
                "type": "object",
                "properties": {
                    "config": {
                        "type": "object",
                        "description": "Full pipeline configuration JSON including name, source, destination, dataQuality, transformation"
                    }
                },
                "required": ["config"]
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
            name="upload_file",
            description="Upload a data file (CSV, JSON, XML, or compressed archive) to a registered pipeline for processing. The pipeline's rules are applied: schema validation, data quality checks, transformations, then routing to the configured destination. Returns a pipeline token for tracking job status via get_job_status.",
            inputSchema={
                "type": "object",
                "properties": {
                    "file_path": {
                        "type": "string",
                        "description": "Absolute path to the file to upload"
                    },
                    "pipeline": {
                        "type": "string",
                        "description": "Pipeline name to process the file with"
                    }
                },
                "required": ["file_path", "pipeline"]
            }
        ),
        Tool(
            name="get_job_status",
            description="Get job status. Query by pipeline_token (from upload_file) for detailed status of a specific job, or by pipeline_name for a paginated summary of all jobs for that pipeline. Status values: RUNNING, COMPLETED, FAILED, CANCELLED.",
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
            name="generate_schema",
            description="Upload a sample data file and use AI to automatically generate a complete pipeline configuration with inferred field names, data types, and schema. Supports CSV, JSON, and XML. This is the fastest way to create a new pipeline — generate the config, review/modify it, then register it with create_pipeline.",
            inputSchema={
                "type": "object",
                "properties": {
                    "file_path": {
                        "type": "string",
                        "description": "Absolute path to the file to analyze"
                    },
                    "pipeline": {
                        "type": "string",
                        "description": "Pipeline name for the generated configuration"
                    },
                    "delimiter": {
                        "type": "string",
                        "description": "CSV delimiter (default: comma)"
                    },
                    "header": {
                        "type": "boolean",
                        "description": "Whether CSV has a header row (default: true)"
                    }
                },
                "required": ["file_path", "pipeline"]
            }
        ),
        Tool(
            name="profile_data",
            description="Upload a data file and use AI to generate a comprehensive data profile: summary statistics per column, data quality issues detected, and suggested validation rules (regex patterns and AI-powered rules). Use the suggested rules when building a pipeline's dataQuality section.",
            inputSchema={
                "type": "object",
                "properties": {
                    "file_path": {
                        "type": "string",
                        "description": "Absolute path to the file to profile"
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
                "required": ["file_path"]
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
            description="Upload a configuration file to the Datris platform. Supports two types: 'validation-schema' (JSON Schema files used in pipeline dataQuality schema validation) and 'javascript' (JS files used in pipeline transformation row functions). The file is stored and can be referenced by pipeline configurations.",
            inputSchema={
                "type": "object",
                "properties": {
                    "file_path": {"type": "string", "description": "Absolute path to the configuration file to upload"},
                    "type": {"type": "string", "enum": ["validation-schema", "javascript"], "description": "Config file type: 'validation-schema' for JSON Schema or 'javascript' for transformation scripts"},
                },
                "required": ["file_path", "type"]
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
        return _call("get", "/api/v1/pipelines")

    elif name == "get_pipeline":
        return _call("get", "/api/v1/pipeline", params={"pipeline": args["pipeline"]})

    elif name == "create_pipeline":
        return _call("post", "/api/v1/pipeline", json=args["config"])

    elif name == "delete_pipeline":
        return _call("delete", "/api/v1/pipeline", params={"pipeline": args["pipeline"]})

    elif name == "upload_file":
        data = {"pipeline": args["pipeline"]}
        return _upload("/api/v1/pipeline/upload", args["file_path"], data)

    elif name == "get_job_status":
        params = {}
        if args.get("pipeline_token"):
            params["pipelinetoken"] = args["pipeline_token"]
        if args.get("pipeline_name"):
            params["pipelinename"] = args["pipeline_name"]
        if args.get("page"):
            params["page"] = args["page"]
        return _call("get", "/api/v1/pipeline/status", params=params)

    elif name == "kill_job":
        payload = {"pipelineToken": args["pipeline_token"]}
        return _call("post", "/api/v1/job/kill", json=payload)

    elif name == "generate_schema":
        data = {"pipeline": args["pipeline"]}
        if args.get("delimiter"):
            data["delimiter"] = args["delimiter"]
        if args.get("header") is not None:
            data["header"] = str(args["header"]).lower()
        return _upload("/api/v1/pipeline/generate", args["file_path"], data)

    elif name == "profile_data":
        data = {}
        if args.get("delimiter"):
            data["delimiter"] = args["delimiter"]
        if args.get("header") is not None:
            data["header"] = str(args["header"]).lower()
        if args.get("sample_size"):
            data["sampleSize"] = str(args["sample_size"])
        return _upload("/api/v1/pipeline/profile", args["file_path"], data)

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
        return _upload("/api/v1/config/upload", args["file_path"], data)

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
    from starlette.applications import Starlette
    from starlette.routing import Route
    import uvicorn

    sse = SseServerTransport("/messages")

    async def handle_sse(request):
        async with sse.connect_sse(request.scope, request.receive, request._send) as streams:
            await server.run(streams[0], streams[1], server.create_initialization_options())

    async def handle_messages(request):
        await sse.handle_post_message(request.scope, request.receive, request._send)

    app = Starlette(routes=[
        Route("/sse", endpoint=handle_sse),
        Route("/messages", endpoint=handle_messages, methods=["POST"]),
    ])

    config = uvicorn.Config(app, host="0.0.0.0", port=port)
    srv = uvicorn.Server(config)
    await srv.serve()


if __name__ == "__main__":
    import asyncio

    parser = argparse.ArgumentParser(description="Datris MCP Server")
    parser.add_argument("--sse", action="store_true", help="Run in SSE mode (default: stdio)")
    parser.add_argument("--port", type=int, default=3000, help="SSE port (default: 3000)")
    args = parser.parse_args()

    if args.sse:
        asyncio.run(run_sse(args.port))
    else:
        asyncio.run(run_stdio())
