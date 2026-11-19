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

object SearchAPIController {
    private val companionLogger: Logger = LoggerFactory.getLogger(classOf[SearchAPIController])

    /** False only for a limited caller ([[ai.datris.auth.CapabilityCheck.isLimited]]:
      * a restricted user's session, or an API key limited by a `catalog=` /
      * `owner=` scope) unless at least one pipeline names `container` and
      * every pipeline naming it is one it may read
      * ([[PipelineAPIController.mayRead]]). `schema` is the request's
      * pgvector schema. Everyone else (unrestricted sessions, unscoped and
      * full keys): true, without reading configs. */
    def containerVisible(
        request: jakarta.servlet.http.HttpServletRequest,
        store: String,
        container: String,
        schema: Option[String],
        find: (String, String, Option[String]) => Seq[ai.datris.model.PipelineConfig]
    ): Boolean =
        !ai.datris.auth.CapabilityCheck.isLimited(request) || {
            val namers = find(store, container, schema)
            namers.nonEmpty && namers.forall(c => PipelineAPIController.mayRead(request, c))
        }

    /** The search gate and its allowed writers. Callers that are not
      * limited (unrestricted sessions, unscoped and full keys, no identity):
      * `Right(None)`, without calling `readAll`. A limited caller (a
      * restricted user's session, or a catalog- / owner-scoped API key):
      * one `readAll` (nulls dropped); the [[containerVisible]] rule on it,
      * `Left` the existing 404 when it fails (for keys too: a container is
      * a lookup, D2); otherwise `Right(Some(names))`, the names of every
      * pipeline the caller can read
      * (in any store), plus the previous names of those among them that name
      * this container ([[PipelineAliases.recordNames]]), less any name reused
      * after a rename with a pipeline it may belong to hidden
      * ([[PipelineAliases.shownRecordNames]]). A `readAll` failure propagates (fail closed). */
    def searchWriters(
        request: jakarta.servlet.http.HttpServletRequest,
        store: String,
        container: String,
        schema: Option[String],
        readAll: () => Seq[ai.datris.model.PipelineConfig]
    ): Either[ResponseEntity[String], Option[Set[String]]] =
        if (!ai.datris.auth.CapabilityCheck.isLimited(request)) Right(None)
        else {
            val configs = Option(readAll()).getOrElse(Nil).filter(_ != null)
            if (!containerVisible(request, store, container, schema, (s, c, sc) => VectorSearchRegistry.pipelinesForContainer(s, c, configs, sc))) {
                val param = VectorSearchRegistry.forStore(store).map(_.containerParam).getOrElse("container")
                Left(containerNotFound(param, container))
            } else {
                // Chunks keep the name they were written under: a renamed
                // pipeline's earlier names count for it, but only for a
                // pipeline that names this container (PipelineAliases).
                // A name reused after a rename counts only when every
                // pipeline it may belong to is visible.
                val visible = PipelineAPIController.visiblePipelines(request, configs)
                val namers = VectorSearchRegistry.pipelinesForContainer(store, container, configs, schema).map(_.name).toSet
                val shown = PipelineAliases.shownRecordNames(visible, configs)
                Right(Some(
                    (visible.map(_.name).toSet ++ PipelineAliases.recordNames(visible.filter(c => namers.contains(c.name)), configs)).filter(shown.contains)
                ))
            }
        }

    /** The single place search hits are post-processed before the response.
      * `writers` from [[searchWriters]]: None returns `hits` itself (same
      * instance); Some keeps only chunks written by one of those pipelines
      * ([[VectorChunkWriter.keepWrittenBy]]), then drops the tap stamp
      * columns of survivors fed by a tap the session cannot read
      * ([[ai.datris.auth.HiddenTapScrub.scrubHits]]). Further per-hit scrubs
      * belong here, after the writer check, applied to the survivors. Reads
      * the tap and run-lineage stores, only for a surviving hit that carries
      * a tap stamp or a feed filename. */
    def hitsFor(
        request: jakarta.servlet.http.HttpServletRequest,
        hits: java.util.List[java.util.Map[String, Any]],
        writers: Option[Set[String]]
    ): java.util.List[java.util.Map[String, Any]] =
        hitsFor(request, hits, writers, n => TapConfigIO.read(DatrisEnvironment.current.tapTableName, n), id => RunLineageIO.read(id))

    /** [[hitsFor]] with the tap reader and the run-lineage reader (by run id)
      * given. `writers == None`: `hits` itself, neither function called.
      * Both are memoised per call. A tap read that throws counts as a hidden
      * tap, and a run read that throws as no record (both fail closed: the
      * hit loses its tap stamp columns instead of the search failing). */
    def hitsFor(
        request: jakarta.servlet.http.HttpServletRequest,
        hits: java.util.List[java.util.Map[String, Any]],
        writers: Option[Set[String]],
        readTap: String => ai.datris.model.TapConfig,
        run: String => Option[ai.datris.model.RunLineage]
    ): java.util.List[java.util.Map[String, Any]] =
        writers match {
            case None => hits
            case Some(w) =>
                val survivors = VectorChunkWriter.keepWrittenBy(hits, w)
                val hiddenMemo = scala.collection.mutable.Map[String, Boolean]()
                val runMemo = scala.collection.mutable.Map[String, Option[ai.datris.model.RunLineage]]()
                ai.datris.auth.HiddenTapScrub.scrubHits(
                    survivors,
                    n =>
                        hiddenMemo.getOrElseUpdate(
                            n,
                            try ai.datris.auth.HiddenTapScrub.tapHidden(request, n, readTap)
                            catch {
                                case e: Exception =>
                                    companionLogger.warn("Search: tap '" + n + "' could not be read, treating it as hidden: " + e.getMessage)
                                    true
                            }
                        ),
                    id =>
                        runMemo.getOrElseUpdate(
                            id,
                            try run(id)
                            catch {
                                case e: Exception =>
                                    companionLogger.warn("Search: run record '" + id + "' could not be read, treating it as not found: " + e.getMessage)
                                    None
                            }
                        )
                )
        }

    /** The `search:vector:collection=` check (plans/stories/api-key-inert-scopes.md,
      * Fix 2): Some(403) when the request's key holds `search:vector` only
      * under `collection=` scopes and none names this container
      * ([[ai.datris.auth.CapabilityScopes.collectionName]]: pgvector outside
      * `public` is `<schema>.<table>`). Runs before any config, store or
      * secret lookup, so the answer is the same whether or not the container
      * exists. None for unscoped keys, sessions, legacy keys, a request
      * with no identity, and a key with no applied `search:vector` (the gate
      * decided: inert-only under `DATRIS_KEY_INERT_SCOPES=allow`, or log-only). */
    def collectionScopeRefusal(
        request: jakarta.servlet.http.HttpServletRequest,
        store: String,
        container: String,
        schema: Option[String]
    ): Option[ResponseEntity[String]] =
        try {
            ai.datris.auth.CapabilityCheck.assertCollectionScope(
                request,
                "search",
                "vector",
                Seq(ai.datris.auth.CapabilityScopes.collectionNameOpt(store, container, schema))
            )
            None
        } catch {
            case e: ai.datris.auth.CapabilityDeniedException => Some(PipelineAPIController.capabilityDenied(e))
        }

    def containerNotFound(param: String, container: String): ResponseEntity[String] =
        ResponseEntity
            .status(HttpStatus.NOT_FOUND)
            .body[String](ApiErrors.errorBody(param.capitalize + ": " + container + " not found"))
}

@RestController
@RequestMapping(Array("/api/v1"))
class SearchAPIController {
    private val logger: Logger = LoggerFactory.getLogger(classOf[SearchAPIController])

    /** One endpoint for all vector stores. The store key in the URL resolves a
      * VectorSearchUtility through the registry, so adding a store means adding a
      * registry entry — not a controller method. Request/response shapes per store
      * are unchanged (weaviate keeps "className", pgvector keeps "table"/"schema";
      * client-supplied secret names remain ignored in favor of the server's).
      */
    @PostMapping(path = Array("/search/{store}"), consumes = Array(MediaType.APPLICATION_JSON_VALUE), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def search(
        @RequestHeader(name = "x-api-key", required = false) apiKey: String,
        @PathVariable store: String,
        @RequestBody body: java.util.Map[String, Any],
        request: jakarta.servlet.http.HttpServletRequest
    ): ResponseEntity[String] = {
        try {
            logger.info("API endpoint POST /search/" + store + " called")
            APIKeyValidator.validate(apiKey)

            val util = VectorSearchRegistry.forStore(store)
                .getOrElse(throw new DatrisException("Unknown vector store: '" + store + "'. Valid stores: " + VectorSearchRegistry.storeKeys.mkString(", ")))

            val query = requireString(body, "query")
            val container = optString(body, util.containerParam, util.containerDefault)
            // A restricted user searches only containers fed by a pipeline in
            // its catalog grants; any other container is as if it did not exist.
            val schema = if (store == "pgvector") Option(body.get("schema")).map(_.toString) else None
            // A key scoped to collections searches only those (checked before
            // any lookup, so a refusal says nothing about what exists).
            SearchAPIController.collectionScopeRefusal(request, store, container, schema).foreach(r => return r)
            // A restricted user also gets only chunks written by pipelines it
            // can read (a container no config names can hold other catalogs' chunks).
            val writers = SearchAPIController.searchWriters(
                request,
                store,
                container,
                schema,
                () => PipelineConfigIO.readAll(DatrisEnvironment.current.pipelineTableName)
            ) match {
                case Left(notFound) => return notFound
                case Right(w) => w
            }
            // Always use the server's secret names — the client may have stale/incorrect
            // names, and in multi-tenant mode this enforces tenant isolation.
            val embeddingSecretName = DatrisEnvironment.current.embeddingSecretName
            val secretName = util.tenantSecretName
            val topK = optInt(body, "topK", 5)

            val results = SearchAPIController.hitsFor(
                request,
                util.searchStore(query, container, embeddingSecretName, secretName, topK, body),
                writers,
                n => TapConfigIO.read(DatrisEnvironment.current.tapTableName, n),
                id => RunLineageIO.read(id)
            )
            buildResponse(results)
        } catch {
            case e: DatrisException =>
                // User-actionable platform errors (e.g. dim mismatch from the
                // pre-flight check). Return the message cleanly with 400 so
                // the UI surfaces "fix this" instead of a JVM stack trace.
                logger.warn("Search rejected: " + e.getMessage)
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body[String](e.getMessage)
            case e: Exception if dimensionMismatchMessage(e).isDefined =>
                // Stores other than pgvector don't have a pre-flight dim check
                // yet; if their underlying client throws something that looks
                // like a dim error, surface it cleanly too.
                val storeMsg = dimensionMismatchMessage(e).get
                val friendly = "Vector dimension mismatch from store: " + storeMsg +
                    ". This usually means the embedding provider was changed between ingest and query. " +
                    "Switch the embedding provider in Configuration to match the collection's dim, or re-ingest under the current provider."
                logger.warn("Search rejected (vector store dim error): " + storeMsg)
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body[String](friendly)
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ApiErrors.internal(e)
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

    /** Walk the cause chain looking for a vector-dimension-mismatch signature.
      * Different stores phrase it differently — pgvector's exact string is
      * "different vector dimensions"; Qdrant/Weaviate/Milvus/Chroma all mention
      * "dimension" in their dim-error messages. The pre-flight check in
      * PGVectorSearchUtil will short-circuit pgvector before we get here, so
      * this is mostly a safety net for the other four stores. */
    private def dimensionMismatchMessage(e: Throwable): Option[String] = {
        var cur: Throwable = e
        while (cur != null) {
            val msg = Option(cur.getMessage).getOrElse("")
            val lower = msg.toLowerCase
            if (
                lower.contains("different vector dimensions") ||
                lower.contains("vector dimension") ||
                (lower.contains("dimension") && (lower.contains("mismatch") || lower.contains("does not match")))
            ) {
                return Some(msg)
            }
            cur = cur.getCause
        }
        None
    }

}
