# Choosing a destination

A pipeline can write to several destinations in parallel. Pick by what the project will do with the data afterwards. Exact config keys are in the `datris://pipeline-config-reference` MCP resource; read it before writing a config.

## Keep it or just look at it?

| Intent | Destination |
|---|---|
| Keep rows, query them later, refresh on a schedule | One of the stores below |
| See what a source returns, check a file against its rules, preview a transformation, then move on | `scratch` (Live Read). Rows are held briefly, not catalogued, and expire. Read them with `get_pipeline_result`. |
| Preview what a tap script produces | `test_tap`, no pipeline needed |

Never create a table to read rows once; never use Live Read for data the project will want again. If Live Read rows turn out to be worth keeping, change the pipeline's destination; the tap, its schedule and its cursor stay put.

## Structured rows

Offer all of these when the user has not chosen; do not default silently.

| Destination | Fits when | Needs |
|---|---|---|
| PostgreSQL | Relational queries, joins, the bundled default | Nothing extra on the Docker stack |
| MongoDB | Document-shaped or ragged records | Nothing extra on the Docker stack |
| Snowflake | The project's warehouse is Snowflake | A platform `credentialsSecret`, warehouse, database |
| Databricks | The project's lakehouse is Databricks | A platform `credentialsSecret`, SQL warehouse optional, catalog. Unity Catalog metadata push is available. |
| Object store | Files in a lake: Parquet, ORC, or an Iceberg table, in MinIO (bundled) or S3 | For S3, `provider: "s3"` and a platform `credentialsSecret` holding `accessKey`, `secretKey`, `region` |

Column types: a pipeline created from a sample may land every column as text. `get_dest_types` proposes real types from landed rows; `apply_dest_types` migrates with the user's explicit approval.

## Vector stores (semantic search, RAG)

pgvector (bundled Postgres), Qdrant, Weaviate, Milvus, Chroma. The source must be `unstructuredAttributes` for documents. The embedding model is pinned at creation and dimensions cannot change afterwards; confirm which embedder the instance uses (`run_doctor` reports it) before creating the pipeline. Chunking strategies: fixed-size, sentence, paragraph, recursive.

## Messaging and callbacks

Kafka, ActiveMQ and a REST endpoint destination exist for projects that want rows pushed to them. Independently of the destination, every loader publishes a completion notification on the ActiveMQ virtual topic.

## Verifying a platform secret before using it

`list_platform_secrets` then `get_platform_secret_fields` shows whether a candidate secret has the keys the destination needs. Agents cannot create or edit platform secrets; ask the user to add one in Configuration, Secrets when none fits.
