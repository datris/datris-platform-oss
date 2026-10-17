package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import org.scalatest.funsuite.AnyFunSuite

/** The deterministic sensitive-name list that gates topK in incident evidence. */
class RunProfilerSensitiveNameSpec extends AnyFunSuite {

    test("personal and secret-looking column names are sensitive") {
        val names = List(
            "email",
            "EMAIL",
            "user_email",
            "emailAddress",
            "phone",
            "mobile_phone",
            "ssn",
            "first_name",
            "lastName",
            "name",
            "username",
            "address",
            "street_address",
            "dob",
            "birth_date",
            "token",
            "api_key",
            "apiKey",
            "secret",
            "password",
            "iban",
            "card_number",
            "cardNumber"
        )
        names.foreach(n => assert(RunProfiler.looksSensitive(n), n))
    }

    test("ordinary column names are not sensitive") {
        List("amount", "status", "code", "note", "flag", "filename", "created_at", "region", "price", "monkey", "tokenizer_version")
            .foreach(n => assert(!RunProfiler.looksSensitive(n), n))
    }

    test("a sensitive column profiles without topK even when low-cardinality") {
        val rows = Iterator.fill(10)(Map[String, Any]("email" -> "a@b.co", "status" -> "ok"))
        val s = RunProfiler.profile(rows, n => !RunProfiler.looksSensitive(n))
        assert(s.columns.find(_.name == "email").get.topK.isEmpty)
        assert(s.columns.find(_.name == "status").get.topK == Some(List(("ok", 10L))))
    }
}
