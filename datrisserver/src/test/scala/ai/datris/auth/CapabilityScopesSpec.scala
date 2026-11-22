package ai.datris.auth

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.api.{KeyTemplates, KeysAPIController}
import ai.datris.incident.RecoveryKey
import ai.datris.model.{Capability, ResolvedKey}
import com.google.gson.JsonParser
import org.scalatest.funsuite.AnyFunSuite

import scala.collection.JavaConverters._

/** Story: API keys, a scope the server does not apply can no longer be put on
  * a key, and stored ones stop granting (plans/stories/api-key-inert-scopes.md),
  * Acceptance, `CapabilityScopesSpec`.
  *
  * Pinned seam (does not exist at d3e13515; the implementation adds it):
  * {{{
  * // ai.datris.auth.CapabilityScopes (new, pure)
  * val Vocabulary: Seq[(String, Seq[String])]          // resource -> actions (a Map works too)
  * def supported(resource: String, action: String): Set[String]  // scope keys applied (a Seq works too)
  * def issueProblem(raw: String): Option[String]        // None = may be issued
  * def partition(caps: Seq[Capability]): (Seq[Capability], Seq[Capability])  // (supported, inert)
  * def collectionName(store: String, name: String, schema: Option[String]): String
  * }}}
  * The catalogue is read through the public `GET /keys/capabilities/catalog`
  * handler (`capabilitiesCatalog()`, no secret-store access), as
  * QueryCapabilityBundlesSpec does. */
class CapabilityScopesSpec extends AnyFunSuite {

    /** The 39 actions the platform knows: the catalogue's 37 plus code-repo. */
    private val ExpectedVocabulary: Map[String, Set[String]] = Map(
        "pipeline" -> Set("read", "create", "update", "delete", "run"),
        "tap" -> Set("read", "create", "update", "delete", "run"),
        "secret" -> Set("read", "write"),
        "document" -> Set("upload"),
        "search" -> Set("vector"),
        "query" -> Set("postgres", "mongodb", "objectstore", "snowflake", "databricks", "sqlserver", "natural"),
        "job" -> Set("read", "kill"),
        "metadata" -> Set("read"),
        "config" -> Set("read", "write"),
        "mcp" -> Set("tool"),
        "audit" -> Set("read"),
        "policy" -> Set("read", "update"),
        "approval" -> Set("read", "decide"),
        "incident" -> Set("read", "abandon", "proceed"),
        "protect" -> Set("reveal", "admin"),
        "code-repo" -> Set("read", "write")
    )

    private val AllScopeKeys = Seq("catalog", "database", "collection", "destination_kind", "_type", "owner")

    private def vocabulary: Map[String, Set[String]] =
        CapabilityScopes.Vocabulary.toSeq.map { case (r, as) => r -> as.toSet }.toMap

    private def expectedSupported(resource: String, action: String): Set[String] = (resource, action) match {
        case ("pipeline" | "tap", _) => Set("catalog", "owner")
        case ("secret", _) => Set("_type")
        case ("search", "vector") | ("document", "upload") => Set("collection")
        case ("approval", "read") => Set("owner")
        case ("protect", "reveal") => Set("catalog", "owner")
        case _ => Set.empty
    }

    private def valueFor(key: String): String = key match {
        case "owner" => "self"
        case "_type" => "tap"
        case _ => "x"
    }

    private def refused(raw: String): String =
        CapabilityScopes.issueProblem(raw).getOrElse(fail("'" + raw + "' must be refused at issue"))

    test(
        "supported scopes: catalog and owner on every pipeline and tap action and on protect:reveal, _type on secret, collection on search:vector and document:upload, owner on approval:read, none elsewhere"
    ) {
        assert(vocabulary == ExpectedVocabulary, CapabilityScopes.Vocabulary)
        assert(vocabulary.values.map(_.size).sum == 39)
        for ((r, actions) <- ExpectedVocabulary; a <- actions) {
            assert(CapabilityScopes.supported(r, a).toSet == expectedSupported(r, a), s"$r:$a")
            // every known pair is grantable unscoped
            assert(CapabilityScopes.issueProblem(s"$r:$a").isEmpty, s"$r:$a unscoped must be accepted")
            // and every supported scope key is accepted on it
            expectedSupported(r, a).foreach { k =>
                val raw = s"$r:$a:$k=${valueFor(k)}"
                assert(CapabilityScopes.issueProblem(raw).isEmpty, raw + " -> " + CapabilityScopes.issueProblem(raw))
            }
        }
        // the six-word parse vocabulary is not the supported set
        assert(CapabilityScopes.supported("job", "read").isEmpty)
        assert(CapabilityScopes.supported("query", "postgres").isEmpty)
        assert(!CapabilityScopes.supported("document", "upload").toSet.contains("destination_kind"))
    }

    test("issueProblem is None for every capability of every template and of RecoveryKey.Capabilities") {
        val templateCaps = KeyTemplates.all.flatMap(_._3)
        assert(templateCaps.nonEmpty)
        (templateCaps ++ RecoveryKey.Capabilities).distinct.foreach { raw =>
            assert(CapabilityScopes.issueProblem(raw).isEmpty, raw + " -> " + CapabilityScopes.issueProblem(raw))
        }
        // spot checks of the scoped ones they carry
        Seq("pipeline:run:owner=self", "tap:run:owner=self", "secret:read:_type=tap", "secret:write:_type=tap", "approval:read:owner=self").foreach { raw =>
            assert(CapabilityScopes.issueProblem(raw).isEmpty, raw)
        }
    }

    test(
        "issueProblem refuses owner on job, database on every query action, destination_kind on document:upload, owner on secret and approval:decide, catalog on metadata, config, audit, incident and protect:admin, accepts catalog on protect:reveal, and names the supported scopes"
    ) {
        // The story's example, verbatim.
        assert(
            refused("job:read:owner=self") ==
                "Invalid capability 'job:read:owner=self': scope 'owner' is not applied on job:read. " +
                "Supported scopes for job:read: none. Grant 'job:read' unscoped or leave it out."
        )

        def check(raw: String, scopeKey: String, pair: String, supportedList: String): Unit = {
            val m = refused(raw)
            assert(m.contains("'" + raw + "'"), m)
            assert(m.contains("scope '" + scopeKey + "' is not applied on " + pair), m)
            assert(m.contains("Supported scopes for " + pair + ": " + supportedList + "."), m)
        }

        check("job:kill:owner=self", "owner", "job:kill", "none")
        ExpectedVocabulary("query").foreach(a => check(s"query:$a:database=x", "database", s"query:$a", "none"))
        check("document:upload:destination_kind=qdrant", "destination_kind", "document:upload", "collection")
        check("secret:read:owner=self", "owner", "secret:read", "_type")
        check("secret:write:owner=self", "owner", "secret:write", "_type")
        check("approval:decide:owner=self", "owner", "approval:decide", "none")
        check("metadata:read:catalog=Sales", "catalog", "metadata:read", "none")
        check("config:read:catalog=Sales", "catalog", "config:read", "none")
        check("config:write:catalog=Sales", "catalog", "config:write", "none")
        check("audit:read:catalog=Sales", "catalog", "audit:read", "none")
        check("incident:read:catalog=Sales", "catalog", "incident:read", "none")
        assert(CapabilityScopes.issueProblem("protect:reveal:catalog=Sales").isEmpty, CapabilityScopes.issueProblem("protect:reveal:catalog=Sales"))
        check("protect:admin:catalog=Sales", "catalog", "protect:admin", "none")
        // a foreign scope key on pipeline / tap names the two that apply
        check("pipeline:read:database=x", "database", "pipeline:read", "catalog, owner")
        check("tap:run:collection=x", "collection", "tap:run", "catalog, owner")
        // every scope key on a resource that applies none
        Seq("metadata:read", "config:read", "mcp:tool", "audit:read", "policy:read", "incident:read", "code-repo:read", "protect:admin").foreach { pair =>
            AllScopeKeys.foreach(k => refused(pair + ":" + k + "=" + valueFor(k)))
        }
    }

    test(
        "issueProblem refuses an unknown resource, an unknown action, a repeated scope key, approval:read:owner=other and `*:*:catalog=Sales`; accepts `*:*`, `pipeline:*`, `pipeline:*:catalog=Sales`, `catalog=*`"
    ) {
        refused("pipline:read")
        refused("pipeline:reed")
        val rep = refused("pipeline:read:catalog=A,catalog=B")
        assert(rep.contains("catalog"), rep)
        refused("approval:read:owner=other")
        refused("*:*:catalog=Sales")
        refused("*:read:catalog=Sales") // covers job:read, metadata:read, ... which apply no catalog
        Seq(
            "*:*",
            "pipeline:*",
            "tap:*",
            "pipeline:*:catalog=Sales",
            "tap:*:owner=self",
            "secret:*:_type=tap",
            "pipeline:read:catalog=*",
            "pipeline:read:catalog=Sales,owner=self"
        ).foreach { raw =>
            assert(CapabilityScopes.issueProblem(raw).isEmpty, raw + " -> " + CapabilityScopes.issueProblem(raw))
        }
    }

    test(
        "partition keeps supported capabilities as the same instances, moves unsupported ones to inert, and narrows `*:read:catalog=Sales` to pipeline:read and tap:read"
    ) {
        val supported = Capability.parseList(Seq(
            "pipeline:read:catalog=Sales",
            "tap:run:owner=self",
            "secret:read:_type=tap",
            "search:vector:collection=docs",
            "document:upload",
            "approval:read:owner=self",
            "job:read",
            "pipeline:*:catalog=Sales",
            "*:*"
        ))
        val unsupported = Capability.parseList(Seq(
            "job:read:owner=self",
            "query:postgres:database=x",
            "document:upload:destination_kind=qdrant",
            "metadata:read:catalog=Sales",
            "pipeline:read:database=x"
        ))
        val (kept, inert) = CapabilityScopes.partition(supported ++ unsupported)
        assert(kept.size == supported.size, kept.map(_.raw))
        supported.foreach(c => assert(kept.exists(_ eq c), "same instance kept: " + c.raw))
        assert(inert.map(_.raw).toSet == unsupported.map(_.raw).toSet, inert.map(_.raw))

        // all-supported input: nothing moves
        val (k2, i2) = CapabilityScopes.partition(supported)
        assert(i2.isEmpty && k2.size == supported.size && supported.forall(c => k2.exists(_ eq c)))

        // the scoped wildcard narrows to the pairs that apply its scope
        val (narrowed, _) = CapabilityScopes.partition(Capability.parseList(Seq("*:read:catalog=Sales")))
        assert(
            narrowed.map(c => (c.resource, c.action, c.scope)).toSet ==
                Set(("pipeline", "read", Map("catalog" -> "Sales")), ("tap", "read", Map("catalog" -> "Sales"))),
            narrowed.map(_.raw)
        )
        val rk = ResolvedKey(None, "w", narrowed, isLegacyFullAccess = false)
        assert(rk.grants("pipeline", "read", Map("catalog" -> "Sales")))
        assert(rk.grants("tap", "read", Map("catalog" -> "Sales")))
        assert(!rk.grants("pipeline", "read", Map("catalog" -> "HR")))
        Seq(("job", "read"), ("metadata", "read"), ("audit", "read"), ("secret", "read"), ("config", "read"), ("incident", "read"))
            .foreach { case (r, a) => assert(!rk.matchesResourceAction(r, a), s"$r:$a must not survive narrowing") }

        // *:*:catalog=Sales keeps only pipeline and tap
        val (wide, _) = CapabilityScopes.partition(Capability.parseList(Seq("*:*:catalog=Sales")))
        val wk = ResolvedKey(None, "w", wide, isLegacyFullAccess = false)
        assert(wk.grants("pipeline", "delete", Map("catalog" -> "Sales")) && wk.grants("tap", "run", Map("catalog" -> "Sales")))
        Seq(("query", "postgres"), ("config", "write"), ("audit", "read"), ("document", "upload"), ("job", "read"))
            .foreach { case (r, a) => assert(!wk.matchesResourceAction(r, a), s"$r:$a must not survive narrowing") }
    }

    test("the catalogue offers no scope key that issueProblem refuses, and lists code-repo") {
        val body = new KeysAPIController().capabilitiesCatalog().getBody
        val resources = JsonParser.parseString(body).getAsJsonObject.getAsJsonArray("resources").asScala.map(_.getAsJsonObject).toList
        // response key set unchanged
        resources.foreach(r => assert(r.keySet().asScala.toSet == Set("resource", "actions", "scopeKeys"), r))
        def strs(o: com.google.gson.JsonObject, f: String): Seq[String] = o.getAsJsonArray(f).asScala.map(_.getAsString).toList
        val byResource = resources.map(r => r.get("resource").getAsString -> (strs(r, "actions"), strs(r, "scopeKeys"))).toMap

        assert(byResource.contains("code-repo"), byResource.keySet)
        assert(byResource("code-repo")._1.toSet == Set("read", "write"), byResource("code-repo"))
        assert(byResource.map { case (r, (as, _)) => r -> as.toSet } == ExpectedVocabulary, byResource)

        byResource.foreach { case (r, (actions, keys)) =>
            keys.foreach { k =>
                val ok = actions.exists(a => CapabilityScopes.issueProblem(s"$r:$a:$k=${valueFor(k)}").isEmpty)
                assert(ok, s"catalogue offers '$k' on $r but issueProblem refuses it on every $r action")
            }
        }
        // the inert offers are gone
        assert(byResource("job")._2.isEmpty, byResource("job"))
        assert(byResource("query")._2.isEmpty, byResource("query"))
        assert(byResource("secret")._2 == Seq("_type"), byResource("secret"))
        assert(byResource("document")._2 == Seq("collection"), byResource("document"))
        assert(byResource("search")._2 == Seq("collection"), byResource("search"))
        assert(byResource("pipeline")._2.toSet == Set("catalog", "owner") && byResource("tap")._2.toSet == Set("catalog", "owner"))
    }

    test("collectionName: plain name for qdrant, weaviate, milvus, chroma and pgvector public; schema.table for another pgvector schema") {
        assert(CapabilityScopes.collectionName("qdrant", "docs", None) == "docs")
        assert(CapabilityScopes.collectionName("milvus", "docs", None) == "docs")
        assert(CapabilityScopes.collectionName("chroma", "docs", None) == "docs")
        // weaviate class identity is case-insensitive (VectorContainers); the case is not pinned
        assert(CapabilityScopes.collectionName("weaviate", "Docs", None).equalsIgnoreCase("Docs"))
        assert(CapabilityScopes.collectionName("pgvector", "docs", None) == "docs")
        assert(CapabilityScopes.collectionName("pgvector", "docs", Some("public")) == "docs")
        assert(CapabilityScopes.collectionName("pgvector", "docs", Some("")) == "docs", "blank schema is public, as in VectorContainers.key")
        assert(CapabilityScopes.collectionName("pgvector", "docs", Some("other")) == "other.docs")
    }

    test("inert messages: a scope applied on the route but with a value it rejects says the route accepts only 'self'") {
        val bob = Seq(Capability.parse("approval:read:owner=bob"))
        val denial = CapabilityScopes.inertDenialMessage("agent", "approval:read", bob)
        assert(denial.contains("approval:read:owner=bob"), denial)
        assert(denial.contains("scope 'owner' on approval:read accepts only 'self'"), denial)
        assert(!denial.contains("not applied"), "owner IS applied on approval:read: " + denial)
        assert(!denial.contains("Supported scopes for approval:read: owner"), denial)
        assert(denial.contains("'approval:read:owner=self' or 'approval:read'"), denial)
        val used = CapabilityScopes.inertUsedMessage("agent", "approval:read", bob)
        assert(used.contains("accepts only 'self'") && !used.contains("not applied"), used)
        // a scope key the route does not apply keeps its wording
        val job = Seq(Capability.parse("job:read:owner=self"))
        val jobDenial = CapabilityScopes.inertDenialMessage("ik", "job:read", job)
        assert(jobDenial.contains("scope 'owner' is not applied on job:read") && jobDenial.contains("Supported scopes for job:read: none"), jobDenial)
    }

    test("inert-scope-used reason claims only that the capability gate let the request through") {
        val used = CapabilityScopes.inertUsedMessage("ik", "job:read", Seq(Capability.parse("job:read:owner=self")))
        assert(used.endsWith(CapabilityScopes.InertScopesEnv + "=allow let it through the capability gate."), used)
        assert(!used.contains("allowed because"), used)
    }

    test("inert messages: a capability carrying both a scope the route does not apply and one it applies with a rejected value describes each correctly") {
        val mixed = Seq(Capability.parse("approval:read:owner=bob,catalog=x"))
        val denial = CapabilityScopes.inertDenialMessage("agent", "approval:read", mixed)
        assert(denial.contains("scope 'catalog' is not applied on approval:read and scope 'owner' on approval:read accepts only 'self'"), denial)
        assert(!denial.contains("scopes 'catalog', 'owner' are not applied"), denial)
        assert(denial.contains("Supported scopes for approval:read: owner (only 'self')."), denial)
        assert(denial.contains("'approval:read:owner=self' or 'approval:read'"), denial)
        val used = CapabilityScopes.inertUsedMessage("agent", "approval:read", mixed)
        assert(used.contains("whose scope 'catalog' is not applied on approval:read and scope 'owner' on approval:read accepts only 'self'"), used)
        // two keys the route does not apply keep the plural wording
        val two = CapabilityScopes.inertDenialMessage("ik", "job:read", Seq(Capability.parse("job:read:owner=self,catalog=x")))
        assert(two.contains("scopes 'catalog', 'owner' are not applied on job:read"), two)
    }
}
