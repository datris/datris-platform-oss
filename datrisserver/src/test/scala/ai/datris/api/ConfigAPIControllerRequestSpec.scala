package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.ValidationException
import org.scalatest.funsuite.AnyFunSuite

/** Story: generate-schema and config upload: bad input is a 400 with a clear
  * message, not a 500 (plans/stories/generate-schema-empty-body-500.md).
  *
  * Pure spec of the request-checking seam on `object ConfigAPIController`:
  * every refusal is a `ValidationException`, which `ApiErrors.classify`
  * already maps to `400 {"error": <message>}`. No Spring, object store or
  * model is touched. */
class ConfigAPIControllerRequestSpec extends AnyFunSuite {

    import ConfigAPIController.{parseGenerateSchema, requireUploadType, sanitizeKeySegment, GenerateSchemaRequest}

    private val NotObject = "Request body must be a JSON object with string fields: type, name, sampleData"
    private val NotJson = "Request body is not valid JSON"

    /** Assert the refusal contract: ValidationException, exact message, 400 body. */
    private def assertRefused(message: String)(thunk: => Any): ValidationException = {
        val e = intercept[ValidationException](thunk)
        assert(e.getMessage == message)
        assert(ApiErrors.classify(e) == ((400, ApiErrors.errorBody(message))))
        e
    }

    private def refused(body: String, message: String): ValidationException =
        withClue(s"body: $body ") { assertRefused(message)(parseGenerateSchema(body)) }

    test("empty object names all three missing fields") {
        refused("{}", "Missing required field(s): type, name, sampleData")
    }

    test("each missing field is named on its own") {
        refused("""{"name":"s","sampleData":"x"}""", "Missing required field(s): type")
        refused("""{"type":"json-schema","sampleData":"x"}""", "Missing required field(s): name")
        refused("""{"type":"json-schema","name":"s"}""", "Missing required field(s): sampleData")
        // Only the missing ones, in the order type, name, sampleData.
        refused("""{"name":"s"}""", "Missing required field(s): type, sampleData")
    }

    test("a null field counts as missing") {
        refused("""{"type":null,"name":"s","sampleData":"x"}""", "Missing required field(s): type")
        refused("""{"type":"json-schema","name":null,"sampleData":"x"}""", "Missing required field(s): name")
        refused("""{"type":"json-schema","name":"s","sampleData":null}""", "Missing required field(s): sampleData")
        refused("""{"type":null,"name":null,"sampleData":null}""", "Missing required field(s): type, name, sampleData")
    }

    test("an object or array field is refused as not a string") {
        refused("""{"type":{},"name":"s","sampleData":"x"}""", "Field 'type' must be a string")
        refused("""{"type":[],"name":"s","sampleData":"x"}""", "Field 'type' must be a string")
        // One-element array is 200 today through a Gson coercion quirk; it becomes a refusal.
        refused("""{"type":["json-schema"],"name":"s","sampleData":"x"}""", "Field 'type' must be a string")
        refused("""{"type":"json-schema","name":{},"sampleData":"x"}""", "Field 'name' must be a string")
        refused("""{"type":"json-schema","name":"s","sampleData":["a","b"]}""", "Field 'sampleData' must be a string")
        // First offender in the order type, name, sampleData.
        refused("""{"type":"json-schema","name":[],"sampleData":{}}""", "Field 'name' must be a string")
        // Missing fields are reported before wrong-typed ones.
        refused("""{"type":{},"name":"s"}""", "Missing required field(s): sampleData")
    }

    test("a non-object body is refused") {
        Seq("[]", "\"str\"", "5", "null", "\"\"", "", "   ").foreach(b => refused(b, NotObject))
    }

    test("malformed JSON is refused without the parser's message") {
        Seq("{not json", """{"type":"json-schema"""").foreach { b =>
            val e = refused(b, NotJson)
            val m = e.getMessage
            assert(!m.toLowerCase.contains("gson"), m)
            assert(!m.contains("Exception"), m)
            assert(!m.contains("line 1"), m)
        }
    }

    test("no refusal message echoes the request body") {
        val marker = "ZQ_MARKER_7731"
        val bodies = Seq(
            s"""["$marker"]""",
            s""""$marker"""",
            s"""{$marker""",
            s"""{"type":"json-schema","$marker":"""",
            s"""{"$marker":"x"}""",
            s"""{"type":{"$marker":1},"name":"s","sampleData":"x"}""",
            s"""{"type":"json-schema","name":"s","sampleData":["$marker"]}""",
            s"""{"type":"json-schema","name":"s","sampleData":null,"extra":"$marker"}"""
        )
        bodies.foreach { b =>
            val e = intercept[ValidationException](parseGenerateSchema(b))
            assert(!e.getMessage.contains(marker), s"body: $b message: ${e.getMessage}")
            assert(!ApiErrors.classify(e)._2.contains(marker))
        }
    }

    test("invalid schema type, blank name and blank sampleData keep their messages and are 400") {
        refused("""{"type":"avro","name":"s","sampleData":"x"}""", "Invalid schema type: avro. Must be one of: json-schema, xsd")
        refused("""{"type":"json-schema","name":"","sampleData":"x"}""", "Schema name is required")
        refused("""{"type":"json-schema","name":"   ","sampleData":"x"}""", "Schema name is required")
        refused("""{"type":"json-schema","name":"s","sampleData":""}""", "Sample data is required")
        refused("""{"type":"xsd","name":"s","sampleData":"  \n "}""", "Sample data is required")
    }

    test("a name with a bad character or no usable segment is refused before generation") {
        refused(
            """{"type":"json-schema","name":"a b!","sampleData":"x"}""",
            "File name 'a b!' contains invalid characters. Allowed: letters, digits, . _ -"
        )
        refused("""{"type":"json-schema","name":"..","sampleData":"x"}""", "Invalid file name: ..")
        refused("""{"type":"json-schema","name":"/","sampleData":"x"}""", "Invalid file name: /")
        // Traversal is stripped, not refused: ../x reduces to x as today.
        assert(parseGenerateSchema("""{"type":"json-schema","name":"../x","sampleData":"x"}""").name == "x")
    }

    test("a valid body parses unchanged") {
        assert(
            parseGenerateSchema("""{"type":"json-schema","name":"orders","sampleData":"[{\"id\":1}]"}""") ==
                GenerateSchemaRequest("json-schema", "orders", "[{\"id\":1}]")
        )
        assert(
            parseGenerateSchema("""{"type":"xsd","name":"orders_xml","sampleData":"<a>1</a>"}""") ==
                GenerateSchemaRequest("xsd", "orders_xml", "<a>1</a>")
        )
        // Name is trimmed; sampleData is passed through as sent.
        assert(parseGenerateSchema("""{"type":"json-schema","name":"  orders  ","sampleData":" x "}""") == GenerateSchemaRequest(
            "json-schema",
            "orders",
            " x "
        ))
        // A path-prefixed name still reduces to its last segment.
        assert(parseGenerateSchema("""{"type":"json-schema","name":"dir/orders","sampleData":"x"}""").name == "orders")
        // JSON numbers and booleans are still coerced to strings.
        assert(parseGenerateSchema("""{"type":"json-schema","name":42,"sampleData":true}""") == GenerateSchemaRequest("json-schema", "42", "true"))
        // Unknown extra fields are ignored, as today.
        assert(parseGenerateSchema("""{"type":"json-schema","name":"o","sampleData":"x","extra":{}}""") == GenerateSchemaRequest("json-schema", "o", "x"))
    }

    test("upload type outside validation-schema and javascript is a ValidationException with the existing message") {
        assertRefused("Invalid config file type: bogus. Must be one of: validation-schema, javascript")(requireUploadType("bogus"))
        assertRefused("Invalid config file type: . Must be one of: validation-schema, javascript")(requireUploadType(""))
        requireUploadType("validation-schema")
        requireUploadType("javascript")
    }

    test("sanitizeKeySegment refusals are ValidationExceptions") {
        assertRefused("File name is required")(sanitizeKeySegment(null))
        assertRefused("Invalid file name: ..")(sanitizeKeySegment(".."))
        assertRefused("Invalid file name: /")(sanitizeKeySegment("/"))
        assertRefused("File name 'a b.json' contains invalid characters. Allowed: letters, digits, . _ -")(sanitizeKeySegment("dir/a b.json"))
        assert(sanitizeKeySegment("../tap-scripts/x.py") == "x.py")
        assert(sanitizeKeySegment("a\\b\\schema.json") == "schema.json")
    }
}
