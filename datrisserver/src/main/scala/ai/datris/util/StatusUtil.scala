package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
*/

import com.google.gson.Gson
import ai.datris.model._
import org.slf4j.{Logger, LoggerFactory}

import java.security.InvalidParameterException
import java.sql.Timestamp
import java.text.SimpleDateFormat
import java.util.{Date, TimeZone}

class StatusUtil {
    private val logger: Logger = LoggerFactory.getLogger(classOf[StatusUtil])

    private var tableName: String = _
    private var processName: Option[String] = None
    private var pipelineToken: Option[String] = None
    private var publisherToken: Option[String] = None
    private var filename: Option[String] = None
    private var hadWarning: Boolean = false
    private var hadError: Boolean = false
    private var recordCount: Int = 0
    private var dataType: Option[String] = None

    def init(tableName: String, processName: String): StatusUtil = {
        this.tableName = tableName
        this.processName = Some(processName)
        this
    }

    def overrideProcessName(processName: String): Unit = {
        this.processName = Some(processName)
    }

    def setPipelineToken(pipelineToken: String): Unit = {
        if(pipelineToken != null)
            this.pipelineToken = Some(pipelineToken)
    }

    def setPublisherToken(publisherToken: String): Unit = {
        if(publisherToken != null)
            this.publisherToken = Some(publisherToken)
    }

    def setFilename(filename: String): Unit = {
        if(filename != null)
            this.filename = Some(filename)
    }

    def setFilename(metadata: PipelineMetadata): Unit = {
        this.filename = Some(metadata.dataFileName)
    }

    def setRecordCount(recordCount: Int): Unit = {
        if(recordCount >= 0)
            this.recordCount = recordCount
    }

    def setDataType(dataType: String): Unit = {
        if(dataType != null)
            this.dataType = Some(dataType)
    }

    def info(state: String, description: String): Unit = {
        send(state, "info", description)
    }

    def warn(state: String, description: String): Unit = {
        hadWarning = true
        send(state, "warning", description)
    }

    def error(state: String, description: String): Unit = {
        hadError = true
        send(state, "error", description)
    }

    private def send(state: String, code: String, description: String): Unit = {
        state match {
            case "begin" | "processing" | "end" =>
            case _ => throw new InvalidParameterException("Invalid state. State must be one of the following: begin, processing, end")
        }
        code match {
            case "info" | "warning" | "error" =>
            case _ => throw new InvalidParameterException("Invalid code.  Code must be one of the following: info, warning, error")
        }

        val status = Status(processName.getOrElse(""),
            publisherToken.getOrElse(""),
            pipelineToken.getOrElse(""),
            filename.getOrElse(""),
            state,
            code,
            description)

        writeToNoSQLDb(status)

        // Write to the logger
        val message = pipelineToken.getOrElse("") + ": " + description
        code match {
            case "info" =>
                if(state.compareTo("processing") == 0)
                    logger.info(message)
            case "warning" =>
                if(state.compareTo("processing") == 0)
                    logger.warn(message)
            case "error" =>
                if(state.compareTo("processing") == 0)
                    logger.error(message)
        }
    }

    private def writeToNoSQLDb(status: Status): Unit = {
        val gson = new Gson
        val nowTimestamp = new Timestamp(new Date().getTime)
        val nowInMillis = new Timestamp(new Date().getTime).getTime

        def utcFormatter: SimpleDateFormat = {
            val sdf = new SimpleDateFormat(DatrisEnvironment.current.dateFormat)
            sdf.setTimeZone(TimeZone.getTimeZone(DatrisEnvironment.current.dateTimezone))
            sdf
        }
        val pipelineName = getPipelineName(status.filename, status.pipelineToken)

        // Query for the pipeline token in the pipeline status summary table
        val statusSummaryList = NoSQLDbUtil.queryJSONItemsByKey(tableName + "-summary", "pipeline_token", status.pipelineToken)

        if(statusSummaryList != null && statusSummaryList.nonEmpty) {
            // If the summary record exists, update it
            val pipelineStatusSummaryTable = gson.fromJson(statusSummaryList.head, classOf[PipelineStatusSummaryTable])
            val pipelineStatusSummary = pipelineStatusSummaryTable.json

            val (elapsed, timedout) = ElapsedTimeUtil.getElapsedTime(nowInMillis - pipelineStatusSummary.createdAt)
            val totalTime = {
                if(timedout)
                    "timed out"
                else
                    elapsed
            }

            val running = {
                if (hadError)
                    false
                else if (hadWarning)
                    false
                else if (timedout)
                    false
                else if (status.processName.compareToIgnoreCase("JobRunner") == 0
                    && status.state.compareToIgnoreCase("end") == 0)
                    false
                else
                    true
            }

            val statusString = {
                if (hadError)
                    "error"
                else if (running)
                    "processing"
                else if (timedout)
                    "error"
                else if (hadWarning)
                    "warning"
                else
                    "success"
            }

            val statusSummary = PipelineStatusSummary(
                pipelineStatusSummary.createdAtTimestamp,
                pipelineStatusSummary.createdAt,
                nowInMillis,
                pipelineName,
                pipelineToken.orNull,
                processName.orNull,
                utcFormatter.format(Timestamp.valueOf(pipelineStatusSummary.createdAtTimestamp)),
                utcFormatter.format(nowTimestamp),
                totalTime,
                statusString,
                // Preserve the prior count when the current event hasn't published
                // one (e.g. an intermediate "processing" status from a loader emitted
                // before JobRunner records the final count).
                if (this.recordCount > 0) this.recordCount else pipelineStatusSummary.recordCount,
                this.dataType.orElse(Option(pipelineStatusSummary.dataType)).orNull
            )

            NoSQLDbUtil.updateItemJSON(tableName + "-summary",
                "pipeline_token",
                pipelineToken.orNull,
                "json",
                gson.toJson(statusSummary),
                "created_at",
                pipelineStatusSummaryTable.created_at
            )
        }
        else {
            // Summary record does not exist, create it
            val statusSummary = PipelineStatusSummary(
                nowTimestamp.toString,
                nowInMillis,
                nowInMillis,
                pipelineName,
                pipelineToken.orNull,
                processName.orNull,
                utcFormatter.format(nowTimestamp),
                utcFormatter.format(nowTimestamp),
                "0 seconds",
                "processing",
                this.recordCount,
                this.dataType.orNull
            )

            NoSQLDbUtil.putItemJSON(tableName + "-summary",
                "pipeline_token", pipelineToken.orNull,
                "json",
                gson.toJson(statusSummary),
                "created_at",
                nowInMillis
            )
        }

        // Save the pipeline status record
        val pipelineStatus = PipelineStatus(
            0,
            utcFormatter.format(nowTimestamp),
            pipelineName,
            status.processName,
            status.publisherToken,
            status.pipelineToken,
            status.filename,
            status.state,
            status.code,
            status.description,
            nowInMillis
        )

        // Top-level `publisher_token` is the indexed read path used by
        // PipelineStatusUtil.getPipelineStatusByPublisher. The same value also lives
        // inside the embedded `json` doc (PipelineStatus.publisherToken) for backward
        // compatibility with older readers; new readers should prefer the top-level field.
        val extra: java.util.Map[String, AnyRef] = {
            if (status.publisherToken != null && status.publisherToken.nonEmpty) {
                val m = new java.util.HashMap[String, AnyRef]()
                m.put("publisher_token", status.publisherToken)
                m
            } else null
        }

        NoSQLDbUtil.putItemJSON(tableName,
            "pipeline_token", pipelineToken.orNull,
            "json",
            gson.toJson(pipelineStatus),
            "created_at",
            nowInMillis,
            extra
        )
    }

    private def getPipelineName(filename: String, pipelineToken: String): String = {
        if(filename != null && filename.contains(".pipeline.")) {
            val tokens = filename.split("\\.")
            tokens(0)
        }
        else if(pipelineToken != null) {
            // metadata.json file ingestion
            val rawValue = NoSQLDbUtil.getItemJSON(DatrisEnvironment.current.archivedMetadataTableName, "pipeline_token", pipelineToken, "metadata").getOrElse(
                throw new DatrisException("Internal error, pipelineToken: " + pipelineToken + " was not found in the NoSQL table")
            )
            val jsonMetadata = rawValue
            val gson = new Gson
            val metadata = gson.fromJson(jsonMetadata, classOf[PipelineMetadata])
            metadata.pipeline
        }
        else
            null
    }
}

object StatusUtil {
    private var _statusUtil: StatusUtil = _
    def init(tableName: String, processName: String): Unit =
        _statusUtil  = new StatusUtil().init(tableName, processName)

    def overrideProcessName(processName: String): Unit =
        _statusUtil.overrideProcessName(processName)

    def setPipelineToken(pipelineToken: String): Unit =
        _statusUtil.setPipelineToken(pipelineToken)

    def setPublisherToken(publisherToken: String): Unit =
        _statusUtil.setPublisherToken(publisherToken)

    def setFilename(filename: String): Unit =
        _statusUtil.setFilename(filename)

    def setFilename(metadata: PipelineMetadata): Unit =
        _statusUtil.setFilename(metadata)

    def setRecordCount(recordCount: Int): Unit =
        _statusUtil.setRecordCount(recordCount)

    def setDataType(dataType: String): Unit =
        _statusUtil.setDataType(dataType)

    def info(state: String, description: String): Unit = {
        if(_statusUtil == null) throw new IllegalStateException("StatusUtil.init() must be called before use")
        _statusUtil.send(state, "info", description)
    }

    def warn(state: String, description: String): Unit = {
        if(_statusUtil == null) throw new IllegalStateException("StatusUtil.init() must be called before use")
        _statusUtil.send(state, "warning", description)
    }

    def error(state: String, description: String): Unit = {
        if(_statusUtil == null) throw new IllegalStateException("StatusUtil.init() must be called before use")
        _statusUtil.send(state, "error", description)
    }
}