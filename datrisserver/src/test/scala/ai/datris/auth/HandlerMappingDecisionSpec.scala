package ai.datris.auth

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import org.scalatest.funsuite.AnyFunSuite
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider
import org.springframework.core.annotation.AnnotatedElementUtils
import org.springframework.core.`type`.filter.AnnotationTypeFilter
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.{RequestMapping, RequestMethod}

import scala.collection.JavaConverters._

/** Every handler mapping in the application has an access decision: a
  * [[CapabilityRoutes]] row or a skip pattern, for every method the
  * dispatcher routes to it (HEAD is decided as GET; OPTIONS never reaches a
  * controller method). A controller method added later without a decision
  * fails this spec, so it can never fall through the capability gate.
  *
  * The mappings are read from the controllers' own mapping annotations
  * (merged, so `@GetMapping` and friends count), with the class-level
  * `@RequestMapping` prefix, from every `@Controller` / `@RestController`
  * under `ai.datris`. */
class HandlerMappingDecisionSpec extends AnyFunSuite {

    /** Routes deliberately outside the interceptor chain (WebMvcConfig
      * excludes them), with how each is authenticated. */
    private val OutsideInterceptors: Map[String, String] = Map(
        "/minio-events" -> "object-store bucket notifications; outside /api/**, excluded from every interceptor in WebMvcConfig"
    )

    private val AllMethods = Seq("GET", "POST", "PUT", "PATCH", "DELETE")

    /** (methods, path pattern, controller.method) for every handler mapping. */
    private lazy val mappings: Seq[(Seq[String], String, String)] = {
        val scanner = new ClassPathScanningCandidateComponentProvider(false)
        scanner.addIncludeFilter(new AnnotationTypeFilter(classOf[Controller]))
        // Application classes only: specs define stand-in controllers of their own.
        val classes = scanner.findCandidateComponents("ai.datris").asScala.toSeq.map(bd => Class.forName(bd.getBeanClassName))
            .filterNot(c => Option(c.getProtectionDomain.getCodeSource).exists(_.getLocation.getPath.contains("test-classes")))
        classes.flatMap { cls =>
            val prefixes = Option(AnnotatedElementUtils.findMergedAnnotation(cls, classOf[RequestMapping]))
                .map(a => (a.path().toSeq ++ a.value().toSeq).distinct)
                .filter(_.nonEmpty)
                .getOrElse(Seq(""))
            cls.getDeclaredMethods.toSeq.flatMap { m =>
                Option(AnnotatedElementUtils.findMergedAnnotation(m, classOf[RequestMapping])).toSeq.flatMap { a =>
                    val paths = (a.path().toSeq ++ a.value().toSeq).distinct match {
                        case Seq() => Seq("")
                        case ps => ps
                    }
                    val methods = a.method().toSeq.map(_.name()) match {
                        case Seq() => AllMethods
                        case ms => ms
                    }
                    for (pre <- prefixes; p <- paths) yield (methods, pre + p, cls.getSimpleName + "." + m.getName)
                }
            }
        }
    }

    /** A concrete path the pattern matches: `{var}` -> `example`. */
    private def concrete(pattern: String): String = pattern.replaceAll("\\{\\*[^}]+\\}", "example/item").replaceAll("\\{[^}]+\\}", "example")

    test("the scan finds the application's handler mappings") {
        assert(mappings.size > 140, mappings.size)
        assert(mappings.exists(_._2 == "/api/v1/query/postgres"))
        assert(mappings.exists(_._2 == "/minio-events"))
    }

    test("every handler mapping has a capability row or a skip pattern for every method it accepts") {
        val undecided = for {
            (methods, pattern, where) <- mappings
            if !OutsideInterceptors.contains(pattern)
            m <- methods
            if CapabilityRoutes.lookup(m, concrete(pattern)) == RouteCheck.Unmapped
        } yield s"$m $pattern ($where)"
        assert(undecided.isEmpty, "handler mappings with no access decision: " + undecided.distinct.sorted.mkString(", "))
    }

    test("every handler is restricted to explicit methods, and none maps OPTIONS or HEAD itself") {
        val scanner = mappings.filter(_._1 == AllMethods)
        assert(scanner.isEmpty, "mapped without a method restriction: " + scanner.map(_._3).mkString(", "))
        val explicit = mappings.filter(m => m._1.contains("OPTIONS") || m._1.contains("HEAD"))
        assert(explicit.isEmpty, explicit)
        assert(RequestMethod.values().map(_.name()).contains("OPTIONS"))
    }

    test("every handler outside /api is excluded from the interceptors on purpose") {
        val outside = mappings.map(_._2).filterNot(_.startsWith("/api/")).distinct
        assert(outside.toSet == OutsideInterceptors.keySet, outside)
    }
}
