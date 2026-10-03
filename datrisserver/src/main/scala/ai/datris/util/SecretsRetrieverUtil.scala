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
            jdbcUrl
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
