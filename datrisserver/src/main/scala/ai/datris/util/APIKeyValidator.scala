package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
*/

import ai.datris.model.{DatrisEnvironment, DatrisException}

import scala.collection.JavaConverters._

object APIKeyValidator {
    def validate(apiKey: String): Unit = {
        if(DatrisEnvironment.values.useApiKeys) {
            if(apiKey == null)
                throw new DatrisException("x-api-key does not exist or is invalid")


            val apiKeysMap = ai.datris.util.SecretsUtil.getSecretMap(DatrisEnvironment.values.apiKeysSecretName)
                .getOrElse(throw new DatrisException("The Secrets Manager entry for value: " + DatrisEnvironment.values.apiKeysSecretName + " was not found"))
            val apiKeys = apiKeysMap.asScala.map { case (key, value) => value }.toList

            if(! apiKeys.contains(apiKey))
                throw new DatrisException("Invalid x-api-key: " + apiKey)
        }
    }
}
