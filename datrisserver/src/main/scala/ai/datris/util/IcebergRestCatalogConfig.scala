package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import org.apache.iceberg.catalog.TableIdentifier

/** Pure builders for `unityCatalog.catalogMode: rest`: the Iceberg
  * `RESTCatalog` properties for one pipeline's secret, the Spark catalog that
  * wraps them, and the table identifiers. No HTTP, no Spark session.
  *
  * The endpoint and prefix reuse IcebergCatalogRegistrar's rules (so the
  * secret's `icebergRestPath` / `icebergRestPrefix` mean the same in both
  * modes). File IO is always HadoopFileIO: the runtime bundle has no AWS S3
  * SDK, and the s3a keys applied per bucket live in the Hadoop conf.
  * `warehouse` is the UC catalog for Databricks (its config endpoint requires
  * it) and absent for a custom REST path; every create still passes an
  * explicit location. */
object IcebergRestCatalogConfig {

    val SparkCatalogClass = "org.apache.iceberg.spark.SparkCatalog"
    val HadoopFileIO = "org.apache.iceberg.hadoop.HadoopFileIO"
    val SparkCatalogPrefix = "datris_uc_"
    val ConnectionTimeoutKey = "rest.client.connection-timeout-ms"
    val SocketTimeoutKey = "rest.client.socket-timeout-ms"

    /** RESTCatalog properties: `uri`, optional `prefix`, `io-impl`,
      * `cache-enabled=false`, HTTP connection/socket timeouts (10 s / 30 s),
      * plus auth by secret shape (M2M: OAuth2
      * client credentials; PAT: `token`; neither: none). */
    def catalogProperties(creds: ResolvedDatabricksCredentials, fields: Map[String, String], catalog: String): Map[String, String] = {
        val base = DatabricksRestClient.baseUrl(Option(creds.host).getOrElse(""))
        val core = Map(
            "uri" -> (base + IcebergCatalogRegistrar.restBase(fields)),
            "io-impl" -> HadoopFileIO,
            "cache-enabled" -> "false",
            // Bound every catalog call (config, token exchange, load, commit):
            // a hung endpoint must not pin a run or a query thread.
            ConnectionTimeoutKey -> "10000",
            SocketTimeoutKey -> "30000"
        ) ++ IcebergCatalogRegistrar.prefix(fields, catalog).map(p => "prefix" -> p) ++
            // Databricks' /v1/config rejects a request without `warehouse`
            // ("Must provide 'warehouse' parameter"); it names the UC catalog.
            // Server `overrides` are merged over client properties
            // (ConfigResponse.merge: defaults < client < overrides), so an
            // explicit prefix equal to the server's is harmless.
            (if (IcebergCatalogRegistrar.hasCustomRestPath(fields)) Map.empty[String, String] else Map("warehouse" -> catalog))
        val id = creds.clientId.filter(_.nonEmpty)
        val secret = creds.clientSecret.filter(_.nonEmpty)
        val auth: Map[String, String] =
            if (id.isDefined && secret.isDefined)
                Map(
                    "credential" -> (id.get + ":" + secret.get),
                    "oauth2-server-uri" -> (base + "/oidc/v1/token"),
                    "scope" -> "all-apis"
                )
            else creds.token.filter(_.nonEmpty).map(t => Map("token" -> t)).getOrElse(Map.empty)
        core ++ auth
    }

    /** Base of the Spark catalog name for a pipeline (no properties hash). */
    def sparkCatalogName(pipeline: String): String = SparkCatalogPrefix + IcebergCatalogRegistrar.tableName(pipeline)

    /** The Spark catalog a pipeline's writes go through: the base name plus
      * the first 8 hex of the SHA-256 of its sorted catalog properties
      * (uri, prefix, credential/token, ...). Spark caches the catalog plugin
      * per name for the session's life, so a changed secret or catalog, or
      * two pipelines whose table names collide with different secrets, get a
      * different name rather than a cached plugin pointing elsewhere. */
    def sparkCatalogName(pipeline: String, props: Map[String, String]): String =
        sparkCatalogName(pipeline) + "_" + propsHash(props)

    private[util] def propsHash(props: Map[String, String]): String = {
        val canonical = props.toSeq.sortBy(_._1).map { case (k, v) => k + "=" + v }.mkString("\n")
        java.security.MessageDigest
            .getInstance("SHA-256")
            .digest(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8))
            .take(4)
            .map(b => f"${b & 0xff}%02x")
            .mkString
    }

    /** `spark.sql.catalog.<name>` = SparkCatalog, `.type` = rest, `.<k>` per property. */
    def sparkConf(name: String, props: Map[String, String]): Map[String, String] = {
        val root = "spark.sql.catalog." + name
        Map(root -> SparkCatalogClass, root + ".type" -> "rest") ++ props.map { case (k, v) => (root + "." + k) -> v }
    }

    def identifier(schema: String, pipeline: String): TableIdentifier =
        TableIdentifier.of(schema, IcebergCatalogRegistrar.tableName(pipeline))

    def sqlIdentifier(sparkCatalogName: String, schema: String, pipeline: String): String =
        Seq(sparkCatalogName, schema, IcebergCatalogRegistrar.tableName(pipeline)).map(quote).mkString(".")

    private[util] def quote(name: String): String = "`" + name.replace("`", "``") + "`"
}
