package ai.datris.config

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.audit.AuditActorInfo
import ai.datris.auth.RoleToolSets
import ai.datris.model.{Capability, ResolvedKey, User}
import ai.datris.policy.{PendingAction, PolicyGate}
import ai.datris.util.InMemoryUserDb.grants
import ai.datris.util.TokenHash
import jakarta.servlet.http.{HttpServletRequest, HttpServletResponse}
import org.mockito.ArgumentMatchers.{any, anyInt, anyString}
import org.mockito.Mockito.{doAnswer, mock, when}
import org.springframework.web.method.HandlerMethod

import java.io.{PrintWriter, StringWriter}
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.temporal.ChronoUnit
import scala.collection.mutable

/** Fixtures for plans/stories/approval-replay-requester-scope.md specs.
  *
  * Requesting keys are resolved by label through the story's
  * `APIKeyValidator.resolveByLabelOverride` seam (set by each spec), so no
  * secret store is read. The carrier `ui` key resolves through the key
  * resolution cache ([[ChatAuthorityFixtures.seedKey]]). Pending actions
  * live in [[MemoryStore]] (a `PolicyInterceptor.Store`) or, end to end, in
  * the fake MongoDB collection behind `PendingActionIO`. */
object ReplayScopeFixtures {

    // ------------------------------------------------------------ users

    /** A restricted editor who owns the Sales catalog (may delete there). */
    val olga: User = User("olga", "h", User.RoleEditor, null, null, null, restricted = true, catalogGrants = grants("Sales" -> "own"))

    val users: Seq[User] = ChatAuthorityFixtures.allUsers :+ olga

    // ------------------------------------------------------------ requesting keys

    val SalesDeleteCaps: Seq[String] = Seq("pipeline:read:catalog=Sales", "pipeline:delete:catalog=Sales")

    /** `pipeline:delete:catalog=Sales` (and read there). */
    val salesDeleter: ResolvedKey =
        ResolvedKey(None, "sales-deleter", Capability.parseList(SalesDeleteCaps), isLegacyFullAccess = false, keyId = Some("k_sales"))

    /** Unscoped pipeline read/delete/create. */
    val opsAgent: ResolvedKey =
        ResolvedKey(
            None,
            "ops-agent",
            Capability.parseList(Seq("pipeline:read", "pipeline:delete", "pipeline:create")),
            isLegacyFullAccess = false,
            keyId = Some("k_ops")
        )

    /** A key with no metadata (legacy full access, no key id). */
    val oldAgent: ResolvedKey = ResolvedKey(None, "old-agent", Seq(Capability.FullAccess), isLegacyFullAccess = true)

    /** `owner=self` key: creates pipelines and reads only what it created. */
    val ragBuilder: ResolvedKey =
        ResolvedKey(None, "rag-builder", Capability.parseList(RoleToolSets.OwnerSelfCaps), isLegacyFullAccess = false, keyId = Some("k_rag"))

    /** A key that may update taps (tap-prompt fragments are `tap:update`). */
    val promptAgent: ResolvedKey =
        ResolvedKey(None, "prompt-agent", Capability.parseList(Seq("tap:read", "tap:update")), isLegacyFullAccess = false, keyId = Some("k_prompt"))

    /** The requesters' current keys, as `resolveByLabel` would answer them. */
    val liveKeys: Map[String, ResolvedKey] = Seq(salesDeleter, opsAgent, oldAgent, ragBuilder, promptAgent).map(k => k.label -> k).toMap

    // ------------------------------------------------------------ actors as stored on the pending action

    def keyActor(rk: ResolvedKey): AuditActorInfo =
        AuditActorInfo("api-key", rk.label, keyLabel = Some(rk.label), keyId = rk.keyId, legacyFullAccess = rk.isLegacyFullAccess)

    /** An in-platform chat (the `ui` key on behalf of `u`), as AuditActor records it. */
    def chatActor(u: User): AuditActorInfo =
        AuditActorInfo(
            "assistant",
            "session:" + u.username,
            keyLabel = Some("ui"),
            username = Some(u.username),
            role = Some(u.role),
            legacyFullAccess = true
        )

    // ------------------------------------------------------------ pending actions

    val DeletePath = "/api/v1/pipeline"
    val CreatePath = "/api/v1/pipeline"

    def deleteQuery(pipeline: String): String = "pipeline=" + pipeline + "&deleteData=true&deleteConfig=true"

    /** An approved action holding the hash of `token`, not yet used. */
    def approved(
        id: String,
        actor: AuditActorInfo,
        token: String,
        method: String = "DELETE",
        path: String = DeletePath,
        query: Option[String] = None,
        body: Option[String] = None,
        resourceName: Option[String] = None,
        resourceType: String = "pipeline",
        action: String = "pipeline:delete",
        hashVersion: Option[Int] = Some(PendingAction.CurrentHashVersion),
        approver: String = "ed"
    ): PendingAction = {
        val now = Instant.now()
        PendingAction(
            id = id,
            action = action,
            resourceType = Some(resourceType),
            resourceName = resourceName,
            resourceVersion = None,
            actor = actor,
            reason = Some("cleanup"),
            agentSession = None,
            method = method,
            path = path,
            query = query,
            contentType = body.map(_ => "application/json"),
            body = body,
            bodyHash = "h-" + id,
            createdAt = now.minus(5, ChronoUnit.MINUTES),
            expiresAt = now.plus(1, ChronoUnit.HOURS),
            state = PendingAction.Approved,
            decidedBy = Some(approver),
            decidedAt = Some(now),
            replayTokenHash = Some(TokenHash.sha256Hex(token)),
            hashVersion = hashVersion
        )
    }

    /** In-memory pending-action store. Counts the consuming calls. */
    class MemoryStore extends PolicyInterceptor.Store {
        val docs: mutable.LinkedHashMap[String, PendingAction] = mutable.LinkedHashMap()
        val consumed: mutable.Buffer[String] = mutable.Buffer()
        val inserted: mutable.Buffer[PendingAction] = mutable.Buffer()
        @volatile var failReads = false

        def put(pa: PendingAction): Unit = synchronized(docs.put(pa.id, pa))
        def get(id: String): Option[PendingAction] = synchronized {
            if (failReads) throw new IllegalStateException("store unavailable")
            docs.get(id)
        }
        def consumeReplay(id: String, token: String): Option[PendingAction] = synchronized {
            consumed += id
            docs.get(id).filter(pa => PolicyInterceptor.replayable(pa, token)).map { pa =>
                val used = pa.copy(replayConsumedAt = Some(Instant.now()), replayTokenHash = None)
                docs.put(id, used)
                used
            }
        }
        def findPendingDuplicate(hash: String, actorLabel: String): Option[PendingAction] = None
        def countPending(actorLabel: String): Long = 0L
        def listPending(actorLabel: String): List[PendingAction] = Nil
        def insert(pa: PendingAction): Unit = synchronized { inserted += pa; docs.put(pa.id, pa) }
        def currentVersion(resourceType: String, name: String): Option[Int] = None
    }

    // ------------------------------------------------------------ requests

    /** A mocked request: method, raw path, query, optional JSON body (as the
      * policy body cache holds it), headers, and a real attribute map. */
    def call(
        method: String,
        uri: String,
        query: Option[String] = None,
        body: Option[String] = None,
        headers: Seq[(String, String)] = Nil
    ): (HttpServletRequest, mutable.Map[String, AnyRef]) = {
        val (r, attrs) = ChatAuthorityFixtures.request(method, uri, headers: _*)
        when(r.getQueryString).thenReturn(query.orNull)
        val params = query.toSeq.flatMap(_.split("&")).map(_.split("=", 2)).collect { case Array(k, v) => k -> v }
        when(r.getParameter(anyString())).thenReturn(null)
        params.foreach { case (k, v) => when(r.getParameter(k)).thenReturn(v) }
        body.foreach { b =>
            val bytes = b.getBytes(StandardCharsets.UTF_8)
            attrs.put(PolicyGate.BodyAttr, bytes)
            when(r.getContentType).thenReturn("application/json")
            when(r.getContentLengthLong).thenReturn(bytes.length.toLong)
        }
        (r, attrs)
    }

    /** A response whose status the spec (or an interceptor) sets and reads back. */
    class Resp {
        val out = new StringWriter()
        @volatile var status: Int = 200
        val response: HttpServletResponse = mock(classOf[HttpServletResponse])
        when(response.getWriter).thenReturn(new PrintWriter(out, true))
        doAnswer { i => status = i.getArgument[Int](0); null }.when(response).setStatus(anyInt())
        when(response.getStatus).thenAnswer(_ => status)
        doAnswer(_ => null).when(response).setHeader(anyString(), any())
        def body: String = out.toString
    }

    /** A routed controller method, so PolicyInterceptor and AuditInterceptor treat the request as an action. */
    class StandIn { def handle(): String = "ok" }
    def handler: HandlerMethod = new HandlerMethod(new StandIn, classOf[StandIn].getMethod("handle"))
}
