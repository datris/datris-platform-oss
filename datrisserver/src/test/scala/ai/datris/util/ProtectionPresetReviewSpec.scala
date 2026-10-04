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

    // ---- review round 2 ----

    test("stacked prefixes and trailing digits do not un-classify a health plan number") {
        Seq(
            "patient_member_id",
            "insured_member_id",
            "patient_subscriber_id",
            "patient_mbr_id",
            "subscriber_member_number",
            "member_id2",
            "member_id_2",
            "member_number2",
            "mbr_id2",
            "subscriber_id_1",
            "subscriber_number_2",
            "patient_member_id_2"
        ).foreach(n => assert(klass(n) == Some("health_plan"), s"$n → ${classify(n)}"))
        assert(classify("patient_id").isEmpty && classify("patient_id_2").isEmpty, "a bare id stays unclassified")
        assert(klass("patient_email2") == Some("email"))
    }

    test("every ZIP+4 extension spelling is redacted") {
        Seq("zip_code_4", "zipcode4", "postal_code_4", "zip_code_ext", "zip_code_extension", "zip_suffix", "zip_plus_four").foreach { n =>
            assert(klass(n) == Some("geographic") && method(n) == Some("redact"), s"$n → ${classify(n)}")
        }
        Seq("zip5", "zip9").foreach(n => assert(classify(n).map(_._2.preserve) == Some("first3"), n))
    }

    test("clinical event dates are masked; generic dates stay unclassified with a review note") {
        Seq("admitted_at", "discharged_at", "died_on").foreach { n =>
            assert(klass(n) == Some("date") && classify(n).get._2.preserve == "year", s"$n → ${classify(n)}")
        }
        val generic = Seq("date", "created_at", "updated_at", "timestamp", "start_date", "end_date", "order_date", "ship_date", "due_date", "event_time")
        generic.foreach(n => assert(classify(n).isEmpty, s"$n → ${classify(n)}"))
        val p = propose(generic.map(n => SchemaField(n, "string")).toList, Set.empty, Map.empty)
        val note = p.review.find(_.contains("may be dates related to an individual"))
        assert(note.isDefined, s"review: ${p.review}")
        generic.foreach(n => assert(note.get.contains(n), s"$n named in: ${note.get}"))
        assert(note.get.contains("did not mask"))
        val none =
            propose(List(SchemaField("mrn", "string"), SchemaField("visit_count", "int"), SchemaField("lab_date_format", "string")), Set.empty, Map.empty)
        assert(!none.review.exists(_.contains("may be dates")), s"review: ${none.review}")
    }

    test("the agreed extra identifier names are recognised; person-name variants are not") {
        val expected = Map(
            "ssn" -> Seq("ssn_last4", "last4_ssn", "ssn4"),
            "account" -> Seq("routing_number", "account_number_last4"),
            "geographic" -> Seq("place_of_birth", "birthplace"),
            "biometric" -> Seq("face_id", "iris", "signature", "voiceprint")
        )
        expected.foreach { case (k, names) => names.foreach(n => assert(klass(n) == Some(k), s"$n → ${classify(n)}, expected $k")) }
        Seq("ssn_last4", "last4_ssn", "ssn4", "face_id", "iris", "signature").foreach(n => assert(method(n) == Some("drop"), n))
        Seq("routing_number", "account_number_last4").foreach(n => assert(method(n) == Some("hmac"), n))
        Seq("place_of_birth", "birthplace").foreach(n => assert(method(n) == Some("redact"), n))
        Seq("username", "user_name", "display_name", "doctor_name", "provider_name", "first", "last").foreach(n =>
            assert(classify(n).isEmpty, s"$n → ${classify(n)}")
        )
    }
}
