package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{CodeGenScript, DatrisEnvironment}
import com.google.gson.Gson
import org.slf4j.LoggerFactory

/** Last generated CodeGen script per pipeline (see [[CodeGenScript]]).
  * Writes never fail the caller. */
object CodeGenScriptIO {

    private val logger = LoggerFactory.getLogger(getClass)
    private val gson = new Gson()

    private def table: String = DatrisEnvironment.current.codegenScriptTableName
    private def key(pipeline: String, kind: String): String = pipeline + "|" + kind

    def write(pipeline: String, kind: String, instruction: String, script: String): Unit = {
        if (pipeline == null || pipeline.isEmpty || script == null) return
        try {
            val doc = CodeGenScript(pipeline, kind, instruction, script, java.time.Instant.now().toString)
            NoSQLDbUtil.putItemJSON(table, "key", key(pipeline, kind), "value", gson.toJson(doc))
        } catch {
            case e: Exception => logger.debug("codegen script record skipped for " + pipeline + ": " + e.getMessage)
        }
    }

    def read(pipeline: String, kind: String): Option[CodeGenScript] =
        try NoSQLDbUtil.getItemJSON(table, "key", key(pipeline, kind), "value").map(gson.fromJson(_, classOf[CodeGenScript]))
        catch { case _: Exception => None }
}
