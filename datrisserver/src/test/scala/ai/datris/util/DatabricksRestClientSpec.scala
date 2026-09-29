package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.DatrisException
import com.google.gson.JsonObject
import org.scalatest.funsuite.AnyFunSuite

import java.nio.charset.StandardCharsets
import java.util.Base64
import scala.collection.mutable.ListBuffer

/** Story: Unity Catalog 3: lineage publish
  * (plans/stories/unity-catalog-3-lineage-publish.md), Acceptance bullet 1.
  *
  * Seams this spec pins (named in the story's Files section):
  *
  * {{{
  *   trait HttpTransport {            // package ai.datris.util, single abstract method
  *       def apply(method: String, url: String, headers: Map[String, String], body: String): (Int, String)
  *   }
  *   class DatabricksRestClient(
  *       creds: ResolvedDatabricksCredentials,
  *       transport: HttpTransport = ApacheTransport,
  *       clock: () => Long = System.currentTimeMillis   // epoch millis
  *   ) {
  *       def token(): String
  *       def get(path: String): JsonObject
  *       def post(path: String, json: JsonObject): JsonObject
  *       def patch(path: String, json: JsonObject): JsonObject
  *   }
  * }}}
  *
  * The fake below is a lambda (SAM conversion), so the method name on the
  * trait is free; the parameter list and `(status, body)` result are not.
  * Header names are matched case-insensitively. `body` is null (or empty)
  * for a GET.
  */
class DatabricksRestClientSpec extends AnyFunSuite {

    private val HOST = "dbc-a1b2c3d4-e5f6.cloud.databricks.com"
    private val API = "/api/2.0/lineage-tracking/external-metadata/datris-pipeline-orders"

    private case class Call(method: String, url: String, headers: Map[String, String], body: String) {
        def header(name: String): Option[String] = headers.collectFirst { case (k, v) if k.equalsIgnoreCase(name) => v }
    }

    /** Records every request. `/oidc/v1/token` answers with successive tokens
      * (`tok-1`, `tok-2`, ...) valid for `expiresIn` seconds; any other URL
      * answers `(apiStatus, apiBody)`. */
    private class FakeTransport(expiresIn: Int = 3600, apiStatus: Int = 200, apiBody: String = """{"name":"datris-pipeline-orders"}""") {
        val calls = new ListBuffer[Call]()
        private var issued = 0
        val transport: HttpTransport = (method: String, url: String, headers: Map[String, String], body: String) => {
            calls += Call(method, url, headers, body)
            if (url.contains("/oidc/v1/token")) {
                issued += 1
                (200, s"""{"access_token":"tok-$issued","token_type":"Bearer","expires_in":$expiresIn,"scope":"all-apis"}""")
            } else (apiStatus, apiBody)
        }
        def tokenCalls: List[Call] = calls.filter(_.url.contains("/oidc/v1/token")).toList
        def apiCalls: List[Call] = calls.filterNot(_.url.contains("/oidc/v1/token")).toList
    }

    private def m2m(host: String = HOST) =
        ResolvedDatabricksCredentials(host = host, clientId = Some("sp-client-id"), clientSecret = Some("sp-secret"), token = None)
    private def pat(host: String = HOST) =
        ResolvedDatabricksCredentials(host = host, clientId = None, clientSecret = None, token = Some("dapi-pat-123"))

    private class ManualClock(var now: Long = 1790000000000L) { val fn: () => Long = () => now }

    test("client-credentials token request posts grant_type and scope=all-apis with Basic auth to {host}/oidc/v1/token") {
        val fake = new FakeTransport()
        val client = new DatabricksRestClient(m2m(), fake.transport, new ManualClock().fn)
        val res = client.get(API)
        assert(res != null && res.get("name").getAsString == "datris-pipeline-orders")

        assert(fake.tokenCalls.size == 1, fake.calls.mkString("\n"))
        val t = fake.tokenCalls.head
        assert(t.method.equalsIgnoreCase("POST"), t)
        assert(t.url == s"https://$HOST/oidc/v1/token", t.url)
        val expectedBasic = "Basic " + Base64.getEncoder.encodeToString("sp-client-id:sp-secret".getBytes(StandardCharsets.UTF_8))
        assert(t.header("Authorization").contains(expectedBasic), t.headers)
        assert(t.header("Content-Type").exists(_.toLowerCase.startsWith("application/x-www-form-urlencoded")), t.headers)
        val form = t.body.split("&").toSet
        assert(form.contains("grant_type=client_credentials"), t.body)
        assert(form.contains("scope=all-apis"), t.body)
        assert(!t.body.contains("sp-secret"), "the client secret travels in Basic auth, not the form body")

        // The token is then used as a Bearer on the API call.
        assert(fake.apiCalls.size == 1, fake.calls.mkString("\n"))
        val a = fake.apiCalls.head
        assert(a.method.equalsIgnoreCase("GET"), a)
        assert(a.url == s"https://$HOST$API", a.url)
        assert(a.header("Authorization").contains("Bearer tok-1"), a.headers)
        // Token request precedes the API call.
        assert(fake.calls.head.url.contains("/oidc/v1/token"))
    }

    test("PAT credentials send the token as Bearer without a token request") {
        val fake = new FakeTransport()
        val client = new DatabricksRestClient(pat(), fake.transport, new ManualClock().fn)
        assert(client.token() == "dapi-pat-123")
        val body = new JsonObject()
        body.addProperty("name", "datris-pipeline-orders")
        client.post("/api/2.0/lineage-tracking/external-metadata", body)
        client.patch(API + "?update_mask=properties", body)
        assert(fake.tokenCalls.isEmpty, fake.calls.mkString("\n"))
        assert(fake.apiCalls.size == 2, fake.calls.mkString("\n"))
        fake.apiCalls.foreach(c => assert(c.header("Authorization").contains("Bearer dapi-pat-123"), c.headers))
        assert(fake.apiCalls.map(_.method.toUpperCase) == List("POST", "PATCH"))
        assert(fake.apiCalls.head.url == s"https://$HOST/api/2.0/lineage-tracking/external-metadata")
        assert(fake.apiCalls(1).url == s"https://$HOST$API?update_mask=properties")
        assert(fake.apiCalls.head.body.contains("\"datris-pipeline-orders\""), fake.apiCalls.head.body)
        assert(fake.apiCalls.head.header("Content-Type").exists(_.toLowerCase.startsWith("application/json")), fake.apiCalls.head.headers)
    }

    test("token is reused until 60 s before expiry and refreshed after (injectable clock)") {
        val fake = new FakeTransport(expiresIn = 3600)
        val clock = new ManualClock()
        val start = clock.now
        val client = new DatabricksRestClient(m2m(), fake.transport, clock.fn)

        assert(client.token() == "tok-1")
        clock.now = start + 1000L * 1800
        assert(client.token() == "tok-1", "reused mid-life")
        clock.now = start + 1000L * (3600 - 60) - 1
        assert(client.token() == "tok-1", "reused just inside the 60 s margin")
        assert(fake.tokenCalls.size == 1, fake.calls.mkString("\n"))

        clock.now = start + 1000L * (3600 - 60) + 1
        assert(client.token() == "tok-2", "refreshed within 60 s of expiry")
        assert(fake.tokenCalls.size == 2, fake.calls.mkString("\n"))

        client.get(API)
        assert(fake.apiCalls.last.header("Authorization").contains("Bearer tok-2"), fake.apiCalls.last.headers)
        assert(fake.tokenCalls.size == 2, "the API call reuses the refreshed token")
    }

    private def failure(status: Int, body: String): String = {
        val fake = new FakeTransport(apiStatus = status, apiBody = body)
        val client = new DatabricksRestClient(pat(), fake.transport, new ManualClock().fn)
        val e = intercept[DatrisException](client.get(API))
        assert(e.getMessage != null, s"status $status")
        e.getMessage
    }

    test("401/403 name the CREATE EXTERNAL METADATA privilege; 404 names the API/host; other statuses include the body head") {
        Seq(401, 403).foreach { s =>
            val m = failure(s, """{"error_code":"PERMISSION_DENIED","message":"User does not have CREATE EXTERNAL METADATA on Metastore"}""")
            assert(m.contains("CREATE EXTERNAL METADATA"), s"$s: $m")
            assert(m.contains("MODIFY"), s"$s must also name MODIFY on the objects/table: $m")
        }

        val nf = failure(404, """{"error_code":"ENDPOINT_NOT_FOUND","message":"No API found"}""")
        assert(nf.contains("External Metadata API"), nf)
        assert(nf.toLowerCase.contains("host"), nf)

        val longBody = """{"error_code":"INTERNAL_ERROR","message":"backend exploded"}""" + ("x" * 5000)
        val other = failure(500, longBody)
        assert(other.contains("500"), other)
        assert(other.contains("INTERNAL_ERROR") && other.contains("backend exploded"), other)
        assert(other.length < 2000, s"only the head of the body is kept, got ${other.length} chars")

        val conflict = failure(409, """{"error_code":"RESOURCE_ALREADY_EXISTS","message":"exists"}""")
        assert(conflict.contains("409") && conflict.contains("RESOURCE_ALREADY_EXISTS"), conflict)
        assert(!conflict.contains("CREATE EXTERNAL METADATA"), conflict)
    }

    test("host with protocol and path is normalized") {
        val raw = s"https://$HOST:443/sql/1.0/warehouses/abc123?o=1"
        val fake = new FakeTransport()
        val client = new DatabricksRestClient(m2m(host = raw), fake.transport, new ManualClock().fn)
        client.get(API)
        assert(fake.tokenCalls.head.url == s"https://$HOST/oidc/v1/token", fake.tokenCalls.head.url)
        assert(fake.apiCalls.head.url == s"https://$HOST$API", fake.apiCalls.head.url)

        val fake2 = new FakeTransport()
        new DatabricksRestClient(pat(host = s"  $HOST/  "), fake2.transport, new ManualClock().fn).get(API)
        assert(fake2.apiCalls.head.url == s"https://$HOST$API", fake2.apiCalls.head.url)

        // userinfo (user@ / user:pass@) is stripped too.
        val fake3 = new FakeTransport()
        new DatabricksRestClient(pat(host = s"https://someone:pw@$HOST/path"), fake3.transport, new ManualClock().fn).get(API)
        assert(fake3.apiCalls.head.url == s"https://$HOST$API", fake3.apiCalls.head.url)
        assert(DatabricksConnectionUtil.normalizeHost("a@b.example") == "b.example")
    }
}
