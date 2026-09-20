package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{DatrisEnvironment, StagedFormat}
import org.slf4j.{Logger, LoggerFactory}

import java.io.{BufferedWriter, InputStream, OutputStream, Writer}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.util.UUID
import scala.concurrent.duration.{Duration, DurationInt}

/** Local-disk staging for pipeline payloads (plans/streaming-pipeline.md, Phase 1).
  *
  *  Layout: `{root}/{pipelineToken}/{stage}-{uuid}.{ext}`. The root comes from
  *  `DatrisEnvironment.current.tempDir` (`DATRIS_TEMP_DIR`, default
  *  `/tmp/datris-staging`). The pipeline token is bound per thread by
  *  [[withToken]] — `StreamNotifier.process` and `JobRunner.run` set it — so
  *  every `Data` built while a run is in flight lands in that run's directory
  *  and `JobRunner` can drop the whole directory in its `finally`. Files staged
  *  outside any run (unit tests, ad-hoc construction) go under `_unscoped/` and
  *  are only reclaimed by the startup sweep.
  */
object StagingArea {
    private val logger: Logger = LoggerFactory.getLogger(getClass)

    val DefaultRoot: String = "/tmp/datris-staging"
    val DefaultMaterializeMaxMB: Int = 256
    val MaterializeCapEnvVar: String = "PIPELINE_MATERIALIZE_MAX_MB"
    val TempDirEnvVar: String = "DATRIS_TEMP_DIR"
    val SweepAge: Duration = 24.hours

    // Per-run payload disk budget (plans/streaming-pipeline.md, Phase 4). Replaces
    // the old in-heap tap output cap (TAP_MAX_OUTPUT_MB): the bytes now land in a
    // staged file, so the ceiling is disk, not heap, and the default is 4 GB.
    val PayloadBudgetEnvVar: String = "PIPELINE_MAX_PAYLOAD_MB"
    val LegacyPayloadBudgetEnvVar: String = "TAP_MAX_OUTPUT_MB"
    val DefaultPayloadBudgetMB: Int = 4096

    private val UnscopedDir = "_unscoped"

    private val currentToken = new ThreadLocal[String]()

    private def env: Option[DatrisEnvironment] = Option(DatrisEnvironment.current)

    /** Staging root for the current tenant/thread. */
    def root: Path = Paths.get(env.map(_.tempDir).filter(d => d != null && d.nonEmpty).getOrElse(DefaultRoot))

    /** Whole-payload materialization cap in MB for the current tenant/thread. */
    def materializeMaxMB: Int = env.map(_.pipelineMaterializeMaxMB).filter(_ > 0).getOrElse(DefaultMaterializeMaxMB)

    /** Effective per-run payload disk budget, MB; 0 = unlimited. Precedence:
      * `pipelineMaxPayloadMB` when >= 0; else the deprecated `tapMaxOutputMB`
      * when >= 0; else [[DefaultPayloadBudgetMB]]. Both carry "unset" as -1 so an
      * explicit 0 keeps meaning unlimited. */
    def payloadBudgetMB: Int = env match {
        case Some(e) if e.pipelineMaxPayloadMB >= 0 => e.pipelineMaxPayloadMB
        case Some(e) if e.tapMaxOutputMB >= 0 => e.tapMaxOutputMB
        case _ => DefaultPayloadBudgetMB
    }

    /** The budget in bytes, or 0 when unlimited. */
    def payloadBudgetBytes: Long = payloadBudgetMB.toLong * 1024L * 1024L

    /** True when `bytes` written for one run exceed the budget. */
    def overBudget(bytes: Long): Boolean = {
        val limit = payloadBudgetBytes
        limit > 0 && bytes > limit
    }

    /** The failure wording for an over-budget run (story Step 1). `bytes` is what was written before the run was stopped. */
    def budgetExceededMessage(bytes: Long): String =
        "The tap payload exceeded the configured disk budget for one run (" + PayloadBudgetEnvVar + " = " + payloadBudgetMB +
            " MB, got ~" + math.max(bytes / (1024L * 1024L), payloadBudgetMB.toLong + 1L).toString + " MB). " +
            "Raise it, or chunk the source range via run_tap params."

    /** Create the root (idempotent). Called once at boot. */
    def ensureRoot(): Path = Files.createDirectories(root)

    /** Bind `token` as the current run for the duration of `body`. */
    def withToken[T](token: String)(body: => T): T = {
        val previous = currentToken.get()
        currentToken.set(token)
        try body
        finally
            if (previous == null) currentToken.remove() else currentToken.set(previous)
    }

    def currentTokenOption: Option[String] = Option(currentToken.get())

    private def safeName(token: String): String = {
        val cleaned = Option(token).getOrElse("").replaceAll("[^A-Za-z0-9._-]", "_").stripPrefix(".")
        if (cleaned.isEmpty) UnscopedDir else cleaned
    }

    /** The per-run directory for `token`, created on demand. */
    def forToken(token: String): Path = Files.createDirectories(root.resolve(safeName(token)))

    /** A fresh file path for a staged payload in the current run's directory. */
    def newFile(stage: String, format: StagedFormat): Path = newFile(stage, format.extension)

    /** A fresh file path with an explicit extension (for files a script reads
      * by name, e.g. the `.json` a CodeGen script expects). */
    def newFile(stage: String, extension: String): Path = {
        val dir = forToken(currentTokenOption.getOrElse(UnscopedDir))
        dir.resolve(safeName(stage) + "-" + UUID.randomUUID().toString + "." + extension)
    }

    /** Open a UTF-8 writer on a new staged file. Caller closes it. */
    def newWriter(stage: String, format: StagedFormat): (Path, Writer) = newWriter(stage, format.extension)

    /** Open a UTF-8 writer on a new staged file with an explicit extension. Caller closes it. */
    def newWriter(stage: String, extension: String): (Path, Writer) = {
        val path = newFile(stage, extension)
        (path, new BufferedWriter(Files.newBufferedWriter(path, StandardCharsets.UTF_8)))
    }

    /** Open an output stream on a new staged file. Caller closes it. */
    def newOutputStream(stage: String, format: StagedFormat): (Path, OutputStream) = {
        val path = newFile(stage, format)
        (path, new java.io.BufferedOutputStream(Files.newOutputStream(path)))
    }

    /** Write `text` verbatim to a new staged file; returns (path, bytes). */
    def writeText(stage: String, format: StagedFormat, text: String): (Path, Long) = {
        val path = newFile(stage, format)
        val bytes = Option(text).getOrElse("").getBytes(StandardCharsets.UTF_8)
        Files.write(path, bytes)
        (path, bytes.length.toLong)
    }

    /** Write `bytes` verbatim to a new staged file; returns (path, bytes). */
    def writeBytes(stage: String, format: StagedFormat, bytes: Array[Byte]): (Path, Long) = {
        val path = newFile(stage, format)
        Files.write(path, bytes)
        (path, bytes.length.toLong)
    }

    /** Copy `source` verbatim into a new staged file; closes `source`. Returns (path, bytes). */
    def copyStream(stage: String, format: StagedFormat, source: InputStream): (Path, Long) = {
        val path = newFile(stage, format)
        val copied =
            try Files.copy(source, path)
            finally source.close()
        (path, copied)
    }

    /** Remove the run directory for `token`, if any. Never throws. */
    def delete(token: String): Unit = {
        if (token == null || token.isEmpty) return
        val dir = root.resolve(safeName(token))
        if (safeName(token) == UnscopedDir || !Files.isDirectory(dir)) return
        try {
            deleteTree(dir)
            logger.debug("Staging: removed " + dir)
        } catch {
            case e: Exception => logger.warn("Staging: could not remove " + dir + ": " + e.getMessage)
        }
    }

    private def deleteTree(dir: Path): Unit = {
        val stream = Files.walk(dir)
        try stream.sorted(java.util.Comparator.reverseOrder[Path]()).forEach(p => Files.deleteIfExists(p))
        finally stream.close()
    }

    /** Startup sweep: delete every staged file older than `maxAge` under the
      * root, then any run directory left empty. Never touches anything outside
      * the root. Returns the number of files deleted; 0 when the root is absent. */
    def sweepOlderThan(maxAge: Duration): Int = {
        val base = root
        if (!Files.isDirectory(base)) return 0
        val cutoff = System.currentTimeMillis() - maxAge.toMillis
        var deleted = 0
        val files = Files.walk(base)
        try {
            val all = files.sorted(java.util.Comparator.reverseOrder[Path]()).toArray.map(_.asInstanceOf[Path])
            all.foreach { p =>
                if (p == base) ()
                else if (Files.isRegularFile(p)) {
                    if (Files.getLastModifiedTime(p).toMillis < cutoff && Files.deleteIfExists(p)) deleted += 1
                } else if (Files.isDirectory(p)) {
                    val children = Files.list(p)
                    val empty =
                        try !children.findAny().isPresent
                        finally children.close()
                    if (empty && Files.getLastModifiedTime(p).toMillis < cutoff) Files.deleteIfExists(p)
                }
            }
        } finally files.close()
        logger.info("Staging sweep: deleted " + deleted + " expired staged file(s) under " + base)
        deleted
    }
}
