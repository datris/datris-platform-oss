package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

/** Uniform dispatch surface for the vector-store search utilities. The five
  * store objects extend this; SearchAPIController resolves the store from the
  * URL through [[VectorSearchRegistry]] instead of one endpoint per store —
  * a sixth store touches only the registry map and its own utility object.
  */
trait VectorSearchUtility {

    /** Store key as it appears in the URL: "qdrant", "weaviate", … */
    def storeType: String

    /** Request-body field naming the container to search, and its default. */
    def containerParam: String = "collection"
    def containerDefault: String = "documents"

    /** Server-side secret name for this store (tenant-resolved). Client-supplied
      * secret names are deliberately ignored — see SearchAPIController.
      */
    def tenantSecretName: String

    /** Run the search. `requestBody` carries any store-specific extras
      * (e.g. pgvector's "schema").
      */
    def searchStore(
        query: String,
        container: String,
        embeddingSecretName: String,
        secretName: String,
        topK: Int,
        requestBody: java.util.Map[String, Any]
    ): java.util.List[java.util.Map[String, Any]]
}

object VectorSearchRegistry {
    private val utilities: Map[String, VectorSearchUtility] =
        List(QdrantSearchUtil, WeaviateSearchUtil, MilvusSearchUtil, ChromaSearchUtil, PGVectorSearchUtil)
            .map(u => u.storeType -> u)
            .toMap

    def forStore(store: String): Option[VectorSearchUtility] = utilities.get(store)

    def storeKeys: List[String] = utilities.keys.toList.sorted

    /** The container (collection / class / table) a pipeline's `store`
      * vector destination writes to, if it has one. */
    def containerOf(store: String, config: ai.datris.model.PipelineConfig): Option[String] = {
        val d = Option(config).flatMap(c => Option(c.destination)).orNull
        if (d == null) return None
        store match {
            case "qdrant" => Option(d.qdrant).flatMap(q => Option(q.collectionName))
            case "weaviate" => Option(d.weaviate).flatMap(w => Option(w.className))
            case "pgvector" => Option(d.pgvector).flatMap(p => Option(p.tableName))
            case "milvus" => Option(d.milvus).flatMap(m => Option(m.collectionName))
            case "chroma" => Option(d.chroma).flatMap(c => Option(c.collectionName))
            case _ => None
        }
    }

    /** Pipelines (from `configs`) whose `store` destination targets
      * `container` (`schema`: pgvector only; None or blank = "public").
      * Identity per [[VectorContainers.key]]: weaviate class names compare
      * case-insensitively, pgvector by schema + table, the others exactly.
      * Pure; see [[findPipelinesForContainer]] for the stored-config version. */
    def pipelinesForContainer(
        store: String,
        container: String,
        configs: Seq[ai.datris.model.PipelineConfig],
        schema: Option[String] = None
    ): Seq[ai.datris.model.PipelineConfig] =
        VectorContainers.key(store, container, schema) match {
            case Some(k) => VectorContainers.namers(k, configs)
            case None => Nil
        }

    /** The stored pipelines whose `store` vector destination targets
      * `container`. Shared by the Search tab and the `search_*` MCP tools
      * (both reach `POST /search/{store}`) to resolve a container back to the
      * pipelines (and so the catalogs) that own it. */
    def findPipelinesForContainer(store: String, container: String, schema: Option[String] = None): Seq[ai.datris.model.PipelineConfig] =
        pipelinesForContainer(
            store,
            container,
            PipelineConfigIO.readAll(ai.datris.model.DatrisEnvironment.current.pipelineTableName).filter(_ != null),
            schema
        )
}
