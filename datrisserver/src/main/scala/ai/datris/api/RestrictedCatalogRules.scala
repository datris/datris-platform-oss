package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.auth.{CapabilityCheck, CatalogGrants}
import ai.datris.model.DatrisException
import ai.datris.util.CatalogOps
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.{HttpStatus, ResponseEntity}

/** Catalog rules a restricted user's session is held to when it saves a
  * pipeline or tap (create, update, `set_catalog` move). Everyone else is
  * untouched: API keys keep today's behaviour. Shared by
  * `PipelineAPIController` and `TapAPIController`. */
object RestrictedCatalogRules {

    private def blank(c: String): Boolean = c == null || c.trim.isEmpty

    def badRequest(msg: String): ResponseEntity[String] =
        ResponseEntity.status(HttpStatus.BAD_REQUEST).body[String](QueryAPIController.errorBody(new DatrisException(msg)))

    /** 404 for a catalog outside the caller's grants (same as a missing one). */
    def catalogNotFound(catalog: String): ResponseEntity[String] =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body[String](QueryAPIController.errorBody(new DatrisException("Catalog '" + catalog + "' not found")))

    /** For a restricted user's session only: refusal for a catalog-level
      * operation on `catalog` needing `action` on its pipelines/taps. 404 when
      * the catalog is outside the grants (same body as a missing catalog),
      * 403 when it is visible but `action` is not granted. None for everyone
      * else, so API keys keep today's behaviour. */
    def catalogRefusal(request: HttpServletRequest, catalog: String, action: String): Option[ResponseEntity[String]] =
        CapabilityCheck.resolvedKey(request) match {
            case Some(rk) if rk.restrictedSession =>
                val ctx = Map("catalog" -> catalog)
                if (catalog == null || !rk.grants("pipeline", "read", ctx)) Some(catalogNotFound(catalog))
                else if (!rk.grants("pipeline", action, ctx))
                    Some(PipelineAPIController.capabilityDenied(new DatrisException(
                        "capability denied: your grant on catalog " + catalog + " does not allow this"
                    )))
                else None
            case _ => None
        }

    /** Right(None) = leave the catalog as sent; Right(Some(c)) = save under
      * `c`; Left = refusal. `notFound` is the resource's own 404 body, used
      * when an existing item of that name is outside the grants. */
    def catalogForSave(
        request: HttpServletRequest,
        resource: String,
        targetCatalog: String,
        existingPresent: Boolean,
        existingCatalog: String,
        existingOwner: String,
        notFound: => ResponseEntity[String]
    ): Either[ResponseEntity[String], Option[String]] = {
        val rk = CapabilityCheck.resolvedKey(request) match {
            case Some(k) if k.restrictedSession => k
            case _ => return Right(None)
        }
        def canCreateIn(c: String): Boolean = rk.grants(resource, "create", Map("catalog" -> c))

        if (existingPresent) {
            val src = CatalogOps.scopeContext(existingCatalog, existingOwner)
            if (!rk.grants(resource, "read", src)) return Left(notFound)
            if (!rk.grants(resource, "update", src))
                return Left(PipelineAPIController.capabilityDenied(new DatrisException(
                    "capability denied: you can view catalog " + existingCatalog + " but not edit it"
                )))
            if (blank(targetCatalog))
                return Left(badRequest("Uncataloged is not available to you. Choose one of your catalogs."))
            if (targetCatalog != existingCatalog && !canCreateIn(targetCatalog)) return Left(catalogNotFound(targetCatalog))
            Right(Some(targetCatalog))
        } else if (blank(targetCatalog)) {
            CatalogGrants.defaultCatalogFor(rk) match {
                case Left(text) => Left(badRequest(text))
                case Right(c) => Right(c)
            }
        } else if (canCreateIn(targetCatalog)) Right(Some(targetCatalog))
        else Left(catalogNotFound(targetCatalog))
    }
}
