package ai.datris.model

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

/** The AI provider answered but declined the request (a safety decline, not
  * a transport or configuration error). Callers with a local fallback (CSV
  * schema generation, data profiling) catch this type only; every other
  * failure keeps propagating. */
class AIRefusalException(message: String) extends DatrisException(message)
