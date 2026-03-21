package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
*/

import com.google.common.base.Throwables
import ai.datris.model.{GlobalJobContext, DatrisEnvironment, DatrisException}
import ai.datris.util.{AIProfileUtil, AISchemaUtil, DatasetConfigIO, ObjectStoreUtil, StatusUtil}
import ai.datris.controller.StreamNotifier
import ai.datris.util.APIKeyValidator
import org.slf4j.{Logger, LoggerFactory}
import org.springframework.http.{HttpStatus, MediaType, ResponseEntity}
import org.springframework.web.bind.annotation._
import org.springframework.web.multipart.MultipartFile

import java.io.ByteArrayInputStream
import java.text.SimpleDateFormat
import java.util.Date

@RestController
@RequestMapping(Array("/api/v1"))
@CrossOrigin(origins = Array("*"), methods = Array(RequestMethod.GET, RequestMethod.POST, RequestMethod.DELETE, RequestMethod.OPTIONS))
class FileUploadAPIController {
    private val logger: Logger = LoggerFactory.getLogger(classOf[FileUploadAPIController])

    @PostMapping(path = Array("/dataset/upload"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def uploadRawFile(@RequestHeader(name = "x-api-key", required = false) apiKey: String,
                      @RequestPart("file") multipartFile: MultipartFile,
                      @RequestParam("dataset") dataset: String,
                      @RequestParam(required = false) publishertoken: String): ResponseEntity[String] = {
        try {
            logger.info("API endpoint POST /dataset/upload called for dataset: " + dataset + ", filename: " + multipartFile.getOriginalFilename + ", publishertoken: " + publishertoken)
            APIKeyValidator.validate(apiKey)

            // Validate dataset is registered before processing
            val config = DatasetConfigIO.read(DatrisEnvironment.values.datasetTableName, dataset)
            if (config == null)
                throw new IllegalArgumentException("Dataset '" + dataset + "' is not registered. Use POST /api/v1/dataset to register it first.")

            val byteArray = multipartFile.getBytes
            val filename = multipartFile.getOriginalFilename

            if (isCompressed(filename)) {
                // Compressed files: write to S3 raw bucket and let the normal FileNotifier path handle decompression
                val dateFormat = new SimpleDateFormat("yyyy-MM-dd.HH-mm-ss-SSS")
                val rawFilename = {
                    val ext = filename.substring(filename.lastIndexOf('.') + 1)
                    if (publishertoken != null)
                        config.name + "." + publishertoken + "." + dateFormat.format(new Date()) + "." + System.currentTimeMillis().toString + ".dataset." + ext
                    else
                        config.name + "." + dateFormat.format(new Date()) + "." + System.currentTimeMillis().toString + ".dataset." + ext
                }
                val path = "s3://" + DatrisEnvironment.values.environment + "-raw/temp/" + config.name + "/" + rawFilename
                ObjectStoreUtil.writeBucketObjectFromStream(ObjectStoreUtil.getBucket(path), ObjectStoreUtil.getKey(path), new ByteArrayInputStream(byteArray), byteArray.length.toLong)
                new ResponseEntity[String](HttpStatus.OK)
            } else {
                // Uncompressed files: pass bytes directly into the pipeline in memory, bypassing S3
                val jobContext = new StreamNotifier().process(byteArray, filename, dataset, publishertoken)
                GlobalJobContext.addJobContext(jobContext)
                new ResponseEntity[String](jobContext.pipelineToken, HttpStatus.OK)
            }
        }
        catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                try {
                    val statusUtil = new StatusUtil().init(DatrisEnvironment.values.datasetStatusTableName, this.getClass.getSimpleName)
                    statusUtil.setFilename(dataset)
                    statusUtil.error("end", e.getMessage)
                }
                catch {
                    case _: Exception => // ignore status write failures
                }
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](Throwables.getStackTraceAsString(e))
        }
    }

    @PostMapping(path = Array("/dataset/generate"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def generateAiDataset(@RequestHeader(name = "x-api-key", required = false) apiKey: String,
                          @RequestPart("file") multipartFile: MultipartFile,
                          @RequestParam(required = false) dataset: String,
                          @RequestParam(required = false) delimiter: String,
                          @RequestParam(required = false) header: Boolean): ResponseEntity[String] = {
        try {
            val filename = multipartFile.getOriginalFilename
            val datasetName = {
                if (dataset != null && dataset.nonEmpty)
                    dataset
                else {
                    val name = filename.lastIndexOf('.') match {
                        case -1 => filename
                        case i  => filename.substring(0, i)
                    }
                    name.toLowerCase.replaceAll("[^a-z0-9_]", "_")
                }
            }
            logger.info("API endpoint POST /dataset/generate called for dataset: " + datasetName + ", filename: " + filename)
            APIKeyValidator.validate(apiKey)

            if (!DatrisEnvironment.values.aiEnabled)
                throw new DatrisException("AI schema generation is disabled. Set 'ai.enabled: true' in application.yaml to enable it.")

            val json = {
                if (filename.toLowerCase.endsWith(".json"))
                    AISchemaUtil.buildJsonConfig(datasetName)
                else if (filename.toLowerCase.endsWith(".xml"))
                    AISchemaUtil.buildXmlConfig(datasetName)
                else {
                    val fileContent = new String(multipartFile.getBytes, "UTF-8")
                    AISchemaUtil.buildCsvConfig(datasetName, fileContent, delimiter, header)
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

    @PostMapping(path = Array("/dataset/profile"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def profileDataset(@RequestHeader(name = "x-api-key", required = false) apiKey: String,
                       @RequestPart("file") multipartFile: MultipartFile,
                       @RequestParam(required = false, defaultValue = ",") delimiter: String,
                       @RequestParam(required = false, defaultValue = "true") header: Boolean,
                       @RequestParam(required = false, defaultValue = "200") sampleSize: Int): ResponseEntity[String] = {
        try {
            val filename = multipartFile.getOriginalFilename
            logger.info("API endpoint POST /dataset/profile called, filename: " + filename)
            APIKeyValidator.validate(apiKey)

            if (!DatrisEnvironment.values.aiEnabled)
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