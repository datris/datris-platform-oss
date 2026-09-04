package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.TapConfig
import org.scalatest.funsuite.AnyFunSuite

class TapSourceResolverSpec extends AnyFunSuite {

    test("declared source wins over everything") {
        val t = TapConfig(
            name = "t",
            description = "d",
            targetPipeline = "p",
            source = " SEC EDGAR ",
            scriptKind = "http",
            endpointUrl = "https://feeds.example.org/x"
        )
        assert(TapSourceResolver.resolve(t) == "SEC EDGAR")
    }

    test("http tap without a declared source uses the endpoint host") {
        val t = TapConfig(name = "t", description = "d", targetPipeline = "p", scriptKind = "http", endpointUrl = "https://feeds.example.org/v1/prices")
        assert(TapSourceResolver.resolve(t) == "feeds.example.org")
    }

    test("script with no readable script falls back to tap:<name>") {
        val t = TapConfig(name = "orphan", description = "d", targetPipeline = "p")
        assert(TapSourceResolver.resolve(t) == "tap:orphan")
    }

    test("deriveFromScript picks the most-referenced host, normalized") {
        val script =
            """import requests
              |BASE = "https://www.sec.gov/cgi-bin/browse-edgar"
              |r = requests.get("https://data.sec.gov/submissions/CIK.json")
              |r2 = requests.get(f"https://www.sec.gov/Archives/{path}")
              |fallback = "https://api.polygon.io/v2/aggs"
              |""".stripMargin
        assert(TapSourceResolver.deriveFromScript(script).contains("sec.gov"))
    }

    test("deriveFromScript ignores infrastructure hosts and ties go to the first mention") {
        val script =
            """url = "https://financialmodelingprep.com/api/v3/price-target"
              |other = "https://api.polygon.io/v3/reference"
              |cb = "http://host.docker.internal:8080/api/v1/query"
              |doc = "https://github.com/datris/datris-platform-oss"
              |""".stripMargin
        assert(TapSourceResolver.deriveFromScript(script).contains("financialmodelingprep.com"))
    }

    test("deriveFromScript returns None for scripts without URLs") {
        assert(TapSourceResolver.deriveFromScript("import os\nprint(os.environ['X'])").isEmpty)
        assert(TapSourceResolver.deriveFromScript("").isEmpty)
    }

    test("normalize strips only leading generic labels") {
        assert(TapSourceResolver.normalize("api.polygon.io") == "polygon.io")
        assert(TapSourceResolver.normalize("www.data.sec.gov") == "sec.gov")
        assert(TapSourceResolver.normalize("query1.finance.yahoo.com") == "query1.finance.yahoo.com")
        assert(TapSourceResolver.normalize("api.io") == "api.io")
    }
}
