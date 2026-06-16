package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
*/

import ai.datris.model.{DatrisEnvironment, DatrisException, ObjectStore}

case class ResolvedObjectStoreCredentials(
    accessKey: Option[String],
    secretKey: Option[String],
    sessionToken: Option[String],
    region: Option[String]
)

/** Snowflake destination credentials, resolved from a Platform-tab secret named
 *  by `Database.credentialsSecret`. `account` lives in the secret (bound to the
 *  credential, same call S3 makes for `region`). Auth is key-pair by default
 *  (privateKey [+ passphrase]); password is the fallback. */
case class ResolvedSnowflakeCredentials(
    account: String,
    user: String,
    privateKey: Option[String],
    privateKeyPassphrase: Option[String],
    password: Option[String]
)

object CredentialResolver {

    def resolve(objectStore: ObjectStore): ResolvedObjectStoreCredentials = {
        val provider = Option(objectStore.provider).getOrElse("minio").toLowerCase
        provider match {
            case "minio" => resolveMinIO()
            case "s3"    => resolveS3(objectStore.credentialsSecret)
            case other   => throw new DatrisException("Unknown objectStore.provider: '" + other + "'. Expected 'minio' or 's3'.")
        }
    }

    private def resolveMinIO(): ResolvedObjectStoreCredentials = {
        val cfg = DatrisEnvironment.current.minIOConfig
        if (cfg == null)
            ResolvedObjectStoreCredentials(None, None, None, None)
        else
            ResolvedObjectStoreCredentials(
                accessKey = Option(cfg.accessKey),
                secretKey = Option(cfg.secretKey),
                sessionToken = None,
                region = None
            )
    }

    private def resolveS3(secretName: String): ResolvedObjectStoreCredentials = {
        if (secretName == null || secretName.trim.isEmpty) {
            // Fall back to AWS DefaultAWSCredentialsProviderChain (instance role,
            // env vars, ~/.aws/credentials). Region also comes from the chain.
            return ResolvedObjectStoreCredentials(None, None, None, None)
        }
        // Vault stores secrets at "<environment>/<name>" — same convention as
        // SecretsAPIController and TapScriptRunner. The agent / wizard passes
        // the bare name (matches what list_platform_secrets returns); we add
        // the environment prefix here.
        val secretPath = DatrisEnvironment.current.environment + "/" + secretName
        val secret = SecretsUtil.getSecretMap(secretPath)
            .getOrElse(throw new DatrisException("S3 credentialsSecret not found in Secrets Manager at path '" + secretPath + "' (looked up by name '" + secretName + "'). Create it on Configuration → Secrets → Platform."))
        // Field-name lookups are case-insensitive (and accept underscores or hyphens)
        // because the UI's Secrets form leaves naming to the operator — a credential
        // pasted in as AWS_ACCESS_KEY shouldn't fail just because the resolver expects
        // accessKey.
        def field(canonical: String, aliases: String*): Option[String] = {
            val candidates = (canonical +: aliases).flatMap(n => Seq(n, n.toLowerCase, n.toUpperCase))
            candidates.iterator.map(secret.get).find(_ != null)
        }
        val accessKey = field("accessKey", "access_key", "access-key", "AWS_ACCESS_KEY", "AWS_ACCESS_KEY_ID")
            .getOrElse(throw new DatrisException("S3 credentialsSecret '" + secretName + "' is missing required field 'accessKey' (also accepted: AWS_ACCESS_KEY, AWS_ACCESS_KEY_ID, access_key)"))
        val secretKey = field("secretKey", "secret_key", "secret-key", "AWS_SECRET_KEY", "AWS_SECRET_ACCESS_KEY")
            .getOrElse(throw new DatrisException("S3 credentialsSecret '" + secretName + "' is missing required field 'secretKey' (also accepted: AWS_SECRET_KEY, AWS_SECRET_ACCESS_KEY, secret_key)"))
        val region = field("region", "AWS_REGION", "aws_region")
            .getOrElse(throw new DatrisException("S3 credentialsSecret '" + secretName + "' is missing required field 'region' (also accepted: AWS_REGION). Region lives in the credentials secret alongside accessKey/secretKey so it stays bound to the credential that authorizes it."))
        val sessionToken = field("sessionToken", "session_token", "AWS_SESSION_TOKEN")
        ResolvedObjectStoreCredentials(
            accessKey = Some(accessKey),
            secretKey = Some(secretKey),
            sessionToken = sessionToken,
            region = Some(region)
        )
    }

    /** Resolve Snowflake destination credentials from the Platform-tab secret named
     *  by `Database.credentialsSecret`. Mirrors resolveS3: same env-prefixed Vault
     *  path, same case/underscore-insensitive field lookup, same actionable error
     *  pointing the user at Configuration → Secrets → Platform. */
    def resolveSnowflake(secretName: String): ResolvedSnowflakeCredentials = {
        if (secretName == null || secretName.trim.isEmpty)
            throw new DatrisException("Snowflake destination requires a credentialsSecret naming a Platform-tab secret (with fields account, user, and privateKey or password). Create it on Configuration → Secrets → Platform, then set it on the destination.")

        val secretPath = DatrisEnvironment.current.environment + "/" + secretName
        val secret = SecretsUtil.getSecretMap(secretPath)
            .getOrElse(throw new DatrisException("Snowflake credentialsSecret not found in Secrets Manager at path '" + secretPath + "' (looked up by name '" + secretName + "'). Create it on Configuration → Secrets → Platform."))

        def field(canonical: String, aliases: String*): Option[String] = {
            val candidates = (canonical +: aliases).flatMap(n => Seq(n, n.toLowerCase, n.toUpperCase))
            candidates.iterator.map(secret.get).find(_ != null)
        }

        val account = field("account", "SNOWFLAKE_ACCOUNT")
            .getOrElse(throw new DatrisException("Snowflake credentialsSecret '" + secretName + "' is missing required field 'account' (e.g. xy12345.us-east-1). Account lives in the credentials secret so it stays bound to the credential that authorizes it."))
        val user = field("user", "username", "SNOWFLAKE_USER")
            .getOrElse(throw new DatrisException("Snowflake credentialsSecret '" + secretName + "' is missing required field 'user'."))
        val privateKey = field("privateKey", "private_key", "private-key", "SNOWFLAKE_PRIVATE_KEY")
        val privateKeyPassphrase = field("privateKeyPassphrase", "private_key_passphrase", "privateKeyPassword", "SNOWFLAKE_PRIVATE_KEY_PASSPHRASE")
        val password = field("password", "SNOWFLAKE_PASSWORD")

        if (privateKey.isEmpty && password.isEmpty)
            throw new DatrisException("Snowflake credentialsSecret '" + secretName + "' must contain either 'privateKey' (key-pair auth, recommended) or 'password' (fallback). Neither was found.")

        ResolvedSnowflakeCredentials(
            account = account,
            user = user,
            privateKey = privateKey,
            privateKeyPassphrase = privateKeyPassphrase,
            password = password
        )
    }
}
