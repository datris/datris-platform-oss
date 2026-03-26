package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
*/

import com.google.gson.Gson
import ai.datris.model.{Notification, DatrisEnvironment}
import ai.datris.model._
import org.postgresql.copy.CopyManager
import org.postgresql.core.BaseConnection
import org.slf4j.{Logger, LoggerFactory}

import java.sql.{Connection, DriverManager, Statement}
import java.util.Properties
import scala.collection.JavaConverters._
import scala.util.Try

class PostgresLoader(jobContext: JobContext) {
    private val logger: Logger = LoggerFactory.getLogger(classOf[PostgresLoader])
    private val config = jobContext.config
    private val statusUtil = jobContext.statusUtil

    def process(): Unit = {
        statusUtil.overrideProcessName(this.getClass.getSimpleName)

        statusUtil.info("begin", "Loading the data into Postgres database: " + config.destination.database.dbName + ", table: " + config.destination.database.table)

        val secrets = SecretsRetrieverUtil.postgresSecrets()

        Class.forName("org.postgresql.Driver")
        statusUtil.info("processing", "Postgres driver loaded successfully")

        var conn: Connection = null
        var statement: Statement = null

        try {
            val properties = new Properties()
            properties.setProperty("user", secrets.username)
            properties.setProperty("password", secrets.password)
            val jdbcUrl = secrets.jdbcUrl + "/" + config.destination.database.dbName
            statusUtil.info("processing", "jdbc url: " + jdbcUrl)
            conn = DriverManager.getConnection(jdbcUrl, properties)
            statusUtil.info("processing", "Postgres connection acquired")
            if (config.destination.database.useTransaction)
                conn.setAutoCommit(false)
            statement = conn.createStatement()

            val file = createStagingFile()

            if(config.destination.database.truncateBeforeWrite) {
                statusUtil.info("processing", "'truncateTableBeforeWrite' is set to true, truncating table")
                statement.execute("truncate table " + config.destination.database.dbName + "." + config.destination.database.schema + "." + config.destination.database.table)
            }

            copyInto(conn, statement, file)

            if (config.destination.database.useTransaction)
                conn.commit()
            sendNotification()
            statusUtil.info("end", "Process completed")
        } catch {
            case e: Exception =>
                if (config.destination.database.useTransaction && conn != null)
                    Try(conn.rollback())
                throw e
        } finally {
            if (statement != null)
                statement.close()
            if (conn != null)
                conn.close()
        }
    }

    private def createStagingFile(): String = {
        // Write the data to a temp location
        val tempUrl = "s3://" + DatrisEnvironment.values.environment + "-temp/data/" + GuidV5.nameUUIDFrom(System.currentTimeMillis().toString).toString + ".csv"
        val data = if (jobContext.data.rows != null && jobContext.data.rows.nonEmpty)
            jobContext.data.rows.mkString("\n")
        else if (jobContext.data.rawData != null)
            // Wrap rawData in CSV quoting — escape internal quotes by doubling them
            "\"" + jobContext.data.rawData.replace("\"", "\"\"") + "\""
        else
            throw new DatrisException("No data to load — both rows and rawData are empty")
        ObjectStoreUtil.writeBucketObject(ObjectStoreUtil.getBucket(tempUrl), ObjectStoreUtil.getKey(tempUrl), data)
        tempUrl
    }

    private def copyInto(conn: Connection, statement: Statement, fileUrl: String): Unit = {
        statusUtil.info("processing", "Copying data into " + config.destination.database.table)

        if(!config.destination.database.manageTableManually)
            createTableIfUndefined(statement, config.destination.database.table)

        val sql = new StringBuilder()
        sql.append("COPY " + "\"" + config.destination.database.schema + "\"" + "." + "\"" + config.destination.database.table + "\"" + " FROM STDIN (")

        // Append the options (i.e. DELIMITER ',', FORMAT csv, etc)
        if(config.destination.database.options != null) {
            val options = config.destination.database.options.asScala.mkString(", ")
            sql.append(options)
        }
        else {
            // Default to CSV if no options are declared
            // NULL '.' handles common placeholder values (e.g., FRED uses "." for missing data)
            sql.append("FORMAT csv, NULL '.'")
        }

        sql.append(")")

        statusUtil.info("processing", "Copy command: " + sql.toString())
        val inputStream = ObjectStoreUtil.getInputStream(ObjectStoreUtil.getBucket(fileUrl), ObjectStoreUtil.getKey(fileUrl))
        val rowsInserted = new CopyManager(conn.asInstanceOf[BaseConnection])
            .copyIn(sql.mkString, inputStream)
        statusUtil.info("processing", "Rows inserted into table: " + rowsInserted.toString)

        inputStream.close()
    }

    private def createTableIfUndefined(statement: Statement, tableName: String): Unit = {
        val sql = new StringBuilder()

        // Begin
        val dbName = config.destination.database.dbName
        val schema = config.destination.database.schema
        sql.append("create table if not exists " + dbName + "." + schema + "." + tableName + " (")

        // Fields
        config.destination.schemaProperties.fields.forEach(field => {
            sql.append("\"" + field.name + "\" ")
            // Force the semi-structured field type to SUPER
            if(field.name.compareToIgnoreCase("_json") == 0)
                sql.append("json, ")
            else if(field.name.compareToIgnoreCase("_xml") == 0)
                sql.append("xml, ")
            else if(field.`type`.compareToIgnoreCase("tinyint") == 0)
                sql.append("int2, ")
            else if(field.`type`.compareToIgnoreCase("smallint") == 0)
                sql.append("int2, ")
            else if(field.`type`.compareToIgnoreCase("float") == 0)
                sql.append("float4, ")
            else if(field.`type`.compareToIgnoreCase("double") == 0)
                sql.append("float8, ")
            else if(field.`type`.compareToIgnoreCase("string") == 0)
                sql.append("text, ")
            else
                sql.append(field.`type` + ", ")
        })
        sql.setLength(sql.length - 2)

        // Keys?
        if(config.destination.database.keyFields != null) {
            sql.append(", primary key (")
            config.destination.database.keyFields.forEach(field => {
                sql.append(field + ", ")
            })
            sql.setLength(sql.length - 2)
            sql.append(")")
        }

        // End
        sql.append(");")

        // Create schema if it doesn't exist
        if (config.destination.database.schema != null && config.destination.database.schema.nonEmpty) {
            statement.execute("CREATE SCHEMA IF NOT EXISTS " + config.destination.database.schema + ";")
        }

        statusUtil.info("processing", "Postgres create table statement: " + sql.mkString)
        statement.execute(sql.mkString)
    }

    private def sendNotification(): Unit = {
        val notification = Notification(
            config.name,
            jobContext.metadata.publisherToken,
            jobContext.pipelineToken,
            "postgres",
            null,
            null,
            null,
            config.destination.database.schema,
            config.destination.database.dbName,
            config.destination.database.table,
            null
        )
        val gson = new Gson
        val jsonNotification = gson.toJson(notification)

        // Create the message attributes for the notification filter
        val attributes = new java.util.HashMap[String, String]
        attributes.put("pipeline", config.name)
        attributes.put("destination", "postgres")
        attributes.put("schema", config.destination.database.schema)
        attributes.put("database", config.destination.database.dbName)
        attributes.put("table", config.destination.database.table)

        NotificationUtil.add(DatrisEnvironment.values.pipelineTopic, jsonNotification, attributes.asScala.toMap)
        statusUtil.info("processing", "notification sent: " + jsonNotification)
    }
}
