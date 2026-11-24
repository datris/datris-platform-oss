package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import org.scalatest.funsuite.AnyFunSuite

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}

/** Story: an optional separate low-privilege login for read-back queries
  * (plans/stories/postgres-query-role.md). Which login each path connects
  * with is decided where it reads the Postgres secret from Vault, which a
  * spec cannot reach (`SecretsUtil` has no test override, and
  * `PostgresQueryUtil.poolOverride` skips the secret altogether). So the
  * wiring is pinned in source: every query through `PostgresQueryUtil.run`
  * (query_postgres, query_natural, POST /query/postgres, its column lookup
  * and `DestSchemaApply.sampleLanded`) connects with `postgresQueryLogin`,
  * chosen before any connection is opened; the health probe, loads,
  * deletes, the `DestSchemaApply` migration and the metadata listings keep
  * the loading login; no log line in the query path carries a password.
  * What only a live PostgreSQL shows is in the story's manual Verify. */
class PostgresQueryLoginWiringSpec extends AnyFunSuite {

    private def source(relative: String): String = {
        val path: Path = Seq("src/main/scala/", "datrisserver/src/main/scala/")
            .map(r => Paths.get(r + relative))
            .find(Files.exists(_))
            .getOrElse(fail("source not found: " + relative))
        new String(Files.readAllBytes(path), StandardCharsets.UTF_8)
    }

    /** The text of the member that starts at `signature`, up to the next
      * member of the same object (four-space indent). */
    private def member(src: String, signature: String): String = {
        val start = src.indexOf(signature)
        assert(start >= 0, "no member " + signature)
        val next = "\n    (private|def |/\\*\\*|@volatile|val |lazy |override )".r
            .findFirstMatchIn(src.substring(start + signature.length))
            .map(m => start + signature.length + m.start)
            .getOrElse(src.length)
        src.substring(start, next)
    }

    private lazy val queryUtil = source("ai/datris/util/PostgresQueryUtil.scala")
    private lazy val runBody = member(queryUtil, "private def run(")

    test("queries connect with the query login, not the loading login") {
        assert(runBody.contains("postgresQueryLogin("), "PostgresQueryUtil.run chooses its login with SecretsRetrieverUtil.postgresQueryLogin:\n" + runBody)
        assert(runBody.contains("PostgresPool.withEvictableConnection("), "run still connects through the pool:\n" + runBody)
        assert(
            !runBody.contains("secrets.username") && !runBody.contains("secrets.password"),
            "run passes the query login to the pool, not the secret's loading username/password:\n" + runBody
        )
    }

    test("a half-configured query login fails before any connection is opened") {
        val choose = runBody.indexOf("postgresQueryLogin(")
        val connect = runBody.indexOf("PostgresPool.withEvictableConnection(")
        assert(choose >= 0, "run calls postgresQueryLogin:\n" + runBody)
        assert(choose < connect, "the login (and its refusal when half-configured) is decided before the pool is asked for a connection:\n" + runBody)
        // Still inside the read-only transaction whichever login is used.
        assert(runBody.contains("runOn("), runBody)
        assert(member(queryUtil, "private[util] def runOn(").contains("inReadOnlyTransaction("))
    }

    test("postgresSecrets reads the optional queryUsername and queryPassword keys") {
        val body = member(source("ai/datris/util/SecretsRetrieverUtil.scala"), "def postgresSecrets()")
        assert(body.contains("\"queryUsername\""), "postgresSecrets reads queryUsername:\n" + body)
        assert(body.contains("\"queryPassword\""), "postgresSecrets reads queryPassword:\n" + body)
    }

    test("the health probe keeps the loading login") {
        val body = member(queryUtil, "def probeError()")
        assert(body.contains("secrets.username") && body.contains("secrets.password"), body)
        assert(!body.contains("postgresQueryLogin") && !body.contains("queryUsername"), body)
    }

    test("loads, deletes, the schema migration and the metadata listings keep the loading login") {
        Seq(
            "ai/datris/util/PostgresLoader.scala",
            "ai/datris/util/DestSchemaApply.scala",
            "ai/datris/api/MetadataAPIController.scala",
            "ai/datris/api/PipelineAPIController.scala",
            "ai/datris/util/DestinationProbe.scala"
        ).foreach { f =>
            val src = source(f)
            assert(!src.contains("postgresQueryLogin") && !src.contains("queryUsername") && !src.contains("queryPassword"), f + " uses the loading login")
        }
        val migration = member(source("ai/datris/util/DestSchemaApply.scala"), "private def withPostgres[")
        assert(migration.contains("secrets.username, secrets.password"), migration)
    }

    test("the landed-row sample for a schema proposal reads through the query path") {
        val sample = member(source("ai/datris/util/DestSchemaApply.scala"), "private[datris] def sampleLanded(")
        val postgres = sample.substring(sample.indexOf("case \"postgres\""), sample.indexOf("case \"snowflake\""))
        assert(postgres.contains("PostgresQueryUtil.query(") || postgres.contains("PostgresQueryUtil.queryBound("), postgres)
    }

    /** `text` without its string literals, so a word inside a message is not
      * read as a reference to a value. */
    private def withoutLiterals(text: String): String = text.replaceAll("\"(?:\\\\.|[^\"\\\\])*\"", "\"\"")

    /** Every `logger.<level>(...)` call in `src`, parentheses matched outside literals. */
    private def loggerCalls(src: String): Seq[String] = {
        val plain = withoutLiterals(src)
        "logger\\.(trace|debug|info|warn|error)\\(".r.findAllMatchIn(plain).map { m =>
            var depth = 0
            var i = m.end - 1
            var done = false
            while (i < plain.length && !done) {
                plain.charAt(i) match {
                    case '(' => depth += 1
                    case ')' => depth -= 1; if (depth == 0) done = true
                    case _ =>
                }
                i += 1
            }
            plain.substring(m.start, i)
        }.toSeq
    }

    test("no log line in the query path carries a password") {
        val calls = loggerCalls(queryUtil) ++ loggerCalls(source("ai/datris/util/SecretsRetrieverUtil.scala"))
        calls.foreach { c =>
            assert("(?i).*(password|\\._2\\b).*".r.pattern.matcher(c.replace('\n', ' ')).matches() == false, "a log call refers to a password: " + c)
        }
    }
}
