package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import org.scalatest.funsuite.AnyFunSuite

import scala.collection.JavaConverters._

/** Guards the netty dependencyOverrides family in build.sbt. Mixed netty jars
  *  fail at runtime with NoSuchMethodError, and the override list only
  *  protects artifacts it names — a NEW transitive netty artifact (dragged in
  *  by a future Spark/azure/qdrant bump) resolves to whatever version its
  *  parent declares and silently skews the family. Version.identify() reads
  *  each netty jar's version properties off the classpath, so any straggler
  *  shows up here before it can fail on a live connection.
  */
class NettyFamilyConsistencySpec extends AnyFunSuite {

    test("every io.netty artifact on the classpath resolves to one version") {
        val versions = io.netty.util.Version.identify().asScala
        assert(versions.nonEmpty, "no netty artifacts found — Version.identify() returned nothing")
        val byVersion = versions.values.map(_.artifactVersion()).toSet
        assert(
            byVersion.size == 1,
            s"netty family is skewed: ${versions.map { case (a, v) => s"$a=${v.artifactVersion()}" }.mkString(", ")} — " +
                "add the missing artifact to the build.sbt netty overrides"
        )
    }
}
