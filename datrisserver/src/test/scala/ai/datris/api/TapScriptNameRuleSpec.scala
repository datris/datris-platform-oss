package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.auth.ScopeDenialFixtures
import ai.datris.model.{DatrisEnvironment, User}
import ai.datris.util.{
    APIKeyValidator,
    ObjectStoreUtility,
    ScriptPaths,
    SecretNames,
    TapFixResult,
    TapGenerateResult,
    TapOptimizeResult,
    TapReviewResult,
    TapScriptGenerator
}
import com.google.gson.JsonParser
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.{doAnswer, mock}
import org.scalatest.BeforeAndAfterEach
import org.scalatest.funsuite.AnyFunSuite
import org.springframework.http.ResponseEntity

import scala.collection.mutable

/** One tap-name rule on every path that stores a tap script: the generate,
  * fix, review and optimize routes answer a tap name whose script key would
  * have an empty, `.` or `..` segment with the same `400` and message as
  * `POST /tap/script` ([[ScriptPaths.tapNameRefusal]]), and store nothing.
  * Ordinary names are stored under `tap-scripts/<name>_<id>.py` as before.
  *
  * The routes run through their [[TapAPIController.TapAiIO]] seam; each fake
  * model step ends as the production one does, with
  * `TapScriptGenerator.storeScript` over an in-memory object store. */
class TapScriptNameRuleSpec extends AnyFunSuite with BeforeAndAfterEach {

    private val env: DatrisEnvironment = ScopeDenialFixtures.auditEnv()
    private var savedValues: DatrisEnvironment = _

    override def beforeEach(): Unit = {
        savedValues = DatrisEnvironment.values
        DatrisEnvironment.init(env)
        ScopeDenialFixtures.install(env)
    }

    override def afterEach(): Unit = ScopeDenialFixtures.install(savedValues)

    private val admin = APIKeyValidator.resolveFromSession(User("ada", "h", User.RoleAdmin, null, null, null))

    private val OldPath = "tap-scripts/orders_0ld0ld00.py"
    private val OldScript = "def fetch():\n    return [{'a': 1}]"
    private val NewScript = "def fetch():\n    return [{'a': 2}]"

    private def jl(xs: String*): java.util.List[String] = {
        val l = new java.util.ArrayList[String]()
        xs.foreach(l.add)
        l
    }

    /** The object store writes, and the four model steps ending in the store write. */
    private class World {
        val writes: mutable.Buffer[String] = mutable.Buffer.empty
        val objects: ObjectStoreUtility = mock(classOf[ObjectStoreUtility])
        doAnswer { i => writes += i.getArgument[String](1); null }.when(objects).writeBucketObject(anyString(), anyString(), anyString())

        private def store(name: String, old: String): String = TapScriptGenerator.storeScript(name, NewScript, old, objects = objects)

        val io: TapAPIController.TapAiIO = TapAPIController.TapAiIO(
            readTap = _ => null,
            readScript = _ => None,
            write = _ => (),
            writeVersioned = (t, _, _) => t,
            secretType = _ => Some(Some(SecretNames.TapScopeTap)),
            secretOwner = _ => None,
            generate = (_, n, old, _, _) => TapGenerateResult(NewScript, jl(), store(n, old), jl(), "anthropic", "claude-opus-5-5"),
            fix = (n, _, _, _, _, old, _) => TapFixResult(NewScript, jl(), store(Option(n).getOrElse("tap"), old)),
            review = (n, _, _, _, _, old, _) => TapReviewResult(NewScript, jl(), store(n, old), jl("handle HTTP 429"), rewritten = true),
            optimize = (n, _, _, _, _, old, _) => TapOptimizeResult(NewScript, jl(), store(n, old), jl("batch the requests"))
        )
    }

    private val Routes = Seq("generate", "fix", "review", "optimize")

    private def body(route: String, tapName: String): java.util.Map[String, Object] = {
        val m = new java.util.HashMap[String, Object]()
        m.put("tapName", tapName)
        m.put("oldScriptPath", OldPath)
        route match {
            case "generate" =>
                m.put("description", "fetch the orders")
                m.put("tapType", "structured")
            case "fix" =>
                m.put("script", OldScript)
                m.put("diagnosis", "the endpoint moved")
                m.put("priorIterations", "[]")
            case _ =>
                m.put("script", OldScript)
                m.put("recordCount", "5")
                m.put("durationMs", "120")
                m.put("priorIterations", "[]")
        }
        m
    }

    private def strings(m: java.util.Map[String, Object]): java.util.Map[String, String] = {
        val s = new java.util.HashMap[String, String]()
        m.forEach((k, v) => s.put(k, if (v == null) null else v.toString))
        s
    }

    private def call(route: String, tapName: String, w: World): ResponseEntity[String] = {
        val c = new TapAPIController()
        val b = body(route, tapName)
        val r = ScopeDenialFixtures.request(admin, "POST", "/api/v1/tap/" + route)._1
        route match {
            case "generate" => c.generateScriptWith(strings(b), r, w.io)
            case "fix" => c.fixScriptWith(strings(b), r, w.io)
            case "review" => c.reviewScriptWith(b, r, w.io)
            case "optimize" => c.optimizeScriptWith(b, r, w.io)
        }
    }

    /** Names whose script key would have an empty, `.` or `..` segment. */
    private val Refused = Seq("a//b", "/orders", "./orders", "../orders", "a/./b", "a/../b")

    /** Names that keep working as before. */
    private val Accepted = Seq("orders", "sales.orders", "a/b", "a/", "..", "x/..", "...", "pipelines/orders")

    for (route <- Routes) {
        test(route + ": a tap name with an empty, '.' or '..' segment is a 400 with the upload route's message, and nothing is stored") {
            for (name <- Refused) {
                val w = new World
                val r = call(route, name, w)
                assert(r.getStatusCode.value == 400, route + " " + name + ": " + r.getStatusCode + " " + r.getBody)
                val error = JsonParser.parseString(r.getBody).getAsJsonObject.get("error").getAsString
                assert(error == ScriptPaths.tapNameRefusal(name).get, route + " " + name + ": " + error)
                assert(r.getBody == ApiErrors.errorBody(ScriptPaths.tapNameRefusal(name).get))
                assert(w.writes.isEmpty, route + " " + name + ": " + w.writes)
            }
        }

        test(route + ": an ordinary tap name is stored under tap-scripts/<name>_<id>.py and answered 200 as before") {
            for (name <- Accepted) {
                val w = new World
                val r = call(route, name, w)
                assert(r.getStatusCode.value == 200, route + " " + name + ": " + r.getBody)
                val path = JsonParser.parseString(r.getBody).getAsJsonObject.get("scriptPath").getAsString
                assert(path.matches(java.util.regex.Pattern.quote("tap-scripts/" + name + "_") + "[0-9a-f]{8}\\.py"), path)
                assert(w.writes.toList == List(path), route + " " + name + ": " + w.writes)
            }
        }
    }

    test("every refused name above is refused by the rule, and every accepted name passes it") {
        assert(Refused.forall(n => ScriptPaths.tapNameRefusal(n).isDefined), Refused.filter(n => ScriptPaths.tapNameRefusal(n).isEmpty))
        assert(Accepted.forall(n => ScriptPaths.tapNameRefusal(n).isEmpty))
    }
}
