# Release Notes

## v1.5.0 — April 2, 2026

### Multi-Tenant Hosting Support

The platform now supports multi-tenant hosting on a shared instance. Each tenant gets full data isolation through per-request environment resolution via API keys.

- **Per-tenant PostgreSQL databases** — each tenant's data is stored in an isolated database (named by their environment prefix), auto-created on first use
- **Per-tenant MongoDB databases** — scoped via the connection string environment name
- **Tenant-scoped metadata endpoints** — PostgreSQL databases, schemas, tables, and columns are filtered to the tenant's own database. MongoDB and vector DB collections are filtered to the tenant's pipelines.
- **Tenant-scoped query endpoints** — PostgreSQL and MongoDB queries are forced to the tenant's database in multi-tenant mode
- **Tenant interceptor** — new `TenantInterceptor` sets `DatrisEnvironment.current` per request based on the API key
- **Vector DB secret isolation** — all vector DB loaders (Qdrant, Weaviate, Milvus, Chroma, pgvector) use tenant-scoped secret names

### Batch Upload for Compressed Files

Uploading a `.zip`, `.gz`, `.tar`, or `.jar` file now processes the contents inline — no MinIO webhook dependency.

- **CSV archives** — files are concatenated into a single pipeline run (headers deduplicated), resulting in one DQ pass, one transformation, and one database write
- **Non-CSV archives** (PDFs, documents, etc.) — each file is processed individually as a separate pipeline run
- Filters system files (`__MACOSX`, `META-INF`, `./._*`)

### Pipeline UI Improvements

- **Search bar** — filter pipelines by name, source type, or destination
- **Alphabetical sorting** — pipelines listed in alpha order
- **Ingest data icon** — upload files directly from the pipeline row, with success message directing to the Ingestion tab
- **View configuration icon** — eye icon to view pipeline JSON config without editing
- **Row click navigates to edit** — clicking a pipeline row opens the edit wizard
- **Auto-analyze on file select** — uploading a sample file in step 1 automatically triggers analysis
- **Pipeline architecture diagram** — "View Pipeline Architecture" link opens a modal showing the full Sources → Processing → Storage → Notification flow
- **Page descriptions** — added descriptions to the Pipelines and Ingestion tabs explaining the workflow
- **Empty state updated** — now mentions the Create Pipeline button

### Delete Pipeline — Data Only Option

The `DELETE /api/v1/pipeline` endpoint now supports a `deleteConfig` parameter (default: `true`). Setting `deleteConfig=false` deletes destination data (tables, collections) while keeping the pipeline configuration.

```bash
# Delete data only, keep config
curl -X DELETE "http://localhost:8080/api/v1/pipeline?pipeline=my_pipeline&deleteData=true&deleteConfig=false"
```

### Version Endpoint Enhanced

`GET /api/v1/version` now returns additional fields:

```json
{
  "version": "1.5.0",
  "environment": "oss",
  "multiTenant": "false"
}
```

### Health-Based Service Filtering

The UI now calls `/api/v1/health/services` to determine which optional services are available. Destination options (Kafka, Qdrant, Weaviate, Milvus, Chroma) are hidden in the pipeline wizard and search dropdowns if the service is not deployed.

### API Key Authentication UI

New API key prompt component for managed hosting. When `multiTenant` is enabled, users must enter their API key before accessing the platform. Self-hosted users (`multiTenant: false`) bypass the prompt automatically.

### Configuration Tab

New Configuration tab in the platform UI for viewing instance settings.

### Search Tab — Multi-Tenant Improvements

- Database field is read-only in multi-tenant mode, showing the tenant's actual database name
- MongoDB database selector is disabled in multi-tenant mode
- Embedding and vector secret name fields are hidden in multi-tenant mode (server uses tenant defaults)

### Environment & Version Display

The top-right corner of the nav bar now shows the environment name and version (e.g., `oss v1.5.0`).

### pgvector — Table Existence Check

`PGVectorLoader` now checks `information_schema.tables` before creating a table, avoiding the PostgreSQL composite type collision error when re-ingesting into an existing pgvector table.

### Nginx Upload Size Limit

Added `client_max_body_size 1G` and a JSON error response for 413 errors. Previously, large file uploads returned raw HTML errors.

### MCP Tab Updates

- Updated source types to include PDF, Word, Excel, PowerPoint, HTML, Markdown
- Updated data quality description (removed regex patterns, JSON Schema references)
- Removed JavaScript row functions from transformation description

### Documentation Updates

- `pipeline-api.mdx` — added `deleteConfig` and `deleteData` parameters to DELETE endpoint
- `version-api.mdx` — added `environment` and `multiTenant` response fields
- `openapi.yaml` — synced all changes to the OpenAPI 3.0.3 spec

### Deploy Infrastructure

New `deploy/` directory with production deployment scripts and configuration:
- `docker-compose.prod.yml` — production Docker Compose with nginx, TLS, vault
- `deploy.sh` / `deploy-trial.sh` — deployment scripts for dedicated and trial instances
- `provision.sh` — auto-provisioning script for new instances
- `nginx.conf` — production nginx with TLS, reverse proxy, upload limits
- `application-trial.yaml` — trial instance configuration with multi-tenant enabled
- `backup.sh` — database backup script

### Version

- Server: 1.5.0

---

## v1.4.4 — March 30, 2026

See [v1.4.4 release notes](release-notes/v1.4.4.md).

## v1.4.3 — March 29, 2026

See [v1.4.3 release notes](release-notes/v1.4.3.md).

## v1.4.2 — March 27, 2026

See [v1.4.2 release notes](release-notes/v1.4.2.md).

## v1.4.1 — March 26, 2026

See [v1.4.1 release notes](release-notes/v1.4.1.md).

## v1.4.0 — March 26, 2026

See [v1.4.0 release notes](release-notes/v1.4.0.md).
