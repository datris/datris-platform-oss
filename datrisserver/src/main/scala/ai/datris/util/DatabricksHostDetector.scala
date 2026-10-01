package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

/** Recognises a Databricks workspace hostname (AWS/GCP `*.databricks.com`,
 *  Azure `*.azuredatabricks.net`) from whatever shape the secret's `host`
 *  field holds: scheme, userinfo, port, path, mixed case and surrounding
 *  whitespace are tolerated via DatabricksConnectionUtil.normalizeHost. A
 *  hostname that appears only in a path, or a look-alike such as
 *  `databricks.com.evil.example`, is not a Databricks host. */
object DatabricksHostDetector {

    val Suffixes: Seq[String] = Seq(".databricks.com", ".azuredatabricks.net")

    def isDatabricksHost(raw: String): Boolean = {
        if (raw == null || raw.trim.isEmpty) return false
        val host = DatabricksConnectionUtil.normalizeHost(raw).toLowerCase
        host.nonEmpty && Suffixes.exists(host.endsWith)
    }
}
