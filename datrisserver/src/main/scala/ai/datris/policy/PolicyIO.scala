package ai.datris.policy

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.DatrisEnvironment
import ai.datris.util.NoSQLDbUtil
import org.slf4j.LoggerFactory

import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

/** The one policy document per environment, `{env}-agent-policy` / key
  * `default`. Read on every gated request through a short cache so a policy
  * edit applies within seconds without a restart. */
object PolicyIO {

    private val logger = LoggerFactory.getLogger(getClass)
    private val DocKey = "default"
    private val CacheMs = 5000L

    private case class Cached(table: String, policy: AgentPolicy, at: Long)
    private val cache = new AtomicReference[Cached](null)

    def enabled: Boolean = {
        val v = DatrisEnvironment.values
        v != null && v.useAgentPolicy
    }

    def current: AgentPolicy = {
        val table = DatrisEnvironment.current.agentPolicyTableName
        val now = System.currentTimeMillis()
        val c = cache.get()
        if (c != null && c.table == table && now - c.at < CacheMs) return c.policy
        val policy = read(table)
        cache.set(Cached(table, policy, now))
        policy
    }

    private def read(table: String): AgentPolicy =
        try {
            NoSQLDbUtil.getItemJSON(table, "key", DocKey, "value") match {
                case Some(json) =>
                    AgentPolicy.fromJson(json) match {
                        case Right(p) => p
                        case Left(err) =>
                            // A stored policy that no longer validates (an action
                            // key retired from CapabilityRoutes, say) must not
                            // silently become "auto everything". Fail closed on
                            // the parts that still parse would be ideal, but the
                            // parser is all-or-nothing; log loudly and keep the
                            // last good cached value if there is one.
                            logger.error("Stored agent policy is invalid (" + err + "); keeping the last good policy in memory")
                            Option(cache.get()).map(_.policy).getOrElse(AgentPolicy.Empty)
                    }
                case None => AgentPolicy.Empty
            }
        } catch {
            case e: Exception =>
                logger.warn("Could not read agent policy: " + e.getMessage)
                Option(cache.get()).map(_.policy).getOrElse(AgentPolicy.Empty)
        }

    def write(policy: AgentPolicy, by: String): AgentPolicy = {
        val prev = current
        val next = policy.copy(version = prev.version + 1, updatedAt = Some(Instant.now().toString), updatedBy = Some(by))
        NoSQLDbUtil.putItemJSON(DatrisEnvironment.current.agentPolicyTableName, "key", DocKey, "value", next.toJson.toString)
        cache.set(null)
        next
    }

    def invalidate(): Unit = cache.set(null)
}
