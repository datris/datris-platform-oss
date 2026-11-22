package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.auth.ScopeDenialFixtures
import ai.datris.model._
import ai.datris.util.{DestinationDrop, PipelineDeletePlan, SharedDatasets, VectorContainers, VectorStoreDrop}
import com.google.gson.JsonParser
import com.mongodb.{MongoSocketOpenException, ServerAddress}
import com.mongodb.client.{MongoClient, MongoCollection, MongoDatabase}
import org.bson.Document
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.{mock, when}
import org.scalatest.BeforeAndAfterEach
import org.scalatest.funsuite.AnyFunSuite

import scala.collection.JavaConverters._
import scala.collection.mutable.ArrayBuffer

/** Story: Pipeline delete: a table, collection or prefix that could not be
  * removed is reported, and no cleanup can hang the request
  * (plans/stories/pipeline-delete-reports-failed-cleanup.md), Acceptance,
  * `PipelineDeleteCleanupWarningSpec`.
  *
  * The delete path reads the config store, Vault and the stores, none of
  * which has a test override except the MongoDB drop client
  * (`PipelineAPIController.mongoDropClientOverride`). So the real
  * `cleanupDestinationData` is driven for a MongoDB pipeline with a fake
  * client (no MongoDB is reached), the warning assembly and the ledger rule
  * go through pure seams fed with injected `DestinationDrop.Step`s, and
  * where `deletePipelineOutcome` wires them together a source pin checks
  * the wiring (as VectorDeleteFailureWarningSpec does).
  *
  * Pinned seam:
  * {{{
  * object PipelineAPIController {
  *     // The sibling's case class, widened (defaults keep the two-argument
  *     // construction compiling): every non-vector cleanup step with its
  *     // outcome, in destination order, and the labels of object-store
  *     // prefixes kept because another pipeline's prefix overlaps.
  *     case class DestinationCleanup(
  *         objectStoreDataDeleted: Boolean,
  *         vectorFailures: Seq[(VectorContainers.Key, String)],
  *         steps: Seq[(DestinationDrop.Step, DestinationDrop.Outcome)] = Nil,
  *         keptPrefixes: Seq[String] = Nil
  *     ) {
  *         // a vector failure, or a Failed step with countsAsData
  *         def dataLeft: Boolean
  *     }
  *     // Kept lines for keptPrefixes (SharedDatasets.keptOnDeleteMessage(label,
  *     // None)), then one DestinationDrop.warning per Failed step, in step
  *     // order. Vector failures stay where the sibling put them.
  *     def cleanupWarnings(cleanup: DestinationCleanup, configDeleted: Boolean): Seq[String]
  *     // The warning for a document-tap ledger cleanup that failed; it is
  *     // given no tap name.
  *     def ledgerCleanupWarning(reason: String, configDeleted: Boolean): String
  *     // The config row delete (Decision 6): on failure, rethrows; when the
  *     // data was already removed, with the Decision 6 sentence as message.
  *     def deleteConfigRow(dataRemoved: Boolean, delete: () => Unit): Unit
  * }
  * }}}
  */
class PipelineDeleteCleanupWarningSpec extends AnyFunSuite with BeforeAndAfterEach {

    import PipelineAPIController.DestinationCleanup

    private val UR = "the store could not be reached"
    private val TO = "the store did not answer in time"
    private val CR = "the store refused the credentials"
    private val PM = "the store refused permission to remove it"
    private val RE = "the store reported an error"
    private val NC = "the store is not configured"

    private val full = PipelineDeletePlan.of("true", "true")
    private val reset = PipelineDeletePlan.of("true", "false")

    // ------------------------------------------------------------ fixtures

    private var saved: DatrisEnvironment = _
    private val dropped = ArrayBuffer[String]()
    @volatile private var dropFailure: Throwable = null

    private def env(): DatrisEnvironment =
        ai.datris.util.PlatformCollectionFixtures.named(
            ScopeDenialFixtures.auditEnv().copy(
                environment = "oss",
                useApiKeys = false,
                useAuditLog = false,
                multiTenant = false,
                postgresDatabase = "datris",
                mongoDbConfig = MongoDBConfig("mongodb://unused", "datris", "oss")
            )
        )

    private def fakeClient(): MongoClient = {
        val client = mock(classOf[MongoClient])
        when(client.getDatabase(anyString())).thenAnswer { i =>
            val dbName = i.getArgument[String](0)
            val db = mock(classOf[MongoDatabase])
            when(db.getCollection(anyString())).thenAnswer { j =>
                val coll = mock(classOf[MongoCollection[Document]])
                val cn = j.getArgument[String](0)
                org.mockito.Mockito
                    .doAnswer { _ =>
                        val f = dropFailure
                        if (f != null) throw f
                        dropped += dbName + "." + cn
                        null
                    }
                    .when(coll)
                    .drop()
                coll
            }
            db
        }
        client
    }

    override def beforeEach(): Unit = {
        saved = DatrisEnvironment.values
        dropped.clear()
        dropFailure = null
        PipelineAPIController.mongoDropClientOverride = () => fakeClient()
        ScopeDenialFixtures.install(env())
    }

    override def afterEach(): Unit = {
        PipelineAPIController.mongoDropClientOverride = null
        ScopeDenialFixtures.install(saved)
    }

    private def mongoPipeline(name: String = "cf_mongo"): PipelineConfig =
        PipelineConfig(
            name = name,
            catalog = "Sales",
            createdByKeyLabel = "someone",
            destination = Destination(database = Database(dbName = "analytics", table = "orders", useMongoDB = true))
        )

    private def mongoLabel(c: PipelineConfig): String =
        SharedDatasets.label(SharedDatasets.keysOf(c, SharedDatasets.Env("oss-data", None, None)).find(_.kind == "mongodb").get)

    private def step(label: String, outcome: DestinationDrop.Outcome, countsAsData: Boolean = true): DestinationDrop.Step =
        DestinationDrop.Step(label, countsAsData, () => outcome)

    private def cleanupOf(steps: Seq[DestinationDrop.Step], vector: Seq[(VectorContainers.Key, String)] = Nil, kept: Seq[String] = Nil): DestinationCleanup =
        DestinationCleanup(objectStoreDataDeleted = false, vectorFailures = vector, steps = DestinationDrop.runAll(steps), keptPrefixes = kept)

    private def bodyWarnings(body: String): List[String] =
        JsonParser.parseString(body).getAsJsonObject.get("warnings").getAsJsonArray.asScala.map(_.getAsString).toList

    // ------------------------------------------------------------ source pins

    private def source(rel: String): String =
        new String(
            java.nio.file.Files.readAllBytes(
                Seq(java.nio.file.Paths.get("src/main/scala/" + rel), java.nio.file.Paths.get("datrisserver/src/main/scala/" + rel))
                    .find(java.nio.file.Files.exists(_))
                    .get
            ),
            java.nio.charset.StandardCharsets.UTF_8
        )

    private lazy val controller = source("ai/datris/api/PipelineAPIController.scala")

    /** The text of `def <name>(` up to the next member definition at the same indent. */
    private def method(name: String): String = {
        val start = Seq("    def ", "    private[api] def ", "    private def ").map(p => controller.indexOf(p + name + "(")).filter(_ >= 0) match {
            case Nil => -1
            case is => is.min
        }
        assert(start >= 0, s"$name not found")
        val next = "\n    (private |private\\[api\\] |override )?(def|val|lazy val|var) ".r
        val bodyFrom = controller.indexOf("\n", start) + 1
        val end = next.findFirstMatchIn(controller.substring(bodyFrom)).map(m => bodyFrom + m.start).getOrElse(controller.length)
        controller.substring(start, end)
    }

    /** The rest of a call's argument list from `from` up to its closing parenthesis. */
    private def argument(text: String, from: Int): String = {
        var depth = 0
        var i = from
        while (i < text.length && !(text.charAt(i) == ')' && depth == 0)) {
            if (text.charAt(i) == '(') depth += 1
            if (text.charAt(i) == ')') depth -= 1
            i += 1
        }
        text.substring(from, i).trim
    }

    // ------------------------------------------------------------ bullet 1

    test("all steps Removed or Absent: no warning added and deleteResponseBody equals today's body") {
        val clean = cleanupOf(
            Seq(
                step("postgres table datris.public.a", DestinationDrop.Removed),
                step("mongodb collection datris.b", DestinationDrop.Absent),
                step("object-store prefix oss-data.c", DestinationDrop.Removed),
                step("scratch folder of this pipeline", DestinationDrop.NotSent, countsAsData = false)
            )
        )
        Seq(true, false).foreach(cd => assert(PipelineAPIController.cleanupWarnings(clean, cd).isEmpty, cd))
        assert(!clean.dataLeft)
        assert(PipelineAPIController.deleteResponseBody(PipelineAPIController.cleanupWarnings(clean, configDeleted = true), None) == "{\"warnings\":[]}")
        val kept = SharedDatasets.keptOnDeleteMessage("postgres table datris.public.shared", Some(Seq("other")))
        assert(PipelineAPIController.deleteResponseBody(
            Seq(kept) ++ PipelineAPIController.cleanupWarnings(clean, true),
            None
        ) == "{\"warnings\":[\"" + kept + "\"]}")

        // The real cleanup of a MongoDB pipeline whose drop succeeds: one
        // step, Removed, labelled with the config's own key, no warning.
        val c = mongoPipeline()
        val ctl = new PipelineAPIController()
        val done = ctl.cleanupDestinationData(c, Set.empty, Set.empty, None)
        assert(dropped == Seq("analytics.orders"), dropped)
        val mongoSteps = done.steps.filter(_._1.label == mongoLabel(c))
        assert(mongoSteps.map(_._2) == Seq(DestinationDrop.Removed), done.steps)
        assert(mongoSteps.head._1.countsAsData)
        assert(PipelineAPIController.cleanupWarnings(done, configDeleted = true).isEmpty)
        assert(!done.dataLeft)
        assert(!done.objectStoreDataDeleted && done.vectorFailures.isEmpty)
    }

    // ------------------------------------------------------------ bullet 2

    test("one Failed step per destination kind yields one warning each, after the Kept warnings, in destination order") {
        val labels = Seq(
            "postgres table datris.public.cf_pg" -> UR,
            "mongodb collection analytics.orders" -> CR,
            "snowflake table DB.S.T" -> PM,
            "sqlserver table db.dbo.t" -> TO,
            "databricks table c.s.t" -> RE,
            "pgvector table public.cf_vec" -> NC,
            "object-store prefix oss-data.cf_os" -> UR,
            "scratch folder of this pipeline" -> PM
        )
        val cleanup = cleanupOf(labels.map { case (l, r) => step(l, DestinationDrop.Failed(r), countsAsData = l != "scratch folder of this pipeline") })
        Seq(true, false).foreach { cd =>
            val ws = PipelineAPIController.cleanupWarnings(cleanup, cd)
            assert(ws == labels.map { case (l, r) => DestinationDrop.warning(l, r, cd) }, ws)
            assert(ws.distinct.size == labels.size)
        }
        // In the body: the Kept lines first, then the failures.
        val kept = SharedDatasets.keptOnDeleteMessage("postgres table datris.public.shared", None)
        val ws = PipelineAPIController.cleanupWarnings(cleanup, configDeleted = true)
        assert(bodyWarnings(PipelineAPIController.deleteResponseBody(kept +: ws, None)) == (kept +: ws).toList)

        // The real cleanup: a MongoDB drop the store refuses is one warning
        // naming the pipeline's own collection, nothing else.
        dropFailure = new MongoSocketOpenException("Exception opening socket", new ServerAddress("mongo-host.internal", 27017))
        val c = mongoPipeline()
        val done = new PipelineAPIController().cleanupDestinationData(c, Set.empty, Set.empty, None)
        assert(dropped.isEmpty)
        assert(done.steps.filter(_._1.label == mongoLabel(c)).map(_._2) == Seq(DestinationDrop.Failed(UR)), done.steps)
        assert(
            PipelineAPIController.cleanupWarnings(done, configDeleted = true) == Seq(
                "Could not remove mongodb collection analytics.orders: the store could not be reached. Its pipeline configuration was deleted; remove it in the store."
            )
        )
        assert(done.dataLeft)

        // Wiring: the steps run in destination order inside
        // cleanupDestinationData (Postgres, MongoDB, Snowflake, SQL Server,
        // Databricks, pgvector, object store, scratch) and the delete adds
        // their warnings after the Kept warnings, before the ledger decision.
        val cleanupSrc = method("cleanupDestinationData")
        val order = Seq(
            "dropStructured(\"postgres\")",
            "dropStructured(\"mongodb\")",
            "dropStructured(\"snowflake\")",
            "dropStructured(\"sqlserver\")",
            "dropStructured(\"databricks\")",
            "dropStructured(\"pgvector\")",
            "dest.objectStore != null",
            "cleanupScratchData("
        ).map(cleanupSrc.indexOf(_))
        assert(order.forall(_ >= 0) && order == order.sorted, s"destination order in cleanupDestinationData: $order")
        assert(cleanupSrc.contains("DestinationDrop.runAll("), "cleanupDestinationData does not run its blocks as DestinationDrop steps")
        val outcome = method("deletePipelineOutcome")
        val keptAt = outcome.indexOf("PipelineAPIController.structuredDeleteWarnings(")
        val failAt = outcome.indexOf("PipelineAPIController.cleanupWarnings(")
        val ledgerAt = outcome.indexOf("clearTapLedgersForPlan(")
        assert(keptAt >= 0 && failAt > keptAt && ledgerAt > failAt, s"kept=$keptAt failures=$failAt ledgers=$ledgerAt")
        val arg = argument(outcome, failAt + "PipelineAPIController.cleanupWarnings(".length)
        assert(arg.endsWith("deleteConfigBool"), s"the warnings must follow the config-delete mode: $arg")
    }

    // ------------------------------------------------------------ bullet 3

    test("a reset with a failed data step keeps the tap ledgers; with only a failed scratch or history step it clears them; a full delete always clears them") {
        val dataFailed = cleanupOf(Seq(
            step("postgres table datris.public.cf_pg", DestinationDrop.Failed(TO)),
            step("scratch folder of this pipeline", DestinationDrop.Removed, countsAsData = false)
        ))
        val sideFailed = cleanupOf(
            Seq(
                step("postgres table datris.public.cf_pg", DestinationDrop.Removed),
                step("scratch folder of this pipeline", DestinationDrop.Failed(UR), countsAsData = false),
                step("stored history of this pipeline", DestinationDrop.Failed(RE), countsAsData = false)
            )
        )
        val vectorFailed = DestinationCleanup(false, Seq(VectorContainers.key("qdrant", "vd_q", None).get -> UR))
        assert(dataFailed.dataLeft && vectorFailed.dataLeft && !sideFailed.dataLeft)

        assert(!PipelineAPIController.clearTapLedgersForPlan(reset, keptSharedVector = dataFailed.dataLeft))
        assert(PipelineAPIController.clearTapLedgersForPlan(reset, keptSharedVector = sideFailed.dataLeft))
        assert(PipelineAPIController.clearTapLedgersForPlan(full, keptSharedVector = dataFailed.dataLeft))
        assert(PipelineAPIController.clearTapLedgersForPlan(full, keptSharedVector = sideFailed.dataLeft))

        // A scratch failure still warns (it just does not keep the ledgers).
        assert(PipelineAPIController.cleanupWarnings(sideFailed, configDeleted = false).size == 2)

        // The real reset of a MongoDB pipeline whose drop failed leaves data.
        dropFailure = new MongoSocketOpenException("Exception opening socket", new ServerAddress("mongo-host.internal", 27017))
        assert(new PipelineAPIController().cleanupDestinationData(mongoPipeline(), Set.empty, Set.empty, None).dataLeft)

        // Wiring: the delete's ledger flag includes the data steps.
        val outcome = method("deletePipelineOutcome")
        val at = outcome.indexOf("clearTapLedgersForPlan(plan,")
        assert(at >= 0, "clearTapLedgersForPlan(plan, ...) not found in deletePipelineOutcome")
        val a = argument(outcome, at + "clearTapLedgersForPlan(plan,".length)
        val definition =
            if (a.matches("[A-Za-z_][A-Za-z0-9_]*"))
                ("val " + a + "\\s*(:\\s*Boolean\\s*)?=([^\\n]*)").r.findFirstMatchIn(outcome).map(_.group(2)).getOrElse(a)
            else a
        assert(definition.contains("sharedVector.nonEmpty") && definition.contains("dataLeft"), s"ledger argument ignores failed data steps: $a / $definition")
    }

    // ------------------------------------------------------------ bullet 4

    test("a failed ledger cleanup warns without naming the tap") {
        Seq(UR, TO, RE, NC).foreach { r =>
            Seq(true, false).foreach { cd =>
                val w = PipelineAPIController.ledgerCleanupWarning(r, cd)
                assert(w.startsWith("Could not remove "), w)
                assert(w.contains(": " + r + ". "), w)
                // Review finding 6: platform-internal state, nothing to remove in a store.
                assert(
                    w.endsWith(if (cd) "clear the ledger of that tap (DELETE /api/v1/tap/ledger?name=<tap>) before loading again."
                    else "Run the reset again to retry."),
                    w
                )
                assert(!w.contains("remove it in the store"), w)
                // Ledgers are keyed by tap: never claim a later pipeline delete clears them.
                if (cd) assert(w.contains("will skip the documents it already loaded") && !w.contains("removed the next time"), w)
                assert(!w.contains("'"), s"the ledger warning quotes a name: $w")
            }
        }
        // Wiring: the ledger cleanup reports an outcome, and the delete turns
        // a failure into this warning (never the tap name it logs).
        val ledgers = method("cleanupDocumentTapLedgers")
        val signature = ledgers.takeWhile(_ != '{')
        assert(!signature.contains(": Unit"), s"cleanupDocumentTapLedgers still returns Unit: $signature")
        assert(method("deletePipelineOutcome").contains("PipelineAPIController.ledgerCleanupWarning("), "deletePipelineOutcome never adds the ledger warning")
        assert(!method("ledgerCleanupWarning").contains("tap.name"))
    }

    // ------------------------------------------------------------ bullet 5

    test("an overlapping object-store prefix yields a Kept line with no pipeline name") {
        val label = SharedDatasets.label(SharedDatasets.Key("objectstore", "objectstore", Seq("lake", "cf_os")))
        val cleanup = cleanupOf(Nil, kept = Seq(label))
        val ws = PipelineAPIController.cleanupWarnings(cleanup, configDeleted = true)
        assert(ws == Seq(SharedDatasets.keptOnDeleteMessage(label, None)), ws)
        assert(ws.head == "Kept object-store prefix lake.cf_os: another pipeline also writes to it.", ws)
        assert(!cleanup.objectStoreDataDeleted)
        // A kept prefix is a Kept line, first, before any failure.
        val mixed = cleanupOf(Seq(step("postgres table datris.public.cf_pg", DestinationDrop.Failed(UR))), kept = Seq(label))
        val mw = PipelineAPIController.cleanupWarnings(mixed, configDeleted = true)
        assert(mw.size == 2 && mw.head.startsWith("Kept ") && mw(1).startsWith("Could not remove "), mw)
        // Wiring: the overlap branch records the kept prefix.
        assert(method("cleanupDestinationData").contains("keptPrefixes"), "cleanupDestinationData does not report a kept overlapping prefix")
    }

    // ------------------------------------------------------------ bullet 6

    test("the cascade renders a failed cleanup as {pipeline, message}") {
        val w = DestinationDrop.warning("postgres table datris.public.cf_pg", UR, configDeleted = true)
        val arr = CatalogAPIController.warningsArray(Seq("cf_pg" -> w), Nil)
        assert(arr.size == 1, arr)
        val o = arr.get(0).getAsJsonObject
        assert(o.keySet.asScala.toSet == Set("pipeline", "message"), o)
        assert(o.get("pipeline").getAsString == "cf_pg")
        assert(
            o.get("message").getAsString ==
                "Could not remove postgres table datris.public.cf_pg: the store could not be reached. Its pipeline configuration was deleted; remove it in the store.",
            o
        )
        // The cascade reaches the same delete (deletePipelineInternal ->
        // deletePipelineOutcome), whose warnings carry the failures.
        assert(source("ai/datris/api/CatalogAPIController.scala").contains("pipelineController.deletePipelineInternal("))
        assert(method("deletePipelineInternal").contains("deletePipelineOutcome("))
        assert(method("deletePipelineOutcome").contains("cleanupDestinationData("))
        assert(controller.split(java.util.regex.Pattern.quote("cleanupDestinationData(config"), -1).length - 1 == 1, "cleanupDestinationData has one caller")
    }

    // ------------------------------------------------------------ bullet 7

    test("a config row delete that fails after data steps ran reports that the data was already removed") {
        val sentence = "destination data was already removed; the configuration could not be deleted. Run the delete again."
        var calls = 0
        PipelineAPIController.deleteConfigRow(dataRemoved = true, () => calls += 1)
        assert(calls == 1)

        val storeDown = new RuntimeException("Timed out after 30000 ms while waiting to connect to mongo-host.internal:27017")
        val e = intercept[Exception](PipelineAPIController.deleteConfigRow(dataRemoved = true, () => throw storeDown))
        assert(e.getMessage.contains(sentence), e.getMessage)
        // Still a 500: the handler renders it with QueryAPIController.errorBody.
        val body = JsonParser.parseString(QueryAPIController.errorBody(e)).getAsJsonObject.get("error").getAsString
        assert(body.contains(sentence), body)

        // No data touched (keep-data delete): the original error, no claim.
        val e2 = intercept[Exception](PipelineAPIController.deleteConfigRow(dataRemoved = false, () => throw storeDown))
        assert(!e2.getMessage.contains("already removed"), e2.getMessage)
        // Review finding 5: no store text in either 500.
        Seq(e, e2).foreach(x => assert(!x.getMessage.contains("mongo-host") && !x.getMessage.contains("27017"), x.getMessage))
        assert(e2.getMessage == "The pipeline configuration could not be deleted. Run the delete again.", e2.getMessage)

        // Wiring: the delete removes its config row through this seam.
        val outcome = method("deletePipelineOutcome")
        assert(outcome.contains("PipelineAPIController.deleteConfigRow("), "deletePipelineOutcome does not delete the config row through deleteConfigRow")
        assert(
            !outcome.contains("\n            NoSQLDbUtil.deleteItemJSON(DatrisEnvironment.current.pipelineTableName"),
            "the bare config row delete is still there"
        )
    }

    // ------------------------------------------------------------ Decision 3 wiring

    test("Decision 3 wiring: SQL Server, Snowflake and Databricks drops pass the bound; Postgres, pgvector and MongoDB use the bounded connections") {
        val pass = "timeoutSeconds = Some(DestinationDrop.TimeoutSeconds)"
        Seq("SnowflakeConnectionUtil.withConnection(", "SqlServerConnectionUtil.withConnection(", "DatabricksConnectionUtil.withConnection(").foreach { call =>
            val at = controller.indexOf(call)
            assert(at >= 0, call)
            val drops = controller.split(java.util.regex.Pattern.quote(call), -1).drop(1).filter(_.contains("DROP TABLE IF EXISTS"))
            assert(drops.nonEmpty, s"$call no longer drops")
            val withBound = controller.split(java.util.regex.Pattern.quote(call), -1).drop(1).exists(after => argument(after, 0).contains(pass))
            assert(withBound, s"$call: the drop does not pass $pass")
        }
        assert(controller.contains("DestinationDrop.jdbcProperties("), "the Postgres drop connection is not bounded")
        assert(controller.contains("DestinationDrop.pgvectorJdbc("), "the pgvector drop does not use the bounded connection target")
        assert(controller.contains("DestinationDrop.execute("), "the JDBC drop statements do not get a query timeout")
        assert(controller.contains("DestinationDrop.mongoSettings("), "the MongoDB drop client is not bounded")
    }

    // ------------------------------------------------------------ review follow-ups

    test("review 5: the config row's 'already removed' sentence needs a step that removed data") {
        val outcome = method("deletePipelineOutcome")
        assert(outcome.contains("dataRemoved = cleanup.steps.exists(_._2 == DestinationDrop.Removed)"), "dataRemoved is not taken from the removed steps")
        assert(!outcome.contains("dataRemoved = deleteDataBool"), "dataRemoved still claims removal for every data delete")
    }

    test("review 6: stored history, CodeGen scripts and Unity Catalog state failures use the platform wording") {
        Seq(PipelineAPIController.HistoryLabel, PipelineAPIController.ScriptsLabel, PipelineAPIController.UnityCatalogStateLabel).foreach { l =>
            val w = PipelineAPIController.platformCleanupWarning(l, RE, configDeleted = true)
            assert(
                w == "Could not remove " + l + ": the store reported an error. It is left in the platform storage and is removed the next time a pipeline of this name is deleted. " +
                    "A new pipeline created with this name will see it until then.",
                w
            )
        }
        val outcome = method("deletePipelineOutcome")
        assert(outcome.contains("PipelineAPIController.platformCleanupWarning(label, reason, configDeleted = true)"))
    }

    test("review 8: an empty object-store prefixKey is not deleted, not reported and not data left") {
        Seq("", "/", null).foreach { prefix =>
            val c = PipelineConfig(name = "cf_os_empty", destination = Destination(objectStore = ObjectStore(prefixKey = prefix)))
            val done = new PipelineAPIController().cleanupDestinationData(c, Set.empty, Set.empty, None)
            assert(done.steps.map(_._2) == Seq(DestinationDrop.NotSent), done.steps)
            assert(PipelineAPIController.cleanupWarnings(done, configDeleted = true).isEmpty)
            assert(!done.dataLeft && !done.objectStoreDataDeleted && done.keptPrefixes.isEmpty)
        }
    }

    test("review 3 (round 3): the SQL Server and Databricks drops pass the socket and warehouse-start bounds") {
        val cleanupSrc = method("cleanupDestinationData")
        assert(cleanupSrc.contains("socketTimeoutSeconds = Some(DestinationDrop.SocketTimeoutSeconds)"), "SQL Server drop socket bound")
        assert(cleanupSrc.contains("warehouseStartSeconds = Some(DestinationDrop.DatabricksWarehouseStartSeconds)"), "Databricks warehouse-start bound")
    }
}
