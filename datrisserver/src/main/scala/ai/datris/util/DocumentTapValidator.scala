package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
*/

import ai.datris.model.PipelineConfig

/**
 * A document tap hands raw file bytes to its target pipeline. The pipeline
 * must be shaped to accept those bytes — i.e. its source must be
 * `unstructuredAttributes` and its destination must be a vector store.
 * If either is wrong, the tap will silently pump bytes into a loader that
 * cannot process them (e.g. PDF into MongoDBLoader's JSON parser). This
 * validator centralises the shape check so save-time and run-time guards
 * agree on what "compatible" means.
 */
object DocumentTapValidator {

    /** Returns None if compatible, or Some(reason) describing the first violation. */
    def incompatibilityReason(pipeline: PipelineConfig): Option[String] = {
        if (pipeline == null) return Some("target pipeline not found")

        val src = Option(pipeline.source)
        val fileAttrs = src.flatMap(s => Option(s.fileAttributes))
        val hasUnstructuredSource = fileAttrs.exists(_.unstructuredAttributes != null)

        if (!hasUnstructuredSource)
            return Some("pipeline source must be configured as unstructured (source.fileAttributes.unstructuredAttributes). Current source is not unstructured, so raw document bytes cannot be routed through text extraction/chunking/embedding.")

        val dest = Option(pipeline.destination)
        val hasVectorDest = dest.exists { d =>
            d.qdrant != null || d.weaviate != null || d.pgvector != null || d.milvus != null || d.chroma != null
        }

        if (!hasVectorDest)
            return Some("pipeline destination must be a vector store (qdrant, weaviate, pgvector, milvus, or chroma). Document taps cannot write raw bytes to relational or object-store destinations.")

        None
    }

    def isCompatible(pipeline: PipelineConfig): Boolean = incompatibilityReason(pipeline).isEmpty
}
