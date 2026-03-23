# Helper Applications

The `helpers/` directory contains Python utility applications that integrate with and extend the Datris. These are standalone apps designed for testing, development, and production use alongside the pipeline.

## Chat Vector Store (Unified)

**Location:** `helpers/chat-vector-store/`

A unified RAG chat application that supports all five vector store destinations: Qdrant, Weaviate, Milvus, Chroma, and pgvector. Select the vector store via a command-line argument.

**Setup:**
```bash
cd helpers/chat-vector-store
pip install qdrant-client weaviate-client psycopg2-binary pymilvus chromadb openai anthropic python-dotenv requests
```

**Run:**
```bash
python app.py                     # default: qdrant
python app.py --store qdrant
python app.py --store weaviate
python app.py --store pgvector
python app.py --store milvus
python app.py --store chroma
python app.py -s chroma            # short flag
```

**Configuration (`.env`):**
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

---

## Data Quality REST

**Location:** `helpers/data-quality-rest/`

A Flask REST API for external data quality validation. The pipeline can call this endpoint as part of its data quality rules, enabling custom validation logic outside the pipeline.

**Endpoints:**
- `POST /dataquality/rest/row` — Validate a single row
- `POST /dataquality/rest/batch` — Validate a batch of rows

**Setup:**
```bash
cd helpers/data-quality-rest
pip install flask
```

**Run:**
```bash
python app.py    # runs on port 5500
```

**Usage:** Configure a pipeline's `dataQuality.rowRules` with `function: "restEndpoint"` pointing to `http://host.docker.internal:5500/dataquality/rest/row`.

---

## Kafka CSV Loader

**Location:** `helpers/kafka-csv-loader/`

A Kafka producer utility that publishes CSV file contents to Kafka topics. Use this to ingest data into the pipeline via Kafka instead of the file upload API.

**Setup:**
```bash
cd helpers/kafka-csv-loader
pip install kafka-python
```

**Run:**
```bash
python app.py
```

**Note:** Edit `app.py` to configure the Kafka broker address, topic name, and CSV file path.

---

## Preprocessor

**Location:** `helpers/preprocessor/`

A Flask REST API that demonstrates the pipeline's preprocessor capability. Supports both synchronous and asynchronous preprocessing modes.

**Endpoints:**
- `POST /preprocess/sync` — Process data synchronously and return the result
- `POST /preprocess/async` — Process data asynchronously and POST results back via callback

**Setup:**
```bash
cd helpers/preprocessor
pip install flask requests
```

**Run:**
```bash
python app.py    # runs on port 5500
```

**Usage:** Configure a pipeline's `source.preprocessor` with the endpoint URL. The pipeline sends data to your preprocessor before ingestion, allowing custom transformations.

---

## Topic Subscriber

**Location:** `helpers/topic-subscriber/`

An ActiveMQ/STOMP consumer that subscribes to pipeline pipeline notification events. Use this to trigger downstream workflows when pipelines are processed.

**Features:**
- Durable subscription with automatic reconnection
- Supports Virtual Topic and Durable Topic subscription styles
- Graceful shutdown on SIGINT/SIGTERM
- Logs to both stdout and file (`notification_consumer.log`)

**Configuration (`.env`):**
```bash
ACTIVEMQ_HOST=localhost
ACTIVEMQ_PORT=61613
ACTIVEMQ_USER=admin
ACTIVEMQ_PASSWORD=admin
TOPIC_NAME=oss-pipeline-notification
SUBSCRIPTION_STYLE=virtual_topic    # virtual_topic or durable_topic
CLIENT_ID=my-subscriber
```

**Setup:**
```bash
cd helpers/topic-subscriber
pip install stomp.py python-dotenv
```

**Run:**
```bash
python app.py
```

The subscriber will print pipeline processing notifications as they arrive, including pipeline name, destination, and pipeline token.

---

## MCP Server Test

**Location:** `helpers/test-mcp-server/`

An integration test script for the MCP server that exercises all 15 tools end-to-end. Tests the full round-trip: push data into the pipeline, wait for ingestion, then query it back.

**Test flow:**
1. **CSV → PostgreSQL** — create pipeline, upload CSV, wait, query back with SQL
2. **JSON → MongoDB** — create pipeline, upload JSON, wait, query back with find()
3. **AI profiling + schema generation** — profile the CSV, generate a pipeline config
4. **PDF → pgvector** — create pipeline, upload Apple 10-Q PDF, wait for chunking/embedding, semantic search "What was Apple's revenue?"
5. **Cleanup** — delete all test pipelines

**Setup:**
```bash
cd helpers/test-mcp-server
pip install requests python-dotenv psycopg2-binary pymongo openai
```

**Run:**
```bash
python app.py
```

Requires the pipeline running via `docker-compose up`. Vector search requires an OpenAI API key for embedding generation.
