package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.audit.AuditLog
import ai.datris.model._
import com.google.gson.JsonObject
import org.slf4j.{Logger, LoggerFactory}

import java.net.URLEncoder
import java.time.Instant
import scala.util.Try
import scala.util.control.NonFatal

/** Registers an object-store Iceberg table in Unity Catalog (opt-in via
  * `unityCatalog: {"enabled": true, "credentialsSecret": ..., "catalog": ...}`
  * on an objectStore destination with `fileFormat: iceberg`). After each
  * successful commit the loader calls [[sync]], which speaks the Iceberg REST
  * `register` verb: `POST .../v1/[prefix/]namespaces/<schema>/register` with
  * `{name, metadata-location}` (location sent as `s3://`), naming the table
  * `<catalog>.<schema>.<pipeline>`.
  *
  * Spike semantics: the first run registers; a later run that finds the table
  * already registered leaves Unity Catalog alone. If UC points at an older
  * metadata file of this table, the run gets a warning that the pointer is
  * stale (keeping it current is a later release); if UC points
  * anywhere else, the name belongs to another table and is refused. Nothing
  * is ever dropped or merged, and a failure is one warning plus an audit
  * entry, never a failed load. */
object IcebergCatalogRegistrar {
    private val logger: Logger = LoggerFactory.getLogger(getClass)

    val ErrorPrefix = "uc-register:"
    val DefaultRestPath = "/api/2.1/unity-catalog/iceberg-rest"
    val RestPathField = "icebergRestPath"
    val RestPrefixField = "icebergRestPrefix"

    /** Already-exists answered, but the table then 404s on GET. */
    private class NotReadable(message: String) extends DatrisException(message)

    /** Where Unity Catalog's current pointer sits relative to our table. */
    sealed trait Classification
    case object Current extends Classification
    case object Stale extends Classification
    case object Foreign extends Classification

    // --- pure parts ----------------------------------------------------------

    /** UC table name: the securable-safe pipeline name, lowercased (UC lowercases). */
    def tableName(pipeline: String): String = UnityCatalogLineagePublisher.ucName(pipeline).toLowerCase

    private def enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private def field(fields: Map[String, String], name: String): Option[String] =
        Option(fields).flatMap(f => f.get(name).orElse(f.collectFirst { case (k, v) if k != null && k.equalsIgnoreCase(name) => v }))

    /** True when the secret overrides the REST path (a non-Databricks catalog). */
    def hasCustomRestPath(fields: Map[String, String]): Boolean = field(fields, RestPathField).isDefined

    /** REST root: the secret's `icebergRestPath` when present (`/` or empty ⇒
      * the host root), else Databricks' Iceberg REST catalog. */
    def restBase(fields: Map[String, String]): String =
        field(fields, RestPathField) match {
            case Some(p) =>
                val t = Option(p).getOrElse("").trim.stripSuffix("/")
                if (t.isEmpty) "" else if (t.startsWith("/")) t else "/" + t
            case None => DefaultRestPath
        }

    /** Catalog prefix: the secret's `icebergRestPrefix` when present (empty, or
      * the literal `-` for secret editors that reject blanks, ⇒ none), else
      * Databricks' `catalogs/<catalog>`. */
    def prefix(fields: Map[String, String], catalog: String): Option[String] =
        field(fields, RestPrefixField) match {
            case Some(p) =>
                val t = Option(p).getOrElse("").trim.stripPrefix("/").stripSuffix("/")
                if (t.isEmpty || t == "-") None else Some(t)
            case None => Some("catalogs/" + enc(catalog))
        }

    private def namespacePath(fields: Map[String, String], catalog: String, schema: String): String =
        restBase(fields) + "/v1/" + prefix(fields, catalog).map(_ + "/").getOrElse("") + "namespaces/" + enc(schema)

    def registerPath(fields: Map[String, String], catalog: String, schema: String): String =
        namespacePath(fields, catalog, schema) + "/register"

    def tablePath(fields: Map[String, String], catalog: String, schema: String, table: String): String =
        namespacePath(fields, catalog, schema) + "/tables/" + enc(table)

    /** Iceberg REST `RegisterTableRequest`. */
    def registerBody(name: String, metadataLocation: String): JsonObject = {
        val o = new JsonObject()
        o.addProperty("name", name)
        o.addProperty("metadata-location", metadataLocation)
        o
    }

    /** `s3a://` / `s3n://` → `s3://` (the spelling Unity Catalog and its
      * external locations use). Warnings name locations in this form. */
    def normalize(loc: String): String =
        Option(loc).map(_.trim.replaceFirst("(?i)^s3[an]://", "s3://")).orNull

    /** `Current` when UC points at our file, `Stale` when it points at another
      * metadata file under our table root, `Foreign` otherwise (including a
      * sibling table sharing our root as a string prefix). `s3a://` and
      * `s3n://` compare equal to `s3://`. */
    def classify(ucMetadataLocation: String, ours: String, tableRoot: String): Classification = {
        val uc = normalize(ucMetadataLocation)
        if (uc == null || uc.isEmpty) Foreign
        else if (uc == normalize(ours)) Current
        else if (uc.startsWith(normalize(tableRoot).stripSuffix("/") + "/metadata/")) Stale
        else Foreign
    }

    /** Table root of a metadata file: everything before its `/metadata/`. */
    def tableRootOf(metadataLocation: String): String = {
        val i = Option(metadataLocation).map(_.lastIndexOf("/metadata/")).getOrElse(-1)
        if (i > 0) metadataLocation.substring(0, i) else null
    }

    /** Already-exists answers: the Iceberg REST fixture's 409
      * `AlreadyExistsException`, Databricks' `TABLE_ALREADY_EXISTS`. A 400 or
      * 409 without one of those markers (e.g. a commit conflict) is a failure,
      * not "the table exists". */
    private[util] def alreadyExists(status: Int, body: String): Boolean = {
        val b = Option(body).getOrElse("").toLowerCase
        (status == 400 || status == 409) && (b.contains("table_already_exists") || b.contains("alreadyexists"))
    }

    /** A secret with half an OAuth pair and no token would silently fall back
      * to an unauthenticated call; say so instead. None = usable shape. */
    def credentialShape(creds: ResolvedDatabricksCredentials, secretName: String): Option[String] = {
        val id = creds.clientId.exists(_.nonEmpty)
        val secret = creds.clientSecret.exists(_.nonEmpty)
        if (id != secret && !creds.token.exists(_.nonEmpty))
            Some(
                s"$ErrorPrefix secret $secretName has " + (if (id) "clientId without clientSecret" else "clientSecret without clientId") +
                    " and no token; skipping registration"
            )
        else None
    }

    private def head(body: String): String =
        Option(body).map(_.trim).filter(_.nonEmpty).map(b => " Response: " + b.take(500).replaceAll("[\\r\\n]+", " ")).getOrElse("")

    /** Register-specific wording for a non-2xx answer. Body rules first. */
    private[util] def describe(status: Int, body: String, url: String, catalog: String, schema: String, tableRoot: String): String = {
        val b = Option(body).getOrElse("").toLowerCase
        if (b.contains("external_location_does_not_exist") || b.contains("external location"))
            "no Unity Catalog external location covers " + normalize(tableRoot) + "; an admin must create one (status " + status + ")." + head(body)
        else if (status == 401 || status == 403)
            "Unity Catalog refused the register (status " + status + "): external data access is not enabled on the metastore, " +
                "or the principal lacks EXTERNAL USE SCHEMA on " + catalog + "." + schema + " (the catalog owner grants it)." + head(body)
        else if (status == 404 && b.contains("endpoint_not_found"))
            "this catalog has no Iceberg REST register endpoint (Databricks Unity Catalog does not implement it); " +
                "use `catalogMode: \"rest\"` so Datris commits through the catalog instead (status 404)." + head(body)
        else if (status == 404)
            "no Iceberg REST register at " + url + " (status 404): check the host, the secret's " + RestPathField + "/" + RestPrefixField +
                " fields, and that external data access is enabled." + head(body)
        else "Iceberg REST call to " + url + " failed with status " + status + "." + head(body)
    }

    private def otherErrors(lastError: String): Seq[String] =
        Option(lastError).toSeq.flatMap(_.split("\n")).filter(l => l.nonEmpty && !l.startsWith(ErrorPrefix))

    private def joined(lines: Seq[String]): String = if (lines.isEmpty) null else lines.mkString("\n")

    private def audit(pipeline: String, message: String): Unit =
        Try(
            AuditLog.system(
                category = "unity-catalog",
                action = "register",
                resourceType = "pipeline",
                resourceName = pipeline,
                outcome = "failure",
                errorMessage = message
            )
        )

    // --- REST orchestration --------------------------------------------------

    /** Register `table` at `metadataLocation`; returns `previous` (or a fresh
      * state) with the register fields and `uc-register:` lines updated,
      * every other field and foreign `lastError` line kept. Never throws. */
    def register(
        client: DatabricksRestClient,
        fields: Map[String, String],
        catalog: String,
        schema: String,
        table: String,
        metadataLocation: String,
        tableRoot: String,
        previous: UnityCatalogSyncState,
        statusUtil: StatusUtil,
        pipeline: String = null
    ): UnityCatalogSyncState = {
        val owner = Option(pipeline).getOrElse(table)
        val base =
            if (previous != null) previous
            else UnityCatalogSyncState(
                pipeline = owner,
                lastSyncAt = null,
                lastRunId = null,
                commentsHash = null,
                tagsHash = null,
                propertiesHash = null,
                lastError = null
            )
        val others = otherErrors(base.lastError)
        val qualified = catalog + "." + schema + "." + table
        val regPath = registerPath(fields, catalog, schema)
        var currentUrl = client.url(regPath)

        def warnLine(msg: String): String = ErrorPrefix + " " + msg.replaceAll("[\\r\\n]+", " ")

        try {
            val registered =
                try {
                    client.post(regPath, registerBody(table, normalize(metadataLocation)))
                    true
                } catch {
                    case e: DatabricksHttpException if alreadyExists(e.status, Option(e.body).getOrElse(e.getMessage)) => false
                }

            if (registered) {
                statusUtil.info("processing", s"$ErrorPrefix registered $qualified at $metadataLocation")
                base.copy(registeredMetadataLocation = metadataLocation, lastRegisterAt = Instant.now().toString, lastError = joined(others))
            } else {
                val tPath = tablePath(fields, catalog, schema, table)
                currentUrl = client.url(tPath)
                val loaded =
                    try client.get(tPath)
                    catch {
                        case e: DatabricksHttpException if e.status == 404 =>
                            throw new NotReadable(
                                "the catalog reported " + qualified + " as existing but it is not readable at " + currentUrl +
                                    "; leaving it untouched"
                            )
                    }
                val ucLoc =
                    if (loaded.has("metadata-location") && loaded.get("metadata-location").isJsonPrimitive)
                        loaded.get("metadata-location").getAsString
                    else null
                classify(ucLoc, metadataLocation, tableRoot) match {
                    case Current =>
                        statusUtil.info("processing", s"$ErrorPrefix $qualified is already registered at the current metadata file")
                        base.copy(
                            registeredMetadataLocation = metadataLocation,
                            lastRegisterAt = Option(base.lastRegisterAt).getOrElse(Instant.now().toString),
                            lastError = joined(others)
                        )
                    case Stale =>
                        val line = warnLine(
                            s"Unity Catalog still points at ${normalize(ucLoc)}; the table has moved on to ${normalize(metadataLocation)}. " +
                                "Datris will keep it current in a later release"
                        )
                        statusUtil.warn("processing", line)
                        base.copy(registeredMetadataLocation = metadataLocation, lastError = joined(line +: others))
                    case Foreign =>
                        val line = warnLine(
                            s"$qualified already exists in Unity Catalog and points at ${Option(normalize(ucLoc)).getOrElse("an unknown location")}, " +
                                s"outside this pipeline's table ${normalize(tableRoot)}; refusing to touch it (never merge, never drop); " +
                                "rename the pipeline or choose another schema"
                        )
                        statusUtil.warn("processing", line)
                        audit(owner, line)
                        base.copy(registeredMetadataLocation = null, lastError = joined(line +: others))
                }
            }
        } catch {
            case NonFatal(e) =>
                val msg = e match {
                    case h: DatabricksHttpException =>
                        describe(h.status, Option(h.body).getOrElse(""), currentUrl, catalog, schema, tableRoot)
                    case n: NotReadable => n.getMessage
                    case other => "register of " + qualified + " failed: " + Option(other.getMessage).getOrElse(other.getClass.getSimpleName)
                }
                val line = warnLine(msg)
                statusUtil.warn("processing", line)
                logger.warn("uc-register failed for pipeline " + owner + ": " + msg)
                audit(owner, line)
                base.copy(lastError = joined(line +: others))
        }
    }

    /** Loader hook: runs after a successful Iceberg commit, before the run's
      * `end` line. No-op unless the pipeline opted in (`enabled`, `register`
      * knob on, `fileFormat: iceberg`); one info line when the kill switch is
      * off. Never throws. */
    def sync(jobContext: JobContext, result: IcebergWriter.WriteResult): Unit = {
        val statusUtil = jobContext.statusUtil
        val config = jobContext.config
        try {
            if (config == null || config.unityCatalog == null) return
            val uc = config.unityCatalog
            if (!uc.enabled || !uc.registerOn) return
            val objectStore = if (config.destination != null) config.destination.objectStore else null
            if (objectStore == null || objectStore.fileFormat == null || !objectStore.fileFormat.trim.equalsIgnoreCase("iceberg")) return
            if (!UnityCatalogMetadataSync.switchedOn) {
                statusUtil.info("processing", s"$ErrorPrefix Unity Catalog sync is switched off (DATRIS_UNITY_CATALOG_SYNC=false); registration skipped")
                return
            }
            if (result == null || result.metadataLocation == null)
                throw new DatrisException("the Iceberg metadata location of this commit is unknown; registration skipped")

            val metadataLocation = result.metadataLocation
            val tableRoot = Option(tableRootOf(metadataLocation))
                .getOrElse("s3a://" + ObjectStoreSpark.resolveBucket(objectStore) + "/" + objectStore.prefixKey)
            val creds = CredentialResolver.resolveDatabricks(uc.credentialsSecret, requireCredentials = false)
            credentialShape(creds, uc.credentialsSecret) match {
                case Some(line) =>
                    statusUtil.warn("processing", line)
                    audit(config.name, line)
                    return
                case None =>
            }
            val previous =
                try UnityCatalogSyncIO.read(config.name)
                catch {
                    case NonFatal(e) =>
                        logger.warn("uc-register state read failed for " + config.name + ": " + e.getMessage)
                        null
                }
            val state = register(
                client = new DatabricksRestClient(creds),
                fields = creds.extra,
                catalog = uc.catalog,
                schema = uc.schemaOrDefault,
                table = tableName(config.name),
                metadataLocation = metadataLocation,
                tableRoot = tableRoot,
                previous = previous,
                statusUtil = statusUtil,
                pipeline = config.name
            )
            try UnityCatalogSyncIO.write(state.copy(pipeline = config.name))
            catch { case NonFatal(e) => logger.warn("uc-register state write failed for " + config.name + ": " + e.getMessage) }
        } catch {
            case t: Throwable if NonFatal(t) || t.isInstanceOf[LinkageError] =>
                val msg = Option(t.getMessage).getOrElse(t.getClass.getSimpleName).replaceAll("[\\r\\n]+", " ")
                val line = ErrorPrefix + " Unity Catalog registration failed: " + msg
                Try(statusUtil.warn("processing", line))
                Try(logger.warn("uc-register failed for " + (if (config != null) config.name else "?"), t))
                audit(if (config != null) config.name else null, line)
        }
    }
}
