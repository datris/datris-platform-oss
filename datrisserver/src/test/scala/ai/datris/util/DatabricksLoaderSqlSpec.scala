package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{Database, SchemaField}
import org.scalatest.funsuite.AnyFunSuite

/** Story: Column names from ingested data: checked once at ingest, quoted at
  * every destination, never able to change what a load does
  * (plans/stories/ingest-column-name-escaping.md), Step 6.
  *
  * The DatabricksLoader statement text moves, unchanged, into pure builders
  * on its existing companion:
  * {{{
  * object DatabricksLoader {
  *     // CREATE TABLE IF NOT EXISTS <cat.schema.table> (<col> <type>[ NOT NULL], ...[, CONSTRAINT pk_<table> PRIMARY KEY (<keys>)])
  *     def createTableSql(db: Database, fields: Seq[SchemaField]): String
  *     // ALTER TABLE <cat.schema.table> ADD COLUMN <col> <type>
  *     def addColumnSql(db: Database, field: SchemaField): String
  *     // CAST(`c` AS <type>) AS <c>, ... ; PARSE_JSON(`_json`) AS _json
  *     def selectList(fields: Seq[SchemaField]): String
  *     // COPY INTO <t> FROM (SELECT <selectList> FROM '<path>') FILEFORMAT = CSV FORMAT_OPTIONS (...) COPY_OPTIONS ('mergeSchema' = 'false')
  *     def copySql(db: Database, stagedPath: String, fields: Seq[SchemaField], delimiter: String): String
  *     // MERGE INTO <t> t USING (SELECT <selectList> FROM read_files(...)) s ON ... WHEN MATCHED ... WHEN NOT MATCHED THEN INSERT ...
  *     def mergeSql(db: Database, stagedPath: String, fields: Seq[SchemaField], keyFields: Seq[String], delimiter: String): String
  *     // INSERT OVERWRITE <t> (<cols>) SELECT <selectList> FROM read_files(...)
  *     def insertOverwriteSql(db: Database, stagedPath: String, fields: Seq[SchemaField], delimiter: String): String
  * }
  * }}}
  * Every name goes through `DatabricksConnectionUtil.ident` / `quote`. */
class DatabricksLoaderSqlSpec extends AnyFunSuite {

    private val db = Database(dbName = "main", schema = "sales", table = "orders", keyFields = java.util.Arrays.asList("id"), useDatabricks = true)
    private val noKeys = db.copy(keyFields = null)
    private val path = "/Volumes/main/sales/datris_staging/orders_1.csv"

    private val readFiles =
        "read_files('" + path + "', format => 'csv', header => 'true', delimiter => ',', quote => '\"', escape => '\"', " +
            "nullValue => '', multiLine => 'true', inferSchema => 'false', schemaEvolutionMode => 'none')"
    private val formatOptions =
        "FORMAT_OPTIONS ('header' = 'true', 'delimiter' = ',', 'quote' = '\"', 'escape' = '\"', 'nullValue' = '', 'multiLine' = 'true', 'inferSchema' = 'false')"

    private val fields = Seq(SchemaField("id", "string"), SchemaField("amount", "int"), SchemaField("price", "double"))
    private val select = "CAST(`id` AS STRING) AS id, CAST(`amount` AS INT) AS amount, CAST(`price` AS DOUBLE) AS price"

    test("for plain names every statement equals the text the v1.60.0 code built") {
        assert(
            DatabricksLoader.createTableSql(db, fields :+ SchemaField("_json", "string")) ==
                "CREATE TABLE IF NOT EXISTS main.sales.orders (id STRING NOT NULL, amount INT, price DOUBLE, _json VARIANT, " +
                "CONSTRAINT pk_orders PRIMARY KEY (id))"
        )
        assert(DatabricksLoader.createTableSql(noKeys, fields.take(2)) == "CREATE TABLE IF NOT EXISTS main.sales.orders (id STRING, amount INT)")
        assert(DatabricksLoader.addColumnSql(db, SchemaField("note", "string")) == "ALTER TABLE main.sales.orders ADD COLUMN note STRING")
        assert(DatabricksLoader.selectList(fields) == select)
        assert(DatabricksLoader.selectList(Seq(SchemaField("_json", "string"))) == "PARSE_JSON(`_json`) AS _json")
        assert(
            DatabricksLoader.copySql(db, path, fields, ",") ==
                "COPY INTO main.sales.orders FROM (SELECT " + select + " FROM '" + path + "') FILEFORMAT = CSV " + formatOptions +
                " COPY_OPTIONS ('mergeSchema' = 'false')"
        )
        assert(
            DatabricksLoader.mergeSql(db, path, fields, Seq("id"), ",") ==
                "MERGE INTO main.sales.orders t USING (SELECT " + select + " FROM " + readFiles + ") s ON t.id = s.id " +
                "WHEN MATCHED THEN UPDATE SET t.amount = s.amount, t.price = s.price " +
                "WHEN NOT MATCHED THEN INSERT (id, amount, price) VALUES (s.id, s.amount, s.price)"
        )
        assert(
            DatabricksLoader.insertOverwriteSql(db, path, fields, ",") ==
                "INSERT OVERWRITE main.sales.orders (id, amount, price) SELECT " + select + " FROM " + readFiles
        )
        // a non-simple plain name is quoted exactly as before
        assert(DatabricksLoader.addColumnSql(db, SchemaField("unit price", "double")) == "ALTER TABLE main.sales.orders ADD COLUMN `unit price` DOUBLE")
    }

    test(
        "a column, key-field or merge-key name holding that engine's delimiter and a semicolon yields one statement whose only unescaped delimiters bound identifiers"
    ) {
        val evil = "x`; DROP TABLE canary; --"
        val evil2 = "a``b;`c"
        for (n <- Seq(evil, evil2)) {
            val allowed = Set(n, "id", "_json")
            val keyed = db.copy(keyFields = java.util.Arrays.asList("id", n))
            val fs = Seq(SchemaField("id", "string"), SchemaField(n, "string"))
            def one(sql: String, clue: String): SqlIdentifierScan.Scan = SqlIdentifierScan.assertOneStatement(sql, '`', '`', allowed, Seq(n), clue)
            one(DatabricksLoader.createTableSql(keyed, fs), "createTable")
            one(DatabricksLoader.addColumnSql(db, SchemaField(n, "string")), "addColumn")
            one(DatabricksLoader.selectList(fs), "selectList")
            one(DatabricksLoader.copySql(db, path, fs, ","), "copy")
            one(DatabricksLoader.insertOverwriteSql(db, path, fs, ","), "insertOverwrite")
            val keyMerge = one(DatabricksLoader.mergeSql(db, path, fs, Seq(n), ","), "merge on key")
            assert(keyMerge.identifiers.count(_ == n) == 6, "select (2) + ON (2) + INSERT + VALUES: " + keyMerge)
            val colMerge = one(DatabricksLoader.mergeSql(db, path, fs, Seq("id"), ","), "merge column")
            assert(colMerge.identifiers.count(_ == n) == 6, "select (2) + SET (2) + INSERT + VALUES: " + colMerge)
        }
    }
}
