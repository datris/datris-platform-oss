package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
*/

import ai.datris.model.{DatrisEnvironment, DatrisException}

import scala.collection.JavaConverters._

object APIKeyValidator {
    def validate(apiKey: String): Unit = {
        if(DatrisEnvironment.values.multiTenant) {
            // In multi-tenant mode, validation is handled by TenantInterceptor
            return
        }
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

    /** Validates the API key and resolves the tenant environment name.
      * Returns Some(environmentName) when multiTenant is true, None otherwise. */
    def validateAndResolve(apiKey: String): Option[String] = {
        if(DatrisEnvironment.values.multiTenant) {
            if(apiKey == null || apiKey.isEmpty)
                return None // No API key — fall back to global environment

            val mappings = ai.datris.util.SecretsUtil.getSecretMap("api-key-mappings")
                .getOrElse(return None) // Mappings not found — fall back to global
            val environment = mappings.asScala.get(apiKey)

            environment // Some(env) if found, None if not (falls back to global)
        } else {
            validate(apiKey)
            None
        }
    }
}
