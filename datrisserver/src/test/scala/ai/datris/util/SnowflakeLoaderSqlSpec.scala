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
  * The SnowflakeLoader statement text moves, unchanged, into pure builders on
  * a new companion (as SqlServerLoader's already are):
  * {{{
  * object SnowflakeLoader {
  *     // CREATE TABLE IF NOT EXISTS <db.schema.table> (<col> <type>, ...[, PRIMARY KEY (<keys>)])
  *     def createTableSql(db: Database, fields: Seq[SchemaField]): String
  *     // ALTER TABLE <db.schema.table> ADD COLUMN IF NOT EXISTS <col> <type>
  *     def addColumnSql(db: Database, field: SchemaField): String
  *     // COPY INTO <tableRef> (<cols>) FROM <stage> | FROM (SELECT $1, PARSE_JSON($2) FROM <stage>) FILE_FORMAT = (...) PURGE = TRUE
  *     def copySql(tableRef: String, stage: String, fields: Seq[SchemaField], delimiter: String): String
  *     // MERGE INTO <targetRef> t USING <stagingRef> s ON ... WHEN MATCHED ... WHEN NOT MATCHED THEN INSERT ...
  *     def mergeSql(targetRef: String, stagingRef: String, columns: Seq[String], keyFields: Seq[String]): String
  * }
  * }}}
  * Every name goes through `SnowflakeConnectionUtil.ident`. */
class SnowflakeLoaderSqlSpec extends AnyFunSuite {

    private val db = Database(dbName = "datris", schema = "public", table = "orders", keyFields = java.util.Arrays.asList("id"), useSnowflake = true)
    private val noKeys = db.copy(keyFields = null)
    private val fields = Seq(SchemaField("id", "string"), SchemaField("amount", "int"), SchemaField("price", "double"), SchemaField("_json", "string"))

    private val fileFormat =
        "FILE_FORMAT = (TYPE = CSV FIELD_OPTIONALLY_ENCLOSED_BY = '\"' FIELD_DELIMITER = ',' EMPTY_FIELD_AS_NULL = TRUE COMPRESSION = AUTO)"

    test("for plain names every statement equals the text the v1.60.0 code built") {
        assert(
            SnowflakeLoader.createTableSql(db, fields) ==
                "CREATE TABLE IF NOT EXISTS datris.public.orders (id VARCHAR, amount NUMBER(38,0), price FLOAT, _json VARIANT, PRIMARY KEY (id))"
        )
        assert(SnowflakeLoader.createTableSql(noKeys, fields.take(2)) == "CREATE TABLE IF NOT EXISTS datris.public.orders (id VARCHAR, amount NUMBER(38,0))")
        assert(
            SnowflakeLoader.addColumnSql(db, SchemaField("note", "string")) ==
                "ALTER TABLE datris.public.orders ADD COLUMN IF NOT EXISTS note VARCHAR"
        )
        assert(
            SnowflakeLoader.copySql("datris.public.orders", "@%orders", fields.take(2), ",") ==
                "COPY INTO datris.public.orders (id, amount) FROM @%orders " + fileFormat + " PURGE = TRUE"
        )
        assert(
            SnowflakeLoader.copySql("datris.public.orders", "@%orders", Seq(SchemaField("id", "string"), SchemaField("_json", "string")), ",") ==
                "COPY INTO datris.public.orders (id, _json) FROM (SELECT $1, PARSE_JSON($2) FROM @%orders) " + fileFormat + " PURGE = TRUE"
        )
        assert(
            SnowflakeLoader.mergeSql("datris.public.orders", "DATRIS_STG_x", Seq("id", "amount", "price"), Seq("id")) ==
                "MERGE INTO datris.public.orders t USING DATRIS_STG_x s ON t.id = s.id " +
                "WHEN MATCHED THEN UPDATE SET t.amount = s.amount, t.price = s.price " +
                "WHEN NOT MATCHED THEN INSERT (id, amount, price) VALUES (s.id, s.amount, s.price)"
        )
        assert(
            SnowflakeLoader.mergeSql("datris.public.orders", "DATRIS_STG_x", Seq("ID", "Region"), Seq("id", "region")) ==
                "MERGE INTO datris.public.orders t USING DATRIS_STG_x s ON t.id = s.id AND t.region = s.region " +
                "WHEN NOT MATCHED THEN INSERT (ID, Region) VALUES (s.ID, s.Region)"
        )
        // a non-simple plain name is quoted exactly as before
        assert(
            SnowflakeLoader.addColumnSql(db, SchemaField("unit price", "double")) ==
                "ALTER TABLE datris.public.orders ADD COLUMN IF NOT EXISTS \"unit price\" FLOAT"
        )
    }

    test(
        "a column, key-field or merge-key name holding that engine's delimiter and a semicolon yields one statement whose only unescaped delimiters bound identifiers"
    ) {
        val evil = "x\"; DROP TABLE canary; --"
        val evil2 = "a\"\"b;\"c"
        for (n <- Seq(evil, evil2)) {
            val allowed = Set(n)
            val keyed = db.copy(keyFields = java.util.Arrays.asList("id", n))
            val fs = Seq(SchemaField("id", "string"), SchemaField(n, "string"), SchemaField("_json", "string"))
            SqlIdentifierScan.assertOneStatement(SnowflakeLoader.createTableSql(keyed, fs), '"', '"', allowed, Seq(n), "createTable")
            SqlIdentifierScan.assertOneStatement(SnowflakeLoader.addColumnSql(db, SchemaField(n, "string")), '"', '"', allowed, Seq(n), "addColumn")
            SqlIdentifierScan.assertOneStatement(
                SnowflakeLoader.copySql("datris.public.orders", "@%orders", fs.take(2), ","),
                '"',
                '"',
                allowed,
                Seq(n),
                "copy"
            )
            SqlIdentifierScan.assertOneStatement(
                SnowflakeLoader.copySql("datris.public.orders", "@%orders", fs, ","),
                '"',
                '"',
                allowed,
                Seq(n),
                "copy variant"
            )
            val keyMerge = SqlIdentifierScan.assertOneStatement(
                SnowflakeLoader.mergeSql("datris.public.orders", "DATRIS_STG_x", Seq("id", n, "amount"), Seq(n)),
                '"',
                '"',
                allowed,
                Seq(n),
                "merge on key"
            )
            assert(keyMerge.identifiers.size == 4, "ON t.k = s.k + INSERT (k) + VALUES (s.k): " + keyMerge)
            val colMerge = SqlIdentifierScan.assertOneStatement(
                SnowflakeLoader.mergeSql("datris.public.orders", "DATRIS_STG_x", Seq("id", n), Seq("id")),
                '"',
                '"',
                allowed,
                Seq(n),
                "merge column"
            )
            assert(colMerge.identifiers.size == 4, "SET t.c = s.c + INSERT (c) + VALUES (s.c): " + colMerge)
        }
    }
}
