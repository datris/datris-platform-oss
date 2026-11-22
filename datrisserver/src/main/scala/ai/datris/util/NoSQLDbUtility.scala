package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

trait NoSQLDbUtility {
    def getItemsKeysByKeyName(tableName: String, keyName: String): List[String]

    def getItemAttribute[T](tableName: String, keyName: String, key: String, attributeName: String): T

    def setItemNameValue(tableName: String, keyName: String, key: String, valueName: String, value: String): Unit

    def getItemJSON(tableName: String, keyName: String, key: String, valueName: String): Option[String]

    def putItemJSON(
        tableName: String,
        keyName: String,
        key: String,
        valueName: String,
        value: String,
        sortKeyName: String = null,
        sortKeyValue: Number = null,
        extraFields: java.util.Map[String, AnyRef] = null
    ): Unit

    def updateItemJSON(
        tableName: String,
        keyName: String,
        key: String,
        valueName: String,
        value: String,
        sortKeyName: String = null,
        sortKeyValue: Number = null
    ): Unit

    def deleteItemJSON(tableName: String, keyName: String, key: String, sortKeyName: String = null, sortKeyValue: Number = null): Unit

    def queryJSONItemsByKey(tableName: String, keyName: String, key: String): List[String]

    def getAllItemsAsJSON(tableName: String): List[String]

    def getPageOfItemsAsJSON(tableName: String, pageNbr: Int, maxPageSize: Int, sortField: String = null, sortDescending: Boolean = true): List[String]

    /** [[getPageOfItemsAsJSON]] over only the items whose `field` (a dotted
      * path into the stored document, such as `json.pipeline`) holds one of
      * `values` exactly: the same sort, the same skip (`pageNbr` *
      * `maxPageSize`) and limit, applied after the filter, so page n is
      * items [n * maxPageSize, (n + 1) * maxPageSize) of the matching items.
      * An item whose field is missing, null or not a string never matches.
      * This default reads every item and filters, sorts (on the top-level
      * numeric `sortField`) and pages in memory; a store that can filter in
      * its query overrides it ([[MongoDBUtil]]). */
    def getPageOfItemsWhereInAsJSON(
        tableName: String,
        field: String,
        values: Set[String],
        pageNbr: Int,
        maxPageSize: Int,
        sortField: String = null,
        sortDescending: Boolean = true
    ): List[String] = {
        if (values == null || values.isEmpty || maxPageSize <= 0) return Nil
        val path = field.split('.').toSeq
        def at(o: com.google.gson.JsonObject, keys: Seq[String]): Option[com.google.gson.JsonElement] =
            keys match {
                case Seq() => Some(o)
                case Seq(k) => if (o.has(k)) Some(o.get(k)) else None
                case k +: rest => if (o.has(k) && o.get(k).isJsonObject) at(o.getAsJsonObject(k), rest) else None
            }
        def str(e: com.google.gson.JsonElement): Option[String] =
            if (e != null && e.isJsonPrimitive && e.getAsJsonPrimitive.isString) Some(e.getAsString) else None
        def num(e: com.google.gson.JsonElement): Double =
            if (e != null && e.isJsonPrimitive && e.getAsJsonPrimitive.isNumber) e.getAsDouble else Double.MinValue
        val parsed = Option(getAllItemsAsJSON(tableName)).getOrElse(Nil).flatMap { json =>
            val o = scala.util.Try(com.google.gson.JsonParser.parseString(json)).toOption.filter(_.isJsonObject).map(_.getAsJsonObject)
            o.filter(d => at(d, path).flatMap(str).exists(values.contains)).map(d => (json, d))
        }
        val sorted =
            if (sortField == null) parsed
            else {
                val asc = parsed.sortBy { case (_, d) => num(at(d, Seq(sortField)).orNull) }
                if (sortDescending) asc.reverse else asc
            }
        sorted.drop(math.max(0, pageNbr) * maxPageSize).take(maxPageSize).map(_._1)
    }

    /** Indexed range query: returns rows where `sortField >= sinceEpochMs`, sorted desc,
     *  capped to `maxItems`. Used for time-window dashboards (ops activity, etc.)
     *  where fan-out by key would be slow at scale. The caller must have written
     *  rows with `sortField` populated via `putItemJSON(..., sortKeyName, sortKeyValue)`. */
    def getItemsSinceAsJSON(tableName: String, sortField: String, sinceEpochMs: Long, maxItems: Int): List[String]

    def insertJSON(tableName: String, json: String): Unit

    def deleteAll(tableName: String): Long

    def upsertJSON(tableName: String, keyFields: java.util.List[String], json: String): Unit
}
