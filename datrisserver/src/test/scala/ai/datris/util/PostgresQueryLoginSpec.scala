package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{DatrisException, PostgresSecrets}
import org.scalatest.funsuite.AnyFunSuite

/** Story: an optional separate low-privilege login for read-back queries
  * (plans/stories/postgres-query-role.md). The platform Postgres secret may
  * carry two optional keys, `queryUsername` and `queryPassword`;
  * `SecretsRetrieverUtil.postgresQueryLogin(secrets): (String, String)` is
  * the login a query connects with: the query pair when both are set, the
  * loading pair when neither is, a DatrisException when only one is
  * (Decision 2: fails closed, never a fall back to the loading login). Blank
  * keys count as absent. Pure: no Vault, no database.
  *
  * The two new `PostgresSecrets` fields and `postgresQueryLogin` are reached
  * by reflection so this spec compiles before they exist; each test fails at
  * run time naming what is missing. The position of the two new fields in
  * the constructor is read from the accessors, not assumed. */
class PostgresQueryLoginSpec extends AnyFunSuite {

    private val LoadUser = "loader-user-7f3a"
    private val LoadPassword = "loader-pass-91c2"
    private val JdbcUrl = "jdbc:postgresql://pg-host-5d8e:5432/"
    private val QueryUser = "reader-user-2b6d"
    private val QueryPassword = "reader-pass-e04f"

    private val Fields = Seq("username", "password", "jdbcUrl", "queryUsername", "queryPassword")

    private def accessor(name: String): java.lang.reflect.Method =
        try classOf[PostgresSecrets].getMethod(name)
        catch {
            case _: NoSuchMethodException =>
                throw new AssertionError("PostgresSecrets has no field " + name + " (added by the story)")
        }

    private def read(s: PostgresSecrets, name: String): String = accessor(name).invoke(s).asInstanceOf[String]

    /** The five-String constructor, and for each field name the argument
      * position that sets it (found by building one with marker values). */
    private lazy val constructor: (java.lang.reflect.Constructor[_], Map[String, Int]) = {
        Fields.foreach(accessor)
        val ctor = classOf[PostgresSecrets].getConstructors.find(c => c.getParameterCount == 5 && c.getParameterTypes.forall(_ == classOf[String]))
            .getOrElse(
                throw new AssertionError(
                    "PostgresSecrets has no constructor of five Strings (username, password, jdbcUrl and the two optional query keys; added by the story)"
                )
            )
        val markers = (0 until 5).map(i => "marker-" + i)
        val probe = ctor.newInstance(markers: _*).asInstanceOf[PostgresSecrets]
        val positions = Fields.map(f => f -> markers.indexOf(read(probe, f))).toMap
        assert(positions.values.toSet == (0 until 5).toSet, "each field set by one constructor argument: " + positions)
        (ctor, positions)
    }

    private def secrets(queryUsername: String, queryPassword: String): PostgresSecrets = {
        val (ctor, positions) = constructor
        val values = Map(
            "username" -> LoadUser,
            "password" -> LoadPassword,
            "jdbcUrl" -> JdbcUrl,
            "queryUsername" -> queryUsername,
            "queryPassword" -> queryPassword
        )
        val args = new Array[AnyRef](5)
        values.foreach { case (k, v) => args(positions(k)) = v }
        ctor.newInstance(args: _*).asInstanceOf[PostgresSecrets]
    }

    private def queryLogin(s: PostgresSecrets): (String, String) = {
        val m = QuerySafetyReflect.method(SecretsRetrieverUtil, "postgresQueryLogin", classOf[PostgresSecrets])
        QuerySafetyReflect.invoke(SecretsRetrieverUtil, m, s) match {
            case (u: String, p: String) => (u, p)
            case other => throw new AssertionError("postgresQueryLogin should return (user, password), got: " + other)
        }
    }

    private def refused(s: PostgresSecrets): DatrisException =
        try {
            val got = queryLogin(s)
            fail("expected a DatrisException for a half-configured query login, got the login " + got._1)
        } catch { case e: DatrisException => e }

    private def assertNoSecretValue(message: String): Unit =
        Seq(LoadUser, LoadPassword, JdbcUrl, QueryUser, QueryPassword).foreach { v =>
            assert(!message.contains(v), "the message carries a value of the secret: " + message)
        }

    test("no query keys: the loading user and password are returned") {
        val (user, password) = queryLogin(secrets(null, null))
        assert(user == LoadUser && password == LoadPassword, user)
        // The values themselves, untouched: the connection is the one made today.
        assert((user eq LoadUser) && (password eq LoadPassword))
    }

    test("no query keys: a secret built from the three keys of today has no query keys and uses the loading login") {
        // Upgrade path: the existing three-key secret (the new fields default to null).
        val today = PostgresSecrets(LoadUser, LoadPassword, JdbcUrl)
        assert(read(today, "queryUsername") == null, "queryUsername defaults to null")
        assert(read(today, "queryPassword") == null, "queryPassword defaults to null")
        assert(queryLogin(today) == ((LoadUser, LoadPassword)))
    }

    test("both query keys: the query user and password are returned") {
        val s = secrets(QueryUser, QueryPassword)
        assert(queryLogin(s) == ((QueryUser, QueryPassword)))
        // The loading login is still on the secret for loads.
        assert(s.username == LoadUser && s.password == LoadPassword && s.jdbcUrl == JdbcUrl)
    }

    test("only queryUsername, or only queryPassword: a DatrisException that names the missing key and no secret value") {
        val noPassword = refused(secrets(QueryUser, null)).getMessage
        assert(noPassword != null && "(?is).*query\\s*password.*".r.pattern.matcher(noPassword).matches(), "names the missing queryPassword: " + noPassword)
        assert(noPassword.toLowerCase(java.util.Locale.ROOT).contains("postgres"), "says it is the Postgres secret: " + noPassword)
        assertNoSecretValue(noPassword)

        val noUser = refused(secrets(null, QueryPassword)).getMessage
        assert(noUser != null && "(?is).*query\\s*user.*".r.pattern.matcher(noUser).matches(), "names the missing queryUsername: " + noUser)
        assert(noUser.toLowerCase(java.util.Locale.ROOT).contains("postgres"), "says it is the Postgres secret: " + noUser)
        assertNoSecretValue(noUser)
    }

    test("blank query keys count as absent") {
        // A secret edited to empty strings behaves as unset.
        assert(queryLogin(secrets("", "")) == ((LoadUser, LoadPassword)))
        assert(queryLogin(secrets("", null)) == ((LoadUser, LoadPassword)))
        assert(queryLogin(secrets(null, "")) == ((LoadUser, LoadPassword)))
        // One key set and the other empty is half-configured: refused, never the loading login.
        val noPassword = refused(secrets(QueryUser, "")).getMessage
        assert("(?is).*query\\s*password.*".r.pattern.matcher(noPassword).matches(), noPassword)
        assertNoSecretValue(noPassword)
        val noUser = refused(secrets("", QueryPassword)).getMessage
        assert("(?is).*query\\s*user.*".r.pattern.matcher(noUser).matches(), noUser)
        assertNoSecretValue(noUser)
    }

    // ------------------------------------------------------------------
    // Review follow-ups: whitespace-only keys, the same user as the loading
    // login, and usesQueryLogin.
    // ------------------------------------------------------------------

    test("whitespace-only query keys count as absent; a key with real characters is used exactly as written") {
        assert(queryLogin(secrets("   ", "\t")) == ((LoadUser, LoadPassword)))
        assert(queryLogin(secrets(" \n ", null)) == ((LoadUser, LoadPassword)))
        assert(queryLogin(secrets(null, "  ")) == ((LoadUser, LoadPassword)))
        val noPassword = refused(secrets(QueryUser, "   ")).getMessage
        assert("(?is).*query\\s*password.*".r.pattern.matcher(noPassword).matches(), noPassword)
        assertNoSecretValue(noPassword)
        val noUser = refused(secrets(" \t", QueryPassword)).getMessage
        assert("(?is).*query\\s*user.*".r.pattern.matcher(noUser).matches(), noUser)
        assertNoSecretValue(noUser)
        // Not trimmed: the padded values are the login.
        assert(queryLogin(secrets(" " + QueryUser, QueryPassword + " ")) == ((" " + QueryUser, QueryPassword + " ")))
    }

    test("the loading user as the query user with a different password is refused, naming no secret value") {
        val message = refused(secrets(LoadUser, QueryPassword)).getMessage
        assert(message.toLowerCase(java.util.Locale.ROOT).contains("postgres"), message)
        assert(message.contains("different PostgreSQL user"), message)
        assertNoSecretValue(message)
    }

    test("the loading user as the query user with the loading password is the loading login") {
        val (user, password) = queryLogin(secrets(LoadUser, LoadPassword))
        assert((user eq LoadUser) && (password eq LoadPassword))
        assert(!SecretsRetrieverUtil.usesQueryLogin(secrets(LoadUser, LoadPassword)))
    }

    test("usesQueryLogin: true only for a complete query login naming another user") {
        assert(SecretsRetrieverUtil.usesQueryLogin(secrets(QueryUser, QueryPassword)))
        assert(!SecretsRetrieverUtil.usesQueryLogin(PostgresSecrets(LoadUser, LoadPassword, JdbcUrl)))
        assert(!SecretsRetrieverUtil.usesQueryLogin(secrets(QueryUser, null)))
        assert(!SecretsRetrieverUtil.usesQueryLogin(secrets(null, QueryPassword)))
        assert(!SecretsRetrieverUtil.usesQueryLogin(secrets("  ", "  ")))
    }
}
