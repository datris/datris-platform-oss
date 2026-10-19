package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{CodeGenScriptVersion, DatrisEnvironment}
import com.google.gson.Gson
import org.slf4j.{Logger, LoggerFactory}

/** History IO for stored CodeGen scripts (see [[CodeGenScriptVersion]]). A
  * parameter of [[PipelineScripts]] so specs run without Mongo. */
trait CodeGenScriptVersions {

    /** All versions of `pipeline|kind`, ascending by version. */
    def listVersions(pipeline: String, kind: String): List[CodeGenScriptVersion]

    /** Highest version recorded for `pipeline|kind`, or 0 if none. */
    def latestVersion(pipeline: String, kind: String): Int

    def get(pipeline: String, kind: String, version: Int): Option[CodeGenScriptVersion]

    def append(v: CodeGenScriptVersion): Unit

    /** Keep the newest `keep` versions of `pipeline|kind`; `keep <= 0` keeps all. */
    def prune(pipeline: String, kind: String, keep: Int): Unit

    /** Remove every version of both kinds for the pipeline. */
    def deleteAllForPipeline(pipeline: String): Unit
}

/** The append-only `<env>-codegen-script-version` collection: flat docs with
  * a top-level `key` (`pipeline|kind|version`) and `entityName`
  * (`pipeline|kind`), queried by entity and deleted or pruned by key, as
  * [[EntityVersionIO]]. Malformed rows are skipped with a warning. */
object CodeGenScriptVersionIO extends CodeGenScriptVersions {

    private val logger: Logger = LoggerFactory.getLogger(getClass)

    private def gson = new Gson

    private def table: String = DatrisEnvironment.current.codegenScriptVersionTableName

    def entityName(pipeline: String, kind: String): String = pipeline + "|" + kind

    def docKey(pipeline: String, kind: String, version: Int): String = entityName(pipeline, kind) + "|" + version

    override def listVersions(pipeline: String, kind: String): List[CodeGenScriptVersion] = {
        val entity = entityName(pipeline, kind)
        NoSQLDbUtil
            .queryJSONItemsByKey(table, "entityName", entity)
            .flatMap { json =>
                try Option(gson.fromJson(json, classOf[CodeGenScriptVersion])).filter(v => v.key != null && v.version > 0)
                catch {
                    case e: Exception =>
                        logger.warn("Skipping malformed CodeGen script version record for \"" + entity + "\" in table \"" + table + "\"", e)
                        None
                }
            }
            .sortBy(_.version)
    }

    override def latestVersion(pipeline: String, kind: String): Int = {
        val versions = listVersions(pipeline, kind)
        if (versions.isEmpty) 0 else versions.map(_.version).max
    }

    override def get(pipeline: String, kind: String, version: Int): Option[CodeGenScriptVersion] =
        listVersions(pipeline, kind).find(_.version == version)

    override def append(v: CodeGenScriptVersion): Unit =
        NoSQLDbUtil.insertJSON(table, gson.toJson(v))

    override def prune(pipeline: String, kind: String, keep: Int): Unit = {
        if (keep <= 0) return
        val all = listVersions(pipeline, kind)
        if (all.size <= keep) return
        all.dropRight(keep).foreach(delete)
    }

    override def deleteAllForPipeline(pipeline: String): Unit =
        PipelineScripts.Kinds.foreach(kind => listVersions(pipeline, kind).foreach(delete))

    private def delete(v: CodeGenScriptVersion): Unit =
        try NoSQLDbUtil.deleteItemJSON(table, "key", v.key)
        catch {
            case e: Exception => logger.warn("Failed to delete CodeGen script version record \"" + v.key + "\" from table \"" + table + "\"", e)
        }
}
