# PostgreSQL Destination

The PostgreSQL destination performs bulk inserts using the PostgreSQL `COPY` command. It streams data directly into the target table, making it significantly faster than row-by-row inserts.

## Connection

Connection credentials are retrieved from Vault using the secret name specified in `postgresSecretName`. The secret must contain the following keys:

| Key        | Description                              |
|------------|------------------------------------------|
| `jdbcUrl`  | JDBC connection string for the database  |
| `username` | Database user                            |
| `password` | Database password                        |

## Table Management

When `manageTableManually` is set to `false` (the default), the pipeline auto-creates the target table if it does not exist. The table schema is derived from the dataset's source schema using the type mapping below.

Set `manageTableManually` to `true` if you want to create and manage the table yourself. In this case the table must already exist and its column types must be compatible with the incoming data.

## Type Mapping

Source schema types are mapped to PostgreSQL types as follows:

| Source Type | PostgreSQL Type |
|-------------|-----------------|
| `tinyint`   | `int2`          |
| `smallint`  | `int2`          |
| `int`       | `int4`          |
| `bigint`    | `int8`          |
| `float`     | `float4`        |
| `double`    | `float8`        |
| `string`    | `text`          |
| `boolean`   | `boolean`       |
| `date`      | `date`          |
| `timestamp` | `timestamp`     |
| `*_json`    | `json`          |
| `*_xml`     | `xml`           |

Column names ending in `_json` are stored as the PostgreSQL `json` type. Column names ending in `_xml` are stored as the PostgreSQL `xml` type.

## Truncate Before Write

Set `truncateBeforeWrite` to `true` to issue a `TRUNCATE` statement on the target table before loading data. This replaces the full contents of the table on each run.

## Transaction Support

All writes execute inside a database transaction. If any error occurs during the `COPY` operation, the transaction is rolled back and no partial data is committed.

## Primary Key

Specify one or more columns in `keyFields` to define the primary key constraint on the target table. When `manageTableManually` is `false`, the auto-created table includes a `PRIMARY KEY` constraint on these columns.

## Custom COPY Options

Use the `options` array to pass additional PostgreSQL `COPY` options. Each entry is appended to the `COPY` command.

## Configuration Example

```json
{
  "name": "orders_pipeline",
  "source": { "..." : "..." },
  "destination": {
    "database": {
      "dbName": "analytics",
      "schema": "public",
      "table": "orders",
      "usePostgres": true,
      "manageTableManually": false,
      "truncateBeforeWrite": true,
      "keyFields": ["order_id"],
      "options": ["NULL 'NA'"]
    }
  }
}
```

Connection credentials are configured globally in `application.yaml` via `secrets.postgresSecretName`, not per dataset. See [Configuration Reference](../configuration-reference.md) for Vault secret format.

### Field Reference

| Field                 | Required | Default  | Description                                      |
|-----------------------|----------|----------|--------------------------------------------------|
| `dbName`              | yes      |          | Target database name                             |
| `schema`              | no       | `public` | Target schema                                    |
| `table`               | yes      |          | Target table name                                |
| `usePostgres`         | yes      | `false`  | Must be `true` to enable PostgreSQL destination  |
| `manageTableManually` | no       | `false`  | If `true`, skip auto-creation of the table       |
| `truncateBeforeWrite` | no       | `false`  | Truncate the table before loading                |
| `keyFields`           | no       |          | Array of column names for the primary key        |
| `useTransaction`      | no       | `false`  | Wrap the COPY in a database transaction          |
| `options`             | no       |          | Array of additional COPY option strings          |
