package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import org.apache.iceberg.catalog.{Namespace, TableIdentifier}
import org.scalatest.funsuite.AnyFunSuite

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}

/** Story: Unity Catalog 5: Iceberg via RESTCatalog (`catalogMode: rest`)
  * (plans/stories/unity-catalog-5-iceberg-restcatalog.md), Acceptance bullet 1
  * (Step 3) plus the Files-section redaction regex.
  *
  * Seams this spec pins (pure; no HTTP, no Spark session):
  *
  * {{{
  *   object IcebergRestCatalogConfig {
  *     // RESTCatalog properties for one pipeline's secret.
  *     def catalogProperties(creds: ResolvedDatabricksCredentials, fields: Map[String, String], catalog: String): Map[String, String]
  *     def sparkCatalogName(pipeline: String): String              // "datris_uc_" + IcebergCatalogRegistrar.tableName(pipeline)
  *     def sparkConf(name: String, props: Map[String, String]): Map[String, String]
  *                                                                 // spark.sql.catalog.<name> -> SparkCatalog, .type -> rest, .<k> -> v
  *     def identifier(schema: String, pipeline: String): TableIdentifier     // TableIdentifier.of(schema, tableName(pipeline))
  *     def sqlIdentifier(sparkCatalogName: String, schema: String, pipeline: String): String
  *                                                                 // `<name>`.`<schema>`.`<table>`
  *   }
  * }}}
  *
  * The story's `identifier(catalog-name, schema, pipeline)` returns both the
  * TableIdentifier and the backticked SQL form; this spec fixes them as two
  * functions, `identifier` and `sqlIdentifier`.
  *
  * Property keys are Iceberg's (CatalogProperties / OAuth2Properties):
  * `uri`, `prefix`, `io-impl`, `cache-enabled`, `warehouse`, `credential`,
  * `oauth2-server-uri`, `scope`, `token`.
  */
class IcebergRestCatalogConfigSpec extends AnyFunSuite {

    private val C = IcebergRestCatalogConfig

    private val DBX_HOST = "dbc-a1b2c3d4-e5f6.cloud.databricks.com"
    private val FIXTURE = "http://iceberg-rest:8181"
    private val CAT = "unity_cat"
    private val HadoopFileIO = "org.apache.iceberg.hadoop.HadoopFileIO"
    private val AuthKeys = Set("credential", "oauth2-server-uri", "scope", "token")

    private val m2mFields = Map("host" -> DBX_HOST, "clientId" -> "sp-app-id", "clientSecret" -> "sp-secret")
    private val m2m = ResolvedDatabricksCredentials(
        host = DBX_HOST,
        clientId = Some("sp-app-id"),
        clientSecret = Some("sp-secret"),
        token = None,
        extra = m2mFields
    )

    private val patFields = Map("host" -> DBX_HOST, "token" -> "dapi-x")
    private val pat = ResolvedDatabricksCredentials(host = DBX_HOST, clientId = None, clientSecret = None, token = Some("dapi-x"), extra = patFields)

    private val fixtureFields = Map("host" -> FIXTURE, "icebergRestPath" -> "/", "icebergRestPrefix" -> "-")
    private val fixture = ResolvedDatabricksCredentials(host = FIXTURE, clientId = None, clientSecret = None, token = None, extra = fixtureFields)

    private def m2mProps = C.catalogProperties(m2m, m2mFields, CAT)
    private def patProps = C.catalogProperties(pat, patFields, CAT)
    private def fixtureProps = C.catalogProperties(fixture, fixtureFields, "unity")

    // --- the three secret shapes (Step 3) ----------------------------------------

    test("Databricks M2M secret → uri at the Iceberg REST root, prefix catalogs/<cat>, OAuth2 credential/server-uri/scope, no token") {
        val p = m2mProps
        assert(p.get("uri").contains(s"https://$DBX_HOST/api/2.1/unity-catalog/iceberg-rest"), p)
        assert(p.get("prefix").contains(s"catalogs/$CAT"), p)
        assert(p.get("credential").contains("sp-app-id:sp-secret"), p)
        assert(p.get("oauth2-server-uri").contains(s"https://$DBX_HOST/oidc/v1/token"), p)
        assert(p.get("scope").contains("all-apis"), p)
        assert(!p.contains("token"), s"M2M must not send a token: $p")
        assert(p.get("cache-enabled").contains("false"), p)
    }

    test("Databricks PAT secret → token, no credential / oauth2-server-uri / scope") {
        val p = patProps
        assert(p.get("uri").contains(s"https://$DBX_HOST/api/2.1/unity-catalog/iceberg-rest"), p)
        assert(p.get("prefix").contains(s"catalogs/$CAT"), p)
        assert(p.get("token").contains("dapi-x"), p)
        assert(!p.contains("credential") && !p.contains("oauth2-server-uri") && !p.contains("scope"), p)
    }

    test("fixture secret (icebergRestPath=/, icebergRestPrefix=-) → uri is the host root, no prefix, no auth keys") {
        val p = fixtureProps
        assert(p.get("uri").contains(FIXTURE), p)
        assert(!p.contains("prefix"), s"'-' means no prefix; the key must be absent: $p")
        assert(p.keySet.intersect(AuthKeys).isEmpty, s"fixture has no auth: $p")
    }

    test("uri reuses DatabricksRestClient.baseUrl + IcebergCatalogRegistrar.restBase (scheme-less host, trailing slash, default port)") {
        val f1 = Map("host" -> ("https://" + DBX_HOST + "/"), "token" -> "dapi-x")
        val p1 = C.catalogProperties(pat.copy(host = "https://" + DBX_HOST + "/", extra = f1), f1, CAT)
        assert(p1.get("uri").contains(s"https://$DBX_HOST/api/2.1/unity-catalog/iceberg-rest"), p1)
        val f2 = Map("host" -> "http://iceberg-rest:8181/", "icebergRestPath" -> "/iceberg", "icebergRestPrefix" -> "wh1")
        val p2 = C.catalogProperties(fixture.copy(host = "http://iceberg-rest:8181/", extra = f2), f2, "unity")
        assert(p2.get("uri").contains("http://iceberg-rest:8181/iceberg"), p2)
        assert(p2.get("prefix").contains("wh1"), p2)
    }

    test("warehouse is never set") {
        Seq(m2mProps, patProps, fixtureProps).foreach(p => assert(!p.contains("warehouse"), p))
    }

    // Round-3 review: every catalog call is bounded (connection 10 s, socket 30 s).
    test("REST client connection and socket timeouts are set for every shape") {
        Seq(m2mProps, patProps, fixtureProps).foreach { p =>
            assert(p.get("rest.client.connection-timeout-ms").contains("10000"), p)
            assert(p.get("rest.client.socket-timeout-ms").contains("30000"), p)
        }
    }

    test("io-impl is HadoopFileIO for every shape") {
        Seq(m2mProps, patProps, fixtureProps).foreach(p => assert(p.get("io-impl").contains(HadoopFileIO), p))
    }

    // --- Spark catalog mapping ---------------------------------------------------

    test("sparkCatalogName is datris_uc_<lowercased ucName of the pipeline>") {
        assert(C.sparkCatalogName("orders_daily") == "datris_uc_orders_daily")
        assert(C.sparkCatalogName("Orders.Daily v2") == "datris_uc_" + IcebergCatalogRegistrar.tableName("Orders.Daily v2"))
        assert(C.sparkCatalogName("Orders.Daily v2") == "datris_uc_orders_daily_v2")
    }

    // Review follow-up (finding 3): the Spark catalog the writes use is the
    // base name plus a hash of the catalog properties, so a changed secret or
    // catalog, or two pipelines whose table names collide, never share a
    // cached Spark catalog plugin.
    test("sparkCatalogName(pipeline, props) is datris_uc_<table>_<8 hex of the properties hash>") {
        val name = C.sparkCatalogName("Orders.Daily v2", m2mProps)
        assert(name.matches("datris_uc_orders_daily_v2_[0-9a-f]{8}"), name)
        assert(C.sparkCatalogName("Orders.Daily v2", m2mProps) == name, "deterministic")
        assert(C.sparkCatalogName("Orders.Daily v2", patProps) != name, "different credentials → different catalog")
        val otherCatalog = C.catalogProperties(m2m, m2mFields, "other_cat")
        assert(C.sparkCatalogName("Orders.Daily v2", otherCatalog) != name, "different UC catalog (prefix) → different catalog")
        val rotated = m2m.copy(clientSecret = Some("sp-secret-2"))
        assert(C.sparkCatalogName("Orders.Daily v2", C.catalogProperties(rotated, m2mFields, CAT)) != name, "rotated secret → different catalog")
        // Colliding table names with different secrets do not share a catalog.
        assert(C.sparkCatalogName("Orders Daily", m2mProps) != C.sparkCatalogName("orders_daily", patProps))
    }

    test("sparkConf keys all start with spark.sql.catalog.datris_uc_<table>. and include type=rest") {
        val name = C.sparkCatalogName("Orders.Daily v2", m2mProps)
        val props = m2mProps
        val conf = C.sparkConf(name, props)
        val root = "spark.sql.catalog." + name
        assert(conf.get(root).contains("org.apache.iceberg.spark.SparkCatalog"), conf)
        assert(conf.get(root + ".type").contains("rest"), conf)
        (conf.keySet - root).foreach(k => assert(k.startsWith(root + "."), s"$k must be under $root."))
        props.foreach { case (k, v) => assert(conf.get(root + "." + k).contains(v), s"property $k missing from sparkConf: $conf") }
        // Nothing but the root, .type, and the properties.
        assert(conf.keySet == Set(root, root + ".type") ++ props.keySet.map(k => root + "." + k), conf)
    }

    test("identifier is <schema>.<lowercased ucName>; sqlIdentifier backticks the Spark catalog, schema and table") {
        val id = C.identifier("sales", "Orders.Daily v2")
        assert(id == TableIdentifier.of(Namespace.of("sales"), "orders_daily_v2"), id)
        assert(id.toString == "sales.orders_daily_v2", id.toString)
        val name = C.sparkCatalogName("Orders.Daily v2")
        assert(C.sqlIdentifier(name, "sales", "Orders.Daily v2") == "`datris_uc_orders_daily_v2`.`sales`.`orders_daily_v2`")
    }

    // --- redaction (Files: SparkSessionManager builder) -----------------------

    /** The literal `spark.redaction.regex` value SparkSessionManager configures. */
    private def redactionRegex: String = {
        val rel = "src/main/scala/ai/datris/util/SparkSessionManager.scala"
        val file = Seq(Paths.get(rel), Paths.get("datrisserver", rel)).find(Files.isRegularFile(_))
            .getOrElse(fail(s"cannot find $rel from ${Paths.get("").toAbsolutePath}"))
        val src = new String(Files.readAllBytes(file), StandardCharsets.UTF_8)
        val m = "\"spark\\.redaction\\.regex\"\\s*,\\s*\"([^\"]+)\"".r.findFirstMatchIn(src)
        assert(m.isDefined, "SparkSessionManager must set spark.redaction.regex (Spark's default omits 'credential')")
        m.get.group(1).replace("\\\\", "\\")
    }

    test("spark.redaction.regex covers every auth key the REST catalog conf carries (credential, token) and Spark's defaults") {
        val re = redactionRegex.r
        val name = C.sparkCatalogName("orders_daily")
        val keys = (C.sparkConf(name, m2mProps) ++ C.sparkConf(name, patProps)).keySet
            .filter(k => k.endsWith(".credential") || k.endsWith(".token"))
        assert(keys.exists(_.endsWith(".credential")) && keys.exists(_.endsWith(".token")), keys)
        keys.foreach(k => assert(re.findFirstIn(k).isDefined, s"$k must be redacted by '$re'"))
        Seq("spark.hadoop.fs.s3a.secret.key", "spark.hadoop.fs.s3a.access.key", "db.password")
            .foreach(k => assert(re.findFirstIn(k).isDefined, s"$k must still be redacted by '$re'"))
        assert(re.findFirstIn("spark.sql.catalog." + name + ".uri").isEmpty, "the uri is not a secret")
    }
}
