package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

/** Decides which SQL warehouse a Databricks destination connects through.
 *  `destination.database.warehouse` is optional when the pipeline's
 *  credentials secret carries a warehouse-like field, so the effective value
 *  is: the pipeline's own warehouse if non-blank → else the secret's
 *  `warehouse` field (aliases below) → else a Left naming both fixes. Pure:
 *  the caller supplies the already-resolved secret fields. */
object DatabricksWarehouse {

    /** Secret field names read as the SQL warehouse (ID or HTTP path). Shared
     *  with [[UnityCatalogDiscovery.resolveWarehouse]]. */
    val SecretFieldNames: Seq[String] = Seq("warehouse", "httpPath", "http_path", "DATABRICKS_WAREHOUSE")

    private def nonBlank(s: String): Option[String] = Option(s).map(_.trim).filter(_.nonEmpty)

    /** The warehouse field on a secret (any alias/case), if present and non-blank. */
    def fromSecret(secretFields: java.util.Map[String, String]): Option[String] =
        Option(secretFields).flatMap(f => CredentialResolver.secretField(f, SecretFieldNames.head, SecretFieldNames.tail: _*)).flatMap(nonBlank)

    /** Effective warehouse, trimmed (not yet normalised to an httpPath — pass
     *  the Right through [[DatabricksConnectionUtil.warehouseHttpPath]]).
     *  Left carries the fix-it message for `pipelineName`/`secretName`. */
    def effective(
        dbWarehouse: String,
        secretFields: Map[String, String],
        pipelineName: String = null,
        secretName: String = null
    ): Either[String, String] = {
        val javaFields = new java.util.HashMap[String, String]()
        if (secretFields != null) secretFields.foreach { case (k, v) => javaFields.put(k, v) }
        nonBlank(dbWarehouse).orElse(fromSecret(javaFields)) match {
            case Some(w) => Right(w)
            case None =>
                Left(
                    "No SQL warehouse configured for " + nonBlank(pipelineName).map("pipeline " + _).getOrElse("this Databricks destination") +
                        ": set destination.database.warehouse, or add a 'warehouse' field (the warehouse ID or HTTP path) to the Databricks secret '" +
                        Option(secretName).getOrElse("") + "'"
                )
        }
    }
}
