package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import org.scalatest.funsuite.AnyFunSuite

import java.util.Properties

/** Guards the log4j dependencyOverrides family in build.sbt (pinned over the
  *  version Spark ships). A core/api skew breaks logging initialization at
  *  server startup, not at compile time — same failure shape as the Jackson
  *  pin guarded by SparkJacksonCompatSpec. Versions are read from each jar's
  *  Maven pom.properties, which every log4j artifact ships.
  */
class Log4jFamilyConsistencySpec extends AnyFunSuite {

    private val artifacts = Seq("log4j-api", "log4j-core", "log4j-1.2-api", "log4j-slf4j2-impl")

    private def versionOf(artifact: String): String = {
        val path = s"/META-INF/maven/org.apache.logging.log4j/$artifact/pom.properties"
        val stream = getClass.getResourceAsStream(path)
        assert(stream != null, s"$artifact not on the test classpath (or no pom.properties) — family list is stale")
        try {
            val props = new Properties()
            props.load(stream)
            props.getProperty("version")
        } finally {
            stream.close()
        }
    }

    test("all log4j artifacts on the classpath resolve to one version") {
        val versions = artifacts.map(a => a -> versionOf(a))
        assert(
            versions.map(_._2).toSet.size == 1,
            s"log4j family is skewed: ${versions.map { case (a, v) => s"$a=$v" }.mkString(", ")} — " +
                "bump the build.sbt log4j overrides together"
        )
    }
}
