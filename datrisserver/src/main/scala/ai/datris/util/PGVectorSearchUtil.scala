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

            // Dim guard: switching the embedding provider between ingest and
            // query (e.g. Ollama bge-m3 1024-dim → OpenAI text-embedding-3-small
            // 1536-dim) leaves the collection's stored vectors at the old dim.
            // Without this check, Postgres throws a raw "different vector
            // dimensions X and Y" exception that surfaces as a stack trace.
            val storedDimStmt = conn.prepareStatement(
                "SELECT atttypmod FROM pg_attribute " +
                "WHERE attrelid = (?::regclass) AND attname = 'embedding' AND NOT attisdropped"
            )
            storedDimStmt.setString(1, "\"" + schema + "\".\"" + table + "\"")
            val storedDim: Option[Int] = try {
                val rs = storedDimStmt.executeQuery()
                val v = if (rs.next()) Some(rs.getInt(1)) else None
                rs.close()
                v.filter(_ > 0)
            } finally storedDimStmt.close()

            storedDim.foreach { dim =>
                if (dim != queryEmbedding.length)
                    throw new DatrisException(
                        s"Vector dimension mismatch on $schema.$table: collection stores $dim-dim vectors but the current embedding model " +
                        s"('${embeddingConfig.model}' at ${embeddingConfig.endpoint}) produces ${queryEmbedding.length}-dim vectors. " +
                        s"Switch the embedding provider in Configuration to one that produces $dim-dim vectors, or re-ingest the collection under the current provider."
                    )
            }

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
