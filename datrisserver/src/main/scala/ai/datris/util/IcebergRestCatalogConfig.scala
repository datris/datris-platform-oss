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
  * SDK, and the s3a keys applied per bucket live in the Hadoop conf. No
  * `warehouse` is set: every create passes an explicit location. */
object IcebergRestCatalogConfig {

    val SparkCatalogClass = "org.apache.iceberg.spark.SparkCatalog"
    val HadoopFileIO = "org.apache.iceberg.hadoop.HadoopFileIO"
    val SparkCatalogPrefix = "datris_uc_"

    /** RESTCatalog properties: `uri`, optional `prefix`, `io-impl`,
      * `cache-enabled=false`, plus auth by secret shape (M2M: OAuth2
      * client credentials; PAT: `token`; neither: none). */
    def catalogProperties(creds: ResolvedDatabricksCredentials, fields: Map[String, String], catalog: String): Map[String, String] = {
        val base = DatabricksRestClient.baseUrl(Option(creds.host).getOrElse(""))
        val core = Map(
            "uri" -> (base + IcebergCatalogRegistrar.restBase(fields)),
            "io-impl" -> HadoopFileIO,
            "cache-enabled" -> "false"
        ) ++ IcebergCatalogRegistrar.prefix(fields, catalog).map(p => "prefix" -> p)
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

    /** One Spark catalog per pipeline: Spark caches the catalog plugin per
      * name, and two pipelines may use two secrets. */
    def sparkCatalogName(pipeline: String): String = SparkCatalogPrefix + IcebergCatalogRegistrar.tableName(pipeline)

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
