# Release Notes

## v1.6.2 — April 14, 2026

Input sanitization hardening and minor UI polish.

### Destination identifier sanitization

Pipeline creation now sanitizes destination identifiers before writing the pipeline config, preventing invalid characters from reaching the database/broker/vector store. Applies to:

- Postgres `dbName`, `schema`, `table`
- MongoDB `dbName`, `table`
- Kafka `topic`
- ActiveMQ `queueName`
- Vector (Qdrant/Milvus/Chroma/pgvector) `collectionName`, `tableName`, `schemaName`
- Data-quality schema name in the inline DQ/TX editor

### Shared sanitization utility

Consolidated four duplicated `sanitizeName` helpers across Discovery, Data Catalog, Tap Create, and Pipeline Create into a single `ui/src/app/shared/sanitize.ts` module exporting `sanitizeLabel` and `sanitizeIdentifier`.

### UI polish

- Bumped contrast of the environment/version badge in the header for readability.

---

See [archived release notes](release-notes/) for prior versions.
