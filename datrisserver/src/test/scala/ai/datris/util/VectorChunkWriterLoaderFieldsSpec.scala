package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model._
import org.scalatest.funsuite.AnyFunSuite

/** Static destination `metadata` cannot replace a field the vector loaders
  * write onto each chunk themselves (`id`, `text`, `chunk_index`,
  * `filename`, `embedding`), on top of the writer fields. A planted
  * `filename` would otherwise name a tap feed the chunk did not come from. */
class VectorChunkWriterLoaderFieldsSpec extends AnyFunSuite {

    private def meta(pairs: (String, String)*): java.util.Map[String, String] = {
        val m = new java.util.LinkedHashMap[String, String]()
        pairs.foreach { case (k, v) => m.put(k, v) }
        m
    }

    private val planted = meta(
        "team" -> "a",
        "filename" -> "tap-t_sales.csv",
        "Text" -> "spoofed text",
        "CHUNK_INDEX" -> "7",
        "id" -> "x",
        "embedding" -> "[0]",
        "filename_label" -> "kept",
        "source_pipeline" -> "spoof",
        "owner" -> "o"
    )

    test("staticMetadata drops the loader-written field names in any letter case, exact names only, and keeps order") {
        assert(VectorChunkWriter.LoaderFields == Seq("id", "text", "chunk_index", "filename", "embedding"))
        assert(VectorChunkWriter.ReservedKeys == VectorChunkWriter.WriterFields ++ VectorChunkWriter.LoaderFields)
        assert(VectorChunkWriter.staticMetadata(planted) == Seq("team" -> "a", "filename_label" -> "kept", "owner" -> "o"))
        assert(VectorChunkWriter.ignoredKeys(planted) == Seq("filename", "Text", "CHUNK_INDEX", "id", "embedding", "source_pipeline"))
    }

    private def ctx(destination: String): JobContext =
        JobContext(
            pipelineToken = "tok",
            metadata = PipelineMetadata("p", "tap-t_hr_feed.csv", "/tmp/x", "pub", bulkUpload = false),
            data = null,
            config = new com.google.gson.Gson().fromJson(
                s"""{"name":"vector_pipe","destination":{$destination}}""",
                classOf[PipelineConfig]
            ),
            pipelineProperties = null,
            state = null,
            thread = null,
            statusUtil = null
        )

    private val metaJson =
        """"metadata":{"team":"a","filename":"tap-t_sales.csv","text":"t","chunk_index":"7","Filename":"x","owner":"o"}"""

    test("every vector loader writes static metadata without the loader-written field names") {
        val expected = Seq("team" -> "a", "owner" -> "o")
        assert(new QdrantLoader(ctx(s""""qdrant":{"collectionName":"c",$metaJson}""")).chunkMetadata == expected, "qdrant")
        assert(new MilvusLoader(ctx(s""""milvus":{"collectionName":"c",$metaJson}""")).chunkMetadata == expected, "milvus")
        assert(new ChromaLoader(ctx(s""""chroma":{"collectionName":"c",$metaJson}""")).chunkMetadata == expected, "chroma")
        assert(new WeaviateLoader(ctx(s""""weaviate":{"className":"C",$metaJson}""")).chunkMetadata == expected, "weaviate")
    }
}
