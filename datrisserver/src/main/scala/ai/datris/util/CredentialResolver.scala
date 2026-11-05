package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{DatrisEnvironment, DatrisException, ObjectStore}

import scala.collection.JavaConverters._
import scala.util.Try

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

/** Databricks destination credentials, resolved from a Platform-tab secret named
 *  by `Database.credentialsSecret`. `host` lives in the secret (bound to the
 *  credential, same call Snowflake makes for `account`). Auth is OAuth M2M by
 *  default (clientId + clientSecret of a service principal); a personal access
 *  token is the fallback. */
case class ResolvedDatabricksCredentials(
    host: String,
    clientId: Option[String],
    clientSecret: Option[String],
    token: Option[String],
    // Every raw field of the secret (e.g. icebergRestPath / icebergRestPrefix
    // for the Iceberg register); empty when built by hand.
    extra: Map[String, String] = Map.empty
)

/** Microsoft SQL Server / Azure SQL destination credentials, resolved from a
 *  Platform-tab secret named by `Database.credentialsSecret`. `host` lives in
 *  the secret (bound to the credential, same call Snowflake makes for
 *  `account`); the database name is per pipeline (`Database.dbName`) and goes
 *  in the JDBC URL. `authMethod` (default `sql`) selects which of the optional
 *  fields are set:
 *    sql                    user, password
 *    entraServicePrincipal  clientId, clientSecret, tenantId
 *    entraPassword          user, password
 *    entraManagedIdentity   managedIdentityClientId (only for a user-assigned identity)
 *    kerberos               principal, plus password OR jaasConfigurationName (a keytab entry in a
 *                           mounted JAAS file; then no password is needed or bound) */
case class ResolvedSqlServerCredentials(
    host: String,
    port: Int,
    authMethod: String,
    user: Option[String] = None,
    password: Option[String] = None,
    clientId: Option[String] = None,
    clientSecret: Option[String] = None,
    tenantId: Option[String] = None,
    managedIdentityClientId: Option[String] = None,
    principal: Option[String] = None,
    jaasConfigurationName: Option[String] = None,
    encrypt: Boolean = true,
    trustServerCertificate: Boolean = false
)

object CredentialResolver {

    /** Case/underscore/hyphen-insensitive secret-field lookup shared by every
     *  resolver: the UI's Secrets form leaves naming to the operator, so a
     *  credential pasted in as AWS_ACCESS_KEY shouldn't fail just because the
     *  resolver expects accessKey. */
    private[datris] def secretField(secret: java.util.Map[String, String], canonical: String, aliases: String*): Option[String] = {
        val candidates = (canonical +: aliases).flatMap(n => Seq(n, n.toLowerCase, n.toUpperCase))
        candidates.iterator.map(secret.get).find(_ != null)
    }

    /** True when `secret` holds a complete set of Snowflake credentials —
     *  account AND user AND (privateKey OR password) — under any of the alias
     *  spellings resolveSnowflake accepts. Presence check only, no connection
     *  attempt. Used by GET /destinations/available, which cannot know the
     *  user-chosen credentialsSecret name and so scans every Platform secret
     *  for one that would satisfy resolveSnowflake. */
    def hasSnowflakeCredentials(secret: java.util.Map[String, String]): Boolean = {
        secretField(secret, "account", "SNOWFLAKE_ACCOUNT").isDefined &&
        secretField(secret, "user", "username", "SNOWFLAKE_USER").isDefined &&
        (secretField(secret, "privateKey", "private_key", "private-key", "SNOWFLAKE_PRIVATE_KEY").isDefined ||
            secretField(secret, "password", "SNOWFLAKE_PASSWORD").isDefined)
    }

    /** True when `secret` holds a complete set of Databricks credentials —
     *  host AND ((clientId AND clientSecret) OR token) — under any of the
     *  alias spellings resolveDatabricks accepts. Presence check only; same
     *  Platform-secret scan rationale as hasSnowflakeCredentials. */
    def hasDatabricksCredentials(secret: java.util.Map[String, String]): Boolean = {
        secretField(secret, "host", "server", "hostname", "workspaceUrl", "workspace_url", "DATABRICKS_HOST").isDefined &&
        ((secretField(secret, "clientId", "client_id", "client-id", "DATABRICKS_CLIENT_ID").isDefined &&
            secretField(secret, "clientSecret", "client_secret", "client-secret", "DATABRICKS_CLIENT_SECRET").isDefined) ||
            secretField(secret, "token", "pat", "personalAccessToken", "personal_access_token", "access_token", "DATABRICKS_TOKEN").isDefined)
    }

    /** SQL Server `authMethod` values and the secret fields each one requires
     *  (beyond `host`, which every method needs). Canonical spellings; the
     *  lookup itself is case/underscore-insensitive via [[secretField]].
     *  Kerberos drops `password` when the secret names a
     *  `jaasConfigurationName` (keytab sign-in); see [[sqlServerRequiredFields]]. */
    val SqlServerAuthMethods: Seq[(String, Seq[String])] = Seq(
        "sql" -> Seq("user", "password"),
        "entraServicePrincipal" -> Seq("clientId", "clientSecret", "tenantId"),
        "entraPassword" -> Seq("user", "password"),
        "entraManagedIdentity" -> Seq.empty,
        "kerberos" -> Seq("principal", "password")
    )

    private val SqlServerFieldAliases: Map[String, Seq[String]] = Map(
        "host" -> Seq("server", "hostname", "SQLSERVER_HOST"),
        "port" -> Seq("SQLSERVER_PORT"),
        "authMethod" -> Seq("auth_method", "SQLSERVER_AUTH_METHOD"),
        "user" -> Seq("username", "SQLSERVER_USER"),
        "password" -> Seq("SQLSERVER_PASSWORD"),
        "clientId" -> Seq("client_id", "client-id", "SQLSERVER_CLIENT_ID"),
        "clientSecret" -> Seq("client_secret", "client-secret", "SQLSERVER_CLIENT_SECRET"),
        "tenantId" -> Seq("tenant_id", "tenant-id", "SQLSERVER_TENANT_ID"),
        "managedIdentityClientId" -> Seq("managed_identity_client_id", "msiClientId", "SQLSERVER_MANAGED_IDENTITY_CLIENT_ID"),
        "principal" -> Seq("SQLSERVER_PRINCIPAL"),
        "jaasConfigurationName" -> Seq("jaas_configuration_name"),
        "encrypt" -> Seq("SQLSERVER_ENCRYPT"),
        "trustServerCertificate" -> Seq("trust_server_certificate", "SQLSERVER_TRUST_SERVER_CERTIFICATE")
    )

    private def sqlServerField(secret: java.util.Map[String, String], canonical: String): Option[String] =
        secretField(secret, canonical, SqlServerFieldAliases.getOrElse(canonical, Seq.empty): _*).map(_.trim).filter(_.nonEmpty)

    /** True when a kerberos secret signs in through a keytab entry in a
     *  mounted JAAS file (`jaasConfigurationName` set) instead of a password. */
    private def sqlServerUsesKeytab(method: String, secret: java.util.Map[String, String]): Boolean =
        method == "kerberos" && sqlServerField(secret, "jaasConfigurationName").isDefined

    /** Fields `method` requires in `secret` beyond `host`: the
     *  [[SqlServerAuthMethods]] list, minus `password` for a keytab kerberos
     *  secret. */
    private def sqlServerRequiredFields(method: String, secret: java.util.Map[String, String]): Seq[String] = {
        val base = SqlServerAuthMethods.find(_._1 == method).get._2
        if (sqlServerUsesKeytab(method, secret)) base.filterNot(_ == "password") else base
    }

    /** The canonical `authMethod` for a secret (absent ⇒ `sql`), or the raw
     *  value when it names no supported method. Case-insensitive. */
    private def sqlServerAuthMethod(secret: java.util.Map[String, String]): Either[String, String] = {
        val raw = sqlServerField(secret, "authMethod").getOrElse("sql")
        SqlServerAuthMethods.map(_._1).find(_.equalsIgnoreCase(raw)).toRight(raw)
    }

    /** True when `secret` holds a complete set of SQL Server credentials for
     *  its `authMethod` (absent ⇒ `sql`): `host` plus every field that method
     *  requires. Unknown methods are never complete. Presence check only; same
     *  Platform-secret scan rationale as hasSnowflakeCredentials. */
    def hasSqlServerCredentials(secret: java.util.Map[String, String]): Boolean =
        sqlServerAuthMethod(secret) match {
            case Right(method) =>
                sqlServerField(secret, "host").isDefined &&
                sqlServerRequiredFields(method, secret).forall(f => sqlServerField(secret, f).isDefined)
            case Left(_) => false
        }

    def resolve(objectStore: ObjectStore): ResolvedObjectStoreCredentials = {
        val provider = Option(objectStore.provider).getOrElse("minio").toLowerCase
        provider match {
            case "minio" => resolveMinIO()
            case "s3" => resolveS3(objectStore.credentialsSecret)
            case other => throw new DatrisException("Unknown objectStore.provider: '" + other + "'. Expected 'minio' or 's3'.")
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
        SecretNames.requireNotServerManaged(secretName)
        val secretPath = DatrisEnvironment.current.environment + "/" + secretName
        val secret = SecretsUtil.getSecretMap(secretPath)
            .getOrElse(throw new DatrisException(
                "S3 credentialsSecret not found in Secrets Manager at path '" + secretPath + "' (looked up by name '" + secretName + "'). Create it on Configuration → Secrets → Platform."
            ))
        def field(canonical: String, aliases: String*): Option[String] = secretField(secret, canonical, aliases: _*)
        val accessKey = field("accessKey", "access_key", "access-key", "AWS_ACCESS_KEY", "AWS_ACCESS_KEY_ID")
            .getOrElse(throw new DatrisException(
                "S3 credentialsSecret '" + secretName + "' is missing required field 'accessKey' (also accepted: AWS_ACCESS_KEY, AWS_ACCESS_KEY_ID, access_key)"
            ))
        val secretKey = field("secretKey", "secret_key", "secret-key", "AWS_SECRET_KEY", "AWS_SECRET_ACCESS_KEY")
            .getOrElse(throw new DatrisException(
                "S3 credentialsSecret '" + secretName + "' is missing required field 'secretKey' (also accepted: AWS_SECRET_KEY, AWS_SECRET_ACCESS_KEY, secret_key)"
            ))
        val region = field("region", "AWS_REGION", "aws_region")
            .getOrElse(throw new DatrisException(
                "S3 credentialsSecret '" + secretName + "' is missing required field 'region' (also accepted: AWS_REGION). Region lives in the credentials secret alongside accessKey/secretKey so it stays bound to the credential that authorizes it."
            ))
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
            throw new DatrisException(
                "Snowflake destination requires a credentialsSecret naming a Platform-tab secret (with fields account, user, and privateKey or password). Create it on Configuration → Secrets → Platform, then set it on the destination."
            )

        SecretNames.requireNotServerManaged(secretName)
        val secretPath = DatrisEnvironment.current.environment + "/" + secretName
        val secret = SecretsUtil.getSecretMap(secretPath)
            .getOrElse(throw new DatrisException(
                "Snowflake credentialsSecret not found in Secrets Manager at path '" + secretPath + "' (looked up by name '" + secretName + "'). Create it on Configuration → Secrets → Platform."
            ))

        def field(canonical: String, aliases: String*): Option[String] = secretField(secret, canonical, aliases: _*)

        val account = field("account", "SNOWFLAKE_ACCOUNT")
            .getOrElse(throw new DatrisException(
                "Snowflake credentialsSecret '" + secretName + "' is missing required field 'account' (e.g. xy12345.us-east-1). Account lives in the credentials secret so it stays bound to the credential that authorizes it."
            ))
        val user = field("user", "username", "SNOWFLAKE_USER")
            .getOrElse(throw new DatrisException("Snowflake credentialsSecret '" + secretName + "' is missing required field 'user'."))
        val privateKey = field("privateKey", "private_key", "private-key", "SNOWFLAKE_PRIVATE_KEY")
        val privateKeyPassphrase = field("privateKeyPassphrase", "private_key_passphrase", "privateKeyPassword", "SNOWFLAKE_PRIVATE_KEY_PASSPHRASE")
        val password = field("password", "SNOWFLAKE_PASSWORD")

        if (privateKey.isEmpty && password.isEmpty)
            throw new DatrisException(
                "Snowflake credentialsSecret '" + secretName + "' must contain either 'privateKey' (key-pair auth, recommended) or 'password' (fallback). Neither was found."
            )

        ResolvedSnowflakeCredentials(
            account = account,
            user = user,
            privateKey = privateKey,
            privateKeyPassphrase = privateKeyPassphrase,
            password = password
        )
    }

    /** Resolve Databricks destination credentials from the Platform-tab secret
     *  named by `Database.credentialsSecret`. Mirrors resolveSnowflake: same
     *  env-prefixed Vault path, same case/underscore-insensitive field lookup,
     *  same actionable errors pointing at Configuration → Secrets → Platform.
     *  `requireCredentials = false` (the Iceberg register only) accepts a
     *  host-only secret, for an Iceberg REST catalog without auth. */
    def resolveDatabricks(secretName: String, requireCredentials: Boolean = true): ResolvedDatabricksCredentials = {
        if (secretName == null || secretName.trim.isEmpty)
            throw new DatrisException(
                "Databricks destination requires a credentialsSecret naming a Platform-tab secret (with fields host, and clientId/clientSecret or token). Create it on Configuration → Secrets → Platform, then set it on the destination."
            )

        SecretNames.requireNotServerManaged(secretName)
        val secretPath = DatrisEnvironment.current.environment + "/" + secretName
        val secret = SecretsUtil.getSecretMap(secretPath)
            .getOrElse(throw new DatrisException(
                "Databricks credentialsSecret not found in Secrets Manager at path '" + secretPath + "' (looked up by name '" + secretName + "'). Create it on Configuration → Secrets → Platform."
            ))

        def field(canonical: String, aliases: String*): Option[String] = secretField(secret, canonical, aliases: _*)

        val host = field("host", "server", "hostname", "workspaceUrl", "workspace_url", "DATABRICKS_HOST")
            .getOrElse(throw new DatrisException(
                "Databricks credentialsSecret '" + secretName + "' is missing required field 'host' — the workspace hostname (e.g. dbc-a1b2c3d4-e5f6.cloud.databricks.com). Host lives in the credentials secret so it stays bound to the credential that authorizes it."
            ))
        val clientId = field("clientId", "client_id", "client-id", "DATABRICKS_CLIENT_ID")
        val clientSecret = field("clientSecret", "client_secret", "client-secret", "DATABRICKS_CLIENT_SECRET")
        val token = field("token", "pat", "personalAccessToken", "personal_access_token", "access_token", "DATABRICKS_TOKEN")

        if (requireCredentials && (clientId.isEmpty || clientSecret.isEmpty) && token.isEmpty)
            throw new DatrisException(
                "Databricks credentialsSecret '" + secretName + "' must contain either 'clientId' and 'clientSecret' (service principal OAuth, recommended) or 'token' (personal access token, fallback). Neither pair was found."
            )

        ResolvedDatabricksCredentials(
            host = host,
            clientId = clientId,
            clientSecret = clientSecret,
            token = token,
            extra = secret.asScala.toMap
        )
    }

    /** Resolve SQL Server destination credentials from the Platform-tab secret
     *  named by `Database.credentialsSecret`. Mirrors resolveSnowflake: same
     *  env-prefixed Vault path and actionable errors pointing at
     *  Configuration → Secrets → Platform; the field validation is
     *  [[resolveSqlServerFromSecret]]. */
    def resolveSqlServer(secretName: String): ResolvedSqlServerCredentials = {
        if (secretName == null || secretName.trim.isEmpty)
            throw new DatrisException(
                "SQL Server destination requires a credentialsSecret naming a Platform-tab secret (with field host, plus the fields its authMethod needs — sql: user, password). Create it on Configuration → Secrets → Platform, then set it on the destination."
            )

        SecretNames.requireNotServerManaged(secretName)
        val secretPath = DatrisEnvironment.current.environment + "/" + secretName
        val secret = SecretsUtil.getSecretMap(secretPath)
            .getOrElse(throw new DatrisException(
                "SQL Server credentialsSecret not found in Secrets Manager at path '" + secretPath + "' (looked up by name '" + secretName + "'). Create it on Configuration → Secrets → Platform."
            ))
        resolveSqlServerFromSecret(secretName, secret)
    }

    /** Validate and resolve an already-read SQL Server secret. Names the
     *  missing field and the auth method in every error. */
    def resolveSqlServerFromSecret(secretName: String, secret: java.util.Map[String, String]): ResolvedSqlServerCredentials = {
        val methods = SqlServerAuthMethods.map(_._1).mkString(", ")
        val method = sqlServerAuthMethod(secret) match {
            case Right(m) => m
            case Left(raw) =>
                throw new DatrisException(
                    "SQL Server credentialsSecret '" + secretName + "' has unsupported authMethod '" + raw + "'. Use one of: " + methods +
                        " (absent means sql). Update it on Configuration → Secrets → Platform."
                )
        }
        val required = "host" +: sqlServerRequiredFields(method, secret)
        def field(name: String): Option[String] = sqlServerField(secret, name)
        def need(name: String): String =
            field(name).getOrElse(throw new DatrisException(
                "SQL Server credentialsSecret '" + secretName + "' is missing required field '" + name + "' for authMethod '" + method +
                    "' (" + method + " needs: " + required.mkString(", ") + "). Add it on Configuration → Secrets → Platform."
            ))
        required.foreach(need)

        val port = field("port") match {
            case None => 1433
            case Some(p) =>
                Try(p.toInt).toOption.filter(n => n > 0 && n < 65536).getOrElse(throw new DatrisException(
                    "SQL Server credentialsSecret '" + secretName + "' has an invalid 'port' value '" + p + "' — expected a TCP port number such as 1433."
                ))
        }
        def bool(name: String, default: Boolean): Boolean = field(name) match {
            case None => default
            case Some(v) if v.equalsIgnoreCase("true") || v.equalsIgnoreCase("yes") || v == "1" => true
            case Some(v) if v.equalsIgnoreCase("false") || v.equalsIgnoreCase("no") || v == "0" => false
            case Some(v) =>
                throw new DatrisException(
                    "SQL Server credentialsSecret '" + secretName + "' has an invalid '" + name + "' value '" + v + "' — expected true or false."
                )
        }
        val usesUserPassword = method == "sql" || method == "entraPassword"

        ResolvedSqlServerCredentials(
            host = need("host"),
            port = port,
            authMethod = method,
            user = if (usesUserPassword) field("user") else None,
            password = if (usesUserPassword || (method == "kerberos" && !sqlServerUsesKeytab(method, secret))) field("password") else None,
            clientId = if (method == "entraServicePrincipal") field("clientId") else None,
            clientSecret = if (method == "entraServicePrincipal") field("clientSecret") else None,
            tenantId = if (method == "entraServicePrincipal") field("tenantId") else None,
            managedIdentityClientId = if (method == "entraManagedIdentity") field("managedIdentityClientId") else None,
            principal = if (method == "kerberos") field("principal") else None,
            jaasConfigurationName = if (method == "kerberos") field("jaasConfigurationName") else None,
            encrypt = bool("encrypt", default = true),
            trustServerCertificate = bool("trustServerCertificate", default = false)
        )
    }
}
