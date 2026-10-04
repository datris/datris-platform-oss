package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model._
import com.google.gson.Gson
import org.scalatest.funsuite.AnyFunSuite

import scala.collection.JavaConverters._

/** Field protection 10: HIPAA Safe Harbor preset, server side
  * (plans/stories/field-protection-10-safe-harbor-preset-server.md).
  *
  * Shape this spec pins (pure, no model call, no I/O):
  *
  * {{{
  * // ai/datris/util/ProtectionPreset.scala (types may be top-level in
  * // ai.datris.util or nested in the object; this spec imports ProtectionPreset._)
  * case class Classified(name: String, klass: String, policy: ProtectionPolicy, reason: String)
  * case class Proposal(preset: String, fields: List[Classified], unclassified: List[String], review: List[String])
  *
  * object ProtectionPreset {
  *     val Presets: Set[String]                                   // == Set("hipaa-safe-harbor")
  *     def classify(fieldName: String): Option[(String, ProtectionPolicy)]   // (class label, default policy), unclamped
  *     def propose(fields: List[SchemaField], keyFields: Set[String], destTypes: Map[String, String]): Proposal
  *     def missing(config: PipelineConfig): List[(String, String)]           // (field name, class label)
  * }
  * }}}
  *
  * Class labels (short, lowercase, one per Safe Harbor identifier class):
  *
  * {{{
  *  1 name          names
  *  2 geographic    street address, city, county, ZIP (subdivisions smaller than a state)
  *  3 date          birth / admission / discharge / death dates (ages over 89 → review note)
  *  4 phone         telephone numbers
  *  5 fax           fax numbers
  *  6 email         email addresses
  *  7 ssn           social security numbers
  *  8 mrn           medical record numbers
  *  9 health_plan   health plan beneficiary numbers
  * 10 account       account numbers
  * 11 license       certificate / license numbers
  * 12 vehicle       vehicle identifiers, serial numbers, license plates
  * 13 device        device identifiers and serial numbers
  * 14 url           web URLs
  * 15 ip            IP addresses
  * 16 biometric     biometric identifiers
  * 17 photo         full-face photographs
  * 18 other_id      any other unique identifying number, characteristic or code
  * }}}
  *
  * Default policies (Design decisions): date → mask/year; zip → mask/first3;
  * name, address/city/county, phone, fax, email, url, ip → redact; mrn,
  * health_plan, account, license, vehicle, device, other_id → hmac; ssn,
  * biometric, photo → drop.
  *
  * "No protection" in a clamped proposal may be a null policy, a policy with a
  * null method, or method "none"; this spec treats all three as none.
  */
class ProtectionPresetSpec extends AnyFunSuite {
    import ProtectionPreset._

    private val Preset = "hipaa-safe-harbor"

    /** class label → at least two typical field names. */
    private val Positives: Map[String, List[String]] = Map(
        "name" -> List("patient_name", "first_name", "last_name", "full_name"),
        "geographic" -> List("street_address", "address_line1", "city", "county", "zip", "zip_code", "postal_code"),
        "date" -> List("dob", "date_of_birth", "birth_date", "admission_date", "discharge_date", "death_date"),
        "phone" -> List("phone", "phone_number", "telephone"),
        "fax" -> List("fax", "fax_number"),
        "email" -> List("email", "email_address"),
        "ssn" -> List("ssn", "social_security_number"),
        "mrn" -> List("mrn", "medical_record_number"),
        "health_plan" -> List("health_plan_id", "beneficiary_id", "health_plan_beneficiary_number"),
        "account" -> List("account_number", "account_no"),
        "license" -> List("license_number", "licence_number", "certificate_number"),
        "vehicle" -> List("vin", "license_plate", "vehicle_serial_number"),
        "device" -> List("device_id", "device_serial_number"),
        "url" -> List("url", "website_url", "web_url"),
        "ip" -> List("ip_address", "ip"),
        "biometric" -> List("fingerprint", "biometric_data"),
        "photo" -> List("photo", "photograph", "face_photo"),
        "other_id" -> List("national_id", "unique_identifier")
    )

    private val NearMisses = List(
        "name_of_drug", "zip_file", "phone_model", "date_format", "email_template", "account_type", "ip_rating", "url_count"
    )

    private def isNone(p: ProtectionPolicy): Boolean =
        p == null || p.method == null || p.method.trim.equalsIgnoreCase("none")

    private def methodOf(p: ProtectionPolicy): String = if (isNone(p)) "none" else p.method.trim.toLowerCase
    private def preserveOf(p: ProtectionPolicy): String = if (p == null || p.preserve == null) null else p.preserve.trim.toLowerCase

    private def s(name: String, t: String = "string"): SchemaField = SchemaField(name, t)

    private def proposed(p: Proposal, name: String): Classified = {
        val c = p.fields.find(_.name == name)
        assert(c.isDefined, s"'$name' must be in proposal.fields; got ${p.fields.map(_.name)} unclassified=${p.unclassified}")
        c.get
    }

    test("each of the 18 classes is recognised from typical field names") {
        assert(Presets == Set(Preset))
        assert(Positives.size == 18)
        Positives.foreach { case (klass, names) =>
            assert(names.size >= 2)
            names.foreach { n =>
                val got = classify(n)
                assert(got.isDefined, s"'$n' should classify as $klass")
                assert(got.get._1 == klass, s"'$n' should classify as $klass, got ${got.get._1}")
                assert(!isNone(got.get._2), s"'$n' should carry a default policy")
            }
        }
        // Case and separators do not matter.
        assert(classify("Date-Of-Birth").map(_._1) == Some("date"))
        assert(classify("EmailAddress").map(_._1) == Some("email"))
        assert(classify("SSN").map(_._1) == Some("ssn"))
    }

    test("near-miss names are not classified") {
        NearMisses.foreach(n => assert(classify(n).isEmpty, s"'$n' must not classify, got ${classify(n)}"))
        List("visit_count", "amount", "diagnosis_code_count", "status").foreach(n => assert(classify(n).isEmpty, s"'$n' got ${classify(n)}"))
    }

    test("prefixes like patient_ and member_ do not change the class") {
        val pairs = List(
            "patient_email" -> "email",
            "member_email" -> "email",
            "pt_dob" -> "dob",
            "patient_ssn" -> "ssn",
            "member_phone" -> "phone",
            "patient_zip" -> "zip",
            "patientMrn" -> "mrn"
        )
        pairs.foreach { case (prefixed, bare) =>
            val a = classify(prefixed)
            val b = classify(bare)
            assert(b.isDefined, s"'$bare' must classify")
            assert(a.map(_._1) == b.map(_._1), s"'$prefixed' → $a, '$bare' → $b")
            assert(a.map(x => (methodOf(x._2), preserveOf(x._2))) == b.map(x => (methodOf(x._2), preserveOf(x._2))), s"'$prefixed' vs '$bare'")
        }
    }

    test("dates propose mask keeping the year; zip proposes mask keeping the first three") {
        List("dob", "admission_date", "discharge_date", "death_date").foreach { n =>
            val p = classify(n).get._2
            assert(methodOf(p) == "mask" && preserveOf(p) == "year", s"$n → $p")
        }
        List("zip", "zip_code").foreach { n =>
            val p = classify(n).get._2
            assert(methodOf(p) == "mask" && preserveOf(p) == "first3", s"$n → $p")
        }
        // Through propose as well, on string fields with no constraints.
        val prop = propose(List(s("dob"), s("zip")), Set.empty, Map.empty)
        assert(prop.preset == Preset)
        assert(methodOf(proposed(prop, "dob").policy) == "mask" && preserveOf(proposed(prop, "dob").policy) == "year")
        assert(methodOf(proposed(prop, "zip").policy) == "mask" && preserveOf(proposed(prop, "zip").policy) == "first3")
        assert(proposed(prop, "dob").klass == "date" && proposed(prop, "zip").klass == "geographic")
        // Names, addresses and contact columns are redacted (phone/fax keep-last-4 is not Safe Harbor).
        List("patient_name", "street_address", "city", "phone", "fax", "email", "url", "ip_address").foreach { n =>
            assert(methodOf(classify(n).get._2) == "redact", s"$n → ${classify(n)}")
        }
    }

    test("stable identifiers propose hmac; ssn proposes drop") {
        List("mrn", "account_number", "health_plan_id", "license_number", "device_id", "vin", "national_id").foreach { n =>
            assert(methodOf(classify(n).get._2) == "hmac", s"$n → ${classify(n)}")
        }
        List("ssn", "social_security_number", "fingerprint", "photo").foreach { n =>
            assert(methodOf(classify(n).get._2) == "drop", s"$n → ${classify(n)}")
        }
        val prop = propose(List(s("mrn"), s("ssn"), s("visit_count", "int")), Set.empty, Map.empty)
        assert(methodOf(proposed(prop, "mrn").policy) == "hmac")
        assert(methodOf(proposed(prop, "ssn").policy) == "drop")
        assert(prop.unclassified == List("visit_count"))
        assert(proposed(prop, "mrn").reason != null && proposed(prop, "mrn").reason.nonEmpty)
    }

    test("a key column proposal is clamped to hmac or none with the reason") {
        val prop = propose(List(s("mrn"), s("ssn"), s("email")), Set("mrn", "ssn", "email"), Map.empty)
        assert(methodOf(proposed(prop, "mrn").policy) == "hmac", "hmac on a key column stands")
        Seq("ssn", "email").foreach { n =>
            val c = proposed(prop, n)
            assert(isNone(c.policy), s"$n on a key column → none, got ${c.policy}")
            assert(c.reason == FieldProtectionAdvisor.KeyColumnReason, s"$n reason: ${c.reason}")
            assert(c.klass != null && c.klass.nonEmpty, "the class is still reported")
        }
        // Key fields are matched case-insensitively, as the validator does.
        val mixed = propose(List(s("Email")), Set("email"), Map.empty)
        assert(isNone(proposed(mixed, "Email").policy))
    }

    test("a non-string destination column proposal is clamped to drop or none") {
        val prop = propose(
            List(s("dob"), s("mrn"), s("ssn")),
            Set.empty,
            Map("dob" -> "date", "mrn" -> "bigint", "ssn" -> "int")
        )
        val dob = proposed(prop, "dob")
        assert(isNone(dob.policy), s"mask on a date destination column → none, got ${dob.policy}")
        assert(dob.reason != null && dob.reason.contains("destination type is date"), s"reason: ${dob.reason}")
        val mrn = proposed(prop, "mrn")
        assert(isNone(mrn.policy) && mrn.reason.contains("destination type is bigint"), s"mrn: $mrn")
        assert(methodOf(proposed(prop, "ssn").policy) == "drop", "drop stands on any type")
        // A non-string SOURCE type cannot carry a string-producing method either.
        val src = propose(List(s("dob", "date")), Set.empty, Map.empty)
        assert(isNone(proposed(src, "dob").policy), s"source type date: ${proposed(src, "dob")}")
    }

    test("review notes mention ages over 89, small-population ZIP prefixes and free text when such fields are present") {
        def notes(fields: SchemaField*): String = propose(fields.toList, Set.empty, Map.empty).review.mkString("\n").toLowerCase

        val age = notes(s("age", "int"))
        assert(age.contains("89") && age.contains("90"), s"age: $age")
        val dob = notes(s("dob"))
        assert(dob.contains("89"), s"dob (year of birth reveals ages over 89): $dob")

        val zip = notes(s("zip"))
        assert(zip.contains("zip") && (zip.contains("20,000") || zip.contains("20000")) && zip.contains("000"), s"zip: $zip")

        val free = notes(s("notes"), s("comments"))
        assert(free.contains("free text") || free.contains("free-text"), s"notes: $free")

        val plain = notes(s("mrn"), s("visit_count", "int"))
        assert(!plain.contains("89"), s"no age/dob → no age note: $plain")
        assert(!plain.contains("20,000") && !plain.contains("20000"), s"no zip → no zip note: $plain")
        assert(!plain.contains("free text") && !plain.contains("free-text"), s"no free text → no note: $plain")
    }

    // ---- missing -------------------------------------------------------------

    private val gson = new Gson()

    private def presetConfig(sourceFields: String, protection: String): PipelineConfig =
        gson.fromJson(
            s"""{"name":"pp",
               |"source":{"fileAttributes":{"csvAttributes":{"header":true}},"schemaProperties":{"fields":$sourceFields}},
               |"destination":{"database":{"dbName":"datris","schema":"public","table":"pp","usePostgres":true}},
               |"protection":$protection}""".stripMargin,
            classOf[PipelineConfig]
        )

    test("missing lists classified unprotected fields and honours presetExempt") {
        val src =
            """[{"name":"mrn","type":"string","protect":{"method":"hmac"}},
              |{"name":"phone","type":"string"},
              |{"name":"patient_email","type":"string"},
              |{"name":"visit_count","type":"int"}]""".stripMargin
        val cfg = presetConfig(src, s"""{"preset":"$Preset"}""")
        assert(cfg.protection.preset == Preset)
        assert(missing(cfg).toSet == Set(("phone", "phone"), ("patient_email", "email")), s"got ${missing(cfg)}")

        val exempt = presetConfig(src, s"""{"preset":"$Preset","presetExempt":["phone"]}""")
        assert(exempt.protection.presetExempt.asScala.toList == List("phone"))
        assert(missing(exempt) == List(("patient_email", "email")), s"got ${missing(exempt)}")

        val allExempt = presetConfig(src, s"""{"preset":"$Preset","presetExempt":["phone","patient_email"]}""")
        assert(missing(allExempt).isEmpty)
    }
}
