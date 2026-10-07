package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model._
import org.scalatest.funsuite.AnyFunSuite

import scala.collection.JavaConverters._
import scala.collection.mutable.ListBuffer

/** Story: CodeGen scripts 1 (plans/stories/codegen-script-pinning.md), E2E
  * finding: a blank AI rule / AI transformation instruction ("" or spaces).
  * The save hook treats it as absent (no script, no model call), and the
  * run skips the AI stage instead of failing with "has no AI ... instruction". */
class BlankCodeGenInstructionSpec extends AnyFunSuite with AiSampleValuesMarkers {

    private class RecordingStatusUtil extends StatusUtil {
        val messages = new ListBuffer[(String, String)]()
        override def overrideProcessName(processName: String): Unit = ()
        override def info(state: String, description: String): Unit = messages += ((state, description))
        override def warn(state: String, description: String): Unit = messages += ((state, description))
        override def error(state: String, description: String): Unit = messages += ((state, description))
    }

    private class NoRecords extends CodeGenScriptRecords {
        var writes = 0
        override def read(pipeline: String, kind: String): Option[CodeGenScript] = None
        override def write(record: CodeGenScript): Unit = writes += 1
        override def delete(pipeline: String, kind: String): Unit = ()
    }

    private def cfg(rule: String, instruction: String): PipelineConfig =
        PipelineConfig(
            name = "blank",
            source = Source(
                schemaProperties = SchemaProperties(null, List(SchemaField("id", "int"), SchemaField("name", "string")).asJava),
                fileAttributes = FileAttributes(csvAttributes = CsvAttributes(delimiter = ","))
            ),
            dataQuality = if (rule == null) null else ai.datris.model.DataQuality(aiRule = AIRule(rule)),
            transformation = if (instruction == null) null else ai.datris.model.Transformation(aiTransformation = AITransformation(instruction)),
            destination = Destination(database = Database(dbName = "d", schema = "public", table = "t", usePostgres = true))
        )

    private def ctx(status: StatusUtil, config: PipelineConfig): JobContext =
        JobContext(
            pipelineToken = "blank-token",
            metadata = PipelineMetadata("blank", "rows.csv", "/tmp/rows.csv", "pub-1", bulkUpload = false),
            data = Data(size = 10L, header = List("id", "name"), headerWithSchema = null, rows = List("1,alice", "2,bob"), rawData = null),
            config = config,
            pipelineProperties = null,
            state = null,
            thread = null,
            statusUtil = status
        )

    private val blanks = Seq("", "   ", "\t")

    test("a blank instruction makes no model call and records nothing at save") {
        blanks.foreach { b =>
            var calls = 0
            val records = new NoRecords
            val scripts = new PipelineScripts(records, _ => throw new AssertionError("no store use"), (_, _) => { calls += 1; "x" }, () => "m")
            TenantContext.set(testEnv)
            val out =
                try scripts.onSave(null, cfg(b, b), "todd")
                finally TenantContext.clear()
            assert(out.isEmpty, s"blank '$b': $out")
            assert(calls == 0 && records.writes == 0, s"blank '$b'")
        }
    }

    test("a run with a blank AI rule skips the rule instead of failing") {
        blanks.foreach { b =>
            val status = new RecordingStatusUtil
            TenantContext.set(testEnv)
            try StagingArea.withToken("blank-dq")(new DataQuality(ctx(status, cfg(b, null))).process())
            catch { case e: Throwable => fail(s"blank '$b' rule failed the run: $e") }
            finally TenantContext.clear()
            assert(status.messages.exists(_._2.contains("instruction is blank")), s"${status.messages}")
            assert(status.messages.exists(_._1 == "end"), s"${status.messages}")
        }
    }

    test("a run with a blank AI transformation skips it and keeps the data") {
        blanks.foreach { b =>
            val status = new RecordingStatusUtil
            val before = ctx(status, cfg(null, b))
            TenantContext.set(testEnv)
            val after =
                try StagingArea.withToken("blank-tx")(new Transformation(before).process())
                catch { case e: Throwable => fail(s"blank '$b' transformation failed the run: $e") }
                finally TenantContext.clear()
            assert(after.data eq before.data, "data untouched")
            assert(status.messages.exists(_._2.contains("instruction is blank")), s"${status.messages}")
        }
    }
}
