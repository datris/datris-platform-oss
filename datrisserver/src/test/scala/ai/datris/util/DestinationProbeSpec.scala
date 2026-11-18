package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.UnityCatalogSyncState
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FileSystem, Path}
import org.scalatest.funsuite.AnyFunSuite

import java.io.FileNotFoundException
import java.nio.file.Files
import java.sql.SQLException

/** Story: Catalog-scoped user access: a restricted user cannot point a
  * pipeline at a table or prefix that already exists (and then preview it),
  * review findings 3 and 6. The classifiers decide fail-open ("does not
  * exist") versus fail-closed (500): each is true only for its "definitely
  * absent" error. The object-store probe runs against a local directory. */
class DestinationProbeSpec extends AnyFunSuite {

    private def sql(msg: String, code: Int = 0, state: String = null) = new SQLException(msg, state, code)
    private def wrapped(e: Throwable): Throwable = new RuntimeException("context", e)

    private val authAndNetwork: Seq[Throwable] = Seq(
        sql("Incorrect username or password was specified.", 390100),
        sql("JDBC driver encountered communication error. Message: HTTP status=403"),
        sql("[PERMISSION_DENIED] User does not have USE CATALOG on Catalog 'main'.", 500051, "42501"),
        sql("Login failed for user 'svc'.", 18456),
        new java.net.ConnectException("Connection refused"),
        new java.net.SocketTimeoutException("connect timed out"),
        new RuntimeException("something unknown"),
        new java.nio.file.AccessDeniedException("s3a://b/p: getFileStatus on s3a://b/p: 403 Forbidden")
    )

    test("snowflakeMissingDatabase is true only for error 2003 / does not exist or not authorized") {
        val missing = sql("SQL compilation error:\nDatabase 'NOPE' does not exist or not authorized.", 2003, "02000")
        assert(DestinationProbe.snowflakeMissingDatabase(missing))
        assert(DestinationProbe.snowflakeMissingDatabase(wrapped(missing)))
        authAndNetwork.foreach(e => assert(!DestinationProbe.snowflakeMissingDatabase(e), e))
    }

    test("databricksMissingCatalog is true only for a missing catalog, never for TABLE_OR_VIEW_NOT_FOUND") {
        assert(DestinationProbe.databricksMissingCatalog(sql("[NO_SUCH_CATALOG_EXCEPTION] Catalog 'nope' was not found.")))
        assert(DestinationProbe.databricksMissingCatalog(wrapped(sql("[CATALOG_NOT_FOUND] The catalog `nope` cannot be found."))))
        val noInfoSchema = sql("[TABLE_OR_VIEW_NOT_FOUND] The table or view `hive_metastore`.`information_schema`.`tables` cannot be found.", 0, "42P01")
        assert(!DestinationProbe.databricksMissingCatalog(noInfoSchema))
        assert(DestinationProbe.databricksInformationSchemaMissing(noInfoSchema))
        authAndNetwork.foreach { e =>
            assert(!DestinationProbe.databricksMissingCatalog(e), e)
            assert(!DestinationProbe.databricksInformationSchemaMissing(e), e)
            assert(!DestinationProbe.databricksObjectMissing(e), e)
        }
        // DESCRIBE TABLE fallback: a missing table, schema or catalog is absent
        assert(DestinationProbe.databricksObjectMissing(noInfoSchema))
        assert(DestinationProbe.databricksObjectMissing(sql("[SCHEMA_NOT_FOUND] The schema `hive_metastore`.`nope` cannot be found.")))
        assert(DestinationProbe.databricksObjectMissing(sql("[NO_SUCH_CATALOG_EXCEPTION] Catalog 'x' was not found.")))
    }

    test("sqlServerMissingDatabase is true only for error 4060 / Cannot open database") {
        val missing = sql("Cannot open database \"nope\" requested by the login. The login failed.", 4060, "S0001")
        assert(DestinationProbe.sqlServerMissingDatabase(missing))
        assert(DestinationProbe.sqlServerMissingDatabase(wrapped(missing)))
        authAndNetwork.foreach(e => assert(!DestinationProbe.sqlServerMissingDatabase(e), e))
    }

    test("objectStoreMissing is true only for a missing path or bucket") {
        assert(DestinationProbe.objectStoreMissing(new FileNotFoundException("No such file or directory: s3a://b/p")))
        assert(DestinationProbe.objectStoreMissing(wrapped(new FileNotFoundException("x"))))
        assert(DestinationProbe.objectStoreMissing(new org.apache.hadoop.fs.s3a.UnknownStoreException("s3a://nope", "bucket does not exist")))
        authAndNetwork.foreach(e => assert(!DestinationProbe.objectStoreMissing(e), e))
    }

    test("the object-store probe finds data under a prefix, through globs and '..', and nothing elsewhere") {
        val root = Files.createTempDirectory("dest-probe")
        try {
            Files.createDirectories(root.resolve("pt_orphan/year=2026"))
            Files.write(root.resolve("pt_orphan/year=2026/part-0.parquet"), Array[Byte](1, 2, 3))
            Files.createDirectories(root.resolve("empty_dir"))
            Files.createDirectories(root.resolve("zz"))
            Files.write(root.resolve("single_file"), Array[Byte](1))
            val fs = FileSystem.getLocal(new Configuration())
            def p(rel: String) = new Path("file://" + root.toAbsolutePath.toString + "/" + rel)
            def probe(rel: String) = DestinationProbe.anyObjectUnder(fs, p(rel))

            assert(probe("pt_orphan"))
            assert(probe("pt_orphan/year=2026"))
            assert(probe("single_file"))
            assert(probe(""))
            // globs expand the way a Spark read expands them
            assert(probe("pt_orph*"))
            assert(probe("[p]t_orphan"))
            assert(probe("{pt_orphan}"))
            assert(probe("pt_orpha?"))
            // '..' is normalised by Path
            assert(probe("zz/../pt_orphan"))
            assert(probe("a/../pt_orphan"))

            assert(!probe("missing"))
            assert(!probe("missing/deeper"))
            assert(!probe("missing*"))
            assert(!probe("empty_dir"))
            assert(!probe("pt_orphan2"))
            assert(!probe("pt_orphan/year=2027"))
        } finally {
            Files.walk(root).sorted(java.util.Comparator.reverseOrder()).forEach(f => Files.deleteIfExists(f))
        }
    }

    test("a kept Unity Catalog state counts as committed only for a managed or rest catalog commit") {
        def doc(mode: String, loc: String, at: String) =
            UnityCatalogSyncState("p", null, null, null, null, null, null, catalogMode = mode, restMetadataLocation = loc, lastRestCommitAt = at)
        assert(!DestinationProbe.committed(null))
        assert(DestinationProbe.committed(doc("managed", "s3://b/t/metadata/1.json", "2026-11-01T00:00:00Z")))
        assert(DestinationProbe.committed(doc("rest", "s3://b/p/metadata/1.json", "2026-11-01T00:00:00Z")))
        assert(!DestinationProbe.committed(doc("register", "s3://b/p/metadata/1.json", "2026-11-01T00:00:00Z")))
        assert(!DestinationProbe.committed(doc("managed", null, "2026-11-01T00:00:00Z")))
        assert(!DestinationProbe.committed(doc("rest", "s3://b/p/metadata/1.json", null)))
        assert(!DestinationProbe.committed(doc(null, null, null)))
    }

    test("the DESCRIBE TABLE fallback: an answer is true, not-found is false, anything else throws") {
        import org.mockito.Mockito.{mock, when}
        val sqlText = "DESCRIBE TABLE hive_metastore.default.t"
        val answers = mock(classOf[java.sql.Statement])
        when(answers.executeQuery(sqlText)).thenReturn(mock(classOf[java.sql.ResultSet]))
        assert(DestinationProbe.describeExists(answers, sqlText))

        for (
            msg <- Seq(
                "[TABLE_OR_VIEW_NOT_FOUND] The table or view `hive_metastore`.`default`.`t` cannot be found.",
                "[SCHEMA_NOT_FOUND] The schema `hive_metastore`.`nope` cannot be found.",
                "[NO_SUCH_CATALOG_EXCEPTION] Catalog 'nope' was not found."
            )
        ) {
            val missing = mock(classOf[java.sql.Statement])
            when(missing.executeQuery(sqlText)).thenThrow(sql(msg))
            assert(!DestinationProbe.describeExists(missing, sqlText), msg)
        }

        for (e <- Seq(sql("[PERMISSION_DENIED] User does not have SELECT on table", 0, "42501"), sql("Communication link failure"))) {
            val failing = mock(classOf[java.sql.Statement])
            when(failing.executeQuery(sqlText)).thenThrow(e)
            val thrown = intercept[SQLException](DestinationProbe.describeExists(failing, sqlText))
            assert(thrown eq e)
        }
    }
}
