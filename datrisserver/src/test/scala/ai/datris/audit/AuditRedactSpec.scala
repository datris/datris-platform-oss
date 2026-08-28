package ai.datris.audit

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.util.LogRedactUtil
import com.google.gson.JsonParser
import org.scalatest.funsuite.AnyFunSuite

class AuditRedactSpec extends AnyFunSuite {

    private val Sentinel = "s3cr3t-SENTINEL-value"

    test("sensitive field names match by substring, case-insensitively") {
        Seq(
            "password",
            "Password",
            "passwd",
            "clientSecret",
            "secret_key",
            "token",
            "refreshToken",
            "apiKey",
            "api_key",
            "x-api-key",
            "Authorization",
            "cookie",
            "credentials",
            "privateKey",
            "private_key"
        )
            .foreach(n => assert(LogRedactUtil.isSensitiveField(n), n))
        Seq("name", "username", "role", "catalog", "endpoint", "model", "provider")
            .foreach(n => assert(!LogRedactUtil.isSensitiveField(n), n))
    }

    test("redactJson masks nested and array values under sensitive keys, keeps the rest") {
        val in = JsonParser.parseString(
            s"""{"name":"crypto","password":"$Sentinel","nested":{"apiKey":"$Sentinel","endpoint":"https://x"},
               |"list":[{"token":"$Sentinel","id":1}],"role":"admin"}""".stripMargin
        )
        val out = LogRedactUtil.redactJson(in).getAsJsonObject
        val s = out.toString
        assert(!s.contains(Sentinel))
        assert(out.get("name").getAsString == "crypto")
        assert(out.get("password").getAsString == LogRedactUtil.Mask)
        assert(out.getAsJsonObject("nested").get("apiKey").getAsString == LogRedactUtil.Mask)
        assert(out.getAsJsonObject("nested").get("endpoint").getAsString == "https://x")
        assert(out.getAsJsonArray("list").get(0).getAsJsonObject.get("token").getAsString == LogRedactUtil.Mask)
        assert(out.getAsJsonArray("list").get(0).getAsJsonObject.get("id").getAsInt == 1)
        // input untouched
        assert(in.toString.contains(Sentinel))
    }

    test("redactJson tolerates null and primitives") {
        assert(LogRedactUtil.redactJson(null) == null)
        assert(LogRedactUtil.redactJson(JsonParser.parseString("42")).getAsInt == 42)
    }

    test("redactQueryString masks sensitive params only, preserving order") {
        assert(LogRedactUtil.redactQueryString(s"name=x&apiKey=$Sentinel&limit=5") == "name=x&apiKey=***&limit=5")
        assert(LogRedactUtil.redactQueryString(s"token=$Sentinel") == "token=***")
        assert(LogRedactUtil.redactQueryString("name=x&flag") == "name=x&flag")
        assert(LogRedactUtil.redactQueryString("") == "")
        assert(LogRedactUtil.redactQueryString(null) == null)
    }

    test("an audit entry built with redacted metadata never carries the sentinel") {
        val md = LogRedactUtil.redactJson(JsonParser.parseString(s"""{"from":"viewer","to":"admin","password":"$Sentinel"}""")).getAsJsonObject
        val e = AuditEntry(
            ts = java.time.Instant.now(),
            actor = AuditActorInfo.System,
            category = "user",
            action = "update",
            resourceType = Some("user"),
            resourceName = Some("bob"),
            outcome = "success",
            request =
                Some(AuditRequestInfo("PATCH", "/api/v1/auth/users/bob", Some(LogRedactUtil.redactQueryString(s"secret=$Sentinel")), Some("10.0.0.1"), None)),
            metadata = Some(md)
        )
        assert(!e.toJson.toString.contains(Sentinel))
        assert(!e.toDocument.toJson.contains(Sentinel))
    }
}
