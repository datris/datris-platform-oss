# Configuration Reference

The pipeline server is configured via `application.yaml` (or `application.properties`). This page documents all available properties.

## Full Reference

### Spring Boot

| Property | Default | Description |
|----------|---------|-------------|
| `spring.servlet.multipart.max-file-size` | `1GB` | Maximum upload file size |
| `spring.servlet.multipart.max-request-size` | `1GB` | Maximum request size |
| `spring.server.tomcat.connection-timeout` | `600000` | Tomcat connection timeout (ms) |

### Logging

| Property | Default | Description |
|----------|---------|-------------|
| `logging.level.root` | `INFO` | Root log level |
| `logging.level.org.springframework.web` | `INFO` | Spring web log level |
| `logging.level.net.datris.pipeline` | `INFO` | Pipeline log level |

### Scheduling

| Property | Default | Description |
|----------|---------|-------------|
| `schedule.checkFileNotifierQueue` | `5000` | Polling interval for file notification queue (ms) |
| `schedule.findJobsToStart` | `5000` | Interval to check for queued jobs (ms) |
| `schedule.checkDatabaseSourceQueries` | `30000` | Interval to check for database pulls (ms) |

### Pipeline

| Property | Default | Description |
|----------|---------|-------------|
| `environment` | `oss` | Environment name. Used as prefix for bucket names (`oss-raw`, `oss-data`, etc.) and table names |
| `useApiKeys` | `false` | Enable API key authentication |
| `sendPipelineNotifications` | `true` | Enable pipeline event notifications |
| `ttlFileNotifierQueueMessages` | `60` | Days to retain processed message IDs for deduplication |

### MinIO (Object Store)

| Property | Description |
|----------|-------------|
| `minio.server` | MinIO endpoint URL (e.g., `http://localhost:9000`) |

MinIO credentials are stored in Vault under the secret specified by `secrets.minIOSecretName`:
```json
{
  "accessKey": "minioadmin",
  "secretKey": "minioadmin"
}
```

### Secrets (HashiCorp Vault)

| Property | Description |
|----------|-------------|
| `secrets.apiKeysSecretName` | Vault path for API keys |
| `secrets.postgresSecretName` | Vault path for PostgreSQL credentials |
| `secrets.minIOSecretName` | Vault path for MinIO credentials |
| `secrets.activeMQSecretName` | Vault path for ActiveMQ credentials |
| `secrets.mongoDbSecretName` | Vault path for MongoDB credentials |
| `secrets.kafkaProducerSecretName` | Vault path for Kafka producer credentials |
| `secrets.embeddingSecretName` | Vault path for embedding model credentials |
| `secrets.qdrantSecretName` | Vault path for Qdrant connection |
| `secrets.weaviateSecretName` | Vault path for Weaviate connection |
| `secrets.milvusSecretName` | Vault path for Milvus connection |
| `secrets.chromaSecretName` | Vault path for Chroma connection |
| `secrets.pgvectorSecretName` | Vault path for pgvector PostgreSQL connection |

Vault connection is configured via environment variables:
- `VAULT_ADDR` - Vault server URL (e.g., `http://vault:8200`)
- `VAULT_TOKEN` - Authentication token

### ActiveMQ (Queue & Notifications)

| Property | Description |
|----------|-------------|
| `activemq.server` | ActiveMQ broker URL (e.g., `tcp://localhost:61616`) |

ActiveMQ credentials are stored in Vault under `secrets.activeMQSecretName`:
```json
{
  "username": "admin",
  "password": "admin"
}
```

### MongoDB (NoSQL Store)

| Property | Description |
|----------|-------------|
| `mongodb.connectionString` | MongoDB connection URI (e.g., `mongodb://localhost:27017`) |
| `mongodb.database` | Database name for pipeline metadata |

### Kafka Consumer (Optional)

| Property | Default | Description |
|----------|---------|-------------|
| `kafkaConsumer.enabled` | `false` | Enable Kafka topic consumption |
| `kafkaConsumer.bootstrapServers` | | Kafka broker address |
| `kafkaConsumer.groupId` | | Consumer group ID |
| `kafkaConsumer.topicPollingInterval` | `500` | Topic polling interval (ms) |
| `kafkaConsumer.topicPrefix` | | Prefix for topic names |

### AI Schema Generation (Optional)

| Property | Default | Description |
|----------|---------|-------------|
| `ai.enabled` | `false` | Enable the AI schema generation endpoint |
| `ai.provider` | `anthropic` | AI provider — `anthropic` or `openai` |
| `ai.aiSecretName` | | Vault secret name containing `endpoint`, `model`, and `apiKey` |

See [AI Schema Generation](api-reference/schema-generation-api.md) for full setup details.

## Vault Secret Formats

### PostgreSQL
```json
{
  "username": "postgres",
  "password": "password",
  "jdbcUrl": "jdbc:postgresql://localhost:5432"
}
```

### MySQL
```json
{
  "username": "root",
  "password": "password",
  "jdbcUrl": "jdbc:mysql://localhost:3306"
}
```

### Kafka Producer
```json
{
  "bootstrapServers": "kafka:9092",
  "username": null,
  "password": null
}
```

### MongoDB
```json
{
  "connectionString": "mongodb://localhost:27017"
}
```

## MinIO Buckets

The following buckets are created automatically by the `minio-init` container:

| Bucket | Purpose |
|--------|---------|
| `{environment}-raw` | File upload staging |
| `{environment}-raw-plus` | Processed file staging |
| `{environment}-temp` | Temporary processing files |
| `{environment}-data` | Object store destination output |
| `{environment}-config` | Configuration files (validation schemas) |

## MongoDB Collections

| Collection | Purpose |
|------------|---------|
| `{environment}-pipeline` | Pipeline configurations |
| `{environment}-pipeline-status` | Job processing status |
| `{environment}-archived-metadata` | File ingestion metadata |
| `{environment}-file-notifier-message` | Processed message deduplication |
| `{environment}-data-pull` | Database pull scheduling state |
