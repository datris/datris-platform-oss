package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.StartupRunner
import ai.datris.audit.{AuditEntry, AuditLog}
import ai.datris.model._
import ai.datris.util.{NoSQLDbUtility, PasswordHasher, UserStore}
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.google.gson.{Gson, JsonParser}
import jakarta.servlet.http.HttpServletRequest
import org.mockito.ArgumentMatchers.{any, eq => eqTo}
import org.mockito.Mockito.{mock, never, verify, when}
import org.scalatest.BeforeAndAfterEach
import org.scalatest.funsuite.AnyFunSuite
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._
import scala.collection.mutable

/** Story: audit credential resets (plans/stories/audit-credential-resets.md).
  *
  * Every event that changes what password an account can log in with must
  * leave an audit row naming the account and the kind of change:
  *   - PATCH /api/v1/auth/users/{u} with resetPassword → user / reset-password
  *   - PATCH with role → user / update with metadata.role (one row per change)
  *   - startup rotation of a null-hash account → user / rotate-password,
  *     system actor, metadata.reason = "no-password-hash", no password text
  *   - first-boot admin seed → user / create on user:admin, system actor,
  *     metadata.reason = "bootstrap"
  *
  * No Spring, no Mongo: the controller is called directly with a mocked
  * request, UserStore runs its real logic against an in-memory NoSQLDbUtility,
  * and AuditLog entries are captured at `submit` instead of being queued.
  *
  * Pinned seam (does not exist on main at v1.45.0; the implementation adds it):
  * {{{
  * // ai.datris.api.AuthAPIController — HttpServletRequest added, as on the
  * // other controller methods that call AuditLog.record
  * def patchUser(username: String, body: String, request: HttpServletRequest): ResponseEntity[String]
  *
  * // ai.datris.StartupRunner (companion object) — the user part of
  * // initUserAuth (null-hash rotation + empty-table admin seed), without
  * // SessionStore.ensureIndex. initUserAuth calls it.
  * object StartupRunner { private[datris] def bootstrapUsers(): Unit }
  *
  * // ai.datris.util.UserStore — test override for the backing store
  * // (null = the package NoSQLDbUtil), same pattern as FieldProtection's
  * // *Override vars.
  * @volatile private[datris] var dbOverride: NoSQLDbUtility = null
  *
  * // ai.datris.audit.AuditLog — test sink (null = the bounded queue). Still
  * // gated by `enabled`: with useAuditLog off nothing reaches the sink.
  * @volatile private[datris] var sinkOverride: AuditEntry => Unit = null
  * }}}
  */
class AuthAPIControllerAuditSpec extends AnyFunSuite with BeforeAndAfterEach {

    // ---- in-memory user table ---------------------------------------------

    private class FakeUserDb extends NoSQLDbUtility {
        private val gson = new Gson
        val rows: mutable.LinkedHashMap[String, String] = mutable.LinkedHashMap()
        private def keyOf(json: String): String = JsonParser.parseString(json).getAsJsonObject.get("username").getAsString

        def put(u: User): Unit = rows.put(u.username, gson.toJson(u))
        def user(name: String): Option[User] = rows.get(name).map(gson.fromJson(_, classOf[User]))

        override def getAllItemsAsJSON(tableName: String): List[String] = rows.values.toList
        override def getItemJSON(tableName: String, keyName: String, key: String, valueName: String): Option[String] = rows.get(key)
        override def insertJSON(tableName: String, json: String): Unit = rows.put(keyOf(json), json)

        /** When set, a write that clears the password hash fails (simulated store error). */
        var failPasswordClear = false
        override def upsertJSON(tableName: String, keyFields: java.util.List[String], json: String): Unit = {
            val o = JsonParser.parseString(json).getAsJsonObject
            if (failPasswordClear && (!o.has("passwordHash") || o.get("passwordHash").isJsonNull))
                throw new RuntimeException("simulated store failure")
            rows.put(keyOf(json), json)
        }
        override def deleteItemJSON(tableName: String, keyName: String, key: String, sortKeyName: String, sortKeyValue: Number): Unit =
            rows.remove(key)
        override def deleteAll(tableName: String): Long = { val n = rows.size; rows.clear(); n.toLong }
        override def getItemsKeysByKeyName(tableName: String, keyName: String): List[String] = rows.keys.toList
        override def getItemAttribute[T](tableName: String, keyName: String, key: String, attributeName: String): T = null.asInstanceOf[T]
        override def setItemNameValue(tableName: String, keyName: String, key: String, valueName: String, value: String): Unit = ()
        override def putItemJSON(
            tableName: String,
            keyName: String,
            key: String,
            valueName: String,
            value: String,
            sortKeyName: String,
            sortKeyValue: Number,
            extraFields: java.util.Map[String, AnyRef]
        ): Unit = ()
        override def updateItemJSON(
            tableName: String,
            keyName: String,
            key: String,
            valueName: String,
            value: String,
            sortKeyName: String,
            sortKeyValue: Number
        ): Unit = ()
        override def queryJSONItemsByKey(tableName: String, keyName: String, key: String): List[String] = rows.get(key).toList
        override def getPageOfItemsAsJSON(tableName: String, pageNbr: Int, maxPageSize: Int, sortField: String, sortDescending: Boolean): List[String] =
            rows.values.toList
        override def getItemsSinceAsJSON(tableName: String, sortField: String, sinceEpochMs: Long, maxItems: Int): List[String] = Nil
    }

    // ---- environment --------------------------------------------------------

    private def env(auditOn: Boolean): DatrisEnvironment = DatrisEnvironment(
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
        useApiKeys = false,
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
        useUserAuth = true,
        userTableName = "test-user",
        useAuditLog = auditOn,
        auditLogTableName = "test-audit-log"
    )

    private val Probe = "audit-probe"
    private val now = java.time.Instant.now().toString
    private def user(name: String, role: String, hash: String): User = User(name, hash, role, now, now, null)
    private val adminUser = user("admin", User.RoleAdmin, "existing-admin-hash")

    private var savedValues: DatrisEnvironment = _
    private var db: FakeUserDb = _
    private val captured = mutable.ArrayBuffer[AuditEntry]()

    private def install(auditOn: Boolean): Unit = {
        val e = env(auditOn)
        DatrisEnvironment.init(e)
        TenantContext.set(e)
    }

    override def beforeEach(): Unit = {
        savedValues = DatrisEnvironment.values
        captured.clear()
        db = new FakeUserDb
        UserStore.dbOverride = db
        AuditLog.sinkOverride = (e: AuditEntry) => captured.synchronized { captured += e; () }
        install(auditOn = true)
    }

    override def afterEach(): Unit = {
        AuditLog.sinkOverride = null
        UserStore.dbOverride = null
        UserContext.clear()
        TenantContext.clear()
        DatrisEnvironment.values = savedValues
    }

    // ---- controller path ------------------------------------------------------

    private def req(username: String): HttpServletRequest = {
        val r = mock(classOf[HttpServletRequest])
        when(r.getMethod).thenReturn("PATCH")
        when(r.getRequestURI).thenReturn("/api/v1/auth/users/" + username)
        when(r.getRemoteAddr).thenReturn("127.0.0.1")
        r
    }

    /** Admin session acting on the throwaway user, as from Configuration → Users. */
    private def patch(body: String): (Int, HttpServletRequest) = {
        db.put(adminUser)
        db.put(user(Probe, User.RoleViewer, PasswordHasher.hash("probe-old-password")))
        UserContext.set(adminUser)
        val r = req(Probe)
        val resp = new AuthAPIController().patchUser(Probe, body, r)
        (resp.getStatusCode.value, r)
    }

    private def userRows: Seq[AuditEntry] = captured.filter(_.category == "user").toSeq

    test("PATCH with resetPassword records user/reset-password for that username") {
        val (status, r) = patch("""{"resetPassword":true}""")
        assert(status == 200)
        assert(db.user(Probe).exists(_.mustSetPassword), "the reset itself must still happen")

        val rows = userRows
        assert(rows.map(_.action) == Seq("reset-password"), s"captured: ${captured.map(e => e.category + "/" + e.action)}")
        val e = rows.head
        assert(e.resourceType.contains("user"))
        assert(e.resourceName.contains(Probe))
        assert(e.outcome == "success")
        assert(e.actor.label == "session:admin", "the acting admin session is the actor")
        // AuditLog.record marks the request so the interceptor adds no generic row.
        verify(r).setAttribute(eqTo(AuditLog.RecordedAttr), any())
    }

    test("PATCH with role records user/update with metadata.role") {
        val (status, r) = patch("""{"role":"editor"}""")
        assert(status == 200)
        assert(db.user(Probe).map(_.role).contains(User.RoleEditor))

        val rows = userRows
        assert(rows.map(_.action) == Seq("update"), s"captured: ${captured.map(e => e.category + "/" + e.action)}")
        val e = rows.head
        assert(e.resourceType.contains("user"))
        assert(e.resourceName.contains(Probe))
        assert(e.actor.label == "session:admin")
        val role = e.metadata.flatMap(m => Option(m.get("role"))).map(_.getAsString)
        assert(role.contains("editor"), s"metadata: ${e.metadata}")
        val from = e.metadata.flatMap(m => Option(m.get("from"))).map(_.getAsString)
        assert(from.contains(User.RoleViewer), s"metadata.from is the previous role: ${e.metadata}")
        verify(r).setAttribute(eqTo(AuditLog.RecordedAttr), any())
    }

    test("PATCH with both records both rows") {
        val (status, _) = patch("""{"role":"editor","resetPassword":true}""")
        assert(status == 200)
        val rows = userRows
        assert(rows.size == 2, s"captured: ${captured.map(e => e.category + "/" + e.action)}")
        assert(rows.map(_.action).toSet == Set("update", "reset-password"))
        assert(rows.forall(_.resourceName.contains(Probe)))
        assert(rows.forall(_.actor.label == "session:admin"))
        val update = rows.find(_.action == "update").get
        assert(update.metadata.flatMap(m => Option(m.get("role"))).map(_.getAsString).contains("editor"))
    }

    test("PATCH where the role change succeeds and the password reset fails keeps the update row and lets the interceptor record the failure") {
        db.failPasswordClear = true
        val (status, r) = patch("""{"role":"editor","resetPassword":true}""")
        assert(status == 500)
        assert(db.user(Probe).map(_.role).contains(User.RoleEditor))
        assert(userRows.map(_.action) == Seq("update"), s"captured: ${captured.map(e => e.category + "/" + e.action)}")
        // The recorded mark is cleared so the interceptor writes its failure row for the 500.
        verify(r).removeAttribute(AuditLog.RecordedAttr)
    }

    test("PATCH with an empty body records nothing from the controller") {
        val (status, r) = patch("""{}""")
        assert(status == 200)
        assert(captured.isEmpty, s"captured: ${captured.map(e => e.category + "/" + e.action)}")
        // The interceptor's generic user/update row must still be written.
        verify(r, never()).setAttribute(eqTo(AuditLog.RecordedAttr), any())
    }

    test("with the audit log off, PATCH resets and changes role exactly as before and records nothing") {
        install(auditOn = false)
        val (status, r) = patch("""{"role":"editor","resetPassword":true}""")
        assert(status == 200)
        assert(db.user(Probe).exists(u => u.mustSetPassword && u.role == User.RoleEditor))
        assert(captured.isEmpty)
        verify(r, never()).setAttribute(eqTo(AuditLog.RecordedAttr), any())
    }

    // ---- startup path ---------------------------------------------------------

    /** Runs the startup user bootstrap and returns every formatted log line it
      * wrote, so the generated password can be recovered from the one place
      * it is allowed to appear. */
    private def bootstrapCapturingLog(): Seq[String] = {
        val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).asInstanceOf[ch.qos.logback.classic.Logger]
        val savedLevel = root.getLevel
        val appender = new ListAppender[ILoggingEvent]()
        appender.start()
        root.addAppender(appender)
        root.setLevel(ch.qos.logback.classic.Level.INFO)
        try {
            StartupRunner.bootstrapUsers()
            appender.list.asScala.map(_.getFormattedMessage).toSeq
        } finally {
            root.detachAppender(appender)
            root.setLevel(savedLevel)
        }
    }

    test(
        "an existing account with a null hash is rotated and records user/rotate-password with reason no-password-hash and the password string absent from the entry"
    ) {
        db.put(adminUser)
        db.put(user(Probe, User.RoleViewer, null))

        val lines = bootstrapCapturingLog()

        val Rotated = ("User '" + Probe + "' had no password set.*Assigned a bootstrap password: (\\S+)").r.unanchored
        val password = lines.collectFirst { case Rotated(pw) => pw }
            .getOrElse(fail("the one-time rotation log line is missing: " + lines.mkString("\n")))
        assert(db.user(Probe).exists(!_.mustSetPassword), "the account must actually be rotated")

        val rows = captured.filter(e => e.category == "user" && e.action == "rotate-password").toSeq
        assert(rows.size == 1, s"captured: ${captured.map(e => e.category + "/" + e.action + " " + e.resourceName)}")
        val e = rows.head
        assert(e.resourceType.contains("user"))
        assert(e.resourceName.contains(Probe))
        assert(e.actor.actorType == "system" && e.actor.label == "system")
        assert(e.metadata.flatMap(m => Option(m.get("reason"))).map(_.getAsString).contains("no-password-hash"), s"metadata: ${e.metadata}")
        val json = e.toJson.toString
        assert(!json.contains(password), "the bootstrap password must never enter the audit log: " + json)

        // An account that already had a hash is not touched and not recorded.
        assert(db.user("admin").map(_.passwordHash).contains("existing-admin-hash"))
        assert(!captured.exists(e => e.action == "rotate-password" && e.resourceName.contains("admin")))
        // The password appears nowhere in anything the audit log captured.
        assert(!captured.exists(_.toJson.toString.contains(password)))
    }

    test("an empty user table seeds admin and records user/create with reason bootstrap") {
        assert(db.rows.isEmpty)

        val lines = bootstrapCapturingLog()

        val Seeded = "Bootstrap login.*password: (\\S+)".r.unanchored
        val password = lines.collectFirst { case Seeded(pw) => pw }
            .getOrElse(fail("the one-time seed log line is missing: " + lines.mkString("\n")))
        assert(db.user("admin").exists(u => u.role == User.RoleAdmin && !u.mustSetPassword))

        val rows = captured.filter(e => e.category == "user" && e.action == "create").toSeq
        assert(rows.size == 1, s"captured: ${captured.map(e => e.category + "/" + e.action + " " + e.resourceName)}")
        val e = rows.head
        assert(e.resourceType.contains("user"))
        assert(e.resourceName.contains("admin"))
        assert(e.actor.actorType == "system" && e.actor.label == "system")
        assert(e.metadata.flatMap(m => Option(m.get("reason"))).map(_.getAsString).contains("bootstrap"), s"metadata: ${e.metadata}")
        assert(!captured.exists(_.toJson.toString.contains(password)), "the bootstrap password must never enter the audit log")
    }
}
