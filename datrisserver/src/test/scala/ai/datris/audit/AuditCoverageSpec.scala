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
}
