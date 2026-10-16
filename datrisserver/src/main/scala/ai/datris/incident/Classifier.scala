package ai.datris.incident

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

/** Deterministic failure classifications the runner applies in code, so a
  * model's proposal cannot talk past them. Pure. */
object Classifier {

    /** An authentication / authorization failure from the source. */
    private val CredentialPattern =
        """(?i)\b(401|403)\b|unauthori[sz]ed|forbidden|invalid_grant|invalid[_ ]token|token (has )?expired|credentials? (are |is )?(expired|invalid|rejected)""".r

    /** True when the trigger error or the latest run's logs show the source
      * rejected the tap's credentials. Either argument may be null. */
    def credential(error: String, logs: String): Boolean =
        Seq(error, logs).exists(s => s != null && s.nonEmpty && CredentialPattern.findFirstIn(s).isDefined)

    val Credential = "credential"
    val Platform = "platform"

    /** The `error` checks of a doctor report (DoctorService.Report.toJson)
      * that are not tap-specific (`tap.*` checks describe one tap's setup,
      * which the diagnosis handles): (id, detail, remediation). Unparseable
      * or check-less input is no errors. */
    def platformErrors(doctorJson: String): List[(String, String, String)] =
        try {
            val el = com.google.gson.JsonParser.parseString(Option(doctorJson).getOrElse(""))
            if (!el.isJsonObject || !el.getAsJsonObject.has("checks") || !el.getAsJsonObject.get("checks").isJsonArray) Nil
            else {
                def str(o: com.google.gson.JsonObject, k: String): String =
                    if (o.has(k) && o.get(k).isJsonPrimitive) o.get(k).getAsString else ""
                val it = el.getAsJsonObject.getAsJsonArray("checks").iterator()
                val buf = List.newBuilder[(String, String, String)]
                while (it.hasNext) {
                    val c = it.next()
                    if (c.isJsonObject) {
                        val o = c.getAsJsonObject
                        val id = str(o, "id")
                        if (str(o, "status") == "error" && !id.startsWith("tap.")) buf += ((id, str(o, "detail"), str(o, "remediation")))
                    }
                }
                buf.result()
            }
        } catch {
            case _: Exception => Nil
        }
}
