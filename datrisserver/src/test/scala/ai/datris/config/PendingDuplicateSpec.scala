package ai.datris.config

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.audit.AuditActorInfo
import ai.datris.policy.{PendingAction, PendingActionIO}
import com.mongodb.MongoClientSettings
import org.bson.BsonDocument
import org.scalatest.funsuite.AnyFunSuite

import java.time.Instant
import java.time.temporal.ChronoUnit
import scala.collection.mutable

/** The approval queue's duplicate lookup (`PolicyInterceptor.pendingDuplicate`):
  * one store read; an expired action is never handed back; actions stored
  * without a hash version (queued before it existed) are found by their
  * earlier-form hash however many newer actions the actor has; new actions
  * are stored with a hash whose fields cannot imitate each other. */
class PendingDuplicateSpec extends AnyFunSuite {

    private val agent = "agent-key"
    private val body = "{\"name\":\"t1\"}"
    private val path = "/api/v1/tap/run"

    private def current: String = PendingAction.delimitedHashOf(agent, "POST", path, None, Some(body))
    private def legacy: String = PendingAction.hashOf(agent, "POST", path, None, Some(body))

    private def pa(id: String, hashVersion: Option[Int], expiresIn: Long = 3600, created: Instant = Instant.now(), b: String = body): PendingAction =
        PendingAction(
            id = id,
            action = "tap:run",
            resourceType = Some("tap"),
            resourceName = Some("t1"),
            resourceVersion = None,
            actor = AuditActorInfo("api-key", agent),
            reason = None,
            agentSession = None,
            method = "POST",
            path = path,
            query = None,
            contentType = Some("application/json"),
            body = Some(b),
            bodyHash =
                if (hashVersion.isDefined) PendingAction.delimitedHashOf(agent, "POST", path, None, Some(b))
                else PendingAction.hashOf(agent, "POST", path, None, Some(b)),
            createdAt = created,
            expiresAt = Instant.now().plus(expiresIn, ChronoUnit.SECONDS),
            state = PendingAction.Pending,
            hashVersion = hashVersion
        )

    /** Models the Mongo store: expired documents stay until the TTL monitor
      * removes them; `listPending` is clamped to the newest 500 as
      * `PendingActionIO.list` is; `pendingCandidates` is the unclamped
      * `PendingActionIO.candidatesFilter` query. Counts every read. */
    private class MongoLikeStore(docs: Seq[PendingAction]) extends PolicyInterceptor.Store {
        val calls: mutable.Buffer[String] = mutable.Buffer.empty
        private def pending(a: String) = docs.filter(d => d.state == PendingAction.Pending && d.actor.label == a)
        def get(id: String): Option[PendingAction] = { calls += "get"; docs.find(_.id == id) }
        def consumeReplay(id: String, token: String): Option[PendingAction] = { calls += "consume"; None }
        def findPendingDuplicate(hash: String, actorLabel: String): Option[PendingAction] = {
            calls += "findPendingDuplicate"
            pending(actorLabel).find(_.bodyHash == hash).filter(!_.isExpired())
        }
        def countPending(actorLabel: String): Long = { calls += "count"; pending(actorLabel).size.toLong }
        def listPending(actorLabel: String): List[PendingAction] = {
            calls += "listPending"
            pending(actorLabel).sortBy(_.createdAt).reverse.take(PendingActionIO.MaxListLimit).toList
        }
        def insert(p: PendingAction): Unit = calls += "insert"
        def currentVersion(resourceType: String, name: String): Option[Int] = None
        override def pendingCandidates(hash: String, actorLabel: String): List[PendingAction] = {
            calls += "pendingCandidates"
            pending(actorLabel).filter(d => d.bodyHash == hash || d.hashVersion.isEmpty).toList
        }
    }

    private def lookup(store: PolicyInterceptor.Store): Option[PendingAction] =
        PolicyInterceptor.pendingDuplicate(store, current, legacy, agent)

    test("one store read: a request whose action is pending under the current hash is found with no other read") {
        val s = new MongoLikeStore(Seq(pa("pa_new", Some(PendingAction.CurrentHashVersion))))
        assert(lookup(s).map(_.id).contains("pa_new"))
        assert(s.calls == Seq("pendingCandidates"), s.calls.toString)
    }

    test("no legacy document: a request with no duplicate costs one read, and nothing is rehashed or listed") {
        val others = (1 to 20).map(i => pa("pa_" + i, Some(PendingAction.CurrentHashVersion), b = "{\"name\":\"t" + (i + 100) + "\"}"))
        val s = new MongoLikeStore(others)
        assert(lookup(s).isEmpty)
        assert(s.calls == Seq("pendingCandidates"), s.calls.toString)
    }

    test("an expired pending action is not returned, whether stored under the current hash or the earlier one") {
        for (hv <- Seq(Some(PendingAction.CurrentHashVersion), None)) {
            val s = new MongoLikeStore(Seq(pa("pa_old", hv, expiresIn = -5)))
            assert(lookup(s).isEmpty, s"$hv")
            // the default Store answer (spec stores) applies the same rule
            val d = new MongoLikeStore(Seq(pa("pa_old", hv, expiresIn = -5))) {
                override def pendingCandidates(hash: String, actorLabel: String): List[PendingAction] =
                    findPendingDuplicate(hash, actorLabel).toList ++ listPending(actorLabel).filter(_.hashVersion.isEmpty)
            }
            assert(lookup(d).isEmpty, s"default $hv")
        }
        // unexpired, earlier form: found
        assert(lookup(new MongoLikeStore(Seq(pa("pa_legacy", None)))).map(_.id).contains("pa_legacy"))
    }

    test("a legacy action older than the actor's newest 500 is still found") {
        val t0 = Instant.now().minus(1, ChronoUnit.DAYS)
        val legacyOld = pa("pa_legacy", None, created = t0)
        val newer = (1 to 600).map(i =>
            pa("pa_" + i, Some(PendingAction.CurrentHashVersion), created = t0.plusSeconds(i.toLong), b = "{\"name\":\"t" + (i + 1000) + "\"}")
        )
        val s = new MongoLikeStore(legacyOld +: newer)
        assert(!s.listPending(agent).exists(_.id == "pa_legacy"), "fixture: the clamped list does not reach it")
        s.calls.clear()
        assert(lookup(s).map(_.id).contains("pa_legacy"))
        assert(s.calls == Seq("pendingCandidates"), s.calls.toString)
    }

    test("the Mongo candidate query: the stored hash, or no hash version, for the actor's pending actions; no limit") {
        val f = PendingActionIO.candidatesFilter("h", agent).toBsonDocument(classOf[BsonDocument], MongoClientSettings.getDefaultCodecRegistry)
        val expected = BsonDocument.parse(
            """{"$or": [{"$and": [{"bodyHash": "h"}, {"actor.label": "agent-key"}, {"state": "pending"}]},
              |         {"$and": [{"actor.label": "agent-key"}, {"state": "pending"}, {"hashVersion": null}]}]}""".stripMargin
        )
        assert(f == expected, f.toJson)
        val rel = "src/main/scala/ai/datris/policy/PendingAction.scala"
        val file = Seq(java.nio.file.Paths.get(rel), java.nio.file.Paths.get("datrisserver", rel))
            .find(java.nio.file.Files.isRegularFile(_)).getOrElse(fail(rel + " not found"))
        val src = new String(java.nio.file.Files.readAllBytes(file), java.nio.charset.StandardCharsets.UTF_8)
        val i = src.indexOf("def pendingCandidates(")
        val body = src.substring(i, src.indexOf("\n    def ", i + 1))
        assert(body.contains("candidatesFilter(") && !body.contains(".limit("), body)
        assert(src.contains("""new Document("actor.label", 1).append("state", 1).append("hashVersion", 1)"""), "index for the earlier-hash branch")
    }

    test("the hash version is stored on new documents and absent documents read as before") {
        val withMarker = pa("pa_new", Some(PendingAction.CurrentHashVersion))
        val d = withMarker.toDocument
        assert(d.getInteger("hashVersion") == PendingAction.CurrentHashVersion)
        assert(PendingAction.fromDocument(d).hashVersion.contains(PendingAction.CurrentHashVersion))
        assert(PendingAction.fromDocument(d).bodyHash == withMarker.bodyHash)
        val old = pa("pa_old", None)
        val od = old.toDocument
        assert(!od.containsKey("hashVersion"))
        assert(PendingAction.fromDocument(od).hashVersion.isEmpty)
        assert(PendingAction.fromDocument(od).bodyHash == old.bodyHash)
    }

    test("the current hash is unambiguous: a path with an encoded line break does not collide with a path plus query") {
        // The earlier form collides for this shape (documented; it is used
        // only to find documents stored before the hash version).
        val p1 = ("/api/v1/x\nq", Option.empty[String], Some("{}"))
        val p2 = ("/api/v1/x", Some("q"), Some("\n{}"))
        assert(PendingAction.hashOf(agent, "POST", p1._1, p1._2, p1._3) == PendingAction.hashOf(agent, "POST", p2._1, p2._2, p2._3))
        assert(PendingAction.delimitedHashOf(agent, "POST", p1._1, p1._2, p1._3) != PendingAction.delimitedHashOf(agent, "POST", p2._1, p2._2, p2._3))
        // other boundary shifts and absent versus empty
        assert(PendingAction.delimitedHashOf("a\nb", "POST", path, None, None) != PendingAction.delimitedHashOf("a", "b\nPOST", path, None, None))
        assert(PendingAction.delimitedHashOf(agent, "POST", path, None, None) != PendingAction.delimitedHashOf(agent, "POST", path, Some(""), None))
        assert(PendingAction.delimitedHashOf(agent, "POST", path, None, None) != PendingAction.delimitedHashOf(agent, "POST", path, None, Some("")))
        // deterministic, and differs from the earlier form
        assert(current == PendingAction.delimitedHashOf(agent, "POST", path, None, Some(body)) && current != legacy)
    }
}
