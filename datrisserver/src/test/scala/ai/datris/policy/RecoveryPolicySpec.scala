package ai.datris.policy

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.incident.Thresholds
import org.scalatest.funsuite.AnyFunSuite

class RecoveryPolicySpec extends AnyFunSuite {

    private def policy(json: String): AgentPolicy = AgentPolicy.fromJson(json) match {
        case Right(p) => p
        case Left(err) => fail("expected a valid policy: " + err)
    }

    test("recovery defaults to off with the documented limits") {
        val p = policy("""{}""")
        assert(p.recovery == RecoverySettings())
        assert(p.recovery.mode == RecoverySettings.Off)
        assert(p.recovery.maxAiCallsPerIncident == 12)
        assert(p.recovery.maxActionsPerIncident == 3)
        assert(p.recovery.maxRuntimeMinutes == 15)
        assert(p.recovery.maxOpenIncidents == 10)
        assert(p.recovery.cooldownHours == 6)
    }

    test("recovery block parses and round-trips") {
        val p = policy(
            """{"recovery":{"mode":"autopilot","maxAiCallsPerIncident":5,"maxActionsPerIncident":2,"maxRuntimeMinutes":10,"maxOpenIncidents":4,"cooldownHours":12}}"""
        )
        assert(p.recovery.mode == RecoverySettings.Autopilot)
        assert(p.recovery.maxActionsPerIncident == 2)
        val back = policy(p.toJson.toString)
        assert(back.recovery == p.recovery)
    }

    test("bad recovery modes and out-of-range limits are rejected") {
        assert(AgentPolicy.fromJson("""{"recovery":{"mode":"yolo"}}""").isLeft)
        assert(AgentPolicy.fromJson("""{"recovery":{"maxRuntimeMinutes":0}}""").isLeft)
        assert(AgentPolicy.fromJson("""{"recovery":{"maxAiCallsPerIncident":"many"}}""").isLeft)
    }

    test("per-resource recovery override moves in either direction") {
        val p = policy("""{"recovery":{"mode":"propose"},"overrides":{"tap:prices":{"recovery":"autopilot"},"tap:hr-data":{"recovery":"off"}}}""")
        assert(p.effectiveRecoveryMode("tap", "prices") == RecoverySettings.Autopilot)
        assert(p.effectiveRecoveryMode("tap", "hr-data") == RecoverySettings.Off)
        assert(p.effectiveRecoveryMode("tap", "other") == RecoverySettings.Propose)
        assert(p.effectiveRecoveryMode("pipeline", "prices") == RecoverySettings.Propose)
    }

    test("recovery override coexists with action overrides in the same object and round-trips") {
        val p = policy("""{"actions":{"tap:run":"auto"},"overrides":{"tap:prices":{"tap:run":"approve","recovery":"autopilot"}}}""")
        assert(p.decide("tap:run", Some("tap"), Some("prices")) == PolicyMode.Approve)
        assert(p.effectiveRecoveryMode("tap", "prices") == RecoverySettings.Autopilot)
        val back = policy(p.toJson.toString)
        assert(back == p)
    }

    test("a bad recovery override mode is rejected, naming the resource") {
        val r = AgentPolicy.fromJson("""{"overrides":{"tap:prices":{"recovery":"sometimes"}}}""")
        assert(r.isLeft && r.left.get.contains("tap:prices"))
    }

    // Story: Incident evidence model (plans/stories/self-healing-1-evidence-model.md),
    // Acceptance bullet 5 — five verification thresholds on RecoverySettings.

    test("verification thresholds default to 25 / false / false / None / 0.10") {
        val r = policy("""{}""").recovery
        assert(r.maxRecordDeltaPct == 25)
        assert(!r.allowColumnRemoval)
        assert(!r.allowTypeChange)
        assert(r.minDqPassRate.isEmpty)
        assert(r.maxNullRateIncrease == 0.10)
        assert(r.thresholds == Thresholds(25.0, allowColumnRemoval = false, allowTypeChange = false, minDqPassRate = None, maxNullRateIncrease = 0.10))
    }

    test("an older recovery block without thresholds parses with the threshold defaults") {
        val r = policy("""{"recovery":{"mode":"propose","maxAiCallsPerIncident":5}}""").recovery
        assert(r.mode == RecoverySettings.Propose)
        assert(r.maxRecordDeltaPct == 25 && !r.allowColumnRemoval && !r.allowTypeChange && r.minDqPassRate.isEmpty && r.maxNullRateIncrease == 0.10)
    }

    test("maxRecordDeltaPct = 101 is rejected naming the field") {
        val r = AgentPolicy.fromJson("""{"recovery":{"maxRecordDeltaPct":101}}""")
        assert(r.isLeft && r.left.get.contains("maxRecordDeltaPct"), r)
    }

    test("maxNullRateIncrease = 1.5 is rejected naming the field") {
        val r = AgentPolicy.fromJson("""{"recovery":{"maxNullRateIncrease":1.5}}""")
        assert(r.isLeft && r.left.get.contains("maxNullRateIncrease"), r)
    }

    test("verification thresholds round-trip through toJson") {
        val p = policy(
            """{"recovery":{"mode":"propose","maxRecordDeltaPct":40,"allowColumnRemoval":true,"allowTypeChange":true,"minDqPassRate":0.9,"maxNullRateIncrease":0.2}}"""
        )
        assert(p.recovery.maxRecordDeltaPct == 40)
        assert(p.recovery.allowColumnRemoval && p.recovery.allowTypeChange)
        assert(p.recovery.minDqPassRate == Some(0.9))
        assert(p.recovery.maxNullRateIncrease == 0.2)
        val back = policy(p.toJson.toString)
        assert(back.recovery == p.recovery)
        assert(back.recovery.thresholds == Thresholds(
            40.0,
            allowColumnRemoval = true,
            allowTypeChange = true,
            minDqPassRate = Some(0.9),
            maxNullRateIncrease = 0.2
        ))
    }

    // Story: Scratch-first verification (plans/stories/self-healing-3-scratch-first-verify.md),
    // Acceptance bullet 1 — `recovery.verifyMode`: scratch-first (default) | real.

    test("verifyMode defaults to scratch-first, including an older recovery block without it") {
        assert(RecoverySettings.ScratchFirst == "scratch-first")
        assert(RecoverySettings().verifyMode == RecoverySettings.ScratchFirst)
        assert(policy("""{}""").recovery.verifyMode == RecoverySettings.ScratchFirst)
        assert(policy("""{"recovery":{"mode":"propose","maxAiCallsPerIncident":5}}""").recovery.verifyMode == RecoverySettings.ScratchFirst)
        assert(RecoverySettings.VerifyModes == Set("scratch-first", "real"))
    }

    test("verifyMode accepts real") {
        val r = policy("""{"recovery":{"mode":"propose","verifyMode":"real"}}""").recovery
        assert(r.verifyMode == "real")
        assert(r.mode == RecoverySettings.Propose)
    }

    test("verifyMode = always is rejected naming the field") {
        val r = AgentPolicy.fromJson("""{"recovery":{"verifyMode":"always"}}""")
        assert(r.isLeft, r)
        assert(r.left.get.contains("recovery.verifyMode must be scratch-first or real"), r)
    }

    test("verifyMode round-trips through toJson") {
        for (vm <- Seq("real", "scratch-first")) {
            val p = policy("""{"recovery":{"mode":"autopilot","verifyMode":"""" + vm + """"}}""")
            assert(p.recovery.verifyMode == vm)
            val json = p.toJson
            assert(json.getAsJsonObject("recovery").get("verifyMode").getAsString == vm, json)
            val back = policy(json.toString)
            assert(back.recovery == p.recovery)
        }
    }

    // Story: Schema-drift signal, credential and platform classifications
    // (plans/stories/self-healing-4-signals.md), Acceptance bullet 5 —
    // `recovery.allowSchemaDriftActions: Boolean = false`.

    test("allowSchemaDriftActions defaults false, including an older recovery block without it") {
        assert(!RecoverySettings().allowSchemaDriftActions)
        assert(!policy("""{}""").recovery.allowSchemaDriftActions)
        assert(!policy("""{"recovery":{"mode":"autopilot","maxAiCallsPerIncident":5}}""").recovery.allowSchemaDriftActions)
    }

    test("allowSchemaDriftActions parses and round-trips through toJson") {
        val p = policy("""{"recovery":{"mode":"autopilot","allowSchemaDriftActions":true}}""")
        assert(p.recovery.allowSchemaDriftActions)
        assert(p.recovery.mode == RecoverySettings.Autopilot)
        val json = p.toJson
        assert(json.getAsJsonObject("recovery").get("allowSchemaDriftActions").getAsBoolean, json)
        val back = policy(json.toString)
        assert(back.recovery == p.recovery)
        assert(back.recovery.allowSchemaDriftActions)

        val off = policy("""{"recovery":{"mode":"autopilot","allowSchemaDriftActions":false}}""")
        assert(policy(off.toJson.toString).recovery == off.recovery)
        assert(!off.recovery.allowSchemaDriftActions)
    }

    test("a non-boolean allowSchemaDriftActions is rejected naming the field") {
        val r = AgentPolicy.fromJson("""{"recovery":{"allowSchemaDriftActions":"yes"}}""")
        assert(r.isLeft && r.left.get.contains("recovery.allowSchemaDriftActions"), r)
    }

    // Story: Environment-aware recovery defaults
    // (plans/stories/self-healing-6-env-defaults-learn-notes.md), Acceptance bullet 1.
    //
    // Contract pinned here:
    // {{{
    // object RecoverySettings {
    //   /** dev (case-insensitive) -> autopilot, 50 % rows, column removal and type change allowed;
    //     * qa, production, any other label and None -> propose with the strict defaults. */
    //   def recommendedFor(label: Option[String]): RecoverySettings
    //   /** The per-field default AgentPolicy.fromJson uses for threshold fields absent from the JSON. */
    //   def thresholdDefaultsFor(label: Option[String]): ai.datris.incident.Thresholds
    // }
    // }}}
    // The label is EnvironmentLabel.current; specs set the `datris.env`
    // system property ("" = unset, and it shadows DATRIS_ENV).

    private def withLabel[T](label: String)(body: => T): T = {
        val saved = sys.props.get("datris.env")
        sys.props("datris.env") = label
        try body
        finally saved match {
                case Some(v) => sys.props("datris.env") = v
                case None => sys.props -= "datris.env"
            }
    }

    private val Strict = Thresholds(25.0, allowColumnRemoval = false, allowTypeChange = false, minDqPassRate = None, maxNullRateIncrease = 0.10)
    private val Loose = Thresholds(50.0, allowColumnRemoval = true, allowTypeChange = true, minDqPassRate = None, maxNullRateIncrease = 0.10)

    test("datris.env=dev: fromJson(\"{}\") keeps mode off but defaults the thresholds loose (50 / true / true)") {
        withLabel("dev") {
            for (json <- Seq("""{}""", """{"recovery":{}}""", """{"recovery":{"maxAiCallsPerIncident":5}}""")) {
                val r = policy(json).recovery
                assert(r.mode == RecoverySettings.Off, json + " -> " + r)
                assert(r.maxRecordDeltaPct == 50, json + " -> " + r)
                assert(r.allowColumnRemoval, json + " -> " + r)
                assert(r.allowTypeChange, json + " -> " + r)
                assert(r.verifyMode == RecoverySettings.ScratchFirst, "verifyMode does not vary by label: " + r)
                assert(r.thresholds == Loose, r.thresholds)
            }
        }
    }

    test("datris.env=qa, production or unset: fromJson(\"{}\") has the strict thresholds (25 / false / false)") {
        for (label <- Seq("qa", "production", "")) withLabel(label) {
            val r = policy("""{}""").recovery
            assert(r.mode == RecoverySettings.Off, "label '" + label + "': " + r)
            assert(r.maxRecordDeltaPct == 25, "label '" + label + "': " + r)
            assert(!r.allowColumnRemoval && !r.allowTypeChange, "label '" + label + "': " + r)
            assert(r.thresholds == Strict, "label '" + label + "': " + r.thresholds)
        }
    }

    test("recommendedFor: dev -> autopilot with loose thresholds; production / qa / None -> propose with strict thresholds") {
        val dev = RecoverySettings.recommendedFor(Some("dev"))
        assert(dev.mode == RecoverySettings.Autopilot)
        assert(dev.maxRecordDeltaPct == 50 && dev.allowColumnRemoval && dev.allowTypeChange, dev)
        assert(RecoverySettings.recommendedFor(Some("DEV")).mode == RecoverySettings.Autopilot, "the dev label is case-insensitive")

        for (label <- Seq(Some("production"), Some("qa"), None)) {
            val r = RecoverySettings.recommendedFor(label)
            assert(r.mode == RecoverySettings.Propose, label + " -> " + r)
            assert(r.maxRecordDeltaPct == 25 && !r.allowColumnRemoval && !r.allowTypeChange, label + " -> " + r)
            assert(r.verifyMode == RecoverySettings.ScratchFirst, label + " -> " + r)
        }

        assert(RecoverySettings.thresholdDefaultsFor(Some("dev")) == Loose)
        assert(RecoverySettings.thresholdDefaultsFor(Some("Dev")) == Loose)
        assert(RecoverySettings.thresholdDefaultsFor(Some("qa")) == Strict)
        assert(RecoverySettings.thresholdDefaultsFor(Some("production")) == Strict)
        assert(RecoverySettings.thresholdDefaultsFor(None) == Strict)
    }

    test("a stored block that sets maxRecordDeltaPct = 10 keeps 10 under any label") {
        for (label <- Seq("dev", "qa", "production", "")) withLabel(label) {
            val r = policy("""{"recovery":{"mode":"propose","maxRecordDeltaPct":10}}""").recovery
            assert(r.maxRecordDeltaPct == 10, "label '" + label + "': " + r)
        }
    }

    test("toJson -> fromJson is identity under a different label (explicit fields win)") {
        val unlabelled = withLabel("")(policy("""{"recovery":{"mode":"propose"}}"""))
        assert(unlabelled.recovery.maxRecordDeltaPct == 25)
        val readOnDev = withLabel("dev")(policy(unlabelled.toJson.toString))
        assert(readOnDev.recovery == unlabelled.recovery, "a saved strict block stays strict on dev: " + readOnDev.recovery)

        val dev = withLabel("dev")(policy("""{"recovery":{"mode":"autopilot"}}"""))
        assert(dev.recovery.maxRecordDeltaPct == 50)
        for (label <- Seq("production", "qa", "")) {
            val back = withLabel(label)(policy(dev.toJson.toString))
            assert(back.recovery == dev.recovery, "a saved loose block stays loose on '" + label + "': " + back.recovery)
        }
    }

    test("no stored policy: the empty policy carries the label's threshold defaults, same as fromJson(\"{}\")") {
        withLabel("dev") {
            val e = AgentPolicy.emptyForCurrentLabel
            assert(e.recovery.mode == RecoverySettings.Off, e.recovery)
            assert(e.recovery.thresholds == Loose, e.recovery.thresholds)
            assert(e == policy("""{}"""), e)
        }
        for (label <- Seq("qa", "production", "")) withLabel(label) {
            val e = AgentPolicy.emptyForCurrentLabel
            assert(e.recovery.thresholds == Strict, "label '" + label + "': " + e.recovery.thresholds)
            assert(e == policy("""{}"""), "label '" + label + "': " + e)
        }
    }
}
