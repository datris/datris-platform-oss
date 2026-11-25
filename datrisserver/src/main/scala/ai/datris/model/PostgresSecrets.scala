package ai.datris.model

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

/** The platform Postgres secret. `username`/`password` are the loading login
  * (loads, deletes, migrations, metadata listings, the health probe).
  * `queryUsername`/`queryPassword` are optional (null when the secret does
  * not carry them): a separate login that read-back queries connect with
  * instead; see SecretsRetrieverUtil.postgresQueryLogin. */
case class PostgresSecrets(
    username: String,
    password: String,
    jdbcUrl: String,
    queryUsername: String = null,
    queryPassword: String = null
)
