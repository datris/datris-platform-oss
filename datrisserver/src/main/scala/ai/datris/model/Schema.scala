package ai.datris.model

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import com.fasterxml.jackson.annotation.{JsonCreator, JsonProperty}

/** One schema column. `protect` (source fields only) names a field-protection
  * policy applied by `FieldProtection` right after the preprocessor and before
  * any DataQuality / transformation stage. Absent on every pipeline that does
  * not use it: Gson reads a missing key as null, and the default keeps every
  * two-argument `SchemaField(name, type)` call site. */
case class SchemaField(
    name: String,
    `type`: String,
    protect: ProtectionPolicy = null
)

/** `{"method": "hmac" | "mask" | "redact" | "drop", "preserve": "last4" | "domain" | "year", "params": {...}}`.
  *
  * Same Jackson/Gson rule as `UnityCatalogSync` (PipelineConfig.scala):
  * Spring's `@RequestBody` Jackson mapper does not apply Scala default
  * arguments, and Gson skips constructors on config DB reads, so every
  * field is nullable and read null-safe. */
case class ProtectionPolicy @JsonCreator() (
    @JsonProperty("method") method: String = null,
    @JsonProperty("preserve") preserve: String = null,
    @JsonProperty("params") params: java.util.Map[String, String] = null
) {
    def this() = this(null, null, null)

    /** `hmac`, `mask:domain`, `mask`, ... — for status lines and lineage evidence (never a value). */
    def label: String =
        if (method != null && method.equalsIgnoreCase("mask") && preserve != null && preserve.nonEmpty) "mask:" + preserve
        else String.valueOf(method)
}

object ProtectionPolicy {
    val Methods: Set[String] = Set("hmac", "mask", "redact", "drop")
    val Reserved: Set[String] = Set("fpe", "encrypt", "tokenize")
    val Preserves: Set[String] = Set("last4", "domain", "year")
}

case class Schema(
    fields: java.util.List[SchemaField]
)
