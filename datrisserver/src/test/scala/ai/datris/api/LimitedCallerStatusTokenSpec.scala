package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.audit.{AuditEntry, AuditLog}
import ai.datris.auth.ScopeDenialFixtures
import ai.datris.model._
import ai.datris.util.{APIKeyValidator, InMemoryUserDb, ScratchResultException}
import ai.datris.util.InMemoryUserDb.grants
import com.google.gson.Gson
import jakarta.servlet.http.HttpServletRequest
import org.scalatest.BeforeAndAfterEach
import org.scalatest.funsuite.AnyFunSuite
import org.springframework.http.{HttpHeaders, HttpStatus, ResponseEntity}

import scala.collection.JavaConverters._

/** Story: Four small read leaks for callers limited to a catalog
  * (plans/stories/limited-caller-read-leaks.md), Acceptance bullet 2 (gap
  * 10a, owner decision D2 (a)): for a caller limited to a catalog or to what
  * it created, a run token of a pipeline it cannot read is answered exactly
  * like a token that does not exist (200 with the empty body on
  * `GET /pipeline/status`, the unknown-token 404 on `GET /pipeline/result`).
  *
  * `GET /pipeline/status` maps `PipelineStatusAPIController.restrictedView`
  * (`restrictedViewWith` on the loaded stores) to its response: Left is sent
  * as is, Right(x) as 200 with `Gson.toJson(x)` and no extra headers. These
  * tests apply the same mapping ([[route]]) to `restrictedViewWith`, on the
  * payload each token variant reads: a list of events (`pipelinetoken`,
  * `publishertoken`) or a rollup response (`withrollup=true`); the payload
  * for a token with no rows is what `PipelineStatusUtil` builds from zero
  * rows (an empty list; an empty rollup: allDone false, status processing,
  * no jobs, no events).
  *
  * `GET /pipeline/result` decides on the token's jobs before any of
  * `ScratchReader.resolve`'s refusals, through one new seam:
  * {{{
  * object PipelineStatusAPIController {
  *   // The job GET /pipeline/result reads: ScratchReader.resolve's rules
  *   // (publishertoken wins; none -> 404 "No pipeline run found for ...";
  *   // publishertoken with no scratch job -> 404 "Only Live Read ..."; several
  *   // scratch jobs -> 400) applied to `jobs` (the token's rollup jobs) after,
  *   // for a limited caller, removing the jobs of pipelines whose records it
  *   // may not see (resultHidden). `configs` is called only for a limited
  *   // caller. Throws ScratchResultException.
  *   def resultJob(request: HttpServletRequest, pipelinetoken: String, publishertoken: String,
  *                 jobs: List[PipelineJobRollup], configs: () => Seq[PipelineConfig]): PipelineJobRollup
  * }
  * }}} */
class LimitedCallerStatusTokenSpec extends AnyFunSuite with BeforeAndAfterEach {

    private def req(rk: ResolvedKey): HttpServletRequest = ScopeDenialFixtures.request(rk, "GET", "/api/v1/pipeline/status")._1
    private def key(label: String, caps: String*): ResolvedKey = ScopeDenialFixtures.key(label, caps: _*)

    private val bob: ResolvedKey = APIKeyValidator.resolveFromSession(
        User("bob", "h", User.RoleEditor, null, null, null, restricted = true, catalogGrants = grants("Sales" -> "view"))
    )
    private val salesKey: ResolvedKey = key("sales", "pipeline:read:catalog=Sales", "tap:read:catalog=Sales", "job:read")
    private val ownKey: ResolvedKey = key("own", "pipeline:read:owner=self", "tap:read:owner=self", "job:read")
    private val limited: Seq[(String, ResolvedKey)] = Seq("restricted session" -> bob, "catalog= key" -> salesKey, "owner=self key" -> ownKey)

    private def unchanged: Seq[(String, HttpServletRequest)] = Seq(
        "admin session" -> req(APIKeyValidator.resolveFromSession(User("admin", "h", User.RoleAdmin, null, null, null))),
        "unrestricted editor session" -> req(APIKeyValidator.resolveFromSession(User("ed", "h", User.RoleEditor, null, null, null))),
        "*:* key" -> req(key("full", "*:*")),
        "unscoped job key" -> req(key("monitor", "job:read", "pipeline:read")),
        "legacy key" -> req(ResolvedKey(None, "legacy", Seq(Capability.FullAccess), isLegacyFullAccess = true)),
        "keys off (no identity)" -> ScopeDenialFixtures.request(null, "GET", "/api/v1/pipeline/status")._1
    )

    private val orders = PipelineConfig(name = "orders", catalog = "Sales", createdByKeyLabel = "own")
    private val payroll = PipelineConfig(name = "payroll", catalog = "HR", createdByKeyLabel = "someone")
    private val pipelines: Seq[PipelineConfig] = Seq(orders, payroll)
    private val taps: Seq[TapConfig] = Nil

    private val failPipelines: () => Seq[PipelineConfig] = () => fail("the pipeline store must not be read for this caller")
    private val failTaps: () => Seq[TapConfig] = () => fail("the tap store must not be read for this caller")

    private val gson = new Gson

    // ------------------------------------------------------------ payloads

    private def ev(pipeline: String, token: String, pub: String, process: String, state: String, code: String, desc: String, epoch: Long): PipelineStatus =
        PipelineStatus(0, "2026-11-18 10:00:0" + (epoch % 10) + " UTC", pipeline, process, pub, token, pipeline + ".csv", state, code, desc, epoch)

    private def runEvents(pipeline: String, token: String, pub: String, ok: Boolean, at: Long): List[PipelineStatus] = List(
        ev(pipeline, token, pub, "JobRunner", "begin", "info", "Process started", at),
        if (ok) ev(pipeline, token, pub, "JobRunner", "end", "info", "Process completed", at + 1)
        else ev(pipeline, token, pub, "PostgresLoader", "end", "error", "PostgresLoader failed: " + pipeline + "_tbl missing", at + 1)
    )

    private def list(es: Seq[PipelineStatus]): java.util.List[PipelineStatus] = new java.util.ArrayList[PipelineStatus](es.asJava)

    private def job(pipeline: String, token: String, status: String, scratch: Boolean = false): PipelineJobRollup =
        PipelineJobRollup(
            pipelineToken = token,
            pipeline = pipeline,
            filename = pipeline + ".csv",
            status = status,
            startedAt = "2026-11-18 10:00:00 UTC",
            lastEventAt = "2026-11-18 10:00:01 UTC",
            elapsed = "1s",
            lastError = if (status == "error") PipelineJobError("PostgresLoader", "PostgresLoader failed: " + pipeline + "_tbl missing", null) else null,
            resultUri = if (scratch) "s3://scratch/" + pipeline + "/" + token + ".jsonl" else null,
            resultRowCount = if (scratch) java.lang.Long.valueOf(3L) else null
        )

    private def rollup(jobs: Seq[PipelineJobRollup], events: Seq[PipelineStatus], allDone: Boolean, status: String): PipelineStatusResponse =
        PipelineStatusResponse(
            PipelineStatusRollup(allDone = allDone, status = status, jobs = new java.util.ArrayList[PipelineJobRollup](jobs.asJava)),
            list(events)
        )

    /** What a token with no rows reads: PipelineStatusUtil maps zero rows to these. */
    private def unknownList: java.util.List[PipelineStatus] = list(Nil)
    private def unknownRollup: PipelineStatusResponse = rollup(Nil, Nil, allDone = false, status = "processing")

    private val hiddenRun = runEvents("payroll", "tok-payroll", "pub-hr", ok = false, 100L)
    private val hiddenFanOut = hiddenRun ++ runEvents("payroll", "tok-payroll-2", "pub-hr", ok = true, 200L)
    private val visibleRun = runEvents("orders", "tok-orders", "pub-sales", ok = true, 300L)

    /** The route's mapping of restrictedView's answer to its response. */
    private def route(e: Either[ResponseEntity[String], AnyRef]): (Int, String, HttpHeaders) = {
        val r = e match {
            case Left(refused) => refused
            case Right(shown) => new ResponseEntity[String](gson.toJson(shown), HttpStatus.OK)
        }
        (r.getStatusCode.value, r.getBody, r.getHeaders)
    }

    private def shownTo(rk: ResolvedKey, data: AnyRef): (Int, String, HttpHeaders) =
        route(PipelineStatusAPIController.restrictedViewWith(req(rk), data, pipelines, taps))

    private var savedEnv: DatrisEnvironment = _

    override def beforeEach(): Unit = {
        savedEnv = DatrisEnvironment.values
        val e = InMemoryUserDb.env(useAuditLog = true)
        DatrisEnvironment.init(e)
        TenantContext.set(e)
        AuditLog.sinkOverride = (_: AuditEntry) => ()
    }

    override def afterEach(): Unit = {
        AuditLog.sinkOverride = null
        TenantContext.clear()
        DatrisEnvironment.values = savedEnv
    }

    // ================================================================== GET /pipeline/status

    test("bullet 2: pipelinetoken of a run the caller cannot read answers exactly what an unknown token answers") {
        for ((who, rk) <- limited) {
            val unknown = shownTo(rk, unknownList)
            assert(unknown._1 == 200 && unknown._2 == "[]", who + ": precondition, an unknown token is 200 []: " + unknown)
            val hidden = shownTo(rk, list(hiddenRun))
            assert(hidden == unknown, who + ": hidden " + hidden + " vs unknown " + unknown)
            assert(!hidden._2.contains("payroll"), who)
        }
    }

    test("bullet 2: publishertoken whose runs are all of a pipeline the caller cannot read answers what an unknown publisher token answers") {
        for ((who, rk) <- limited) {
            assert(shownTo(rk, list(hiddenFanOut)) == shownTo(rk, unknownList), who)
        }
    }

    test("bullet 2: withrollup=true for a hidden run (pipeline or publisher token) answers the empty rollup an unknown token answers") {
        val hiddenRollup = rollup(Seq(job("payroll", "tok-payroll", "error")), hiddenRun, allDone = true, status = "error")
        val hiddenFanRollup =
            rollup(Seq(job("payroll", "tok-payroll", "error"), job("payroll", "tok-payroll-2", "success")), hiddenFanOut, allDone = true, status = "error")
        for ((who, rk) <- limited) {
            val unknown = shownTo(rk, unknownRollup)
            assert(unknown._1 == 200, who + ": " + unknown)
            assert(unknown._2 == """{"rollup":{"allDone":false,"status":"processing","jobs":[]},"events":[]}""", who + ": " + unknown._2)
            assert(shownTo(rk, hiddenRollup) == unknown, who + ": " + shownTo(rk, hiddenRollup))
            assert(shownTo(rk, hiddenFanRollup) == unknown, who)
        }
    }

    test(
        "bullet 2 (D2 (a), mixed token): a publisher token whose runs touch a visible and a hidden pipeline shows only the visible run, and the rollup is computed from it"
    ) {
        val mixedEvents = visibleRun ++ hiddenRun
        val mixedRollup =
            rollup(Seq(job("orders", "tok-orders", "success"), job("payroll", "tok-payroll", "error")), mixedEvents, allDone = true, status = "error")
        for ((who, rk) <- limited) {
            val (status, body, _) = shownTo(rk, list(mixedEvents))
            assert(status == 200, who + ": " + body)
            assert(body == gson.toJson(list(visibleRun)), who + ": " + body)
            val (rs, rb, _) = shownTo(rk, mixedRollup)
            assert(rs == 200, who + ": " + rb)
            assert(!rb.contains("payroll") && !rb.contains("tok-payroll"), who + ": " + rb)
            val shown = gson.fromJson(rb, classOf[com.google.gson.JsonObject])
            val ro = shown.getAsJsonObject("rollup")
            assert(ro.get("status").getAsString == "success" && ro.get("allDone").getAsBoolean, who + ": the aggregate is the visible run's: " + rb)
            assert(ro.getAsJsonArray("jobs").size == 1 && shown.getAsJsonArray("events").size == visibleRun.size, who + ": " + rb)
        }
    }

    test("unchanged: every caller that is not limited gets the payload itself for any token, and no store is read") {
        val hiddenRollup = rollup(Seq(job("payroll", "tok-payroll", "error")), hiddenRun, allDone = true, status = "error")
        for ((who, r) <- unchanged; data <- Seq[AnyRef](list(hiddenRun), list(hiddenFanOut), hiddenRollup, unknownList, unknownRollup)) {
            PipelineStatusAPIController.restrictedViewFrom(r, data, failPipelines, failTaps, _ => fail("run read")) match {
                case Right(shown) => assert(shown eq data, who)
                case Left(refused) => fail(who + ": " + refused.getBody)
            }
        }
    }

    test("unchanged: a limited caller's own run reads as today, for each variant") {
        val visibleRollup = rollup(Seq(job("orders", "tok-orders", "success")), visibleRun, allDone = true, status = "success")
        for ((who, rk) <- limited; data <- Seq[AnyRef](list(visibleRun), visibleRollup)) {
            val (status, body, _) = shownTo(rk, data)
            assert(status == 200 && body == gson.toJson(data), who + ": " + body)
        }
    }

    // ================================================================== GET /pipeline/result

    private def outcome(f: => PipelineJobRollup): Either[(Int, String), PipelineJobRollup] =
        try Right(f)
        catch { case e: ScratchResultException => Left((e.status, e.getMessage)) }

    private def resultJob(r: HttpServletRequest, token: String, pub: String, jobs: List[PipelineJobRollup], configs: () => Seq[PipelineConfig]) =
        outcome(PipelineStatusAPIController.resultJob(r, token, pub, jobs, configs))

    test("bullet 2: /pipeline/result for a hidden run answers the unknown-token 404, whatever the token's jobs are") {
        val all = () => pipelines
        for ((who, rk) <- limited) {
            val r = req(rk)
            // pipelinetoken
            val unknownTok = resultJob(r, "tok-payroll", null, Nil, all)
            assert(unknownTok == Left((404, "No pipeline run found for pipelinetoken 'tok-payroll'")), who + ": " + unknownTok)
            assert(resultJob(r, "tok-payroll", null, List(job("payroll", "tok-payroll", "success", scratch = true)), all) == unknownTok, who)
            // publishertoken: one hidden scratch job, hidden jobs without a scratch result, several hidden scratch jobs
            val unknownPub = resultJob(r, null, "pub-hr", Nil, all)
            assert(unknownPub == Left((404, "No pipeline run found for publishertoken 'pub-hr'")), who + ": " + unknownPub)
            Seq(
                List(job("payroll", "tok-payroll", "success", scratch = true)),
                List(job("payroll", "tok-payroll", "success"), job("payroll", "tok-payroll-2", "success")),
                List(job("payroll", "tok-payroll", "success", scratch = true), job("payroll", "tok-payroll-2", "success", scratch = true))
            ).foreach { jobs =>
                val got = resultJob(r, null, "pub-hr", jobs, all)
                assert(got == unknownPub, who + " " + jobs.map(_.pipelineToken) + ": " + got)
            }
        }
    }

    test("bullet 2: /pipeline/result for a publisher token with one visible and one hidden scratch job reads the visible job") {
        val jobs = List(job("orders", "tok-orders", "success", scratch = true), job("payroll", "tok-payroll", "success", scratch = true))
        for ((who, rk) <- limited) {
            val got = resultJob(req(rk), null, "pub-mixed", jobs, () => pipelines)
            assert(got.right.toOption.map(_.pipelineToken).contains("tok-orders"), who + ": " + got)
        }
    }

    test("unchanged: /pipeline/result keeps today's rules for callers that are not limited (no store read) and for a limited caller's own runs") {
        val two = List(job("payroll", "tok-payroll", "success", scratch = true), job("payroll", "tok-payroll-2", "success", scratch = true))
        for ((who, r) <- unchanged) {
            assert(resultJob(r, "tok-x", null, Nil, failPipelines) == Left((404, "No pipeline run found for pipelinetoken 'tok-x'")), who)
            assert(resultJob(r, "tok-payroll", null, two.take(1), failPipelines).right.toOption.map(_.pipelineToken).contains("tok-payroll"), who)
            assert(
                resultJob(r, null, "pub-hr", List(job("payroll", "tok-payroll", "success")), failPipelines) ==
                    Left((404, "Only Live Read (scratch) pipelines have a result; publishertoken 'pub-hr' has no Live Read result")),
                who
            )
            assert(
                resultJob(r, null, "pub-hr", two, failPipelines) ==
                    Left((400, "publishertoken 'pub-hr' resolves to 2 scratch jobs; pass pipelinetoken to select one")),
                who
            )
        }
        for ((who, rk) <- limited) {
            val own = List(job("orders", "tok-orders", "success", scratch = true))
            assert(resultJob(req(rk), "tok-orders", null, own, () => pipelines).right.toOption.map(_.pipelineToken).contains("tok-orders"), who)
            assert(
                resultJob(req(rk), null, "pub-sales", List(job("orders", "tok-orders", "success")), () => pipelines) ==
                    Left((404, "Only Live Read (scratch) pipelines have a result; publishertoken 'pub-sales' has no Live Read result")),
                who
            )
        }
    }

    test("wiring: GET /pipeline/result picks its job through resultJob") {
        val src = {
            val rel = "datrisserver/src/main/scala/ai/datris/api/PipelineStatusAPIController.scala"
            val file = Seq(java.nio.file.Paths.get(rel), java.nio.file.Paths.get(rel.stripPrefix("datrisserver/")))
                .find(java.nio.file.Files.isRegularFile(_)).getOrElse(fail(rel + " not found"))
            new String(java.nio.file.Files.readAllBytes(file), java.nio.charset.StandardCharsets.UTF_8)
        }
        val i = src.indexOf("def getPipelineResult(")
        assert(i >= 0)
        val body = src.substring(i, src.indexOf("@DeleteMapping", i))
        assert(body.contains("resultJob("), body)
    }
}
