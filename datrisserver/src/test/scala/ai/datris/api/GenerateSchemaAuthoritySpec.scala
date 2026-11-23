package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.audit.{AuditEntry, AuditLog}
import ai.datris.auth.CapabilityScopes
import ai.datris.config.TenantInterceptor
import ai.datris.incident.RecoveryKey
import ai.datris.model._
import ai.datris.util.{APIKeyValidator, AiSampleValues, InMemoryUserDb}
import ai.datris.util.InMemoryUserDb.grants
import com.google.gson.{Gson, JsonParser}
import jakarta.servlet.http.HttpServletRequest
import org.mockito.Mockito.{mock, when}
import org.scalatest.BeforeAndAfterEach
import org.scalatest.funsuite.AnyFunSuite
import org.springframework.http.ResponseEntity

import scala.collection.mutable

/** Story: Generating a validation schema needs the right to author pipelines
  * and never replaces a schema another pipeline depends on
  * (plans/stories/generate-schema-write-authority.md). Owner decisions D2 (a):
  * a schema name that pipelines use, stored or not yet stored, is written
  * only when the caller may update every one of them; D3: the refusal names no pipeline, for every
  * caller. The route's capability (D1) is pinned in
  * GenerateSchemaRouteAuthoritySpec.
  *
  * The handler reads the pipeline configs, calls the model and writes the
  * object store, so it gets a seam the Spring method delegates to (the same
  * style as `CatalogEnableJob.Deps`); the live implementation uses
  * `ObjectStoreUtil`, `PipelineConfigIO.readAll` and
  * `AISchemaUtil.generateJsonSchema` / `generateXsdSchema`:
  * {{{
  * object ConfigAPIController {
  *   trait GenerateSchemaDeps {
  *     /** Every stored pipeline config. Throws when they cannot be read. */
  *     def pipelines(): Seq[PipelineConfig]
  *     /** The model call; schemaType is "json-schema" or "xsd". */
  *     def generate(schemaType: String, sampleData: String): String
  *     def write(bucket: String, key: String, bytes: Array[Byte]): Unit
  *   }
  *   /** POST /config/generate-schema after the key check: the request
  *     * checks of parseGenerateSchema (400, unchanged), then the in-use rule,
  *     * then the model and the write. A failed pipelines read is a
  *     * 500 with nothing generated or written. */
  *   def generateSchemaWith(request: HttpServletRequest, body: String, deps: GenerateSchemaDeps): ResponseEntity[String]
  *   /** Pure: Some(409 {"error": "A validation schema named '<file>' is
  *     * already in use. Choose another schema name."}) when some pipeline of
  *     * `referencing` fails `mayUpdate`, whether or not the file is stored
  *     * yet (a pipeline naming a file not yet stored would start validating
  *     * against it); None otherwise. */
  *   def schemaOverwriteRefusal(file: String, referencing: Seq[PipelineConfig],
  *                              mayUpdate: PipelineConfig => Boolean): Option[ResponseEntity[String]]
  * }
  * }}}
  * A pipeline uses the object when its `dataQuality.validationSchema`
  * resolves to it exactly as `DataQuality` and `SchemaValidationUtil` resolve
  * it at run time (`s3://` URL as given, else
  * `s3://<env>-config/validation-schema/<value>`, then bucket and key through
  * the URI). Object keys are case-sensitive in the shipped store (MinIO on a
  * named volume), so a name differing only in case is another object.
  * Object keys are compared after collapsing repeated `/` and dropping a
  * leading `/`; a value that cannot be parsed names no object.
  * "May update" is the rule the pipeline update routes apply: the identity
  * grants `pipeline:update` on the pipeline's catalog and owner and, for an
  * API key, may also read it (`CallerVisibility.storedWriteDenial`); no
  * identity: yes. A caller who may update every pipeline
  * (`pipelineReadNeeded` false) is never refused and the stored pipelines
  * are not read for it. */
class GenerateSchemaAuthoritySpec extends AnyFunSuite with BeforeAndAfterEach {

    import ConfigAPIController.{generateSchemaWith, pipelineReadNeeded, pipelinesUsing, schemaOverwriteRefusal, GenerateSchemaDeps}

    private var savedEnv: DatrisEnvironment = _
    private val captured = mutable.ArrayBuffer[AuditEntry]()

    override def beforeEach(): Unit = {
        savedEnv = DatrisEnvironment.values
        val e = InMemoryUserDb.env(useAuditLog = true)
        DatrisEnvironment.init(e)
        TenantContext.set(e)
        captured.clear()
        AuditLog.sinkOverride = (a: AuditEntry) => captured.synchronized { captured += a; () }
    }

    override def afterEach(): Unit = {
        AuditLog.sinkOverride = null
        UserContext.clear()
        TenantContext.clear()
        DatrisEnvironment.values = savedEnv
    }

    // ------------------------------------------------------------ fakes

    private val Bucket = "test-config"
    private val GeneratedSchema = """{"$schema":"http://json-schema.org/draft-04/schema#","type":"array"}"""
    private val GeneratedXsd = """<xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema"/>"""

    /** `stored` names what the store holds in each scenario; the in-use rule
      * does not depend on it, so the handler never reads it. */
    private class FakeDeps(
        val stored: Set[String] = Set.empty,
        configs: Seq[PipelineConfig] = Nil,
        pipelinesFail: Boolean = false
    ) extends GenerateSchemaDeps {
        val generated = mutable.ArrayBuffer[(String, String)]()
        val written = mutable.ArrayBuffer[(String, String, String)]()
        var pipelineReads = 0
        override def pipelines(): Seq[PipelineConfig] = {
            pipelineReads += 1
            if (pipelinesFail) throw new RuntimeException("pipeline configs unavailable")
            configs
        }
        override def generate(schemaType: String, sampleData: String): String = {
            generated += (schemaType -> sampleData)
            if (schemaType == "xsd") GeneratedXsd else GeneratedSchema
        }
        override def write(bucket: String, key: String, bytes: Array[Byte]): Unit =
            written += ((bucket, key, new String(bytes, "UTF-8")))
        def untouched: Boolean = generated.isEmpty && written.isEmpty
    }

    private def req(rk: ResolvedKey): HttpServletRequest = {
        val r = mock(classOf[HttpServletRequest])
        when(r.getMethod).thenReturn("POST")
        when(r.getRequestURI).thenReturn("/api/v1/config/generate-schema")
        when(r.getRemoteAddr).thenReturn("127.0.0.1")
        when(r.getAttribute(TenantInterceptor.ResolvedKeyAttr)).thenAnswer(_ => rk)
        r
    }

    private def session(u: User): ResolvedKey = APIKeyValidator.resolveFromSession(u)
    private val admin = req(session(User("root", "h", User.RoleAdmin, null, null, null)))
    private val editor = req(session(User("ed", "h", User.RoleEditor, null, null, null)))
    private val keysOff = req(ResolvedKey(None, "anonymous", Seq(Capability.FullAccess), isLegacyFullAccess = true))
    private val noIdentity = req(null)

    /** Edit in Marketing, view in Sales, nothing in HR. */
    private val bob = req(
        session(
            User("bob", "h", User.RoleEditor, null, null, null, restricted = true, catalogGrants = grants("Marketing" -> "edit", "Sales" -> "view"))
        )
    )
    private def key(label: String, caps: String*): HttpServletRequest =
        req(ResolvedKey(None, label, Capability.parseList(caps), isLegacyFullAccess = false))
    private val marketingKey = key("mkt-builder", "pipeline:read:catalog=Marketing", "pipeline:create:catalog=Marketing", "pipeline:update:catalog=Marketing")
    private val ownKey = key("own-builder", "pipeline:read:owner=self", "pipeline:create", "pipeline:update:owner=self")
    private val authorKey = key("author", "pipeline:read", "pipeline:create", "pipeline:update")

    /** Stored capabilities as key resolution splits them (applied, inert). */
    private def storedRk(label: String, caps: String*): ResolvedKey = {
        val (applied, inert) = CapabilityScopes.partition(Capability.parseList(caps))
        ResolvedKey(None, label, applied, isLegacyFullAccess = false, inertCapabilities = inert)
    }
    private val fullAccessRk = ResolvedKey(None, "full", Seq(Capability.FullAccess), isLegacyFullAccess = false)
    private val legacyRk = ResolvedKey(None, "legacy", Seq(Capability.FullAccess), isLegacyFullAccess = true)
    private val recoveryRk = ResolvedKey(None, RecoveryKey.Label, Capability.parseList(RecoveryKey.Capabilities), isLegacyFullAccess = false)
    private val wildcardRk = ResolvedKey(None, "mkt-wild", Capability.parseList(Seq("*:*:catalog=Marketing")), isLegacyFullAccess = false)

    /** Unscoped pipeline update, pipeline read limited to Marketing. */
    private val readLimitedRk = ResolvedKey(
        None,
        "upd-everywhere",
        Capability.parseList(Seq("pipeline:update", "pipeline:create", "pipeline:read:catalog=Marketing")),
        isLegacyFullAccess = false
    )

    /** pipeline:update scoped by a key the platform does not apply on it (inert). */
    private val inertRk = storedRk("inert-upd", "pipeline:read", "pipeline:update:collection=docs")
    private val fullAccessKey = req(fullAccessRk)
    private val legacyKey = req(legacyRk)
    private val recoveryKey = req(recoveryRk)
    private val wildcardKey = req(wildcardRk)
    private val readLimitedKey = req(readLimitedRk)
    private val inertKey = req(inertRk)

    private def inMode[T](allow: Boolean)(f: => T): T = {
        val prior = sys.props.get(CapabilityScopes.InertScopesProperty)
        if (allow) sys.props(CapabilityScopes.InertScopesProperty) = CapabilityScopes.InertScopesAllow
        else sys.props -= CapabilityScopes.InertScopesProperty
        try f
        finally prior match {
                case Some(v) => sys.props(CapabilityScopes.InertScopesProperty) = v
                case None => sys.props -= CapabilityScopes.InertScopesProperty
            }
    }

    private def pipe(name: String, catalog: String, schema: String, owner: String = "someone"): PipelineConfig =
        PipelineConfig(name = name, catalog = catalog, createdByKeyLabel = owner, dataQuality = DataQuality(validationSchema = schema))

    private val mktOrders = pipe("mkt_orders", "Marketing", "orders.json", owner = "mkt-builder")
    private val salesOrders = pipe("sales_orders", "Sales", "orders.json")
    private val hrPayroll = pipe("hr_payroll_feed", "HR", "orders.json")
    private val ownOrders = pipe("own_orders", "Any", "orders.json", owner = "own-builder")
    private val unrelated = pipe("other_feed", "HR", "other.json")

    private val Stored = Set("validation-schema/orders.json", "validation-schema/other.json")
    private val InUse = "A validation schema named 'orders.json' is already in use. Choose another schema name."

    private def body(name: String = "orders", schemaType: String = "json-schema", sample: String = """[{"id":1}]"""): String = {
        val o = new com.google.gson.JsonObject
        o.addProperty("type", schemaType)
        o.addProperty("name", name)
        o.addProperty("sampleData", sample)
        o.toString
    }

    /** The response today's handler builds for a generated file. */
    private def todaysResponse(filename: String, schema: String): String = {
        val m = new java.util.LinkedHashMap[String, Any]()
        m.put("filename", filename)
        m.put("path", "s3://" + Bucket + "/validation-schema/" + filename)
        m.put("schema", schema)
        if (!AiSampleValues.enabled) m.put("valuesWithheld", true)
        new Gson().toJson(m)
    }

    private def status(r: ResponseEntity[String]): Int = r.getStatusCode.value
    private def error(r: ResponseEntity[String]): String = JsonParser.parseString(r.getBody).getAsJsonObject.get("error").getAsString

    private def assertAllowed(r: ResponseEntity[String], d: FakeDeps, file: String = "orders.json", schemaType: String = "json-schema"): Unit = {
        assert(status(r) == 200, r.getBody)
        val schema = if (schemaType == "xsd") GeneratedXsd else GeneratedSchema
        assert(r.getBody == todaysResponse(file, schema), r.getBody)
        assert(d.generated.toList == List(schemaType -> """[{"id":1}]"""))
        assert(d.written.toList == List((Bucket, "validation-schema/" + file, schema)))
    }

    private def assertInUse(r: ResponseEntity[String], d: FakeDeps, hidden: Seq[String] = Nil): Unit = {
        assert(status(r) == 409, s"${status(r)} ${r.getBody}")
        assert(error(r) == InUse, r.getBody)
        assert(d.untouched, s"refused, yet generated ${d.generated} and wrote ${d.written}")
        (hidden ++ Seq("hr_payroll_feed", "sales_orders", "mkt_orders", "own_orders", "HR", "Sales", "Marketing")).foreach { n =>
            assert(!r.getBody.contains(n), s"the refusal names '$n': ${r.getBody}")
        }
    }

    // ------------------------------------------------------------ Acceptance 1: who may overwrite

    test("an editor generating under a new name gets 200 and the object is written exactly as before") {
        val d = new FakeDeps(stored = Stored, configs = Seq(salesOrders, hrPayroll))
        assertAllowed(generateSchemaWith(editor, body("fresh_feed"), d), d, file = "fresh_feed.json")
        val x = new FakeDeps(stored = Stored, configs = Seq(salesOrders))
        assertAllowed(generateSchemaWith(editor, body("fresh_feed", "xsd"), x), x, file = "fresh_feed.xsd", schemaType = "xsd")
    }

    test("generating over an existing schema that no pipeline uses gets 200") {
        for (r <- Seq(editor, bob, marketingKey, ownKey, authorKey)) {
            val d = new FakeDeps(stored = Stored, configs = Seq(unrelated))
            assertAllowed(generateSchemaWith(r, body(), d), d)
        }
    }

    test("generating over a schema used only by pipelines the caller may update gets 200") {
        val cases = Seq(
            "restricted session, edit grant" -> (bob, Seq(mktOrders)),
            "catalog key" -> (marketingKey, Seq(mktOrders)),
            "key limited to what it created" -> (ownKey, Seq(ownOrders)),
            "unscoped authoring key" -> (authorKey, Seq(salesOrders, hrPayroll)),
            "editor" -> (editor, Seq(salesOrders, hrPayroll, mktOrders)),
            "admin" -> (admin, Seq(salesOrders, hrPayroll)),
            "keys-off" -> (keysOff, Seq(salesOrders, hrPayroll)),
            "no identity" -> (noIdentity, Seq(salesOrders, hrPayroll)),
            "full-access key" -> (fullAccessKey, Seq(salesOrders, hrPayroll)),
            "legacy key" -> (legacyKey, Seq(salesOrders, hrPayroll)),
            "recovery key" -> (recoveryKey, Seq(salesOrders, hrPayroll, mktOrders)),
            "scoped wildcard key" -> (wildcardKey, Seq(mktOrders)),
            "unscoped update, read limited to the catalog" -> (readLimitedKey, Seq(mktOrders))
        )
        cases.foreach { case (what, (r, used)) =>
            val d = new FakeDeps(stored = Stored, configs = used :+ unrelated)
            withClue(what + ": ") { assertAllowed(generateSchemaWith(r, body(), d), d) }
        }
    }

    test("a restricted session cannot replace a schema used by a pipeline in another catalog: 409, no model call, object unchanged") {
        // Visible but not editable (Sales: view).
        val visible = new FakeDeps(stored = Stored, configs = Seq(mktOrders, salesOrders))
        val rVisible = generateSchemaWith(bob, body(), visible)
        assertInUse(rVisible, visible)
        // Not visible at all (HR: no grant): the same answer, word for word.
        val hidden = new FakeDeps(stored = Stored, configs = Seq(mktOrders, hrPayroll))
        val rHidden = generateSchemaWith(bob, body(), hidden)
        assertInUse(rHidden, hidden)
        assert(rHidden.getBody == rVisible.getBody)
        // An uncataloged pipeline is outside every grant.
        val blank = new FakeDeps(stored = Stored, configs = Seq(pipe("loose_feed", null, "orders.json")))
        assertInUse(generateSchemaWith(bob, body(), blank), blank, hidden = Seq("loose_feed"))
    }

    test("a catalog-limited key cannot replace a schema used by a pipeline outside its catalog: 409, no model call, object unchanged") {
        val d = new FakeDeps(stored = Stored, configs = Seq(mktOrders, hrPayroll))
        val r = generateSchemaWith(marketingKey, body(), d)
        assertInUse(r, d)
        // Same text as the restricted session gets (D3: for every caller).
        val viaBob = new FakeDeps(stored = Stored, configs = Seq(hrPayroll))
        assert(generateSchemaWith(bob, body(), viaBob).getBody == r.getBody)
    }

    test("a key limited to what it created cannot replace a schema someone else's pipeline uses") {
        val d = new FakeDeps(stored = Stored, configs = Seq(ownOrders, salesOrders))
        assertInUse(generateSchemaWith(ownKey, body(), d), d)
    }

    test("a key with unscoped pipeline update but pipeline read limited to one catalog cannot replace a schema a pipeline elsewhere uses") {
        val d = new FakeDeps(stored = Stored, configs = Seq(mktOrders, hrPayroll))
        assertInUse(generateSchemaWith(readLimitedKey, body(), d), d)
        // A name used only inside the catalog it reads: allowed.
        val own = new FakeDeps(stored = Stored, configs = Seq(mktOrders, unrelated))
        assertAllowed(generateSchemaWith(readLimitedKey, body(), own), own)
    }

    test("a scoped wildcard key cannot replace a schema a pipeline outside its catalog uses") {
        val d = new FakeDeps(stored = Stored, configs = Seq(mktOrders, salesOrders))
        assertInUse(generateSchemaWith(wildcardKey, body(), d), d)
    }

    test("an inert-scoped pipeline:update grants no pipeline: any name a pipeline uses is refused, a free name is generated") {
        Seq(false, true).foreach { allow =>
            inMode(allow) {
                withClue(s"allow=$allow: ") {
                    val d = new FakeDeps(stored = Stored, configs = Seq(mktOrders))
                    assertInUse(generateSchemaWith(inertKey, body(), d), d)
                    val free = new FakeDeps(stored = Stored, configs = Seq(mktOrders))
                    assertAllowed(generateSchemaWith(inertKey, body("fresh_feed"), free), free, file = "fresh_feed.json")
                }
            }
        }
    }

    test("the legacy and recovery keys replace a schema whatever pipelines use it") {
        Seq("legacy key" -> legacyKey, "recovery key" -> recoveryKey).foreach { case (what, r) =>
            val d = new FakeDeps(stored = Stored, configs = Seq(salesOrders, hrPayroll, mktOrders))
            withClue(what + ": ") { assertAllowed(generateSchemaWith(r, body(), d), d) }
        }
    }

    // ------------------------------------------------------------ same object, other spelling

    test("a request name in path form is the stored object it reduces to") {
        Seq("sub/orders", "../orders", "  orders  ").foreach { n =>
            val d = new FakeDeps(stored = Stored, configs = Seq(hrPayroll))
            withClue(n + ": ") { assertInUse(generateSchemaWith(marketingKey, body(n), d), d) }
        }
    }

    test("a pipeline naming the same object by s3 URL or encoded name counts as using it") {
        Seq(
            "s3://test-config/validation-schema/orders.json",
            "s3://test-config/validation-schema/orders%2Ejson",
            "orders%2Ejson",
            "/orders.json",
            "s3://test-config/validation-schema//orders.json",
            "s3://test-config//validation-schema/orders.json"
        ).foreach { v =>
            val d = new FakeDeps(stored = Stored, configs = Seq(pipe("hr_payroll_feed", "HR", v)))
            withClue(v + ": ") { assertInUse(generateSchemaWith(marketingKey, body(), d), d) }
        }
    }

    test("a pipeline naming a different object does not block the name") {
        Seq(
            "Orders.json", // keys are case-sensitive: another object
            "orders.xsd", // generating json-schema writes orders.json
            "s3://other-config/validation-schema/orders.json",
            "s3://test-config/validation-schema/archive/orders.json",
            "validation-schema/orders.json" // resolves under validation-schema/validation-schema/
        ).foreach { v =>
            val d = new FakeDeps(
                stored = Stored ++ Set("validation-schema/Orders.json", "validation-schema/orders.xsd"),
                configs = Seq(pipe("hr_payroll_feed", "HR", v))
            )
            withClue(v + ": ") { assertAllowed(generateSchemaWith(marketingKey, body(), d), d) }
        }
        // A name whose generated file differs from the stored one.
        val d = new FakeDeps(stored = Stored, configs = Seq(hrPayroll))
        assertAllowed(generateSchemaWith(marketingKey, body("orders.json"), d), d, file = "orders.json.json")
        // Case variant of the request name: Orders.json is not orders.json.
        val c = new FakeDeps(stored = Stored, configs = Seq(hrPayroll))
        assertAllowed(generateSchemaWith(marketingKey, body("Orders"), c), c, file = "Orders.json")
    }

    test("a pipeline whose validation-schema value cannot be parsed names no object and blocks nothing") {
        Seq("orders json", "orders.json%", "s3://test config/validation-schema/orders.json").foreach { v =>
            val p = pipe("hr_payroll_feed", "HR", v)
            withClue(v + ": ") {
                assert(pipelinesUsing("orders.json", Bucket, Seq(p)).isEmpty)
                val d = new FakeDeps(stored = Stored, configs = Seq(p))
                assertAllowed(generateSchemaWith(marketingKey, body(), d), d)
            }
        }
    }

    test("an xsd name is checked against the .xsd object") {
        val d = new FakeDeps(stored = Set("validation-schema/orders.xsd"), configs = Seq(pipe("hr_payroll_feed", "HR", "orders.xsd")))
        val r = generateSchemaWith(marketingKey, body(schemaType = "xsd"), d)
        assert(status(r) == 409, r.getBody)
        assert(error(r) == "A validation schema named 'orders.xsd' is already in use. Choose another schema name.")
        assert(d.untouched)
    }

    // ------------------------------------------------------------ failures and order

    /** Callers the in-use rule can never refuse (the stored pipelines are not read). */
    private def skipCallers: Seq[(String, HttpServletRequest)] = Seq(
        "admin session" -> admin,
        "unrestricted editor session" -> editor,
        "keys-off" -> keysOff,
        "no identity" -> noIdentity,
        "full-access key" -> fullAccessKey,
        "legacy key" -> legacyKey,
        "recovery key" -> recoveryKey,
        "unscoped authoring key" -> authorKey
    )

    /** Callers the in-use rule can refuse (the stored pipelines are read). */
    private def readCallers: Seq[(String, HttpServletRequest)] = Seq(
        "restricted session with an edit grant" -> bob,
        "catalog-limited key" -> marketingKey,
        "key limited to what it created" -> ownKey,
        "scoped wildcard key" -> wildcardKey,
        "unscoped update with scoped read" -> readLimitedKey,
        "inert-scoped update" -> inertKey
    )

    test("pipelineReadNeeded: false for callers who may update every pipeline, true for everyone else") {
        Seq(false, true).foreach { allow =>
            inMode(allow) {
                val identity: HttpServletRequest => Option[ResolvedKey] = ai.datris.auth.CapabilityCheck.resolvedKey
                val wrong = skipCallers.collect { case (what, r) if pipelineReadNeeded(identity(r)) => what + ": read, expected skip" } ++
                    readCallers.collect { case (what, r) if !pipelineReadNeeded(identity(r)) => what + ": skip, expected read" }
                assert(wrong.isEmpty, s"allow=$allow" + wrong.mkString("\n", "\n", ""))
            }
        }
        assert(!pipelineReadNeeded(None))
    }

    test("with the pipeline read failing, a caller who may update every pipeline gets 200 and reads no pipeline") {
        skipCallers.foreach { case (what, r) =>
            val d = new FakeDeps(stored = Stored, configs = Seq(hrPayroll), pipelinesFail = true)
            withClue(what + ": ") {
                assertAllowed(generateSchemaWith(r, body(), d), d)
                assert(d.pipelineReads == 0)
            }
        }
    }

    test("with the pipeline read failing, every other caller gets a 500 with nothing generated or written") {
        readCallers.foreach { case (what, r) =>
            val d = new FakeDeps(stored = Stored, configs = Seq(hrPayroll), pipelinesFail = true)
            val res = generateSchemaWith(r, body(), d)
            withClue(what + ": ") {
                assert(status(res) == 500, res.getBody)
                assert(d.untouched)
                assert(d.pipelineReads == 1)
            }
        }
    }

    test("input errors keep their 400 text, come before the in-use rule and the model, and say nothing about use") {
        val bad = Seq(
            "" -> "Request body must be a JSON object with string fields: type, name, sampleData",
            "{not json" -> "Request body is not valid JSON",
            "{}" -> "Missing required field(s): type, name, sampleData",
            body(sample = "   ") -> "Sample data is required",
            body(name = " ") -> "Schema name is required",
            body(schemaType = "avro") -> "Invalid schema type: avro. Must be one of: json-schema, xsd",
            body(name = "a b!") -> "File name 'a b!' contains invalid characters. Allowed: letters, digits, . _ -"
        )
        bad.foreach { case (b, msg) =>
            // The name is in use by a pipeline the caller may not update ...
            val used = new FakeDeps(stored = Stored, configs = Seq(hrPayroll))
            val r = generateSchemaWith(marketingKey, b, used)
            assert(status(r) == 400, s"$b -> ${status(r)} ${r.getBody}")
            assert(r.getBody == ApiErrors.errorBody(msg), r.getBody)
            assert(used.untouched)
            // ... and the answer is the same as when it is not.
            val free = new FakeDeps()
            assert(generateSchemaWith(marketingKey, b, free).getBody == r.getBody)
        }
    }

    // ------------------------------------------------------------ the pure rule

    test("schemaOverwriteRefusal: refused exactly when a pipeline the caller may not update uses the name") {
        val yes: PipelineConfig => Boolean = _ => true
        val no: PipelineConfig => Boolean = _ => false
        val onlyMarketing: PipelineConfig => Boolean = _.catalog == "Marketing"
        assert(schemaOverwriteRefusal("orders.json", Nil, no).isEmpty)
        assert(schemaOverwriteRefusal("orders.json", Seq(mktOrders, hrPayroll), yes).isEmpty)
        assert(schemaOverwriteRefusal("orders.json", Seq(mktOrders), onlyMarketing).isEmpty)
        val r = schemaOverwriteRefusal("orders.json", Seq(mktOrders, hrPayroll), onlyMarketing)
        assert(r.isDefined)
        assert(status(r.get) == 409)
        assert(r.get.getBody == ApiErrors.errorBody(InUse), r.get.getBody)
        assert(!r.get.getBody.contains("hr_payroll_feed"))
        assert(schemaOverwriteRefusal("orders.json", Seq(salesOrders), no).get.getBody == r.get.getBody)
    }

    test("a name a pipeline the caller may not update uses is refused even before the file is stored") {
        // The pipeline names a file that does not exist yet; generating it
        // would start that pipeline validating against the caller's schema.
        val notStored = new FakeDeps(stored = Set.empty, configs = Seq(mktOrders, hrPayroll))
        assertInUse(generateSchemaWith(marketingKey, body(), notStored), notStored)
        val viaBob = new FakeDeps(stored = Set.empty, configs = Seq(salesOrders))
        assertInUse(generateSchemaWith(bob, body(), viaBob), viaBob)
        // A name only the caller's own pipelines use, not stored yet: allowed.
        val own = new FakeDeps(stored = Set.empty, configs = Seq(mktOrders))
        assertAllowed(generateSchemaWith(marketingKey, body(), own), own)
    }
}
