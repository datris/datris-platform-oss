# Database Source Pulling

The pipeline can pull data directly from relational databases on a schedule. Configure a database source, set a cron expression, and the pipeline handles connection management, incremental extraction, and secret retrieval from Vault.

## Supported Databases

| Database | JDBC Driver |
|---|---|
| PostgreSQL | `org.postgresql.Driver` |
| MySQL | `com.mysql.cj.jdbc.Driver` |
| MSSQL | `com.microsoft.sqlserver.jdbc.SQLServerDriver` |

## Configuration

Database sources are configured in the `databaseAttributes` section of a dataset configuration. Connection credentials (JDBC URL, username, password) are stored in Vault — not in the dataset config itself.

```json
{
  "name": "customer_sync",
  "source": {
    "databaseAttributes": {
      "type": "postgres",
      "postgresSecretsName": "oss/postgres",
      "database": "production",
      "schema": "public",
      "table": "customers",
      "cronExpression": "0 */15 * * * ?",
      "timestampFieldName": "updated_at",
      "includeFields": ["id", "email", "name", "updated_at", "status"]
    },
    "schemaProperties": {
      "fields": [
        { "name": "id", "type": "bigint" },
        { "name": "email", "type": "varchar(255)" },
        { "name": "name", "type": "varchar(200)" },
        { "name": "updated_at", "type": "timestamp" },
        { "name": "status", "type": "varchar(20)" }
      ]
    }
  },
  "destination": {
    "database": {
      "dbName": "idata",
      "schema": "public",
      "table": "customers",
      "usePostgres": true
    }
  }
}
```

## Configuration Reference

| Property | Required | Description |
|---|---|---|
| `type` | Yes | One of `postgres`, `mysql`, `mssql` |
| `postgresSecretsName` | Conditional | Vault secret name for PostgreSQL credentials |
| `mysqlSecretsName` | Conditional | Vault secret name for MySQL credentials |
| `mssqlSecretsName` | Conditional | Vault secret name for MSSQL credentials |
| `cronExpression` | Yes | Quartz-format cron expression controlling the pull schedule |
| `database` | No | Database name (if not in the JDBC URL) |
| `schema` | No | Schema within the database |
| `table` | Yes* | Table to pull data from (*unless `sqlOverride` is set) |
| `timestampFieldName` | No | Column used for incremental pulls |
| `includeFields` | No | Array of column names to select; when omitted, all columns are selected |
| `sqlOverride` | No | Custom SQL query that replaces the generated SELECT statement |
| `outputDelimiter` | No | Delimiter for CSV output (default `,`) |

## Secrets in Vault

Database credentials are never stored in the dataset configuration. Instead, the pipeline reads them from HashiCorp Vault using the secret name configured above.

The Vault secret must contain `username`, `password`, and `jdbcUrl` keys:

```json
{
  "username": "pipeline_reader",
  "password": "s3cureP@ss",
  "jdbcUrl": "jdbc:postgresql://db.internal.example.com:5432/production"
}
```

Store the credentials in Vault using the CLI:

```bash
vault kv put oss/postgres \
  username="pipeline_reader" \
  password="s3cureP@ss" \
  jdbcUrl="jdbc:postgresql://db.internal.example.com:5432/production"
```

## Cron-Based Scheduling

The `cronExpression` field accepts a Quartz cron string with six fields (seconds, minutes, hours, day-of-month, month, day-of-week):

| Expression | Schedule |
|---|---|
| `0 */15 * * * ?` | Every 15 minutes |
| `0 0 * * * ?` | Every hour on the hour |
| `0 0 2 * * ?` | Daily at 02:00 |
| `0 0 0 ? * MON` | Every Monday at midnight |

## Incremental Pulls

When `timestampFieldName` is set, the pipeline tracks the maximum value of that column after each pull. On the next execution, it adds a `WHERE {timestampFieldName} > {lastMaxValue}` clause to fetch only new or updated rows.

The high-water mark is stored in MongoDB in the `{environment}-data-pull` collection. When `timestampFieldName` is omitted, every pull fetches the full table contents.

## Custom SQL

Set `sqlOverride` to run an arbitrary query instead of a simple `SELECT ... FROM table`:

```json
{
  "databaseAttributes": {
    "type": "postgres",
    "postgresSecretsName": "oss/postgres",
    "sqlOverride": "SELECT o.id, o.total, c.email FROM orders o JOIN customers c ON o.customer_id = c.id",
    "cronExpression": "0 */30 * * * ?"
  }
}
```

## Field Filtering

Use `includeFields` to select a subset of columns from the source table:

```json
{
  "databaseAttributes": {
    "table": "users",
    "includeFields": ["id", "username", "email", "created_at"]
  }
}
```

When `sqlOverride` is set, `includeFields` is ignored because the SQL query already defines the column list.

## Troubleshooting

| Symptom | Check |
|---|---|
| Connection refused | Verify the `jdbcUrl` in Vault is correct and the database allows connections from the pipeline host. |
| Authentication failed | Confirm the Vault secret name is correct and contains valid `username`/`password`/`jdbcUrl` keys. |
| Zero rows returned on incremental pull | The high-water mark in MongoDB may already be ahead of the data. Delete the entry in the `{environment}-data-pull` collection to reset it. |
| SQL syntax error | When using `sqlOverride`, test the query directly against the database first. |
