package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import org.scalatest.funsuite.AnyFunSuite
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.convert.ApplicationConversionService
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.core.env.{MapPropertySource, SystemEnvironmentPropertySource}

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import scala.collection.JavaConverters._

/** Holds the flag exactly as `StartupRunner` declares it, so the spec reads a
  * value through the same placeholder, default and boolean conversion the
  * server uses (the declaration itself is pinned below). */
class MultiTenantFlagProbe {
    @Value("${multiTenant:false}")
    var multiTenant: Boolean = _
}

/** Story: retire the multi-tenant setting (plans/stories/retire-multi-tenant-setting.md).
  *
  * `MultiTenantRetired.problem(flagOn: Boolean): Option[String]` is pure: None
  * when the setting is off, the operator-facing message when it is on.
  * `StartupRunner.run` calls it as its first statement, logs the message at
  * ERROR and throws `DatrisException`, before anything connects to a store,
  * creates an index, bootstraps a user or lets a scheduler do work (the
  * schedulers wait for `DatrisEnvironment.current.initialized`, which only
  * `initDatrisEnvironment()` sets).
  */
class MultiTenantRetiredSpec extends AnyFunSuite {

    private def mainRoot: Path = Seq(Paths.get("src/main/scala"), Paths.get("datrisserver/src/main/scala")).find(Files.exists(_)).get
    private def read(p: Path): String = new String(Files.readAllBytes(p), StandardCharsets.UTF_8)
    private def startupRunner: String = read(mainRoot.resolve("ai/datris/StartupRunner.scala"))

    private def message: String = {
        val m = MultiTenantRetired.problem(true)
        assert(m.isDefined, "setting on must give a message")
        m.get
    }

    // ---------------------------------------------------------------- Acceptance bullet 1

    test("setting off gives no problem") {
        assert(MultiTenantRetired.problem(false).isEmpty)
    }

    test("setting on gives a message that says the server did not start, that nothing was changed, and how to keep running") {
        val m = message
        val lower = m.toLowerCase
        assert(m.contains("MULTI_TENANT"), "names the setting: " + m)
        assert(lower.contains("multi-tenant"), "says what the setting is: " + m)
        assert(lower.contains("no longer support"), "says it is no longer supported: " + m)
        assert("""not (been )?start""".r.findFirstIn(lower).isDefined, "says the server has not started: " + m)
        assert(
            """nothing (was|has been) changed|no data (was|has been) (changed|touched)""".r.findFirstIn(lower).isDefined,
            "says nothing was changed: " + m
        )
        assert(lower.contains("remove"), "says to remove the setting: " + m)
        assert(lower.contains("previous version"), "says how to keep running (the previous version): " + m)
        assert(m.contains("docs.datris.ai/production/upgrades"), "links the upgrade note: " + m)
        assert(MultiTenantRetired.problem(true) == MultiTenantRetired.problem(true), "the same message on every restart")
    }

    test("the message names no tenant and no secret") {
        val m = message
        val lower = m.toLowerCase
        Seq("api-key-mappings", "oss/", "vault", "secret/", "tenant-", "acme").foreach { s =>
            assert(!lower.contains(s), "the message must not contain '" + s + "': " + m)
        }
        // plain words: no internal class, package or exception name
        Seq("ai.datris", "MultiTenantRetired", "StartupRunner", "DatrisEnvironment", "DatrisException", "Exception", "TenantInterceptor")
            .foreach(s => assert(!m.contains(s), "the message must not name '" + s + "': " + m))
    }

    // ------------------------------------------- every spelling the server's parser accepts

    /** What the server's `@Value("${multiTenant:false}") var multiTenant: Boolean`
      * receives for an environment value (compose forwards `.env` MULTI_TENANT
      * as MULTITENANT; Spring maps it to `multiTenant`), with the conversion
      * service Spring Boot installs. Left = the value does not convert, which
      * stops startup today before `run`. */
    private def serverReads(envValue: Option[String], yamlValue: Option[String] = None): Either[String, Boolean] = {
        val ctx = new AnnotationConfigApplicationContext()
        try {
            ctx.getBeanFactory.setConversionService(ApplicationConversionService.getSharedInstance)
            val sources = ctx.getEnvironment.getPropertySources
            yamlValue.foreach(v => sources.addFirst(new MapPropertySource("yaml", Map[String, AnyRef]("multiTenant" -> v).asJava)))
            envValue.foreach(v => sources.addFirst(new SystemEnvironmentPropertySource("env", Map[String, AnyRef]("MULTITENANT" -> v).asJava)))
            ctx.register(classOf[MultiTenantFlagProbe])
            ctx.refresh()
            Right(ctx.getBean(classOf[MultiTenantFlagProbe]).multiTenant)
        } catch { case e: Exception => Left(e.getClass.getSimpleName + ": " + e.getMessage) }
        finally ctx.close()
    }

    private val readAsTrue = Seq("true", "TRUE", "True", " true ", "1", "yes", "YES", "Yes", "on", "ON", "On", " on")
    private val readAsFalse = Seq("false", "FALSE", "False", " false ", "0", "no", "NO", "off", "OFF", "Off")

    test("the server still reads the setting through @Value(\"${multiTenant:false}\") into a Boolean") {
        assert(
            """@Value\("\$\{multiTenant:false\}"\)\s*var multiTenant: Boolean""".r.findFirstIn(startupRunner).isDefined,
            "StartupRunner's flag declaration changed; the spelling table in this spec no longer describes the server"
        )
    }

    test("every value the server reads as true is refused") {
        readAsTrue.foreach { v =>
            val parsed = serverReads(Some(v))
            assert(parsed == Right(true), "'" + v + "' -> " + parsed)
            assert(MultiTenantRetired.problem(parsed.toOption.get).isDefined, "'" + v + "' must be refused")
        }
        // set in a config file instead of the environment
        assert(serverReads(None, yamlValue = Some("true")).toOption.exists(on => MultiTenantRetired.problem(on).isDefined))
    }

    test("every value the server reads as false, and an absent setting, proceeds with no problem") {
        readAsFalse.foreach { v =>
            val parsed = serverReads(Some(v))
            assert(parsed == Right(false), "'" + v + "' -> " + parsed)
            assert(MultiTenantRetired.problem(parsed.toOption.get).isEmpty, "'" + v + "' must proceed")
        }
        assume(sys.env.get("MULTITENANT").isEmpty && sys.props.get("multiTenant").isEmpty, "the test JVM sets the flag")
        assert(serverReads(None) == Right(false))
        assert(MultiTenantRetired.problem(false).isEmpty)
        // the bundled application.yaml value
        assert(serverReads(None, yamlValue = Some("false")) == Right(false))
    }

    test("a value the server cannot read as a boolean still stops startup as it does today, before run") {
        // Not a new behaviour: Spring fails to inject the field, so run is never reached.
        // An empty value is one of these; the compose default `${MULTI_TENANT:-false}`
        // turns an empty or missing `.env` value into "false" before it reaches the server.
        Seq("", "  ", "maybe", "enabled", "2", "t", "y").foreach(v => assert(serverReads(Some(v)).isLeft, "'" + v + "' -> " + serverReads(Some(v))))
    }

    // -------------------------------------- Acceptance bullet 2 (seam; the live exit is Verify)

    private def runBody: Seq[String] = {
        val lines = startupRunner.linesIterator.toVector
        val start = lines.indexWhere(_.contains("def run(args: ApplicationArguments): Unit"))
        assert(start >= 0, "StartupRunner.run not found")
        lines.drop(start + 1).map(_.trim).filter(l => l.nonEmpty && !l.startsWith("//"))
    }

    test("StartupRunner.run decides on the setting before anything else it does") {
        val body = runBody
        assert(body.head.contains("MultiTenantRetired") && body.head.contains("multiTenant"), "first statement of run: " + body.head)
        val check = body.indexWhere(_.contains("MultiTenantRetired"))
        Seq(
            "initDatrisEnvironment()",
            "TapScriptRunner.assertIsolationConfig()",
            "CodeGenRunner.assertConfig()",
            "RecoveryKey.ensure()",
            "TapSecretScopeScan.logAtStartup()"
        ).foreach { s =>
            val at = body.indexWhere(_.contains(s))
            assert(at < 0 || check < at, s + " runs before the multi-tenant check")
        }
    }

    test("the refusal logs the message at ERROR and stops with a DatrisException") {
        val body = runBody
        val upTo = body.indexWhere(_.contains("TapScriptRunner.assertIsolationConfig()"))
        val prefix = (if (upTo < 0) body.take(8) else body.take(upTo)).mkString("\n")
        val helper = Option(mainRoot.resolve("ai/datris/util/MultiTenantRetired.scala")).filter(Files.exists(_)).map(read).getOrElse("")
        val refusal = prefix + "\n" + helper
        assert(prefix.contains("MultiTenantRetired"), prefix)
        assert(refusal.contains("logger.error"), "the message is logged at ERROR:\n" + prefix)
        assert(refusal.contains("throw new DatrisException") || refusal.contains("throw new ai.datris.model.DatrisException"), prefix)
    }

    test("the OIDC multi-tenant warning is gone from startup") {
        val warns = startupRunner.contains("not verified with MULTI_TENANT")
        assert(!warns, "StartupRunner still logs the OIDC multi-tenant warning, which can no longer be reached")
    }
}
