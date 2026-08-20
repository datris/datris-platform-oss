package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import org.scalatest.funsuite.AnyFunSuite

/** Guards the Jackson dependencyOverrides pin in build.sbt. Spark's
  *  RDDOperationScope builds an ObjectMapper with DefaultScalaModule at class
  *  init; jackson-module-scala strictly checks that jackson-databind matches
  *  its own minor version and throws on module registration if the family is
  *  skewed. That path is only reached at runtime (any destination using
  *  SparkSession), so without this test a bad pin compiles green and fails on
  *  the first ingestion. Registration is exercised directly rather than via a
  *  local SparkSession: Hadoop 3.3.4's UserGroupInformation needs the
  *  SecurityManager APIs removed in JDK 24+, so SparkContext cannot start on
  *  newer dev-machine JDKs (the Docker runtime is temurin 17).
  */
class SparkJacksonCompatSpec extends AnyFunSuite {

    test("jackson-databind and jackson-module-scala versions match") {
        val databind = com.fasterxml.jackson.databind.cfg.PackageVersion.VERSION
        val moduleScala = new DefaultScalaModule().version()
        assert(
            databind.getMajorVersion == moduleScala.getMajorVersion &&
                databind.getMinorVersion == moduleScala.getMinorVersion,
            s"jackson-databind $databind vs jackson-module-scala $moduleScala — bump the build.sbt overrides together"
        )
    }

    test("DefaultScalaModule registers and round-trips (the RDDOperationScope init path)") {
        val mapper = new ObjectMapper().registerModule(DefaultScalaModule)
        val json = mapper.writeValueAsString(Map("name" -> "smoke", "id" -> 1))
        assert(json.contains("\"name\":\"smoke\""))
        val back = mapper.readValue(json, classOf[Map[String, Any]])
        assert(back("name") == "smoke")
    }
}
