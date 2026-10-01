package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.UnityCatalogSyncState
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
                restMetadataLocation = loc,
                lastRestCommitAt = "2026-09-29T10:00:03Z"
            )
        val restLoc = n(ROOT) + "/metadata/00003-9b2d.metadata.json"
        assert(IcebergRestSession.restCommitted(st("rest", restLoc), ROOT))
        assert(IcebergRestSession.restCommitted(st("rest", restLoc), ROOT + "/"))
        assert(!IcebergRestSession.restCommitted(st("refused", restLoc), ROOT))
        assert(!IcebergRestSession.restCommitted(st("rest", null), ROOT))
        assert(!IcebergRestSession.restCommitted(null, ROOT))
        // A doc without a recorded commit time is not a catalog commit.
        assert(!IcebergRestSession.restCommitted(st("rest", restLoc).copy(lastRestCommitAt = null), ROOT))
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
            restMetadataLocation = n(ROOT) + "/metadata/00003-9b2d.metadata.json",
            lastRestCommitAt = "2026-09-29T10:00:03Z"
        )
        val Q = "unity.default.orders_daily"
        // A local def, not `guardFailure _`: Unity Catalog 7 adds a defaulted
        // `managedActive` parameter, which eta-expansion would not default.
        def G(p: UnityCatalogSyncState, r: String, i: Boolean, d: Boolean, a: Boolean, q: String): Option[String] =
            IcebergRestSession.guardFailure(p, r, i, d, a, q)
        // Format flipped to parquet, unityCatalog removed, deleteBeforeWrite on.
        val parquetDelete = G(committed, ROOT, false, true, false, Q)
        assert(parquetDelete.exists(m => m.contains("deleteBeforeWrite cannot be used") && m.contains(Q)), s"$parquetDelete")
        // Parquet without deleteBeforeWrite is the switch-back refusal.
        val parquet = G(committed, ROOT, false, false, false, Q)
        assert(parquet.exists(m => m.contains("not supported") && m.contains("have an admin drop " + Q)), s"$parquet")
        // Iceberg with rest off: switch-back; rest on: no objection; rest on + delete: refused.
        assert(G(committed, ROOT, true, false, false, Q).exists(
            _.contains("committed through Unity Catalog (catalogMode rest) but this pipeline is not in rest mode")
        ))
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
            restMetadataLocation = recorded,
            lastRestCommitAt = "2026-09-29T10:00:03Z"
        )
        val R = IcebergTableResolver
        // Unity Catalog 7: resolve returns Resolved(metadataLocation, managed);
        // a rest doc is never `managed`.
        def rest(loc: String) = IcebergTableResolver.Resolved(loc.replace("s3://", "s3a://"), managed = false)
        // Not catalog-committed: path read (the catalog is never asked).
        assert(R.resolve(null, ROOT, () => fail("must not ask the catalog")).isEmpty)
        assert(R.resolve(st.copy(catalogMode = "refused"), ROOT, () => fail("must not ask the catalog")).isEmpty)
        // Catalog answers: its pointer wins, as s3a.
        assert(R.resolve(st, ROOT, () => Some(newer)).contains(rest(newer)))
        // Catalog unreachable, empty, or pointing elsewhere: the recorded commit.
        assert(R.resolve(st, ROOT, () => throw new RuntimeException("down")).contains(rest(recorded)))
        assert(R.resolve(st, ROOT, () => None).contains(rest(recorded)))
        assert(R.resolve(st, ROOT, () => Some("s3://elsewhere/t/metadata/00001-x.metadata.json")).contains(rest(recorded)))
    }

    test("IcebergRestSession.registerUnsupported: Databricks ENDPOINT_NOT_FOUND and UnsupportedOperationException, nothing else") {
        val U = IcebergRestSession.registerUnsupported _
        assert(U(new UnsupportedOperationException("Server does not support endpoint: POST /v1/{prefix}/namespaces/{namespace}/register")))
        assert(U(new RuntimeException(
            """{"error_code":"ENDPOINT_NOT_FOUND","message":"No API found for 'POST /unity-catalog/iceberg-rest/v1/catalogs/main/namespaces/default/register'"}"""
        )))
        assert(U(new RuntimeException("wrapped", new RuntimeException("No API found for 'POST .../register'"))))
        assert(!U(new RuntimeException("Forbidden: principal lacks EXTERNAL USE SCHEMA")))
        assert(!U(new RuntimeException("Not found")))
        assert(!U(null))
        assert(IcebergRestSession.RegisterUnsupportedMessage.contains("start from a new prefix"))
        assert(IcebergRestSession.RegisterUnsupportedMessage.contains("deleteBeforeWrite once"))
    }

    // ---- Story: Unity Catalog 7: Databricks-managed Iceberg mode -----------
    // (plans/stories/unity-catalog-7-managed-iceberg.md), Acceptance bullets 2
    // and 4. Seam these tests pin:
    //
    //   RestAdoptDecision.decideManaged(catalogHas: Option[String], previous: UnityCatalogSyncState,
    //                                   qualified: String, bucket: String): Decision
    //     only CreateNew | AdoptCatalog | RefuseForeign(c), never AdoptPath / RefuseBehind
    //   IcebergRestSession.managedCommitted(previous: UnityCatalogSyncState): Boolean   (root-independent)
    //   IcebergRestSession.managedProviderRefusal(provider: String, secretFields: Map[String, String],
    //                                             secretName: String): Option[String]
    //   IcebergRestSession.guardFailure(previous, tableRoot, iceberg, deleteBeforeWrite,
    //                                   restActive /* commits through the catalog: rest OR managed */,
    //                                   qualified, managedActive: Boolean = false): Option[String]
    //   IcebergRestSession.switchFromManagedMessage(qualified: String): String

    private val MQ = "datris.uc.orders_daily"
    private val MANAGED_LOC = "s3://datris/uc/__unitystorage/schemas/5e1f/tables/9a2b/metadata/00001-c3d4.metadata.json"

    private def ucState(
        mode: String,
        loc: String = MANAGED_LOC,
        at: String = "2026-10-01T09:00:00Z",
        created: String = null
    ): UnityCatalogSyncState =
        UnityCatalogSyncState(
            pipeline = "orders_daily",
            lastSyncAt = null,
            lastRunId = null,
            commentsHash = null,
            tagsHash = null,
            propertiesHash = null,
            lastError = null,
            catalogMode = mode,
            restMetadataLocation = loc,
            lastRestCommitAt = at,
            restCreatedTable = created
        )

    test("IcebergRestSession.managedCommitted: a managed doc with a recorded commit, whatever the table root") {
        val M = IcebergRestSession.managedCommitted _
        assert(M(ucState("managed")))
        // Root-independent: the managed table lives where the catalog put it,
        // not under prefixKey; restCommitted (root-checked) does not see it.
        assert(!IcebergRestSession.restCommitted(ucState("managed"), ROOT))
        assert(!M(ucState("rest", loc = n(ROOT) + "/metadata/00003-9b2d.metadata.json")), "a rest commit is not managed")
        assert(!M(ucState("refused")))
        assert(!M(ucState("managed", loc = null)))
        assert(!M(ucState("managed", at = null)), "no commit time: not a catalog commit")
        assert(!M(null))
    }

    test("decideManaged: adopts only a table this pipeline recorded; foreign otherwise") {
        val DM = RestAdoptDecision.decideManaged _
        val inBucket = Some(MANAGED_LOC)
        val inBucketS3a = Some(MANAGED_LOC.replace("s3://", "s3a://"))
        val otherBucket = Some("s3://datris-archive/uc/__unitystorage/schemas/5e1f/tables/9a2b/metadata/00001-c3d4.metadata.json")

        // Nothing in the catalog: create, whatever the doc says.
        assert(DM(None, null, MQ, "datris") == D.CreateNew)
        assert(DM(None, ucState("managed"), MQ, "datris") == D.CreateNew)
        assert(DM(None, ucState("refused", loc = null, at = null, created = MQ), MQ, "datris") == D.CreateNew)

        // Ours: an earlier managed commit (s3/s3a spellings compare equal).
        assert(DM(inBucket, ucState("managed"), MQ, "datris") == D.AdoptCatalog)
        assert(DM(inBucketS3a, ucState("managed"), MQ, "datris") == D.AdoptCatalog)
        // Ours: the empty table a refused Databricks rest run created for this name.
        assert(DM(inBucket, ucState("refused", loc = null, at = null, created = MQ), MQ, "datris") == D.AdoptCatalog)

        // Not recorded by this pipeline: never adopt someone else's managed table, even in our bucket.
        def foreign(d: RestAdoptDecision.Decision, c: String): Unit = d match {
            case D.RefuseForeign(loc) => assert(n(loc) == n(c), s"$loc vs $c")
            case other => fail(s"expected RefuseForeign($c), got $other")
        }
        foreign(DM(inBucket, null, MQ, "datris"), MANAGED_LOC)
        foreign(DM(inBucket, ucState("refused", loc = null, at = null, created = "datris.uc.other_table"), MQ, "datris"), MANAGED_LOC)
        foreign(DM(inBucket, ucState("managed", at = null), MQ, "datris"), MANAGED_LOC)
        foreign(DM(inBucket, ucState("rest", loc = n(ROOT) + "/metadata/00003-9b2d.metadata.json"), MQ, "datris"), MANAGED_LOC)
        // Another bucket: foreign even when this pipeline recorded it
        // (the pipeline's S3 secret only covers its own bucket).
        foreign(DM(otherBucket, ucState("managed"), MQ, "datris"), otherBucket.get)
        foreign(DM(otherBucket, ucState("refused", loc = null, at = null, created = MQ), MQ, "datris"), otherBucket.get)

        // No path-table outcomes exist in managed mode.
        val all = Seq(
            DM(None, null, MQ, "datris"),
            DM(inBucket, ucState("managed"), MQ, "datris"),
            DM(inBucket, null, MQ, "datris"),
            DM(otherBucket, ucState("managed"), MQ, "datris")
        )
        assert(!all.exists(d => d.isInstanceOf[D.AdoptPath] || d.isInstanceOf[D.RefuseBehind]), s"$all")
    }

    test("managedProviderRefusal: minio + Databricks-shaped secret refused, minio + icebergRestPath allowed, s3 always allowed") {
        def P(provider: String, fields: Map[String, String]): Option[String] =
            IcebergRestSession.managedProviderRefusal(provider = provider, secretFields = fields, secretName = "databricks_uc")
        val databricks = Map("host" -> "https://dbc-1.cloud.databricks.com", "clientId" -> "id", "clientSecret" -> "s")
        val fixture = Map("host" -> "http://iceberg-rest:8181", "icebergRestPath" -> "/")

        val refused = P("minio", databricks)
        assert(refused.isDefined, "minio with a Databricks secret must be refused before any write")
        refused.foreach { m =>
            assert(m.contains("databricks_uc"), s"names the secret: $m")
            assert(m.contains("MinIO"), s"names MinIO: $m")
            assert(m.contains("'s3'"), s"says provider s3 is needed: $m")
            assert(m.contains("icebergRestPath"), s"says what would allow minio: $m")
            assert(m.contains("managed"), m)
        }
        // Absent provider is MinIO (ObjectStoreSpark default).
        assert(P(null, databricks).isDefined)
        assert(P("MinIO", databricks).isDefined, "provider is case-insensitive")

        assert(P("minio", fixture).isEmpty, "a custom icebergRestPath (non-Databricks catalog) allows MinIO")
        assert(P("minio", Map("host" -> "http://iceberg-rest:8181", "icebergrestpath" -> "")).isEmpty, "field name is case-insensitive, any value")
        assert(P(null, fixture).isEmpty)
        assert(P("s3", databricks).isEmpty)
        assert(P("s3", fixture).isEmpty)
        assert(P("S3", Map.empty).isEmpty)
    }

    test("guardFailure: switching a managed-committed table to rest/register or adding deleteBeforeWrite fails the run") {
        val managed = ucState("managed")
        val restDoc = ucState("rest", loc = n(ROOT) + "/metadata/00003-9b2d.metadata.json")
        def G(
            p: UnityCatalogSyncState,
            iceberg: Boolean = true,
            delete: Boolean = false,
            throughCatalog: Boolean,
            managedActive: Boolean,
            root: String = ROOT
        ): Option[String] =
            IcebergRestSession.guardFailure(p, root, iceberg, delete, throughCatalog, MQ, managedActive = managedActive)

        // Managed stays managed: no objection, also from a new prefix (root-independent).
        assert(G(managed, throughCatalog = true, managedActive = true).isEmpty)
        assert(G(managed, throughCatalog = true, managedActive = true, root = "s3a://datris-lake/orders_daily_v2").isEmpty)

        val switchMsg = IcebergRestSession.switchFromManagedMessage(MQ)
        assert(switchMsg.contains(MQ) && switchMsg.contains("managed"), switchMsg)
        // managed -> rest, managed -> register, managed -> unityCatalog removed / parquet.
        assert(G(managed, throughCatalog = true, managedActive = false).contains(switchMsg), "managed -> rest")
        assert(G(managed, throughCatalog = false, managedActive = false).contains(switchMsg), "managed -> register / off")
        assert(G(managed, iceberg = false, throughCatalog = false, managedActive = false).isDefined, "managed -> parquet")
        assert(
            G(managed, throughCatalog = false, managedActive = false, root = "s3a://datris-lake/orders_daily_v2").contains(switchMsg),
            "the managed guard follows the pipeline to a new prefix"
        )

        // deleteBeforeWrite on a managed-committed table fails the run (the files are the catalog's).
        val del = G(managed, delete = true, throughCatalog = true, managedActive = true)
        assert(del.isDefined, "deleteBeforeWrite + managed-committed must fail")
        assert(del.exists(_.contains("deleteBeforeWrite")), s"$del")
        assert(G(managed, delete = true, throughCatalog = false, managedActive = false).isDefined)

        // rest-committed -> managed: the existing switch-back refusal.
        assert(G(restDoc, throughCatalog = true, managedActive = true).contains(IcebergRestSession.switchBackMessage(MQ)), "rest -> managed")
        // rest stays rest: unchanged.
        assert(G(restDoc, throughCatalog = true, managedActive = false).isEmpty)

        // Never committed: managed with or without delete has no guard objection
        // (deleteBeforeWrite + managed is a validation 400, PipelineValidatorUtilSpec).
        assert(G(null, throughCatalog = true, managedActive = true).isEmpty)
        assert(G(ucState("refused", loc = null, at = null, created = MQ), throughCatalog = true, managedActive = true).isEmpty)
    }
}
