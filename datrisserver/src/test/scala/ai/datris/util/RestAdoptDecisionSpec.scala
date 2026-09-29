package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import org.scalatest.funsuite.AnyFunSuite

/** Story: Unity Catalog 5: Iceberg via RESTCatalog (`catalogMode: rest`)
  * (plans/stories/unity-catalog-5-iceberg-restcatalog.md), Acceptance bullet 2
  * (Step 4).
  *
  * Seam this spec pins (pure; reuses IcebergCatalogRegistrar.classify/normalize):
  *
  * {{{
  *   object RestAdoptDecision {
  *     sealed trait Decision
  *     case object CreateNew extends Decision                                   // neither side has a table
  *     final case class AdoptPath(pathCurrent: String) extends Decision         // path table only → registerTable
  *     case object AdoptCatalog extends Decision                                // catalog already at our current file
  *     final case class RefuseBehind(catalogLoc: String, pathCurrent: String) extends Decision  // Stale
  *     final case class RefuseForeign(catalogLoc: String) extends Decision      // Foreign
  *     def decide(catalogHas: Option[String] /* catalog metadata-location */,
  *                pathCurrent: Option[String] /* path table's current metadata file */,
  *                tableRoot: String): Decision
  *   }
  * }}}
  *
  * Locations inside the case classes may come back in either s3 spelling, so
  * the assertions compare through IcebergCatalogRegistrar.normalize.
  */
class RestAdoptDecisionSpec extends AnyFunSuite {

    private val D = RestAdoptDecision
    private def n(s: String): String = IcebergCatalogRegistrar.normalize(s)

    private val ROOT = "s3a://datris-lake/orders_daily"
    private val OURS = ROOT + "/metadata/00002-7f1c.metadata.json"
    private val OLDER = ROOT + "/metadata/00001-3a9e.metadata.json"

    test("no table anywhere → CreateNew") {
        assert(D.decide(None, None, ROOT) == D.CreateNew)
    }

    test("path table only → AdoptPath with its metadata file") {
        D.decide(None, Some(OURS), ROOT) match {
            case D.AdoptPath(loc) => assert(n(loc) == n(OURS), loc)
            case other => fail(s"expected AdoptPath($OURS), got $other")
        }
    }

    test("catalog at our current file (s3 vs s3a) → AdoptCatalog") {
        assert(D.decide(Some(OURS), Some(OURS), ROOT) == D.AdoptCatalog)
        // The catalog reports s3://, we hold s3a:// (and the reverse).
        assert(D.decide(Some(n(OURS)), Some(OURS), ROOT) == D.AdoptCatalog)
        assert(D.decide(Some(OURS), Some(n(OURS)), n(ROOT)) == D.AdoptCatalog)
        assert(D.decide(Some(OURS.replace("s3a://", "s3n://")), Some(OURS), ROOT) == D.AdoptCatalog)
    }

    test("catalog at an older file under our metadata/ → RefuseBehind naming both") {
        D.decide(Some(n(OLDER)), Some(OURS), ROOT) match {
            case D.RefuseBehind(catalogLoc, pathLoc) =>
                assert(n(catalogLoc) == n(OLDER), catalogLoc)
                assert(n(pathLoc) == n(OURS), pathLoc)
            case other => fail(s"expected RefuseBehind($OLDER, $OURS), got $other")
        }
    }

    test("catalog outside our root, incl. sibling prefix → RefuseForeign") {
        val elsewhere = "s3://other-bucket/orders_daily/metadata/00001-aaaa.metadata.json"
        val sibling = "s3://datris-lake/orders_daily2/metadata/00001-bbbb.metadata.json"
        val underRootNotMetadata = "s3://datris-lake/orders_daily/data/00001-cccc.metadata.json"
        Seq(elsewhere, sibling, underRootNotMetadata).foreach { foreign =>
            Seq(Some(OURS), None).foreach { path =>
                D.decide(Some(foreign), path, ROOT) match {
                    case D.RefuseForeign(loc) => assert(n(loc) == n(foreign), loc)
                    case other => fail(s"catalog at $foreign (path table $path): expected RefuseForeign, got $other")
                }
            }
        }
        // Root given with a trailing slash still treats the sibling as foreign.
        D.decide(Some(sibling), Some(OURS), ROOT + "/") match {
            case D.RefuseForeign(_) =>
            case other => fail(s"trailing-slash root: expected RefuseForeign, got $other")
        }
    }

    // --- Unity Catalog 5 review follow-ups -----------------------------------------

    test("catalog ahead with path file no longer in the metadata log but state says rest → AdoptCatalog") {
        val restCommit = ROOT + "/metadata/00140-aa11.metadata.json"
        // The adopted path file (v1) fell out of the truncated metadata log.
        val log = Set(ROOT + "/metadata/00139-bb22.metadata.json")
        assert(D.decide(Some(n(restCommit)), Some(OLDER), ROOT, log, restCommitted = true) == D.AdoptCatalog)
        // Without the state signal and without the log entry it is still refused.
        assert(D.decide(Some(n(restCommit)), Some(OLDER), ROOT, log).isInstanceOf[D.RefuseBehind])
        // The log is still a secondary signal on its own.
        assert(D.decide(Some(n(restCommit)), Some(OLDER), ROOT, Set(OLDER)) == D.AdoptCatalog)
        // restCommitted never adopts a catalog entry outside our root.
        val sibling = "s3://datris-lake/orders_daily2/metadata/00001-bbbb.metadata.json"
        assert(D.decide(Some(sibling), Some(OURS), ROOT, Set.empty, restCommitted = true).isInstanceOf[D.RefuseForeign])
    }

    test("IcebergRestSession.restCommitted: state says rest and its last commit is under this table root") {
        import ai.datris.model.UnityCatalogSyncState
        def st(mode: String, loc: String) =
            UnityCatalogSyncState(
                pipeline = "p",
                lastSyncAt = null,
                lastRunId = null,
                commentsHash = null,
                tagsHash = null,
                propertiesHash = null,
                lastError = null,
                catalogMode = mode,
                restMetadataLocation = loc
            )
        val restLoc = n(ROOT) + "/metadata/00003-9b2d.metadata.json"
        assert(IcebergRestSession.restCommitted(st("rest", restLoc), ROOT))
        assert(IcebergRestSession.restCommitted(st("rest", restLoc), ROOT + "/"))
        assert(!IcebergRestSession.restCommitted(st("refused", restLoc), ROOT))
        assert(!IcebergRestSession.restCommitted(st("rest", null), ROOT))
        assert(!IcebergRestSession.restCommitted(null, ROOT))
        // A new prefix is a new table: the guard does not follow the pipeline.
        assert(!IcebergRestSession.restCommitted(st("rest", restLoc), "s3a://datris-lake/orders_daily_v2"))
    }

    test("IcebergRestSession.guardFailure: parquet + deleteBeforeWrite on a rest-committed table fails the run") {
        import ai.datris.model.UnityCatalogSyncState
        val committed = UnityCatalogSyncState(
            pipeline = "p",
            lastSyncAt = null,
            lastRunId = null,
            commentsHash = null,
            tagsHash = null,
            propertiesHash = null,
            lastError = null,
            catalogMode = "rest",
            restMetadataLocation = n(ROOT) + "/metadata/00003-9b2d.metadata.json"
        )
        val Q = "unity.default.orders_daily"
        val G = IcebergRestSession.guardFailure _
        // Format flipped to parquet, unityCatalog removed, deleteBeforeWrite on.
        val parquetDelete = G(committed, ROOT, false, true, false, Q)
        assert(parquetDelete.exists(m => m.contains("deleteBeforeWrite cannot be used") && m.contains(Q)), s"$parquetDelete")
        // Parquet without deleteBeforeWrite is the switch-back refusal.
        val parquet = G(committed, ROOT, false, false, false, Q)
        assert(parquet.exists(m => m.contains("not supported") && m.contains("have an admin drop " + Q)), s"$parquet")
        // Iceberg with rest off: switch-back; rest on: no objection; rest on + delete: refused.
        assert(G(committed, ROOT, true, false, false, Q).exists(_.contains("switching from catalogMode rest")))
        assert(G(committed, ROOT, true, false, true, Q).isEmpty)
        assert(G(committed, ROOT, true, true, true, Q).exists(_.contains("deleteBeforeWrite cannot be used")))
        // Never committed, or committed at another prefix: no objection.
        assert(G(null, ROOT, false, true, false, Q).isEmpty)
        assert(G(committed, "s3a://datris-lake/orders_daily_v2", false, true, false, Q).isEmpty)
    }

    test("IcebergRestSession.deleteRefused: only a catalog table under our root blocks deleteBeforeWrite") {
        val DR = IcebergRestSession.deleteRefused _
        assert(DR(true, Some(n(OURS)), ROOT))
        assert(DR(true, Some(OLDER), ROOT + "/"))
        assert(!DR(true, Some("s3://other-bucket/orders_daily/metadata/00001-aaaa.metadata.json"), ROOT), "foreign: our prefix may be deleted")
        assert(!DR(true, Some("s3://datris-lake/orders_daily2/metadata/00001-bbbb.metadata.json"), ROOT), "sibling prefix is foreign")
        assert(!DR(true, None, ROOT))
        assert(!DR(false, Some(OURS), ROOT))
    }

    test("IcebergTableResolver.resolve: catalog-committed tables read at the catalog pointer, else the state doc; path tables unchanged") {
        import ai.datris.model.UnityCatalogSyncState
        val recorded = n(ROOT) + "/metadata/00003-9b2d.metadata.json"
        val newer = n(ROOT) + "/metadata/00004-7c1e.metadata.json"
        val st = UnityCatalogSyncState(
            pipeline = "p",
            lastSyncAt = null,
            lastRunId = null,
            commentsHash = null,
            tagsHash = null,
            propertiesHash = null,
            lastError = null,
            catalogMode = "rest",
            restMetadataLocation = recorded
        )
        val R = IcebergTableResolver
        // Not catalog-committed: path read (the catalog is never asked).
        assert(R.resolve(null, ROOT, () => fail("must not ask the catalog")).isEmpty)
        assert(R.resolve(st.copy(catalogMode = "refused"), ROOT, () => fail("must not ask the catalog")).isEmpty)
        // Catalog answers: its pointer wins, as s3a.
        assert(R.resolve(st, ROOT, () => Some(newer)).contains(newer.replace("s3://", "s3a://")))
        // Catalog unreachable, empty, or pointing elsewhere: the recorded commit.
        assert(R.resolve(st, ROOT, () => throw new RuntimeException("down")).contains(recorded.replace("s3://", "s3a://")))
        assert(R.resolve(st, ROOT, () => None).contains(recorded.replace("s3://", "s3a://")))
        assert(R.resolve(st, ROOT, () => Some("s3://elsewhere/t/metadata/00001-x.metadata.json")).contains(recorded.replace("s3://", "s3a://")))
    }
}
