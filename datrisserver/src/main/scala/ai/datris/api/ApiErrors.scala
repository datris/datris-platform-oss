package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.config.CapabilityInterceptor
import ai.datris.model.{DatrisException, ValidationException}
import ai.datris.util.APIKeyValidator
import com.google.gson.JsonObject
import org.springframework.http.{HttpStatus, MediaType, ResponseEntity}

/** Uniform error responses for controller catch-all blocks.
  *
  * Controllers used to answer `500` with the full Java stack trace as the
  * body. That leaked class names, file paths and internal messages to any
  * caller, and turned an API-key rejection raised inside the controller
  * (Skip-list routes the capability interceptor does not gate) into a 500.
  * [[internal]] answers key rejections with the same 401/503 JSON the
  * interceptor uses, a [[ValidationException]] (an invalid config the caller
  * must fix) with `400 {"error": <message>}`, and everything else with
  * `500 {"error": <message>}`,
  * first line only. The stack trace belongs in the server log, which every
  * call site already writes before calling this. */
object ApiErrors {

    val AuthenticationRequiredBody: String = "{\"error\":\"Authentication required\"}"

    def errorBody(message: String): String = {
        val o = new JsonObject()
        o.addProperty("error", message)
        o.toString
    }

    /** The message with any embedded stack trace cut off: first line only,
      * or the exception class name when there is no message. Run status
      * events no longer embed traces (ErrorText.messageChain; the trace is in
      * the event's `detail`), but the exceptions StreamNotifier rethrows to
      * the upload API still do, as may older or third-party messages. */
    def firstLine(e: Throwable): String = {
        val m = Option(e.getMessage).map(_.trim).filter(_.nonEmpty).getOrElse(e.getClass.getSimpleName)
        m.split("\\r?\\n", 2)(0).trim
    }

    /** Pure classification: (status, body) for an exception caught by a
      * controller. */
    def classify(e: Throwable): (Int, String) = e match {
        case d: DatrisException if APIKeyValidator.isKeyRejection(d) =>
            if (d.getMessage == APIKeyValidator.MissingKeyMessage)
                (HttpStatus.UNAUTHORIZED.value, AuthenticationRequiredBody)
            else CapabilityInterceptor.rejectionResponse(d.getMessage)
        case v: ValidationException =>
            (HttpStatus.BAD_REQUEST.value, errorBody(firstLine(v)))
        case _ =>
            (HttpStatus.INTERNAL_SERVER_ERROR.value, errorBody(firstLine(e)))
    }

    def internal(e: Throwable): ResponseEntity[String] = {
        val (status, body) = classify(e)
        ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body[String](body)
    }
}
