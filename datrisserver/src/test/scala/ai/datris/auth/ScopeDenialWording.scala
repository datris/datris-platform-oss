package ai.datris.auth

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{Capability, DatrisEnvironment, MongoDBConfig, ResolvedKey, TenantContext}
import com.google.gson.JsonParser
import jakarta.servlet.http.HttpServletRequest
import org.mockito.ArgumentMatchers.{any, anyString}
import org.mockito.Mockito.{doAnswer, mock, when}
import org.scalatest.Assertions._

import scala.collection.JavaConverters._
import scala.collection.mutable

/** The one place the scope-refusal wording is pinned
  * (plans/stories/scoped-key-refusal-hides-scope.md). Later stories on the
  * same branch (api-key-inert-scopes, scoped-key-read-routes,
  * field-protection-key-scope) assert their refusals with these helpers so
  * every 403 a scoped key gets reads the same.
  *
  * Wording, after the unchanged prefix through "for the targeted resource":
  *   - `. The key's <resource> capabilities are: <raw, raw, ...>. The target is outside that scope.`
  *   - `. The key holds no <resource> capability.` when the key holds nothing for the resource.
  * `<raw, ...>` is the caller's own capability strings whose resource is
  * `<resource>` or `*`, distinct, sorted, first 8 then `, ...`. Never the
  * target's catalog, owner, `_type` or any other label.
  *
  * The 403 body keeps exactly the keys `error` ("capability denied"),
  * `errorKind` ("capability_denied") and `message`. */
object ScopeDenialWording {

    val BodyKeys: Set[String] = Set("error", "errorKind", "message")
    val Error = "capability denied"
    val ErrorKind = "capability_denied"
    val MaxListed = 8

    /** Today's text up to and including "for the targeted resource" (unchanged). */
    def prefix(label: String, resource: String, action: String): String =
        "capability denied: key '" + label + "' does not hold capability '" + resource + ":" + action + "' for the targeted resource"

    /** The caller's own capability strings that the message lists for `resource`. */
    def ownCapabilities(keyCaps: Seq[String], resource: String): Seq[String] =
        Capability.parseList(keyCaps).filter(c => c.resource == resource || c.resource == "*").map(_.raw).distinct.sorted

    /** The new `message` for a key holding `keyCaps`. */
    def message(label: String, resource: String, action: String, keyCaps: Seq[String]): String = {
        val own = ownCapabilities(keyCaps, resource)
        if (own.isEmpty) prefix(label, resource, action) + ". The key holds no " + resource + " capability."
        else {
            val listed = own.take(MaxListed).mkString(", ") + (if (own.size > MaxListed) ", ..." else "")
            prefix(label, resource, action) + ". The key's " + resource + " capabilities are: " + listed + ". The target is outside that scope."
        }
    }

    /** Today's full text, kept as the exception's `detail` (server log and audit log only). */
    def detail(label: String, resource: String, action: String, context: Map[String, String]): String = {
        val scope =
            if (context.isEmpty) "—"
            else context.toSeq.sortBy(_._1).map { case (k, v) => k + "=" + v }.mkString(", ")
        prefix(label, resource, action) + " (scope: " + scope + ")"
    }

    /** A scope-refusal 403 body: exactly [[BodyKeys]], the unchanged `error`
      * and `errorKind`, `message` == `expectedMessage`, and none of `hidden`
      * (the target's catalog, owner, `_type`, ...) nor `(scope:` anywhere. */
    def assertBody(body: String, expectedMessage: String, hidden: Seq[String]): Unit = {
        assert(body != null, "no body")
        val o = JsonParser.parseString(body).getAsJsonObject
        assert(o.keySet().asScala.toSet == BodyKeys, body)
        assert(o.get("error").getAsString == Error, body)
        assert(o.get("errorKind").getAsString == ErrorKind, body)
        assert(o.get("message").getAsString == expectedMessage, body)
        assertHides(body, hidden)
    }

    /** `text` names none of `hidden` and carries no `(scope:` clause. */
    def assertHides(text: String, hidden: Seq[String]): Unit = {
        assert(!text.contains("(scope:"), text)
        hidden.foreach(h => assert(!text.contains(h), "'" + h + "' disclosed: " + text))
    }
}

/** Request and environment fixtures shared by the scope-denial specs. */
object ScopeDenialFixtures {

    /** A mocked request backed by a real attribute map. */
    def request(rk: ResolvedKey, method: String = "GET", uri: String = "/api/v1/pipeline"): (HttpServletRequest, mutable.Map[String, AnyRef]) = {
        val m = mutable.Map[String, AnyRef]()
        if (rk != null) m.put(ai.datris.config.TenantInterceptor.ResolvedKeyAttr, rk)
        val r = mock(classOf[HttpServletRequest])
        when(r.getMethod).thenReturn(method)
        when(r.getRequestURI).thenReturn(uri)
        when(r.getRemoteAddr).thenReturn("127.0.0.1")
        when(r.getAttribute(anyString())).thenAnswer(i => m.getOrElse(i.getArgument[String](0), null))
        doAnswer { i => m.put(i.getArgument[String](0), i.getArgument[AnyRef](1)); null }.when(r).setAttribute(anyString(), any())
        doAnswer { i => m.remove(i.getArgument[String](0)); null }.when(r).removeAttribute(anyString())
        (r, m)
    }

    def key(label: String, caps: String*): ResolvedKey =
        ResolvedKey(None, label, Capability.parseList(caps), isLegacyFullAccess = false)

    /** An environment with the audit log on (no Mongo is touched while a
      * test sink is installed). */
    def auditEnv(logReads: Boolean = false): DatrisEnvironment = DatrisEnvironment(
        initialized = true,
        environment = "test",
        fileNotifierQueue = null,
        ttlFileNotifierQueueMessages = 0,
        pipelineTopic = null,
        pipelineTableName = null,
        archivedMetadataTableName = null,
        pipelineStatusTableName = null,
        fileNotifierMessageTableName = null,
        dataPullTableName = null,
        useApiKeys = true,
        apiKeysSecretName = null,
        postgresSecretName = null,
        mongoDbSecretName = null,
        kafkaProducerSecretName = null,
        kafkaConsumerConfig = null,
        mongoDbConfig = MongoDBConfig("mongodb://unused", "datris", "datris"),
        minIOConfig = null,
        activeMQConfig = null,
        aiConfig = null,
        aiEnabled = false,
        embeddingSecretName = null,
        qdrantSecretName = null,
        weaviateSecretName = null,
        milvusSecretName = null,
        chromaSecretName = null,
        pgvectorSecretName = null,
        multiTenant = false,
        useAuditLog = true,
        auditLogTableName = "test-audit-log",
        auditLogEmitLogLine = false,
        auditLogLogReads = logReads,
        auditLogStrict = false
    )

    def install(e: DatrisEnvironment): Unit = {
        DatrisEnvironment.values = e
        if (e == null) TenantContext.clear() else TenantContext.set(e)
    }
}

/** The route-gate refusal for a stored capability whose scope the platform
  * does not apply (plans/stories/api-key-inert-scopes.md, Fix 3). It is the
  * CapabilityInterceptor's capability-denied body (key set unchanged), with a
  * `message` that names the inert capability and why. The exact wording is
  * left to the implementation; these helpers pin what it must carry. */
object InertScopeDenial {

    import scala.collection.JavaConverters._

    /** The interceptor's capability-denied body keys, as today. */
    val BodyKeys: Set[String] = Set("error", "errorKind", "key", "required", "route", "message")

    /** `text` names `inertRaw` and, outside that string, its scope key. */
    def assertNames(text: String, inertRaw: String, scopeKey: String): Unit = {
        assert(text != null && text.contains(inertRaw), "does not name '" + inertRaw + "': " + text)
        assert(text.replace(inertRaw, "").contains(scopeKey), "does not name the scope '" + scopeKey + "': " + text)
    }

    def assertBody(body: String, label: String, required: String, route: String, inertRaw: String, scopeKey: String): Unit = {
        assert(body != null && body.nonEmpty, "no body")
        val o = JsonParser.parseString(body).getAsJsonObject
        assert(o.keySet().asScala.toSet == BodyKeys, body)
        assert(o.get("error").getAsString == ScopeDenialWording.Error, body)
        assert(o.get("errorKind").getAsString == ScopeDenialWording.ErrorKind, body)
        assert(o.get("key").getAsString == label, body)
        assert(o.get("required").getAsString == required, body)
        assert(o.get("route").getAsString == route, body)
        assertNames(o.get("message").getAsString, inertRaw, scopeKey)
    }
}

/** The refusal of a route or request that covers the whole installation, for
  * a caller limited to a catalog or to what it created
  * (plans/stories/scoped-key-read-routes.md, Part C, lead rulings 5 and 6).
  * One text for every such refusal; it names nothing from the request. */
object InstallWideDenial {

    import scala.collection.JavaConverters._

    /** The exact message, pinned here once. */
    val Message: String =
        "capability denied: this request covers the whole installation and is not available to an API key or user " +
            "limited to a catalog or to what it created"

    /** The interceptor's six-key body for `label` on `required` at `route`. */
    def assertGateBody(body: String, label: String, required: String, route: String): Unit = {
        assert(body != null && body.nonEmpty, "no body")
        val o = JsonParser.parseString(body).getAsJsonObject
        assert(o.keySet().asScala.toSet == InertScopeDenial.BodyKeys, body)
        assert(o.get("error").getAsString == ScopeDenialWording.Error, body)
        assert(o.get("errorKind").getAsString == ScopeDenialWording.ErrorKind, body)
        assert(o.get("key").getAsString == label, body)
        assert(o.get("required").getAsString == required, body)
        assert(o.get("route").getAsString == route, body)
        assert(o.get("message").getAsString == Message, body)
    }

    /** A controller's three-key capability-denied body. */
    def assertControllerBody(body: String, hidden: Seq[String]): Unit = ScopeDenialWording.assertBody(body, Message, hidden)
}
