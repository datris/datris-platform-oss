package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.audit.{AuditEntry, AuditLog}
import ai.datris.auth.ScopeDenialFixtures
import ai.datris.model.{Capability, DatrisEnvironment, ResolvedKey}
import org.scalatest.BeforeAndAfterEach
import org.scalatest.funsuite.AnyFunSuite

/** The caller-`sql` refusal notes the route's own capability for the audit
  * log, read from the route the dispatcher matched: a request spelled
  * differently from its canonical path names the same capability. */
class KeySqlRefusalRouteSpec extends AnyFunSuite with BeforeAndAfterEach {

    private var saved: DatrisEnvironment = _

    override def beforeEach(): Unit = {
        saved = DatrisEnvironment.values
        AuditLog.sinkOverride = (_: AuditEntry) => ()
        ScopeDenialFixtures.install(ScopeDenialFixtures.auditEnv())
    }

    override def afterEach(): Unit = {
        AuditLog.sinkOverride = null
        ScopeDenialFixtures.install(saved)
    }

    private val limited =
        ResolvedKey(None, "cat-sales", Capability.parseList(Seq("pipeline:read:catalog=Sales", "query:snowflake")), isLegacyFullAccess = false)

    private def spellings(path: String): Seq[String] = {
        val segs = path.split("/").toSeq
        val l = segs.length - 1
        val i = segs(l).indexWhere(_.isLetter)
        val enc = segs(l).take(i) + "%%%02x".format(segs(l)(i).toInt) + segs(l).drop(i + 1)
        Seq(path, segs.updated(l, segs(l) + ";x=1").mkString("/"), segs.updated(l, enc).mkString("/"), segs.updated(1, segs(1) + ";y").mkString("/"))
    }

    test("the noted capability is the matched route's, however the request is spelled") {
        for (route <- Seq("/api/v1/query/snowflake", "/api/v1/query/sqlserver", "/api/v1/query/databricks"); p <- spellings(route)) {
            val r = ScopeDenialFixtures.request(limited, "POST", p)._1
            assert(QueryAPIController.keySqlRefusal(r, Some("select 1")).exists(_.getStatusCode.value == 403), p)
            assert(AuditLog.scopeDenials(r).map(_._1) == Vector("query:" + route.split("/").last), s"$p: ${AuditLog.scopeDenials(r)}")
        }
    }
}
