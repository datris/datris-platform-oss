package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.auth.ScopeDenialFixtures
import ai.datris.model.DatrisEnvironment
import org.scalatest.funsuite.AnyFunSuite

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import scala.collection.JavaConverters._

/** The platform-collection rule cannot go stale: every collection name the
  * platform defines (each `*TableName` of DatrisEnvironment, as StartupRunner
  * and forEnvironment set them) is in `platformCollectionNames`, and no code
  * names an internal collection any other way. */
class PlatformCollectionsSpec extends AnyFunSuite {

    private val env: DatrisEnvironment = PlatformCollectionFixtures.named(ScopeDenialFixtures.auditEnv().copy(environment = "probe"))

    private def mainRoot: Path = Seq(Paths.get("src/main/scala"), Paths.get("datrisserver/src/main/scala")).find(Files.exists(_)).get
    private def read(p: Path): String = new String(Files.readAllBytes(p), StandardCharsets.UTF_8)
    private def sources: Seq[Path] = Files.walk(mainRoot).iterator().asScala.filter(_.toString.endsWith(".scala")).toSeq

    test("every *TableName of DatrisEnvironment is a platform collection") {
        val cls = classOf[DatrisEnvironment]
        val ctorFields = cls.getDeclaredFields.map(_.getName).filter(_.endsWith("TableName")).toSet
        val getters = cls.getMethods.filter(m => m.getName.endsWith("TableName") && m.getParameterCount == 0 && m.getReturnType == classOf[String])
        assert(getters.nonEmpty)
        // the fixture sets every constructor name, so a new one fails here first
        for (f <- ctorFields) {
            val v = cls.getMethod(f).invoke(env)
            assert(v != null, "PlatformCollectionFixtures does not set " + f)
        }
        val names = env.platformCollectionNames.toSet
        for (m <- getters) {
            val v = m.invoke(env).asInstanceOf[String]
            assert(names.contains(v), m.getName + " = " + v + " is not in platformCollectionNames")
            assert(MongoReservedNames.platformCollection(v, env), m.getName)
        }
    }

    test("the names StartupRunner and forEnvironment give are covered: exactly in one install, for any prefix multi-tenant") {
        val pattern = """(\w+TableName)\s*=\s*(?:environment|env)\s*\+\s*"-([\w-]+)"""".r
        val texts = Seq(read(mainRoot.resolve("ai/datris/StartupRunner.scala")), read(mainRoot.resolve("ai/datris/model/DatrisEnvironment.scala")))
        val found = texts.flatMap(t => pattern.findAllMatchIn(t).map(m => m.group(1) -> m.group(2))).distinct
        assert(found.size >= 15, found)
        val suffixes = MongoReservedNames.platformSuffixes(env).toSet
        for ((field, suffix) <- found) {
            assert(suffixes.contains(suffix), field + ": -" + suffix + " is not a platform suffix")
            assert(MongoReservedNames.platformCollection("probe-" + suffix, env), "probe-" + suffix)
            for (prefix <- Seq("oss", "trial-x-1", "tenant-b", "Weird_Prefix"))
                assert(MongoReservedNames.platformCollection(prefix + "-" + suffix, env.copy(multiTenant = true)), prefix + "-" + suffix)
        }
    }

    test("no code names an internal collection outside DatrisEnvironment") {
        // a store call whose collection argument is built by concatenation
        val derived = """NoSQLDbUtil\s*\.\s*\w+\(\s*[^,()]*\+""".r
        val literal = """NoSQLDbUtil\s*\.\s*\w+\(\s*"""".r
        val offenders = for {
            p <- sources
            if !p.toString.endsWith("model/DatrisEnvironment.scala")
            text = read(p)
            m <- derived.findAllMatchIn(text).toSeq ++ literal.findAllMatchIn(text).toSeq
        } yield p.getFileName.toString + ": " + text.substring(m.start, math.min(text.length, m.end + 30)).linesIterator.next()
        assert(offenders.isEmpty, offenders.mkString("\n"))
    }

    test("the internal database is opened only by the platform's own store") {
        val uses = for {
            p <- sources
            text = read(p)
            if text.contains("getDatabase(") && """\binternalDatabase\b""".r.findFirstIn(text).isDefined
        } yield p.getFileName.toString
        assert(uses.toSet == Set("MongoDBUtil.scala"), uses)
    }

    test("the internal database name is read only where the platform configures, opens and protects it") {
        val allowed = Set("MongoDBUtil.scala", "MongoReservedNames.scala", "MongoDBConfig.scala", "StartupRunner.scala")
        val uses = sources.filter(p => """\binternalDatabase\b""".r.findFirstIn(read(p)).isDefined).map(_.getFileName.toString).toSet
        assert(uses.subsetOf(allowed), (uses -- allowed).toString)
    }

    test("the platform's store over the internal database is built in one place") {
        val build = """MongoDBUtilBuilder\s*\.\s*build\(\s*\)""".r
        val uses = sources.filter(p => build.findFirstIn(read(p)).isDefined).map(p => mainRoot.relativize(p).toString).toSet
        assert(uses == Set("ai/datris/util/package.scala"), uses)
    }

    test("no store call names a collection with a string literal") {
        val literal = """(?:\.collection|ensureTtlIndex|insertWithDateField|upsertWithDateField)\(\s*"""".r
        val offenders = for {
            p <- sources
            text = read(p)
            m <- literal.findAllMatchIn(text).toSeq
        } yield p.getFileName.toString + ": " + text.substring(m.start, math.min(text.length, m.end + 30)).linesIterator.next()
        assert(offenders.isEmpty, offenders.mkString("\n"))
    }

    test("legacy platform collections are covered for this environment, and for any prefix in a multi-tenant install") {
        for (n <- Seq("probe-dataset", "probe-dataset-status", "probe-dataset-status-summary")) assert(MongoReservedNames.platformCollection(n, env), n)
        assert(!MongoReservedNames.platformCollection("other-dataset", env))
        val mt = env.copy(multiTenant = true)
        for (n <- Seq("other-dataset", "t-1-dataset-status", "x-dataset-status-summary")) assert(MongoReservedNames.platformCollection(n, mt), n)
    }
}
