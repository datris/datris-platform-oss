#!/usr/bin/env python3
"""
Datris MCP Server

Exposes the Datris REST API as MCP tools so AI agents (Claude, Cursor, etc.)
can natively interact with the pipeline — upload files, register datasets, monitor jobs,
profile data, and manage configurations.

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
import sys
from typing import Any

import requests
from dotenv import load_dotenv
from mcp.server import Server
from mcp.types import Tool, TextContent

load_dotenv()

PIPELINE_URL = os.getenv("PIPELINE_URL", "http://localhost:8080")
PIPELINE_API_KEY = os.getenv("PIPELINE_API_KEY", "")

# Embedding config (for vector search tools)
EMBEDDING_PROVIDER = os.getenv("EMBEDDING_PROVIDER", "openai")
EMBEDDING_MODEL = os.getenv("EMBEDDING_MODEL", "text-embedding-3-small")
EMBEDDING_ENDPOINT = os.getenv("EMBEDDING_ENDPOINT", "http://localhost:11434")

server = Server("datris")


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


# ---------------------------------------------------------------------------
# MCP Tools
# ---------------------------------------------------------------------------

@server.list_tools()
async def list_tools():
    return [
        Tool(
            name="list_pipelines",
            description="List all registered pipeline configurations in the pipeline",
            inputSchema={
                "type": "object",
                "properties": {},
            }
        ),
        Tool(
            name="get_pipeline",
            description="Get a specific pipeline configuration by name",
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
            description="Register or update a pipeline configuration. Pass the full pipeline JSON config.",
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
            description="Delete a registered pipeline configuration",
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
            description="Upload a file for processing by a registered dataset. Returns a pipeline token for tracking.",
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
            description="Get the status of a pipeline job by pipeline token or pipeline name",
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
            description="Upload a file and use AI to automatically generate a pipeline configuration (schema, field names, types). Supports CSV, JSON, XML.",
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
            description="Upload a file and use AI to generate a data profile — summary statistics, quality issues, and suggested data quality rules (regex and AI rules).",
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
            description="Get the pipeline server version",
            inputSchema={
                "type": "object",
                "properties": {},
            }
        ),
        # --- Vector Database Search Tools ---
        Tool(
            name="search_qdrant",
            description="Semantic search across a Qdrant vector database collection. Finds document chunks most similar to your natural language query.",
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
            description="Semantic search across a Weaviate vector database class. Finds document chunks most similar to your natural language query.",
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
            description="Semantic search across a Milvus vector database collection. Finds document chunks most similar to your natural language query.",
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
            description="Semantic search across a PostgreSQL pgvector table. Finds document chunks most similar to your natural language query using cosine distance.",
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
            description="Semantic search across a Chroma vector database collection. Finds document chunks most similar to your natural language query.",
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
            description="Execute a read-only SQL query against PostgreSQL. Returns results as JSON. Only SELECT queries are allowed.",
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
            description="Query a MongoDB collection. Returns matching documents as JSON.",
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
    ]


@server.call_tool()
async def call_tool(name: str, arguments: dict[str, Any]) -> list[TextContent]:
    try:
        result = _dispatch(name, arguments)
        return [TextContent(type="text", text=result)]
    except Exception as e:
        return [TextContent(type="text", text=json.dumps({"error": str(e)}))]


def _dispatch(name: str, args: dict) -> str:
    if name == "list_pipelines":
        return _call("get", "/api/v1/pipelines")

    elif name == "get_pipeline":
        return _call("get", f"/api/v1/pipeline", params={"pipeline": args["pipeline"]})

    elif name == "create_pipeline":
        return _call("post", "/api/v1/pipeline", json=args["config"])

    elif name == "delete_pipeline":
        return _call("delete", f"/api/v1/pipeline", params={"pipeline": args["pipeline"]})

    elif name == "upload_file":
        file_path = args["file_path"]
        dataset = args["pipeline"]
        with open(file_path, "rb") as f:
            files = {"file": (os.path.basename(file_path), f)}
            data = {"pipeline": dataset}
            h = {}
            if PIPELINE_API_KEY:
                h["x-api-key"] = PIPELINE_API_KEY
            resp = requests.post(
                f"{PIPELINE_URL}/api/v1/pipeline/upload",
                headers=h,
                files=files,
                data=data,
                timeout=300
            )
            return resp.text

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
        file_path = args["file_path"]
        with open(file_path, "rb") as f:
            files = {"file": (os.path.basename(file_path), f)}
            data = {"pipeline": args["pipeline"]}
            if args.get("delimiter"):
                data["delimiter"] = args["delimiter"]
            if args.get("header") is not None:
                data["header"] = str(args["header"]).lower()
            h = {}
            if PIPELINE_API_KEY:
                h["x-api-key"] = PIPELINE_API_KEY
            resp = requests.post(
                f"{PIPELINE_URL}/api/v1/pipeline/generate",
                headers=h,
                files=files,
                data=data,
                timeout=300
            )
            return resp.text

    elif name == "profile_data":
        file_path = args["file_path"]
        with open(file_path, "rb") as f:
            files = {"file": (os.path.basename(file_path), f)}
            data = {}
            if args.get("delimiter"):
                data["delimiter"] = args["delimiter"]
            if args.get("header") is not None:
                data["header"] = str(args["header"]).lower()
            if args.get("sample_size"):
                data["sampleSize"] = str(args["sample_size"])
            h = {}
            if PIPELINE_API_KEY:
                h["x-api-key"] = PIPELINE_API_KEY
            resp = requests.post(
                f"{PIPELINE_URL}/api/v1/pipeline/profile",
                headers=h,
                files=files,
                data=data,
                timeout=300
            )
            return resp.text

    elif name == "get_version":
        return _call("get", "/api/v1/version")

    # --- Vector Database Search ---
    elif name == "search_qdrant":
        return _search_qdrant(args)
    elif name == "search_weaviate":
        return _search_weaviate(args)
    elif name == "search_milvus":
        return _search_milvus(args)
    elif name == "search_pgvector":
        return _search_pgvector(args)
    elif name == "search_chroma":
        return _search_chroma(args)

    # --- Database Queries ---
    elif name == "query_postgres":
        return _query_postgres(args)
    elif name == "query_mongodb":
        return _query_mongodb(args)

    else:
        return json.dumps({"error": f"Unknown tool: {name}"})


# ---------------------------------------------------------------------------
# Embedding
# ---------------------------------------------------------------------------

def _get_embedding(text):
    """Generate an embedding vector for a text query."""
    if EMBEDDING_PROVIDER == "openai":
        import openai
        client = openai.OpenAI()
        response = client.embeddings.create(input=text, model=EMBEDDING_MODEL)
        return response.data[0].embedding
    elif EMBEDDING_PROVIDER == "ollama":
        response = requests.post(
            f"{EMBEDDING_ENDPOINT}/api/embeddings",
            json={"model": EMBEDDING_MODEL, "prompt": text}
        )
        response.raise_for_status()
        return response.json()["embedding"]
    else:
        raise ValueError(f"Unknown embedding provider: {EMBEDDING_PROVIDER}")


def _format_results(results):
    """Format search results as JSON."""
    return json.dumps(results, indent=2, default=str)


# ---------------------------------------------------------------------------
# Vector Database Search Implementations
# ---------------------------------------------------------------------------

def _search_qdrant(args):
    from qdrant_client import QdrantClient

    host = os.getenv("QDRANT_HOST", "localhost")
    port = int(os.getenv("QDRANT_PORT", "6333"))
    collection = args.get("collection", "financial_documents")
    top_k = args.get("top_k", 5)

    embedding = _get_embedding(args["query"])
    client = QdrantClient(host=host, port=port)
    results = client.query_points(collection_name=collection, query=embedding, limit=top_k)

    formatted = []
    for point in results.points:
        entry = dict(point.payload)
        entry["_score"] = point.score
        formatted.append(entry)
    return _format_results(formatted)


def _search_weaviate(args):
    import weaviate
    import weaviate.classes.query as wq

    host = os.getenv("WEAVIATE_HOST", "localhost")
    port = int(os.getenv("WEAVIATE_PORT", "8079"))
    grpc_port = int(os.getenv("WEAVIATE_GRPC_PORT", "50051"))
    scheme = os.getenv("WEAVIATE_SCHEME", "http")
    class_name = args.get("class_name", "FinancialDocuments")
    top_k = args.get("top_k", 5)

    embedding = _get_embedding(args["query"])

    client = weaviate.connect_to_custom(
        http_host=host, http_port=port, http_secure=(scheme == "https"),
        grpc_host=host, grpc_port=grpc_port, grpc_secure=(scheme == "https"),
    )
    try:
        collection = client.collections.get(class_name)
        response = collection.query.near_vector(
            near_vector=embedding, limit=top_k,
            return_metadata=wq.MetadataQuery(distance=True),
        )
        formatted = []
        for obj in response.objects:
            entry = dict(obj.properties)
            distance = obj.metadata.distance if obj.metadata.distance is not None else 0
            entry["_score"] = 1.0 - float(distance)
            formatted.append(entry)
        return _format_results(formatted)
    finally:
        client.close()


def _search_milvus(args):
    from pymilvus import MilvusClient

    host = os.getenv("MILVUS_HOST", "localhost")
    port = os.getenv("MILVUS_PORT", "19530")
    collection = args.get("collection", "financial_documents")
    top_k = args.get("top_k", 5)

    embedding = _get_embedding(args["query"])
    client = MilvusClient(uri=f"http://{host}:{port}")
    results = client.search(
        collection_name=collection, data=[embedding], limit=top_k,
        output_fields=["text", "chunk_index", "source_dataset", "filename"],
    )

    formatted = []
    for hit in results[0]:
        entry = dict(hit["entity"])
        entry["_score"] = hit["distance"]
        formatted.append(entry)
    return _format_results(formatted)


def _search_chroma(args):
    import chromadb

    host = os.getenv("CHROMA_HOST", "localhost")
    port = int(os.getenv("CHROMA_PORT", "8000"))
    collection_name = args.get("collection", "financial_documents")
    top_k = args.get("top_k", 5)

    embedding = _get_embedding(args["query"])
    client = chromadb.HttpClient(host=host, port=port)
    collection = client.get_collection(name=collection_name)
    results = collection.query(
        query_embeddings=[embedding],
        n_results=top_k,
        include=["documents", "metadatas", "distances"]
    )

    formatted = []
    if results["ids"] and results["ids"][0]:
        for i, doc_id in enumerate(results["ids"][0]):
            entry = {}
            if results["documents"] and results["documents"][0]:
                entry["text"] = results["documents"][0][i]
            if results["metadatas"] and results["metadatas"][0]:
                entry.update(results["metadatas"][0][i])
            distance = results["distances"][0][i] if results["distances"] and results["distances"][0] else 0
            entry["_score"] = 1.0 - float(distance)
            formatted.append(entry)
    return _format_results(formatted)


def _search_pgvector(args):
    import psycopg2

    host = os.getenv("PG_HOST", "localhost")
    port = int(os.getenv("PG_PORT", "5432"))
    database = os.getenv("PG_DATABASE", "datris")
    user = os.getenv("PG_USER", "postgres")
    password = os.getenv("PG_PASSWORD", "postgres")
    schema = args.get("schema", "public")
    table = args.get("table", "financial_documents")
    top_k = args.get("top_k", 5)

    embedding = _get_embedding(args["query"])
    vector_str = "[" + ",".join(str(v) for v in embedding) + "]"

    conn = psycopg2.connect(host=host, port=port, dbname=database, user=user, password=password)
    try:
        cur = conn.cursor()
        # Get columns
        cur.execute("""
            SELECT column_name FROM information_schema.columns
            WHERE table_schema = %s AND table_name = %s AND column_name NOT IN ('id', 'embedding')
            ORDER BY ordinal_position
        """, (schema, table))
        columns = [row[0] for row in cur.fetchall()]
        col_list = ", ".join(f'"{c}"' for c in columns)

        cur.execute(f"""
            SELECT {col_list}, 1 - (embedding <=> %s::vector) AS similarity
            FROM "{schema}"."{table}"
            ORDER BY embedding <=> %s::vector
            LIMIT %s
        """, (vector_str, vector_str, top_k))

        col_names = columns + ["_score"]
        formatted = [dict(zip(col_names, row)) for row in cur.fetchall()]
        cur.close()
        return _format_results(formatted)
    finally:
        conn.close()


# ---------------------------------------------------------------------------
# Database Query Implementations
# ---------------------------------------------------------------------------

def _query_postgres(args):
    import psycopg2

    sql = args["sql"].strip()
    limit = args.get("limit", 100)

    # Safety: only allow SELECT queries
    if not sql.upper().startswith("SELECT"):
        return json.dumps({"error": "Only SELECT queries are allowed"})

    host = os.getenv("PG_HOST", "localhost")
    port = int(os.getenv("PG_PORT", "5432"))
    database = os.getenv("PG_DATABASE", "datris")
    user = os.getenv("PG_USER", "postgres")
    password = os.getenv("PG_PASSWORD", "postgres")

    conn = psycopg2.connect(host=host, port=port, dbname=database, user=user, password=password)
    try:
        conn.set_session(readonly=True, autocommit=True)
        cur = conn.cursor()

        # Add LIMIT if not present
        if "LIMIT" not in sql.upper():
            sql = sql.rstrip(";") + f" LIMIT {limit}"

        cur.execute(sql)
        columns = [desc[0] for desc in cur.description]
        rows = [dict(zip(columns, row)) for row in cur.fetchall()]
        cur.close()
        return _format_results(rows)
    finally:
        conn.close()


def _query_mongodb(args):
    from pymongo import MongoClient

    uri = os.getenv("MONGO_URI", "mongodb://localhost:27017")
    database = os.getenv("MONGO_DATABASE", "datris")

    collection_name = args["collection"]
    query_filter = args.get("filter", {})
    projection = args.get("projection", None)
    limit = args.get("limit", 20)

    client = MongoClient(uri)
    try:
        db = client[database]
        collection = db[collection_name]
        cursor = collection.find(query_filter, projection).limit(limit)
        results = []
        for doc in cursor:
            doc["_id"] = str(doc["_id"])  # Convert ObjectId to string
            results.append(doc)
        return _format_results(results)
    finally:
        client.close()


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
