package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model._
import com.google.gson.{JsonElement, JsonParser}
import org.scalatest.funsuite.AnyFunSuite

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.{ZipEntry, ZipOutputStream}
import scala.collection.JavaConverters._
import scala.collection.mutable

/** Story: Catalog backup and restore: zip export/restore endpoints and MCP tools
  * (plans/stories/catalog-backup-restore.md), Acceptance bullets 1-7.
  *
  * API this spec pins (pure, no Mongo, no network — `CatalogArchive` is the
  * story's "pure archive model + (un)packing with no IO"):
  *
  * {{{
  * object CatalogArchive {
  *   val FormatVersion: Int = 1
  *
  *   case class RequiredSecret(name: String, usedBy: Seq[String], kind: String)
  *   case class Manifest(formatVersion: Int, catalog: String, exportedAt: String, datrisVersion: String,
  *                       taps: Seq[String], pipelines: Seq[String],
  *                       requiredSecrets: Seq[RequiredSecret], warnings: Seq[String])
  *   case class Entry(path: String, bytes: Array[Byte])
  *
  *   def stripTapForExport(t: TapConfig): TapConfig
  *   def stripPipelineForExport(p: PipelineConfig): PipelineConfig
  *   def requiredSecrets(taps: Seq[TapConfig], pipelines: Seq[PipelineConfig]): Seq[RequiredSecret]
  *
  *   /** Every install-specific value of one pipeline, as
  *     * "<pipeline>: <field>=<value> may not be valid on another install". */
  *   def installWarnings(p: PipelineConfig): Seq[String]
  *
  *   /** The archive layout: manifest.json, taps/<name>.json, taps/<name>.py,
  *     * taps/<name>.state.json, pipelines/<name>.json,
  *     * pipelines/<name>/validation-schema/<file>, pipelines/<name>/codegen/<kind>.py. */
  *   def archiveEntries(manifest: Manifest, taps: Seq[TapConfig], pipelines: Seq[PipelineConfig],
  *                      scripts: Map[String, String] = Map.empty,          // tap -> script text
  *                      states: Map[String, String] = Map.empty,           // tap -> state JSON
  *                      validationSchemas: Map[String, Map[String, Array[Byte]]] = Map.empty, // pipeline -> file -> bytes
  *                      codegen: Map[String, Map[String, String]] = Map.empty): Seq[Entry]  // pipeline -> kind -> script
  *
  *   def pack(entries: Seq[Entry]): Array[Byte]
  *   /** Left on a bad zip, a `..`/leading-`/` entry, > 1000 entries, > 50 MB
  *     * uncompressed, a missing manifest or a formatVersion above FormatVersion. */
  *   def unpack(bytes: Array[Byte]): Either[String, Map[String, Array[Byte]]]
  *
  *   /** Names in the archive that already exist ANYWHERE on the install
  *     * (tap-vs-tap, pipeline-vs-pipeline, cross-type). mode "new" | "overwrite";
  *     * overwrite does not report a name already a member of `target`. Each
  *     * clash's toString names the clashing item. */
  *   def clashes(manifest: Manifest, allTaps: Seq[TapConfig], allPipelines: Seq[PipelineConfig],
  *               target: String, mode: String = "new"): Seq[_]
  *
  *   /** GitHub-backed restore target: compare each archived tap script with the
  *     * file at branch head (`fileAt(cfg, GithubCodeStore.scriptRepoPath(tap, cfg), cfg.branch)`,
  *     * the same seam GithubScriptStore takes for GithubClient.getFile), and each
  *     * archived AI script (pipeline -> kind -> text, dataQuality / transformation
  *     * only) with the file at
  *     * `GithubCodeStore.scriptRepoPath(ScriptFamily.Pipeline(p, kind), cfg)`.
  *     * codegenReuse / codegenWrite are keyed "<p>/<kind>". */
  *   case class RepoScriptPlan(clashes: Seq[String], reuse: Set[String], write: Set[String],
  *                             codegenReuse: Set[String], codegenWrite: Set[String])
  *   def planRepoScripts(scripts: Map[String, String], cfg: CodeRepoConfig, mode: String,
  *                       targetMembers: Set[String],
  *                       fileAt: (CodeRepoConfig, String, String) => Option[RepoFile],
  *                       codegenScripts: Map[String, Map[String, String]],
  *                       targetPipelineMembers: Set[String]): RepoScriptPlan
  *
  *   /** Which archived tap states the restore writes (tap -> state JSON):
  *     * none when restoreState is false; never a tap that existed before. */
  *   def statesToRestore(entries: Map[String, Array[Byte]], restoreState: Boolean,
  *                       preexistingTaps: Set[String]): Map[String, String]
  * }
  * }}}
  */
class CatalogArchiveSpec extends AnyFunSuite {
    import CatalogArchive._

    private def utf8(s: String): Array[Byte] = s.getBytes(StandardCharsets.UTF_8)
    private def str(b: Array[Byte]): String = new String(b, StandardCharsets.UTF_8)

    private def fullTap(storage: String): TapConfig = TapConfig(
        name = "orders",
        description = "orders feed",
        scriptPath = "taps/orders.py",
        targetPipeline = "orders_p",
        secretName = "orders_api",
        cronExpression = "0 0 * * * ?",
        enabled = true,
        lastRunStatus = "success",
        lastRunTime = "2026-10-01 00:00:00",
        lastRunRecordCount = 42,
        lastRunError = "boom",
        lastRunDataType = "csv",
        lastRunColumns = java.util.Arrays.asList("a", "b"),
        lastTestRunStatus = "success",
        lastTestRunTime = "2026-09-30 00:00:00",
        lastTestRunRecordCount = 5,
        lastTestRunError = "test boom",
        lastTestRunDataType = "csv",
        lastTestRunColumns = java.util.Arrays.asList("a"),
        createdAt = "2026-09-01",
        updatedAt = "2026-09-02",
        catalog = "sales",
        createdByKeyLabel = "agent-key",
        version = 7,
        lastRunTrigger = "cron",
        lastRunRetrySafe = true,
        retryCount = 3,
        scriptStorage = storage,
        scriptRepoPath = if (storage == "github") "taps/orders.py" else null,
        scriptCommitSha = if (storage == "github") "abc123" else null,
        tags = java.util.Arrays.asList("finance"),
        source = "example source",
        lastTestRunScriptId = if (storage == "github") "gh:abc123" else "sha256:deadbeef"
    )

    private def tap(name: String, catalog: String = null, secretName: String = null): TapConfig =
        TapConfig(name = name, description = null, targetPipeline = null, catalog = catalog, secretName = secretName)

    private def pipeline(name: String, catalog: String = null): PipelineConfig =
        PipelineConfig(name = name, catalog = catalog)

    private def manifest(taps: Seq[String] = Nil, pipelines: Seq[String] = Nil, secrets: Seq[RequiredSecret] = Nil): CatalogArchive.Manifest =
        CatalogArchive.Manifest(
            formatVersion = FormatVersion,
            catalog = "sales",
            exportedAt = "2026-10-12 10:00:00",
            datrisVersion = "1.47.2",
            taps = taps,
            pipelines = pipelines,
            requiredSecrets = secrets,
            warnings = Nil
        )

    /** A hand-built zip (hostile archives come from outside, not from `pack`). */
    private def rawZip(entries: Seq[(String, Array[Byte])]): Array[Byte] = {
        val bos = new ByteArrayOutputStream()
        val zos = new ZipOutputStream(bos)
        entries.foreach { case (path, bytes) =>
            zos.putNextEntry(new ZipEntry(path))
            zos.write(bytes)
            zos.closeEntry()
        }
        zos.close()
        bos.toByteArray
    }

    private val validManifestJson = utf8("""{"formatVersion":1,"catalog":"sales","taps":[],"pipelines":[],"requiredSecrets":[],"warnings":[]}""")

    // ------------------------------------------------------ Acceptance bullet 1

    test("stripTapForExport clears run status, ownership and script refs; keeps cron, secret, tags and the minio test-run id") {
        val s = stripTapForExport(fullTap("minio"))
        assert(s.lastRunStatus == null)
        assert(s.lastRunTime == null)
        assert(s.lastRunRecordCount == 0)
        assert(s.lastRunError == null)
        assert(s.lastRunDataType == null)
        assert(s.lastRunColumns == null)
        assert(s.lastRunTrigger == null)
        assert(!s.lastRunRetrySafe)
        assert(s.lastTestRunStatus == null)
        assert(s.lastTestRunTime == null)
        assert(s.lastTestRunRecordCount == 0)
        assert(s.lastTestRunError == null)
        assert(s.lastTestRunDataType == null)
        assert(s.lastTestRunColumns == null)
        assert(s.retryCount == 0)
        assert(s.createdByKeyLabel == null)
        assert(s.createdAt == null)
        assert(s.updatedAt == null)
        assert(s.scriptPath == null)
        assert(s.scriptRepoPath == null)
        assert(s.scriptCommitSha == null)
        assert(s.scriptStorage == null)

        assert(s.name == "orders")
        assert(s.cronExpression == "0 0 * * * ?")
        assert(s.secretName == "orders_api")
        assert(s.tags.asScala == Seq("finance"))
        assert(s.source == "example source")
        assert(s.lastTestRunScriptId == "sha256:deadbeef", "minio test-run id survives: same bytes give the same id")
    }

    test("stripTapForExport keeps lastTestRunScriptId for a legacy tap with no storage field") {
        assert(stripTapForExport(fullTap(null)).lastTestRunScriptId == "sha256:deadbeef")
    }

    test("stripTapForExport nulls lastTestRunScriptId and the repo refs for a github tap") {
        val s = stripTapForExport(fullTap("github"))
        assert(s.lastTestRunScriptId == null, "a gh:<sha> id is meaningless on another repo")
        assert(s.scriptRepoPath == null)
        assert(s.scriptCommitSha == null)
        assert(s.scriptStorage == null)
        assert(s.lastRunStatus == null && s.lastTestRunStatus == null)
        assert(s.createdByKeyLabel == null)
        assert(s.cronExpression == "0 0 * * * ?")
        assert(s.secretName == "orders_api")
    }

    // ------------------------------------------------------ Acceptance bullet 2

    private val secretTaps = Seq(
        tap("t1", "sales", secretName = "shared_sec"),
        tap("t2", "sales", secretName = "tap_api"),
        tap("t3", "sales", secretName = "tap_api")
    )

    private val secretPipelines = Seq(
        PipelineConfig(
            name = "p1",
            catalog = "sales",
            destination = Destination(objectStore = ObjectStore(provider = "s3", credentialsSecret = "shared_sec")),
            unityCatalog = UnityCatalogSync(enabled = true, credentialsSecret = "uc_sec")
        ),
        PipelineConfig(
            name = "p2",
            catalog = "sales",
            destination = Destination(
                qdrant = QdrantConfig(
                    collectionName = "c",
                    chunking = null,
                    metadata = null,
                    embeddingSecretName = null,
                    qdrantSecretName = "qdrant_sec"
                )
            )
        )
    )

    test("requiredSecrets finds tap, objectStore, qdrant and Unity Catalog secrets, de-duplicated with usedBy lists") {
        val req = requiredSecrets(secretTaps, secretPipelines)
        val byName = req.map(r => r.name -> r).toMap
        assert(req.map(_.name).distinct.size == req.size, "one row per secret name: " + req)
        assert(Set("tap_api", "shared_sec", "qdrant_sec", "uc_sec").subsetOf(byName.keySet), req.toString)
        assert(byName("tap_api").usedBy.toSet == Set("t2", "t3"))
        assert(byName("shared_sec").usedBy.toSet == Set("t1", "p1"))
        assert(byName("qdrant_sec").usedBy.toSet == Set("p2"))
        assert(byName("uc_sec").usedBy.toSet == Set("p1"))
        assert(!byName.contains(null) && !byName.contains(""), "a null secret field is not a required secret")
    }

    private def keysIn(e: JsonElement): Seq[String] =
        if (e.isJsonObject) e.getAsJsonObject.entrySet().asScala.toSeq.flatMap(en => en.getKey +: keysIn(en.getValue))
        else if (e.isJsonArray) e.getAsJsonArray.asScala.toSeq.flatMap(keysIn)
        else Nil

    test("a packed archive has no secrets/ entry and its manifest has no value keys") {
        val m = manifest(Seq("t1", "t2", "t3"), Seq("p1", "p2"), requiredSecrets(secretTaps, secretPipelines))
        val entries = archiveEntries(
            m,
            secretTaps.map(stripTapForExport),
            secretPipelines.map(stripPipelineForExport),
            scripts = Map("t1" -> "print(1)")
        )
        val unpacked = unpack(pack(entries)).fold(e => fail("unpack failed: " + e), identity)
        assert(unpacked.contains("manifest.json"))
        assert(unpacked.contains("taps/t1.json") && unpacked.contains("taps/t1.py") && unpacked.contains("pipelines/p1.json"), unpacked.keySet)
        assert(!unpacked.keys.exists(k => k.startsWith("secrets/") || k.contains("/secrets/")), unpacked.keySet)

        val manifestJson = JsonParser.parseString(str(unpacked("manifest.json")))
        val keys = keysIn(manifestJson)
        assert(!keys.contains("value"), "manifest keys: " + keys.distinct)
        assert(!keys.contains("values"), "manifest keys: " + keys.distinct)
        assert(manifestJson.getAsJsonObject.get("formatVersion").getAsInt == 1)
        val names = manifestJson.getAsJsonObject.getAsJsonArray("requiredSecrets").asScala.map(_.getAsJsonObject.get("name").getAsString).toSet
        assert(names == Set("tap_api", "shared_sec", "qdrant_sec", "uc_sec"))
    }

    // Reviewer finding 1: inline credentials in a pipeline never reach the archive.
    test("stripPipelineForExport removes inline REST credentials and the archive bytes do not contain them") {
        val p = PipelineConfig(
            name = "rest_p",
            catalog = "sales",
            preprocessor = RestEndpoint(endpoint = "https://pre.example.com", bearerToken = "PRE-TOKEN-123", apiKey = "PRE-KEY-456"),
            transformation = Transformation(rowFunctions =
                java.util.Arrays.asList(
                    RowFunction("restEndpoint", java.util.Arrays.asList("https://fn.example.com", "row", "30000", "FN-TOKEN-789", "FN-KEY-012"))
                )
            ),
            destination =
                Destination(restEndpoint = RestEndpoint(endpoint = "https://dest.example.com", bearerToken = "DEST-TOKEN-345", apiKey = "DEST-KEY-678"))
        )
        val secrets = Seq("PRE-TOKEN-123", "PRE-KEY-456", "FN-TOKEN-789", "FN-KEY-012", "DEST-TOKEN-345", "DEST-KEY-678")
        val bytes = pack(archiveEntries(manifest(pipelines = Seq("rest_p")), Nil, Seq(stripPipelineForExport(p))))
        val all = unpack(bytes).fold(e => fail(e), identity).values.map(str).mkString("\n")
        secrets.foreach(s => assert(!all.contains(s), s + " leaked into the archive"))
        val s = stripPipelineForExport(p)
        assert(s.preprocessor.endpoint == "https://pre.example.com")
        assert(s.transformation.rowFunctions.get(0).parameters.get(0) == "https://fn.example.com")
        val w = redactionWarnings(p)
        assert(w.size == 6, w)
        assert(w.forall(x => x.startsWith("rest_p: ") && x.contains("was removed from the archive; re-enter it after restore")), w)
        assert(w.exists(_.contains("preprocessor.bearerToken")) && w.exists(_.contains("destination.restEndpoint.apiKey")), w)
        assert(redactionWarnings(pipeline("bare")).isEmpty)
    }

    // Reviewer finding 2: overwriting an existing item is checked against its live owner.
    test("restoreScopeChecks checks create as the caller, plus update against the live owner of an existing item") {
        assert(restoreScopeChecks("sales", "keyA", None) == Seq("create" -> Map("catalog" -> "sales", "owner" -> "keyA")))
        val existing = restoreScopeChecks("sales", "keyA", Some(("sales", "keyB")))
        assert(existing.contains("update" -> Map("catalog" -> "sales", "owner" -> "keyB")), existing)
        assert(existing.contains("create" -> Map("catalog" -> "sales", "owner" -> "keyA")), existing)
    }

    // Reviewer round 2, finding 1: missingSecrets is no secret-existence oracle.
    test("secretCheck reports present or missing only where the caller may read secrets") {
        val fields = new java.util.HashMap[String, String]()
        fields.put("_type", "platform")
        val yes: java.util.Map[String, String] => Boolean = _ => true
        val no: java.util.Map[String, String] => Boolean = _ => false
        assert(secretCheck(Some(Some(fields)), yes, canReadUntyped = true) == SecretCheck.Present)
        assert(secretCheck(Some(None), yes, canReadUntyped = true) == SecretCheck.Missing)
        // A scoped key: an unreadable existing secret and a missing one look the same.
        assert(secretCheck(Some(Some(fields)), no, canReadUntyped = false) == SecretCheck.Unchecked)
        assert(secretCheck(Some(None), no, canReadUntyped = false) == SecretCheck.Unchecked)
        assert(secretCheck(None, yes, canReadUntyped = true) == SecretCheck.Unchecked)
        assert(secretNotCheckedWarning("codegen") == "codegen: not checked, this key may not read secrets")
    }

    // E2E finding: a missing config for the SECOND listed tap/pipeline is a
    // Left from readContents, never an exception (Scala 2.12 lazy Stream +
    // non-local return escaped into the controller as a 500).
    test("readContents returns Left when a later manifest tap or pipeline has no config entry") {
        val m = utf8("""{"formatVersion":1,"catalog":"sales","taps":["first_tap","ghost_tap"],"pipelines":[],"requiredSecrets":[],"warnings":[]}""")
        val zip = rawZip(Seq("manifest.json" -> m, "taps/first_tap.json" -> utf8(new com.google.gson.Gson().toJson(tap("first_tap", "sales")))))
        val entries = unpack(zip).fold(e => fail(e), identity)
        val mf = parseManifest(entries("manifest.json")).fold(e => fail(e), identity)
        val result = readContents(mf, entries)
        assert(result == Left("Archive is missing taps/ghost_tap.json"), result)

        val mp = utf8("""{"formatVersion":1,"catalog":"sales","taps":[],"pipelines":["p1","ghost_p"],"requiredSecrets":[],"warnings":[]}""")
        val zip2 = rawZip(Seq("manifest.json" -> mp, "pipelines/p1.json" -> utf8(new com.google.gson.Gson().toJson(pipeline("p1", "sales")))))
        val entries2 = unpack(zip2).fold(e => fail(e), identity)
        val result2 = readContents(parseManifest(entries2("manifest.json")).fold(e => fail(e), identity), entries2)
        assert(result2 == Left("Archive is missing pipelines/ghost_p.json"), result2)

        val ok = readContents(mf, entries + ("taps/ghost_tap.json" -> utf8(new com.google.gson.Gson().toJson(tap("ghost_tap", "sales")))))
        assert(ok.isRight && ok.right.get.taps.map(_.name) == Seq("first_tap", "ghost_tap"), ok)
    }

    // ------------------------------------------------------ Acceptance bullet 3

    test("unpack rejects an entry that climbs out with ..") {
        val zip = rawZip(Seq("manifest.json" -> validManifestJson, "../x.json" -> utf8("{}")))
        assert(unpack(zip).isLeft)
    }

    test("unpack rejects an absolute entry path") {
        val zip = rawZip(Seq("manifest.json" -> validManifestJson, "/etc/passwd" -> utf8("root:x:0:0")))
        assert(unpack(zip).isLeft)
    }

    test("unpack rejects an archive with more than 1000 entries") {
        val many = ("manifest.json" -> validManifestJson) +: (1 to 1000).map(i => s"taps/t$i.json" -> utf8("{}"))
        assert(many.size == 1001)
        assert(unpack(rawZip(many)).isLeft)
    }

    test("unpack rejects formatVersion 2") {
        val v2 = utf8("""{"formatVersion":2,"catalog":"sales","taps":[],"pipelines":[],"requiredSecrets":[],"warnings":[]}""")
        val result = unpack(rawZip(Seq("manifest.json" -> v2)))
        assert(result.isLeft)
        assert(result.left.get.contains("2") || result.left.get.toLowerCase.contains("version"), result.left.get)
    }

    test("unpack rejects bytes that are not a zip") {
        assert(unpack(utf8("not a zip at all")).isLeft)
    }

    test("pack then unpack round-trips every entry byte for byte") {
        val binary = Array.tabulate[Byte](512)(i => (i * 31).toByte)
        val entries = archiveEntries(
            manifest(Seq("orders"), Seq("orders_p")),
            Seq(stripTapForExport(fullTap("minio"))),
            Seq(stripPipelineForExport(pipeline("orders_p", "sales"))),
            scripts = Map("orders" -> "import os\nprint('héllo')\n"),
            states = Map("orders" -> """{"cursor":"2026-10-01"}"""),
            validationSchemas = Map("orders_p" -> Map("schema.json" -> binary)),
            codegen = Map("orders_p" -> Map("dataQuality" -> "def check(r): return True\n"))
        )
        val back = unpack(pack(entries)).fold(e => fail("unpack failed: " + e), identity)
        assert(back.keySet == entries.map(_.path).toSet)
        entries.foreach(e => assert(back(e.path).sameElements(e.bytes), e.path))
        assert(back.contains("taps/orders.state.json"))
        assert(back.contains("pipelines/orders_p/validation-schema/schema.json"))
        assert(str(back("taps/orders.py")) == "import os\nprint('héllo')\n")
    }

    // ------------------------------------------------------ Acceptance bullet 4

    private def show(cs: Seq[_]): Seq[String] = cs.map(_.toString)

    /** Whole-word match, so "orders" does not match "orders_p". */
    private def mentions(cs: Seq[String], name: String): Boolean =
        cs.exists(c => ("(?<![A-Za-z0-9_-])" + java.util.regex.Pattern.quote(name) + "(?![A-Za-z0-9_-])").r.findFirstIn(c).isDefined)

    test("clashes reports an archived tap whose name is a pipeline in another catalog") {
        val m = manifest(taps = Seq("orders"), pipelines = Seq("orders_p"))
        val installTaps = Seq(tap("unrelated", "hr"))
        val installPipelines = Seq(pipeline("orders", "hr"))
        val cs = show(clashes(m, installTaps, installPipelines, "sales_copy"))
        assert(mentions(cs, "orders"), cs)
        assert(!mentions(cs, "orders_p"), "orders_p exists nowhere: " + cs)
        assert(!mentions(cs, "unrelated"), cs)
    }

    test("clashes reports tap-vs-tap and pipeline-vs-pipeline across the whole install") {
        val m = manifest(taps = Seq("orders"), pipelines = Seq("orders_p"))
        val cs = show(clashes(m, Seq(tap("orders", "hr")), Seq(pipeline("orders_p", null)), "sales_copy"))
        assert(mentions(cs, "orders"), cs)
        assert(mentions(cs, "orders_p"), cs)
    }

    test("overwrite mode does not report a member already in the target catalog, but still reports one in another catalog") {
        val m = manifest(taps = Seq("orders", "refunds"), pipelines = Seq("orders_p"))
        val installTaps = Seq(tap("orders", "sales"), tap("refunds", "hr"))
        val installPipelines = Seq(pipeline("orders_p", "sales"))
        val over = show(clashes(m, installTaps, installPipelines, "sales", "overwrite"))
        assert(!mentions(over, "orders") && !mentions(over, "orders_p"), "members of the target are overwritable: " + over)
        assert(mentions(over, "refunds"), "a name in ANOTHER catalog is still a clash: " + over)

        val fresh = show(clashes(m, installTaps, installPipelines, "sales", "new"))
        assert(mentions(fresh, "orders"), "mode=new never merges: " + fresh)
    }

    // ------------------------------------------------------ Acceptance bullet 5

    test("installWarnings names the objectStore endpoint and bucket, warehouse and an absolute s3:// validation schema") {
        val p = PipelineConfig(
            name = "orders_p",
            dataQuality = DataQuality(validationSchema = "s3://acme-bucket/schemas/orders.json"),
            destination = Destination(
                objectStore = ObjectStore(
                    provider = "s3",
                    endpoint = "https://s3.eu-west-1.example.com",
                    destinationBucketOverride = "acme-landing"
                ),
                database = Database(dbName = "analytics", warehouse = "COMPUTE_WH", useSnowflake = true)
            )
        )
        val w = installWarnings(p)
        def named(value: String): Boolean = w.exists(x => x.startsWith("orders_p: ") && x.contains(value))
        assert(named("https://s3.eu-west-1.example.com"), w)
        assert(w.exists(x => x.contains("endpoint") && x.contains("https://s3.eu-west-1.example.com")), w)
        assert(named("acme-landing"), w)
        assert(named("COMPUTE_WH"), w)
        assert(w.exists(x => x.contains("warehouse") && x.contains("COMPUTE_WH")), w)
        assert(named("s3://acme-bucket/schemas/orders.json"), w)
        assert(w.forall(_.contains("may not be valid on another install")), w)
    }

    test("installWarnings says nothing for a pipeline with no install-specific values") {
        val plain = PipelineConfig(
            name = "plain_p",
            dataQuality = DataQuality(validationSchema = "orders.json"),
            destination = Destination(database = Database(dbName = "datris", table = "orders", usePostgres = true))
        )
        assert(installWarnings(plain).isEmpty, installWarnings(plain))
        assert(installWarnings(pipeline("bare")).isEmpty)
    }

    // ------------------------------------------------------ Acceptance bullet 6

    private val repoCfg = CodeRepoConfig(repo = "acme/scripts", branch = "main", pathPrefix = "taps/")

    private class StubRepo(files: Map[String, String]) {
        val calls = mutable.ArrayBuffer.empty[(String, String)]
        val fileAt: (CodeRepoConfig, String, String) => Option[RepoFile] = (_, path, ref) => {
            calls += (path -> ref)
            files.get(path).map(c => RepoFile(c, "blob-" + c.hashCode, "head-sha"))
        }
    }

    test("GitHub target: a script already at branch head with different bytes is a clash in mode=new and nothing is written") {
        val repo = new StubRepo(Map("taps/orders.py" -> "print('theirs')"))
        val plan = planRepoScripts(Map("orders" -> "print('ours')"), repoCfg, "new", Set.empty, repo.fileAt, Map.empty, Set.empty)
        assert(mentions(plan.clashes, "orders"), plan)
        assert(plan.write.isEmpty, "nothing is committed when there is a clash: " + plan)
        assert(plan.reuse.isEmpty, plan)
        assert(repo.calls.contains("taps/orders.py" -> "main"), "checked at branch head: " + repo.calls)
    }

    test("GitHub target: identical bytes at branch head are reused without a new commit") {
        val repo = new StubRepo(Map("taps/orders.py" -> "print('same')"))
        val plan = planRepoScripts(Map("orders" -> "print('same')"), repoCfg, "new", Set.empty, repo.fileAt, Map.empty, Set.empty)
        assert(plan.clashes.isEmpty, plan)
        assert(plan.reuse == Set("orders"), plan)
        assert(plan.write.isEmpty, "identical bytes need no commit: " + plan)
    }

    test("GitHub target: an absent file is written; overwrite of a target member with different bytes is written, a non-member still clashes") {
        val repo = new StubRepo(Map("taps/orders.py" -> "old", "taps/refunds.py" -> "theirs"))
        val fresh = planRepoScripts(Map("new_tap" -> "print(1)"), repoCfg, "new", Set.empty, repo.fileAt, Map.empty, Set.empty)
        assert(fresh.write == Set("new_tap") && fresh.clashes.isEmpty, fresh)

        val over = planRepoScripts(Map("orders" -> "new", "refunds" -> "ours"), repoCfg, "overwrite", Set("orders"), repo.fileAt, Map.empty, Set.empty)
        assert(over.write.contains("orders"), over)
        assert(mentions(over.clashes, "refunds"), over)
        assert(!over.write.contains("refunds"), over)
    }

    // Story: Restore refuses a new pipeline whose AI script path holds a foreign
    // file (plans/stories/multi-env-3b-restore-ai-script-head-check.md).
    // planRepoScripts(scripts, cfg, mode, targetMembers, fileAt, codegenScripts,
    // targetPipelineMembers) also compares each archived AI script
    // (pipeline -> kind -> text) with the file at
    // GithubCodeStore.scriptRepoPath(ScriptFamily.Pipeline(p, kind), cfg);
    // RepoScriptPlan.codegenReuse / codegenWrite are keyed "<p>/<kind>".

    private val aiPath = "taps/pipelines/orders/data-quality.py"
    private val aiClash =
        "pipeline script 'orders/dataQuality': script file '" + aiPath +
            "' already exists in the code repository (branch main) with different content"

    test("planRepoScripts: an AI script identical at branch head is pinned (codegenReuse) and is no clash") {
        val repo = new StubRepo(Map(aiPath -> "print('same dq')\n"))
        val plan = planRepoScripts(
            Map.empty,
            repoCfg,
            "new",
            Set.empty,
            repo.fileAt,
            codegenScripts = Map("orders" -> Map("dataQuality" -> "print('same dq')")),
            targetPipelineMembers = Set.empty
        )
        assert(plan.clashes.isEmpty, plan)
        assert(plan.codegenReuse == Set("orders/dataQuality"), plan)
        assert(plan.codegenWrite.isEmpty, "identical bytes need no commit: " + plan)
        assert(plan.reuse.isEmpty && plan.write.isEmpty, "reuse / write stay tap-only: " + plan)
        assert(repo.calls.contains(aiPath -> "main"), "checked at branch head: " + repo.calls)
    }

    test("planRepoScripts: an AI script missing at branch head is written (codegenWrite) and is no clash") {
        val repo = new StubRepo(Map.empty)
        val plan = planRepoScripts(
            Map.empty,
            repoCfg,
            "new",
            Set.empty,
            repo.fileAt,
            codegenScripts = Map("orders" -> Map("dataQuality" -> "print('dq')")),
            targetPipelineMembers = Set.empty
        )
        assert(plan.clashes.isEmpty, plan)
        assert(plan.codegenWrite == Set("orders/dataQuality"), plan)
        assert(plan.codegenReuse.isEmpty, plan)
        assert(plan.reuse.isEmpty && plan.write.isEmpty, "reuse / write stay tap-only: " + plan)
        assert(repo.calls.contains(aiPath -> "main"), "checked at branch head: " + repo.calls)
    }

    test(
        "planRepoScripts: a different AI script file for a pipeline that is not a target member is a clash naming orders/dataQuality and the path, nothing in codegenWrite"
    ) {
        Seq("new" -> Set.empty[String], "overwrite" -> Set.empty[String], "overwrite" -> Set("refunds")).foreach { case (mode, members) =>
            val repo = new StubRepo(Map(aiPath -> "print('foreign')"))
            val plan = planRepoScripts(
                Map.empty,
                repoCfg,
                mode,
                Set.empty,
                repo.fileAt,
                codegenScripts = Map("orders" -> Map("dataQuality" -> "print('archived dq')")),
                targetPipelineMembers = members
            )
            val ctx = s"mode=$mode members=$members: $plan"
            assert(mentions(plan.clashes, "orders/dataQuality"), ctx)
            assert(mentions(plan.clashes, aiPath), ctx)
            assert(plan.clashes == Seq(aiClash), ctx)
            assert(plan.codegenWrite.isEmpty, "nothing is committed when there is a clash: " + ctx)
            assert(plan.codegenReuse.isEmpty, ctx)
            assert(repo.calls.contains(aiPath -> "main"), "checked at branch head: " + repo.calls)
        }
    }

    test("planRepoScripts: a different AI script file for a target member under overwrite is not a clash") {
        val repo = new StubRepo(Map(aiPath -> "print('hand edited')"))
        val plan = planRepoScripts(
            Map.empty,
            repoCfg,
            "overwrite",
            Set.empty,
            repo.fileAt,
            codegenScripts = Map("orders" -> Map("dataQuality" -> "print('archived dq')")),
            targetPipelineMembers = Set("orders")
        )
        assert(plan.clashes.isEmpty, plan)
        assert(plan.codegenWrite.contains("orders/dataQuality"), plan)
        assert(!plan.codegenReuse.contains("orders/dataQuality"), plan)
    }

    test("planRepoScripts: a .py under codegen that is not a CodeGen kind is ignored (no clash, no lookup)") {
        val repo = new StubRepo(Map(aiPath -> "print('same dq')"))
        val plan = planRepoScripts(
            Map.empty,
            repoCfg,
            "new",
            Set.empty,
            repo.fileAt,
            codegenScripts = Map("orders" -> Map("helper" -> "print('helper')")),
            targetPipelineMembers = Set.empty
        )
        assert(plan.clashes.isEmpty, plan)
        assert(plan.codegenReuse.isEmpty && plan.codegenWrite.isEmpty, plan)
        assert(plan.reuse.isEmpty && plan.write.isEmpty, plan)
        assert(repo.calls.isEmpty, "an unknown kind is never looked up: " + repo.calls)
    }

    // ------------------------------------------------------ Acceptance bullet 7

    test("restoreState=false restores no tap state at all") {
        val entries = Map(
            "manifest.json" -> validManifestJson,
            "taps/a.json" -> utf8("{}"),
            "taps/a.state.json" -> utf8("""{"cursor":1}"""),
            "taps/b.json" -> utf8("{}"),
            "taps/b.state.json" -> utf8("""{"cursor":2}""")
        )
        assert(statesToRestore(entries, restoreState = false, preexistingTaps = Set.empty).isEmpty)
        val on = statesToRestore(entries, restoreState = true, preexistingTaps = Set("b"))
        assert(on.keySet == Set("a"), "state is only written for taps that did not exist before: " + on)
        assert(JsonParser.parseString(on("a")).getAsJsonObject.get("cursor").getAsInt == 1)
    }

    // Story: Tap dependencies (plans/stories/tap-dependency-order.md), Acceptance
    // bullet 4. The user-declared `dependsOn` travels in taps/<name>.json; the
    // server-stamped `inferredDependsOn` does not (the restore re-stamps it).
    test("stripTapForExport keeps dependsOn and clears inferredDependsOn") {
        val t = fullTap("minio").copy(
            dependsOn = java.util.Arrays.asList("prices", "universe"),
            inferredDependsOn = java.util.Arrays.asList("prices")
        )
        val s = stripTapForExport(t)
        assert(s.dependsOn == java.util.Arrays.asList("prices", "universe"))
        assert(s.inferredDependsOn == null)
        // An explicit "none" survives too: [] is not the same as absent.
        val none = stripTapForExport(t.copy(dependsOn = new java.util.ArrayList[String]()))
        assert(none.dependsOn != null && none.dependsOn.isEmpty)
        assert(stripTapForExport(fullTap("minio")).dependsOn == null)
    }

    // Story: Environment label everywhere, part 1
    // (plans/stories/multi-env-2a-environment-label-server-ui.md), Acceptance
    // bullet 2. Pinned: `Manifest` gains `sourceEnvironment: Option[String] = None`
    // as its last parameter; `manifestJson` writes the key only when set;
    // `parseManifest` reads it as optional; and the pure helper
    // `environmentWarning(source: Option[String], target: Option[String]): Option[String]`.

    test("manifest round-trips sourceEnvironment and omits it when unset") {
        val withEnv = manifest(taps = Seq("orders")).copy(sourceEnvironment = Some("dev"))
        val json = manifestJson(withEnv)
        val obj = JsonParser.parseString(json).getAsJsonObject
        assert(obj.has("sourceEnvironment"), json)
        assert(obj.get("sourceEnvironment").getAsString == "dev", json)
        val back = parseManifest(utf8(json)).fold(e => fail(e), identity)
        assert(back.sourceEnvironment.contains("dev"), back)
        assert(back == withEnv, back)

        val without = manifest(taps = Seq("orders"))
        assert(without.sourceEnvironment.isEmpty, "the default is None")
        val json2 = manifestJson(without)
        assert(!JsonParser.parseString(json2).getAsJsonObject.has("sourceEnvironment"), "unset writes no key: " + json2)
        assert(!json2.contains("sourceEnvironment"), json2)
        val back2 = parseManifest(utf8(json2)).fold(e => fail(e), identity)
        assert(back2.sourceEnvironment.isEmpty, back2)
        assert(back2 == without, back2)

        // Through the archive itself, as export writes it.
        val unpacked = unpack(pack(archiveEntries(withEnv, Seq(tap("orders", "sales")), Nil))).fold(e => fail(e), identity)
        val fromZip = parseManifest(unpacked("manifest.json")).fold(e => fail(e), identity)
        assert(fromZip.sourceEnvironment.contains("dev"), fromZip)
    }

    test("parseManifest accepts a manifest with no sourceEnvironment") {
        // Every archive exported before this story looks like this.
        val parsed = parseManifest(validManifestJson)
        assert(parsed.isRight, parsed)
        assert(parsed.right.get.sourceEnvironment.isEmpty, parsed)
        assert(parsed.right.get.catalog == "sales")

        val explicitNull =
            utf8("""{"formatVersion":1,"catalog":"sales","taps":[],"pipelines":[],"requiredSecrets":[],"warnings":[],"sourceEnvironment":null}""")
        val p2 = parseManifest(explicitNull)
        assert(p2.isRight && p2.right.get.sourceEnvironment.isEmpty, p2)
    }

    test("environmentWarning names source and target and is None when both are unset") {
        val both = environmentWarning(Some("dev"), Some("production"))
        assert(both.isDefined, both)
        val w = both.get
        assert(w.contains("archive was exported from"), w)
        assert(w.contains("this install is"), w)
        assert(w.indexOf("dev") >= 0 && w.indexOf("dev") < w.indexOf("production"), "source then target: " + w)
        assert(w.matches("archive was exported from `?dev`?; this install is `?production`?"), w)

        val targetOnly = environmentWarning(None, Some("production"))
        assert(targetOnly.exists(_.contains("production")), targetOnly)

        val sourceOnly = environmentWarning(Some("dev"), None)
        assert(sourceOnly.exists(_.contains("dev")), sourceOnly)

        assert(environmentWarning(None, None).isEmpty)
        assert(environmentWarning(Some("dev"), Some("dev")).isEmpty, "a same-environment restore is not a warning")
    }

    // =====================================================================
    // Story: Restore honours archived CodeGen scripts
    // (plans/stories/multi-env-3-restore-import-codegen-scripts.md),
    // Acceptance bullet 1. Pinned seam:
    // {{{
    // object CatalogArchive {
    //   /** pipelines/<p>/codegen/<kind>.json */
    //   def codegenRecordPath(pipeline: String, kind: String): String
    //   /** Clears script, storage, scriptPath, scriptRepoPath, scriptCommitSha,
    //     * pendingReason, conflictFingerprint, conflictReason; keeps fingerprint,
    //     * model, generatedAt, generatedAgainst, origin, contractVersion, instruction. */
    //   def stripCodeGenRecordForExport(r: CodeGenScript): CodeGenScript
    //   def archiveEntries(..., codegen: Map[String, Map[String, String]] = Map.empty,
    //                      codegenRecords: Map[String, Map[String, CodeGenScript]] = Map.empty): Seq[Entry]
    //   case class Contents(..., codegenScripts: Map[String, Map[String, String]],      // pipeline -> kind -> text
    //                            codegenRecords: Map[String, Map[String, CodeGenScript]]) // pipeline -> kind -> sidecar
    // }
    // }}}
    // =====================================================================

    private val archivedRule = "age must be a positive whole number"
    private val archivedScript = "def check(r):\n    return True\n"

    /** A live Ready record as the export reads it: every field set. */
    private def liveRecord(): CodeGenScript = CodeGenScript(
        pipeline = "orders",
        kind = "dataQuality",
        instruction = archivedRule,
        script = "legacy inline text",
        generatedAt = "2026-10-01T12:00:00Z",
        storage = "github",
        scriptPath = "pipeline-scripts/orders/dataQuality_1.py",
        scriptRepoPath = "taps/pipelines/orders/data-quality.py",
        scriptCommitSha = "0123456789abcdef0123456789abcdef01234567",
        fingerprint = "fp-archived",
        generatedAgainst = java.util.Arrays.asList("full_name", "age"),
        model = "model-a",
        status = "ready",
        pendingReason = "stale reason",
        origin = "save",
        contractVersion = 1,
        conflictFingerprint = "fp-conflict",
        conflictReason = "stale conflict"
    )

    private def codegenArchive(withRecord: Boolean): Seq[Entry] =
        archiveEntries(
            manifest(pipelines = Seq("orders")),
            Nil,
            Seq(stripPipelineForExport(pipeline("orders", "sales"))),
            codegen = Map("orders" -> Map("dataQuality" -> archivedScript)),
            codegenRecords =
                if (withRecord) Map("orders" -> Map("dataQuality" -> stripCodeGenRecordForExport(liveRecord())))
                else Map.empty
        )

    private def contentsOf(entries: Map[String, Array[Byte]]): Contents = {
        val mf = parseManifest(entries(ManifestPath)).fold(e => fail(e), identity)
        readContents(mf, entries).fold(e => fail(e), identity)
    }

    test("codegenRecordPath is the .json sidecar next to the codegen script") {
        assert(codegenRecordPath("orders", "dataQuality") == "pipelines/orders/codegen/dataQuality.json")
        assert(codegenPath("orders", "dataQuality") == "pipelines/orders/codegen/dataQuality.py")
    }

    test("stripCodeGenRecordForExport clears install-specific fields and keeps fingerprint, model, generatedAt and contract") {
        val s = stripCodeGenRecordForExport(liveRecord())
        assert(s.script == null)
        assert(s.storage == null)
        assert(s.scriptPath == null)
        assert(s.scriptRepoPath == null)
        assert(s.scriptCommitSha == null)
        assert(s.pendingReason == null)
        assert(s.conflictFingerprint == null)
        assert(s.conflictReason == null)

        assert(s.fingerprint == "fp-archived")
        assert(s.model == "model-a")
        assert(s.generatedAt == "2026-10-01T12:00:00Z")
        assert(s.generatedAgainst.asScala == Seq("full_name", "age"))
        assert(s.origin == "save")
        assert(s.contractVersion == 1)
        assert(s.instruction == archivedRule)
        assert(s.pipeline == "orders" && s.kind == "dataQuality")
    }

    test("archiveEntries with one Ready record emits the codegen .py and its .json sidecar") {
        val entries = codegenArchive(withRecord = true)
        val paths = entries.map(_.path)
        assert(paths.contains("pipelines/orders/codegen/dataQuality.py"), paths)
        assert(paths.contains("pipelines/orders/codegen/dataQuality.json"), paths)

        val sidecar = JsonParser.parseString(str(entries.find(_.path == "pipelines/orders/codegen/dataQuality.json").get.bytes)).getAsJsonObject
        assert(sidecar.get("fingerprint").getAsString == "fp-archived", sidecar.toString)
        assert(sidecar.get("model").getAsString == "model-a", sidecar.toString)
        assert(sidecar.get("generatedAt").getAsString == "2026-10-01T12:00:00Z", sidecar.toString)
        Seq("scriptPath", "scriptRepoPath", "scriptCommitSha", "storage", "script").foreach { k =>
            assert(!sidecar.has(k) || sidecar.get(k).isJsonNull, s"$k must not travel in the sidecar: $sidecar")
        }
        val raw = str(entries.find(_.path == "pipelines/orders/codegen/dataQuality.json").get.bytes)
        assert(!raw.contains("0123456789abcdef"), "no commit sha in the archive")
        assert(!raw.contains("data-quality.py"), "no repository path in the archive")
    }

    test("readContents returns the codegen scripts and records, and the record is the stripped one") {
        val c = contentsOf(codegenArchive(withRecord = true).map(e => e.path -> e.bytes).toMap)
        assert(c.codegenScripts.get("orders").flatMap(_.get("dataQuality")).contains(archivedScript), c.codegenScripts)
        val rec = c.codegenRecords.get("orders").flatMap(_.get("dataQuality")).getOrElse(fail("no codegen record read: " + c.codegenRecords))
        assert(rec.fingerprint == "fp-archived")
        assert(rec.model == "model-a")
        assert(rec.generatedAt == "2026-10-01T12:00:00Z")
        assert(rec.contractVersion == 1)
        assert(rec.scriptPath == null)
        assert(rec.scriptRepoPath == null)
        assert(rec.scriptCommitSha == null)
        assert(rec.storage == null)
        assert(rec.script == null)
    }

    test("a codegen .py with no .json sidecar yields the script and an empty codegenRecords entry") {
        val entries = codegenArchive(withRecord = false)
        assert(!entries.map(_.path).contains("pipelines/orders/codegen/dataQuality.json"))
        val c = contentsOf(entries.map(e => e.path -> e.bytes).toMap)
        assert(c.codegenScripts.get("orders").flatMap(_.get("dataQuality")).contains(archivedScript), c.codegenScripts)
        assert(c.codegenRecords.getOrElse("orders", Map.empty[String, CodeGenScript]).isEmpty, c.codegenRecords)
    }

    test("pack then unpack round-trips the codegen sidecar") {
        val entries = codegenArchive(withRecord = true)
        val back = unpack(pack(entries)).fold(e => fail("unpack failed: " + e), identity)
        val path = "pipelines/orders/codegen/dataQuality.json"
        assert(back.contains(path), back.keySet)
        assert(back(path).sameElements(entries.find(_.path == path).get.bytes))
        val rec = contentsOf(back).codegenRecords("orders")("dataQuality")
        assert(rec == stripCodeGenRecordForExport(liveRecord()), rec)
    }
}
