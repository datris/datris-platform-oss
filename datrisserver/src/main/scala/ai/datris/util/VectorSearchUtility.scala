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
}
