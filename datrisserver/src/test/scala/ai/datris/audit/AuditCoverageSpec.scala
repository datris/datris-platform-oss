package ai.datris.audit

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import org.scalatest.funsuite.AnyFunSuite
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider
import org.springframework.core.`type`.filter.AnnotationTypeFilter
import org.springframework.web.bind.annotation._

import scala.collection.JavaConverters._

/** Every non-GET controller route must be classifiable — either through
  * CapabilityRoutes or the audit supplemental table — or be on the capability
  * skip-list on purpose (chat streams, health). A new write endpoint that
  * matches neither would be logged as `unmapped`, which is the coverage alarm
  * this spec turns into a build failure. */
class AuditCoverageSpec extends AnyFunSuite {

    private case class Mapped(method: String, path: String, controller: String)

    private def routes(): Seq[Mapped] = {
        val scanner = new ClassPathScanningCandidateComponentProvider(false)
        scanner.addIncludeFilter(new AnnotationTypeFilter(classOf[RestController]))
        val out = Seq.newBuilder[Mapped]
        scanner.findCandidateComponents("ai.datris").asScala.foreach { bd =>
            val cls = Class.forName(bd.getBeanClassName)
            val base = Option(cls.getAnnotation(classOf[RequestMapping])).flatMap(_.value().headOption).getOrElse("")
            cls.getMethods.foreach { m =>
                def add(method: String, paths: Array[String]): Unit =
                    (if (paths.isEmpty) Array("") else paths).foreach(p => out += Mapped(method, base + p, cls.getSimpleName))
                Option(m.getAnnotation(classOf[PostMapping])).foreach(a => add("POST", if (a.path().nonEmpty) a.path() else a.value()))
                Option(m.getAnnotation(classOf[PutMapping])).foreach(a => add("PUT", if (a.path().nonEmpty) a.path() else a.value()))
                Option(m.getAnnotation(classOf[PatchMapping])).foreach(a => add("PATCH", if (a.path().nonEmpty) a.path() else a.value()))
                Option(m.getAnnotation(classOf[DeleteMapping])).foreach(a => add("DELETE", if (a.path().nonEmpty) a.path() else a.value()))
                Option(m.getAnnotation(classOf[RequestMapping])).foreach { a =>
                    a.method().foreach(rm => if (rm != RequestMethod.GET) add(rm.name(), if (a.path().nonEmpty) a.path() else a.value()))
                }
            }
        }
        out.result()
    }

    /** Turn a Spring path template into a concrete sample path for matching. */
    private def sample(path: String): String =
        path.replaceAll("\\{[^}]+\\}", "sample").replace("**", "sample")

    test("every non-GET controller route is audited or deliberately skipped") {
        val all = routes().filter(_.path.startsWith("/api/"))
        assert(all.nonEmpty, "controller scan found no routes — classpath scanning broke")
        val gaps = all.filterNot { r =>
            val p = sample(r.path)
            AuditClassifier.isKnown(r.method, p) || AuditClassifier.isDeliberatelySkipped(r.method, p)
        }
        assert(
            gaps.isEmpty,
            "Non-GET routes with no audit classification (add to CapabilityRoutes, the audit supplemental table, or AuditClassifier.neverAudited):\n  " +
                gaps.map(g => g.method + " " + g.path + "  (" + g.controller + ")").sorted.mkString("\n  ")
        )
    }

    test("every neverAudited entry still matches a live route (no stale allowlist)") {
        val live = routes()
        val stale = AuditClassifier.neverAudited.filterNot { case (m, pattern, _) =>
            live.exists(r => r.method == m && (r.path == pattern || sample(r.path) == sample(pattern)))
        }
        assert(stale.isEmpty, "Stale entries in AuditClassifier.neverAudited: " + stale.map(e => e._1 + " " + e._2).mkString(", "))
    }

    // Story: plans/stories/config-chat-server-seam.md, Step 9 — the config chat
    // stream is on both skip lists, so its route passes the coverage check.
    test("config-chat stream is deliberately skipped by audit and capability routing") {
        assert(AuditClassifier.isDeliberatelySkipped("POST", "/api/v1/config-chat/chat"))
        assert(ai.datris.auth.CapabilityRoutes.lookup("POST", "/api/v1/config-chat/chat") == ai.datris.auth.RouteCheck.Skip)
    }

    // Story: plans/stories/catalog-ops-server-mcp.md, Step 4 — the catalog
    // rename/delete endpoints exist as controller routes and are classified by
    // the new CapabilityRoutes rows (one audit entry per request).
    test("catalog rename and delete routes exist and are audit-classified") {
        val live = routes().map(r => (r.method, r.path))
        assert(live.contains(("PUT", "/api/v1/catalog/{name}")), "PUT /api/v1/catalog/{name} route missing")
        assert(live.contains(("DELETE", "/api/v1/catalog/{name}")), "DELETE /api/v1/catalog/{name} route missing")
        assert(AuditClassifier.isKnown("PUT", "/api/v1/catalog/sample"))
        assert(AuditClassifier.isKnown("DELETE", "/api/v1/catalog/sample"))
        assert(!AuditClassifier.isDeliberatelySkipped("PUT", "/api/v1/catalog/sample"))
        assert(!AuditClassifier.isDeliberatelySkipped("DELETE", "/api/v1/catalog/sample"))
    }

    // Story: plans/stories/catalog-backup-restore.md — the restore route is a
    // live controller route and is audit-classified (export is a GET: not audited).
    test("catalog restore route exists and is audit-classified") {
        val live = routes().map(r => (r.method, r.path))
        assert(live.contains(("POST", "/api/v1/catalog/{name}/restore")), "POST /api/v1/catalog/{name}/restore route missing")
        assert(AuditClassifier.isKnown("POST", "/api/v1/catalog/sample/restore"))
        assert(!AuditClassifier.isDeliberatelySkipped("POST", "/api/v1/catalog/sample/restore"))
    }

    // Story: plans/stories/tap-dependency-order.md, Step 3 — the enable-order
    // endpoint is a live GET controller route and is not audited (a read).
    test("catalog enable-order route exists as a GET and is not audited") {
        val gets = {
            val scanner = new ClassPathScanningCandidateComponentProvider(false)
            scanner.addIncludeFilter(new AnnotationTypeFilter(classOf[RestController]))
            scanner.findCandidateComponents("ai.datris").asScala.toSeq.flatMap { bd =>
                val cls = Class.forName(bd.getBeanClassName)
                val base = Option(cls.getAnnotation(classOf[RequestMapping])).flatMap(_.value().headOption).getOrElse("")
                cls.getMethods.toSeq.flatMap { m =>
                    Option(m.getAnnotation(classOf[GetMapping])).toSeq.flatMap { a =>
                        val paths = if (a.path().nonEmpty) a.path() else a.value()
                        (if (paths.isEmpty) Array("") else paths).map(base + _)
                    }
                }
            }
        }
        assert(gets.contains("/api/v1/catalog/{name}/enable-order"), "GET /api/v1/catalog/{name}/enable-order route missing")
        assert(AuditClassifier.classify("GET", "/api/v1/catalog/sample/enable-order", logReads = false).isEmpty)
        assert(ai.datris.auth.CapabilityRoutes.lookup("GET", "/api/v1/catalog/sample/enable-order") ==
            ai.datris.auth.RouteCheck.Require("tap", "read"))
    }

    // Story: plans/stories/tap-dependency-order.md, lazy backfill. GET /tap
    // and GET enable-order may write the dependency stamp, but they remain
    // reads: neither is audited (with logReads off), and both stay tap:read.
    test("dependency backfill reads stay unaudited GETs") {
        for (path <- Seq("/api/v1/tap", "/api/v1/catalog/sample/enable-order")) {
            assert(AuditClassifier.classify("GET", path, logReads = false).isEmpty, path)
            assert(ai.datris.auth.CapabilityRoutes.lookup("GET", path) == ai.datris.auth.RouteCheck.Require("tap", "read"), path)
        }
    }

    // Story: plans/stories/tap-dependency-order-enable.md — the ordered enable
    // start (POST) and cancel (DELETE) are live routes classified as tap:run
    // (one audit entry each); the status GET is a plain unaudited tap read,
    // not a deliberately-skipped route.
    test("catalog enable-ordered POST and DELETE exist and are audit-classified; GET is an unaudited read") {
        val live = routes().map(r => (r.method, r.path))
        assert(live.contains(("POST", "/api/v1/catalog/{name}/enable-ordered")), "POST /api/v1/catalog/{name}/enable-ordered route missing")
        assert(live.contains(("DELETE", "/api/v1/catalog/{name}/enable-ordered")), "DELETE /api/v1/catalog/{name}/enable-ordered route missing")
        for (m <- Seq("POST", "DELETE")) {
            assert(AuditClassifier.isKnown(m, "/api/v1/catalog/sample/enable-ordered"), m)
            assert(!AuditClassifier.isDeliberatelySkipped(m, "/api/v1/catalog/sample/enable-ordered"), m)
            val c = AuditClassifier.classify(m, "/api/v1/catalog/sample/enable-ordered", logReads = false)
            assert(c.map(r => (r.category, r.action)).contains(("tap", "run")), m + " -> " + c)
        }
        assert(!AuditClassifier.isDeliberatelySkipped("GET", "/api/v1/catalog/sample/enable-ordered"))
        assert(AuditClassifier.classify("GET", "/api/v1/catalog/sample/enable-ordered", logReads = false).isEmpty)
        assert(ai.datris.auth.CapabilityRoutes.lookup("GET", "/api/v1/catalog/sample/enable-ordered") ==
            ai.datris.auth.RouteCheck.Require("tap", "read"))
    }

    // Story: plans/stories/self-healing-3-scratch-first-verify.md, Acceptance
    // bullet 4 (the parts a unit spec can reach) — POST /incidents/{id}/proceed
    // is a live controller route, classified by its own CapabilityRoutes row
    // (`incident:proceed`), so it is audited and scoped keys need the new key.
    test("incident proceed route exists, is audit-classified and maps to incident:proceed") {
        val live = routes().map(r => (r.method, r.path))
        assert(live.contains(("POST", "/api/v1/incidents/{id}/proceed")), "POST /api/v1/incidents/{id}/proceed route missing")
        assert(AuditClassifier.isKnown("POST", "/api/v1/incidents/sample/proceed"))
        assert(!AuditClassifier.isDeliberatelySkipped("POST", "/api/v1/incidents/sample/proceed"))
        assert(ai.datris.auth.CapabilityRoutes.lookup("POST", "/api/v1/incidents/sample/proceed") ==
            ai.datris.auth.RouteCheck.Require("incident", "proceed"))
        // abandon keeps its own key
        assert(ai.datris.auth.CapabilityRoutes.lookup("POST", "/api/v1/incidents/sample/abandon") ==
            ai.datris.auth.RouteCheck.Require("incident", "abandon"))
    }

    // Story: plans/stories/self-healing-5a-script-versions.md — the script
    // version restore is a live POST route classified by its CapabilityRoutes row.
    test("codegen script version restore route exists and is audit-classified") {
        val live = routes().map(r => (r.method, r.path))
        assert(
            live.contains(("POST", "/api/v1/pipelines/{name}/codegen-scripts/{kind}/versions/{version}/restore")),
            "POST /api/v1/pipelines/{name}/codegen-scripts/{kind}/versions/{version}/restore route missing"
        )
        assert(AuditClassifier.isKnown("POST", "/api/v1/pipelines/sample/codegen-scripts/sample/versions/sample/restore"))
        assert(!AuditClassifier.isDeliberatelySkipped("POST", "/api/v1/pipelines/sample/codegen-scripts/sample/versions/sample/restore"))
    }
}
