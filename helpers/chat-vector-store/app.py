#!/usr/bin/env python3
"""
Unified RAG Chat Application for Vector Stores
Queries documents stored in Qdrant, Weaviate, or pgvector (PostgreSQL) via the Datris
and uses an LLM to answer questions.

Usage:
    pip install qdrant-client weaviate-client psycopg2-binary openai anthropic python-dotenv requests

    python app.py                    # default: qdrant
    python app.py --store qdrant
    python app.py --store weaviate
    python app.py --store pgvector

Environment variables (shared):
    EMBEDDING_PROVIDER    - "openai" or "ollama" (default: openai)
    EMBEDDING_MODEL       - Embedding model (default: text-embedding-3-small)
    EMBEDDING_ENDPOINT    - Ollama endpoint (default: http://localhost:11434)
    LLM_PROVIDER          - "anthropic" or "openai" or "ollama" (default: anthropic)
    LLM_MODEL             - LLM model (default: claude-sonnet-4-6)
    ANTHROPIC_API_KEY     - Anthropic API key (required if LLM_PROVIDER=anthropic)
    OPENAI_API_KEY        - OpenAI API key (required if using OpenAI)
    TOP_K                 - Number of chunks to retrieve (default: 5)

Environment variables (Qdrant):
    QDRANT_HOST           - Qdrant host (default: localhost)
    QDRANT_PORT           - Qdrant port (default: 6333)
    QDRANT_COLLECTION     - Collection name (default: financial_documents)

Environment variables (Weaviate):
    WEAVIATE_HOST         - Weaviate host (default: localhost)
    WEAVIATE_PORT         - Weaviate REST port (default: 8079)
    WEAVIATE_GRPC_PORT    - Weaviate gRPC port (default: 50051)
    WEAVIATE_SCHEME       - http or https (default: http)
    WEAVIATE_API_KEY      - Weaviate API key (default: empty)
    WEAVIATE_CLASS        - Class name (default: FinancialDocuments)

Environment variables (pgvector):
    PG_HOST               - PostgreSQL host (default: localhost)
    PG_PORT               - PostgreSQL port (default: 5432)
    PG_DATABASE           - Database name (default: idata)
    PG_USER               - Username (default: postgres)
    PG_PASSWORD           - Password (default: postgres)
    PG_SCHEMA             - Schema name (default: public)
    PG_TABLE              - Table name (default: financial_documents)
"""

import argparse
import os
import sys
import requests
from dotenv import load_dotenv

load_dotenv()

# Shared configuration
EMBEDDING_PROVIDER = os.getenv("EMBEDDING_PROVIDER", "openai")
EMBEDDING_MODEL = os.getenv("EMBEDDING_MODEL", "text-embedding-3-small")
EMBEDDING_ENDPOINT = os.getenv("EMBEDDING_ENDPOINT", "http://localhost:11434")
LLM_PROVIDER = os.getenv("LLM_PROVIDER", "anthropic")
LLM_MODEL = os.getenv("LLM_MODEL", "claude-sonnet-4-6")
TOP_K = int(os.getenv("TOP_K", "5"))


# ---------------------------------------------------------------------------
# Embedding
# ---------------------------------------------------------------------------

def get_embedding(text):
    """Generate an embedding for the query text."""
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


# ---------------------------------------------------------------------------
# Qdrant
# ---------------------------------------------------------------------------

def search_qdrant(query_embedding, top_k=TOP_K):
    """Search Qdrant for similar chunks."""
    from qdrant_client import QdrantClient

    host = os.getenv("QDRANT_HOST", "localhost")
    port = int(os.getenv("QDRANT_PORT", "6333"))
    collection = os.getenv("QDRANT_COLLECTION", "financial_documents")

    client = QdrantClient(host=host, port=port)
    results = client.query_points(
        collection_name=collection,
        query=query_embedding,
        limit=top_k
    )

    formatted = []
    for point in results.points:
        entry = dict(point.payload)
        entry["_score"] = point.score
        formatted.append(entry)
    return formatted


def qdrant_info():
    """Return display info for Qdrant."""
    host = os.getenv("QDRANT_HOST", "localhost")
    port = os.getenv("QDRANT_PORT", "6333")
    collection = os.getenv("QDRANT_COLLECTION", "financial_documents")
    return f"Qdrant @ {host}:{port}, collection: {collection}"


# ---------------------------------------------------------------------------
# Weaviate
# ---------------------------------------------------------------------------

def search_weaviate(query_embedding, top_k=TOP_K):
    """Search Weaviate for similar chunks using nearVector."""
    import weaviate
    import weaviate.classes.query as wq

    host = os.getenv("WEAVIATE_HOST", "localhost")
    port = int(os.getenv("WEAVIATE_PORT", "8079"))
    grpc_port = int(os.getenv("WEAVIATE_GRPC_PORT", "50051"))
    scheme = os.getenv("WEAVIATE_SCHEME", "http")
    api_key = os.getenv("WEAVIATE_API_KEY", "")
    class_name = os.getenv("WEAVIATE_CLASS", "FinancialDocuments")

    kwargs = dict(
        http_host=host,
        http_port=port,
        http_secure=(scheme == "https"),
        grpc_host=host,
        grpc_port=grpc_port,
        grpc_secure=(scheme == "https"),
    )
    if api_key:
        kwargs["auth_credentials"] = weaviate.auth.AuthApiKey(api_key=api_key)

    client = weaviate.connect_to_custom(**kwargs)
    try:
        collection = client.collections.get(class_name)
        response = collection.query.near_vector(
            near_vector=query_embedding,
            limit=top_k,
            return_metadata=wq.MetadataQuery(distance=True),
        )
        formatted = []
        for obj in response.objects:
            entry = dict(obj.properties)
            distance = obj.metadata.distance if obj.metadata.distance is not None else 0
            entry["_score"] = 1.0 - float(distance)
            formatted.append(entry)
        return formatted
    finally:
        client.close()


def weaviate_info():
    """Return display info for Weaviate."""
    host = os.getenv("WEAVIATE_HOST", "localhost")
    port = os.getenv("WEAVIATE_PORT", "8079")
    scheme = os.getenv("WEAVIATE_SCHEME", "http")
    class_name = os.getenv("WEAVIATE_CLASS", "FinancialDocuments")
    return f"Weaviate @ {scheme}://{host}:{port}, class: {class_name}"


# ---------------------------------------------------------------------------
# pgvector
# ---------------------------------------------------------------------------

def search_pgvector(query_embedding, top_k=TOP_K):
    """Search PostgreSQL with pgvector for similar chunks."""
    import psycopg2

    host = os.getenv("PG_HOST", "localhost")
    port = int(os.getenv("PG_PORT", "5432"))
    database = os.getenv("PG_DATABASE", "idata")
    user = os.getenv("PG_USER", "postgres")
    password = os.getenv("PG_PASSWORD", "postgres")
    schema = os.getenv("PG_SCHEMA", "public")
    table = os.getenv("PG_TABLE", "financial_documents")

    conn = psycopg2.connect(host=host, port=port, dbname=database, user=user, password=password)
    try:
        # Discover columns
        cur = conn.cursor()
        cur.execute("""
            SELECT column_name FROM information_schema.columns
            WHERE table_schema = %s AND table_name = %s
            AND column_name NOT IN ('id', 'embedding')
            ORDER BY ordinal_position
        """, (schema, table))
        columns = [row[0] for row in cur.fetchall()]
        cur.close()

        col_list = ", ".join(f'"{c}"' for c in columns)
        vector_str = "[" + ",".join(str(v) for v in query_embedding) + "]"

        cur = conn.cursor()
        cur.execute(f"""
            SELECT {col_list}, 1 - (embedding <=> %s::vector) AS similarity
            FROM "{schema}"."{table}"
            ORDER BY embedding <=> %s::vector
            LIMIT %s
        """, (vector_str, vector_str, top_k))

        col_names = columns + ["similarity"]
        formatted = []
        for row in cur.fetchall():
            entry = dict(zip(col_names, row))
            entry["_score"] = entry.pop("similarity", 0)
            formatted.append(entry)
        cur.close()
        return formatted
    finally:
        conn.close()


def pgvector_info():
    """Return display info for pgvector."""
    host = os.getenv("PG_HOST", "localhost")
    port = os.getenv("PG_PORT", "5432")
    database = os.getenv("PG_DATABASE", "idata")
    schema = os.getenv("PG_SCHEMA", "public")
    table = os.getenv("PG_TABLE", "financial_documents")
    return f"pgvector @ {host}:{port}/{database}, table: {schema}.{table}"


# ---------------------------------------------------------------------------
# Milvus
# ---------------------------------------------------------------------------

def search_milvus(query_embedding, top_k=TOP_K):
    """Search Milvus for similar chunks."""
    from pymilvus import MilvusClient

    host = os.getenv("MILVUS_HOST", "localhost")
    port = os.getenv("MILVUS_PORT", "19530")
    collection = os.getenv("MILVUS_COLLECTION", "financial_documents")

    client = MilvusClient(uri=f"http://{host}:{port}")
    results = client.search(
        collection_name=collection,
        data=[query_embedding],
        limit=top_k,
        output_fields=["text", "chunk_index", "source_dataset", "filename"],
    )

    formatted = []
    for hit in results[0]:
        entry = dict(hit["entity"])
        entry["_score"] = hit["distance"]
        formatted.append(entry)
    return formatted


def milvus_info():
    """Return display info for Milvus."""
    host = os.getenv("MILVUS_HOST", "localhost")
    port = os.getenv("MILVUS_PORT", "19530")
    collection = os.getenv("MILVUS_COLLECTION", "financial_documents")
    return f"Milvus @ {host}:{port}, collection: {collection}"


# ---------------------------------------------------------------------------
# Chroma
# ---------------------------------------------------------------------------

def search_chroma(query_embedding, top_k=TOP_K):
    """Search Chroma for similar chunks."""
    import chromadb

    host = os.getenv("CHROMA_HOST", "localhost")
    port = os.getenv("CHROMA_PORT", "8000")
    collection_name = os.getenv("CHROMA_COLLECTION", "financial_documents")

    client = chromadb.HttpClient(host=host, port=int(port))
    collection = client.get_collection(name=collection_name)
    results = collection.query(
        query_embeddings=[query_embedding],
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
            entry["_score"] = 1.0 - float(distance)  # Convert cosine distance to similarity
            formatted.append(entry)
    return formatted


def chroma_info():
    """Return display info for Chroma."""
    host = os.getenv("CHROMA_HOST", "localhost")
    port = os.getenv("CHROMA_PORT", "8000")
    collection = os.getenv("CHROMA_COLLECTION", "financial_documents")
    return f"Chroma @ {host}:{port}, collection: {collection}"


# ---------------------------------------------------------------------------
# LLM
# ---------------------------------------------------------------------------

def ask_llm(question, context):
    """Send the question and context to the LLM for an answer."""
    system_prompt = (
        "You are a helpful assistant that answers questions based on the provided document context. "
        "Use only the information from the context to answer. If the context doesn't contain "
        "enough information to answer the question, say so. Cite specific details from the documents."
    )

    user_prompt = f"""Context from retrieved documents:

{context}

Question: {question}

Answer based on the context above:"""

    if LLM_PROVIDER == "anthropic":
        import anthropic
        client = anthropic.Anthropic()
        response = client.messages.create(
            model=LLM_MODEL,
            max_tokens=2048,
            system=system_prompt,
            messages=[{"role": "user", "content": user_prompt}]
        )
        return response.content[0].text

    elif LLM_PROVIDER == "openai":
        import openai
        client = openai.OpenAI()
        response = client.chat.completions.create(
            model=LLM_MODEL,
            max_tokens=2048,
            messages=[
                {"role": "system", "content": system_prompt},
                {"role": "user", "content": user_prompt}
            ]
        )
        return response.choices[0].message.content

    elif LLM_PROVIDER == "ollama":
        response = requests.post(
            f"{EMBEDDING_ENDPOINT}/v1/chat/completions",
            json={
                "model": LLM_MODEL,
                "messages": [
                    {"role": "system", "content": system_prompt},
                    {"role": "user", "content": user_prompt}
                ],
                "max_tokens": 2048
            }
        )
        response.raise_for_status()
        return response.json()["choices"][0]["message"]["content"]

    else:
        raise ValueError(f"Unknown LLM provider: {LLM_PROVIDER}")


# ---------------------------------------------------------------------------
# Format context
# ---------------------------------------------------------------------------

def format_context(results):
    """Format search results into context for the LLM."""
    chunks = []
    for i, result in enumerate(results, 1):
        text = result.get("text", "")
        score = result.get("_score", 0)

        metadata = {k: v for k, v in result.items()
                    if k not in ("text", "_score") and v is not None}
        metadata_str = ", ".join(f"{k}: {v}" for k, v in metadata.items()) if metadata else ""

        chunk = f"--- Chunk {i} (score: {score:.4f}) ---"
        if metadata_str:
            chunk += f"\n[{metadata_str}]"
        chunk += f"\n{text}\n"
        chunks.append(chunk)

    return "\n".join(chunks)


# ---------------------------------------------------------------------------
# Chat loop
# ---------------------------------------------------------------------------

STORES = {
    "qdrant": {"search": search_qdrant, "info": qdrant_info},
    "weaviate": {"search": search_weaviate, "info": weaviate_info},
    "pgvector": {"search": search_pgvector, "info": pgvector_info},
    "milvus": {"search": search_milvus, "info": milvus_info},
    "chroma": {"search": search_chroma, "info": chroma_info},
}


def chat(store_name):
    """Main chat loop."""
    store = STORES[store_name]
    search_fn = store["search"]
    store_info = store["info"]()

    print("=" * 60)
    print("  RAG Chat - Vector Store Document Search")
    print(f"  Store: {store_name}")
    print(f"  {store_info}")
    print(f"  Embedding: {EMBEDDING_PROVIDER}/{EMBEDDING_MODEL}")
    print(f"  LLM: {LLM_PROVIDER}/{LLM_MODEL}")
    print(f"  Top-K: {TOP_K}")
    print("=" * 60)
    print("\nType your question (or 'quit' to exit):\n")

    while True:
        try:
            question = input("You: ").strip()
        except (EOFError, KeyboardInterrupt):
            print("\nGoodbye!")
            break

        if not question:
            continue
        if question.lower() in ("quit", "exit", "q"):
            print("Goodbye!")
            break

        try:
            # Step 1: Generate embedding for the question
            print(f"  Searching {store_name}...")
            query_embedding = get_embedding(question)

            # Step 2: Search vector store
            results = search_fn(query_embedding)

            if not results:
                print("\n  No relevant documents found.\n")
                continue

            # Step 3: Format context
            context = format_context(results)

            # Step 4: Ask LLM
            print(f"  Found {len(results)} chunks, asking {LLM_PROVIDER}...")
            answer = ask_llm(question, context)

            print(f"\nAssistant: {answer}\n")

        except Exception as e:
            print(f"\n  Error: {e}\n")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(
        description="RAG Chat - Query documents in Qdrant, Weaviate, or pgvector"
    )
    parser.add_argument(
        "--store", "-s",
        choices=["qdrant", "weaviate", "pgvector", "milvus", "chroma"],
        default="qdrant",
        help="Vector store to query (default: qdrant)"
    )
    args = parser.parse_args()
    chat(args.store)
