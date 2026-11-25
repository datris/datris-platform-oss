package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{DatrisEnvironment, DatrisException}
import ai.datris.model.{KafkaProducerSecrets, MongoDBSecrets, PostgresSecrets}

object SecretsRetrieverUtil {
    def postgresSecrets(): PostgresSecrets = {
        val dbSecret = SecretsUtil.getSecretMap(DatrisEnvironment.current.postgresSecretName)
            .getOrElse(throw new DatrisException(
                "Could not retrieve database information from Secrets Manager, secret name: " + DatrisEnvironment.current.postgresSecretName
            ))
        val username = dbSecret.get("username")
        if (username == null)
            throw new DatrisException("Could not retrieve the Postgres username from Secrets Manager")
        val password = dbSecret.get("password")
        if (password == null)
            throw new DatrisException("Could not retrieve the Postgres password from Secrets Manager")
        val jdbcUrl = dbSecret.get("jdbcUrl")
        if (jdbcUrl == null)
            throw new DatrisException("Could not retrieve the Postgres jdbcUrl from Secrets Manager")
        PostgresTlsGuard.validate(jdbcUrl, "platform Postgres")

        PostgresSecrets(
            username,
            password,
            jdbcUrl,
            // Optional: a separate login for read-back queries (null when absent).
            dbSecret.get("queryUsername"),
            dbSecret.get("queryPassword")
        )
    }

    /** True when `value` carries something other than whitespace. */
    private def present(value: String): Boolean = value != null && value.trim.nonEmpty

    /** True when the Postgres secret carries either query key (a non-blank
      * `queryUsername` or `queryPassword`). */
    def hasQueryLogin(secrets: PostgresSecrets): Boolean =
        present(secrets.queryUsername) || present(secrets.queryPassword)

    /** True when read-back queries connect with a login other than the
      * loading login: both query keys set, naming a different user. Never
      * throws; a half-configured secret answers false (its queries are
      * refused by [[postgresQueryLogin]] before they connect). */
    def usesQueryLogin(secrets: PostgresSecrets): Boolean =
        present(secrets.queryUsername) && present(secrets.queryPassword) && secrets.queryUsername != secrets.username

    /** The (user, password) a read-back query connects with: the secret's
      * `queryUsername`/`queryPassword` when both are set, exactly as written;
      * the loading `username`/`password` when neither is. A key that is
      * missing, empty or only whitespace counts as absent. One without the
      * other is refused with a DatrisException naming the missing key (never
      * a fall back to the loading login). A `queryUsername` equal to the
      * loading `username` is the loading login when `queryPassword` equals
      * `password`, and is refused when it does not. No refusal message carries
      * a value of the secret. Pure. */
    def postgresQueryLogin(secrets: PostgresSecrets): (String, String) = {
        val hasUser = present(secrets.queryUsername)
        val hasPass = present(secrets.queryPassword)
        if (hasUser && hasPass) {
            if (secrets.queryUsername != secrets.username) (secrets.queryUsername, secrets.queryPassword)
            // The loading user again: with its own password this is just the
            // loading login; with another password it would share the loading
            // login's pool under two passwords, so it is refused.
            else if (secrets.queryPassword == secrets.password) (secrets.username, secrets.password)
            else
                throw new DatrisException(
                    "The Postgres secret's query login (queryUsername) is the same PostgreSQL user as the loading login (username), " +
                        "with a different password. The query login must be a different PostgreSQL user from the loading login: " +
                        "set queryUsername to a separate read-only user, or remove queryUsername and queryPassword to query with the loading login."
                )
        } else if (!hasUser && !hasPass) (secrets.username, secrets.password)
        else if (hasUser)
            throw new DatrisException(
                "The Postgres secret names a query user (queryUsername) but no query password (queryPassword). " +
                    "Add queryPassword to the Postgres secret, or remove queryUsername to query with the loading login."
            )
        else
            throw new DatrisException(
                "The Postgres secret has a query password (queryPassword) but no query user (queryUsername). " +
                    "Add queryUsername to the Postgres secret, or remove queryPassword to query with the loading login."
            )
    }

    def kafkaProducerSecrets(): KafkaProducerSecrets = {
        val secretName = DatrisEnvironment.current.kafkaProducerSecretName
        val secret = SecretsUtil.getSecretMap(secretName)
            .getOrElse(throw new DatrisException("Could not retrieve Kafka producer information from Secrets Manager, secret name: " + secretName))
        val bootstrapServers = secret.get("bootstrapServers")
        if (bootstrapServers == null)
            throw new DatrisException("Could not retrieve the Kafka producer bootstrapServers from Secrets Manager")

        KafkaProducerSecrets(
            bootstrapServers,
            secret.get("username"),
            secret.get("password")
        )
    }

    /** Read a secret whose name comes from user-authored configuration (a
      * pipeline, a tap, a code-repo setting, a vector store or embedding
      * reference). Refuses the server-managed secrets (SecretNames.ServerManaged)
      * with a DatrisException before any read, so a saved config that names
      * one fails closed instead of handing its values to a destination or a
      * script. Server-internal reads of its own secrets use SecretsUtil. */
    def userSecret(name: String): Option[java.util.Map[String, String]] = {
        SecretNames.requireNotServerManaged(name)
        SecretsUtil.getSecretMap(name)
    }

    /** Name → fields for every Platform-tab secret in the current environment:
     *  all secrets NOT tagged _type=tap. Secrets without a _type predate the
     *  tag and count as platform. Single source of the filter behind the UI's
     *  Platform tab, the type=platform branch of GET /secrets, and the
     *  external-SaaS credential scan on GET /destinations/available. */
    def platformSecrets(): List[(String, java.util.Map[String, String])] = {
        val env = DatrisEnvironment.current.environment
        // A name created directly in Vault that SecretNames refuses cannot be
        // read through the secret store; skip it rather than fail the list.
        SecretsUtil.listSecrets(env).filter(name => SecretNames.isSafePath(env + "/" + name)).flatMap(name => {
            SecretsUtil.getSecretMap(env + "/" + name)
                .filter(m => {
                    val t = m.get("_type")
                    t == null || !"tap".equals(t)
                })
                .map(m => (name, m))
        })
    }

    def mongoDbSecrets(): MongoDBSecrets = {
        val dbSecret = SecretsUtil.getSecretMap(DatrisEnvironment.current.mongoDbSecretName)
            .getOrElse(throw new DatrisException(
                "Could not retrieve database information from Secrets Manager, secret name: " + DatrisEnvironment.current.mongoDbSecretName
            ))
        val connectionString = dbSecret.get("connectionString")
        if (connectionString == null)
            throw new DatrisException("Could not retrieve the MongoDB connectionString from Secrets Manager")

        MongoDBSecrets(
            connectionString
        )
    }
}
