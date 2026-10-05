package ai.datris.model

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

/** A request was rejected because its input failed validation. */
class ValidationException(message: String) extends DatrisException(message)
