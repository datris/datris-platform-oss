package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import com.google.common.base.Throwables
import ai.datris.model.{DatrisEnvironment, DatrisException, GlobalJobContext, JobContext}
import ai.datris.util.{AIProfileUtil, AISchemaUtil, PipelineConfigIO, StagingArea, StatusUtil}
import ai.datris.controller.StreamNotifier
import ai.datris.util.APIKeyValidator
import org.slf4j.{Logger, LoggerFactory}
import org.springframework.http.{HttpStatus, MediaType, ResponseEntity}
import org.springframework.web.bind.annotation._
import org.springframework.web.multipart.MultipartFile

import java.io.BufferedInputStream
import java.nio.file.Files
import java.util.UUID

@RestController
@RequestMapping(Array("/api/v1"))
class FileUploadAPIController {
    private val logger: Logger = LoggerFactory.getLogger(classOf[FileUploadAPIController])

    @PostMapping(path = Array("/pipeline/upload"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def uploadRawFile(
        @RequestHeader(name = "x-api-key", required = false) apiKey: String,
        @RequestPart("file") multipartFile: MultipartFile,
        @RequestParam("pipeline") pipeline: String,
        @RequestParam(required = false) publishertoken: String
    ): ResponseEntity[String] = {
        try {
            logger.info("API endpoint POST /pipeline/upload called for pipeline: " + pipeline + ", filename: " + multipartFile
                .getOriginalFilename + ", publishertoken: " + publishertoken)
            APIKeyValidator.validate(apiKey)

            // Validate pipeline is registered before processing
            val config = PipelineConfigIO.read(DatrisEnvironment.current.pipelineTableName, pipeline)
            if (config == null)
                throw new IllegalArgumentException("Pipeline '" + pipeline + "' is not registered. Use POST /api/v1/pipeline to register it first.")

            val filename = multipartFile.getOriginalFilename
            val isCsv = config.source.fileAttributes != null && config.source.fileAttributes.csvAttributes != null
            val csvHeader = isCsv && config.source.fileAttributes.csvAttributes.header

            // The part streams into staged files under an upload token
            // (plans/stories/streaming-pipeline-phase5.md, Step 2); each staged
            // file is then fed to its own run, which copies it into the run's
            // own directory (JobRunner's to reclaim). The upload directory goes
            // in the finally, success or failure.
            val uploadToken = "upload-" + UUID.randomUUID().toString
            try
                StagingArea.withToken(uploadToken) {
                    val staged = UploadStager.stage(multipartFile.getInputStream, filename, isCsv, csvHeader)

                    def submit(file: StagedUpload): JobContext = {
                        val in = new BufferedInputStream(Files.newInputStream(file.path))
                        val jobContext = new StreamNotifier().process(in, file.bytes, file.name, pipeline, publishertoken, null)
                        GlobalJobContext.addJobContext(jobContext)
                        jobContext
                    }

                    if (!UploadStager.isArchive(filename)) {
                        // Single file: process directly via StreamNotifier
                        new ResponseEntity[String](submit(staged.head).pipelineToken, HttpStatus.OK)
                    } else if (staged.size == 1 && staged.head.batchedEntries > 1) {
                        // CSV batch mode: every entry concatenated into one payload, one run
                        new ResponseEntity[String](submit(staged.head).pipelineToken, HttpStatus.OK)
                    } else {
                        // Non-CSV or single file: process each file individually
                        logger.info("Processing " + staged.size + " file(s) individually from " + filename)
                        staged.foreach { file =>
                            logger.info("  Processing: " + file.name + " (" + file.bytes + " bytes)")
                            submit(file)
                        }
                        new ResponseEntity[String](staged.size + " file(s) submitted", HttpStatus.OK)
                    }
                }
            finally StagingArea.delete(uploadToken)
        } catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                try {
                    val statusUtil = new StatusUtil().init(DatrisEnvironment.current.pipelineStatusTableName, this.getClass.getSimpleName)
                    statusUtil.setFilename(pipeline)
                    statusUtil.error("end", e.getMessage)
                } catch {
                    case e2: Exception =>
                        // ignore status write failures
                        logger.debug("Failed to write error status for pipeline '" + pipeline + "'", e2)
                }
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](QueryAPIController.errorBody(e))
        }
    }

    @PostMapping(path = Array("/pipeline/generate"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def generateAiPipeline(
        @RequestHeader(name = "x-api-key", required = false) apiKey: String,
        @RequestPart("file") multipartFile: MultipartFile,
        @RequestParam(required = false) pipeline: String,
        @RequestParam(required = false) delimiter: String,
        @RequestParam(required = false) header: Boolean,
        @RequestParam(required = false, defaultValue = "false") allStrings: String
    ): ResponseEntity[String] = {
        try {
            val filename = multipartFile.getOriginalFilename
            val pipelineName = {
                if (pipeline != null && pipeline.nonEmpty)
                    pipeline
                else {
                    val name = filename.lastIndexOf('.') match {
                        case -1 => filename
                        case i => filename.substring(0, i)
                    }
                    name.toLowerCase.replaceAll("[^a-z0-9_]", "_")
                }
            }
            logger.info("API endpoint POST /pipeline/generate called for pipeline: " + pipelineName + ", filename: " + filename)
            APIKeyValidator.validate(apiKey)

            if (!DatrisEnvironment.current.aiEnabled)
                throw new DatrisException("AI schema generation is disabled. Set 'ai.enabled: true' in application.yaml to enable it.")

            val json = {
                if (filename.toLowerCase.endsWith(".json"))
                    AISchemaUtil.buildJsonConfig(pipelineName)
                else if (filename.toLowerCase.endsWith(".xml"))
                    AISchemaUtil.buildXmlConfig(pipelineName)
                else {
                    val fileContent = new String(multipartFile.getBytes, "UTF-8")
                    if (allStrings.equalsIgnoreCase("true"))
                        AISchemaUtil.buildCsvConfigAllStrings(pipelineName, fileContent, delimiter, header)
                    else
                        AISchemaUtil.buildCsvConfig(pipelineName, fileContent, delimiter, header)
                }
            }
            new ResponseEntity[String](json, HttpStatus.OK)
        } catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](QueryAPIController.errorBody(e))
        }
    }

    @PostMapping(path = Array("/pipeline/profile"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def profilePipeline(
        @RequestHeader(name = "x-api-key", required = false) apiKey: String,
        @RequestPart("file") multipartFile: MultipartFile,
        @RequestParam(required = false, defaultValue = ",") delimiter: String,
        @RequestParam(required = false, defaultValue = "true") header: Boolean,
        @RequestParam(required = false, defaultValue = "200") sampleSize: Int
    ): ResponseEntity[String] = {
        try {
            val filename = multipartFile.getOriginalFilename
            logger.info("API endpoint POST /pipeline/profile called, filename: " + filename)
            APIKeyValidator.validate(apiKey)

            if (!DatrisEnvironment.current.aiEnabled)
                throw new DatrisException("AI data profiling is disabled. Set 'ai.enabled: true' in application.yaml to enable it.")

            val fileContent = new String(multipartFile.getBytes, "UTF-8")
            val json = AIProfileUtil.profile(fileContent, filename, delimiter, header, sampleSize)
            new ResponseEntity[String](json, HttpStatus.OK)
        } catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](QueryAPIController.errorBody(e))
        }
    }
}
