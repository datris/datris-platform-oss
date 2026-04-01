package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
*/

import com.google.common.base.Throwables
import com.google.gson.Gson
import ai.datris.model.{DatrisEnvironment, DatrisException}
import ai.datris.util._
import org.slf4j.{Logger, LoggerFactory}
import org.springframework.http.{HttpStatus, MediaType, ResponseEntity}
import org.springframework.web.bind.annotation._

import scala.collection.JavaConverters._

@RestController
@RequestMapping(Array("/api/v1"))
@CrossOrigin(origins = Array("*"), methods = Array(RequestMethod.POST, RequestMethod.OPTIONS))
class SearchAPIController {
    private val logger: Logger = LoggerFactory.getLogger(classOf[SearchAPIController])

    @PostMapping(path = Array("/search/qdrant"), consumes = Array(MediaType.APPLICATION_JSON_VALUE), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def searchQdrant(@RequestHeader(name = "x-api-key", required = false) apiKey: String,
                     @RequestBody body: java.util.Map[String, Any]): ResponseEntity[String] = {
        try {
            logger.info("API endpoint POST /search/qdrant called")
            APIKeyValidator.validate(apiKey)

            val query = requireString(body, "query")
            val collection = optString(body, "collection", "financial_documents")
            val embeddingSecretName = tenantSecretName(optString(body, "embeddingSecretName", DatrisEnvironment.current.embeddingSecretName), DatrisEnvironment.current.embeddingSecretName)
            val qdrantSecretName = tenantSecretName(optString(body, "qdrantSecretName", DatrisEnvironment.current.qdrantSecretName), DatrisEnvironment.current.qdrantSecretName)
            val topK = optInt(body, "topK", 5)

            val results = QdrantSearchUtil.search(query, collection, embeddingSecretName, qdrantSecretName, topK)
            buildResponse(results)
        }
        catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](Throwables.getStackTraceAsString(e))
        }
    }

    @PostMapping(path = Array("/search/weaviate"), consumes = Array(MediaType.APPLICATION_JSON_VALUE), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def searchWeaviate(@RequestHeader(name = "x-api-key", required = false) apiKey: String,
                       @RequestBody body: java.util.Map[String, Any]): ResponseEntity[String] = {
        try {
            logger.info("API endpoint POST /search/weaviate called")
            APIKeyValidator.validate(apiKey)

            val query = requireString(body, "query")
            val className = optString(body, "className", "FinancialDocuments")
            val embeddingSecretName = tenantSecretName(optString(body, "embeddingSecretName", DatrisEnvironment.current.embeddingSecretName), DatrisEnvironment.current.embeddingSecretName)
            val weaviateSecretName = tenantSecretName(optString(body, "weaviateSecretName", DatrisEnvironment.current.weaviateSecretName), DatrisEnvironment.current.weaviateSecretName)
            val topK = optInt(body, "topK", 5)

            val results = WeaviateSearchUtil.search(query, className, embeddingSecretName, weaviateSecretName, topK)
            buildResponse(results)
        }
        catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](Throwables.getStackTraceAsString(e))
        }
    }

    @PostMapping(path = Array("/search/milvus"), consumes = Array(MediaType.APPLICATION_JSON_VALUE), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def searchMilvus(@RequestHeader(name = "x-api-key", required = false) apiKey: String,
                     @RequestBody body: java.util.Map[String, Any]): ResponseEntity[String] = {
        try {
            logger.info("API endpoint POST /search/milvus called")
            APIKeyValidator.validate(apiKey)

            val query = requireString(body, "query")
            val collection = optString(body, "collection", "financial_documents")
            val embeddingSecretName = tenantSecretName(optString(body, "embeddingSecretName", DatrisEnvironment.current.embeddingSecretName), DatrisEnvironment.current.embeddingSecretName)
            val milvusSecretName = tenantSecretName(optString(body, "milvusSecretName", DatrisEnvironment.current.milvusSecretName), DatrisEnvironment.current.milvusSecretName)
            val topK = optInt(body, "topK", 5)

            val results = MilvusSearchUtil.search(query, collection, embeddingSecretName, milvusSecretName, topK)
            buildResponse(results)
        }
        catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](Throwables.getStackTraceAsString(e))
        }
    }

    @PostMapping(path = Array("/search/chroma"), consumes = Array(MediaType.APPLICATION_JSON_VALUE), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def searchChroma(@RequestHeader(name = "x-api-key", required = false) apiKey: String,
                     @RequestBody body: java.util.Map[String, Any]): ResponseEntity[String] = {
        try {
            logger.info("API endpoint POST /search/chroma called")
            APIKeyValidator.validate(apiKey)

            val query = requireString(body, "query")
            val collection = optString(body, "collection", "financial_documents")
            val embeddingSecretName = tenantSecretName(optString(body, "embeddingSecretName", DatrisEnvironment.current.embeddingSecretName), DatrisEnvironment.current.embeddingSecretName)
            val chromaSecretName = tenantSecretName(optString(body, "chromaSecretName", DatrisEnvironment.current.chromaSecretName), DatrisEnvironment.current.chromaSecretName)
            val topK = optInt(body, "topK", 5)

            val results = ChromaSearchUtil.search(query, collection, embeddingSecretName, chromaSecretName, topK)
            buildResponse(results)
        }
        catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](Throwables.getStackTraceAsString(e))
        }
    }

    @PostMapping(path = Array("/search/pgvector"), consumes = Array(MediaType.APPLICATION_JSON_VALUE), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def searchPgvector(@RequestHeader(name = "x-api-key", required = false) apiKey: String,
                       @RequestBody body: java.util.Map[String, Any]): ResponseEntity[String] = {
        try {
            logger.info("API endpoint POST /search/pgvector called")
            APIKeyValidator.validate(apiKey)

            val query = requireString(body, "query")
            val table = optString(body, "table", "financial_documents")
            val schema = optString(body, "schema", "public")
            val embeddingSecretName = tenantSecretName(optString(body, "embeddingSecretName", DatrisEnvironment.current.embeddingSecretName), DatrisEnvironment.current.embeddingSecretName)
            val postgresSecretName = tenantSecretName(optString(body, "postgresSecretName", DatrisEnvironment.current.pgvectorSecretName), DatrisEnvironment.current.pgvectorSecretName)
            val topK = optInt(body, "topK", 5)

            val results = PGVectorSearchUtil.search(query, table, embeddingSecretName, postgresSecretName, schema, topK)
            buildResponse(results)
        }
        catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](Throwables.getStackTraceAsString(e))
        }
    }

    private def buildResponse(results: java.util.List[java.util.Map[String, Any]]): ResponseEntity[String] = {
        val gson = new Gson
        val response = new java.util.LinkedHashMap[String, Any]()
        response.put("results", results)
        response.put("count", results.size())
        new ResponseEntity[String](gson.toJson(response), HttpStatus.OK)
    }

    private def requireString(body: java.util.Map[String, Any], key: String): String = {
        Option(body.get(key)).map(_.toString)
            .getOrElse(throw new DatrisException("'" + key + "' parameter is required"))
    }

    private def optString(body: java.util.Map[String, Any], key: String, default: String): String = {
        Option(body.get(key)).map(_.toString).getOrElse(default)
    }

    /** In multi-tenant mode, always use DatrisEnvironment.current secret names to ensure tenant isolation */
    /** Always use the server's secret name — the client may have stale/incorrect names */
    private def tenantSecretName(clientValue: String, tenantValue: String): String = {
        tenantValue
    }

    private def optInt(body: java.util.Map[String, Any], key: String, default: Int): Int = {
        Option(body.get(key)).map {
            case d: java.lang.Double => d.intValue()
            case i: java.lang.Integer => i.intValue()
            case other => other.toString.toInt
        }.getOrElse(default)
    }
}
