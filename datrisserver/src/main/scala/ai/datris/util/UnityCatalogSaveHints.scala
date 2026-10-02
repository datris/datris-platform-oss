package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.PipelineConfig

import scala.util.Try

/** Advisory hints returned by `POST /api/v1/pipeline` when an object-store
 *  Iceberg pipeline's Unity Catalog secret points at a Databricks workspace
 *  and the configured catalogMode cannot work there: Databricks has no
 *  Iceberg REST register call (`register`), and a table created through its
 *  REST catalog lands at a Databricks-managed location Datris refuses to
 *  write (`rest`). `managed` is the advised fix and produces no hint.
 *
 *  Never a validation failure: the run-time refusal stays the safety net and
 *  the secret may be unreadable at save time (then `hostOf` gives None).
 *  The wording must not contain "error" or "exception": the MCP server treats
 *  a save body containing either as a failure. */
object UnityCatalogSaveHints {

    /** Host of the Platform secret, or None when it is missing, not a
     *  Databricks-shaped secret, or unreadable. Host-only secrets suffice. */
    def resolveHost(secret: String): Option[String] =
        Try(CredentialResolver.resolveDatabricks(secret, requireCredentials = false).host).toOption

    def forConfig(config: PipelineConfig, hostOf: String => Option[String]): Seq[String] = {
        if (config == null) return Nil
        val uc = config.unityCatalog
        if (uc == null || !uc.enabled || !uc.registerOn) return Nil
        val dest = config.destination
        if (dest == null || dest.objectStore == null) return Nil
        if (dest.database != null && dest.database.useDatabricks) return Nil
        val fileFormat = dest.objectStore.fileFormat
        if (fileFormat == null || !fileFormat.trim.equalsIgnoreCase("iceberg")) return Nil
        if (uc.credentialsSecret == null || uc.credentialsSecret.trim.isEmpty) return Nil

        val host = Try(hostOf(uc.credentialsSecret)).toOption.flatten
            .filter(DatabricksHostDetector.isDatabricksHost)
            .map(DatabricksConnectionUtil.normalizeHost)
        host match {
            case None => Nil
            case Some(h) =>
                uc.catalogModeOrDefault match {
                    case "register" => Seq(registerHint(h))
                    case "rest" => Seq(restHint(h))
                    case _ => Nil
                }
        }
    }

    private def registerHint(host: String): String =
        "Unity Catalog on Databricks (" + host + ") has no Iceberg REST register call, so this " +
            "pipeline's table will not be registered; each run will say so and the load itself " +
            "still succeeds. Set catalogMode managed so the table is created in Unity Catalog's " +
            "managed storage, or use the databricks destination."

    private def restHint(host: String): String =
        "Unity Catalog on Databricks (" + host + ") creates a managed table at its own location when " +
            "a table is created through its REST catalog; Datris refuses to write there, the run " +
            "writes by path and the pipeline page shows refused. Set catalogMode managed instead, " +
            "or use the databricks destination."
}
