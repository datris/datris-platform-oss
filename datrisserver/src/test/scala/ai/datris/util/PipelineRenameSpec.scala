package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model._
import com.google.gson.{Gson, JsonParser}
import org.scalatest.funsuite.AnyFunSuite

/** Story: Pipeline rename keeps its data
  * (plans/stories/pipeline-rename-keeps-data.md), Acceptance, PipelineRenameSpec.
  *
  * Pure helpers behind POST /api/v1/pipelines/{name}/rename. Assumed:
  * {{{
  * object PipelineRename {
  *   // blank, unchanged or > 80 chars (as PipelineValidatorUtil); judged on the trimmed new name
  *   def nameProblem(old: String, newName: String): Option[String]
  *   def renamedConfig(c: PipelineConfig, newName: String): PipelineConfig
  *   def rekeyVersion(v: EntityVersion, newName: String): EntityVersion
  *   def rekeyScriptVersion(v: CodeGenScriptVersion, newName: String): CodeGenScriptVersion
  *   def tapsToRepoint(taps: Seq[TapConfig], old: String): Seq[TapConfig]
  *   def repointed(tap: TapConfig, newName: String): TapConfig
  *   def resumable(old: PipelineConfig, existingNew: PipelineConfig): Boolean
  *   def unityCatalogBlock(enabled: Boolean, hasState: Boolean): Option[String]
  *   def jobBlock(jobs: Seq[(String, JobState)], old: String): Boolean
  * }
  * }}}
  */
class PipelineRenameSpec extends AnyFunSuite {

    private def pg(name: String, table: String = "rn_rows", catalog: String = "Sales"): PipelineConfig =
        PipelineConfig(
            name = name,
            catalog = catalog,
            createdByKeyLabel = "sales-builder",
            version = 3,
            authoritative = java.lang.Boolean.TRUE,
            destination = Destination(
                database = Database(dbName = "datris", schema = "public", table = table, usePostgres = true),
                authoritative = "postgres"
            )
        )

    private def tap(name: String, target: String, catalog: String = "Sales"): TapConfig =
        TapConfig(name = name, description = "t", targetPipeline = target, catalog = catalog, enabled = false)

    test("nameProblem refuses a blank, unchanged or over-long name and trims the new name") {
        assert(PipelineRename.nameProblem("rn_a", null).isDefined)
        assert(PipelineRename.nameProblem("rn_a", "").isDefined)
        assert(PipelineRename.nameProblem("rn_a", "   ").isDefined)
        assert(PipelineRename.nameProblem("rn_a", "rn_a").isDefined)
        // unchanged once trimmed
        assert(PipelineRename.nameProblem("rn_a", "  rn_a  ").isDefined)
        // 80 is the limit (PipelineValidatorUtil): 80 is fine, 81 is refused
        assert(PipelineRename.nameProblem("rn_a", "x" * 80).isEmpty)
        assert(PipelineRename.nameProblem("rn_a", "x" * 81).isDefined)
        // surrounding blanks do not count towards the limit
        assert(PipelineRename.nameProblem("rn_a", "  " + ("x" * 80) + "  ").isEmpty)
        assert(PipelineRename.nameProblem("rn_a", " rn_b ").isEmpty)
        assert(PipelineRename.nameProblem("rn_a", "rn_b").isEmpty)
    }

    test("renamedConfig changes the name and keeps destination, catalog, owner label and authority fields") {
        val old = pg("rn_a")
        val r = PipelineRename.renamedConfig(old, "rn_b")
        assert(r.name == "rn_b")
        assert(r.destination == old.destination)
        assert(r.destination.database.table == "rn_rows")
        assert(r.destination.authoritative == "postgres")
        assert(r.catalog == "Sales")
        assert(r.createdByKeyLabel == "sales-builder")
        assert(r.authoritative == java.lang.Boolean.TRUE)
        assert(r == old.copy(name = "rn_b"), "only the name changes")
    }

    test("rekeyVersion rewrites key, entityName and the name inside the snapshot config, keeping version, note, time and actor") {
        val snap = new Gson().toJson(pg("rn_a"))
        val v = EntityVersion(
            key = "rn_a|2",
            entityName = "rn_a",
            version = 2,
            config = snap,
            changeNote = "added a column",
            createdAt = "2026-11-17 10:00:00",
            createdBy = "admin"
        )
        val r = PipelineRename.rekeyVersion(v, "rn_b")
        assert(r.key == "rn_b|2", r.key)
        assert(r.entityName == "rn_b")
        assert(r.version == 2)
        assert(r.changeNote == "added a column")
        assert(r.createdAt == "2026-11-17 10:00:00")
        assert(r.createdBy == "admin")
        val cfg = JsonParser.parseString(r.config).getAsJsonObject
        assert(cfg.get("name").getAsString == "rn_b", "a restored snapshot must not resurrect the old name: " + r.config)
        // the rest of the snapshot is untouched
        val before = JsonParser.parseString(snap).getAsJsonObject
        before.remove("name")
        cfg.remove("name")
        assert(cfg == before)
        assert(!r.config.contains("\"rn_a\""), r.config)
    }

    test("rekeyScriptVersion rewrites key, entityName and pipeline and keeps the script text") {
        val v = CodeGenScriptVersion(
            key = "rn_a|aiRule|4",
            entityName = "rn_a|aiRule",
            pipeline = "rn_a",
            kind = "aiRule",
            version = 4,
            script = "def check(row):\n    return row['id'] != 'rn_a'\n",
            model = "opus",
            fingerprint = "fp1",
            generatedAt = "2026-11-01"
        )
        val r = PipelineRename.rekeyScriptVersion(v, "rn_b")
        assert(r.key == "rn_b|aiRule|4", r.key)
        assert(r.entityName == "rn_b|aiRule", r.entityName)
        assert(r.pipeline == "rn_b")
        assert(r.kind == "aiRule")
        assert(r.version == 4)
        assert(r.script == v.script, "the script text is never rewritten, even where it mentions the old name")
        assert(r.model == "opus" && r.fingerprint == "fp1" && r.generatedAt == "2026-11-01")
    }

    test("tapsToRepoint selects exactly the taps whose targetPipeline is the old name") {
        val taps = Seq(
            tap("t1", "rn_a"),
            tap("t2", "rn_ab"),
            tap("t3", "RN_A"),
            tap("t4", null),
            tap("t5", "rn_a", catalog = "HR"),
            tap("t6", " rn_a")
        )
        assert(PipelineRename.tapsToRepoint(taps, "rn_a").map(_.name) == Seq("t1", "t5"))
        assert(PipelineRename.tapsToRepoint(Nil, "rn_a").isEmpty)

        val moved = PipelineRename.repointed(taps.head, "rn_b")
        assert(moved.targetPipeline == "rn_b")
        assert(moved == taps.head.copy(targetPipeline = "rn_b"), "only targetPipeline changes")
    }

    test("resumable is true only for a config equal to the old one apart from name and version") {
        val old = pg("rn_a")
        assert(PipelineRename.resumable(old, old.copy(name = "rn_b")))
        assert(PipelineRename.resumable(old, old.copy(name = "rn_b", version = 1)))
        assert(PipelineRename.resumable(old, old.copy(name = "rn_b", version = 9)))
        assert(!PipelineRename.resumable(old, null))
        assert(!PipelineRename.resumable(old, pg("rn_b", table = "rn_other")))
        assert(!PipelineRename.resumable(old, pg("rn_b", catalog = "HR")))
        assert(!PipelineRename.resumable(old, old.copy(name = "rn_b", createdByKeyLabel = "someone-else")))
        assert(!PipelineRename.resumable(old, old.copy(name = "rn_b", authoritative = java.lang.Boolean.FALSE)))
    }

    test("unityCatalogBlock refuses when sync is enabled or a state doc exists") {
        assert(PipelineRename.unityCatalogBlock(enabled = true, hasState = false).isDefined)
        assert(PipelineRename.unityCatalogBlock(enabled = false, hasState = true).isDefined)
        assert(PipelineRename.unityCatalogBlock(enabled = true, hasState = true).isDefined)
        assert(PipelineRename.unityCatalogBlock(enabled = false, hasState = false).isEmpty)
        assert(PipelineRename.unityCatalogBlock(enabled = true, hasState = false).get.toLowerCase.contains("unity catalog"))
    }

    test("jobBlock is true for an initialized or processing job of the pipeline and false for completed, cancelled and other pipelines") {
        assert(PipelineRename.jobBlock(Seq("rn_a" -> INITIALIZED), "rn_a"))
        assert(PipelineRename.jobBlock(Seq("rn_a" -> PROCESSING), "rn_a"))
        assert(PipelineRename.jobBlock(Seq("rn_a" -> COMPLETED, "rn_a" -> PROCESSING), "rn_a"))
        assert(!PipelineRename.jobBlock(Seq("rn_a" -> COMPLETED), "rn_a"))
        assert(!PipelineRename.jobBlock(Seq("rn_a" -> CANCELLED), "rn_a"))
        assert(!PipelineRename.jobBlock(Seq("rn_b" -> PROCESSING, "rn_ab" -> INITIALIZED), "rn_a"))
        assert(!PipelineRename.jobBlock(Nil, "rn_a"))
    }
}
