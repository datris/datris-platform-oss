package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.audit.{AuditEntry, AuditLog}
import ai.datris.auth.ScopeDenialFixtures
import ai.datris.model._
import ai.datris.util.InMemoryUserDb.grants
import ai.datris.util.{APIKeyValidator, CodeStore, ScriptRef, StoredScript, TapCodeStore, TapScriptOwnership}
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.google.gson.JsonParser
import jakarta.servlet.http.HttpServletRequest
import org.scalatest.BeforeAndAfterEach
import org.scalatest.funsuite.AnyFunSuite
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity

import scala.collection.JavaConverters._
import scala.collection.mutable

/** Story: A tap's script upload and delete act only on that tap's own script
  * file (plans/stories/tap-script-store-writes-and-deletes-in-scope.md),
  * at the script upload, the storage move and the script step of a tap
  * delete. Owner decisions: D1 earlier prefixes on the repository
  * configuration; D2 rule S2 enforced in this release; S4 and S5 reported
  * only (one WARN line and one `tap/reference-would-be-refused` audit row
  * through TapOwnerScopeReport, the upload going ahead as before).
  *
  * Required behaviour:
  *  - S1: a tap delete removes the tap's script file only when it is the
  *    tap's own file (TapScriptOwnership) and no other stored tap's current
  *    reference is that file; otherwise the file stays and the delete gives
  *    exactly one warning, [[TapScriptOwnership.KeptWarning]]. A tap listing
  *    that cannot be read keeps the file, with the warning.
  *  - S2: an upload or storage move that would write, with no repository
  *    reference of this tap to write over, the repository file another stored
  *    tap runs from writes nothing and is refused: 409 for an unrestricted
  *    caller; for a limited caller the answer the script reference rule gives
  *    (a restricted session: the tap's 404; a key: the shared 403).
  *  - S4: an upload for a stored repository tap whose stored path is not its
  *    own file goes ahead as before (written at its stored path), reported.
  *  - S5: an upload under a name with no stored tap by an API key holding no
  *    tap create goes ahead as before, reported.
  *
  * Names the implementation must provide:
  * {{{
  * object TapAPIController {
  *   /** The store reads and writes of the script upload and the storage move. */
  *   private[datris] case class TapScriptUploadIO(
  *     readTap: String => TapConfig,
  *     allTaps: () => Seq[TapConfig],
  *     repoConfig: () => Option[CodeRepoConfig],               // the enabled repository configuration
  *     storeFor: (String, TapConfig) => TapCodeStore,          // (requested storage or null, stored tap or null) => the store written,
  *                                                             // resolved as the routes do today
  *     write: TapConfig => Unit,
  *     writeVersioned: (TapConfig, String, String) => TapConfig,
  *     loadAll: () => (Seq[TapConfig], Seq[PipelineConfig]),
  *     readPipeline: String => PipelineConfig
  *   )
  *   /** The script step of a tap delete: the warnings it adds (S1). */
  *   private[datris] def deleteTapScript(existing: TapConfig, store: TapCodeStore, lookups: TapScriptOwnership.Lookups): Seq[String]
  * }
  * class TapAPIController {
  *   /** The script upload after key validation; storeScript delegates to it with the live store reads. */
  *   private[datris] def storeScriptWith(body: java.util.Map[String, String], request: HttpServletRequest,
  *                                       io: TapAPIController.TapScriptUploadIO): ResponseEntity[String]
  * }
  * class CodeRepoAPIController {
  *   /** The storage move after key validation; migrateStorage delegates to it with the live store reads. */
  *   private[datris] def migrateStorageWith(body: java.util.Map[String, String], request: HttpServletRequest,
  *                                          io: TapAPIController.TapScriptUploadIO): ResponseEntity[String]
  * }
  * }}}
  * `deleteTapInternal` must use `deleteTapScript` for its script step (and
  * the catalog cascade pass one listing read to every call).
  */
class TapScriptStoreOwnFileSpec extends AnyFunSuite with BeforeAndAfterEach {

    private val env: DatrisEnvironment = ScopeDenialFixtures.auditEnv()
    private var savedValues: DatrisEnvironment = _
    private val audit = mutable.ArrayBuffer[AuditEntry]()

    override def beforeEach(): Unit = {
        savedValues = DatrisEnvironment.values
        DatrisEnvironment.init(env)
        ScopeDenialFixtures.install(env)
        audit.clear()
        AuditLog.sinkOverride = (e: AuditEntry) => audit.synchronized { audit += e; () }
    }

    override def afterEach(): Unit = {
        AuditLog.sinkOverride = null
        ScopeDenialFixtures.install(savedValues)
    }

    // ---------------------------------------------------------------- identities

    private def key(label: String, caps: String*): ResolvedKey = ScopeDenialFixtures.key(label, caps: _*)
    private def session(u: User): ResolvedKey = APIKeyValidator.resolveFromSession(u)

    private val admin = session(User("ada", "h", User.RoleAdmin, null, null, null))
    private val fullKey = key("full-agent", "*:*")
    private val unrestricted = Seq(admin, fullKey)

    private val salesKey = key(
        "sales-agent",
        "pipeline:read:catalog=Sales",
        "pipeline:run:catalog=Sales",
        "tap:read:catalog=Sales",
        "tap:create:catalog=Sales",
        "tap:update:catalog=Sales",
        "tap:run:catalog=Sales"
    )
    private val builder = key("builder", "pipeline:read", "tap:read:owner=self", "tap:create", "tap:update:owner=self", "tap:run:owner=self")
    private val rita = session(User("rita", "h", User.RoleEditor, null, null, null, restricted = true, catalogGrants = grants("Sales" -> "edit")))
    private val limitedKeys = Seq(salesKey, builder)

    /** A key that may update taps but holds no tap create. */
    private val updater = key("updater-agent", "tap:read", "tap:update")

    private def req(rk: ResolvedKey, path: String): HttpServletRequest = ScopeDenialFixtures.request(rk, "POST", path)._1

    // ---------------------------------------------------------------- stores

    private val Prefix = "datris-taps/"

    private def repoCfg(earlier: Seq[String] = Nil): CodeRepoConfig =
        CodeRepoConfig(
            repo = "o/r",
            authSecretName = "s",
            enabled = true,
            pathPrefix = Prefix,
            earlierPathPrefixes = new java.util.ArrayList[String](earlier.asJava)
        )

    /** A script store over a map of files; every write and delete is recorded. */
    private class FakeStore(val storage: String) extends CodeStore {
        val files: mutable.Map[String, String] = mutable.LinkedHashMap.empty
        val writes: mutable.Buffer[(String, String)] = mutable.Buffer.empty
        val deletes: mutable.Buffer[String] = mutable.Buffer.empty
        private def pathOf(ref: ScriptRef): String = if (ref == null) null else if (storage == "github") ref.scriptRepoPath else ref.scriptPath
        override def readScript(ref: ScriptRef): Option[String] = Option(pathOf(ref)).flatMap(files.get)
        override def storeScript(name: String, script: String, prior: ScriptRef, actor: String): StoredScript =
            if (storage == "github") {
                val path =
                    if (prior != null && prior.storage == "github" && prior.scriptRepoPath != null && prior.scriptRepoPath.nonEmpty) prior.scriptRepoPath
                    else Prefix + name + ".py"
                files(path) = script
                writes += (name -> path)
                StoredScript("github", scriptRepoPath = path, scriptCommitSha = "c" + writes.size + "abcdef")
            } else {
                val key = "tap-scripts/" + name + "_" + "%08x".format(writes.size + 1) + ".py"
                files(key) = script
                writes += (name -> key)
                StoredScript("minio", scriptPath = key)
            }
        override def deleteScript(ref: ScriptRef): Unit = Option(pathOf(ref)).foreach { p =>
            deletes += p
            files.remove(p)
        }
        override def scriptExists(ref: ScriptRef): Boolean = readScript(ref).isDefined
    }

    private def adapter(s: CodeStore): TapCodeStore = new TapCodeStore { protected val underlying: CodeStore = s }

    /** In-memory stores behind the upload and the storage move. */
    private class World(initial: Seq[TapConfig], cfg: CodeRepoConfig = repoCfg()) {
        val taps: mutable.Map[String, TapConfig] = mutable.LinkedHashMap(initial.map(t => t.name -> t): _*)
        val repo = new FakeStore("github")
        val builtIn = new FakeStore("minio")
        val tapWrites: mutable.Buffer[TapConfig] = mutable.Buffer.empty
        initial.foreach { t =>
            Option(t.scriptRepoPath).foreach(p => repo.files(p) = "def fetch():\n    return []")
            Option(t.scriptPath).foreach(p => builtIn.files(p) = "def fetch():\n    return []")
        }
        val io: TapAPIController.TapScriptUploadIO = TapAPIController.TapScriptUploadIO(
            readTap = n => taps.get(n).orNull,
            allTaps = () => taps.values.toSeq,
            repoConfig = () => Some(cfg),
            storeFor = (storage, existing) =>
                storage match {
                    case "github" => adapter(repo)
                    case "minio" | "builtin" => adapter(builtIn)
                    case _ => if (existing != null && existing.scriptStorage != "github") adapter(builtIn) else adapter(repo)
                },
            write = t => { tapWrites += t; taps(t.name) = t },
            writeVersioned = (t, _, _) => { tapWrites += t; taps(t.name) = t; t },
            loadAll = () => (taps.values.toSeq, Nil),
            readPipeline = _ => null
        )
        def nothingWritten(): Unit = {
            assert(repo.writes.isEmpty && builtIn.writes.isEmpty, "no script written: " + repo.writes + " " + builtIn.writes)
            assert(tapWrites.isEmpty, "no tap written: " + tapWrites)
        }
    }

    private def repoTap(name: String, path: String, catalog: String = "Sales", owner: String = "session:ada"): TapConfig =
        TapConfig(
            name = name,
            description = "d",
            targetPipeline = "sales-orders",
            catalog = catalog,
            createdByKeyLabel = owner,
            scriptStorage = "github",
            scriptRepoPath = path,
            scriptCommitSha = "abc1234"
        )

    private def builtInTap(name: String, key: String, catalog: String = "Sales"): TapConfig =
        TapConfig(name = name, description = "d", targetPipeline = "sales-orders", catalog = catalog, createdByKeyLabel = "session:ada", scriptPath = key)

    private def upload(rk: ResolvedKey, w: World, name: String, storage: String = null): ResponseEntity[String] = {
        val body = new java.util.HashMap[String, String]()
        body.put("tapName", name)
        body.put("script", "def fetch():\n    return [1]")
        if (storage != null) body.put("storage", storage)
        new TapAPIController().storeScriptWith(body, req(rk, "/api/v1/tap/script"), w.io)
    }

    private def move(rk: ResolvedKey, w: World, name: String, target: String): ResponseEntity[String] = {
        val body = new java.util.HashMap[String, String]()
        body.put("tapName", name)
        body.put("target", target)
        new CodeRepoAPIController().migrateStorageWith(body, req(rk, "/api/v1/tap/migrate-storage"), w.io)
    }

    private def uniq(prefix: String): String = prefix + "-" + System.nanoTime()

    /** The answer the script reference rule gives `rk` for a reference it may not use, naming `name`. */
    private def ruleThreeAnswer(rk: ResolvedKey, name: String): ResponseEntity[String] =
        TapAPIController
            .referenceRefusal(
                req(rk, "/api/v1/tap"),
                TapReferenceRules.Save,
                TapConfig(name = name, description = "d", targetPipeline = "sales-orders", catalog = "Sales", scriptPath = "uploads/x.csv"),
                null,
                TapReferenceRules.Lookups(() => Nil, _ => None, () => Prefix)
            )
            .getOrElse(fail("no rule 3 answer for " + rk.label))

    private def withWarns[T](f: => T): (T, List[String]) = {
        val rootLogger = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).asInstanceOf[ch.qos.logback.classic.Logger]
        val appender = new ListAppender[ILoggingEvent]()
        appender.start()
        rootLogger.addAppender(appender)
        try {
            val r = f
            (r, appender.list.asScala.toList.filter(_.getLevel == Level.WARN).map(_.getFormattedMessage))
        } finally rootLogger.detachAppender(appender)
    }

    private def reportRows(name: String): List[AuditEntry] =
        audit.synchronized(audit.toList).filter(e => e.resourceName.contains(name) && e.action == TapOwnerScopeReportAction)

    private val TapOwnerScopeReportAction = ai.datris.util.TapOwnerScopeReport.ReferenceWouldBeRefusedAction

    private def assertReportedOnce(name: String, warns: List[String]): Unit = {
        assert(warns.count(_.contains(name)) == 1, warns.mkString("\n"))
        val rows = reportRows(name)
        assert(rows.size == 1, rows.toString)
        assert(rows.head.category == "tap" && rows.head.outcome == "warning", rows.head.toString)
        assert(!rows.head.metadata.getOrElse(fail("no metadata")).get("enforced").getAsBoolean, rows.head.toString)
    }

    private def assertNotReported(name: String, warns: List[String]): Unit = {
        assert(!warns.exists(_.contains(name)), warns.mkString("\n"))
        assert(reportRows(name).isEmpty, reportRows(name).toString)
    }

    private def status(r: ResponseEntity[String]): Int = r.getStatusCode.value

    // ================================================================ S2: the upload never replaces a file another tap runs from

    test("S2: an upload under a new name whose repository file another stored tap runs from writes nothing and is a 409 for an unrestricted caller") {
        for (rk <- unrestricted; spelled <- Seq(identity[String] _, (p: String) => p.replaceFirst("/", "//"))) {
            val name = uniq("new-name")
            val w = new World(Seq(repoTap(uniq("finance-hand"), spelled(Prefix + name + ".py"), catalog = "Finance")))
            val r = upload(rk, w, name)
            assert(status(r) == 409, rk.label + ": " + r)
            assert(JsonParser.parseString(r.getBody).getAsJsonObject.has("error"), r.getBody)
            w.nothingWritten()
        }
    }

    test("S2: for a limited caller the refusal is the answer the script reference rule gives, naming nothing of the other tap") {
        for (rk <- limitedKeys) {
            val name = uniq("new-name")
            val other = uniq("finance-hand")
            val w = new World(Seq(repoTap(other, Prefix + name + ".py", catalog = "Finance")))
            val r = upload(rk, w, name)
            val expected = ruleThreeAnswer(rk, name)
            assert(status(r) == status(expected) && r.getBody == expected.getBody, rk.label + ": " + r)
            assert(!r.getBody.contains(other) && !r.getBody.contains("Finance"), r.getBody)
            w.nothingWritten()
        }
        // a restricted session naming a tap that does not exist is answered 404, as before
        val name = uniq("new-name")
        val w = new World(Seq(repoTap(uniq("finance-hand"), Prefix + name + ".py", catalog = "Finance")))
        val r = upload(rita, w, name)
        assert(status(r) == 404, r.toString)
        w.nothingWritten()
    }

    test(
        "S2: moving a stored tap's script into the repository onto a file another tap runs from writes nothing and is refused, by upload and by storage move"
    ) {
        for (rk <- unrestricted) {
            val name = uniq("mover")
            val taps = Seq(builtInTap(name, "tap-scripts/" + name + "_1a2b3c4d.py"), repoTap(uniq("finance-hand"), Prefix + name + ".py", catalog = "Finance"))
            val byUpload = new World(taps)
            assert(status(upload(rk, byUpload, name, storage = "github")) == 409, rk.label)
            byUpload.nothingWritten()
            val byMove = new World(taps)
            assert(status(move(rk, byMove, name, "github")) == 409, rk.label)
            byMove.nothingWritten()
        }
    }

    test("unchanged: an upload for a new name with a free path, and a repeat upload before the first save, write <prefix><name>.py as before") {
        for (rk <- unrestricted ++ limitedKeys) {
            val name = uniq("fresh")
            val w = new World(Seq(repoTap(uniq("other"), Prefix + "other.py")))
            val (results, warns) = withWarns(Seq(upload(rk, w, name), upload(rk, w, name)))
            for (r <- results) {
                assert(status(r) == 200, rk.label + ": " + r)
                assert(JsonParser.parseString(r.getBody).getAsJsonObject.get("scriptRepoPath").getAsString == Prefix + name + ".py", r.getBody)
            }
            assert(w.repo.writes.toList == List(name -> (Prefix + name + ".py"), name -> (Prefix + name + ".py")), w.repo.writes.toString)
            assertNotReported(name, warns)
        }
    }

    test("unchanged: a stored repository tap uploads to its own file even while another tap also runs from it") {
        for (rk <- unrestricted) {
            val name = uniq("owner")
            val own = Prefix + name + ".py"
            val w = new World(Seq(repoTap(name, own), repoTap(uniq("copy"), own)))
            val r = upload(rk, w, name)
            assert(status(r) == 200, rk.label + ": " + r)
            assert(w.repo.writes.toList == List(name -> own), w.repo.writes.toString)
        }
    }

    test("unchanged: a storage move into the repository onto a free path writes <prefix><name>.py and repoints the tap") {
        val name = uniq("mover-free")
        val w = new World(Seq(builtInTap(name, "tap-scripts/" + name + "_1a2b3c4d.py")))
        val r = move(admin, w, name, "github")
        assert(status(r) == 200, r.toString)
        assert(w.repo.writes.toList == List(name -> (Prefix + name + ".py")), w.repo.writes.toString)
        assert(w.taps(name).scriptStorage == "github" && w.taps(name).scriptRepoPath == Prefix + name + ".py", w.taps(name).toString)
    }

    // ================================================================ S4 and S5: reported, unchanged

    test("S4: an upload for a stored repository tap whose stored path is not its own file goes ahead at that path, with one log line and one audit row") {
        val name = uniq("hand-placed")
        val path = Prefix + "lib/shared.py"
        val w = new World(Seq(repoTap(name, path)))
        val (r, warns) = withWarns(upload(admin, w, name))
        assert(status(r) == 200, r.toString)
        assert(w.repo.writes.toList == List(name -> path), w.repo.writes.toString)
        assertReportedOnce(name, warns)
    }

    test("S4: a tap at its own path, under the current or a recorded earlier prefix, is not reported") {
        val current = uniq("own-current")
        val earlier = uniq("own-earlier")
        val w = new World(Seq(repoTap(current, Prefix + current + ".py"), repoTap(earlier, "old-taps/" + earlier + ".py")), repoCfg(Seq("old-taps/")))
        val (rs, warns) = withWarns(Seq(upload(admin, w, current), upload(admin, w, earlier)))
        assert(rs.forall(r => status(r) == 200), rs.toString)
        assert(w.repo.writes.toList == List(current -> (Prefix + current + ".py"), earlier -> ("old-taps/" + earlier + ".py")), w.repo.writes.toString)
        assertNotReported(current, warns)
        assertNotReported(earlier, warns)
    }

    test("S5: an upload under a name with no stored tap by a key holding no tap create goes ahead, with one log line and one audit row") {
        val name = uniq("unsaved")
        val w = new World(Nil)
        val (r, warns) = withWarns(upload(updater, w, name))
        assert(status(r) == 200, r.toString)
        assert(w.repo.writes.toList == List(name -> (Prefix + name + ".py")), w.repo.writes.toString)
        assertReportedOnce(name, warns)
    }

    test("S5: a key or user that may create taps is not reported for an upload under a new name") {
        for (rk <- Seq(admin, fullKey, salesKey)) {
            val name = uniq("creatable")
            val (r, warns) = withWarns(upload(rk, new World(Nil), name))
            assert(status(r) == 200, rk.label + ": " + r)
            assertNotReported(name, warns)
        }
    }

    // ================================================================ S1: the script step of a tap delete

    private def lookups(w: World, cfg: CodeRepoConfig = repoCfg()): TapScriptOwnership.Lookups =
        TapScriptOwnership.Lookups(() => w.taps.values.toSeq, () => Some(cfg))

    test("a tap delete removes its own script file, in either store, with no warning") {
        val b = uniq("own-builtin")
        val r = uniq("own-repo")
        val w = new World(Seq(builtInTap(b, "tap-scripts/" + b + "_1a2b3c4d.py"), repoTap(r, Prefix + r + ".py")))
        assert(TapAPIController.deleteTapScript(w.taps(b), adapter(w.builtIn), lookups(w)).isEmpty)
        assert(TapAPIController.deleteTapScript(w.taps(r), adapter(w.repo), lookups(w)).isEmpty)
        assert(w.builtIn.deletes.toList == List("tap-scripts/" + b + "_1a2b3c4d.py"), w.builtIn.deletes.toString)
        assert(w.repo.deletes.toList == List(Prefix + r + ".py"), w.repo.deletes.toString)
    }

    test(
        "a tap whose reference names another tap's script, an older script of another tap, or any other file is deleted and the file stays, with one warning"
    ) {
        val other = uniq("other")
        val otherKey = "tap-scripts/" + other + "_5e6f7a8b.py"
        val otherRepo = Prefix + other + ".py"
        val builtInRefs =
            Seq(otherKey, "tap-scripts/" + other + "_99999999.py", "uploads/sales/orders.csv", "pipeline-scripts/sales-orders/transformation_1a2b3c4d.py")
        val repoRefs = Seq(otherRepo, Prefix + "pipelines/sales-orders/transformation.py", "README.md", "old-taps/x.py")
        for (p <- builtInRefs) {
            val name = uniq("hand-ref")
            val w = new World(Seq(builtInTap(other, otherKey), repoTap(other + "-r", otherRepo), builtInTap(name, p)))
            w.builtIn.files(p) = "keep me"
            assert(TapAPIController.deleteTapScript(w.taps(name), adapter(w.builtIn), lookups(w)) == Seq(TapScriptOwnership.KeptWarning), p)
            assert(w.builtIn.deletes.isEmpty && w.builtIn.files.contains(p), p)
        }
        for (p <- repoRefs) {
            val name = uniq("hand-ref")
            val w = new World(Seq(builtInTap(other, otherKey), repoTap(other + "-r", otherRepo), repoTap(name, p)))
            w.repo.files(p) = "keep me"
            assert(TapAPIController.deleteTapScript(w.taps(name), adapter(w.repo), lookups(w)) == Seq(TapScriptOwnership.KeptWarning), p)
            assert(w.repo.deletes.isEmpty && w.repo.files.contains(p), p)
        }
    }

    test("a tap whose own script another stored tap still uses is deleted and the file stays; the other tap still reads it") {
        val name = uniq("original")
        val copy = uniq("copy")
        val key = "tap-scripts/" + name + "_1a2b3c4d.py"
        val w = new World(Seq(builtInTap(name, key), builtInTap(copy, key)))
        assert(TapAPIController.deleteTapScript(w.taps(name), adapter(w.builtIn), lookups(w)) == Seq(TapScriptOwnership.KeptWarning))
        assert(w.builtIn.deletes.isEmpty)
        assert(adapter(w.builtIn).readScript(w.taps(copy)).isDefined, "the other tap still reads its script")
        val own = Prefix + name + ".py"
        val rw = new World(Seq(repoTap(name, own), repoTap(copy, own)))
        assert(TapAPIController.deleteTapScript(rw.taps(name), adapter(rw.repo), lookups(rw)) == Seq(TapScriptOwnership.KeptWarning))
        assert(rw.repo.deletes.isEmpty && adapter(rw.repo).readScript(rw.taps(copy)).isDefined)
    }

    test("when the tap listing cannot be read the script file is kept, with the warning") {
        val name = uniq("unlisted")
        val w = new World(Seq(builtInTap(name, "tap-scripts/" + name + "_1a2b3c4d.py"), repoTap(name + "-r", Prefix + name + "-r.py")))
        val failing = TapScriptOwnership.Lookups(() => throw new IllegalStateException("mongo down"), () => Some(repoCfg()))
        assert(TapAPIController.deleteTapScript(w.taps(name), adapter(w.builtIn), failing) == Seq(TapScriptOwnership.KeptWarning))
        assert(TapAPIController.deleteTapScript(w.taps(name + "-r"), adapter(w.repo), failing) == Seq(TapScriptOwnership.KeptWarning))
        assert(w.builtIn.deletes.isEmpty && w.repo.deletes.isEmpty)
    }

    test("a tap stored under a recorded earlier prefix deletes its own file exactly as one under the current prefix") {
        val name = uniq("earlier")
        val w = new World(Seq(repoTap(name, "old-taps/" + name + ".py")), repoCfg(Seq("old-taps/")))
        assert(TapAPIController.deleteTapScript(w.taps(name), adapter(w.repo), lookups(w, repoCfg(Seq("old-taps/")))).isEmpty)
        assert(w.repo.deletes.toList == List("old-taps/" + name + ".py"))
    }
}
