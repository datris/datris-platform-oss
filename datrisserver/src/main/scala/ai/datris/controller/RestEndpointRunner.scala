package ai.datris.controller

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
*/

import com.google.gson.Gson
import ai.datris.model.{DatrisException, RestEndpoint}
import ai.datris.util.HttpUtil
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

class RestEndpointRunner(jobContext: JobContext, restEndpointConfig: RestEndpoint) {
    private val logger = LoggerFactory.getLogger(classOf[RestEndpointRunner])
    private val config = restEndpointConfig
    private val gson = new Gson()
    private val timeoutSeconds = if (config.timeoutSeconds > 0) config.timeoutSeconds else 300

    def process(): JobContext = {
        val pipelineToken = jobContext.pipelineToken
        val statusUtil = jobContext.statusUtil

        statusUtil.overrideProcessName(this.getClass.getSimpleName)

        statusUtil.info("begin", s"Preprocessor calling endpoint: ${config.endpoint}")

        val requestBody = buildRequestBody(pipelineToken, jobContext.config.name, jobContext.data)

        val jc = {
            if (config.async) {
                processAsync(pipelineToken, requestBody)
            } else {
                processSync(pipelineToken, requestBody)
            }
        }

        statusUtil.info("end", s"Preprocessor completed")
        jc
    }

    private def processSync(pipelineToken: String, requestBody: String): JobContext = {
        val response = HttpUtil.post(
            url = config.endpoint,
            contentType = "application/json",
            dataToPost = requestBody,
            bearerToken = config.bearerToken,
            timeoutMillis = timeoutSeconds * 1000
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
            timeoutMillis = timeoutSeconds * 1000
        )

        logger.info(s"Preprocessor async: waiting for callback for token $pipelineToken")

        if (!latch.await(config.timeoutSeconds, TimeUnit.SECONDS)) {
            RestEndpointCallbackRegistry.getResult(pipelineToken)
            throw new DatrisException(s"Preprocessor async: timed out waiting for callback after ${config.timeoutSeconds}s")
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
            rawData = Option(dataMap.get("rawData")).map(_.asInstanceOf[String]).getOrElse(original.rawData)
        )
    }
}