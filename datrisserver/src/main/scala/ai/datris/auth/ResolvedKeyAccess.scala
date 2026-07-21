package ai.datris.auth

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.config.TenantInterceptor
import ai.datris.model.ResolvedKey
import jakarta.servlet.http.HttpServletRequest

/** Reads the [[ResolvedKey]] that [[TenantInterceptor]] attached to the
  * current request. Controllers call this when persisting a new pipeline,
  * tap, or secret so the resource can be tagged with the label of the key
  * that created it — the foundation of `owner=self` capability matching. */
object ResolvedKeyAccess {

    /** The full ResolvedKey if present on the request, None otherwise. */
    def fromRequest(request: HttpServletRequest): Option[ResolvedKey] = {
        if (request == null) return None
        request.getAttribute(TenantInterceptor.ResolvedKeyAttr) match {
            case rk: ResolvedKey => Some(rk)
            case _ => None
        }
    }

    /** Just the human-readable label, suitable for storing as
      * `createdByKeyLabel` on a new resource. None when no key is present
      * (e.g., auth disabled, public endpoint) — resources created in that
      * state will not match `owner=self` capabilities, which is intended. */
    def keyLabel(request: HttpServletRequest): Option[String] =
        fromRequest(request).map(_.label)
}
