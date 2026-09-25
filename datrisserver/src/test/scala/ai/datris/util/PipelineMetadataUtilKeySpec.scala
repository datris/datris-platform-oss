package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.DatrisException
import org.scalatest.funsuite.AnyFunSuite

import java.util.UUID

class PipelineMetadataUtilKeySpec extends AnyFunSuite {

    // A status sink that swallows everything: read() on a plain data key touches no I/O.
    private class SilentStatusUtil extends StatusUtil {
        override def overrideProcessName(processName: String): Unit = ()
        override def info(state: String, description: String): Unit = ()
        override def warn(state: String, description: String): Unit = ()
        override def error(state: String, description: String): Unit = ()
    }

    private def util = new PipelineMetadataUtil(new SilentStatusUtil)

    private val uuid = UUID.randomUUID().toString

    test("splitKey: a key with no slash is an empty prefix plus the whole key") {
        assert(util.splitKey("stock.x.pipeline.csv") == (("", "stock.x.pipeline.csv")))
    }

    test("splitKey: a nested key splits on the last slash") {
        assert(util.splitKey("a/b/c.csv") == (("a/b", "c.csv")))
    }

    test("read: a root-level key ingests with the bucket root as dataFilePath") {
        val name = s"stock.$uuid.1.pipeline.csv"
        val md = util.read("oss-raw", name)
        assert(md.dataFilePath == "s3://oss-raw/")
        assert(md.dataFileName == name)
        assert(md.pipeline == "stock")
        assert(md.publisherToken == uuid)
        assert(!md.bulkUpload)
    }

    test("read: a one-level prefixed key is unchanged") {
        val name = s"stock.$uuid.1.pipeline.csv"
        val md = util.read("oss-raw", s"in/$name")
        assert(md.dataFilePath == "s3://oss-raw/in/")
        assert(md.dataFileName == name)
        assert(md.pipeline == "stock")
    }

    test("read: a nested prefixed key is unchanged") {
        val name = s"stock.$uuid.1.pipeline.csv"
        val md = util.read("oss-raw", s"in/2026/$name")
        assert(md.dataFilePath == "s3://oss-raw/in/2026/")
        assert(md.dataFileName == name)
        assert(md.pipeline == "stock")
        assert(md.publisherToken == uuid)
    }

    test("read: a root-level filename with a single dot-segment still raises the format error") {
        val e = intercept[DatrisException](util.read("oss-raw", "noprefix-single-token"))
        assert(e.getMessage.startsWith("Could not parse the pipeline and/or filename"))
    }

    test("read: a prefixed filename with a single dot-segment still raises the format error") {
        val e = intercept[DatrisException](util.read("oss-raw", "in/noprefix-single-token"))
        assert(e.getMessage.startsWith("Could not parse the pipeline and/or filename"))
    }
}
