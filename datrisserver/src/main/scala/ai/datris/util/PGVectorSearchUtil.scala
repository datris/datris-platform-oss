package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
*/

import ai.datris.model.{DatrisEnvironment, DatrisException}
import org.slf4j.{Logger, LoggerFactory}

import java.sql.{Connection, DriverManager}
import java.util.Properties
import scala.collection.JavaConverters._

object PGVectorSearchUtil {
    private val logger: Logger = LoggerFactory.getLogger(getClass)

    def search(query: String, table: String, embeddingSecretName: String,
               postgresSecretName: String, schema: String = "public",
               topK: Int = 5): java.util.List[java.util.Map[String, Any]] = {

        if (query == null || query.trim.isEmpty)
            throw new DatrisException("Search query cannot be empty")

        val embeddingConfig = EmbeddingUtil.getConfig(embeddingSecretName)
        val queryEmbedding = EmbeddingUtil.generateEmbeddings(List(query), embeddingConfig).head

        val pgSecret = SecretsUtil.getSecretMap(postgresSecretName)
            .getOrElse(throw new DatrisException("PostgreSQL secret not found: " + postgresSecretName))
        val rawJdbcUrl = pgSecret.get("jdbcUrl")
        if (rawJdbcUrl == null) throw new DatrisException("'jdbcUrl' not found in pgvector secret: " + postgresSecretName)
        val jdbcUrl = if (DatrisEnvironment.current.multiTenant) {
            rawJdbcUrl.replaceFirst("/[^/]*$", "/" + DatrisEnvironment.current.environment)
        } else rawJdbcUrl
        val username = Option(pgSecret.get("username")).getOrElse("postgres")
        val password = Option(pgSecret.get("password")).getOrElse("")

        logger.info("Searching pgvector table: " + schema + "." + table + " at " + jdbcUrl)

        Class.forName("org.postgresql.Driver")
        val props = new Properties()
        props.setProperty("user", username)
        props.setProperty("password", password)

        var conn: Connection = null
        try {
            conn = DriverManager.getConnection(jdbcUrl, props)
            conn.setReadOnly(true)

            // Discover columns (exclude id and embedding)
            val metaStmt = conn.createStatement()
            val metaRs = metaStmt.executeQuery(
                "SELECT column_name FROM information_schema.columns WHERE table_schema = '" +
                    schema.replace("'", "''") + "' AND table_name = '" +
                    table.replace("'", "''") + "' ORDER BY ordinal_position"
            )
            val allColumns = new java.util.ArrayList[String]()
            while (metaRs.next()) {
                allColumns.add(metaRs.getString("column_name"))
            }
            metaRs.close()
            metaStmt.close()

            val columns = allColumns.asScala.filter(c => c != "id" && c != "embedding").toList
            if (columns.isEmpty)
                throw new DatrisException("No queryable columns found in table: " + schema + "." + table)

            // Build the vector search query
            val vectorStr = "[" + queryEmbedding.mkString(",") + "]"
            val columnList = columns.map(c => "\"" + c + "\"").mkString(", ")
            val sql = "SELECT " + columnList + ", 1 - (embedding <=> '" + vectorStr + "'::vector) AS similarity" +
                " FROM \"" + schema + "\".\"" + table + "\"" +
                " ORDER BY embedding <=> '" + vectorStr + "'::vector" +
                " LIMIT " + topK

            val stmt = conn.createStatement()
            val rs = stmt.executeQuery(sql)
            val resultMeta = rs.getMetaData
            val columnCount = resultMeta.getColumnCount

            val results = new java.util.ArrayList[java.util.Map[String, Any]]()
            while (rs.next()) {
                val row = new java.util.LinkedHashMap[String, Any]()
                for (i <- 1 to columnCount) {
                    val colName = resultMeta.getColumnLabel(i)
                    if (colName == "similarity")
                        row.put("_score", rs.getDouble(i))
                    else
                        row.put(colName, rs.getObject(i))
                }
                results.add(row)
            }

            rs.close()
            stmt.close()

            logger.info("pgvector search returned " + results.size() + " results")
            results
        } finally {
            if (conn != null) conn.close()
        }
    }
}
