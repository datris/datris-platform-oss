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
}
