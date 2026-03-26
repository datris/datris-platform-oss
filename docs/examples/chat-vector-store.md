# Chat Vector Store

**Location:** `examples/chat-vector-store/`

A unified RAG chat application that supports all five vector store destinations: Qdrant, Weaviate, Milvus, Chroma, and pgvector. Select the vector store via a command-line argument. Ask questions about documents ingested through Datris pipelines and get AI-powered answers grounded in your data.

## Setup

```bash
cd examples/chat-vector-store
pip install qdrant-client weaviate-client psycopg2-binary pymilvus chromadb openai anthropic python-dotenv requests
```

## Run

```bash
python app.py                     # default: qdrant
python app.py --store qdrant
python app.py --store weaviate
python app.py --store pgvector
python app.py --store milvus
python app.py --store chroma
python app.py -s chroma            # short flag
```

## Configuration (`.env`)

```bash
# Shared
EMBEDDING_PROVIDER=openai
EMBEDDING_MODEL=text-embedding-3-small
OPENAI_API_KEY=your-key
LLM_PROVIDER=anthropic
LLM_MODEL=claude-sonnet-4-6
ANTHROPIC_API_KEY=your-key
TOP_K=5

# Qdrant
QDRANT_HOST=localhost
QDRANT_PORT=6333
QDRANT_COLLECTION=financial_documents

# Weaviate
WEAVIATE_HOST=localhost
WEAVIATE_PORT=8079
WEAVIATE_GRPC_PORT=50051
WEAVIATE_SCHEME=http
WEAVIATE_CLASS=FinancialDocuments

# Milvus
MILVUS_HOST=localhost
MILVUS_PORT=19530
MILVUS_COLLECTION=financial_documents

# Chroma
CHROMA_HOST=localhost
CHROMA_PORT=8000
CHROMA_COLLECTION=financial_documents

# pgvector
PG_HOST=localhost
PG_PORT=5432
PG_DATABASE=datris
PG_USER=postgres
PG_PASSWORD=postgres
PG_SCHEMA=public
PG_TABLE=financial_documents
```
