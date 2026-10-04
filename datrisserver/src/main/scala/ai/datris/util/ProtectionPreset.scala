package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{PipelineConfig, ProtectionPolicy, SchemaField}
import com.google.gson.{GsonBuilder, JsonArray, JsonNull, JsonObject}

import scala.collection.JavaConverters._

/** One recognised field in a preset proposal. `policy` is null when a
  * clamp (key column, field or destination type) leaves no method that can
  * stand; `reason` then says why. */
case class Classified(name: String, klass: String, policy: ProtectionPolicy, reason: String)

/** A preset's proposal for a field list: the recognised fields, the names it
  * could not decide, and the review notes a method cannot express. */
case class Proposal(preset: String, fields: List[Classified], unclassified: List[String], review: List[String])

/** Field protection presets (plans/stories/field-protection-10-safe-harbor-preset-server.md).
  *
  * `hipaa-safe-harbor` recognises the 18 HIPAA Safe Harbor identifier
  * classes from field NAMES with the fixed table below. No model is called
  * and nothing is read or sent anywhere, so the same names always give the
  * same answer. Names are normalised before lookup: split on separators and
  * camelCase, lowercased, a leading prefix such as `patient`, `pt` or
  * `member` dropped, then joined; the result must match a table entry
  * exactly (trailing digits are also tried stripped, so `phone2` is a
  * phone). Exact matching is deliberate: `name_of_drug`, `zip_file` and
  * `phone_model` are not identifiers. A bare `id` (including `patient_id`)
  * is never classified, so key columns are not swept up.
  *
  * The preset is an aid to applying Safe Harbor, not a certification. */
object ProtectionPreset {

    val HipaaSafeHarbor = "hipaa-safe-harbor"

    val Presets: Set[String] = Set(HipaaSafeHarbor)

    /** One table row: a class label, the Safe Harbor wording it stands for,
      * the normalised names it matches, and the default policy. */
    private case class Entry(klass: String, wording: String, names: Set[String], policy: ProtectionPolicy)

    private def p(method: String, preserve: String = null): ProtectionPolicy = ProtectionPolicy(method, preserve, null)

    private val Redact = p("redact")
    private val Hmac = p("hmac")
    private val Drop = p("drop")
    private val MaskYear = p("mask", "year")
    private val MaskFirst3 = p("mask", "first3")

    /** Normalised ZIP names: the only geographic entry kept partly (first three digits). */
    private val ZipNames: Set[String] = Set("zip", "zipcode", "postalcode", "postcode")

    private val Table: List[Entry] = List(
        Entry(
            "name",
            "names",
            Set(
                "name",
                "firstname",
                "lastname",
                "fullname",
                "middlename",
                "givenname",
                "surname",
                "familyname",
                "maidenname",
                "mothersmaidenname",
                "fname",
                "lname",
                "nickname",
                "preferredname",
                "spousename",
                "guardianname",
                "emergencycontactname",
                "contactname",
                "emergencycontact",
                "middleinitial",
                "nextofkin"
            ),
            Redact
        ),
        Entry(
            "geographic",
            "geographic subdivisions smaller than a state (street address, city, county, precinct, ZIP code, geocodes)",
            Set(
                "address",
                "streetaddress",
                "street",
                "addressline",
                "addr",
                "apt",
                "billingaddress",
                "shippingaddress",
                "homeaddress",
                "mailingaddress",
                "residentialaddress",
                "city",
                "town",
                "county",
                "precinct"
            ),
            Redact
        ),
        Entry("geographic", "geographic subdivisions smaller than a state (ZIP code)", ZipNames, MaskFirst3),
        // The +4 extension alone: first3 would leave three of its four digits in
        // the clear, so it is redacted. Looked up before the trailing-digit strip.
        Entry(
            "geographic",
            "geographic subdivisions smaller than a state (ZIP+4 extension)",
            Set("zip4", "zipplus4", "plus4", "zipext", "zipextension"),
            Redact
        ),
        Entry(
            "geographic",
            "geographic subdivisions smaller than a state (geocodes)",
            Set("latitude", "longitude", "lat", "lng", "lon", "latlong", "latlng", "geocode", "geolocation", "coordinates"),
            Drop
        ),
        Entry(
            "date",
            "all elements of dates (except year) directly related to an individual, including birth, admission, discharge and death dates",
            Set(
                "dob",
                "dateofbirth",
                "birthdate",
                "birthday",
                "birthdt",
                "admissiondate",
                "admitdate",
                "admissiondt",
                "admitdt",
                "dateofadmission",
                "dischargedate",
                "dischargedt",
                "dateofdischarge",
                "deathdate",
                "dateofdeath",
                "dod",
                "deceaseddate",
                "servicedate",
                "dateofservice",
                "visitdate",
                "encounterdate",
                "appointmentdate",
                "apptdate",
                "visitdt",
                "dos",
                "labdate",
                "collectiondate",
                "proceduredate"
            ),
            MaskYear
        ),
        Entry(
            "phone",
            "telephone numbers",
            Set(
                "phone",
                "phonenumber",
                "phoneno",
                "phonenum",
                "telephone",
                "telephonenumber",
                "tel",
                "telno",
                "cell",
                "mobile",
                "mobileno",
                "guardianphone",
                "mobilenumber",
                "mobilephone",
                "cellphone",
                "cellnumber",
                "homephone",
                "workphone",
                "businessphone",
                "contactphone",
                "contactnumber",
                "primaryphone",
                "emergencyphone",
                "emergencycontactphone"
            ),
            Redact
        ),
        Entry("fax", "fax numbers", Set("fax", "faxnumber", "faxno", "faxnum"), Redact),
        Entry(
            "email",
            "email addresses",
            Set("email", "emailaddress", "emailaddr", "contactemail", "primaryemail", "workemail", "homeemail", "personalemail"),
            Redact
        ),
        Entry(
            "ssn",
            "social security numbers",
            Set("ssn", "social", "socialsecuritynumber", "socialsecurityno", "socialsecuritynum", "socialsecurity", "socsecnum"),
            Drop
        ),
        Entry(
            "mrn",
            "medical record numbers",
            Set(
                "mrn",
                "medicalrecordnumber",
                "medicalrecordno",
                "medicalrecordnum",
                "medicalrecordid",
                "medrecno",
                "medrecnum",
                "chartnumber"
            ),
            Hmac
        ),
        Entry(
            "health_plan",
            "health plan beneficiary numbers",
            Set(
                "healthplanid",
                "healthplannumber",
                "healthplanbeneficiarynumber",
                "healthplanbeneficiaryid",
                "beneficiaryid",
                "beneficiarynumber",
                "insuranceid",
                "insurancenumber",
                "policynumber",
                "policyno",
                "groupnumber",
                "memberid",
                "mbrid",
                "membernumber",
                "subscriberid",
                "subscribernumber",
                "medicaidid",
                "medicaidnumber",
                "medicareid",
                "medicarenumber",
                "mbi",
                "hicn"
            ),
            Hmac
        ),
        Entry(
            "account",
            "account numbers",
            Set(
                "accountnumber",
                "account",
                "acct",
                "creditcardnumber",
                "cardnumber",
                "claimnumber",
                "prescriptionnumber",
                "accountno",
                "accountnum",
                "accountid",
                "acctnumber",
                "acctno",
                "acctnum",
                "acctid",
                "bankaccount",
                "bankaccountnumber",
                "billingaccountnumber",
                "iban"
            ),
            Hmac
        ),
        Entry(
            "license",
            "certificate/license numbers",
            Set(
                "licensenumber",
                "license",
                "licencenumber",
                "licenseno",
                "licenceno",
                "licensenum",
                "licenseid",
                "licenceid",
                "certificatenumber",
                "certificateno",
                "certificateid",
                "driverslicense",
                "driverslicence",
                "driverslicensenumber",
                "dlnumber"
            ),
            Hmac
        ),
        Entry(
            "vehicle",
            "vehicle identifiers and serial numbers, including license plate numbers",
            Set(
                "vin",
                "vehicleid",
                "vehicleidentificationnumber",
                "vehicleserialnumber",
                "vehicleserial",
                "licenseplate",
                "licenceplate",
                "licenseplatenumber",
                "plate",
                "platenumber"
            ),
            Hmac
        ),
        Entry(
            "device",
            "device identifiers and serial numbers",
            Set("deviceid", "deviceidentifier", "deviceserialnumber", "deviceserial", "serialnumber", "serialno", "udi", "imei", "macaddress"),
            Hmac
        ),
        Entry(
            "url",
            "web universal resource locators (URLs)",
            Set("url", "websiteurl", "weburl", "website", "homepage", "profileurl", "personalurl"),
            Redact
        ),
        Entry(
            "ip",
            "internet protocol (IP) address numbers",
            Set("ip", "ipaddress", "ipaddr", "ipv4", "ipv6", "ipv4address", "ipv6address", "clientip", "sourceip"),
            Redact
        ),
        Entry(
            "biometric",
            "biometric identifiers, including finger and voice prints",
            Set(
                "fingerprint",
                "fingerprints",
                "biometric",
                "biometrics",
                "biometricdata",
                "biometricid",
                "retinascan",
                "retina",
                "irisscan",
                "voiceprint",
                "faceprint",
                "palmprint"
            ),
            Drop
        ),
        Entry(
            "photo",
            "full-face photographs and any comparable images",
            Set(
                "photo",
                "photos",
                "photograph",
                "facephoto",
                "faceimage",
                "facialimage",
                "facialphoto",
                "headshot",
                "profilephoto",
                "profilepicture",
                "profileimage",
                "picture",
                "image",
                "imageurl",
                "photourl",
                "selfie"
            ),
            Drop
        ),
        Entry(
            "other_id",
            "any other unique identifying number, characteristic or code",
            Set(
                "nationalid",
                "nationalidentifier",
                "nationalidnumber",
                "uniqueidentifier",
                "passport",
                "passportnumber",
                "passportno",
                "taxid",
                "ein",
                "tin",
                "taxidnumber",
                "taxpayerid",
                "governmentid",
                "alienregistrationnumber"
            ),
            Hmac
        )
    )

    /** Normalised name → entry. Built once; a duplicate name in the table is a bug. */
    private val ByName: Map[String, Entry] = {
        val pairs = Table.flatMap(e => e.names.toList.map(_ -> e))
        val dupes = pairs.groupBy(_._1).filter(_._2.size > 1).keys
        require(dupes.isEmpty, "ProtectionPreset table names appear twice: " + dupes.mkString(", "))
        pairs.toMap
    }

    /** class label → Safe Harbor wording (first entry per class). */
    val Wording: Map[String, String] = Table.groupBy(_.klass).map { case (k, es) => k -> es.head.wording }

    /** Leading words dropped before lookup (when something follows them). */
    private val Prefixes: Set[String] = Set("patient", "pt", "member", "mbr", "subscriber", "insured", "guarantor", "client", "customer", "person")

    /** Last words that mark a free-text column (unclassified; review note). */
    private val FreeTextWords: Set[String] =
        Set("notes", "note", "comment", "comments", "description", "remarks", "remark", "narrative", "memo", "text", "freetext")

    private val CamelBoundary = "(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])"

    /** Lowercase words of a field name: separators and camelCase split. */
    private[util] def words(fieldName: String): List[String] =
        Option(fieldName)
            .getOrElse("")
            .trim
            .split("[^A-Za-z0-9]+")
            .toList
            .flatMap(_.split(CamelBoundary).toList)
            .map(_.toLowerCase)
            .filter(_.nonEmpty)

    /** Words after the leading prefixes are dropped (never down to nothing). */
    private def stripped(fieldName: String): List[String] = {
        var ws = words(fieldName)
        while (ws.size > 1 && Prefixes.contains(ws.head)) ws = ws.tail
        ws
    }

    /** Lookup order: the whole name (so `member_id` and `subscriber_number`
      * match before their prefix is dropped), the name without its leading
      * prefix, then that name without trailing digits (`phone2`). Exact
      * entries such as `zip4` win over the digit strip. */
    private def entryOf(fieldName: String): Option[Entry] = {
        val whole = words(fieldName).mkString
        val joined = stripped(fieldName).mkString
        if (joined.isEmpty) None
        else
            ByName
                .get(whole)
                .orElse(ByName.get(joined))
                .orElse(Option(joined.replaceAll("[0-9]+$", "")).filter(_.nonEmpty).flatMap(ByName.get))
    }

    /** (class label, default policy) for a field name, unclamped; None when the table does not recognise it. */
    def classify(fieldName: String): Option[(String, ProtectionPolicy)] =
        entryOf(fieldName).map(e => (e.klass, e.policy))

    private def isZip(fieldName: String): Boolean = entryOf(fieldName).exists(_.names eq ZipNames)

    private def isAge(fieldName: String): Boolean = stripped(fieldName).exists(w => w == "age" || w == "ages")

    private def isFreeText(fieldName: String): Boolean = stripped(fieldName).lastOption.exists(FreeTextWords.contains)

    private def isDocument(name: String): Boolean = FieldProtectionAdvisor.DocumentFields.contains(name.trim.toLowerCase)

    val AgeNote =
        "Ages over 89 must be aggregated into a single category of 90 or older, and a date or year of birth can reveal such an age; the preset does not aggregate ages, so review age and date fields."

    val ZipNote =
        "The first three digits of a ZIP code must be replaced with 000 where the area they cover holds 20,000 people or fewer; the preset keeps the first three digits and does not check population."

    def freeTextNote(names: List[String]): String =
        "Free-text fields (" + names.mkString(", ") +
            ") may contain identifiers inside their values; the preset classifies by name only, so review them."

    /** The preset's proposal for `fields`. `keyFields` and `destTypes` are the
      * pipeline's constraints (FieldProtectionAdvisor.constraintsOf); each
      * default policy is clamped exactly as the suggestion endpoint clamps.
      * Document fields (`_json`, `_xml`) are skipped. */
    def propose(fields: List[SchemaField], keyFields: Set[String], destTypes: Map[String, String]): Proposal = {
        val input = Option(fields).getOrElse(Nil).filter(f => f != null && f.name != null && f.name.trim.nonEmpty && !isDocument(f.name))
        val keys = Option(keyFields).getOrElse(Set.empty[String]).filter(_ != null).map(_.trim.toLowerCase)
        val dest = Option(destTypes).getOrElse(Map.empty[String, String]).map { case (k, v) => k.trim.toLowerCase -> v }

        val classified = scala.collection.mutable.ListBuffer[Classified]()
        val unclassified = scala.collection.mutable.ListBuffer[String]()
        input.foreach { f =>
            entryOf(f.name) match {
                case Some(e) =>
                    FieldProtectionAdvisor.constrain(f.name, f.`type`, e.policy, keys, dest) match {
                        case Right(policy) => classified += Classified(f.name, e.klass, policy, "Safe Harbor: " + e.wording)
                        case Left(why) => classified += Classified(f.name, e.klass, null, why)
                    }
                case None => unclassified += f.name
            }
        }

        val review = scala.collection.mutable.ListBuffer[String]()
        if (input.exists(f => isAge(f.name) || classified.exists(c => c.name == f.name && c.klass == "date"))) review += AgeNote
        if (input.exists(f => isZip(f.name))) review += ZipNote
        val free = input.filter(f => isFreeText(f.name) && classify(f.name).isEmpty).map(_.name)
        if (free.nonEmpty) review += freeTextNote(free)

        Proposal(HipaaSafeHarbor, classified.toList, unclassified.toList, review.toList)
    }

    /** The pipeline's preset, trimmed; None when unset or blank (the value is not checked here). */
    def presetOf(config: PipelineConfig): Option[String] =
        Option(config).flatMap(c => Option(c.protection)).flatMap(p => Option(p.preset)).map(_.trim).filter(_.nonEmpty)

    /** The pipeline's preset when it is one this server supports. */
    def supportedPresetOf(config: PipelineConfig): Option[String] = presetOf(config).map(_.toLowerCase).filter(Presets.contains)

    /** (field name, class label) for every source field the preset recognises
      * that has no `protect` and is not listed in `protection.presetExempt`
      * (case-insensitive). Empty when the pipeline sets no supported preset. */
    /** `protection.presetExempt`, trimmed and lowercased. */
    def exemptOf(config: PipelineConfig): Set[String] =
        Option(config)
            .flatMap(c => Option(c.protection))
            .flatMap(p => Option(p.presetExempt))
            .map(_.asScala.toList)
            .getOrElse(Nil)
            .filter(_ != null)
            .map(_.trim.toLowerCase)
            .toSet

    def missing(config: PipelineConfig): List[(String, String)] = {
        if (supportedPresetOf(config).isEmpty) return Nil
        val sp = if (config.source != null) config.source.schemaProperties else null
        if (sp == null || sp.fields == null) return Nil
        val exempt = exemptOf(config)
        sp.fields.asScala.toList
            .filter(f => f != null && f.name != null && f.name.trim.nonEmpty && f.protect == null && !isDocument(f.name))
            .filterNot(f => exempt.contains(f.name.trim.toLowerCase))
            .flatMap(f => classify(f.name).map(c => (f.name, c._1)))
    }

    private def policyFields(o: JsonObject, p: ProtectionPolicy): Unit =
        if (p == null || p.method == null) {
            o.addProperty("method", "none")
            o.add("preserve", JsonNull.INSTANCE)
        } else {
            o.addProperty("method", p.method)
            if (p.preserve != null) o.addProperty("preserve", p.preserve) else o.add("preserve", JsonNull.INSTANCE)
        }

    /** `{"preset", "fields": [{"name","class","method","preserve","reason","current"}], "unclassified": [...], "review": [...]}`.
      * `method` is "none" when a clamp leaves nothing; `current` is the field's stored `protect` or null. */
    def toJson(proposal: Proposal, current: Map[String, ProtectionPolicy]): String = {
        val root = new JsonObject()
        root.addProperty("preset", proposal.preset)
        val arr = new JsonArray()
        proposal.fields.foreach { c =>
            val o = new JsonObject()
            o.addProperty("name", c.name)
            o.addProperty("class", c.klass)
            policyFields(o, c.policy)
            o.addProperty("reason", c.reason)
            current.get(c.name) match {
                case Some(cur) if cur != null && cur.method != null =>
                    val co = new JsonObject()
                    co.addProperty("method", cur.method)
                    if (cur.preserve != null) co.addProperty("preserve", cur.preserve) else co.add("preserve", JsonNull.INSTANCE)
                    o.add("current", co)
                case _ => o.add("current", JsonNull.INSTANCE)
            }
            arr.add(o)
        }
        root.add("fields", arr)
        val un = new JsonArray()
        proposal.unclassified.foreach(un.add)
        root.add("unclassified", un)
        val rv = new JsonArray()
        proposal.review.foreach(rv.add)
        root.add("review", rv)
        new GsonBuilder().serializeNulls().create().toJson(root)
    }
}
