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
            .getOrElse(throw new DatrisException("Could not retrieve database information from Secrets Manager, secret name: " + DatrisEnvironment.current.postgresSecretName))
        val username = dbSecret.get("username")
        if (username == null)
            throw new DatrisException("Could not retrieve the Postgres username from Secrets Manager")
        val password = dbSecret.get("password")
        if (password == null)
            throw new DatrisException("Could not retrieve the Postgres password from Secrets Manager")
        val jdbcUrl = dbSecret.get("jdbcUrl")
        if (jdbcUrl == null)
            throw new DatrisException("Could not retrieve the Postgres jdbcUrl from Secrets Manager")

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

    def mongoDbSecrets(): MongoDBSecrets = {
        val dbSecret = SecretsUtil.getSecretMap(DatrisEnvironment.current.mongoDbSecretName)
            .getOrElse(throw new DatrisException("Could not retrieve database information from Secrets Manager, secret name: " + DatrisEnvironment.current.mongoDbSecretName))
        val connectionString = dbSecret.get("connectionString")
        if (connectionString == null)
            throw new DatrisException("Could not retrieve the MongoDB connectionString from Secrets Manager")

        MongoDBSecrets(
            connectionString
        )
    }
}
