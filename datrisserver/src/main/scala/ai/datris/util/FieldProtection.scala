package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model._
import com.google.gson.{JsonElement, JsonObject, JsonParseException, JsonParser, JsonPrimitive}
import org.slf4j.{Logger, LoggerFactory}

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import scala.collection.JavaConverters._

/** Per-field protection (plans/stories/field-protection-1-stage.md).
  *
  * Runs once per job, right after the preprocessor and before DataQuality,
  * so every later stage (AI rule and AI transformation samplers, row
  * functions, every loader, the scratch preview) only ever sees protected
  * values. Source fields opt in with `protect`:
  *
  *  - `hmac`   lowercase hex HMAC-SHA256 under the per-environment key
  *             (FieldProtectionKey): equal inputs, equal tokens.
  *  - `mask`   every char masked, or `preserve`: `last4` / `domain` / `year` / `first3`.
  *  - `redact` the value becomes `[REDACTED]`.
  *  - `drop`   the column (delimited) or top-level key (JSON) is removed, and
  *             the field leaves the in-memory source and destination schemas.
  *  - `encrypt` `enc:v<n>:` AES-256-GCM token ([[FieldCipher]]) under the
  *             current versioned key (FieldProtectionKey.encryptionKey), bound
  *             to the pipeline name and the source field name; reversible
  *             only through the audited reveal endpoint.
  *
  * Empty values stay empty for every method. Deterministic and local: no
  * value ever leaves the process, and no status line, exception message or
  * log line carries one (FixSuggestionUtil forwards messages to an LLM).
  *
  * Once the protected copy is staged, the raw copies are purged
  * ([[purgeRaw]]): the previous staged file at once, and — unless the
  * pipeline sets `protection.purgeSource: false` — the ingest object(s) the
  * run was read from. A purge failure is a warning, never a failed run.
  */
object FieldProtection {
    private val logger: Logger = LoggerFactory.getLogger(getClass)

    val Redacted = "[REDACTED]"

    // ---- test hooks (null = production) --------------------------------------

    /** Fixed HMAC key instead of FieldProtectionKey.ensure() (no Vault in unit tests). */
    @volatile private[datris] var keyOverride: Array[Byte] = null

    /** Fixed (version, key) instead of FieldProtectionKey.encryptionKey() (no Vault in unit tests). */
    @volatile private[datris] var encryptionKeyOverride: (Int, Array[Byte]) = null

    /** Object store for the purge's bulk listing and deletes (null → ObjectStoreUtil). */
    @volatile private[datris] var objectStoreOverride: ObjectStoreUtility = null

    /** (category, action, resourceType, resourceName, metadata, outcome, errorMessage) (null → AuditLog.system). */
    @volatile private[datris] var auditOverride: (String, String, String, String, JsonObject, String, String) => Unit = null

    // ---- policies ------------------------------------------------------------

    /** Source fields that carry `protect`, in schema order. */
    def protectedFields(config: PipelineConfig): List[SchemaField] =
        if (config == null || config.source == null || config.source.schemaProperties == null || config.source.schemaProperties.fields == null) Nil
        else config.source.schemaProperties.fields.asScala.toList.filter(f => f != null && f.name != null && f.protect != null)

    private def methodOf(p: ProtectionPolicy): String = if (p == null || p.method == null) "" else p.method.trim.toLowerCase

    private def isDrop(p: ProtectionPolicy): Boolean = methodOf(p) == "drop"

    private def failure(field: String, p: ProtectionPolicy): DatrisException =
        new DatrisException("Field protection failed on field '" + field + "' (" + Option(p).map(_.label).getOrElse("null") + ")")

    /** The keys one run needs: `hmac` (null unless a field uses hmac) and the
      * current encryption key (null unless a field uses encrypt), plus the
      * pipeline name `encrypt` binds each ciphertext to. */
    private[datris] case class RunKeys(hmac: Array[Byte], enc: (Int, Array[Byte]), pipeline: String)

    /** One value through one policy. Pure. `key` is only read by `hmac`; `encrypt` needs `keys`. */
    private[datris] def protectValue(policy: ProtectionPolicy, value: String, key: Array[Byte]): String =
        protectValue(policy, value, RunKeys(key, null, null), null)

    private[datris] def protectValue(policy: ProtectionPolicy, value: String, keys: RunKeys, field: String): String = {
        val method = methodOf(policy)
        if (ProtectionPolicy.Reserved.contains(method))
            throw new DatrisException("Field protection method '" + method + "' is not yet supported")
        if (!ProtectionPolicy.Methods.contains(method))
            throw new DatrisException("Unknown field protection method '" + method + "' (hmac, mask, redact, drop, encrypt)")
        if (value == null || value.isEmpty) return value
        method match {
            case "hmac" => hmac(value, if (keys == null) null else keys.hmac)
            case "mask" => mask(value, policy.preserve)
            case "redact" => Redacted
            case "drop" => null
            case "encrypt" =>
                if (keys == null || keys.enc == null || keys.pipeline == null || field == null)
                    throw new DatrisException("Field protection: no encryption key")
                FieldCipher.encrypt(keys.enc._2, keys.enc._1, keys.pipeline, field, value)
        }
    }

    private def hmac(value: String, key: Array[Byte]): String = {
        if (key == null || key.isEmpty) throw new DatrisException("Field protection: no hmac key")
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(new SecretKeySpec(key, "HmacSHA256"))
        val out = mac.doFinal(value.getBytes(StandardCharsets.UTF_8))
        val sb = new StringBuilder(out.length * 2)
        out.foreach(b => sb.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16)))
        sb.toString
    }

    private def stars(n: Int): String = "*" * n

    private def len(s: String): Int = s.codePointCount(0, s.length)

    private val LeadingYear = "^(\\d{4})(?!\\d)(.*)$".r

    private def mask(value: String, preserve: String): String = {
        val p = if (preserve == null) "" else preserve.trim.toLowerCase
        p match {
            case "" => stars(len(value))
            case "last4" =>
                val n = len(value)
                if (n <= 4) stars(n)
                else {
                    val cut = value.offsetByCodePoints(0, n - 4)
                    stars(n - 4) + value.substring(cut)
                }
            case "first3" =>
                val n = len(value)
                if (n <= 3) stars(n)
                else {
                    val cut = value.offsetByCodePoints(0, 3)
                    value.substring(0, cut) + stars(n - 3)
                }
            case "domain" =>
                val at = value.lastIndexOf('@')
                if (at < 0) stars(len(value)) else "***" + value.substring(at)
            case "year" =>
                value match {
                    // Keep the year and the separators; mask every letter and digit after it.
                    case LeadingYear(year, rest) =>
                        year + rest.map(c => if (Character.isLetterOrDigit(c)) '*' else c)
                    case _ => stars(len(value))
                }
            case other => throw new DatrisException("Unknown field protection preserve '" + other + "' (last4, domain, year, first3)")
        }
    }

    // ---- stage ---------------------------------------------------------------

    /** Protect the run's data. Returns `ctx` itself when no source field has `protect`.
      * `rawStaged` is the run's payload as it was before the preprocessor
      * (JobRunner passes it); it is purged with the preprocessor's output so
      * no raw staged copy outlives the protected one. */
    def apply(ctx: JobContext, rawStaged: StagedPayload = null): JobContext = {
        if (ctx == null) return ctx
        val fields = protectedFields(ctx.config)
        if (fields.isEmpty) return ctx

        // Second line of defence behind PipelineValidatorUtil: refuse before
        // anything is written or purged.
        fields.foreach { f =>
            val m = methodOf(f.protect)
            if (!ProtectionPolicy.Methods.contains(m)) throw failure(f.name, f.protect)
            if (f.protect.preserve != null && (m != "mask" || !ProtectionPolicy.Preserves.contains(f.protect.preserve.trim.toLowerCase)))
                throw failure(f.name, f.protect)
        }

        val previous = if (ctx.data != null) ctx.data.staged else null
        val (protectedCtx, applied) =
            try {
                val hmacKey: Array[Byte] =
                    if (fields.exists(f => methodOf(f.protect) == "hmac")) {
                        val k = keyOverride
                        if (k != null) k else FieldProtectionKey.ensure()
                    } else null
                val encKey: (Int, Array[Byte]) =
                    if (fields.exists(f => methodOf(f.protect) == "encrypt")) {
                        val k = encryptionKeyOverride
                        if (k != null) k else FieldProtectionKey.encryptionKey()
                    } else null
                val key = RunKeys(hmacKey, encKey, ctx.config.name)

                val data = ctx.data
                if (data == null || data.staged == null || data.staged.isEmpty) (withSchemas(ctx, fields), fields)
                else if (data.isDelimited) protectDelimited(ctx, fields, key)
                else if (data.isNdJson) (protectNdJson(ctx, fields, key), fields)
                else throw new DatrisException("Field protection needs a delimited or JSON source")
            } catch {
                case e: DatrisException => throw e
                // Never forward the underlying message: it may quote a value.
                case e: Exception => throw new DatrisException("Field protection failed (" + e.getClass.getSimpleName + ")")
            }

        if (ctx.statusUtil != null)
            ctx.statusUtil.info(
                "processing",
                "Protected " + applied.size + " field" + (if (applied.size == 1) "" else "s") + ": " +
                    applied.map(f => f.name + "=" + f.protect.label).mkString(", ")
            )

        purgeRaw(protectedCtx, previous, rawStaged)
        protectedCtx
    }

    /** Rewrite each delimited row by header index into a new staged file. */
    private def protectDelimited(ctx: JobContext, fields: List[SchemaField], key: RunKeys): (JobContext, List[SchemaField]) = {
        val data = ctx.data
        val delimiter = data.delimiter
        val header: List[String] =
            if (data.header != null) data.header
            else if (data.headerWithSchema != null) data.headerWithSchema.map(_.name)
            else ctx.config.source.schemaProperties.fields.asScala.toList.map(_.name)
        val lowerHeader = header.map(h => if (h == null) "" else h.toLowerCase)

        val byIndex: Map[Int, SchemaField] = fields.flatMap { f =>
            val i = lowerHeader.indexOf(f.name.toLowerCase)
            if (i >= 0) Some(i -> f) else None
        }.toMap
        val dropIdx: Set[Int] = byIndex.collect { case (i, f) if isDrop(f.protect) => i }.toSet

        def rewrite(row: String): String = {
            val cols = CodeGenTransformationEvaluator.splitLine(row, delimiter)
            val out = cols.iterator.zipWithIndex.flatMap { case (v, i) =>
                if (dropIdx.contains(i)) None
                else
                    byIndex.get(i) match {
                        case Some(f) =>
                            val p =
                                try protectValue(f.protect, v, key, f.name)
                                catch { case _: Exception => throw failure(f.name, f.protect) }
                            Some(ProvenanceStamper.csvEncode(if (p == null) "" else p, delimiter))
                        case None => Some(ProvenanceStamper.csvEncode(v, delimiter))
                    }
            }
            out.mkString(delimiter)
        }

        val rows = data.rowIterator()
        val newStaged =
            try PayloadStager.stageRowIterator("protect", rows.map(rewrite), delimiter)
            finally rows.close()

        val droppedNames: Set[String] = dropIdx.map(lowerHeader)
        val keptHeader = header.zipWithIndex.filterNot(p => dropIdx.contains(p._2)).map(_._1)
        val keptWithSchema =
            if (data.headerWithSchema != null) data.headerWithSchema.filterNot(f => f != null && f.name != null && droppedNames.contains(f.name.toLowerCase))
            else data.headerWithSchema

        val withData = ctx.copy(data = data.withStaged(newStaged).copy(header = keptHeader, headerWithSchema = keptWithSchema))
        // Only the protected fields actually in the data are named on the status line.
        val applied = fields.filter(f => byIndex.values.exists(_ eq f))
        (withSchemas(withData, fields), applied)
    }

    /** Rewrite / remove top-level keys of each NDJSON object into a new staged file. */
    private def protectNdJson(ctx: JobContext, fields: List[SchemaField], key: RunKeys): JobContext = {
        val source = ctx.data.staged
        val byKey: Map[String, SchemaField] = fields.map(f => f.name.toLowerCase -> f).toMap
        val gson = PayloadStager.gson
        val format = StagedFormat.NdJson
        val (path, writer) = StagingArea.newWriter("protect", format)
        var count = 0L
        val records = ctx.data.recordIterator()
        try {
            while (records.hasNext) {
                val line = records.next()
                val out =
                    if (line.trim.isEmpty) line
                    else {
                        val el =
                            try JsonParser.parseString(line)
                            catch {
                                case _: JsonParseException =>
                                    throw new DatrisException("Field protection failed: JSON record " + (count + 1) + " does not parse")
                            }
                        if (el.isJsonObject) {
                            protectObject(el.getAsJsonObject, byKey, key)
                            gson.toJson(el)
                        } else line
                    }
                if (count > 0) writer.write("\n")
                writer.write(out)
                count += 1
            }
        } finally {
            records.close()
            writer.close()
        }
        val staged = StagedPayload(path.toString, format, count, Files.size(path), source.arraySource)
        withSchemas(ctx.copy(data = ctx.data.withStaged(staged)), fields)
    }

    private def protectObject(obj: JsonObject, byKey: Map[String, SchemaField], key: RunKeys): Unit = {
        // Copy the key set: drop removes while iterating.
        val keys = new java.util.ArrayList[String](obj.keySet())
        keys.asScala.foreach { k =>
            byKey.get(k.toLowerCase).foreach { f =>
                if (isDrop(f.protect)) obj.remove(k)
                else {
                    val v: JsonElement = obj.get(k)
                    if (v != null && !v.isJsonNull) {
                        val text = if (v.isJsonPrimitive) v.getAsString else v.toString
                        val p =
                            try protectValue(f.protect, text, key, f.name)
                            catch { case _: Exception => throw failure(f.name, f.protect) }
                        obj.add(k, new JsonPrimitive(if (p == null) "" else p))
                    }
                }
            }
        }
    }

    /** The in-memory schemas handed downstream: `drop` fields leave both the
      * source and the destination schema; for a JSON source every protected
      * key entry leaves them too, so loaders still see only `_json`. The
      * stored config is never written back (copies only). */
    private def withSchemas(ctx: JobContext, fields: List[SchemaField]): JobContext = {
        val cfg = ctx.config
        val json = cfg.source.fileAttributes != null && cfg.source.fileAttributes.jsonAttributes != null
        val remove: Set[String] =
            (if (json) fields else fields.filter(f => isDrop(f.protect))).map(_.name.toLowerCase).toSet
        if (remove.isEmpty) return ctx

        def strip(sp: SchemaProperties): SchemaProperties =
            if (sp == null || sp.fields == null) sp
            else {
                val kept = new java.util.ArrayList[SchemaField]()
                sp.fields.asScala.foreach(f => if (f == null || f.name == null || !remove.contains(f.name.toLowerCase)) kept.add(f))
                sp.copy(fields = kept)
            }

        val newSource = cfg.source.copy(schemaProperties = strip(cfg.source.schemaProperties))
        val newDestination =
            if (cfg.destination != null) cfg.destination.copy(schemaProperties = strip(cfg.destination.schemaProperties))
            else cfg.destination
        ctx.copy(config = cfg.copy(source = newSource, destination = newDestination))
    }

    // ---- purge ---------------------------------------------------------------

    private def audit(action: String, name: String, metadata: JsonObject, outcome: String, errorMessage: String): Unit = {
        val hook = auditOverride
        try {
            if (hook != null) hook("pipeline", action, "pipeline", name, metadata, outcome, errorMessage)
            else ai.datris.audit.AuditLog.system("pipeline", action, "pipeline", name, metadata, outcome, errorMessage)
        } catch { case e: Exception => logger.warn("FieldProtection: audit write failed: " + e.getMessage) }
    }

    /** Remove the raw copies once the protected copy is in `ctx`: the
      * previous staged file (and the pre-preprocessor payload, when given),
      * then (purgeSource on, upload runs only) the ingest object(s) and the
      * archive they were extracted from. Never throws. */
    private[datris] def purgeRaw(ctx: JobContext, previousStaged: StagedPayload, rawStaged: StagedPayload = null): Unit = {
        val status = ctx.statusUtil
        val current = if (ctx.data != null && ctx.data.staged != null) ctx.data.staged.path else null
        List(previousStaged, rawStaged).filter(p => p != null && p.path != null && p.path != current).map(_.path).distinct.foreach { path =>
            try Files.deleteIfExists(Paths.get(path))
            catch { case e: Exception => logger.warn("FieldProtection: could not delete the raw staged file: " + e.getMessage) }
        }

        // Only runs read from an ingest object have one to purge. Tap- and
        // stream-fed runs carry metadata with no dataFilePath (or no file name).
        val md = ctx.metadata
        if (
            !ProtectionConfig.purgeSourceOn(ctx.config) || md == null || md.dataFilePath == null ||
            (!md.bulkUpload && md.dataFileName == null)
        ) return

        val name = ctx.config.name
        val store = Option(objectStoreOverride).getOrElse(ObjectStoreUtil)
        val listed: List[String] =
            try new PipelineMetadataUtil(status).getFiles(ctx.metadata, store)
            catch {
                case e: Exception =>
                    val msg = Option(e.getMessage).getOrElse(e.getClass.getSimpleName)
                    if (status != null) status.warn("processing", "Could not purge raw source: " + msg)
                    audit("purge-source", name, null, "warning", msg)
                    Nil
            }
        // An archive drop: the bulk files are extracted copies; the archive
        // object itself is the original raw source.
        val urls = (listed ++ Option(md.sourceObject).filter(_.nonEmpty).toList).distinct

        val purged = List.newBuilder[String]
        urls.foreach { url =>
            var display = url
            try {
                val bucket = store.getBucket(url)
                val key = store.getKey(url)
                display = bucket + "/" + key
                store.deleteBucketObject(bucket, key)
                purged += display
            } catch {
                case e: Exception =>
                    val msg = Option(e.getMessage).getOrElse(e.getClass.getSimpleName)
                    if (status != null) status.warn("processing", "Could not purge raw source " + display + ": " + msg)
                    val md = new JsonObject()
                    md.addProperty("object", display)
                    audit("purge-source", name, md, "warning", msg)
            }
        }
        val done = purged.result()
        if (done.nonEmpty) {
            if (status != null) status.info("processing", "Purged raw source: " + done.mkString(", "))
            val md = new JsonObject()
            val arr = new com.google.gson.JsonArray()
            done.foreach(arr.add)
            md.add("objects", arr)
            audit("purge-source", name, md, "success", null)
        }
    }
}
