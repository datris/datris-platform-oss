package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{CodeGenScript, DatrisEnvironment}
import com.google.gson.Gson

/** Record IO for the CodeGen script index (see [[CodeGenScript]]). A
  * parameter of [[PipelineScripts]] so specs run without Mongo. */
trait CodeGenScriptRecords {
    def read(pipeline: String, kind: String): Option[CodeGenScript]
    def write(record: CodeGenScript): Unit
    def delete(pipeline: String, kind: String): Unit
}

/** The CodeGen script index per `pipeline|kind` in `<env>-codegen-scripts`.
  * Kinds: `dataQuality` and `transformation`. Reads treat an unreadable row
  * as absent; writes and deletes throw (callers decide whether that is fatal). */
object CodeGenScriptIO extends CodeGenScriptRecords {

    private val gson = new Gson()

    private def table: String = DatrisEnvironment.current.codegenScriptTableName
    private def key(pipeline: String, kind: String): String = pipeline + "|" + kind

    override def write(record: CodeGenScript): Unit =
        if (record != null && record.pipeline != null && record.pipeline.nonEmpty)
            NoSQLDbUtil.putItemJSON(table, "key", key(record.pipeline, record.kind), "value", gson.toJson(record))

    override def read(pipeline: String, kind: String): Option[CodeGenScript] =
        try NoSQLDbUtil.getItemJSON(table, "key", key(pipeline, kind), "value").map(gson.fromJson(_, classOf[CodeGenScript]))
        catch { case _: Exception => None }

    override def delete(pipeline: String, kind: String): Unit =
        NoSQLDbUtil.deleteItemJSON(table, "key", key(pipeline, kind))
}
