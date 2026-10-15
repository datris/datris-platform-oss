package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{CodeRepoConfig, PipelineConfig, TapConfig}
import com.google.gson.{Gson, JsonArray, JsonObject, JsonParser}

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import java.nio.charset.StandardCharsets
import java.util.zip.{ZipEntry, ZipInputStream, ZipOutputStream}
import scala.collection.JavaConverters._
import scala.collection.mutable

/** Catalog backup archive (plans/stories/catalog-backup-restore.md): the pure
  * model and (un)packing behind `GET /catalog/{name}/export` and
  * `POST /catalog/{name}/restore`. No IO: the controller supplies the configs,
  * scripts and states and does every read and write.
  *
  * Layout:
  * {{{
  * manifest.json
  * taps/<name>.json                                 stripped TapConfig
  * taps/<name>.py                                   tap script (python taps)
  * taps/<name>.state.json                           incremental state
  * pipelines/<name>.json                            stripped PipelineConfig
  * pipelines/<name>/validation-schema/<file>        referenced validation schema
  * pipelines/<name>/codegen/<kind>.py               CodeGen script (reference copy)
  * }}}
  * Secret values are never in an archive: the manifest lists secret NAMES
  * (and their `_type`) only. */
object CatalogArchive {

    val FormatVersion: Int = 1

    /** Uncompressed size and entry-count caps for an uploaded archive. */
    val MaxUncompressedBytes: Long = 50L * 1024 * 1024
    val MaxEntries: Int = 1000

    val ManifestPath = "manifest.json"
    val TapsDir = "taps/"
    val PipelinesDir = "pipelines/"

    def tapConfigPath(name: String): String = TapsDir + name + ".json"
    def tapScriptPath(name: String): String = TapsDir + name + ".py"
    def tapStatePath(name: String): String = TapsDir + name + ".state.json"
    def pipelineConfigPath(name: String): String = PipelinesDir + name + ".json"
    def validationSchemaPath(pipeline: String, file: String): String = PipelinesDir + pipeline + "/validation-schema/" + file
    def codegenPath(pipeline: String, kind: String): String = PipelinesDir + pipeline + "/codegen/" + kind + ".py"

    /** A secret the catalog references: its name, the items that use it, and
      * its kind (the stored `_type` when known, else a hint: "tap" or "platform"). */
    case class RequiredSecret(name: String, usedBy: Seq[String], kind: String)

    case class Manifest(
        formatVersion: Int,
        catalog: String,
        exportedAt: String,
        datrisVersion: String,
        taps: Seq[String],
        pipelines: Seq[String],
        requiredSecrets: Seq[RequiredSecret],
        warnings: Seq[String],
        // DATRIS_ENV label of the exporting install; absent on archives
        // exported before environment labels existed or with it unset.
        sourceEnvironment: Option[String] = None
    )

    case class Entry(path: String, bytes: Array[Byte])

    private def utf8(s: String): Array[Byte] = s.getBytes(StandardCharsets.UTF_8)

    // ------------------------------------------------------------ strip

    /** Tap definition as exported: run status, ownership, timestamps, version
      * and script references cleared (the script travels as its own entry).
      * `lastTestRunScriptId` survives only for built-in storage, where it is a
      * hash of the script bytes and so still names the same bytes on restore.
      * `dependsOn` is kept; the server-stamped `inferredDependsOn` is cleared. */
    def stripTapForExport(t: TapConfig): TapConfig = {
        val builtIn = t.scriptStorage == null || t.scriptStorage.isEmpty || t.scriptStorage == "minio"
        t.copy(
            lastRunStatus = null,
            lastRunTime = null,
            lastRunRecordCount = 0,
            lastRunError = null,
            lastRunDataType = null,
            lastRunColumns = null,
            lastRunTrigger = null,
            lastRunRetrySafe = false,
            lastTestRunStatus = null,
            lastTestRunTime = null,
            lastTestRunRecordCount = 0,
            lastTestRunError = null,
            lastTestRunDataType = null,
            lastTestRunColumns = null,
            retryCount = 0,
            createdByKeyLabel = null,
            createdAt = null,
            updatedAt = null,
            version = 1,
            scriptPath = null,
            scriptRepoPath = null,
            scriptCommitSha = null,
            scriptStorage = null,
            lastTestRunScriptId = if (builtIn) t.lastTestRunScriptId else null,
            // The declared dependsOn travels (an explicit [] too); the stamp
            // is re-derived from the script on restore.
            inferredDependsOn = null
        )
    }

    /** Pipeline definition as exported: ownership and version cleared, and
      * every inline credential removed (see [[redactionWarnings]]): the
      * preprocessor's and the REST destination's `bearerToken`/`apiKey`, and
      * parameters 3 and 4 (bearer token, API key) of each `restEndpoint` row
      * function. Unity Catalog settings are configuration and stay. */
    def stripPipelineForExport(p: PipelineConfig): PipelineConfig = {
        def clearRest(r: ai.datris.model.RestEndpoint) = if (r == null) null else r.copy(bearerToken = null, apiKey = null)
        val transformation =
            if (p.transformation == null || p.transformation.rowFunctions == null) p.transformation
            else
                p.transformation.copy(rowFunctions = new java.util.ArrayList(p.transformation.rowFunctions.asScala.map { rf =>
                    if (rf == null || !isRestRowFunction(rf) || rf.parameters == null) rf
                    else {
                        val params = new java.util.ArrayList[String](rf.parameters)
                        RowFunctionSecretIndexes.foreach(i => if (params.size() > i) params.set(i, ""))
                        rf.copy(parameters = params)
                    }
                }.asJava))
        p.copy(
            createdByKeyLabel = null,
            version = 1,
            preprocessor = clearRest(p.preprocessor),
            destination = if (p.destination == null) null else p.destination.copy(restEndpoint = clearRest(p.destination.restEndpoint)),
            transformation = transformation
        )
    }

    /** `restEndpoint` row-function parameters holding credentials:
      * 3 = bearer token, 4 = API key (Transformation.runRestEndpointFunction). */
    private val RowFunctionSecretIndexes = Seq(3, 4)

    private def isRestRowFunction(rf: ai.datris.model.RowFunction): Boolean =
        rf.function != null && rf.function.equalsIgnoreCase("restEndpoint")

    /** One line per inline credential [[stripPipelineForExport]] removes:
      * "<pipeline>: <field> was removed from the archive; re-enter it after restore". */
    def redactionWarnings(p: PipelineConfig): Seq[String] = {
        if (p == null) return Nil
        val fields = mutable.ArrayBuffer.empty[String]
        def rest(prefix: String, r: ai.datris.model.RestEndpoint): Unit =
            if (r != null) {
                if (nonBlank(r.bearerToken).isDefined) fields += (prefix + ".bearerToken")
                if (nonBlank(r.apiKey).isDefined) fields += (prefix + ".apiKey")
            }
        rest("preprocessor", p.preprocessor)
        if (p.destination != null) rest("destination.restEndpoint", p.destination.restEndpoint)
        if (p.transformation != null && p.transformation.rowFunctions != null)
            p.transformation.rowFunctions.asScala.zipWithIndex.foreach { case (rf, i) =>
                if (rf != null && isRestRowFunction(rf) && rf.parameters != null)
                    RowFunctionSecretIndexes.foreach { j =>
                        if (rf.parameters.size() > j && nonBlank(rf.parameters.get(j)).isDefined)
                            fields += ("transformation.rowFunctions[" + i + "].parameters[" + j + "]")
                    }
            }
        fields.toList.map(f => p.name + ": " + f + " was removed from the archive; re-enter it after restore")
    }

    /** Marker shared by every [[redactionWarnings]] line. */
    val RedactionMarker = "was removed from the archive"

    // ------------------------------------------------------------ secrets

    private def nonBlank(s: String): Option[String] = Option(s).map(_.trim).filter(_.nonEmpty)

    /** Every secret name a pipeline config references, as (name, kind hint). */
    private def pipelineSecretNames(p: PipelineConfig): Seq[String] = {
        val out = mutable.ArrayBuffer.empty[String]
        def add(s: String): Unit = nonBlank(s).foreach(out += _)
        val d = p.destination
        if (d != null) {
            if (d.objectStore != null) add(d.objectStore.credentialsSecret)
            if (d.database != null) add(d.database.credentialsSecret)
            if (d.qdrant != null) { add(d.qdrant.embeddingSecretName); add(d.qdrant.qdrantSecretName) }
            if (d.weaviate != null) { add(d.weaviate.embeddingSecretName); add(d.weaviate.weaviateSecretName) }
            if (d.pgvector != null) { add(d.pgvector.embeddingSecretName); add(d.pgvector.postgresSecretName) }
            if (d.milvus != null) { add(d.milvus.embeddingSecretName); add(d.milvus.milvusSecretName) }
            if (d.chroma != null) { add(d.chroma.embeddingSecretName); add(d.chroma.chromaSecretName) }
        }
        if (p.source != null && p.source.databaseAttributes != null) {
            val a = p.source.databaseAttributes
            add(a.postgresSecretsName)
            add(a.mssqlSecretsName)
            add(a.mysqlSecretsName)
        }
        if (p.unityCatalog != null) add(p.unityCatalog.credentialsSecret)
        out.distinct.toList
    }

    /** One row per secret name the taps and pipelines reference, with every
      * item that uses it. Names only — never values. */
    def requiredSecrets(taps: Seq[TapConfig], pipelines: Seq[PipelineConfig]): Seq[RequiredSecret] = {
        val order = mutable.LinkedHashMap.empty[String, (mutable.LinkedHashSet[String], String)]
        def add(secret: String, user: String, kind: String): Unit = {
            val (users, k) = order.getOrElseUpdate(secret, (mutable.LinkedHashSet.empty[String], kind))
            users += user
            if (k != kind && k == "tap") order(secret) = (users, "platform")
        }
        taps.filter(_ != null).foreach(t => nonBlank(t.secretName).foreach(s => add(s, t.name, "tap")))
        pipelines.filter(_ != null).foreach(p => pipelineSecretNames(p).foreach(s => add(s, p.name, "platform")))
        order.toList.map { case (name, (users, kind)) => RequiredSecret(name, users.toList, kind) }
    }

    /** Outcome of checking one required secret on the restore target. */
    sealed trait SecretCheck
    object SecretCheck {
        case object Present extends SecretCheck
        case object Missing extends SecretCheck

        /** The caller may not read it (or the lookup failed): neither present
          * nor missing is reported, so a restore is no existence oracle. */
        case object Unchecked extends SecretCheck
    }

    /** `lookup`: None = the lookup failed; Some(None) = the secret does not
      * exist; Some(Some(fields)) = it exists. `canRead(fields)` is the
      * list_platform_secrets read predicate for an existing secret;
      * `canReadUntyped` is that predicate with no `_type` (only a key that
      * may read every secret learns that one is missing). */
    def secretCheck(
        lookup: Option[Option[java.util.Map[String, String]]],
        canRead: java.util.Map[String, String] => Boolean,
        canReadUntyped: Boolean
    ): SecretCheck = lookup match {
        case Some(Some(fields)) => if (canRead(fields)) SecretCheck.Present else SecretCheck.Unchecked
        case Some(None) => if (canReadUntyped) SecretCheck.Missing else SecretCheck.Unchecked
        case None => SecretCheck.Unchecked
    }

    def secretNotCheckedWarning(name: String): String = name + ": not checked, this key may not read secrets"

    // ------------------------------------------------------------ warnings

    /** Install-specific values of one pipeline that are copied verbatim and
      * may not resolve on another install. */
    def installWarnings(p: PipelineConfig): Seq[String] = {
        if (p == null) return Nil
        val out = mutable.ArrayBuffer.empty[(String, String)]
        val d = p.destination
        if (d != null && d.objectStore != null) {
            nonBlank(d.objectStore.endpoint).foreach(v => out += ("destination.objectStore.endpoint" -> v))
            nonBlank(d.objectStore.destinationBucketOverride).foreach(v => out += ("destination.objectStore.destinationBucketOverride" -> v))
        }
        if (d != null && d.database != null)
            nonBlank(d.database.warehouse).foreach(v => out += ("destination.database.warehouse" -> v))
        if (p.dataQuality != null)
            nonBlank(p.dataQuality.validationSchema).filter(_.startsWith("s3://")).foreach(v => out += ("dataQuality.validationSchema" -> v))
        out.toList.map { case (field, value) => p.name + ": " + field + "=" + value + " may not be valid on another install" }
    }

    // ------------------------------------------------------------ manifest

    private def strArray(values: Seq[String]): JsonArray = {
        val arr = new JsonArray
        values.foreach(v => arr.add(v))
        arr
    }

    def manifestJson(m: Manifest): String = {
        val o = new JsonObject
        o.addProperty("formatVersion", m.formatVersion)
        o.addProperty("catalog", m.catalog)
        o.addProperty("exportedAt", m.exportedAt)
        o.addProperty("datrisVersion", m.datrisVersion)
        o.add("taps", strArray(m.taps))
        o.add("pipelines", strArray(m.pipelines))
        val secrets = new JsonArray
        m.requiredSecrets.foreach { r =>
            val s = new JsonObject
            s.addProperty("name", r.name)
            s.add("usedBy", strArray(r.usedBy))
            s.addProperty("kind", r.kind)
            secrets.add(s)
        }
        o.add("requiredSecrets", secrets)
        o.add("warnings", strArray(m.warnings))
        m.sourceEnvironment.foreach(e => o.addProperty("sourceEnvironment", e))
        new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(o)
    }

    /** Parse `manifest.json`; Left on bad JSON or an unsupported formatVersion. */
    def parseManifest(bytes: Array[Byte]): Either[String, Manifest] =
        try {
            val o = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject
            def str(k: String): String = if (o.has(k) && !o.get(k).isJsonNull) o.get(k).getAsString else null
            def arr(obj: JsonObject, k: String): Seq[String] =
                if (obj.has(k) && obj.get(k).isJsonArray) obj.getAsJsonArray(k).asScala.toList.filterNot(_.isJsonNull).map(_.getAsString)
                else Nil
            if (!o.has("formatVersion") || o.get("formatVersion").isJsonNull)
                return Left("manifest.json has no formatVersion")
            val version = o.get("formatVersion").getAsInt
            if (version > FormatVersion)
                return Left(
                    "Archive formatVersion " + version + " is newer than this server supports (" + FormatVersion + "). Upgrade Datris to restore it."
                )
            if (version < 1) return Left("Invalid archive formatVersion " + version)
            val secrets =
                if (o.has("requiredSecrets") && o.get("requiredSecrets").isJsonArray)
                    o.getAsJsonArray("requiredSecrets").asScala.toList.filter(_.isJsonObject).map { e =>
                        val s = e.getAsJsonObject
                        RequiredSecret(
                            if (s.has("name") && !s.get("name").isJsonNull) s.get("name").getAsString else null,
                            arr(s, "usedBy"),
                            if (s.has("kind") && !s.get("kind").isJsonNull) s.get("kind").getAsString else null
                        )
                    }.filter(_.name != null)
                else Nil
            Right(
                Manifest(
                    version,
                    str("catalog"),
                    str("exportedAt"),
                    str("datrisVersion"),
                    arr(o, "taps"),
                    arr(o, "pipelines"),
                    secrets,
                    arr(o, "warnings"),
                    Option(str("sourceEnvironment")).map(_.trim).filter(_.nonEmpty)
                )
            )
        } catch {
            case e: Exception => Left("manifest.json is not valid: " + Option(e.getMessage).getOrElse(e.getClass.getSimpleName))
        }

    /** Informational restore warning naming the environment an archive was
      * exported from and the one it is restored into; None when neither is
      * known. A cross-environment restore is a promotion, never refused. */
    def environmentWarning(source: Option[String], target: Option[String]): Option[String] =
        if (source.isEmpty && target.isEmpty) None
        else
            Some(
                "archive was exported from `" + source.getOrElse("unset") + "`; this install is `" + target.getOrElse("unset") + "`"
            )

    // ------------------------------------------------------------ entries

    /** Every entry of an archive, manifest first. */
    def archiveEntries(
        manifest: Manifest,
        taps: Seq[TapConfig],
        pipelines: Seq[PipelineConfig],
        scripts: Map[String, String] = Map.empty,
        states: Map[String, String] = Map.empty,
        validationSchemas: Map[String, Map[String, Array[Byte]]] = Map.empty,
        codegen: Map[String, Map[String, String]] = Map.empty
    ): Seq[Entry] = {
        val gson = new com.google.gson.GsonBuilder().setPrettyPrinting().create()
        val out = mutable.ArrayBuffer.empty[Entry]
        out += Entry(ManifestPath, utf8(manifestJson(manifest)))
        taps.foreach { t =>
            out += Entry(tapConfigPath(t.name), utf8(gson.toJson(t)))
            scripts.get(t.name).foreach(s => out += Entry(tapScriptPath(t.name), utf8(s)))
            states.get(t.name).foreach(s => out += Entry(tapStatePath(t.name), utf8(s)))
        }
        pipelines.foreach { p =>
            out += Entry(pipelineConfigPath(p.name), utf8(gson.toJson(p)))
            validationSchemas.getOrElse(p.name, Map.empty).toSeq.sortBy(_._1).foreach { case (file, bytes) =>
                out += Entry(validationSchemaPath(p.name, file), bytes)
            }
            codegen.getOrElse(p.name, Map.empty).toSeq.sortBy(_._1).foreach { case (kind, text) =>
                out += Entry(codegenPath(p.name, kind), utf8(text))
            }
        }
        out.toList
    }

    // ------------------------------------------------------------ zip

    def pack(entries: Seq[Entry]): Array[Byte] = {
        val bos = new ByteArrayOutputStream()
        val zos = new ZipOutputStream(bos)
        try {
            entries.foreach { e =>
                zos.putNextEntry(new ZipEntry(e.path))
                zos.write(e.bytes)
                zos.closeEntry()
            }
        } finally zos.close()
        bos.toByteArray
    }

    private def unsafePath(path: String): Boolean =
        path == null || path.isEmpty || path.startsWith("/") || path.startsWith("\\") ||
            path.split("[/\\\\]").contains("..") || path.contains(":")

    /** Unpack an uploaded archive. Left on a bad zip, an entry climbing out
      * with `..` or starting with `/`, more than [[MaxEntries]] entries, more
      * than [[MaxUncompressedBytes]] uncompressed, no manifest, or a manifest
      * whose formatVersion this server does not support. Directory entries
      * are skipped. */
    def unpack(bytes: Array[Byte]): Either[String, Map[String, Array[Byte]]] = {
        if (bytes == null || bytes.isEmpty) return Left("Archive is empty")
        val out = mutable.LinkedHashMap.empty[String, Array[Byte]]
        var total = 0L
        var count = 0
        val zis = new ZipInputStream(new ByteArrayInputStream(bytes))
        try {
            var entry = zis.getNextEntry
            while (entry != null) {
                count += 1
                if (count > MaxEntries) return Left("Archive has more than " + MaxEntries + " entries")
                val path = entry.getName
                if (unsafePath(path)) return Left("Archive entry '" + path + "' has an unsafe path")
                if (!entry.isDirectory) {
                    val buf = new ByteArrayOutputStream()
                    val chunk = new Array[Byte](8192)
                    var n = zis.read(chunk)
                    while (n > 0) {
                        total += n
                        if (total > MaxUncompressedBytes)
                            return Left("Archive is larger than " + (MaxUncompressedBytes / (1024 * 1024)) + " MB uncompressed")
                        buf.write(chunk, 0, n)
                        n = zis.read(chunk)
                    }
                    if (out.contains(path)) return Left("Archive has a duplicate entry '" + path + "'")
                    out(path) = buf.toByteArray
                }
                zis.closeEntry()
                entry = zis.getNextEntry
            }
        } catch {
            case e: Exception => return Left("Archive is not a valid zip file: " + Option(e.getMessage).getOrElse(e.getClass.getSimpleName))
        } finally {
            try zis.close()
            catch { case _: Exception => }
        }
        if (count == 0) return Left("Archive is not a valid zip file (no entries)")
        out.get(ManifestPath) match {
            case None => Left("Archive has no manifest.json")
            case Some(m) => parseManifest(m).map(_ => out.toMap)
        }
    }

    // ------------------------------------------------------------ restore planning

    /** An archived name that already exists on the install. */
    case class Clash(name: String, archivedAs: String, existingAs: String, existingCatalog: String) {
        override def toString: String =
            archivedAs + " '" + name + "' already exists as a " + existingAs +
                (if (existingCatalog == null || existingCatalog.isEmpty) " (Uncataloged)" else " in catalog '" + existingCatalog + "'")
    }

    /** Archived names that already exist ANYWHERE on the install: tap-vs-tap,
      * pipeline-vs-pipeline and cross-type. `mode` "overwrite" lets through a
      * name whose existing item is already a member of `target`; "new" never
      * does (a restore never merges). Placeholder taps are ignored. */
    def clashes(
        manifest: Manifest,
        allTaps: Seq[TapConfig],
        allPipelines: Seq[PipelineConfig],
        target: String,
        mode: String = "new"
    ): Seq[Clash] = {
        val overwrite = mode == "overwrite"
        val existing: Seq[(String, String, String)] =
            allTaps.filter(t => t != null && !CatalogOps.Placeholder.is(t.name)).map(t => (t.name, "tap", t.catalog)) ++
                allPipelines.filter(_ != null).map(p => (p.name, "pipeline", p.catalog))
        def check(name: String, archivedAs: String): Seq[Clash] =
            existing.collect {
                case (n, kind, cat) if n == name && !(overwrite && cat == target) => Clash(name, archivedAs, kind, cat)
            }
        manifest.taps.flatMap(check(_, "tap")) ++ manifest.pipelines.flatMap(check(_, "pipeline"))
    }

    /** What a restore does with each archived tap script when the target
      * stores scripts in a code repository: `write` commits it, `reuse` pins
      * the identical file already at branch head (no commit), `clashes` names
      * files at branch head with different content that may not be replaced. */
    case class RepoScriptPlan(clashes: Seq[String], reuse: Set[String], write: Set[String])

    private def normaliseScript(text: String): String =
        if (text == null) "" else text.replace("\r\n", "\n").replaceAll("\\s+$", "")

    /** Compare every archived tap script (tap -> text) with the file at branch
      * head. A different file is a clash unless `mode` is "overwrite" and the
      * tap is already a member of the target catalog. A clashing script is
      * never in `write`; the caller refuses the whole restore when `clashes`
      * is non-empty. `fileAt` is GithubClient.getFile. */
    def planRepoScripts(
        scripts: Map[String, String],
        cfg: CodeRepoConfig,
        mode: String,
        targetMembers: Set[String],
        fileAt: (CodeRepoConfig, String, String) => Option[RepoFile]
    ): RepoScriptPlan = {
        val clashes = mutable.ArrayBuffer.empty[String]
        val reuse = mutable.Set.empty[String]
        val write = mutable.Set.empty[String]
        scripts.toSeq.sortBy(_._1).foreach { case (tap, text) =>
            val path = GithubCodeStore.scriptRepoPath(tap, cfg)
            fileAt(cfg, path, cfg.branch) match {
                case None => write += tap; ()
                case Some(f) if normaliseScript(f.content) == normaliseScript(text) => reuse += tap; ()
                case Some(_) =>
                    if (mode == "overwrite" && targetMembers.contains(tap)) { write += tap; () }
                    else {
                        clashes += ("tap '" + tap + "': script file '" + path + "' already exists in the code repository (branch " +
                            cfg.branch + ") with different content")
                        ()
                    }
            }
        }
        RepoScriptPlan(clashes.toList, reuse.toSet, write.toSet)
    }

    /** Capability checks a restore runs for one archived item before writing
      * it, as (action, scope context) pairs for `CapabilityCheck.assertScope`:
      * always `create` in the target catalog as the caller; for an item that
      * already exists (overwrite of a target member) also `update` against
      * its live catalog and owner, as rename checks it, so an owner-scoped key
      * cannot replace another key's item. `live` is (catalog, createdByKeyLabel). */
    def restoreScopeChecks(target: String, caller: String, live: Option[(String, String)]): Seq[(String, Map[String, String])] =
        ("create" -> CatalogOps.scopeContext(target, caller)) +:
            live.toList.map { case (catalog, owner) => "update" -> CatalogOps.scopeContext(catalog, owner) }

    /** Tap states the restore writes (tap -> state JSON): none when
      * `restoreState` is false, and never for a tap that existed before. */
    def statesToRestore(
        entries: Map[String, Array[Byte]],
        restoreState: Boolean,
        preexistingTaps: Set[String]
    ): Map[String, String] =
        if (!restoreState) Map.empty
        else
            entries.collect {
                case (path, bytes)
                    if path.startsWith(TapsDir) && path.endsWith(".state.json") &&
                        !path.substring(TapsDir.length).contains("/") =>
                    path.substring(TapsDir.length, path.length - ".state.json".length) -> new String(bytes, StandardCharsets.UTF_8)
            }.filter { case (name, _) => name.nonEmpty && !preexistingTaps.contains(name) }

    // ------------------------------------------------------------ restore reading

    /** Archived tap configs, script texts and pipeline configs by name, as
      * listed in the manifest. Left names an entry that is missing or does
      * not parse, or a config whose name differs from its manifest entry. */
    case class Contents(
        taps: Seq[TapConfig],
        pipelines: Seq[PipelineConfig],
        scripts: Map[String, String],
        validationSchemas: Map[String, Map[String, Array[Byte]]]
    )

    def readContents(manifest: Manifest, entries: Map[String, Array[Byte]]): Either[String, Contents] = {
        val gson = new Gson
        def text(path: String): Option[String] = entries.get(path).map(b => new String(b, StandardCharsets.UTF_8))
        val tapNames = manifest.taps.toList
        val pipelineNames = manifest.pipelines.toList

        // Strict, closure-free validation: every check runs here, in order,
        // and the first failure is the result (no non-local return can
        // escape into the caller from a lazily evaluated collection).
        (tapNames ++ pipelineNames).find(n => n == null || n.isEmpty || unsafePath(n) || n.contains("/")) match {
            case Some(bad) => Left("Archive manifest lists an invalid item name '" + bad + "'")
            case None =>
                def parseAll[T](names: List[String], path: String => String, kind: String, cls: Class[T], nameOf: T => String): Either[String, List[T]] = {
                    val out = List.newBuilder[T]
                    var rest = names
                    var failure: Option[String] = None
                    while (rest.nonEmpty && failure.isEmpty) {
                        val n = rest.head
                        val parsed: Either[String, T] = text(path(n)) match {
                            case None => Left("Archive is missing " + path(n))
                            case Some(json) =>
                                scala.util.Try(gson.fromJson(json, cls)) match {
                                    case scala.util.Failure(e) => Left(path(n) + " is not a valid " + kind + " config: " + e.getMessage)
                                    case scala.util.Success(v) if v == null || nameOf(v) != n => Left(path(n) + " does not define " + kind + " '" + n + "'")
                                    case scala.util.Success(v) => Right(v)
                                }
                        }
                        parsed match {
                            case Left(err) => failure = Some(err)
                            case Right(v) => out += v
                        }
                        rest = rest.tail
                    }
                    failure.map(Left(_)).getOrElse(Right(out.result()))
                }
                for {
                    taps <- parseAll[TapConfig](tapNames, tapConfigPath, "tap", classOf[TapConfig], _.name).right
                    pipelines <- parseAll[PipelineConfig](pipelineNames, pipelineConfigPath, "pipeline", classOf[PipelineConfig], _.name).right
                } yield {
                    val scripts = tapNames.flatMap(n => text(tapScriptPath(n)).map(n -> _)).toMap
                    val schemas = pipelineNames.map { n =>
                        val prefix = PipelinesDir + n + "/validation-schema/"
                        n -> entries.collect {
                            case (path, bytes) if path.startsWith(prefix) && path.length > prefix.length => path.substring(prefix.length) -> bytes
                        }
                    }.filter(_._2.nonEmpty).toMap
                    Contents(taps, pipelines, scripts, schemas)
                }
        }
    }
}
