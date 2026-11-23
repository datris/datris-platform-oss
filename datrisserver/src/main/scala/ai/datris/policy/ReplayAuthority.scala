package ai.datris.policy

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.config.PolicyInterceptor
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory

/** Is this request the replay of an approved agent action?
  *
  * Answers [[ReplayAuthority.Replay]] only when the agent policy is on, the
  * request carries `X-Datris-Approval: <id>.<token>`, the stored action is
  * approved and holds the hash of that unused token
  * ([[PolicyInterceptor.replayable]]), and the request is that action
  * ([[PolicyInterceptor.replayMatches]]: method, path and resource name).
  * A store read that fails is [[ReplayAuthority.Unchecked]]: whether the
  * request is an approved action cannot be told, so nothing may run on it.
  * Anything else is [[ReplayAuthority.NotReplay]]: the request is then
  * treated as if it carried no approval header.
  *
  * Never consumes the token: PolicyInterceptor uses it, once, further down
  * the chain. TenantInterceptor asks this before deciding the identity a
  * replay runs with, so the presence of the header alone grants nothing. */
object ReplayAuthority {

    private val logger = LoggerFactory.getLogger(getClass)

    sealed trait Answer
    final case class Replay(pa: PendingAction) extends Answer
    case object NotReplay extends Answer

    /** The approval store could not be read: neither a replay nor not one. */
    case object Unchecked extends Answer

    /** `<id>.<token>`; the token is empty when there is no dot. */
    private[datris] def split(header: String): (String, String) = {
        val dot = header.indexOf('.')
        if (dot > 0) (header.substring(0, dot), header.substring(dot + 1)) else (header, "")
    }

    def verify(request: HttpServletRequest, store: PolicyInterceptor.Store): Answer = {
        if (request == null || !PolicyIO.enabled) return NotReplay
        val header = Option(request.getHeader(PolicyReplay.HeaderApproval)).map(_.trim).filter(_.nonEmpty).getOrElse(return NotReplay)
        val (id, token) = split(header)
        if (id.isEmpty || token.isEmpty) return NotReplay
        try
            store.get(id) match {
                case Some(pa) if pa.id == id && PolicyInterceptor.replayable(pa, token) && PolicyInterceptor.replayMatches(request, pa) => Replay(pa)
                case _ => NotReplay
            }
        catch {
            case e: Exception =>
                logger.warn("approval replay check failed: " + ai.datris.util.LogSafe.text(String.valueOf(e.getMessage)))
                Unchecked
        }
    }
}
