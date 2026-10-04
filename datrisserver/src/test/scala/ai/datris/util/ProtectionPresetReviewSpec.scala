package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model._
import org.scalatest.funsuite.AnyFunSuite

/** Story 10 review round 1: names the first table missed, the ZIP+4
  * extension, and near-misses for the broader names that were added. */
class ProtectionPresetReviewSpec extends AnyFunSuite {
    import ProtectionPreset._

    private def klass(n: String): Option[String] = classify(n).map(_._1)
    private def method(n: String): Option[String] = classify(n).map(_._2.method)

    test("a ZIP+4 extension column is redacted, never kept to its first three digits") {
        Seq("zip4", "zip_4", "zipplus4", "zip_plus_4", "plus4", "zip_ext", "zip_extension", "patient_zip4").foreach { n =>
            assert(klass(n) == Some("geographic"), s"$n → ${classify(n)}")
            assert(method(n) == Some("redact") && classify(n).get._2.preserve == null, s"$n → ${classify(n)}")
        }
        assert(classify("zip5").map(_._2.preserve) == Some("first3"), "a 5-digit ZIP keeps its first three")
        assert(!propose(List(SchemaField("zip4", "string")), Set.empty, Map.empty).review.exists(_.contains("20,000")), "no ZIP-prefix note for the extension")
    }

    test("health plan member and subscriber numbers are recognised before the prefix is dropped") {
        Seq("member_id", "memberId", "subscriber_id", "member_number", "subscriber_number", "policy_no", "group_number").foreach { n =>
            assert(klass(n) == Some("health_plan"), s"$n → ${classify(n)}")
            assert(method(n) == Some("hmac"))
        }
        assert(classify("patient_id").isEmpty, "a bare id is still never classified")
    }

    test("common names the first table missed are recognised") {
        val expected = Map(
            "phone" -> Seq("cell", "mobile", "mobile_no", "guardian_phone"),
            "geographic" -> Seq("addr", "addr1", "apt", "billing_address", "shipping_address", "lat", "lng", "lon"),
            "date" -> Seq("visit_dt", "appt_date", "dos", "lab_date", "collection_date"),
            "name" -> Seq("contact_name", "emergency_contact", "middle_initial"),
            "account" -> Seq("acct", "account", "credit_card_number", "card_number", "claim_number", "prescription_number"),
            "license" -> Seq("license"),
            "vehicle" -> Seq("plate"),
            "device" -> Seq("serial_no"),
            "biometric" -> Seq("retina"),
            "photo" -> Seq("picture", "image", "image_url", "photo_url"),
            "ssn" -> Seq("social"),
            "other_id" -> Seq("ein", "tin")
        )
        expected.foreach { case (k, names) => names.foreach(n => assert(klass(n) == Some(k), s"$n → ${classify(n)}, expected $k")) }
        Seq("picture", "image", "image_url", "photo_url").foreach(n => assert(method(n) == Some("drop"), n))
        Seq("account", "acct").foreach(n => assert(method(n) == Some("hmac"), n))
        Seq("lat", "lng", "lon").foreach(n => assert(method(n) == Some("drop"), n))
    }

    test("broad names do not pull in their near-misses; state, country and npi stay unclassified") {
        Seq(
            "account_type",
            "image_count",
            "license_type",
            "plate_count",
            "social_score",
            "cell_count",
            "mobile_app",
            "lab_date_format",
            "state",
            "country",
            "npi"
        ).foreach(n => assert(classify(n).isEmpty, s"$n → ${classify(n)}"))
    }
}
