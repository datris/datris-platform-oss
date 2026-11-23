package ai.datris.auth

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{Capability, ResolvedKey, TenantContext, User}
import ai.datris.util.{APIKeyValidator, InMemoryUserDb, UserStore}
import ai.datris.util.InMemoryUserDb.grants
import org.scalatest.funsuite.AnyFunSuite

/** Story: Tap runs and owner access, report-only release
  * (plans/stories/tap-runs-hold-owner-scope.md), Acceptance bullet 1
  * (`TapPrincipalSpec`): each owner label a tap stores (`createdByKeyLabel`)
  * resolves to the identity it stands for today; an unknown or revoked key
  * and a deleted user are unresolved; a store failure is an error, never
  * "full" and never "unresolved".
  *
  * Pinned seam (does not exist on feature/access-scope-gaps at 4f38ae31; the
  * implementation adds it):
  * {{{
  * // datrisserver/src/main/scala/ai/datris/auth/TapPrincipal.scala
  * object TapPrincipal {
  *   sealed trait Resolution
  *   case object Full extends Resolution                          // install-wide, nothing to hold to
  *   final case class Resolved(key: ResolvedKey) extends Resolution
  *   case object Unresolved extends Resolution                    // revoked / unknown key, deleted user
  *
  *   // Pure core. `findUser(username)` and `keyByLabel(label)` are the store
  *   // reads (UserStore.find, APIKeyValidator.resolveByLabel); either may
  *   // throw, and the throw propagates.
  *   def resolveWith(label: String, findUser: String => Option[User],
  *                   keyByLabel: String => Option[ResolvedKey]): Resolution
  *
  *   // Live: resolveWith(label, UserStore.find, APIKeyValidator.resolveByLabel).
  *   def resolve(label: String): Resolution
  * }
  * }}}
  *
  * Label forms the code stores as a tap owner (ResolvedKeyAccess.keyLabel at
  * create, kept on update): an API key label; `session:<username>` for a
  * signed-in user (APIKeyValidator.resolveFromSession); `ui` (the platform's
  * own key); `recovery-agent` (RecoveryKey.Label); `anonymous` (API keys off);
  * null (no identity on the request, catalog restore). Multi-tenant labels
  * are not covered: the multi-tenant setting is retired on this branch (the
  * server refuses to start with it on).
  */
class TapPrincipalSpec extends AnyFunSuite {

    import TapPrincipal.{Full, Resolved, Unresolved}

    private def key(label: String, caps: String*): ResolvedKey =
        ResolvedKey(None, label, Capability.parseList(caps), isLegacyFullAccess = false, keyId = Some("k_" + label))

    private val salesKey = key("sales-agent", "pipeline:read:catalog=Sales", "pipeline:run:catalog=Sales", "tap:read:catalog=Sales", "tap:create:catalog=Sales")
    private val legacyKey = ResolvedKey(None, "old-agent", Seq(Capability.FullAccess), isLegacyFullAccess = true)

    private val admin = User("ada", "h", User.RoleAdmin, null, null, null)
    private val editor = User("ed", "h", User.RoleEditor, null, null, null)
    private val restricted = User("rita", "h", User.RoleEditor, null, null, null, restricted = true, catalogGrants = grants("Sales" -> "edit"))

    private val users: Map[String, User] = Seq(admin, editor, restricted).map(u => u.username -> u).toMap
    private val keys: Map[String, ResolvedKey] = Map("sales-agent" -> salesKey, "old-agent" -> legacyKey)

    private val findUser: String => Option[User] = users.get
    private val keyByLabel: String => Option[ResolvedKey] = keys.get

    private def resolve(label: String) = TapPrincipal.resolveWith(label, findUser, keyByLabel)

    private val noUserRead: String => Option[User] = n => throw new AssertionError("no user read expected for " + n)
    private val noKeyRead: String => Option[ResolvedKey] = n => throw new AssertionError("no key read expected for " + n)

    // ---------------------------------------------------------------- API keys

    test("a current API key label resolves to that key's current capabilities, scopes and key id") {
        val r = resolve("sales-agent")
        assert(r == Resolved(salesKey), r.toString)
        val Resolved(rk) = r
        assert(CallerVisibility.limited(rk), "a catalog-scoped key stays limited")
        assert(rk.grants("pipeline", "run", Map("catalog" -> "Sales")) && !rk.grants("pipeline", "run", Map("catalog" -> "Finance")))
    }

    test("a key label is looked up as a key, never as a user") {
        val r = TapPrincipal.resolveWith("sales-agent", noUserRead, keyByLabel)
        assert(r == Resolved(salesKey), r.toString)
    }

    test("a legacy key with no stored capabilities resolves to its legacy full access, as a request presenting it would") {
        val r = resolve("old-agent")
        assert(r == Resolved(legacyKey), r.toString)
        val Resolved(rk) = r
        assert(rk.isLegacyFullAccess && rk.isFullAccess && !CallerVisibility.limited(rk))
    }

    test("a revoked or unknown key label is unresolved") {
        // APIKeyValidator.resolveByLabel answers None for both.
        assert(resolve("gone-agent") == Unresolved)
        assert(resolve("never-issued") == Unresolved)
    }

    // ---------------------------------------------------------------- sessions

    test("a signed-in administrator's session label resolves to the administrator's session identity (full access)") {
        val r = resolve("session:ada")
        assert(r == Resolved(APIKeyValidator.resolveFromSession(admin)), r.toString)
        val Resolved(rk) = r
        assert(rk.isFullAccess && !CallerVisibility.limited(rk))
    }

    test("an editor's session label resolves to the editor's role bundle, not limited") {
        val r = resolve("session:ed")
        assert(r == Resolved(APIKeyValidator.resolveFromSession(editor)), r.toString)
        val Resolved(rk) = r
        assert(!rk.isFullAccess && !CallerVisibility.limited(rk))
    }

    test("a restricted user's session label resolves to the session narrowed to its catalog grants") {
        val r = resolve("session:rita")
        assert(r == Resolved(APIKeyValidator.resolveFromSession(restricted)), r.toString)
        val Resolved(rk) = r
        assert(rk.restrictedSession && CallerVisibility.limited(rk))
        assert(rk.label == "session:rita")
    }

    test("a session label is looked up as a user, never as a key") {
        val r = TapPrincipal.resolveWith("session:rita", findUser, noKeyRead)
        assert(r == Resolved(APIKeyValidator.resolveFromSession(restricted)), r.toString)
    }

    test("a deleted user's session label is unresolved") {
        assert(resolve("session:departed") == Unresolved)
    }

    // ---------------------------------------------------------------- the platform's own labels

    test("the platform's own key, the recovery key and anonymous (API keys off) resolve to full access without a store read") {
        for (label <- Seq("ui", "recovery-agent", "anonymous"))
            assert(TapPrincipal.resolveWith(label, noUserRead, noKeyRead) == Full, label)
    }

    test("a blank or missing owner label resolves to full access without a store read") {
        for (label <- Seq(null, "", "   "))
            assert(TapPrincipal.resolveWith(label, noUserRead, noKeyRead) == Full, String.valueOf(label))
    }

    // ---------------------------------------------------------------- store failures

    test("a key store failure is an error, not full access and not unresolved") {
        val e = intercept[Exception](TapPrincipal.resolveWith("sales-agent", findUser, _ => throw new IllegalStateException("key store down")))
        assert(e.getMessage.contains("key store down"), e.getMessage)
    }

    test("a user store failure is an error, not full access and not unresolved") {
        val e = intercept[Exception](TapPrincipal.resolveWith("session:rita", _ => throw new IllegalStateException("user store down"), keyByLabel))
        assert(e.getMessage.contains("user store down"), e.getMessage)
    }

    // ---------------------------------------------------------------- live wiring

    /** `TapPrincipal.resolve` reads users through UserStore (dbOverride) and
      * keys through APIKeyValidator.resolveByLabel (resolveByLabelOverride). */
    private def live[T](keyAnswer: String => Option[ResolvedKey], db: InMemoryUserDb)(body: => T): T = {
        val savedDb = UserStore.dbOverride
        val savedKeys = APIKeyValidator.resolveByLabelOverride
        TenantContext.set(InMemoryUserDb.env())
        UserStore.dbOverride = db
        APIKeyValidator.resolveByLabelOverride = keyAnswer
        try body
        finally {
            UserStore.dbOverride = savedDb
            APIKeyValidator.resolveByLabelOverride = savedKeys
            TenantContext.clear()
        }
    }

    private def userDb(us: User*): InMemoryUserDb = {
        val db = new InMemoryUserDb
        us.foreach(db.put)
        db
    }

    test("live: resolve reads the user store for a session label and the key store for a key label") {
        live(keyByLabel, userDb(admin, editor, restricted)) {
            assert(TapPrincipal.resolve("session:rita") == Resolved(APIKeyValidator.resolveFromSession(restricted)))
            assert(TapPrincipal.resolve("session:departed") == Unresolved)
            assert(TapPrincipal.resolve("sales-agent") == Resolved(salesKey))
            assert(TapPrincipal.resolve("gone-agent") == Unresolved)
            assert(TapPrincipal.resolve(null) == Full)
        }
    }

    test("live: a failing key store or user store makes resolve throw") {
        live(_ => throw new IllegalStateException("API key metadata unavailable"), userDb(restricted)) {
            intercept[Exception](TapPrincipal.resolve("sales-agent"))
        }
        val failingDb = new InMemoryUserDb {
            override def getItemJSON(tableName: String, keyName: String, key: String, valueName: String): Option[String] =
                throw new IllegalStateException("mongo down")
        }
        live(keyByLabel, failingDb) {
            intercept[Exception](TapPrincipal.resolve("session:rita"))
        }
    }
}
