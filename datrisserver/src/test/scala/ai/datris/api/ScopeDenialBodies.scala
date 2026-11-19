package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.auth.CapabilityDeniedException
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.ResponseEntity

/** Test-only bridge to the `private[api]` 403 builders, so specs outside
  * `ai.datris.api` (CapabilityScopeDenialSpec) can pin the body they send. */
object ScopeDenialBodies {

    /** `PipelineAPIController.capabilityDenied(e)`: pipelines, taps, codegen
      * script versions and version restore all answer with it. */
    def pipeline(e: Exception): ResponseEntity[String] = PipelineAPIController.capabilityDenied(e)

    /** `FieldProtectionAPIController.denied(request, e, action, null, null)`
      * (no pipeline or field named). */
    def fieldProtection(request: HttpServletRequest, e: CapabilityDeniedException, action: String): ResponseEntity[String] =
        FieldProtectionAPIController.denied(request, e, action, null, null)
}
