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
  *    path's current file), or holds a catalog-created table under our root
  *    ⇒ `AdoptCatalog`
  *  - the catalog points at an older file of our table ⇒ `RefuseBehind`
  *    (moving the pointer needs a DROP, which Datris never does)
  *  - the catalog points anywhere else ⇒ `RefuseForeign` */
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
      *   pointer. */
    def decide(catalogHas: Option[String], pathCurrent: Option[String], tableRoot: String, catalogHistory: Set[String] = Set.empty): Decision = {
        val root = Option(tableRoot).map(_.trim.stripSuffix("/")).orNull
        def underRoot(loc: String): Boolean =
            root != null && IcebergCatalogRegistrar.normalize(loc).startsWith(IcebergCatalogRegistrar.normalize(root) + "/metadata/")
        (catalogHas.filter(s => s != null && s.trim.nonEmpty), pathCurrent.filter(s => s != null && s.trim.nonEmpty)) match {
            case (None, None) => CreateNew
            case (None, Some(p)) => AdoptPath(p)
            case (Some(c), Some(p)) =>
                IcebergCatalogRegistrar.classify(c, p, root) match {
                    case IcebergCatalogRegistrar.Current => AdoptCatalog
                    case IcebergCatalogRegistrar.Stale =>
                        val ahead = catalogHistory.exists(h => IcebergCatalogRegistrar.normalize(h) == IcebergCatalogRegistrar.normalize(p))
                        if (ahead) AdoptCatalog else RefuseBehind(c, p)
                    case IcebergCatalogRegistrar.Foreign => RefuseForeign(c)
                }
            // No path table (a table created through the catalog never writes
            // a version hint): ours when it lives under our metadata/.
            case (Some(c), None) => if (underRoot(c)) AdoptCatalog else RefuseForeign(c)
        }
    }
}
