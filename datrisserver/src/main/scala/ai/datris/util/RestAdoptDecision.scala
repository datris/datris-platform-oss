package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

/** First-run decision for `catalogMode: rest`: what to do given what the
  * catalog holds under the table's name and what the path-based table at our
  * S3 root holds. Pure; reuses IcebergCatalogRegistrar.classify, so `s3a://`,
  * `s3n://` and `s3://` compare equal and a sibling prefix is foreign.
  *
  *  - neither side has a table ⇒ `CreateNew` (the writer creates it through the catalog)
  *  - only the path table ⇒ `AdoptPath` (register its current metadata file once)
  *  - the catalog points at our current file ⇒ `AdoptCatalog`
  *  - the catalog is ahead of the path table (its metadata log holds the
  *    path's current file, or its `datris.adopted-from` names it), or holds a catalog-created table under our root
  *    ⇒ `AdoptCatalog`
  *  - the catalog points at an older file of our table ⇒ `RefuseBehind`
  *    (moving the pointer needs a DROP, which Datris never does)
  *  - the catalog points anywhere else ⇒ `RefuseForeign`
  * `decideManaged` is the `catalogMode: managed` variant. */
object RestAdoptDecision {
    sealed trait Decision
    case object CreateNew extends Decision
    final case class AdoptPath(pathCurrent: String) extends Decision
    case object AdoptCatalog extends Decision
    final case class RefuseBehind(catalogLoc: String, pathCurrent: String) extends Decision
    final case class RefuseForeign(catalogLoc: String) extends Decision

    /** @param catalogHistory the catalog table's earlier metadata files (its
      *   metadata log). When it contains `pathCurrent` the catalog is AHEAD of
      *   the path table (REST commits never update `version-hint.text`), which
      *   is our own table kept current by earlier `rest` runs, not a stale
      *   pointer. The log is truncated (`write.metadata.previous-versions-max`),
      *   so this is only a secondary signal.
      * @param restCommitted the state doc says an earlier run committed this
      *   table through the catalog (its last catalog commit is under our
      *   metadata/): a catalog entry under our metadata/ is then ours and
      *   ahead of the path table, whatever the log still holds.
      * @param adoptedFrom the catalog table's `datris.adopted-from` property,
      *   stamped when a `rest` run adopted the path table. REST commits never
      *   move the path table's `version-hint.text`, so its current file stays
      *   the adopted file for the table's lifetime: a match proves the catalog
      *   entry is ours and ahead. A table property, so it survives both log
      *   truncation and a lost state doc. Never overrides `RefuseForeign`. */
    /** `loc` (a metadata file) lives under `tableRoot`'s `metadata/` directory,
      * s3/s3a/s3n equal. */
    def metadataUnderRoot(loc: String, tableRoot: String): Boolean = {
        val root = Option(tableRoot).map(_.trim.stripSuffix("/")).orNull
        root != null && loc != null &&
        IcebergCatalogRegistrar.normalize(loc).startsWith(IcebergCatalogRegistrar.normalize(root) + "/metadata/")
    }

    /** An `AdoptCatalog` table under our root that has never been stamped
      * (registered in `register` mode, then switched to `rest`) gets
      * `datris.adopted-from` = the path table's current file, which REST
      * commits never move. Without it a lost state doc after the metadata
      * log has dropped that file would read as an unstamped adoption and be
      * refused. Never re-stamps: an existing property is left as is. */
    def needsStamp(props: Map[String, String], pathCurrent: Option[String]): Boolean =
        pathCurrent.exists(p => p != null && p.trim.nonEmpty) &&
            !Option(props).exists(_.contains(IcebergWriter.AdoptedFromProperty))

    def decide(
        catalogHas: Option[String],
        pathCurrent: Option[String],
        tableRoot: String,
        catalogHistory: Set[String] = Set.empty,
        restCommitted: Boolean = false,
        adoptedFrom: Option[String] = None
    ): Decision = {
        val root = Option(tableRoot).map(_.trim.stripSuffix("/")).orNull
        def underRoot(loc: String): Boolean = metadataUnderRoot(loc, root)
        (catalogHas.filter(s => s != null && s.trim.nonEmpty), pathCurrent.filter(s => s != null && s.trim.nonEmpty)) match {
            case (None, None) => CreateNew
            case (None, Some(p)) => AdoptPath(p)
            case (Some(c), Some(_)) if restCommitted && underRoot(c) => AdoptCatalog
            case (Some(c), Some(p)) =>
                IcebergCatalogRegistrar.classify(c, p, root) match {
                    case IcebergCatalogRegistrar.Current => AdoptCatalog
                    case IcebergCatalogRegistrar.Stale =>
                        val np = IcebergCatalogRegistrar.normalize(p)
                        val ahead = catalogHistory.exists(h => IcebergCatalogRegistrar.normalize(h) == np) ||
                            adoptedFrom.exists(a => a != null && a.trim.nonEmpty && IcebergCatalogRegistrar.normalize(a.trim) == np)
                        if (ahead) AdoptCatalog else RefuseBehind(c, p)
                    case IcebergCatalogRegistrar.Foreign => RefuseForeign(c)
                }
            // No path table (a table created through the catalog never writes
            // a version hint): ours when it lives under our metadata/.
            case (Some(c), None) => if (underRoot(c)) AdoptCatalog else RefuseForeign(c)
        }
    }

    /** First-run decision for `catalogMode: managed`. The catalog chooses the
      * location, so there is no path table to adopt or be behind:
      *  - the catalog has no table ⇒ `CreateNew`;
      *  - it has one in the pipeline's `bucket` that this pipeline recorded
      *    (an earlier managed commit of that same table, i.e. the same
      *    `<table>/metadata/` directory, or the empty table a refused `rest` run
      *    created for this name, `restCreatedTable`) ⇒ `AdoptCatalog`;
      *  - anything else ⇒ `RefuseForeign` (never adopt someone else's managed
      *    table, even in our bucket; never one outside our bucket, which the
      *    pipeline's S3 secret does not cover). */
    def decideManaged(catalogHas: Option[String], previous: ai.datris.model.UnityCatalogSyncState, qualified: String, bucket: String): Decision =
        catalogHas.filter(s => s != null && s.trim.nonEmpty) match {
            case None => CreateNew
            case Some(c) =>
                // A managed commit only vouches for the table it recorded (same
                // <table>/metadata/ dir): after a schema edit the identifier can
                // name someone else's table in the same bucket.
                val ours = IcebergRestSession.managedRecordedTable(c, previous) ||
                    (previous != null && previous.restCreatedTable != null && previous.restCreatedTable == qualified)
                if (ours && IcebergWriter.inBucket(c, bucket)) AdoptCatalog else RefuseForeign(c)
        }
}
