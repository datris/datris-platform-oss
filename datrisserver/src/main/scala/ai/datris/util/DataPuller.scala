package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
*/

import ai.datris.model.{DatabaseAttributes, PipelineConfig, DatrisEnvironment, DatrisException}
import ai.datris.model._
import org.slf4j.{Logger, LoggerFactory}

import java.sql.{Connection, DriverManager, Types}
import java.text.SimpleDateFormat
import java.util
import java.util.Date
import scala.collection.JavaConverters._

class DataPuller {
    private val logger: Logger = LoggerFactory.getLogger(getClass)

    def run(): Unit = {
        PipelinePullTableUtil.getAll.foreach(pipelinePull => {
            val nextPullDate = PipelinePullTableUtil.getNextPullDate(pipelinePull.pipeline)

            // Attempt a pull?
            val now = new Date()
            if(now.compareTo(nextPullDate) > 0) {
                val config = PipelineConfigIO.read(DatrisEnvironment.current.pipelineTableName, pipelinePull.pipeline)

                // Before we pull the data, save the actual pull data date and generate the next pull date from the cron expression
                val generatedNextPullDate = PipelinePullTableUtil.generateNextPullDate(config.source.databaseAttributes.cronExpression)

                val (data, lastTimestamp) = pull(config, pipelinePull)
                if(data == null) {
                    // Re-initialize the data pull table to reset the next pull date request
                    PipelinePullTableUtil.update(config.name, generatedNextPullDate, null)
                }
                else {
                    // Re-initialize the data pull table to reset the next pull date request and the last pull date
                    PipelinePullTableUtil.update(config.name, generatedNextPullDate, lastTimestamp)

                    // Write the data to the raw bucket
                    val rawFilename = {
                        val dateFormat = new SimpleDateFormat("yyyy-MM-dd.HH-mm-ss-SSS")
                        val date = dateFormat.format(new Date())
                        config.name + "." + date + "." + System.currentTimeMillis().toString + ".pipeline.csv"
                    }
                    val path = "s3://" + DatrisEnvironment.current.environment + "-raw/temp/" + config.name + "/" + rawFilename
                    ObjectStoreUtil.writeBucketObject(ObjectStoreUtil.getBucket(path), ObjectStoreUtil.getKey(path), data)
                }
            }
        })
    }

    private def pull(config: PipelineConfig, pipelinePull: PipelinePull): (String, String) = {
        logger.info("Attempting to pull data for pipeline: " + config.name)
        val databaseAttributes = config.source.databaseAttributes

        val connection = getDatabaseConnection(databaseAttributes)
        val rows = new util.ArrayList[String]()
        var lastTimestamp:String = null

        val outputDelimiter = {
            if(databaseAttributes.outputDelimiter == null)
                ","
            else
                databaseAttributes.outputDelimiter
        }

        var preparedStatement: java.sql.PreparedStatement = null
        var resultSet: java.sql.ResultSet = null
        try {
            val sql = new StringBuilder()
            if(databaseAttributes.sqlOverride != null) {
                sql.append(databaseAttributes.sqlOverride)
            }
            else {
                val fieldNames = getFieldNames(config)
                sql.append("select ")
                sql.append(fieldNames.mkString(","))
                sql.append(" from ")
                if(databaseAttributes.database != null)
                    sql.append(databaseAttributes.database + ".")
                if(databaseAttributes.schema != null)
                    sql.append(databaseAttributes.schema + ".")
                sql.append(databaseAttributes.table)
                if (pipelinePull.lastPullTimestampUsed != null) {
                    sql.append(" where ")
                    sql.append(databaseAttributes.timestampFieldName + " > '" + pipelinePull.lastPullTimestampUsed + "'")
                }
                sql.append(" order by " + fieldNames.last)
            }

            // Do the query
            logger.info("For pipeline: " + config.name + ", pull data query: " + sql.mkString)
            preparedStatement = connection.prepareStatement(sql.mkString)
            resultSet = preparedStatement.executeQuery()

            val resultSetMetadata = resultSet.getMetaData
            while(resultSet.next()) {
                val row = (1 until resultSetMetadata.getColumnCount + 1).toList.map(index => {
                    // TODO - Consolidate later to use the SQLUtil.getResultSet
                    val dataType = resultSetMetadata.getColumnType(index)
                    val columnName = resultSetMetadata.getColumnName(index)
                    dataType match {
                        case Types.BOOLEAN | Types.BIT =>
                            resultSet.getBoolean(index).toString
                        case Types.TINYINT | Types.SMALLINT | Types.INTEGER =>
                            resultSet.getInt(index).toString
                        case Types.BIGINT =>
                            resultSet.getLong(index).toString
                        case Types.NUMERIC | Types.DECIMAL =>
                            resultSet.getBigDecimal(index).toString
                        case Types.REAL =>
                            resultSet.getFloat(index).toString
                        case Types.FLOAT | Types.DOUBLE =>
                            resultSet.getDouble(index).toString
                        case Types.TIME | Types.TIME_WITH_TIMEZONE =>
                            resultSet.getTime(index).toString
                        case Types.TIMESTAMP | Types.TIMESTAMP_WITH_TIMEZONE =>
                            if(columnName.compareToIgnoreCase(databaseAttributes.timestampFieldName) == 0) {
                                val timestamp = resultSet.getTimestamp(index)
                                if(timestamp == null)
                                    null
                                else {
                                    val formatter = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS")
                                    val timestampAsString = formatter.format(timestamp)
                                    if(timestamp.toString.length > timestampAsString.length)
                                        timestamp.toString
                                    else
                                        timestampAsString
                                }
                            }
                            else {
                                val timestamp = resultSet.getTimestamp(index)
                                if(timestamp == null) null else timestamp.toString
                            }
                        case Types.DATE =>
                            resultSet.getDate(index).toString
                        case Types.CHAR | Types.VARCHAR | Types.LONGVARCHAR =>
                            resultSet.getString(index)
                        case _ =>
                            throw new DatrisException("Column type name: " + resultSetMetadata.getColumnTypeName(index) + ",column type: " + resultSetMetadata.getColumnType(index) + " is not currently supported, please contact customer support")
                    }
                })
                lastTimestamp = row.last

                // Drop the timestamp column at the end
                val rowWithDelimiter = row.dropRight(1).mkString(outputDelimiter)

                rows.add(rowWithDelimiter)
            }
        }
        finally {
            if(resultSet != null) resultSet.close()
            if(preparedStatement != null) preparedStatement.close()
            connection.close()
        }

        if(rows.size() == 0)
            (null, null)
        else
            (rows.asScala.mkString("\n"), lastTimestamp)
    }

    private def getDatabaseConnection(databaseAttributes: DatabaseAttributes): Connection = {
        // Grab the secrets
        val (secrets, secretsName) = {
            if(databaseAttributes.postgresSecretsName != null) {
                Class.forName("org.postgresql.Driver")

                val secrets = SecretsUtil.getSecretMap(databaseAttributes.postgresSecretsName)
                    .getOrElse(throw new DatrisException("Secrets not found for secret name: " + databaseAttributes.postgresSecretsName))
                (secrets, databaseAttributes.postgresSecretsName)
            }
            else if(databaseAttributes.mysqlSecretsName != null) {
                Class.forName("com.mysql.cj.jdbc.Driver")

                val secrets = SecretsUtil.getSecretMap(databaseAttributes.mysqlSecretsName)
                    .getOrElse(throw new DatrisException("Secrets not found for secret name: " + databaseAttributes.mysqlSecretsName))
                (secrets, databaseAttributes.mysqlSecretsName)
            }
            else if(databaseAttributes.mssqlSecretsName != null) {
                Class.forName("com.microsoft.sqlserver.jdbc.SQLServerDriver")

                val secrets = SecretsUtil.getSecretMap(databaseAttributes.mssqlSecretsName)
                    .getOrElse(throw new DatrisException("Secrets not found for secret name: " + databaseAttributes.mssqlSecretsName))
                (secrets, databaseAttributes.mssqlSecretsName)
            }
            else {
                throw new DatrisException("The pipeline configuration 'source.databaseAttributes' does not contain a database secrets name")
            }
        }

        val jdbcUrl = secrets.get("jdbcUrl")
        if(jdbcUrl == null)
            throw new DatrisException("The 'jdbcUrl' does not exist in the Secrets Manager secrets: " + secretsName)
        val username = secrets.get("username")
        if(username == null)
            throw new DatrisException("The 'username' does not exist in the Secrets Manager secrets: " + secretsName)
        val password = secrets.get("password")
        if(password == null)
            throw new DatrisException("The 'password' does not exist in the Secrets Manager secrets: " + secretsName)

        DriverManager.getConnection(jdbcUrl, username, password)
    }

    private def getFieldNames(config: PipelineConfig): List[String] = {
        val databaseAttributes = config.source.databaseAttributes

        // For mssql, reserved columm names must be surrounded with brackets (e.g. '[column_name]').  But surrounding all columns also works
        val fieldNames = {
            if (databaseAttributes.includeFields == null)
                config.source.schemaProperties.fields.asScala.map(_.name).toList
            else
                databaseAttributes.includeFields.asScala.toList
        }
        fieldNames ::: List(config.source.databaseAttributes.timestampFieldName)
    }
}
