package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.api.TapReferenceRules.{Enforced, Finding, Lookups, Reported}
import ai.datris.audit.{AuditEntry, AuditLog}
import ai.datris.auth.{ScopeDenialFixtures, ScopeDenialWording}
import ai.datris.model._
import ai.datris.util.{APIKeyValidator, MinioScriptStore, ObjectStoreUtility, ScriptRef, SecretNames, TapGenerateResult}
import ai.datris.util.InMemoryUserDb.grants
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.google.gson.JsonParser
import jakarta.servlet.http.HttpServletRequest
import org.mockito.ArgumentMatchers.{anyString, eq => eqTo}
import org.mockito.Mockito.{mock, when}
import org.scalatest.BeforeAndAfterEach
import org.scalatest.funsuite.AnyFunSuite
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity

import java.util.concurrent.atomic.AtomicInteger
import scala.collection.JavaConverters._
import scala.collection.mutable

/** Story: Tap script, script reference and secret rules, first release
  * (plans/stories/tap-save-references-in-scope.md), rules 3 and 5-8.
  *
  * Required behaviour:
  *  - Rule 3 (enforced on save and test; reported only on version restore,
  *    lead ruling 2026-11-23: restoring an earlier version of one's own tap
  *    works on v1.61.0 from the UI and the MCP restore tool, so a refusal
  *    there moves to the follow-up story): for a limited caller
  *    (a restricted session, or a key for which `CallerVisibility.limitedTaps`
  *    holds), a new or changed script reference must sit in the tap script
  *    area (built-in: an object key under `tap-scripts/` exactly, no `..`
  *    step, no leading `/`, same case; code repository: under the
  *    repository's path prefix, not under its `pipelines/` folder, no `..`,
  *    no leading `/`) and must not be the current script of a tap the caller
  *    cannot see. Refused before anything is read or run, with the answer
  *    `testRefusal` gives today (restricted session: the tap's 404 naming the
  *    tap being saved; key: the shared 403 on `tap:read` through
  *    `CapabilityCheck.assertScope`), the same answer for both reasons, naming
  *    nothing of the hidden tap. An unchanged stored reference always passes.
  *    An unrestricted caller is never refused by this rule; a run or a
  *    generate is never refused by it.
  *  - Rules 5-8 (reported only): a finding marked `Reported`, one WARN line
  *    and one audit row (category `tap`, action `reference-would-be-refused`,
  *    outcome `warning`, metadata `rule` and `enforced: false`), at most once
  *    per tap and rule a UTC day; the request's own audit entry is kept
  *    (`AuditLog.RecordedAttr` is not set). The save, test, run or generate
  *    goes ahead exactly as on v1.61.0.
  *      5: a limited caller's new or changed built-in reference names neither
  *         this tap (`tap-scripts/<tap>_...`) nor a tap it may read (the
  *         current script of a readable tap passes: cloning, D5).
  *      6: any caller: a reference outside the tap script area (saved, tested,
  *         restored or run; the store would refuse to read it).
  *      7: save and generate naming a secret that the caller's tap read does
  *         not cover (the check `POST /tap/test` makes: `tap:read` on the
  *         tap's catalog with the secret's owner as owner).
  *      8: a limited caller's run of a stored tap it could not save with that
  *         secret.
  *
  * Names the implementation must provide:
  * {{{
  * object ai.datris.api.TapReferenceRules {
  *   sealed trait Mode; case object Enforced extends Mode; case object Reported extends Mode
  *   case class Finding(rule: Int, mode: Mode, reason: String)
  *   case class Lookups(
  *     allTaps: () => Seq[TapConfig],                       // read only for a limited caller with a new or changed reference
  *     secretOwner: String => Option[Option[String]],       // outer None: no such secret; inner: its createdByKeyLabel
  *     repoPathPrefix: () => String                         // the enabled code repository's path prefix, "" for none
  *   )
  *   val Save = "save"; val Test = "test"; val Restore = "restore"; val Run = "run"; val Generate = "generate"
  *   def judge(caller: Option[ResolvedKey], site: String, candidate: TapConfig, stored: TapConfig, lookups: Lookups): Seq[Finding]  // pure
  *   def reviewWouldBeRefused(caller: Option[ResolvedKey]): Boolean   // rule 4: an identity holding no tap:update
  * }
  * object TapAPIController {
  *   // judge at `site`; Some(refusal) for the first Enforced finding; each Reported one logged and audited. Never throws for a report.
  *   def referenceRefusal(request: HttpServletRequest, site: String, candidate: TapConfig, stored: TapConfig, lookups: TapReferenceRules.Lookups): Option[ResponseEntity[String]]
  *   // TapSaveIO gains, with defaults so every existing spec compiles unedited:
  *   //   secretOwner: String => Option[Option[String]] = liveSecretOwner
  *   //   repoPathPrefix: () => String = <the enabled CodeRepoConfig's normalized pathPrefix, "" for none>
  * }
  * }}}
  * `saveTapCore` calls it (save; a version restore with site Restore), so
  * `saveTapInternalWith` and `restoreTapInternal` are the real entry points
  * here. `testTap`, `runTap` and `generateScript` must call
  * `referenceRefusal` with site Test (before `TapRunner.run`), Run (after the
  * tap is loaded) and Generate; generate is driven through the seam pinned
  * in TapReviewRepointSpec, test and run through the companion only.
  */
class TapReferenceRulesSpec extends AnyFunSuite with BeforeAndAfterEach {

    private val env: DatrisEnvironment = ScopeDenialFixtures.auditEnv()
    private var savedValues: DatrisEnvironment = _
    private var savedScope: Option[String] = None
    private val audit = mutable.ArrayBuffer[AuditEntry]()

    override def beforeEach(): Unit = {
        savedValues = DatrisEnvironment.values
        DatrisEnvironment.init(env)
        ScopeDenialFixtures.install(env)
        savedScope = sys.props.get(SecretNames.TapScopeProperty)
        sys.props(SecretNames.TapScopeProperty) = "tap"
        audit.clear()
        AuditLog.sinkOverride = (e: AuditEntry) => audit.synchronized { audit += e; () }
    }

    override def afterEach(): Unit = {
        AuditLog.sinkOverride = null
        ScopeDenialFixtures.install(savedValues)
        savedScope match {
            case Some(v) => sys.props(SecretNames.TapScopeProperty) = v
            case None => sys.props -= SecretNames.TapScopeProperty
        }
    }

    // ---------------------------------------------------------------- identities

    private def key(label: String, caps: String*): ResolvedKey = ScopeDenialFixtures.key(label, caps: _*)
    private def session(u: User): ResolvedKey = APIKeyValidator.resolveFromSession(u)

    private val salesCaps = Seq(
        "pipeline:read:catalog=Sales",
        "pipeline:run:catalog=Sales",
        "tap:read:catalog=Sales",
        "tap:create:catalog=Sales",
        "tap:update:catalog=Sales",
        "tap:run:catalog=Sales"
    )
    private val builderCaps = Seq("pipeline:read", "tap:read:owner=self", "tap:create", "tap:update:owner=self", "tap:run:owner=self")

    /** Limited callers: a restricted session, a catalog-limited key, a key limited to what it created. */
    private val rita = session(User("rita", "h", User.RoleEditor, null, null, null, restricted = true, catalogGrants = grants("Sales" -> "edit")))
    private val salesKey = key("sales-agent", salesCaps: _*)
    private val builder = key("builder", builderCaps: _*)
    private val limited: Seq[ResolvedKey] = Seq(rita, salesKey, builder)

    /** Unrestricted callers (null: no identity, API keys off). */
    private val admin = session(User("ada", "h", User.RoleAdmin, null, null, null))
    private val editor = session(User("ed", "h", User.RoleEditor, null, null, null))
    private val fullKey = key("full-agent", "*:*")
    private val legacyKey = ResolvedKey(None, "root", Seq(Capability.FullAccess), isLegacyFullAccess = true)
    private val ragKey = key("rag-agent", KeyTemplatesForSpecs.capabilities("rag-builder"): _*)
    private val unrestricted: Seq[ResolvedKey] = Seq(admin, editor, fullKey, legacyKey, ragKey, null)

    private def req(rk: ResolvedKey, path: String = "/api/v1/tap"): (HttpServletRequest, mutable.Map[String, AnyRef]) =
        ScopeDenialFixtures.request(rk, "POST", path)

    // ---------------------------------------------------------------- stored taps

    private val RepoPrefix = "datris-taps/"

    private val salesTap = TapConfig(
        name = "sales-tap",
        description = "d",
        targetPipeline = "sales-orders",
        catalog = "Sales",
        createdByKeyLabel = "session:ada",
        scriptPath = "tap-scripts/sales-tap_11111111.py"
    )
    private val builderTap = salesTap.copy(name = "builder-tap", createdByKeyLabel = "builder", scriptPath = "tap-scripts/builder-tap_22222222.py")
    private val financeTap = TapConfig(
        name = "finance-secret-tap",
        description = "d",
        targetPipeline = "finance-ledger",
        catalog = "Finance",
        createdByKeyLabel = "session:ada",
        scriptPath = "tap-scripts/finance-secret-tap_9f8e7d6c.py"
    )
    private val financeRepoTap = financeTap.copy(
        name = "finance-repo-tap",
        scriptPath = null,
        scriptStorage = "github",
        scriptRepoPath = RepoPrefix + "finance-repo-tap.py",
        scriptCommitSha = "c0ffee"
    )

    /** Hand-saved by an administrator with a script outside the tap script area: saves and runs on v1.61.0. */
    private val legacyTap = salesTap.copy(name = "sales-legacy", createdByKeyLabel = "root", scriptPath = "uploads/legacy/sales-legacy.py")
    private val allTaps = Seq(salesTap, builderTap, financeTap, financeRepoTap, legacyTap)

    /** The current script of a tap the caller may not see, per limited caller. */
    private def hiddenScriptFor(rk: ResolvedKey): String = if (rk eq builder) salesTap.scriptPath else financeTap.scriptPath
    private def hiddenNamesFor(rk: ResolvedKey): Seq[String] =
        if (rk eq builder) Seq("sales-tap", "11111111", "session:ada") else Seq("finance-secret-tap", "Finance", "9f8e7d6c", "session:ada")

    private val secretOwners: Map[String, Option[String]] = Map("ada-weather" -> Some("session:ada"), "builder-secret" -> Some("builder"))

    private class Counted {
        val tapsRead = new AtomicInteger(0)
        val secretsRead = new AtomicInteger(0)
        val lookups: Lookups = Lookups(
            allTaps = () => { tapsRead.incrementAndGet(); allTaps },
            secretOwner = n => { secretsRead.incrementAndGet(); secretOwners.get(n) },
            repoPathPrefix = () => RepoPrefix
        )
    }

    private def judge(rk: ResolvedKey, site: String, candidate: TapConfig, stored: TapConfig, c: Counted = new Counted): Seq[Finding] =
        TapReferenceRules.judge(Option(rk), site, candidate, stored, c.lookups)

    private def enforced(fs: Seq[Finding]): Seq[Finding] = fs.filter(_.mode == Enforced)
    private def reported(fs: Seq[Finding], rule: Int): Boolean = fs.exists(f => f.rule == rule && f.mode == Reported)

    /** A new tap in the caller's own catalog. */
    private def newTap(name: String, path: String): TapConfig =
        TapConfig(name = name, description = "d", targetPipeline = "sales-orders", catalog = "Sales", scriptPath = path)

    private def newRepoTap(name: String, repoPath: String): TapConfig =
        TapConfig(
            name = name,
            description = "d",
            targetPipeline = "sales-orders",
            catalog = "Sales",
            scriptStorage = "github",
            scriptRepoPath = repoPath,
            scriptCommitSha = "abc123"
        )

    /** Every built-in reference form outside the tap script area. */
    private def outsideForms(name: String): Seq[String] = Seq(
        "pipeline-scripts/sales-orders/transformation_1a2b3c4d.py",
        "uploads/sales/orders.csv",
        "tap-scripts/../pipeline-scripts/sales-orders/transformation_1a2b3c4d.py",
        "tap-scripts/x/../../uploads/sales/orders.csv",
        "/tap-scripts/" + name + "_1a2b3c4d.py",
        "TAP-SCRIPTS/" + name + "_1a2b3c4d.py",
        "Tap-Scripts/" + name + "_1a2b3c4d.py",
        "tap-scripts", // the area itself is not a script
        // an empty or dot segment
        "tap-scripts/" + name + "_1a2b3c4d.py/",
        "tap-scripts//" + name + "_1a2b3c4d.py",
        "tap-scripts/sub//" + name + "_1a2b3c4d.py",
        "tap-scripts/./" + name + "_1a2b3c4d.py",
        "./tap-scripts/" + name + "_1a2b3c4d.py",
        "tap-scripts/sub/./" + name + "_1a2b3c4d.py",
        "tap-scripts/."
    )

    private def outsideRepoForms(name: String): Seq[String] = Seq(
        RepoPrefix + "pipelines/sales-orders/transformation.py",
        RepoPrefix + "../" + name + ".py",
        "/" + RepoPrefix + name + ".py",
        "elsewhere/" + name + ".py",
        // an empty or dot segment, after the prefix or anywhere in the path
        RepoPrefix + name + ".py/",
        RepoPrefix + "/" + name + ".py",
        RepoPrefix + "sub//" + name + ".py",
        "datris-taps//" + name + ".py",
        RepoPrefix + "./" + name + ".py",
        RepoPrefix + "sub/./" + name + ".py",
        "./" + RepoPrefix + name + ".py",
        RepoPrefix + "/pipelines/sales-orders/transformation.py",
        RepoPrefix + "./pipelines/sales-orders/transformation.py"
    )

    /** Other spellings of the stored reference `p`: a trailing slash, a
      * doubled slash, a dot segment. */
    private def otherSpellings(p: String): Seq[String] = {
        val i = p.lastIndexOf('/')
        val (dir, file) = (p.substring(0, i), p.substring(i + 1))
        Seq(p + "/", dir + "//" + file, dir + "/./" + file, "./" + p, p.replaceFirst("/", "//"))
    }

    private val Sites3 = Seq(TapReferenceRules.Save, TapReferenceRules.Test, TapReferenceRules.Restore)

    /** Where rule 3 refuses in this release; at version restore it is reported only. */
    private val EnforcedSites = Seq(TapReferenceRules.Save, TapReferenceRules.Test)

    /** Rule 3 as this release has it at `site`: enforced on save and test, reported (never enforced) on restore. */
    private def rule3At(site: String, fs: Seq[Finding]): Boolean =
        if (EnforcedSites.contains(site)) fs.exists(f => f.rule == 3 && f.mode == Enforced)
        else fs.exists(f => f.rule == 3 && f.mode == Reported) && enforced(fs).isEmpty

    // ================================================================ rule 3: the pure decision

    test("rule 3: a limited caller's new reference written by the tap script store for this tap passes, on save, test and restore") {
        for (rk <- limited; site <- Sites3) {
            val fs = judge(rk, site, newTap("new-tap", "tap-scripts/new-tap_1a2b3c4d.py"), null)
            assert(enforced(fs).isEmpty, rk.label + " " + site + ": " + fs)
            val repo = judge(rk, site, newRepoTap("new-tap", RepoPrefix + "new-tap.py"), null)
            assert(enforced(repo).isEmpty, rk.label + " " + site + " repo: " + repo)
        }
    }

    test(
        "rule 3: a limited caller's new reference outside the tap script area is an enforced finding on save and test, reported on restore, in every form the store accepts"
    ) {
        for (rk <- limited; site <- Sites3; p <- outsideForms("new-tap")) {
            val fs = judge(rk, site, newTap("new-tap", p), null)
            assert(rule3At(site, fs), rk.label + " " + site + " '" + p + "': " + fs)
        }
        for (rk <- limited; site <- Sites3; p <- outsideRepoForms("new-tap")) {
            val fs = judge(rk, site, newRepoTap("new-tap", p), null)
            assert(rule3At(site, fs), rk.label + " " + site + " repo '" + p + "': " + fs)
        }
    }

    test(
        "rule 3: a limited caller's new reference equal to the current script of a tap it cannot see is an enforced finding on save and test (reported on restore) naming nothing of that tap"
    ) {
        for (rk <- limited; site <- Sites3) {
            val fs = judge(rk, site, newTap("new-tap", hiddenScriptFor(rk)), null)
            assert(rule3At(site, fs), rk.label + " " + site + ": " + fs)
            hiddenNamesFor(rk).foreach(h => assert(!fs.exists(_.reason.contains(h)), rk.label + ": " + fs))
        }
        // the repository form of a hidden tap's script
        for (rk <- Seq(rita, salesKey); site <- Sites3) {
            val fs = judge(rk, site, newRepoTap("new-tap", financeRepoTap.scriptRepoPath), null)
            assert(rule3At(site, fs), rk.label + " " + site + " repo: " + fs)
        }
    }

    test("rule 3: another spelling of a hidden tap's current script (an empty or dot segment) is an enforced finding on save and test, reported on restore") {
        for (rk <- limited; site <- Sites3; p <- otherSpellings(hiddenScriptFor(rk))) {
            val fs = judge(rk, site, newTap("new-tap", p), null)
            assert(rule3At(site, fs), rk.label + " " + site + " '" + p + "': " + fs)
            hiddenNamesFor(rk).foreach(h => assert(!fs.exists(_.reason.contains(h)), rk.label + ": " + fs))
        }
        for (rk <- Seq(rita, salesKey); site <- Sites3; p <- otherSpellings(financeRepoTap.scriptRepoPath)) {
            val fs = judge(rk, site, newRepoTap("new-tap", p), null)
            assert(rule3At(site, fs), rk.label + " " + site + " repo '" + p + "': " + fs)
        }
    }

    test("rule 3: a hidden tap whose stored reference is written in another spelling is matched by the plain spelling of the same file") {
        val oddBuiltIn = financeTap.copy(name = "finance-odd", scriptPath = "tap-scripts//finance-odd_5e5e5e5e.py/")
        val oddRepo = financeRepoTap.copy(name = "finance-odd-repo", scriptRepoPath = "datris-taps//finance-odd-repo.py")
        val lookups = Lookups(() => allTaps ++ Seq(oddBuiltIn, oddRepo), n => secretOwners.get(n), () => RepoPrefix)
        for (rk <- Seq(rita, salesKey); site <- Sites3) {
            val a = TapReferenceRules.judge(Some(rk), site, newTap("new-tap", "tap-scripts/finance-odd_5e5e5e5e.py"), null, lookups)
            assert(rule3At(site, a), rk.label + " " + site + ": " + a)
            val b = TapReferenceRules.judge(Some(rk), site, newRepoTap("new-tap", RepoPrefix + "finance-odd-repo.py"), null, lookups)
            assert(rule3At(site, b), rk.label + " " + site + " repo: " + b)
        }
        assert(TapReferenceRules.normalizedRef("tap-scripts//a_1.py/") == "tap-scripts/a_1.py")
        assert(TapReferenceRules.normalizedRef("/datris-taps/./a.py") == "datris-taps/a.py")
        assert(TapReferenceRules.normalizedRef("datris-taps/x/../a.py") == "datris-taps/a.py")
    }

    test("rule 3: the references the tap script stores write themselves are inside the area, for every prefix form") {
        // built-in: <tap-scripts/><tap name>_<uuid>.py, whatever the tap name holds
        for (name <- Seq("new-tap", "sales.orders", "Sales_Orders", "tap", "50%-off", "a b"))
            assert(TapReferenceRules.builtInInArea("tap-scripts/" + name + "_1a2b3c4d.py"), name)
        // repository: <normalized prefix><tap name>.py
        for (
            (prefix, name) <- Seq(
                "" -> "new-tap",
                "taps/" -> "new-tap",
                "taps" -> "new-tap",
                "org/team/taps/" -> "sales.orders",
                RepoPrefix -> "50%-off"
            )
        ) {
            val path = TapReferenceRules.normalizePrefix(prefix) + name + ".py"
            assert(TapReferenceRules.repoInArea(path, prefix), prefix + " " + path)
            for (rk <- limited; site <- EnforcedSites) {
                val lk = Lookups(() => allTaps, n => secretOwners.get(n), () => TapReferenceRules.normalizePrefix(prefix))
                val fs = TapReferenceRules.judge(Some(rk), site, newRepoTap(name, path), null, lk)
                assert(enforced(fs).isEmpty, rk.label + " " + site + " '" + path + "': " + fs)
            }
        }
        for (rk <- limited; site <- EnforcedSites)
            assert(enforced(judge(rk, site, newTap("50%-off", "tap-scripts/50%-off_1a2b3c4d.py"), null)).isEmpty, rk.label)
    }

    test("rule 3: a percent sequence in a reference reaches each store as literal characters, so it names that file and no other") {
        // Built-in store: the MinIO client builds the object URL with S3Escaper.encodePath and
        // HttpUrl.Builder.addEncodedPathSegments (MinioAsyncClient / S3Base.buildUrl).
        def minioPath(key: String): java.util.List[String] =
            new okhttp3.HttpUrl.Builder()
                .scheme("http")
                .host("minio")
                .addPathSegment("test-config")
                .addEncodedPathSegments(io.minio.S3Escaper.encodePath(key))
                .build()
                .pathSegments()
        for (seq <- Seq("%2e%2e", "%2E%2E", "%2e", "%2f", "%2F", "%252e")) {
            val key = "tap-scripts/" + seq + "/x_1.py"
            assert(minioPath(key).asScala.toList == List("test-config", "tap-scripts", seq, "x_1.py"), key + " -> " + minioPath(key))
        }
        assert(minioPath("tap-scripts/a%2fb_1.py").asScala.toList == List("test-config", "tap-scripts", "a%2fb_1.py"))
        // A key with a `.` or `..` segment is refused by the client's own argument checks, in the
        // builders MinIOUtility reads, writes and deletes with, so nothing is sent; rule 3 still
        // judges such a reference outside the area.
        for (key <- Seq("tap-scripts/../x_1.py", "tap-scripts/./x_1.py", "tap-scripts/a/../../x_1.py", "tap-scripts/x_1.py/..")) {
            intercept[IllegalArgumentException](io.minio.GetObjectArgs.builder().bucket("test-config").`object`(key).build())
            intercept[IllegalArgumentException](
                io.minio.PutObjectArgs.builder().bucket("test-config").`object`(key).stream(new java.io.ByteArrayInputStream(Array[Byte]()), 0L, -1).build()
            )
            intercept[IllegalArgumentException](io.minio.RemoveObjectArgs.builder().bucket("test-config").`object`(key).build())
            assert(!TapReferenceRules.builtInInArea(key), key)
            for (rk <- limited) assert(rule3At(TapReferenceRules.Save, judge(rk, TapReferenceRules.Save, newTap("new-tap", key), null)), rk.label + " " + key)
        }
        assert(io.minio.GetObjectArgs.builder().bucket("test-config").`object`("tap-scripts/x_1.py").build().`object`() == "tap-scripts/x_1.py")
        // Repository store: GithubClient escapes each segment, so a percent sign is sent as %25
        val cfg = CodeRepoConfig(repo = "o/r", authSecretName = "s")
        val base = "https://api.github.com/repos/o/r/contents/"
        assert(ai.datris.util.GithubClient.contentsUrl(cfg, RepoPrefix + "%2e%2e/x.py") == base + "datris-taps/%252e%252e/x.py")
        assert(ai.datris.util.GithubClient.contentsUrl(cfg, RepoPrefix + "a%2fb.py") == base + "datris-taps/a%252fb.py")
        // the rule judges such a reference as written: a file of that name, inside the area unless it is a hidden tap's
        for (rk <- limited) {
            assert(enforced(judge(rk, TapReferenceRules.Save, newTap("new-tap", "tap-scripts/%2e%2e%2fnew-tap_1.py"), null)).isEmpty, rk.label)
            val hiddenLiteral = financeTap.copy(name = "finance-pct", scriptPath = "tap-scripts/finance%2dpct_1.py")
            val lk = Lookups(() => allTaps :+ hiddenLiteral, n => secretOwners.get(n), () => RepoPrefix)
            val fs = TapReferenceRules.judge(Some(rk), TapReferenceRules.Save, newTap("new-tap", hiddenLiteral.scriptPath), null, lk)
            assert(enforced(fs).nonEmpty, rk.label + ": the literal reference of a hidden tap is refused: " + fs)
        }
    }

    test(
        "rule 3: a changed reference on a stored tap is judged as a new one; the stored tap's own unchanged reference passes even when it would be refused as new"
    ) {
        for (rk <- Seq(rita, salesKey)) {
            // legacyTap's stored reference is outside the area: unchanged, it passes
            for (site <- Sites3) {
                val same = judge(rk, site, legacyTap.copy(description = "changed"), legacyTap)
                assert(enforced(same).isEmpty, rk.label + " " + site + ": " + same)
                val omitted = judge(rk, site, legacyTap.copy(scriptPath = null, description = "changed"), legacyTap)
                assert(enforced(omitted).isEmpty, "a body without a reference keeps the stored one: " + omitted)
            }
            // changed to another outside path: refused
            val moved = judge(rk, TapReferenceRules.Save, legacyTap.copy(scriptPath = "uploads/legacy/other.py"), legacyTap)
            assert(moved.exists(f => f.rule == 3 && f.mode == Enforced), moved.toString)
        }
        val ownSame = judge(builder, TapReferenceRules.Save, builderTap.copy(description = "changed"), builderTap)
        assert(enforced(ownSame).isEmpty, ownSame.toString)
    }

    test("rule 3: a limited caller may point a tap at the current script of a tap it can read (cloning is allowed)") {
        for (site <- Sites3) {
            assert(enforced(judge(rita, site, newTap("rita-clone", salesTap.scriptPath), null)).isEmpty)
            assert(enforced(judge(salesKey, site, newTap("key-clone", builderTap.scriptPath), null)).isEmpty)
            assert(enforced(judge(builder, site, newTap("builder-clone", builderTap.scriptPath), null)).isEmpty)
        }
    }

    test("rule 3: an unrestricted caller is never refused by this rule, whatever the reference") {
        val refs = outsideForms("admin-tap") ++ Seq(financeTap.scriptPath, salesTap.scriptPath, "tap-scripts/admin-tap_1a2b3c4d.py")
        for (rk <- unrestricted; site <- Sites3; p <- refs) {
            val fs = judge(rk, site, newTap("admin-tap", p), null)
            assert(enforced(fs).isEmpty, String.valueOf(rk) + " " + site + " '" + p + "': " + fs)
        }
        for (rk <- unrestricted; p <- outsideRepoForms("admin-tap"))
            assert(enforced(judge(rk, TapReferenceRules.Save, newRepoTap("admin-tap", p), null)).isEmpty)
    }

    test("rule 3: a run or a generate is never refused by these rules, for any caller") {
        for (rk <- limited ++ unrestricted; t <- Seq(legacyTap, financeTap, salesTap.copy(scriptPath = "uploads/x.py"))) {
            assert(enforced(judge(rk, TapReferenceRules.Run, t, t)).isEmpty, String.valueOf(rk) + " run " + t.name)
            assert(enforced(judge(rk, TapReferenceRules.Generate, t, t)).isEmpty, String.valueOf(rk) + " generate " + t.name)
        }
    }

    test("rule 3: all taps are read only for a limited caller with a new or changed reference, and at most once") {
        for (rk <- unrestricted) {
            val c = new Counted
            judge(rk, TapReferenceRules.Save, newTap("admin-tap", "uploads/x.py"), null, c)
            assert(c.tapsRead.get == 0, String.valueOf(rk))
        }
        for (rk <- limited) {
            val same = new Counted
            judge(rk, TapReferenceRules.Save, legacyTap.copy(description = "changed"), legacyTap, same)
            assert(same.tapsRead.get == 0, rk.label + ": unchanged reference")
            val changed = new Counted
            judge(rk, TapReferenceRules.Save, newTap("new-tap", "tap-scripts/new-tap_1a2b3c4d.py"), null, changed)
            assert(changed.tapsRead.get <= 1, rk.label + ": " + changed.tapsRead.get)
        }
    }

    // ================================================================ rules 5-8: reported only

    test("rule 5: a limited caller's new reference naming no tap it may read is reported, never enforced") {
        for (rk <- limited) {
            val fs = judge(rk, TapReferenceRules.Save, newTap("new-tap", "tap-scripts/unrelated-name_1a2b3c4d.py"), null)
            assert(reported(fs, 5) && enforced(fs).isEmpty, rk.label + ": " + fs)
        }
    }

    test("rule 5: this tap's own name, or a readable tap's current script, is not reported; unrestricted callers are never reported under rule 5") {
        for (rk <- limited) {
            assert(!reported(judge(rk, TapReferenceRules.Save, newTap("new-tap", "tap-scripts/new-tap_1a2b3c4d.py"), null), 5), rk.label)
            val readable = if (rk eq builder) builderTap else salesTap
            assert(!reported(judge(rk, TapReferenceRules.Save, newTap("clone", readable.scriptPath), null), 5), rk.label)
            assert(!reported(judge(rk, TapReferenceRules.Save, legacyTap.copy(description = "x"), legacyTap), 5), rk.label + ": unchanged")
        }
        for (rk <- unrestricted)
            assert(!reported(judge(rk, TapReferenceRules.Save, newTap("new-tap", "tap-scripts/unrelated-name_1a2b3c4d.py"), null), 5))
    }

    test("rule 6: a reference outside the tap script area is reported for every caller (never enforced for an unrestricted one), and on a run") {
        for (rk <- unrestricted) {
            val fs = judge(rk, TapReferenceRules.Save, newTap("admin-tap", "uploads/legacy/sales-legacy.py"), null)
            assert(reported(fs, 6) && enforced(fs).isEmpty, String.valueOf(rk) + ": " + fs)
            val run = judge(rk, TapReferenceRules.Run, legacyTap, legacyTap)
            assert(reported(run, 6) && enforced(run).isEmpty, String.valueOf(rk) + " run: " + run)
        }
        // a limited caller's unchanged stored reference outside the area: reported, not refused
        for (rk <- Seq(rita, salesKey)) {
            val fs = judge(rk, TapReferenceRules.Save, legacyTap.copy(description = "x"), legacyTap)
            assert(reported(fs, 6) && enforced(fs).isEmpty, rk.label + ": " + fs)
        }
    }

    test("rule 6: a reference inside the tap script area is not reported") {
        for (rk <- unrestricted ++ limited) {
            assert(!reported(judge(rk, TapReferenceRules.Save, newTap("new-tap", "tap-scripts/new-tap_1a2b3c4d.py"), null), 6))
            assert(!reported(judge(rk, TapReferenceRules.Run, salesTap, salesTap), 6))
            assert(!reported(judge(rk, TapReferenceRules.Save, newRepoTap("new-tap", RepoPrefix + "new-tap.py"), null), 6))
        }
    }

    test("rule 7: a save or generate naming a secret the caller's tap read does not cover is reported, never enforced") {
        val withSecret = builderTap.copy(secretName = "ada-weather")
        for (site <- Seq(TapReferenceRules.Save, TapReferenceRules.Generate)) {
            val fs = judge(builder, site, withSecret, builderTap)
            assert(reported(fs, 7) && enforced(fs).isEmpty, site + ": " + fs)
        }
    }

    test("rule 7: not reported for a secret the caller's read covers, a missing secret, or a caller whose tap read is not scoped (who reads no secret)") {
        assert(!reported(judge(builder, TapReferenceRules.Save, builderTap.copy(secretName = "builder-secret"), builderTap), 7))
        assert(!reported(judge(builder, TapReferenceRules.Save, builderTap.copy(secretName = "no-such-secret"), builderTap), 7))
        // a restricted session's catalog grant covers any tap secret in its own catalog's taps
        assert(!reported(judge(rita, TapReferenceRules.Save, salesTap.copy(secretName = "ada-weather"), salesTap), 7))
        assert(!reported(judge(salesKey, TapReferenceRules.Save, salesTap.copy(secretName = "ada-weather"), salesTap), 7))
        for (rk <- unrestricted) {
            val c = new Counted
            assert(!reported(judge(rk, TapReferenceRules.Save, salesTap.copy(secretName = "ada-weather"), salesTap, c), 7), String.valueOf(rk))
            assert(c.secretsRead.get == 0, String.valueOf(rk) + ": no secret read")
        }
    }

    test("rule 8: a limited caller's run of a stored tap it could not save with its secret is reported, never enforced") {
        val t = builderTap.copy(secretName = "ada-weather")
        val fs = judge(builder, TapReferenceRules.Run, t, t)
        assert(reported(fs, 8) && enforced(fs).isEmpty, fs.toString)
        // a limited caller's run of a tap whose script sits outside the area: reported (rule 6 or 8), never enforced
        val outside = judge(salesKey, TapReferenceRules.Run, legacyTap, legacyTap)
        assert(outside.nonEmpty && enforced(outside).isEmpty && outside.forall(_.mode == Reported), outside.toString)
    }

    test("rule 8: not reported for the caller's own secret, or for an unrestricted caller") {
        val own = builderTap.copy(secretName = "builder-secret")
        assert(!reported(judge(builder, TapReferenceRules.Run, own, own), 8))
        val t = builderTap.copy(secretName = "ada-weather")
        for (rk <- unrestricted) assert(!reported(judge(rk, TapReferenceRules.Run, t, t), 8), String.valueOf(rk))
    }

    test("rule 4: review would be refused (next release) for an identity holding no tap update; not for one that holds it or for no identity") {
        assert(TapReferenceRules.reviewWouldBeRefused(Some(key("ro", KeyTemplatesForSpecs.capabilities("read-only"): _*))))
        assert(TapReferenceRules.reviewWouldBeRefused(Some(key("rag", KeyTemplatesForSpecs.capabilities("rag-builder"): _*))))
        assert(TapReferenceRules.reviewWouldBeRefused(Some(session(User("vera", "h", User.RoleViewer, null, null, null)))))
        assert(!TapReferenceRules.reviewWouldBeRefused(Some(editor)))
        assert(!TapReferenceRules.reviewWouldBeRefused(Some(admin)))
        assert(!TapReferenceRules.reviewWouldBeRefused(Some(salesKey)), "a scoped tap:update is held")
        assert(!TapReferenceRules.reviewWouldBeRefused(Some(fullKey)))
        assert(!TapReferenceRules.reviewWouldBeRefused(None))
    }

    // ================================================================ the save core: rule 3 wiring

    private val pipelines = Seq(
        PipelineConfig(name = "sales-orders", catalog = "Sales", createdByKeyLabel = "session:ada"),
        PipelineConfig(name = "finance-ledger", catalog = "Finance", createdByKeyLabel = "session:ada")
    )

    /** In-memory stores behind the save core; every script read and write is counted. */
    private class Store(initial: Seq[TapConfig] = allTaps) {
        val taps: mutable.Map[String, TapConfig] = mutable.LinkedHashMap(initial.map(t => t.name -> t): _*)
        val writes: mutable.Buffer[TapConfig] = mutable.Buffer.empty
        val scriptChecks = new AtomicInteger(0)
        val scriptReads = new AtomicInteger(0)
        val io: TapAPIController.TapSaveIO = TapAPIController.TapSaveIO(
            readTap = n => taps.get(n).orNull,
            readPipeline = n => pipelines.find(_.name == n).orNull,
            secretType = _ => Some(Some(SecretNames.TapScopeTap)),
            scriptExists = _ => { scriptChecks.incrementAndGet(); true },
            readScript = _ => { scriptReads.incrementAndGet(); Some("def fetch():\n    return []") },
            loadAll = () => (taps.values.toSeq, pipelines),
            writeVersioned = (t, _, _) => {
                writes += t
                taps(t.name) = t
                t
            },
            secretOwner = n => secretOwners.get(n),
            repoPathPrefix = () => RepoPrefix
        )
        def nothingDone(): Unit = {
            assert(writes.isEmpty, "nothing written: " + writes)
            assert(scriptChecks.get == 0 && scriptReads.get == 0, "no script read: " + scriptChecks.get + "/" + scriptReads.get)
        }
    }

    private def save(rk: ResolvedKey, store: Store, body: TapConfig): Either[ResponseEntity[String], TapConfig] =
        new TapAPIController().saveTapInternalWith(body, null, req(rk)._1, restored = false, store.io)

    private def capsOf(rk: ResolvedKey): Seq[String] = if (rk eq builder) builderCaps else salesCaps

    /** The refusal a limited caller gets for `name`, as `testRefusal` answers today. */
    private def assertRefusal(rk: ResolvedKey, name: String, r: ResponseEntity[String], hidden: Seq[String]): Unit =
        if (rk.restrictedSession) {
            val nf = TapAPIController.tapNotFound(name)
            assert(r.getStatusCode == nf.getStatusCode && r.getBody == nf.getBody, r.getBody)
            ScopeDenialWording.assertHides(r.getBody, hidden)
        } else {
            assert(r.getStatusCode.value == 403, r.getBody)
            ScopeDenialWording.assertBody(r.getBody, ScopeDenialWording.message(rk.label, "tap", "read", capsOf(rk)), hidden)
        }

    test("rule 3 at POST /tap: a limited caller's new reference outside the area is refused with the test route's answer, and nothing is read or written") {
        for (rk <- limited; p <- outsideForms("new-tap")) {
            val store = new Store()
            val out = save(rk, store, newTap("new-tap", p))
            assert(out.isLeft, rk.label + " '" + p + "': " + out)
            assertRefusal(rk, "new-tap", out.left.get, hiddenNamesFor(rk))
            store.nothingDone()
        }
        for (rk <- limited; p <- outsideRepoForms("new-tap")) {
            val store = new Store()
            val out = save(rk, store, newRepoTap("new-tap", p))
            assert(out.isLeft, rk.label + " repo '" + p + "': " + out)
            store.nothingDone()
        }
    }

    test("rule 3 at POST /tap: a hidden tap's current script is refused with the same answer as a path outside the area, revealing nothing of that tap") {
        for (rk <- limited) {
            val a = new Store()
            val hiddenOut = save(rk, a, newTap("clone-tap", hiddenScriptFor(rk)))
            val b = new Store()
            val outsideOut = save(rk, b, newTap("clone-tap", "uploads/sales/orders.csv"))
            assert(hiddenOut.isLeft && outsideOut.isLeft, rk.label + ": " + hiddenOut + " / " + outsideOut)
            assert(hiddenOut.left.get.getStatusCode == outsideOut.left.get.getStatusCode && hiddenOut.left.get.getBody == outsideOut.left.get.getBody, rk.label)
            assertRefusal(rk, "clone-tap", hiddenOut.left.get, hiddenNamesFor(rk))
            a.nothingDone()
        }
    }

    test("rule 3 at POST /tap: inside the area, a readable tap's script, and an unchanged stored reference all save as before") {
        for (rk <- limited) {
            val s1 = new Store()
            assert(save(rk, s1, newTap("new-tap", "tap-scripts/new-tap_1a2b3c4d.py")).isRight, rk.label)
            assert(s1.writes.size == 1)
            val s2 = new Store()
            val readable = if (rk eq builder) builderTap else salesTap
            assert(save(rk, s2, newTap("clone-tap", readable.scriptPath)).isRight, rk.label)
        }
        for (rk <- Seq(rita, salesKey)) {
            val s = new Store()
            val out = save(rk, s, legacyTap.copy(description = "changed"))
            assert(out.isRight && s.writes.size == 1 && s.writes.head.scriptPath == legacyTap.scriptPath, rk.label + ": " + out)
            val omitted = new Store()
            val out2 = save(rk, omitted, legacyTap.copy(description = "changed again", scriptPath = null))
            assert(out2.isRight && omitted.writes.head.scriptPath == legacyTap.scriptPath, rk.label + ": " + out2)
        }
    }

    test("unchanged: an unrestricted caller's save with any reference (outside the area, another tap's script) is written exactly as on v1.61.0") {
        val bodies = Seq(
            newTap("admin-tap", "uploads/legacy/sales-legacy.py"),
            newTap("admin-tap-2", financeTap.scriptPath),
            newRepoTap("admin-tap-3", "elsewhere/x.py")
        )
        // the rag-builder key loads only into pipelines it created (pipeline:run:owner=self): its save of a tap
        // targeting another identity's pipeline is refused by the target rule, as on v1.61.0, so it is left out here
        for (b <- bodies) {
            val written = unrestricted.filterNot(_ eq ragKey).map { rk =>
                val s = new Store()
                val out = save(rk, s, b)
                assert(out.isRight && s.writes.size == 1, String.valueOf(rk) + " " + b.name + ": " + out)
                assert(s.scriptReads.get >= 1, "the script is read for dependency inference as today")
                s.writes.head.copy(createdAt = null, updatedAt = null, createdByKeyLabel = null)
            }
            assert(written.distinct.size == 1, b.name + ": " + written)
        }
    }

    // ================================================================ version restore and the recovery agent's revert

    private val hiddenVersion: ResponseEntity[String] = ResponseEntity.status(404).body[String]("{\"error\": \"No version 3 for tap 'sales-tap'\"}")

    private def restore(rk: ResolvedKey, store: Store, snapPath: String, live: TapConfig): Either[ResponseEntity[String], TapConfig] =
        new TapAPIController().restoreTapInternal(
            EntityVersionAPIController.tapRestoreCandidate(live.name, live.copy(scriptPath = snapPath, description = "v3"), live),
            live,
            3,
            hiddenVersion,
            req(rk, "/api/v1/tap/version/restore")._1,
            store.io
        )

    test(
        "rule 3 at version restore (reported only in this release): a limited caller's version outside the area, or on a hidden tap's script, is restored exactly as on v1.61.0, with the report"
    ) {
        for (rk <- Seq(rita, salesKey); snapPath <- Seq("uploads/legacy/old.py", financeTap.scriptPath)) {
            // a tap name of its own, so the once-a-day bound of an earlier case does not hide this report
            val live = salesTap.copy(name = "restore-" + System.nanoTime())
            val s = new Store(allTaps :+ live)
            val (out, warns) = withWarns(restore(rk, s, snapPath, live))
            assert(out.isRight, rk.label + " '" + snapPath + "': " + out)
            assert(s.writes.size == 1 && s.writes.head.scriptPath == snapPath, rk.label + ": " + s.writes)
            assert(warns.exists(_.contains(live.name)), warns.mkString("\n"))
            val rule3 = rowsFor(live.name).filter(_.metadata.exists(md => md.get("rule").getAsString == "3"))
            assert(rule3.size == 1, rowsFor(live.name).toString)
            assert(rule3.head.action == "reference-would-be-refused" && !rule3.head.metadata.get.get("enforced").getAsBoolean, rule3.head.toString)
        }
    }

    test("rule 3 at version restore: a version on this tap's own earlier script restores as before, and unrestricted callers restore any version") {
        for (rk <- Seq(rita, salesKey)) {
            val s = new Store()
            assert(restore(rk, s, "tap-scripts/sales-tap_00000000.py", salesTap).isRight, rk.label)
            assert(s.writes.size == 1)
        }
        for (rk <- Seq(admin, editor, fullKey, legacyKey, null)) {
            val s = new Store()
            assert(restore(rk, s, "uploads/legacy/old.py", salesTap).isRight, String.valueOf(rk))
        }
    }

    test("unchanged: the recovery agent's tap revert (no caller identity) is checked as on v1.61.0, whatever the reference, with no new read") {
        for (snapPath <- Seq("uploads/legacy/old.py", "tap-scripts//old_1.py")) {
            val s = new Store()
            val reads = mutable.Buffer[String]()
            val io = s.io.copy(
                readTap = n => { reads += "readTap"; s.io.readTap(n) },
                readPipeline = n => { reads += "readPipeline"; s.io.readPipeline(n) },
                secretType = n => { reads += "secretType"; s.io.secretType(n) },
                loadAll = () => { reads += "loadAll"; s.io.loadAll() },
                secretOwner = n => { reads += "secretOwner"; s.io.secretOwner(n) },
                repoPathPrefix = () => { reads += "repoPathPrefix"; s.io.repoPathPrefix() }
            )
            val candidate =
                EntityVersionAPIController.tapRestoreCandidate("sales-tap", salesTap.copy(scriptPath = snapPath, secretName = "ada-weather"), salesTap)
            val out = new TapAPIController().checkTapRevert(candidate, salesTap, 3, io)
            assert(out.isRight && out.right.get.scriptPath == snapPath, out.toString)
            assert(s.writes.isEmpty, "the revert check writes nothing")
            // v1.61.0's reads: the secret's type, the script existence check, the tap and pipeline list once, the script once
            assert(reads.toList == List("secretType", "loadAll"), reads.toString)
            assert(s.scriptChecks.get == 1 && s.scriptReads.get == 1, s.scriptChecks.get + "/" + s.scriptReads.get)
        }
    }

    // ================================================================ the companion: test, run, generate sites and the reports

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

    private def rowsFor(name: String): List[AuditEntry] = audit.synchronized(audit.toList).filter(_.resourceName.contains(name))

    private def refusal(
        rk: ResolvedKey,
        site: String,
        candidate: TapConfig,
        stored: TapConfig
    ): (Option[ResponseEntity[String]], mutable.Map[String, AnyRef]) = {
        val (r, attrs) = req(rk, "/api/v1/tap/" + site)
        (TapAPIController.referenceRefusal(r, site, candidate, stored, new Counted().lookups), attrs)
    }

    test("rule 3 at POST /tap/test: the refusal is the same answer the save gives") {
        for (rk <- limited) {
            val (t, _) = refusal(rk, TapReferenceRules.Test, newTap("test-tap", "uploads/sales/orders.csv"), null)
            val s = new Store()
            val saved = save(rk, s, newTap("test-tap", "uploads/sales/orders.csv"))
            assert(t.isDefined && saved.isLeft, rk.label)
            assert(t.get.getStatusCode == saved.left.get.getStatusCode && t.get.getBody == saved.left.get.getBody, rk.label + ": " + t.get.getBody)
        }
    }

    test("rule 3 at POST /tap/test and POST /tap: every outside form, and another spelling of a hidden tap's script, gets the same refusal at both") {
        for (rk <- limited) {
            val forms = outsideForms("test-tap").map(p => newTap("test-tap", p)) ++
                outsideRepoForms("test-tap").map(p => newRepoTap("test-tap", p)) ++
                otherSpellings(hiddenScriptFor(rk)).map(p => newTap("test-tap", p))
            for (candidate <- forms) {
                val (t, _) = refusal(rk, TapReferenceRules.Test, candidate, null)
                val store = new Store()
                val saved = save(rk, store, candidate)
                assert(t.isDefined && saved.isLeft, rk.label + " " + candidate.scriptPath + " " + candidate.scriptRepoPath)
                assert(t.get.getStatusCode == saved.left.get.getStatusCode && t.get.getBody == saved.left.get.getBody, rk.label + ": " + t.get.getBody)
                assertRefusal(rk, "test-tap", t.get, hiddenNamesFor(rk))
                store.nothingDone()
            }
        }
    }

    test("rule 3 at version restore: an outside form or another spelling of a hidden tap's script is restored as before, with the rule 3 report") {
        for (rk <- Seq(rita, salesKey); snapPath <- Seq("tap-scripts//x_1.py", "tap-scripts/./x_1.py", financeTap.scriptPath + "/")) {
            val live = salesTap.copy(name = "restore-sp-" + System.nanoTime())
            val s = new Store(allTaps :+ live)
            val out = restore(rk, s, snapPath, live)
            assert(out.isRight && s.writes.size == 1 && s.writes.head.scriptPath == snapPath, rk.label + " '" + snapPath + "': " + out)
            val rule3 = rowsFor(live.name).filter(_.metadata.exists(md => md.get("rule").getAsString == "3"))
            assert(rule3.size == 1 && !rule3.head.metadata.get.get("enforced").getAsBoolean, rowsFor(live.name).toString)
        }
    }

    // ================================================================ rule 6 for taps stored in a code repository

    private class CountedPrefix(prefix: String = RepoPrefix) {
        val prefixReads = new AtomicInteger(0)
        val lookups: Lookups = Lookups(() => allTaps, n => secretOwners.get(n), () => { prefixReads.incrementAndGet(); prefix })
    }

    test(
        "rule 6: a repository reference outside the current path prefix is reported where the prefix was already read for rule 3 (a limited caller's version restore)"
    ) {
        val c = new CountedPrefix()
        val stored = newRepoTap("repo-tap", RepoPrefix + "repo-tap.py")
        val fs = TapReferenceRules.judge(Some(salesKey), TapReferenceRules.Restore, stored.copy(scriptRepoPath = "old-prefix/repo-tap.py"), stored, c.lookups)
        assert(reported(fs, 3) && reported(fs, 6) && enforced(fs).isEmpty, fs.toString)
        assert(c.prefixReads.get == 1, c.prefixReads.get.toString)
    }

    test("rule 6: no repository settings read is added for it: an unrestricted save, an unchanged reference and every run read none") {
        val stored = newRepoTap("repo-tap", "old-prefix/repo-tap.py")
        for (rk <- unrestricted) {
            val c = new CountedPrefix()
            judge(rk, TapReferenceRules.Save, stored.copy(description = "x"), stored, new Counted)
            TapReferenceRules.judge(Option(rk), TapReferenceRules.Save, newRepoTap("n", "elsewhere/n.py"), null, c.lookups)
            TapReferenceRules.judge(Option(rk), TapReferenceRules.Run, stored, stored, c.lookups)
            assert(c.prefixReads.get == 0, String.valueOf(rk))
        }
        for (rk <- limited) {
            val c = new CountedPrefix()
            TapReferenceRules.judge(Some(rk), TapReferenceRules.Save, stored.copy(description = "x"), stored, c.lookups)
            val run = TapReferenceRules.judge(Some(rk), TapReferenceRules.Run, stored, stored, c.lookups)
            assert(c.prefixReads.get == 0 && !reported(run, 6), rk.label + ": " + run)
        }
    }

    // ================================================================ generate naming no tap

    private def generateIO(seen: mutable.Buffer[String]): TapAPIController.TapAiIO = TapAPIController.TapAiIO(
        readTap = _ => fail("no stored tap is read"),
        readScript = _ => None,
        write = _ => fail("no write"),
        writeVersioned = (_, _, _) => fail("no write"),
        secretType = _ => Some(Some(SecretNames.TapScopeTap)),
        secretOwner = nm => secretOwners.get(nm),
        generate = (_, nm, _, _, _) => {
            seen += nm
            TapGenerateResult("def fetch():\n    return []", new java.util.ArrayList[String](), "tap-scripts/" + nm + "_1a2b3c4d.py")
        },
        fix = (_, _, _, _, _, _, _) => fail("not called"),
        review = (_, _, _, _, _, _, _) => fail("not called"),
        optimize = (_, _, _, _, _, _, _) => fail("not called")
    )

    test("rule 7 at POST /tap/generate: a request naming no tap is not reported, so it cannot repeat the report under a new name each time") {
        for (blank <- Seq(None, Some(""), Some("   "))) {
            val seen = mutable.Buffer[String]()
            val b = new java.util.HashMap[String, String]()
            b.put("description", "fetch the weather")
            blank.foreach(b.put("tapName", _))
            b.put("secretName", "ada-weather")
            val before = audit.synchronized(audit.toList).count(_.action == "reference-would-be-refused")
            val (r, warns) = withWarns(new TapAPIController().generateScriptWith(b, req(builder, "/api/v1/tap/generate")._1, generateIO(seen)))
            assert(r.getStatusCode.value == 200, r.getBody)
            assert(!warns.exists(_.contains("access does not cover")), warns.mkString("\n"))
            assert(audit.synchronized(audit.toList).count(_.action == "reference-would-be-refused") == before)
            assert(seen.size == 1, seen.toString)
        }
    }

    test("POST /tap/generate: the name a missing tapName stands for is made once per request, and the model gets the name the route logged") {
        val seen = mutable.Buffer[String]()
        val b = new java.util.HashMap[String, String]()
        b.put("description", "fetch the weather")
        val r = new TapAPIController().generateScriptWith(b, req(builder, "/api/v1/tap/generate")._1, generateIO(seen), "tap-1700000000000")
        assert(r.getStatusCode.value == 200, r.getBody)
        assert(seen.toList == List("tap-1700000000000"), seen.toString)
    }

    /** One WARN line naming the tap and one `reference-would-be-refused` row, the request's own audit entry kept. */
    private def assertReportedOnce(name: String, rule: Int, warns: List[String], attrs: mutable.Map[String, AnyRef]): Unit = {
        assert(warns.count(_.contains(name)) == 1, warns.mkString("\n"))
        val rows = rowsFor(name)
        assert(rows.size == 1, rows.toString)
        val row = rows.head
        assert(row.category == "tap" && row.action == "reference-would-be-refused" && row.outcome == "warning", row.toString)
        assert(row.resourceType.contains("tap"))
        val md = row.metadata.getOrElse(fail("no metadata"))
        assert(md.get("rule").getAsString == rule.toString && !md.get("enforced").getAsBoolean, md.toString)
        assert(!attrs.contains(AuditLog.RecordedAttr), "the request's own audit entry is kept")
    }

    test("rules 5-8 at the companion: nothing is refused; one WARN line and one audit row per tap and rule") {
        val n5 = "r5-" + System.nanoTime()
        val ((a, at5), w5) = withWarns(refusal(rita, TapReferenceRules.Save, newTap(n5, "tap-scripts/unrelated-name_1a2b3c4d.py"), null))
        assert(a.isEmpty)
        assertReportedOnce(n5, 5, w5, at5)

        val n6 = "r6-" + System.nanoTime()
        val ((b, at6), w6) = withWarns(refusal(admin, TapReferenceRules.Save, newTap(n6, "uploads/legacy/x.py"), null))
        assert(b.isEmpty)
        assertReportedOnce(n6, 6, w6, at6)

        val n7 = "r7-" + System.nanoTime()
        val t7 = builderTap.copy(name = n7, secretName = "ada-weather", scriptPath = null)
        val ((c, at7), w7) = withWarns(refusal(builder, TapReferenceRules.Generate, t7, null))
        assert(c.isEmpty)
        assertReportedOnce(n7, 7, w7, at7)

        val n8 = "r8-" + System.nanoTime()
        val t8 = builderTap.copy(name = n8, secretName = "ada-weather")
        val ((d, at8), w8) = withWarns(refusal(builder, TapReferenceRules.Run, t8, t8))
        assert(d.isEmpty)
        assertReportedOnce(n8, 8, w8, at8)
    }

    test("rules 5-8: a second identical report the same day writes no second line or row") {
        val n = "r6-twice-" + System.nanoTime()
        withWarns(refusal(admin, TapReferenceRules.Save, newTap(n, "uploads/legacy/x.py"), null))
        val (_, again) = withWarns(refusal(admin, TapReferenceRules.Save, newTap(n, "uploads/legacy/x.py"), null))
        assert(!again.exists(_.contains(n)), again.mkString("\n"))
        assert(rowsFor(n).size == 1)
    }

    test("rules 5-8: nothing is reported when no rule would refuse") {
        val n = "quiet-" + System.nanoTime()
        for (rk <- limited ++ unrestricted) {
            val (_, warns) = withWarns(refusal(rk, TapReferenceRules.Save, newTap(n, "tap-scripts/" + n + "_1a2b3c4d.py"), null))
            assert(!warns.exists(_.contains(n)), String.valueOf(rk) + ": " + warns)
        }
        assert(rowsFor(n).isEmpty)
    }

    test("the reports never throw, whatever they are given") {
        AuditLog.sinkOverride = (_: AuditEntry) => throw new IllegalStateException("audit sink down")
        assert(
            TapAPIController.referenceRefusal(req(admin)._1, TapReferenceRules.Save, newTap("sink-down", "uploads/x.py"), null, new Counted().lookups).isEmpty
        )
        val failing = Lookups(() => throw new IllegalStateException("mongo down"), _ => throw new IllegalStateException("vault down"), () => RepoPrefix)
        assert(
            TapAPIController.referenceRefusal(req(builder)._1, TapReferenceRules.Run, builderTap.copy(secretName = "ada-weather"), builderTap, failing).isEmpty
        )
    }

    // ================================================================ rules 5-8 at the real entry points: v1.61.0's outcome

    test("rules 5 and 6 at POST /tap: a limited caller's reference naming no readable tap, and an administrator's reference outside the area, still save") {
        val n5 = "save5-" + System.nanoTime()
        val s5 = new Store()
        val (o5, w5) = withWarns(save(rita, s5, newTap(n5, "tap-scripts/unrelated-name_1a2b3c4d.py")))
        assert(o5.isRight && s5.writes.size == 1, o5.toString)
        assert(w5.exists(_.contains(n5)) && rowsFor(n5).size == 1, w5.mkString("\n"))

        val n6 = "save6-" + System.nanoTime()
        val s6 = new Store()
        val (o6, w6) = withWarns(save(admin, s6, newTap(n6, "uploads/legacy/sales-legacy.py")))
        assert(o6.isRight && s6.writes.size == 1 && s6.scriptReads.get >= 1, o6.toString)
        assert(w6.exists(_.contains(n6)) && rowsFor(n6).size == 1, w6.mkString("\n"))
    }

    test("rule 7 at POST /tap: a key limited to what it created saves its tap with a secret another identity created, as on v1.61.0") {
        val n = "save7-" + System.nanoTime()
        val s = new Store(allTaps :+ builderTap.copy(name = n))
        val (out, warns) = withWarns(save(builder, s, builderTap.copy(name = n, secretName = "ada-weather", description = "now with a secret")))
        assert(out.isRight && s.writes.size == 1 && s.writes.head.secretName == "ada-weather", out.toString)
        assert(warns.exists(_.contains(n)) && rowsFor(n).size == 1, warns.mkString("\n"))
    }

    test("rule 7 at POST /tap/generate: the same key generates with that secret, as on v1.61.0") {
        val n = "gen7-" + System.nanoTime()
        val io = TapAPIController.TapAiIO(
            readTap = _ => null,
            readScript = _ => None,
            write = _ => fail("no write"),
            writeVersioned = (_, _, _) => fail("no write"),
            secretType = _ => Some(Some(SecretNames.TapScopeTap)),
            secretOwner = nm => secretOwners.get(nm),
            generate =
                (_, nm, _, _, _) => TapGenerateResult("def fetch():\n    return []", new java.util.ArrayList[String](), "tap-scripts/" + nm + "_1a2b3c4d.py"),
            fix = (_, _, _, _, _, _, _) => fail("not called"),
            review = (_, _, _, _, _, _, _) => fail("not called"),
            optimize = (_, _, _, _, _, _, _) => fail("not called")
        )
        val b = new java.util.HashMap[String, String]()
        b.put("description", "fetch the weather")
        b.put("tapName", n)
        b.put("secretName", "ada-weather")
        val (r, warns) = withWarns(new TapAPIController().generateScriptWith(b, req(builder, "/api/v1/tap/generate")._1, io))
        assert(r.getStatusCode.value == 200, r.getBody)
        assert(JsonParser.parseString(r.getBody).getAsJsonObject.get("scriptPath").getAsString == "tap-scripts/" + n + "_1a2b3c4d.py", r.getBody)
        assert(warns.exists(_.contains(n)) && rowsFor(n).size == 1, warns.mkString("\n"))
    }

    test("rule 6: the tap script store still reads a script outside its prefix, as on v1.61.0 (the read guard is the next release)") {
        val objects = mock(classOf[ObjectStoreUtility])
        when(objects.readBucketObject(eqTo("test-config"), anyString())).thenReturn(Some("def fetch():\n    return []"))
        val store = new MinioScriptStore("tap-scripts/", objects)
        assert(store.readScript(ScriptRef("sales-legacy", "minio", "uploads/legacy/sales-legacy.py")).isDefined)
        assert(store.readScript(ScriptRef("sales-tap", "minio", "tap-scripts/sales-tap_11111111.py")).isDefined)
    }

    test("unchanged: testRefusal's secret check is as on v1.61.0 (a key limited to what it created may not test with another identity's secret)") {
        val (r, _) = req(builder, "/api/v1/tap/test")
        val refused =
            TapAPIController.testRefusal(r, builderTap.copy(secretName = "ada-weather"), n => allTaps.find(_.name == n).orNull, n => secretOwners.get(n))
        assert(refused.isDefined && refused.get.getStatusCode.value == 403)
        assert(
            TapAPIController.testRefusal(r, builderTap.copy(secretName = "builder-secret"), n => allTaps.find(_.name == n).orNull, n => secretOwners.get(n)).isEmpty
        )
    }

    // ================================================================ repository paths

    /** A hidden tap (Finance, for the Sales callers) on a repository path in a folder. */
    private val financeFolder = financeRepoTap.copy(name = "finance-folder", scriptRepoPath = RepoPrefix + "finance/ledger.py")

    private val folderTaps = allTaps :+ financeFolder

    private def folderLookups(prefix: String = RepoPrefix): Lookups = Lookups(() => folderTaps, n => secretOwners.get(n), () => prefix)

    /** Paths that differ from the hidden tap's only in a separator or case. */
    private val similarToHidden = Seq(RepoPrefix + "finance_ledger.py", RepoPrefix + "finance/Ledger.py", RepoPrefix + "Finance/ledger.py")

    /** Tap names that start with the word used for the pipelines folder. */
    private val pipelinesWordNames = Seq("pipelines_sales-orders_transformation", "pipelines", "pipelines-tap", "pipelinesx")

    test("rule 3: two different repository paths are two scripts: a path that only resembles a hidden tap's script passes; that script's own path is refused") {
        for (a <- similarToHidden) assert(!TapReferenceRules.sameRepoScript(a, financeFolder.scriptRepoPath), a)
        assert(TapReferenceRules.sameRepoScript(RepoPrefix + "finance/ledger.py", "/" + RepoPrefix + "finance//ledger.py"))
        for (rk <- Seq(rita, salesKey); site <- Sites3) {
            for (p <- similarToHidden) {
                assert(TapReferenceRules.repoInArea(p, RepoPrefix), p)
                val fs = TapReferenceRules.judge(Some(rk), site, newRepoTap("new-tap", p), null, folderLookups())
                assert(fs.forall(_.rule != 3), rk.label + " " + site + " '" + p + "': " + fs)
            }
            val fs = TapReferenceRules.judge(Some(rk), site, newRepoTap("new-tap", financeFolder.scriptRepoPath), null, folderLookups())
            assert(rule3At(site, fs), rk.label + " " + site + ": " + fs)
            Seq("finance-folder", "Finance", "session:ada").foreach(h => assert(!fs.exists(_.reason.contains(h)), fs.toString))
        }
        // a caller that may read the tap is not refused
        for (site <- Sites3) {
            val fs = TapReferenceRules.judge(Some(fullKey), site, newRepoTap("new-tap", financeFolder.scriptRepoPath), null, folderLookups())
            assert(enforced(fs).isEmpty, site + ": " + fs)
        }
    }

    test("rule 3: a tap whose name starts with the pipelines word is inside the tap script area; the pipelines folder itself is outside") {
        for (n <- pipelinesWordNames) assert(TapReferenceRules.repoInArea(RepoPrefix + n + ".py", RepoPrefix), n)
        for (p <- Seq(RepoPrefix + "pipelines_sales-orders/transformation.py", RepoPrefix + "my_pipelines_tap.py", RepoPrefix + "pipelines.d/x.py"))
            assert(TapReferenceRules.repoInArea(p, RepoPrefix), p)
        for (rk <- limited; site <- Sites3; n <- pipelinesWordNames) {
            val fs = judge(rk, site, newRepoTap(n, RepoPrefix + n + ".py"), null)
            assert(fs.forall(_.rule != 3), rk.label + " " + site + " '" + n + "': " + fs)
        }
        for (p <- Seq(RepoPrefix + "pipelines/sales-orders/transformation.py", RepoPrefix + "pipelines/x.py")) {
            assert(!TapReferenceRules.repoInArea(p, RepoPrefix), p)
            for (rk <- limited; site <- Sites3) assert(rule3At(site, judge(rk, site, newRepoTap("new-tap", p), null)), rk.label + " " + site + " " + p)
        }
    }

    test(
        "rule 3 at POST /tap and POST /tap/test: a limited caller saves and tests a tap whose name starts with the pipelines word, at the path the store writes"
    ) {
        for (rk <- limited; n <- pipelinesWordNames) {
            val body = newRepoTap(n, RepoPrefix + n + ".py")
            val store = new Store()
            val out = save(rk, store, body)
            assert(out.isRight && store.writes.size == 1, rk.label + " '" + n + "': " + out)
            val (r, _) = req(rk, "/api/v1/tap/test")
            assert(
                TapAPIController.referenceRefusal(r, TapReferenceRules.Test, body, null, Lookups(() => allTaps, n => secretOwners.get(n), () => RepoPrefix)).isEmpty,
                rk.label + " " + n
            )
        }
    }

    test(
        "rule 3 at POST /tap and POST /tap/test: a hidden tap's repository script, or a file in the pipelines folder, gets the same refusal, and nothing is read or written"
    ) {
        for (rk <- Seq(rita, salesKey); p <- Seq(financeFolder.scriptRepoPath, RepoPrefix + "pipelines/sales-orders/transformation.py")) {
            val store = new Store(folderTaps)
            val saved = save(rk, store, newRepoTap("repo-tap", p))
            assert(saved.isLeft, rk.label + " '" + p + "': " + saved)
            assertRefusal(rk, "repo-tap", saved.left.get, Seq("finance-folder", "Finance", "session:ada"))
            store.nothingDone()
            val (r, _) = req(rk, "/api/v1/tap/test")
            val t = TapAPIController.referenceRefusal(r, TapReferenceRules.Test, newRepoTap("repo-tap", p), null, folderLookups())
            assert(t.isDefined && t.get.getStatusCode == saved.left.get.getStatusCode && t.get.getBody == saved.left.get.getBody, rk.label + " " + p)
        }
    }

    test("rule 3 at version restore: a hidden tap's repository script is restored as before, with the rule 3 report; a path that only resembles it has none") {
        for (rk <- Seq(rita, salesKey)) {
            val live = newRepoTap("restore-repo-" + System.nanoTime(), RepoPrefix + "own.py")
            val (r, _) = req(rk, "/api/v1/tap/version/restore")
            val out =
                TapAPIController.referenceRefusal(r, TapReferenceRules.Restore, live.copy(scriptRepoPath = financeFolder.scriptRepoPath), live, folderLookups())
            assert(out.isEmpty, rk.label)
            val rule3 = rowsFor(live.name).filter(_.metadata.exists(md => md.get("rule").getAsString == "3"))
            assert(rule3.size == 1 && !rule3.head.metadata.get.get("enforced").getAsBoolean, rowsFor(live.name).toString)
            val other = newRepoTap("restore-similar-" + System.nanoTime(), RepoPrefix + "own.py")
            val (r2, _) = req(rk, "/api/v1/tap/version/restore")
            assert(
                TapAPIController.referenceRefusal(r2, TapReferenceRules.Restore, other.copy(scriptRepoPath = similarToHidden.head), other, folderLookups()).isEmpty
            )
            assert(!rowsFor(other.name).exists(_.metadata.exists(md => md.get("rule").getAsString == "3")), rowsFor(other.name).toString)
        }
    }

    /** Path prefixes with an empty or dot segment: saved as is by the settings, working on v1.61.0. */
    private val oddPrefixes = Seq("/datris-taps", "//datris-taps/", "datris-taps//", "./datris-taps/", "org//taps/", "/", "./")

    test(
        "rule 3: on an install whose path prefix has an empty or dot segment, the references the store writes, and the prefix in normalised form, are inside the area at save and test"
    ) {
        for (prefix <- oddPrefixes) {
            val written = TapReferenceRules.normalizePrefix(prefix) + "new-tap.py"
            val canonical = Option(TapReferenceRules.normalizedRef(prefix)).filter(_.nonEmpty).map(_ + "/").getOrElse("") + "new-tap.py"
            for (p <- Seq(written, canonical)) {
                assert(TapReferenceRules.repoInArea(p, TapReferenceRules.normalizePrefix(prefix)), prefix + " " + p)
                for (rk <- limited; site <- EnforcedSites) {
                    val lk = Lookups(() => allTaps, n => secretOwners.get(n), () => TapReferenceRules.normalizePrefix(prefix))
                    val fs = TapReferenceRules.judge(Some(rk), site, newRepoTap("new-tap", p), null, lk)
                    assert(fs.isEmpty, rk.label + " " + site + " prefix '" + prefix + "' '" + p + "': " + fs)
                }
            }
        }
    }

    test("rule 3: on such an install, an empty or dot segment after the prefix, or the pipelines folder, is still outside the area") {
        for (prefix <- oddPrefixes) {
            val pre = TapReferenceRules.normalizePrefix(prefix)
            val outside = Seq(
                pre + "/new-tap.py",
                pre + "./new-tap.py",
                pre + "sub/../new-tap.py",
                pre + "new-tap.py/",
                pre + "pipelines/sales-orders/transformation.py"
            )
            assert(TapReferenceRules.repoInArea(pre + "pipelines_sales-orders_transformation.py", pre), prefix)
            for (p <- outside; rk <- limited; site <- EnforcedSites) {
                val lk = Lookups(() => allTaps, n => secretOwners.get(n), () => pre)
                val fs = TapReferenceRules.judge(Some(rk), site, newRepoTap("new-tap", p), null, lk)
                assert(rule3At(site, fs), rk.label + " " + site + " prefix '" + prefix + "' '" + p + "': " + fs)
            }
        }
    }

    test("rule 3 at POST /tap and POST /tap/test: on such an install a limited caller saves and tests the reference the store wrote, as on v1.61.0") {
        for (prefix <- oddPrefixes; rk <- limited) {
            val pre = TapReferenceRules.normalizePrefix(prefix)
            val body = newRepoTap("odd-prefix-tap", pre + "odd-prefix-tap.py")
            val store = new Store()
            val io = store.io.copy(repoPathPrefix = () => pre)
            val out = new TapAPIController().saveTapInternalWith(body, null, req(rk)._1, restored = false, io)
            assert(out.isRight && store.writes.size == 1, rk.label + " prefix '" + prefix + "': " + out)
            val (r, _) = req(rk, "/api/v1/tap/test")
            val lk = Lookups(() => allTaps, n => secretOwners.get(n), () => pre)
            assert(TapAPIController.referenceRefusal(r, TapReferenceRules.Test, body, null, lk).isEmpty, rk.label + " prefix '" + prefix + "'")
        }
    }

    test("POST /tap/script: a tap name whose script path would have an empty, '.' or '..' segment is a 400 for every caller, before anything is read") {
        ScopeDenialFixtures.install(env.copy(useApiKeys = false))
        try {
            for (rk <- limited ++ unrestricted; name <- Seq("../x", "a//b", "/x", "./x", "a/./b", "a/../b")) {
                val body = new java.util.HashMap[String, String]()
                body.put("tapName", name)
                body.put("script", "def fetch():\n    return []")
                val r = new TapAPIController().storeScript(null, body, req(rk, "/api/v1/tap/script")._1)
                assert(r.getStatusCode.value == 400, Option(rk).map(_.label) + " '" + name + "': " + r)
                assert(JsonParser.parseString(r.getBody).getAsJsonObject.get("error").getAsString.startsWith("Invalid tap name"), r.getBody)
            }
        } finally ScopeDenialFixtures.install(env)
    }

    test("rules 5-8: the once-a-day bound is kept per environment") {
        val n = "r6-env-" + System.nanoTime()
        val (_, first) = withWarns(refusal(admin, TapReferenceRules.Save, newTap(n, "uploads/legacy/x.py"), null))
        assert(first.exists(_.contains(n)), first.mkString("\n"))
        ScopeDenialFixtures.install(env.copy(environment = "test-other"))
        try {
            val (_, other) = withWarns(refusal(admin, TapReferenceRules.Save, newTap(n, "uploads/legacy/x.py"), null))
            assert(other.exists(_.contains(n)), "another environment reports it too: " + other.mkString("\n"))
            val (_, again) = withWarns(refusal(admin, TapReferenceRules.Save, newTap(n, "uploads/legacy/x.py"), null))
            assert(!again.exists(_.contains(n)), again.mkString("\n"))
        } finally ScopeDenialFixtures.install(env)
        assert(rowsFor(n).size == 2, rowsFor(n).toString)
    }

    // ================================================================ story: tap-script-store-writes-and-deletes-in-scope, rule S3
    // The pipelines folder name itself, with no separator after it, names a
    // folder and never a tap script: it is outside the tap script area. A
    // file whose name only starts with the pipelines word stays inside.

    test("S3: the pipelines folder name with no separator after it is outside the tap script area; a file named like it stays inside") {
        for (prefix <- Seq(RepoPrefix, "") ++ oddPrefixes) {
            val pre = TapReferenceRules.normalizePrefix(prefix)
            assert(!TapReferenceRules.repoInArea(pre + "pipelines", pre), "'" + prefix + "'")
            for (inside <- Seq("pipelines-x.py", "pipelines.py", "pipelines_x.py", "pipelinesx"))
                assert(TapReferenceRules.repoInArea(pre + inside, pre), "'" + prefix + "' " + inside)
        }
    }

    test("S3: a limited caller's new reference to the pipelines folder name is refused at save and test, reported at restore") {
        for (rk <- limited; site <- Sites3)
            assert(rule3At(site, judge(rk, site, newRepoTap("new-tap", RepoPrefix + "pipelines"), null)), rk.label + " " + site)
        for (rk <- limited) {
            val store = new Store()
            val out = save(rk, store, newRepoTap("new-tap", RepoPrefix + "pipelines"))
            assert(out.isLeft, rk.label + ": " + out)
            assertRefusal(rk, "new-tap", out.left.get, hiddenNamesFor(rk))
            store.nothingDone()
            val ok = new Store()
            assert(save(rk, ok, newRepoTap("pipelines-x", RepoPrefix + "pipelines-x.py")).isRight, rk.label)
        }
    }
}
