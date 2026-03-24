# Release Notes

## v1.2.0 — March 24, 2026

### New UI Tabs

**MCP Tab** — Showcases the Datris MCP server and lets users interact with it directly.
- **What AI Agents See** — Displays the platform description, recommended 7-step agent workflow, available resources, and transport modes (stdio/SSE) that agents receive on connect.
- **Service Health** — Real-time status dashboard for all 10 backend services (PostgreSQL, MongoDB, MinIO, ActiveMQ, Kafka, Qdrant, Weaviate, Milvus, Chroma, pgvector) with green/red/grey status indicators and refresh button.
- **MCP Tools** — Browsable card grid of 30+ MCP tools organized by category (System, Pipeline Management, Vector Search, Database Query, Metadata Discovery, AI, Configuration). Each card shows name, description, parameter count, and a "Try it" button.
- **Connect Your Agent** — Config generator for Claude Desktop, Claude Code, and Cursor with editable Pipeline URL and API Key fields, auto-generated JSON config snippet, and copy-to-clipboard.
- **Tool Playground** — Select any MCP tool, fill in parameters via dynamic forms (text, number, textarea, checkbox, select dropdowns), execute against the live API, and view formatted JSON responses. Vector search tools have collection/class dropdowns populated from metadata endpoints.

**Secrets Tab** — Full CRUD management of HashiCorp Vault secrets from the UI.
- List all secrets dynamically from Vault under the environment path
- View secret key-value fields with sensitive field masking (password, apiKey, secretKey, token)
- Reveal/hide toggle for masked values
- Edit secrets: modify values, add/remove fields
- Create new secrets with dynamic key-value rows
- Delete secrets with confirmation

### New REST API Endpoints

**Vector Store Metadata Discovery**
- `GET /api/v1/metadata/qdrant/collections` — List Qdrant collections
- `GET /api/v1/metadata/weaviate/classes` — List Weaviate classes
- `GET /api/v1/metadata/milvus/collections` — List Milvus collections
- `GET /api/v1/metadata/chroma/collections` — List Chroma collections

**Secrets Management**
- `GET /api/v1/secrets` — List all secret names
- `GET /api/v1/secrets/{name}` — Get secret fields (masked by default, `?reveal=true` for actual values)
- `PUT /api/v1/secrets/{name}` — Create or update a secret
- `DELETE /api/v1/secrets/{name}` — Delete a secret

**AI Schema Generation**
- `POST /api/v1/config/generate-schema` — Generate a JSON Schema (Draft 4) or W3C XSD from sample data using AI

**Health Check**
- `GET /api/v1/health/services` — Check health of all backend services

### New MCP Tools

- `list_qdrant_collections` — List all Qdrant collections
- `list_weaviate_classes` — List all Weaviate classes
- `list_milvus_collections` — List all Milvus collections
- `list_chroma_collections` — List all Chroma collections
- `list_pgvector_collections` — List all pgvector tables
- `check_service_health` — Check backend service health
- `get_version` — Get server version

### Pipeline Wizard Enhancements

**REST Endpoint Transformation (Step 6)**
- New transformation type alongside JavaScript and AI: call an external REST endpoint to transform data
- Row mode (per-row) and batch mode (all rows at once)
- Configurable endpoint URL, timeout, bearer token, API key
- Endpoint can modify field values, add fields, or remove rows (return `null`)
- Server handles float-to-integer conversion for whole numbers in responses

**AI Schema Generation (Step 4)**
- For JSON and XML pipelines, choose between uploading a schema file or generating one with AI
- Enter a schema name and paste/load sample data, then click Generate
- "Load from uploaded file" button pre-fills sample data from the file analyzed in Step 1
- Generated schemas stored in MinIO and referenced automatically in the pipeline config

**XML Source Improvements (Step 2)**
- "Each line contains a complete XML element" checkbox defaults to off (full document mode)
- Added info button with examples showing per-line XML vs full document formats

**XML Destination Restrictions (Step 7)**
- XML sources can only go to PostgreSQL or vector stores
- MongoDB, Object Store, Kafka, ActiveMQ, and REST Endpoint destinations are hidden for XML

**Pipeline View Enhancements**
- Copy, Edit, and Delete buttons next to the pipeline name
- Copy shows a non-intrusive toast notification without scrolling the page
- Auto-refresh every 3 seconds (only updates DOM when data changes)
- Delete with confirmation prompt

### Backend Changes

**Transformation Engine**
- `Transformation.scala` refactored to support multiple row function types (previously only JavaScript)
- Row functions now dispatch by type: `javascript` and `restEndpoint`
- REST endpoint transformation: row mode sends per-row JSON, batch mode sends all rows
- Row removal via `null` return (same pattern as JavaScript transform)
- Float-to-integer conversion for whole numbers in REST responses (`3498900.0` → `3498900`)

**PostgreSQL Loader**
- Handles `rawData` (whole XML/JSON documents) as a single row when `rows` is null
- Proper CSV quoting for raw data containing quotes (XML declarations, etc.)

**PostgreSQL Query**
- `SQLXML` column values converted to strings for JSON serialization (prevents Gson reflection errors)

**Health Check**
- Fixed PostgreSQL health check: appends `/datris` to JDBC URL when database name is missing
- Fixed pgvector health check: handles `jdbcUrl` field instead of `host` field

**Secrets Utility**
- Extended `SecretsManagerUtility` trait with `listSecrets`, `writeSecret`, `deleteSecret`
- `VaultSecretsUtil` implements all three using Vault SDK

**Pipeline Validator**
- Accepts `restEndpoint` row functions in the transformation section (previously only `javascript`)

**Timeout Standardization**
- Preprocessor and REST endpoint destination timeouts standardized to milliseconds (`timeoutMs`, default 300000)
- Backward compatible: `timeoutSeconds` still accepted and converted to ms

### Helper Applications

**New: `helpers/transformation-rest/`**
- Sample Flask REST endpoint for pipeline transformation row functions
- Row mode (`/transform/rest/row`) and batch mode (`/transform/rest/batch`)
- Lowercases all string values as a sample transformation
- Commented examples for row removal and computed fields
- Runs on port 5600

### Documentation Updates

- New: `docs/transformation/rest-endpoint.md` — Full REST endpoint transformation reference
- Updated: `docs/mcp.md` — All new MCP tools documented
- Updated: `docs/helpers.md` — Transformation REST helper added
- Updated: `docs/pipeline-configuration.md` — REST endpoint transformation in examples
- Updated: `docs/preprocessor.md` — Timeout field changed to `timeoutMs` (milliseconds)
- Updated: `docs/openapi.yaml` — Secrets CRUD, schema generation, vector metadata endpoints
- Updated: MCP server `PIPELINE_CONFIG_REFERENCE` — REST endpoint transformation documented

### Bug Fixes

- Fixed `vault-init.sh` PostgreSQL secret missing database name in JDBC URL
- Fixed pgvector health check returning "not_configured" due to missing `host` field (pgvector uses `jdbcUrl`)
- Fixed XML schema validation checkbox not persisting when editing a pipeline
- Fixed XML source type forcing destination to MongoDB (now defaults to PostgreSQL)
- Fixed pipeline view scrolling to top on copy/refresh
- Fixed `PgSQLXML` Gson serialization error when querying XML columns
