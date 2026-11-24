package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model._
import org.scalatest.funsuite.AnyFunSuite

import java.lang.reflect.{InvocationTargetException, Method}

/** Story: pgvector: two table names that PostgreSQL cuts to the same 63 bytes
  * are treated as one table (plans/stories/pgvector-long-names-one-table.md),
  * Acceptance, loader spec (Decision 4).
  *
  * Metadata keys become column names. Two keys equal in their first 63 UTF-8
  * bytes (as `PgIdentifier.kept` cuts them, never splitting a character) would
  * make PostgreSQL refuse the CREATE TABLE / ADD COLUMN with a duplicate
  * column. The loader's name check, `PGVectorLoader.requireNames(schemaName,
  * tableName, metadataKeys)`, which `process()` runs before it connects, now
  * fails such a load with a plain sentence naming both keys. There is no save
  * rule for metadata keys.
  *
  * `requireNames` is private, so it is reached by reflection (on the loader
  * instance, or on its companion object if it moves there); a missing or
  * reshaped member fails the test naming it. */
class PGVectorLoaderNamesSpec extends AnyFunSuite {

    private val cfg: PipelineConfig =
        PipelineConfig(name = "pv_names", destination = Destination(pgvector = PGVectorConfig("docs", "public", ChunkingConfig(), null, null, null)))

    private def loader: PGVectorLoader = new PGVectorLoader(JobContext(null, null, null, cfg, null, null, null, null))

    private def requireNamesOn: (AnyRef, Method) = {
        def find(target: AnyRef): Option[Method] =
            target.getClass.getDeclaredMethods.toSeq
                .filter(m => m.getName == "requireNames" || m.getName.endsWith("$$requireNames"))
                .find(_.getParameterCount == 3)
        val instance = loader
        val companion: Option[AnyRef] =
            scala.util.Try(Class.forName("ai.datris.util.PGVectorLoader$").getField("MODULE$").get(null)).toOption
        val found = find(instance).map(instance -> _).orElse(companion.flatMap(c => find(c).map(c -> _)))
        val (target, m) = found.getOrElse(throw new AssertionError("PGVectorLoader has no requireNames(schemaName, tableName, metadataKeys)"))
        m.setAccessible(true)
        (target, m)
    }

    /** None when the names pass; Some(message) when the check refuses them. */
    private def check(keys: Seq[String], schema: String = "public", table: String = "docs"): Option[String] = {
        val (target, m) = requireNamesOn
        try {
            m.invoke(target, schema, table, keys.toList)
            None
        } catch {
            case e: InvocationTargetException => Some(String.valueOf(e.getCause.getMessage))
        }
    }

    private val p63 = "k" * 63
    private val p62 = "k" * 62

    test("two metadata keys equal after the cut fail before connecting, with both keys named") {
        // differ only after byte 63
        val a = p63 + "_alpha"
        val b = p63 + "_beta"
        val msg = check(Seq("source", a, b))
        assert(msg.isDefined, "two keys PostgreSQL cuts to one column were accepted")
        assert(msg.get.contains(a) && msg.get.contains(b), msg.get)
        assert(!msg.get.toLowerCase(java.util.Locale.ROOT).contains("alter table"), msg.get)
        assert(!msg.get.toLowerCase(java.util.Locale.ROOT).contains("create table"), msg.get)

        // a key of exactly 63 bytes and a longer key with that prefix
        val exact = check(Seq(p63, p63 + "x"))
        assert(exact.isDefined && exact.get.contains(p63 + "x"), exact)

        // a two-byte letter straddling byte 63: PostgreSQL keeps the 62-byte prefix
        val straddle = p62 + "é" + "_x"
        val s = check(Seq(p62, straddle))
        assert(s.isDefined, "a key cut on the character boundary to another key was accepted")
        assert(s.get.contains(straddle) && s.get.contains(p62), s.get)

        // the error is a DatrisException, like the loader's other name checks
        val (target, m) = requireNamesOn
        val thrown =
            try { m.invoke(target, "public", "docs", List(a, b)); None }
            catch { case e: InvocationTargetException => Some(e.getCause) }
        assert(thrown.exists(_.isInstanceOf[DatrisException]), thrown)

        // the check runs before the connection is opened
        val src = new String(
            java.nio.file.Files.readAllBytes(
                Seq(
                    java.nio.file.Paths.get("src/main/scala/ai/datris/util/PGVectorLoader.scala"),
                    java.nio.file.Paths.get("datrisserver/src/main/scala/ai/datris/util/PGVectorLoader.scala")
                ).find(java.nio.file.Files.exists(_)).get
            ),
            java.nio.charset.StandardCharsets.UTF_8
        )
        val call = src.indexOf("requireNames(schemaName, tableName, metadataKeysNow)")
        val connect = src.indexOf("DriverManager.getConnection(")
        assert(call > 0 && connect > call, s"requireNames=$call connect=$connect")

        // keys that differ within 63 bytes, or a single long key, still pass
        assert(check(Seq(p63, p62 + "j")).isEmpty)
        assert(check(Seq(p62 + "a_one", p62 + "b_one")).isEmpty)
        assert(check(Seq(p63 + "_only_one", "source", "author")).isEmpty)
        assert(check(Nil).isEmpty)
        // the existing checks are unchanged
        assert(check(Seq("a\u0000b")).exists(_.contains("NUL")))
        assert(check(Seq("ok"), schema = "").isDefined)
    }
}
