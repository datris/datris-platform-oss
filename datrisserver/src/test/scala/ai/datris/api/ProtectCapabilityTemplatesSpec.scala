package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.incident.RecoveryKey
import ai.datris.model.Capability
import org.scalatest.funsuite.AnyFunSuite

/** Field protection 5 (plans/stories/field-protection-5-encrypt-reveal.md):
  * `protect:reveal` is never handed out by default. Calls only
  * `KeysAPIController.Templates` (private[api], hence this package) and
  * `RecoveryKey.Capabilities`; every capability string is parsed with
  * `Capability.parse` so a wildcard (`*:*`, `protect:*`) counts too. */
class ProtectCapabilityTemplatesSpec extends AnyFunSuite {

    private def grantsReveal(caps: Seq[String]): Boolean =
        caps.map(Capability.parse).exists(_.matchesResourceAction("protect", "reveal"))

    // The `full-access` template is `*:*` (admin-equivalent, like an admin
    // session's FullAccess) and so grants reveal through the wildcard; the
    // story leaves that template unchanged. Every other template must not
    // grant reveal, by name or by wildcard.
    private val AdminEquivalent = Set("full-access")

    test("no key template includes protect:reveal") {
        assert(KeysAPIController.Templates.nonEmpty)
        KeysAPIController.Templates.foreach { case (name, _, caps) =>
            assert(!caps.exists(_.startsWith("protect:")), s"template $name lists a protect capability: $caps")
            if (!AdminEquivalent.contains(name))
                assert(!grantsReveal(caps), s"template $name grants protect:reveal: $caps")
        }
        assert(KeysAPIController.Templates.exists(t => AdminEquivalent.contains(t._1)), "full-access template renamed? revisit this spec")
    }

    test("the recovery agent key does not carry protect capabilities") {
        assert(!grantsReveal(RecoveryKey.Capabilities), RecoveryKey.Capabilities)
        assert(!RecoveryKey.Capabilities.exists(_.startsWith("protect:")))
    }
}
