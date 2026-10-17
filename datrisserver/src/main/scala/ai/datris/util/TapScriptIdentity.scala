package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.TapConfig

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import scala.util.Try

/** Names the exact bytes (or endpoint) a tap's schedule would run, so a
  * successful `mode=test` can be stamped against it (`TapConfig.lastTestRunScriptId`)
  * and a later script edit drops the green until re-tested (see TapCronGate).
  *
  *  - repo-backed taps: `"gh:" + scriptCommitSha` (the pin IS the identity; no read)
  *  - HTTP taps:        `"http:" + endpointUrl` (no read)
  *  - MinIO taps:       sha256 hex of the script text
  *
  * Returns None whenever the script cannot be read (missing object, backend
  * error, unpinned repo tap, no script reference) — an unreadable script must
  * never mint an identity that could match a stamp.
  */
object TapScriptIdentity {

    def of(tap: TapConfig): Option[String] =
        of(tap, t => TapCodeStore.forTap(t).readScript(t))

    def of(tap: TapConfig, readScript: TapConfig => Option[String]): Option[String] = {
        if (tap == null) None
        else if (tap.isHttp) nonEmpty(tap.endpointUrl).map("http:" + _)
        else if (tap.scriptStorage == "github") nonEmpty(tap.scriptCommitSha).map("gh:" + _)
        else if (nonEmpty(tap.scriptPath).isEmpty) None
        else Try(readScript(tap)).toOption.flatten.map(sha256Hex)
    }

    /** The identity `of` will compute for a BUILT-IN (MinIO) tap once `text`
      * is stored and read back: the MinIO reader returns the object line by
      * line joined with "\n" (any line terminator, no trailing one), so the
      * text is put through the same reader before hashing. A passing scratch
      * rehearsal of `text` is stamped with this (see TapCronGate's rehearsed
      * stamp) so the approved fix can be stored on a scheduled tap. */
    def ofText(text: String): String = sha256Hex(asStored(text))

    private def asStored(text: String): String = {
        val reader = new java.io.BufferedReader(new java.io.StringReader(Option(text).getOrElse("")))
        Stream.continually(reader.readLine()).takeWhile(_ != null).mkString("\n")
    }

    private def nonEmpty(s: String): Option[String] = Option(s).filter(_.nonEmpty)

    private def sha256Hex(text: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(text.getBytes(StandardCharsets.UTF_8))
            .map("%02x".format(_))
            .mkString
}
