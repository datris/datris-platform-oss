package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
*/

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, StandardCopyOption}

import org.slf4j.{Logger, LoggerFactory}

/** Atomically update KEY=VALUE assignments in a .env file while preserving
  * comments, blank lines, and the order of unchanged lines.
  *
  * Used by the AI Configuration save flow so changes made in the UI persist
  * back to `.env` — without this, in-memory Vault loses UI changes on every
  * Docker restart because vault-init.sh re-seeds from .env.
  *
  * Scope is intentionally narrow: only the AI-related keys (ANTHROPIC_API_KEY,
  * OPENAI_API_KEY, *_MODEL overrides). Infrastructure values stay where they
  * are. Single-tenant only — multi-tenant deployments don't have a per-tenant
  * .env to write to.
  *
  * Behavior per key:
  *   - KEY exists uncommented → replace its value, preserve position
  *   - KEY exists but commented (# KEY=...) → uncomment and replace
  *   - KEY doesn't exist      → append at end
  *
  * Failures are logged at WARN and return false; the caller's Vault write has
  * already succeeded, so a missing/unwritable .env shouldn't break the save. */
object EnvFileWriter {
    private val logger: Logger = LoggerFactory.getLogger(getClass)

    // Serializes the read-modify-write. A single AI-config save fires up to four
    // concurrent PUTs (ai-primary, codegen, embedding, web-search), each mirroring
    // different keys into the SAME .env. Without this lock they race: each reads
    // the file, edits its keys, and writes back — last writer wins and silently
    // drops the others' changes (and `currentTimeMillis()` temp names collide).
    private val writeLock = new AnyRef

    /** Update the named KEY=VALUE pairs in `filePath`. Returns true on success,
      * false on any failure (file missing, no write permission, etc.). */
    def update(filePath: String, updates: Map[String, String]): Boolean = {
        if (filePath == null || filePath.isEmpty) {
            logger.debug("EnvFileWriter: env file path not configured — skipping writeback")
            return false
        }
        val file = new File(filePath)
        if (!file.exists()) {
            logger.debug("EnvFileWriter: " + filePath + " does not exist — skipping writeback")
            return false
        }
        if (!file.canWrite) {
            logger.warn("EnvFileWriter: " + filePath + " is not writable — skipping writeback (mount it with :rw if running in Docker)")
            return false
        }
        if (updates.isEmpty) return true

        writeLock.synchronized {
          try {
            val existing = {
                val src = scala.io.Source.fromFile(file, StandardCharsets.UTF_8.name())
                try src.getLines().toVector finally src.close()
            }
            val keysToUpdate = updates.keySet
            val seen = scala.collection.mutable.Set.empty[String]

            val rewritten = existing.map { line =>
                // Match either `KEY=...` or `# KEY=...` (allow leading whitespace + `#`).
                // We can't just split on '=' because values may contain '='.
                val keyOpt = extractKey(line)
                keyOpt match {
                    case Some(key) if keysToUpdate.contains(key) =>
                        seen.add(key)
                        key + "=" + quoteIfNeeded(updates(key))
                    case _ => line
                }
            }

            val toAppend = keysToUpdate.diff(seen).toVector.sorted.map { k =>
                k + "=" + quoteIfNeeded(updates(k))
            }
            val finalLines =
                if (toAppend.isEmpty) rewritten
                else {
                    val withSpacer = if (rewritten.lastOption.exists(_.trim.isEmpty)) rewritten else rewritten :+ ""
                    withSpacer ++ toAppend
                }

            val content = (finalLines.mkString("\n") + "\n").getBytes(StandardCharsets.UTF_8)

            // Prefer an atomic temp-file + rename (crash-safe). This needs the
            // PARENT DIRECTORY to be writable. With a single-file bind mount
            // (`./.env:/datris/.env`) the file is writable but its directory is
            // not, so creating the sibling temp file throws AccessDenied — fall
            // back to writing the file in place. Less crash-safe, but it's the
            // only option for a single-file mount, and the writeLock above keeps
            // concurrent saves from interleaving. The unique temp name (PID +
            // nanoTime) avoids cross-thread collisions on the atomic path.
            val tmp = new File(file.getParentFile, file.getName + ".tmp." + System.nanoTime())
            try {
                Files.write(tmp.toPath, content)
                try Files.move(tmp.toPath, file.toPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                catch { case _: java.nio.file.AtomicMoveNotSupportedException =>
                    Files.move(tmp.toPath, file.toPath, StandardCopyOption.REPLACE_EXISTING) }
            } catch {
                case _: java.nio.file.AccessDeniedException =>
                    // Single-file bind mount: directory not writable, file is. Write in place.
                    try Files.deleteIfExists(tmp.toPath) catch { case _: Exception => () }
                    logger.info("EnvFileWriter: atomic temp-file replace denied (likely a single-file bind mount); writing " + filePath + " in place")
                    Files.write(file.toPath, content)
            }

            val updatedKeys = (seen ++ toAppend.map(_.takeWhile(_ != '='))).toList.sorted
            logger.info("EnvFileWriter: updated " + updatedKeys.size + " key(s) in " + filePath + ": " + updatedKeys.mkString(", "))
            true
          } catch {
            case e: Exception =>
                logger.warn("EnvFileWriter: failed to update " + filePath + ": " + e.getClass.getSimpleName + ": " + e.getMessage)
                false
          }
        }
    }

    /** Pull the KEY out of a line that looks like `KEY=...`, `# KEY=...`, or
      * `   #KEY=...`. Returns None for blank lines or pure comments. The key
      * must match identifier syntax (letters, digits, underscore). */
    private def extractKey(line: String): Option[String] = {
        var s = line
        // Strip leading whitespace
        var i = 0
        while (i < s.length && s.charAt(i).isWhitespace) i += 1
        if (i >= s.length) return None
        // Optional leading '#' (commented assignment)
        if (s.charAt(i) == '#') {
            i += 1
            while (i < s.length && s.charAt(i).isWhitespace) i += 1
            if (i >= s.length) return None
        }
        s = s.substring(i)
        val eqIdx = s.indexOf('=')
        if (eqIdx <= 0) return None
        val key = s.substring(0, eqIdx).trim
        if (key.matches("[A-Za-z_][A-Za-z0-9_]*")) Some(key) else None
    }

    /** .env values that contain spaces or shell-special characters need quoting.
      * For api keys (`sk-ant-...`, `sk-...`) and model names this is almost never
      * needed, but we play it safe. */
    private def quoteIfNeeded(value: String): String = {
        val v = if (value == null) "" else value
        if (v.isEmpty) ""
        else if (v.matches("[A-Za-z0-9_./:@\\-]+")) v
        else "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }
}
