package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import com.google.gson.Gson
import ai.datris.model.{CodeRepoConfig, DatrisEnvironment}

object CodeRepoConfigIO {

    def read(): Option[CodeRepoConfig] = {
        val tableName = DatrisEnvironment.current.codeRepoTableName
        NoSQLDbUtil.getItemJSON(tableName, "name", "default", "value").map(parse)
    }

    /** The stored document `json` as a configuration. Gson skips Scala
      * defaults for absent fields, so the ones where null would break
      * URL/path building downstream are normalised, and a document stored
      * before `earlierPathPrefixes` existed reads it as an empty list. Only
      * the value read is normalised; the stored document is not rewritten. */
    private[datris] def parse(json: String): CodeRepoConfig = {
        val config = new Gson().fromJson(json, classOf[CodeRepoConfig])
        var normalized = config
        if (normalized.apiBaseUrl == null || normalized.apiBaseUrl.isEmpty)
            normalized = normalized.copy(apiBaseUrl = "https://api.github.com")
        if (normalized.branch == null || normalized.branch.isEmpty)
            normalized = normalized.copy(branch = "main")
        if (normalized.pathPrefix == null)
            normalized = normalized.copy(pathPrefix = "")
        if (normalized.earlierPathPrefixes == null)
            normalized = normalized.copy(earlierPathPrefixes = new java.util.ArrayList[String]())
        normalized
    }

    /** The repo config iff it is enabled and complete enough to use. */
    def readEnabled(): Option[CodeRepoConfig] =
        read().filter(c => c.enabled && c.repo != null && c.repo.nonEmpty && c.authSecretName != null && c.authSecretName.nonEmpty)

    def write(config: CodeRepoConfig): CodeRepoConfig = {
        val env = DatrisEnvironment.current
        val sdf = new java.text.SimpleDateFormat(env.dateFormat)
        sdf.setTimeZone(java.util.TimeZone.getTimeZone(env.dateTimezone))
        val now = sdf.format(new java.util.Date())
        val existing = read()
        val stamped = config.copy(
            name = "default",
            createdAt = existing.flatMap(e => Option(e.createdAt)).getOrElse(now),
            updatedAt = now
        )
        NoSQLDbUtil.putItemJSON(env.codeRepoTableName, "name", "default", "value", new Gson().toJson(stamped))
        stamped
    }
}
