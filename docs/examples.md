# Examples

The `examples/` directory contains standalone applications that integrate with and extend the Datris platform. From a full-blown AI agent to simple utility scripts — each demonstrates a different way to use Datris.

## Agent

| Example | Description |
|---------|-------------|
| [Market Macro Agent](examples/market-macro-agent.md) | MacroAgent — a real-time financial data pipeline agent. Connects to Datris via MCP, fetches live market data (FRED, yfinance, CoinGecko, SEC EDGAR), creates pipelines, ingests data, and answers market questions — all visible in a browser UI. Full agentic loop with background refresh. |

## Data Processing

| Example | Description |
|---------|-------------|
| [Data Quality REST](examples/data-quality-rest.md) | Flask REST API for external data quality validation. Row and batch modes for custom validation logic. |
| [Transformation REST](examples/transformation-rest.md) | Flask REST API for external data transformation. Modify, filter, or remove rows during pipeline processing. |
| [Preprocessor](examples/preprocessor.md) | Flask REST API for preprocessing data before pipeline ingestion. Synchronous and asynchronous modes. |

## Data Ingestion

| Example | Description |
|---------|-------------|
| [Kafka CSV Loader](examples/kafka-csv-loader.md) | Kafka producer that publishes CSV data to topics for streaming ingestion. |
| [Topic Subscriber](examples/topic-subscriber.md) | ActiveMQ consumer that listens for pipeline completion notifications to trigger downstream workflows. |

## Search & RAG

| Example | Description |
|---------|-------------|
| [Chat Vector Store](examples/chat-vector-store.md) | Unified RAG chat app supporting all 5 vector stores (Qdrant, Weaviate, Milvus, Chroma, pgvector). |

## Testing

| Example | Description |
|---------|-------------|
| [MCP Server Test](examples/test-mcp-server.md) | Integration test exercising MCP tools end-to-end: create pipeline, upload, query, cleanup. |
