package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.config.RequiresRole
import org.scalatest.funsuite.AnyFunSuite
import org.springframework.web.bind.annotation.{PostMapping, RequestMapping, RestController}

/** Story: Configuration chat, server side (plans/stories/config-chat-server-seam.md),
  * Step 7: `POST /api/v1/config-chat/chat`, class-level admin role gate. Loaded by
  * name so the spec compiles before the controller exists; the live 403 for a
  * non-admin session is covered in e2e. */
class ConfigChatAPIControllerSpec extends AnyFunSuite {

    private def cls: Class[_] =
        Class.forName("ai.datris.api.ConfigChatAPIController", false, getClass.getClassLoader)

    test("controller is a RestController under /api/v1 with a class-level admin role gate") {
        val c = cls
        assert(c.getAnnotation(classOf[RestController]) != null)
        assert(Option(c.getAnnotation(classOf[RequestMapping])).exists(_.value().contains("/api/v1")))
        val role = c.getAnnotation(classOf[RequiresRole])
        assert(role != null, "ConfigChatAPIController must carry @RequiresRole at class level")
        assert(role.value().toSeq == Seq("admin"))
    }

    test("controller maps POST /config-chat/chat") {
        val paths = cls.getMethods.toSeq.flatMap { m =>
            Option(m.getAnnotation(classOf[PostMapping])).toSeq.flatMap(a => (a.path() ++ a.value()).toSeq)
        }
        assert(paths.contains("/config-chat/chat"), s"POST paths: $paths")
    }
}
