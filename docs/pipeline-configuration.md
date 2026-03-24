# Pipeline Configuration

Pipelines are configured entirely through JSON. Each pipeline defines a source, optional processing steps (preprocessing, data quality, transformation), and one or more destinations.

## Full Configuration Example

```json
{
  "name": "sales_data",
  "source": {
    "schemaProperties": {
      "fields": [
        {"name": "order_id", "type": "int"},
        {"name": "customer_name", "type": "string"},
        {"name": "amount", "type": "double"},
        {"name": "order_date", "type": "date"},
        {"name": "region", "type": "string"}
      ]
    },
    "fileAttributes": {
      "csvAttributes": {
        "delimiter": ",",
        "header": true,
        "encoding": "UTF-8"
      }
    }
  },
  "preprocessor": {
    "endpoint": "http://my-service:8080/preprocess",
    "async": false,
    "bearerToken": "my-token",
    "timeoutSeconds": 300
  },
  "dataQuality": {
    "validateFileHeader": true,
    "columnRules": [
      {
        "columnName": "amount",
        "function": "regex",
        "parameter": "^[0-9]+(\\.[0-9]+)?$",
        "onFailureIsError": true,
        "description": "Amount must be a positive number"
      }
    ],
    "rowRules": [
      {
        "function": "javascript",
        "parameters": ["validate_sales.js"],
        "onFailureIsError": false
      }
    ]
  },
  "transformation": {
    "trimColumnWhitespace": true,
    "deduplicate": true,
    "rowFunctions": [
      {
        "function": "javascript",
        "parameters": ["transform_sales.js"]
      },
      {
        "function": "restEndpoint",
        "parameters": ["http://my-service:5600/transform/rest/row", "row", "30000"]
      }
    ]
  },
  "destination": {
    "objectStore": {
      "prefixKey": "sales/daily",
      "fileFormat": "parquet",
      "partitionBy": ["region"],
      "deleteBeforeWrite": false,
      "writeMode": "append"
    },
    "database": {
      "dbName": "analytics",
      "schema": "public",
      "table": "sales",
      "keyFields": ["order_id"],
      "usePostgres": true,
      "truncateBeforeWrite": false,
      "useTransaction": true
    },
    "kafka": {
      "topic": "sales-events",
      "keyField": "order_id"
    }
  }
}
```

## Configuration Fields

### Top Level

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `name` | string | Yes | Pipeline name (max 80 characters) |
| `source` | object | Yes | Source configuration |
| `preprocessor` | object | No | Optional REST endpoint called before processing |
| `dataQuality` | object | No | Data validation rules |
| `transformation` | object | No | Data transformation settings |
| `destination` | object | Yes | One or more output destinations |

### Source

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `schemaProperties` | object | Yes* | Schema definition with field names and types. *Required for structured/semi-structured data |
| `fileAttributes` | object | No | File format configuration (CSV, JSON, XML, XLS, unstructured) |
| `databaseAttributes` | object | No | Database pull configuration |
| `streamAttributes` | object | No | Stream source configuration (Kafka) |

### Source > Schema Properties

| Field | Type | Description |
|-------|------|-------------|
| `dbName` | string | Database name associated with this schema (used by some destinations) |
| `fields` | array | List of `{"name": "...", "type": "..."}` objects |

Supported types: `boolean`, `int`, `tinyint`, `smallint`, `bigint`, `float`, `double`, `decimal(p,s)`, `string`, `varchar(n)`, `char(n)`, `date`, `timestamp`

### Source > File Attributes

**CSV:**
```json
"csvAttributes": {
  "delimiter": ",",
  "header": true,
  "encoding": "UTF-8"
}
```

**JSON:**
```json
"jsonAttributes": {
  "everyRowContainsObject": true,
  "encoding": "UTF-8"
}
```

**XML:**
```json
"xmlAttributes": {
  "everyRowContainsObject": true,
  "encoding": "UTF-8"
}
```

**Excel:**
```json
"xlsAttributes": {
  "worksheet": 0,
  "tempCsvFileDelimiter": ","
}
```

**Unstructured:**
```json
"unstructuredAttributes": {
  "fileExtension": "pdf",
  "preserveFilename": true
}
```

### Source > Database Attributes

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `type` | string | Yes | `postgres`, `mysql`, or `mssql` |
| `postgresSecretsName` | string | Conditional | Vault secret name for Postgres credentials |
| `mssqlSecretsName` | string | Conditional | Vault secret name for MSSQL credentials |
| `mysqlSecretsName` | string | Conditional | Vault secret name for MySQL credentials |
| `cronExpression` | string | Yes | Cron schedule for polling (e.g., `0 */5 * * * ?`) |
| `database` | string | No | Database name |
| `schema` | string | No | Schema name |
| `table` | string | Yes* | Table to query (*unless `sqlOverride` is set) |
| `includeFields` | array | No | Column whitelist |
| `timestampFieldName` | string | Yes* | Column for incremental pulls (*unless `sqlOverride` is set) |
| `sqlOverride` | string | No | Custom SELECT query (replaces auto-generated query) |
| `outputDelimiter` | string | No | Delimiter for CSV output (default `,`) |

### Preprocessor

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `endpoint` | string | Required | URL of the preprocessing service |
| `async` | boolean | `false` | If true, returns immediately |
| `bearerToken` | string | null | Authorization bearer token |
| `timeoutSeconds` | int | 300 | Request timeout |

### Data Quality

See [Data Quality](data-quality/column-rules.md) for detailed documentation.

| Field | Type | Description |
|-------|------|-------------|
| `validateFileHeader` | boolean | Validate CSV header matches schema field order |
| `validationSchema` | string | Path to JSON Schema file for JSON/XML validation |
| `columnRules` | array | List of regex column validation rules |
| `rowRules` | array | List of JavaScript or REST endpoint row rules |

### Transformation

See [JavaScript Row Functions](transformation/row-functions.md) and [REST Endpoint Transformation](transformation/rest-endpoint.md) for detailed documentation.

| Field | Type | Description |
|-------|------|-------------|
| `trimColumnWhitespace` | boolean | Trim whitespace from all column values |
| `deduplicate` | boolean | Remove duplicate rows |
| `rowFunctions` | array | List of transformation functions (`"javascript"` or `"restEndpoint"`) |

### Destination > Object Store

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `prefixKey` | string | Required | S3 path prefix for output files |
| `fileFormat` | string | `parquet` | Output format: `parquet` or `orc` |
| `partitionBy` | array | null | Column names for partitioning |
| `destinationBucketOverride` | string | null | Custom bucket (default: `{environment}-data`) |
| `deleteBeforeWrite` | boolean | false | Delete existing data at path before writing |
| `writeToTemporaryLocation` | boolean | false | Write to temp location first |
| `writeMode` | string | `append` | `append`, `overwrite`, `ignore`, or `errorifexists` |

### Destination > Database

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `dbName` | string | Required | Database name |
| `schema` | string | Required | Schema name |
| `table` | string | Required | Table name |
| `keyFields` | array | null | Primary key columns (enables upsert for MongoDB) |
| `usePostgres` | boolean | false | Write to PostgreSQL |
| `useMongoDB` | boolean | false | Write to MongoDB |
| `manageTableManually` | boolean | false | If false, auto-creates tables |
| `truncateBeforeWrite` | boolean | false | Truncate table before loading |
| `useTransaction` | boolean | false | Wrap in a transaction |
| `options` | array | null | Custom COPY options (e.g., `["FORMAT csv", "DELIMITER ','"]`) |

### Destination > Kafka

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `topic` | string | Required | Kafka topic name |
| `keyField` | string | null | Column to use as message key |
| `overrideBootstrapServers` | string | null | Custom bootstrap servers |
| `timeoutMs` | int | 10000 | Producer timeout |

### Destination > ActiveMQ

| Field | Type | Description |
|-------|------|-------------|
| `queueName` | string | ActiveMQ queue name |

### Destination > REST Endpoint

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `endpoint` | string | Required | URL to POST data to |
| `async` | boolean | false | If true, doesn't wait for response |
| `bearerToken` | string | null | Authorization token |
| `timeoutSeconds` | int | 300 | Request timeout |

### Destination > Schema Properties (Optional)

Define a separate destination schema if column mapping differs from the source:

```json
"destination": {
  "schemaProperties": {
    "dbName": "analytics",
    "fields": [
      {"name": "order_id", "type": "int"},
      {"name": "customer", "type": "string"}
    ]
  }
}
```

## Multiple Destinations

A single pipeline can write to multiple destinations simultaneously. All destinations execute in parallel:

```json
"destination": {
  "objectStore": { ... },
  "database": { ... },
  "kafka": { ... },
  "activeMQ": { ... },
  "restEndpoint": { ... }
}
```
