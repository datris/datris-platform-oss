package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{AIConfig, DatrisEnvironment, TenantContext}
import org.scalatest.Assertions

import java.nio.file.{Files, Path}

/** Shared by the field-protection-9 marker specs
  * (plans/stories/field-protection-9-ai-values-switch.md): marker values
  * planted in samples, the system-property switch (restored after every use),
  * and a tenant environment whose AI is "enabled" but points at a dead port so
  * a helper that bypasses the injected AI function fails instead of calling out. */
trait AiSampleValuesMarkers extends Assertions {

    val SwitchKey = "datris.aiSampleValues"

    val Markers: Seq[String] =
        Seq("ZQX-NAME-1", "ZQX-SSN-2", "ZQX-EMAIL-3", "ZQX-CITY-4", "ZQX-ID-5", "ZQX-NOTE-6", "ZQX-NAME-7", "918273645")

    def withSwitch[A](value: Option[String])(body: => A): A = {
        val previous = sys.props.get(SwitchKey)
        value match {
            case Some(v) => sys.props(SwitchKey) = v
            case None => sys.props -= SwitchKey
        }
        try body
        finally previous match {
                case Some(v) => sys.props(SwitchKey) = v
                case None => sys.props -= SwitchKey
            }
    }

    /** Switch off for `body`. */
    def withheld[A](body: => A): A = withSwitch(Some("false"))(body)

    /** Switch at its default (unset) for `body`; skips when the shell sets the env var. */
    def sampled[A](body: => A): A = {
        org.scalatest.Assertions.assume(sys.env.get("DATRIS_AI_SAMPLE_VALUES").isEmpty, "DATRIS_AI_SAMPLE_VALUES is set in this shell")
        withSwitch(None)(body)
    }

    def assertNoMarker(text: String): Unit =
        Markers.foreach(m => assert(!text.contains(m), "marker " + m + " reached the prompt:\n" + text))

    def assertHasMarkers(text: String, expected: Seq[String]): Unit =
        expected.foreach(m => assert(text.contains(m), "marker " + m + " expected in the prompt (switch on):\n" + text))

    lazy val stagingRoot: Path = Files.createTempDirectory("ai-sample-values-spec")

    def testEnv: DatrisEnvironment = DatrisEnvironment(
        initialized = true,
        environment = "test",
        fileNotifierQueue = null,
        ttlFileNotifierQueueMessages = 0,
        pipelineTopic = null,
        pipelineTableName = null,
        archivedMetadataTableName = null,
        pipelineStatusTableName = null,
        fileNotifierMessageTableName = null,
        dataPullTableName = null,
        useApiKeys = false,
        apiKeysSecretName = null,
        postgresSecretName = null,
        mongoDbSecretName = null,
        kafkaProducerSecretName = null,
        kafkaConsumerConfig = null,
        mongoDbConfig = null,
        minIOConfig = null,
        activeMQConfig = null,
        aiConfig = AIConfig("anthropic", "http://127.0.0.1:1/v1/messages", "spec-model", "spec-key"),
        aiEnabled = true,
        embeddingSecretName = null,
        qdrantSecretName = null,
        weaviateSecretName = null,
        milvusSecretName = null,
        chromaSecretName = null,
        pgvectorSecretName = null,
        multiTenant = false,
        tempDir = stagingRoot.toString
    )

    /** Run `body` with the spec environment and a staging token. */
    def inEnv[A](body: => A): A = {
        TenantContext.set(testEnv)
        try StagingArea.withToken("ai-sample-values-spec")(body)
        finally TenantContext.clear()
    }

    /** Thrown by a capturing AI function once it has the prompt, so a CodeGen
      * helper stops before running a script. */
    class PromptCaptured extends RuntimeException("prompt captured")

    /** Run `call`, swallowing whatever the helper does after the capture. */
    def ignoringAfterCapture(call: => Any): Unit =
        try { call; () }
        catch { case _: Throwable => () }
}
