package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
*/

import com.google.common.base.Throwables
import ai.datris.model.{GlobalJobContext, DatrisEnvironment, DatrisException}
import ai.datris.util.{AIProfileUtil, AISchemaUtil, PipelineConfigIO, ObjectStoreUtil, StatusUtil}
import ai.datris.controller.StreamNotifier
import ai.datris.util.APIKeyValidator
import org.apache.commons.compress.archivers.ArchiveStreamFactory
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.slf4j.{Logger, LoggerFactory}
import org.springframework.http.{HttpStatus, MediaType, ResponseEntity}
import org.springframework.web.bind.annotation._
import org.springframework.web.multipart.MultipartFile

import java.io.{BufferedInputStream, ByteArrayInputStream}
import scala.util.control.Breaks._

@RestController
@RequestMapping(Array("/api/v1"))
class FileUploadAPIController {
    private val logger: Logger = LoggerFactory.getLogger(classOf[FileUploadAPIController])

    @PostMapping(path = Array("/pipeline/upload"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def uploadRawFile(@RequestHeader(name = "x-api-key", required = false) apiKey: String,
                      @RequestPart("file") multipartFile: MultipartFile,
                      @RequestParam("pipeline") pipeline: String,
                      @RequestParam(required = false) publishertoken: String): ResponseEntity[String] = {
        try {
            logger.info("API endpoint POST /pipeline/upload called for pipeline: " + pipeline + ", filename: " + multipartFile.getOriginalFilename + ", publishertoken: " + publishertoken)
            APIKeyValidator.validate(apiKey)

            // Validate pipeline is registered before processing
            val config = PipelineConfigIO.read(DatrisEnvironment.current.pipelineTableName, pipeline)
            if (config == null)
                throw new IllegalArgumentException("Pipeline '" + pipeline + "' is not registered. Use POST /api/v1/pipeline to register it first.")

            val byteArray = multipartFile.getBytes
            val filename = multipartFile.getOriginalFilename

            if (isCompressed(filename)) {
                // Compressed files: extract all entries, concatenate data, submit as single batch job
                val extractedFiles = new java.util.ArrayList[(String, Array[Byte])]()
                val lower = filename.toLowerCase

                if (lower.endsWith(".gz")) {
                    val gzIn = new GzipCompressorInputStream(new BufferedInputStream(new ByteArrayInputStream(byteArray)))
                    val extractedBytes = gzIn.readAllBytes()
                    gzIn.close()
                    val extractedName = filename.replaceAll("(?i)\\.gz$", "")
                    extractedFiles.add((extractedName, extractedBytes))
                } else {
                    val buffered = new BufferedInputStream(new ByteArrayInputStream(byteArray))
                    val archiveIn: org.apache.commons.compress.archivers.ArchiveInputStream[_ <: org.apache.commons.compress.archivers.ArchiveEntry] =
                        new ArchiveStreamFactory().createArchiveInputStream(buffered)
                    breakable {
                        while (true) {
                            val entry = archiveIn.getNextEntry
                            if (entry == null) break
                            if (!entry.isDirectory &&
                                !entry.getName.startsWith("__MAC") &&
                                !entry.getName.startsWith("META-INF") &&
                                !entry.getName.startsWith("./._")) {
                                // Read entry bytes using a buffer — readAllBytes() can over-read on archive streams
                                val buffer = new java.io.ByteArrayOutputStream()
                                val buf = new Array[Byte](8192)
                                var len = archiveIn.read(buf)
                                while (len != -1) {
                                    buffer.write(buf, 0, len)
                                    len = archiveIn.read(buf)
                                }
                                val entryBytes = buffer.toByteArray
                                val entryName = entry.getName.split("/").last
                                extractedFiles.add((entryName, entryBytes))
                            }
                        }
                    }
                    archiveIn.close()
                }

                import scala.collection.JavaConverters._

                if (extractedFiles.size() == 0) {
                    throw new DatrisException("No files found in archive: " + filename)
                }

                val isCsv = config.source.fileAttributes != null && config.source.fileAttributes.csvAttributes != null

                if (isCsv && extractedFiles.size() > 1) {
                    // CSV batch mode: concatenate all files into one payload (skip headers on files 2+)
                    logger.info("CSV batch mode: " + extractedFiles.size() + " files extracted from " + filename)
                    val combined = new java.io.ByteArrayOutputStream()
                    var firstFile = true
                    for ((name, bytes) <- extractedFiles.asScala) {
                        logger.info("  Batch file: " + name + " (" + bytes.length + " bytes)")
                        if (firstFile) {
                            combined.write(bytes)
                            firstFile = false
                        } else {
                            val content = new String(bytes, "UTF-8")
                            val lines = content.split("\n", 2)
                            if (lines.length > 1 && config.source.fileAttributes.csvAttributes.header) {
                                if (!combined.toString("UTF-8").endsWith("\n")) combined.write('\n')
                                combined.write(lines(1).getBytes("UTF-8"))
                            } else {
                                if (!combined.toString("UTF-8").endsWith("\n")) combined.write('\n')
                                combined.write(bytes)
                            }
                        }
                    }
                    val batchBytes = combined.toByteArray
                    logger.info("CSV batch combined size: " + batchBytes.length + " bytes from " + extractedFiles.size() + " files")
                    val jobContext = new StreamNotifier().process(batchBytes, extractedFiles.get(0)._1, pipeline, publishertoken)
                    GlobalJobContext.addJobContext(jobContext)
                    new ResponseEntity[String](jobContext.pipelineToken, HttpStatus.OK)
                } else {
                    // Non-CSV or single file: process each file individually
                    logger.info("Processing " + extractedFiles.size() + " file(s) individually from " + filename)
                    val pipelineTokens = new java.util.ArrayList[String]()
                    for ((name, bytes) <- extractedFiles.asScala) {
                        logger.info("  Processing: " + name + " (" + bytes.length + " bytes)")
                        val jobContext = new StreamNotifier().process(bytes, name, pipeline, publishertoken)
                        GlobalJobContext.addJobContext(jobContext)
                        pipelineTokens.add(jobContext.pipelineToken)
                    }
                    new ResponseEntity[String](pipelineTokens.size() + " file(s) submitted", HttpStatus.OK)
                }
            } else {
                // Single file: process directly via StreamNotifier
                val jobContext = new StreamNotifier().process(byteArray, filename, pipeline, publishertoken)
                GlobalJobContext.addJobContext(jobContext)
                new ResponseEntity[String](jobContext.pipelineToken, HttpStatus.OK)
            }
        }
        catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                try {
                    val statusUtil = new StatusUtil().init(DatrisEnvironment.current.pipelineStatusTableName, this.getClass.getSimpleName)
                    statusUtil.setFilename(pipeline)
                    statusUtil.error("end", e.getMessage)
                }
                catch {
                    case _: Exception => // ignore status write failures
                }
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](Throwables.getStackTraceAsString(e))
        }
    }

    @PostMapping(path = Array("/pipeline/generate"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def generateAiPipeline(@RequestHeader(name = "x-api-key", required = false) apiKey: String,
                          @RequestPart("file") multipartFile: MultipartFile,
                          @RequestParam(required = false) pipeline: String,
                          @RequestParam(required = false) delimiter: String,
                          @RequestParam(required = false) header: Boolean,
                          @RequestParam(required = false, defaultValue = "false") allStrings: String): ResponseEntity[String] = {
        try {
            val filename = multipartFile.getOriginalFilename
            val pipelineName = {
                if (pipeline != null && pipeline.nonEmpty)
                    pipeline
                else {
                    val name = filename.lastIndexOf('.') match {
                        case -1 => filename
                        case i  => filename.substring(0, i)
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
        }
        catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](Throwables.getStackTraceAsString(e))
        }
    }

    @PostMapping(path = Array("/pipeline/profile"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def profilePipeline(@RequestHeader(name = "x-api-key", required = false) apiKey: String,
                       @RequestPart("file") multipartFile: MultipartFile,
                       @RequestParam(required = false, defaultValue = ",") delimiter: String,
                       @RequestParam(required = false, defaultValue = "true") header: Boolean,
                       @RequestParam(required = false, defaultValue = "200") sampleSize: Int): ResponseEntity[String] = {
        try {
            val filename = multipartFile.getOriginalFilename
            logger.info("API endpoint POST /pipeline/profile called, filename: " + filename)
            APIKeyValidator.validate(apiKey)

            if (!DatrisEnvironment.current.aiEnabled)
                throw new DatrisException("AI data profiling is disabled. Set 'ai.enabled: true' in application.yaml to enable it.")

            val fileContent = new String(multipartFile.getBytes, "UTF-8")
            val json = AIProfileUtil.profile(fileContent, filename, delimiter, header, sampleSize)
            new ResponseEntity[String](json, HttpStatus.OK)
        }
        catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](Throwables.getStackTraceAsString(e))
        }
    }

    private def isCompressed(filename: String): Boolean = {
        val lower = filename.toLowerCase
        lower.endsWith(".zip") || lower.endsWith(".gz") || lower.endsWith(".tar") || lower.endsWith(".jar")
    }
}