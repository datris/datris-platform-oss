package ai.datris.controller

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import com.google.gson.Gson
import ai.datris.model.{DatrisException, RestEndpoint, StagedFormat}
import ai.datris.util.{CloseableIterator, HttpUtil}
import ai.datris.model.{Data, JobContext}
import org.slf4j.LoggerFactory

import java.util.concurrent.{CountDownLatch, TimeUnit}
import scala.collection.concurrent.TrieMap
import scala.collection.JavaConverters._

object RestEndpointCallbackRegistry {
    private val pending = TrieMap[String, (CountDownLatch, Option[String])]()

    def register(pipelineToken: String): CountDownLatch = {
        val latch = new CountDownLatch(1)
        pending.put(pipelineToken, (latch, None))
        latch
    }

    def complete(pipelineToken: String, data: String): Unit = {
        pending.get(pipelineToken).foreach { case (latch, _) =>
            pending.put(pipelineToken, (latch, Some(data)))
            latch.countDown()
        }
    }

    def getResult(pipelineToken: String): Option[String] = {
        pending.remove(pipelineToken).flatMap(_._2)
    }
}

/** Calls a REST endpoint with the run's payload. One class serves two roles:
  * the preprocessor (`JobRunner` passes the response data downstream) and the
  * destination (`destination = true`). Only the destination role honours
  * `RestEndpoint.batchSize`: with `batchSize > 0` a delimited payload is sent
  * as ceil(rows / batchSize) calls, each carrying `batchSize` rows read from
  * the staged file plus top-level `batch` / `ofBatches`; `0` (the default) and
  * the preprocessor role keep today's single call with the whole payload.
  */
class RestEndpointRunner(jobContext: JobContext, restEndpointConfig: RestEndpoint, destination: Boolean = false) {
    private val logger = LoggerFactory.getLogger(classOf[RestEndpointRunner])
    private val config = restEndpointConfig
    private val gson = new Gson()
    // Support both timeoutMs (preferred) and legacy timeoutSeconds
    private val timeoutMs: Int = {
        if (config.timeoutMs > 0) config.timeoutMs
        else if (config.timeoutSeconds > 0) config.timeoutSeconds * 1000
        else 300000
    }

    /** Batched delivery applies to the destination role, a positive batchSize
      * and a delimited (row) payload with a header. */
    private def batched: Boolean = {
        val staged = jobContext.data.staged
        destination && config.batchSize > 0 && staged != null && !staged.isEmpty &&
        (staged.format match {
            case StagedFormat.Delimited(_) => jobContext.data.header != null
            case _ => false
        })
    }

    def process(): JobContext = {
        val pipelineToken = jobContext.pipelineToken
        val statusUtil = jobContext.statusUtil

        statusUtil.overrideProcessName(this.getClass.getSimpleName)

        statusUtil.info("begin", s"Preprocessor calling endpoint: ${config.endpoint}")

        val jc =
            if (batched) processBatched(pipelineToken)
            else {
                val requestBody = buildRequestBody(pipelineToken, jobContext.config.name, jobContext.data)
                if (config.async) {
                    processAsync(pipelineToken, requestBody)
                } else {
                    processSync(pipelineToken, requestBody)
                }
            }

        statusUtil.info("end", s"Preprocessor completed")
        jc
    }

    /** One JSON request body per HTTP call, in call order. A single body (the
      * whole payload, as before batching existed) unless [[batched]]. The
      * batched iterator reads the staged file lazily; `close()` it if you stop
      * early. */
    private[datris] def requestBodies(): CloseableIterator[String] = {
        if (!batched)
            return CloseableIterator(Iterator.single(buildRequestBody(jobContext.pipelineToken, jobContext.config.name, jobContext.data)), () => ())
        val data = jobContext.data
        val batchSize = config.batchSize
        val ofBatches = ((data.rowCount + batchSize - 1) / batchSize).toInt
        val rows = data.rowIterator()
        val bodies = rows.grouped(batchSize).zipWithIndex.map { case (batch, index) =>
            val map = new java.util.HashMap[String, AnyRef]()
            map.put("size", java.lang.Long.valueOf(data.size))
            map.put("header", data.header.asJava)
            map.put("rows", batch.asJava)
            val payload = new java.util.HashMap[String, AnyRef]()
            payload.put("pipelineToken", jobContext.pipelineToken)
            payload.put("pipelineName", jobContext.config.name)
            payload.put("batch", java.lang.Integer.valueOf(index + 1))
            payload.put("ofBatches", java.lang.Integer.valueOf(ofBatches))
            payload.put("data", map)
            gson.toJson(payload)
        }
        CloseableIterator(bodies, () => rows.close())
    }

    /** Destination role, batchSize > 0: one call per batch, in order. Each
      * response is only checked for an `error` member — a destination does not
      * feed data downstream, so nothing else is read from it. Async batches
      * wait for their callback one at a time. */
    private def processBatched(pipelineToken: String): JobContext = {
        val statusUtil = jobContext.statusUtil
        val bodies = requestBodies()
        var sent = 0
        try
            bodies.foreach { body =>
                val response =
                    if (config.async) {
                        val latch = RestEndpointCallbackRegistry.register(pipelineToken)
                        HttpUtil.post(
                            url = config.endpoint,
                            contentType = "application/json",
                            dataToPost = body,
                            bearerToken = config.bearerToken,
                            apiKey = config.apiKey,
                            timeoutMillis = timeoutMs
                        )
                        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                            RestEndpointCallbackRegistry.getResult(pipelineToken)
                            throw new DatrisException(s"REST destination async: timed out waiting for callback after ${timeoutMs}ms")
                        }
                        RestEndpointCallbackRegistry.getResult(pipelineToken).orNull
                    } else
                        HttpUtil.post(
                            url = config.endpoint,
                            contentType = "application/json",
                            dataToPost = body,
                            bearerToken = config.bearerToken,
                            apiKey = config.apiKey,
                            timeoutMillis = timeoutMs
                        )
                checkForError(response)
                sent += 1
            }
        finally bodies.close()
        statusUtil.info("processing", "Sent " + sent + " batch(es) of up to " + config.batchSize + " rows to " + config.endpoint)
        jobContext
    }

    /** Fail on a `{"error": ...}` response; any other (or empty / non-JSON) body is accepted. */
    private def checkForError(responseBody: String): Unit = {
        if (responseBody == null || responseBody.trim.isEmpty) return
        val error =
            try {
                val result = gson.fromJson(responseBody, classOf[java.util.Map[String, Any]])
                if (result == null) None else Option(result.get("error")).map(_.toString)
            } catch {
                case _: Exception => None
            }
        error.foreach(e => throw new DatrisException(s"REST endpoint returned error: $e"))
    }

    private def processSync(pipelineToken: String, requestBody: String): JobContext = {
        val response = HttpUtil.post(
            url = config.endpoint,
            contentType = "application/json",
            dataToPost = requestBody,
            bearerToken = config.bearerToken,
            apiKey = config.apiKey,
            timeoutMillis = timeoutMs
        )

        jobContext.copy(data = parseResponseData(response))
    }

    private def processAsync(pipelineToken: String, requestBody: String): JobContext = {
        val latch = RestEndpointCallbackRegistry.register(pipelineToken)

        HttpUtil.post(
            url = config.endpoint,
            contentType = "application/json",
            dataToPost = requestBody,
            bearerToken = config.bearerToken,
            apiKey = config.apiKey,
            timeoutMillis = timeoutMs
        )

        logger.info(s"Preprocessor async: waiting for callback for token $pipelineToken")

        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            RestEndpointCallbackRegistry.getResult(pipelineToken)
            throw new DatrisException(s"Preprocessor async: timed out waiting for callback after ${timeoutMs}ms")
        }

        val returnedData = RestEndpointCallbackRegistry.getResult(pipelineToken)
            .getOrElse(throw new DatrisException("Preprocessor async: callback completed but no data returned"))

        jobContext.copy(data = parseResponseData(returnedData))
    }

    private def buildRequestBody(pipelineToken: String, pipelineName: String, data: Data): String = {
        val payload = new java.util.HashMap[String, AnyRef]()
        payload.put("pipelineToken", pipelineToken)
        payload.put("pipelineName", pipelineName)
        payload.put("data", dataToMap(data))
        gson.toJson(payload)
    }

    private def dataToMap(data: Data): java.util.Map[String, AnyRef] = {
        val map = new java.util.HashMap[String, AnyRef]()
        map.put("size", java.lang.Long.valueOf(data.size))
        if (data.header != null)
            map.put("header", data.header.asJava)
        if (data.rows != null)
            map.put("rows", data.rows.asJava)
        if (data.rawData != null)
            map.put("rawData", data.rawData)
        map
    }

    private def parseResponseData(responseBody: String): Data = {
        val result = gson.fromJson(responseBody, classOf[java.util.Map[String, Any]])
        val error = Option(result.get("error")).map(_.toString)
        if (error.isDefined)
            throw new DatrisException(s"REST endpoint returned error: ${error.get}")

        val dataMap = result.get("data").asInstanceOf[java.util.Map[String, Any]]
        val original = jobContext.data
        Data(
            size = Option(dataMap.get("size")).map(_.asInstanceOf[Number].longValue()).getOrElse(original.size),
            header = Option(dataMap.get("header")).map(_.asInstanceOf[java.util.List[String]].asScala.toList).getOrElse(original.header),
            headerWithSchema = original.headerWithSchema,
            rows = Option(dataMap.get("rows")).map(_.asInstanceOf[java.util.List[String]].asScala.toList).getOrElse(original.rows),
            rawData = Option(dataMap.get("rawData")).map(_.asInstanceOf[String]).getOrElse(original.rawData),
            delimiter = {
                val fa = if (jobContext.config.source != null) jobContext.config.source.fileAttributes else null
                if (fa != null && fa.csvAttributes != null && fa.csvAttributes.delimiter != null) fa.csvAttributes.delimiter else ","
            }
        )
    }
}
