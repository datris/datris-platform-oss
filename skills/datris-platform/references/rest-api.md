# When and how to use the Datris REST API

## The dividing line

- **You, the agent, acting now:** MCP tools. Always.
- **Code you write into the project that runs later without you:** the REST API. A web service, a worker, a notebook or a test fixture cannot hold an MCP session, so it talks HTTP.
- **A capability with no MCP tool:** the REST API, from you, as a last resort. Say so in your reply.

Both paths hit the same server and the same capability checks, policy and audit log. The MCP server is a transparent forwarder with no privileges of its own.

## Base URL and authentication

The Docker stack serves the API on port 8080 of the `datris` service, so `http://localhost:8080/api/v1` from the host. When API keys are enabled (`USE_API_KEYS=true`), every request carries `x-api-key: <value>`. A scoped key that lacks the capability a route needs gets HTTP 403.

Runtime code should get its own key, issued from Configuration, API-Keys in the UI with a label naming the service. Each label is a separate identity in the audit log and can be rotated or revoked independently. Never reuse the agent's key for the project's service, and never put the value in source; read it from the environment.

Keys carry capabilities of the form `resource:action[:scope]`, for example `pipeline:read:catalog=<name>` or `search:vector:collection=<name>`. Scopes restrict by container, never by leaf name. Templates: read-only, rag-builder, reporting, ops. Avoid full-access for anything automated.

## The full reference

The OpenAPI document is at `docs/openapi.yaml` in the Datris repository and at https://docs.datris.ai/api-reference. Read the relevant path before writing a call; request shapes are specific.

## Endpoints runtime code usually needs

| Need | Method and path | Notes |
|---|---|---|
| Push a file into a pipeline | `POST /api/v1/pipeline/upload` | multipart form: `file`, `pipeline`, optional `publishertoken`. Returns the pipeline token as text. |
| Watch a load | `GET /api/v1/pipeline/status` | By pipeline or publisher token. Poll until the rollup is done. |
| Read Live Read rows | `GET /api/v1/pipeline/result` | Scratch pipelines only; paged by offset and limit. |
| SQL against landed data | `POST /api/v1/query/postgres`, `/query/snowflake`, `/query/databricks`, `/query/objectstore` | Read-only. |
| Document query | `POST /api/v1/query/mongodb` | Filter and projection. |
| Natural-language query | `POST /api/v1/query/natural` | The platform writes the SQL. |
| Semantic search | `POST /api/v1/search/{store}` | `store` is pgvector, qdrant, weaviate, milvus or chroma. |
| RAG answer | `POST /api/v1/ai/answer` | Context plus optional sources. |
| Find data by meaning | `GET /api/v1/catalog/find` | Same ranking as `find_data`. |
| Provenance and lineage | `GET /api/v1/provenance`, `/lineage`, `/lineage/{type}/{name}`, `/lineage/columns/{pipeline}` | |
| Run a tap from code | `POST /api/v1/tap/run` | Prefer a cron on the tap over calling this from a scheduler. |
| Health | `GET /api/v1/health/services`, `GET /api/v1/version` | |

## Event-driven code

Instead of polling for new rows, subscribe to pipeline notifications. Every destination loader publishes to the ActiveMQ virtual topic `VirtualTopic.<environment>-pipeline-notification`; a consumer reads from its own queue `Consumer.<name>.VirtualTopic.<environment>-pipeline-notification`. No subscription API is required. See https://docs.datris.ai/notifications.

## Code that runs inside a tap

A tap script that needs data already in Datris does not need a key. The platform injects `DATRIS_PLATFORM_HOST`, `DATRIS_PLATFORM_PORT`, `DATRIS_POSTGRES_DATABASE`, `DATRIS_MONGODB_DATABASE` and a per-run read-only `DATRIS_PLATFORM_TOKEN`, and attaches the token automatically to `requests` and `urllib` calls aimed at the platform host. See https://docs.datris.ai/taps#querying-datris-data.
