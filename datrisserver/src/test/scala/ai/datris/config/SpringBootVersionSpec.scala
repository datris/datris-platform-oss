package ai.datris.config

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import org.scalatest.funsuite.AnyFunSuite
import org.springframework.boot.SpringBootVersion
import org.springframework.core.SpringVersion

/** Guards the Spring Boot 4 line in build.sbt. Boot 4.0.8 manages Spring
  * Framework 7.0.9, which closes the spring-webmvc 6.2.x advisories. Application
  * code stays on Jackson 2 through Boot's Jackson 2 compatibility module; Jackson
  * 3 (tools.jackson) must not reach the classpath at all, because Boot's Jackson 2
  * HTTP converter configuration is conditional only on Jackson 2 being present,
  * so a later bump that drags Jackson 3 in would silently change which mapper
  * deserialises @RequestBody payloads. */
class SpringBootVersionSpec extends AnyFunSuite {

    test("server runs Spring Boot 4.0.8 and Framework 7.0.x") {
        assert(SpringBootVersion.getVersion == "4.0.8")
        val framework = SpringVersion.getVersion
        assert(framework != null && framework.startsWith("7.0."), s"Spring Framework version was $framework")
    }

    test("Jackson 3 is not on the classpath") {
        assertThrows[ClassNotFoundException] {
            Class.forName("tools.jackson.databind.ObjectMapper")
        }
    }

    test("the Boot Jackson 2 compatibility auto-configuration is present") {
        val cls = Class.forName("org.springframework.boot.jackson2.autoconfigure.Jackson2AutoConfiguration")
        assert(cls != null)
    }
}
