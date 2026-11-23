package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.auth.TapPrincipal.{Resolution, Resolved, Unresolved}
import ai.datris.model.{Capability, ResolvedKey, TapConfig, User}
import ai.datris.util.{APIKeyValidator, TapOwnerScopeScan}
import org.scalatest.funsuite.AnyFunSuite

/** Tap runs and owner access, report-only release, review follow-up: the
  * next release gives a tap's run token only the reads its owning identity
  * holds, so a tap owned by a key made from any shipped template
  * (KeysAPIController.Templates) that lacks a read the run token carries is
  * reported when its last run used the callback, and never when it did not. */
class TapOwnerKeyTemplatesSpec extends AnyFunSuite {

    private def templateKey(name: String): ResolvedKey = {
        val caps = KeysAPIController.Templates.find(_._1 == name).getOrElse(fail("no template " + name))._3
        ResolvedKey(None, name + "-key", Capability.parseList(caps), isLegacyFullAccess = false)
    }

    private def scanOwnedBy(rk: ResolvedKey, usedCallback: Boolean): TapOwnerScopeScan.Result = {
        val tap = TapConfig(
            name = "t-" + rk.label,
            description = "d",
            targetPipeline = null,
            catalog = "Sales",
            createdByKeyLabel = rk.label,
            lastRunUsedCallback = usedCallback
        )
        val owners: Map[String, Resolution] = Map(rk.label -> Resolved(rk))
        TapOwnerScopeScan.scan(List(tap), _ => null, l => owners.getOrElse(l, Unresolved), _ => null)
    }

    /** Template -> the plain words its reads finding carries. */
    private val expected: Map[String, Option[String]] = Map(
        "read-only" -> Some("cannot run every kind of query (natural)"),
        "rag-builder" -> Some("cannot query data, cannot read metadata"),
        "reporting" -> Some("cannot run every kind of query (natural)"),
        "ops" -> Some("cannot query data, cannot run vector search, cannot read metadata"),
        "full-access" -> None
    )

    test("every shipped template is covered here") {
        assert(KeysAPIController.Templates.map(_._1).toSet == expected.keySet)
    }

    for ((name, words) <- expected) {
        test("template " + name + " as tap owner, callback used: " + words.map("reported, its owner " + _).getOrElse("not reported")) {
            val rk = templateKey(name)
            val r = scanOwnedBy(rk, usedCallback = true)
            words match {
                case Some(w) =>
                    assert(r.findings.map(_.group) == List(TapOwnerScopeScan.ReadsNarrowed), r.toString)
                    assert(r.findings.head.reason == "its owner " + w, r.findings.head.reason)
                    assert(TapOwnerScopeScan.callerReadsNarrowed(Some(rk), usedCallback = true), "rule 1 for the same key as caller")
                case None =>
                    assert(r.findings.isEmpty, r.toString)
                    assert(!TapOwnerScopeScan.callerReadsNarrowed(Some(rk), usedCallback = true))
            }
            // none of the templates is limited to catalogs or to what it created
            assert(TapOwnerScopeScan.limitedWords(rk).isEmpty, name)
        }

        test("template " + name + " as tap owner, callback not used: not reported") {
            val rk = templateKey(name)
            assert(scanOwnedBy(rk, usedCallback = false).findings.isEmpty)
            assert(!TapOwnerScopeScan.callerReadsNarrowed(Some(rk), usedCallback = false))
        }
    }

    test("the user roles: an administrator, an unrestricted editor and an unrestricted viewer are not reported; a restricted user is") {
        def user(role: String, restricted: Boolean = false) =
            APIKeyValidator.resolveFromSession(User(
                "u-" + role,
                "h",
                role,
                null,
                null,
                null,
                restricted = restricted,
                catalogGrants = ai.datris.util.InMemoryUserDb.grants("Sales" -> "read")
            ))
        for (role <- Seq(User.RoleAdmin, User.RoleEditor, User.RoleViewer))
            assert(scanOwnedBy(user(role), usedCallback = true).findings.isEmpty, role)
        val restricted = scanOwnedBy(user(User.RoleViewer, restricted = true), usedCallback = true)
        assert(restricted.findings.map(_.reason) == List("its owner is limited to the catalogs it is granted"), restricted.toString)
    }
}
