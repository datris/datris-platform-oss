package ai.datris.util.aiutil

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import com.google.gson.{JsonArray, JsonObject, JsonParser}
import ai.datris.model.{AIConfig, DatrisException}
import ai.datris.util.SecretsUtil
import org.apache.http.client.methods.{HttpGet, HttpPost, HttpRequestBase}
import org.apache.http.entity.StringEntity
import org.apache.http.util.EntityUtils
import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, AwsSessionCredentials, DefaultCredentialsProvider}
import software.amazon.awssdk.http.{ContentStreamProvider, SdkHttpMethod, SdkHttpRequest}
import software.amazon.awssdk.http.auth.aws.signer.{AwsV4FamilyHttpSigner, AwsV4HttpSigner}
import software.amazon.awssdk.identity.spi.AwsCredentialsIdentity
import software.amazon.awssdk.regions.providers.DefaultAwsRegionProviderChain

import java.io.ByteArrayInputStream
import java.net.{URI, URLEncoder}
import java.nio.charset.StandardCharsets
import org.slf4j.{Logger, LoggerFactory}
import scala.collection.JavaConverters._

/** Amazon Bedrock support: AWS credential resolution, SigV4 request signing,
  * invoke-endpoint derivation, request-body adaptation, and model discovery
  * (ListFoundationModels + ListInferenceProfiles).
  *
  * Bedrock serves Claude models over the Anthropic Messages wire shape, so the
  * request/response handling lives in the existing anthropic branches of
  * AIHttp / AIStreaming / AIResponseParser. What differs is captured here:
  *
  *   - Auth is AWS SigV4 (access key / secret / optional session token), not an
  *     API key header. Signing service name is "bedrock" for both the runtime
  *     (invoke) and control-plane (discovery) endpoints.
  *   - The invoke URL carries the model id in the path
  *     (`/model/{modelId}/invoke`), and the body must NOT contain `model` or
  *     `stream`, but MUST carry `anthropic_version: "bedrock-2023-05-31"`.
  *   - Streaming uses AWS event-stream binary framing (not SSE), so all Bedrock
  *     calls are non-streaming; the Assistant path synthesizes sink events at
  *     the end, same as the Azure fallback.
  *
  * Credential resolution mirrors CredentialResolver.resolveS3's philosophy:
  * explicit stored credentials first, then env vars (single-tenant only), then
  * the AWS default provider chain — so IAM-role / instance-profile deployments
  * work with zero stored secrets. Region is stored alongside the credential
  * (ai-keys `awsRegion`) so it stays bound to the account that owns it.
  */
object BedrockSupport {
    private val logger: Logger = LoggerFactory.getLogger(getClass)

    /** SigV4 credential-scope service name — "bedrock" for both
      * bedrock-runtime.{region} and bedrock.{region} endpoints. */
    private val SIGNING_SERVICE = "bedrock"

    private val ANTHROPIC_BEDROCK_VERSION = "bedrock-2023-05-31"

    /** Resolved AWS identity + region for a Bedrock call. */
    case class AwsCreds(identity: AwsCredentialsIdentity, region: String)

    // Held as singletons so the default chain's internal caching (IMDS creds,
    // profile file) applies across calls instead of re-resolving per request.
    private lazy val defaultCredentialsProvider = DefaultCredentialsProvider.builder().build()
    private lazy val defaultRegionChain = DefaultAwsRegionProviderChain.builder().build()
    private lazy val signer = AwsV4HttpSigner.create()

    /** Resolve AWS credentials + region for Bedrock, in priority order:
      *
      *   1. The shared key store `{env}/ai-keys` — fields `awsAccessKeyId`,
      *      `awsSecretAccessKey`, optional `awsSessionToken`, `awsRegion`.
      *   2. `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` / `AWS_SESSION_TOKEN`
      *      env vars — single-tenant only, matching resolveApiKey's contract.
      *   3. The AWS default provider chain (instance role / IRSA / profile) —
      *      also single-tenant only; a multi-tenant tenant must never ride the
      *      platform's instance role.
      *
      * Region resolves independently through the same tiers (`awsRegion` field,
      * `AWS_REGION`/`AWS_DEFAULT_REGION` env, default region chain). Throws a
      * DatrisException naming what's missing — a Bedrock call cannot proceed
      * without both an identity and a region. */
    def resolveCredentials(env: String, multiTenant: Boolean): AwsCreds = {
        val store: Map[String, String] =
            try SecretsUtil.getSecretMap(env + "/ai-keys").map(_.asScala.toMap).getOrElse(Map.empty)
            catch {
                case e: Exception =>
                    logger.debug("Could not read " + env + "/ai-keys for Bedrock credentials — falling through", e)
                    Map.empty
            }
        def storeField(name: String): String = store.get(name).map(_.trim).filter(_.nonEmpty).getOrElse("")

        val region = {
            val fromStore = storeField("awsRegion")
            if (fromStore.nonEmpty) fromStore
            else if (!multiTenant && sys.env.get("AWS_REGION").exists(_.nonEmpty)) sys.env("AWS_REGION")
            else if (!multiTenant && sys.env.get("AWS_DEFAULT_REGION").exists(_.nonEmpty)) sys.env("AWS_DEFAULT_REGION")
            else if (!multiTenant) {
                try defaultRegionChain.getRegion.id()
                catch {
                    case _: Exception =>
                        throw new DatrisException(
                            "Bedrock requires an AWS region. Set it in the Configuration tab (AWS Region), " +
                                "via the AWS_REGION environment variable, or through the AWS default region chain."
                        )
                }
            } else throw new DatrisException("Bedrock requires an AWS region — set awsRegion in the AI key store.")
        }

        val ak = storeField("awsAccessKeyId")
        val sk = storeField("awsSecretAccessKey")
        val st = storeField("awsSessionToken")
        val identity: AwsCredentialsIdentity =
            if (ak.nonEmpty && sk.nonEmpty) {
                if (st.nonEmpty) AwsSessionCredentials.create(ak, sk, st) else AwsBasicCredentials.create(ak, sk)
            } else if (ak.nonEmpty || sk.nonEmpty) {
                throw new DatrisException(
                    "Bedrock AWS credentials are incomplete: both awsAccessKeyId and awsSecretAccessKey are required " +
                        "(or leave both blank to use the server's IAM role / default AWS credential chain)."
                )
            } else if (!multiTenant) {
                // Blank store: env vars are part of the default chain, so a single
                // resolveCredentials() covers env vars, ~/.aws, and IAM roles alike.
                try defaultCredentialsProvider.resolveCredentials()
                catch {
                    case e: Exception =>
                        throw new DatrisException(
                            "No AWS credentials available for Bedrock. Enter an access key in the Configuration tab, " +
                                "set AWS_ACCESS_KEY_ID/AWS_SECRET_ACCESS_KEY, or run the server under an IAM role. " +
                                "(" + e.getMessage + ")"
                        )
                }
            } else throw new DatrisException("Bedrock AWS credentials not found in the AI key store for this tenant.")

        AwsCreds(identity, region)
    }

    /** The effective invoke URL for a Bedrock chat call. `endpoint` in the slot
      * secret is optional: blank derives the standard regional runtime endpoint;
      * a base URL (GovCloud, VPC endpoint) gets the model path appended; a full
      * URL already containing `/model/` is used as-is. */
    def invokeEndpoint(aiConfig: AIConfig, region: String): String = {
        val model = Option(aiConfig.model).map(_.trim).getOrElse("")
        if (model.isEmpty) throw new DatrisException("Bedrock requires a model id (e.g. anthropic.claude-sonnet-5).")
        val enc = URLEncoder.encode(model, "UTF-8")
        val configured = Option(aiConfig.endpoint).map(_.trim).getOrElse("")
        if (configured.isEmpty) "https://bedrock-runtime." + region + ".amazonaws.com/model/" + enc + "/invoke"
        else if (configured.contains("/model/")) configured
        else configured.stripSuffix("/") + "/model/" + enc + "/invoke"
    }

    /** Adapt an Anthropic-Messages-shaped request body for Bedrock's invoke API:
      * the model id rides in the URL (never the body), streaming is not
      * supported over this path (AWS event-stream framing lives on a different
      * endpoint), and `anthropic_version` is required. Everything else —
      * messages, system, tools, thinking, max_tokens — passes through
      * unchanged. */
    def transformBodyForInvoke(jsonBody: String): String = {
        val obj = JsonParser.parseString(jsonBody).getAsJsonObject
        obj.remove("model")
        obj.remove("stream")
        if (!obj.has("anthropic_version")) obj.addProperty("anthropic_version", ANTHROPIC_BEDROCK_VERSION)
        obj.toString
    }

    /** Build a SigV4-signed HttpPost for a Bedrock endpoint. Signing happens per
      * call (executeWithRetry re-invokes its factory each attempt, so retries get
      * a fresh X-Amz-Date). */
    def signedPost(endpoint: String, body: String, creds: AwsCreds): HttpPost = {
        val bodyBytes = body.getBytes(StandardCharsets.UTF_8)
        val unsigned = SdkHttpRequest.builder()
            .method(SdkHttpMethod.POST)
            .uri(URI.create(endpoint))
            .putHeader("Content-Type", "application/json")
            .build()
        val payload = new ContentStreamProvider {
            override def newStream() = new ByteArrayInputStream(bodyBytes)
        }
        val signed = signer.sign((r: software.amazon.awssdk.http.auth.spi.signer.SignRequest.Builder[AwsCredentialsIdentity]) =>
            r.identity(creds.identity)
                .request(unsigned)
                .payload(payload)
                .putProperty(AwsV4FamilyHttpSigner.SERVICE_SIGNING_NAME, SIGNING_SERVICE)
                .putProperty(AwsV4HttpSigner.REGION_NAME, creds.region)
        )
        val httpPost = new HttpPost(endpoint)
        copySignedHeaders(signed.request(), httpPost)
        httpPost.setEntity(new StringEntity(body, StandardCharsets.UTF_8))
        httpPost
    }

    /** Build a SigV4-signed HttpGet against the Bedrock control plane
      * (discovery APIs). `queryParams` values must be URL-safe as passed. */
    private def signedGet(baseUrl: String, path: String, queryParams: Seq[(String, String)], creds: AwsCreds): HttpGet = {
        var builder = SdkHttpRequest.builder()
            .method(SdkHttpMethod.GET)
            .uri(URI.create(baseUrl + path))
        queryParams.foreach { case (k, v) => builder = builder.appendRawQueryParameter(k, v) }
        val unsigned = builder.build()
        val signed = signer.sign((r: software.amazon.awssdk.http.auth.spi.signer.SignRequest.Builder[AwsCredentialsIdentity]) =>
            r.identity(creds.identity)
                .request(unsigned)
                .putProperty(AwsV4FamilyHttpSigner.SERVICE_SIGNING_NAME, SIGNING_SERVICE)
                .putProperty(AwsV4HttpSigner.REGION_NAME, creds.region)
        )
        val query = if (queryParams.isEmpty) "" else "?" + queryParams.map { case (k, v) => k + "=" + v }.mkString("&")
        val httpGet = new HttpGet(baseUrl + path + query)
        copySignedHeaders(signed.request(), httpGet)
        httpGet
    }

    /** Copy the signer's output headers onto an Apache request. Host and
      * Content-Length are computed by HttpClient itself — re-adding them either
      * duplicates or throws. */
    private def copySignedHeaders(signed: SdkHttpRequest, target: HttpRequestBase): Unit = {
        signed.headers().asScala.foreach { case (name, values) =>
            if (!name.equalsIgnoreCase("Host") && !name.equalsIgnoreCase("Content-Length")) {
                values.asScala.foreach(v => target.setHeader(name, v))
            }
        }
    }

    private def executeControlPlane(request: HttpRequestBase, label: String): String = {
        val response = AIHttp.sslClient.execute(request)
        try {
            val status = response.getStatusLine.getStatusCode
            val body = EntityUtils.toString(response.getEntity, StandardCharsets.UTF_8)
            if (status != 200)
                throw new DatrisException("Bedrock " + label + " returned status " + status + ": " + body.take(500))
            body
        } finally response.close()
    }

    /** Discover invokable Anthropic Claude models in the account/region.
      * Returns `{"provider":"bedrock","models":[{"value","label"}]}` where
      * `value` is an id that will actually work in an invoke call: the bare
      * modelId when the model supports ON_DEMAND inference, else the matching
      * cross-region inference-profile id (e.g. `us.anthropic....`). Models with
      * neither are dropped — offering them would just produce invoke errors. */
    def listModels(env: String, multiTenant: Boolean): String = {
        val creds = resolveCredentials(env, multiTenant)
        val base = "https://bedrock." + creds.region + ".amazonaws.com"

        val foundationModels = executeControlPlane(
            signedGet(base, "/foundation-models", Seq("byProvider" -> "Anthropic"), creds),
            "ListFoundationModels"
        )

        // ListInferenceProfiles paginates via nextToken; walk all pages.
        val profilePages = scala.collection.mutable.ListBuffer.empty[String]
        var nextToken: Option[String] = None
        var firstPage = true
        while (firstPage || nextToken.isDefined) {
            firstPage = false
            val params = Seq("maxResults" -> "1000") ++ nextToken.map("nextToken" -> _)
            val page = executeControlPlane(
                signedGet(base, "/inference-profiles", params, creds),
                "ListInferenceProfiles"
            )
            profilePages += page
            val obj = JsonParser.parseString(page).getAsJsonObject
            nextToken =
                if (obj.has("nextToken") && !obj.get("nextToken").isJsonNull) Some(obj.get("nextToken").getAsString)
                else None
        }

        val models = mergeDiscovery(foundationModels, profilePages.toList)
        val result = new JsonObject()
        result.addProperty("provider", "bedrock")
        result.add("models", models)
        result.toString
    }

    /** Pure merge of the two discovery payloads into a dropdown-ready list.
      * Package-private for unit testing. */
    private[aiutil] def mergeDiscovery(foundationModelsJson: String, inferenceProfilesJsons: List[String]): JsonArray = {
        // profile lookup: foundation-model modelId (last ARN path segment) -> profileId
        val profileByModelId = scala.collection.mutable.LinkedHashMap.empty[String, String]
        inferenceProfilesJsons.foreach { pageJson =>
            val page = JsonParser.parseString(pageJson).getAsJsonObject
            val summaries = if (page.has("inferenceProfileSummaries")) page.getAsJsonArray("inferenceProfileSummaries") else new JsonArray()
            summaries.asScala.map(_.getAsJsonObject).foreach { p =>
                val status = if (p.has("status")) p.get("status").getAsString else "ACTIVE"
                val profileId = if (p.has("inferenceProfileId")) p.get("inferenceProfileId").getAsString else ""
                if (status == "ACTIVE" && profileId.nonEmpty && p.has("models")) {
                    p.getAsJsonArray("models").asScala.map(_.getAsJsonObject).foreach { m =>
                        if (m.has("modelArn")) {
                            val arn = m.get("modelArn").getAsString
                            val modelId = arn.substring(arn.lastIndexOf('/') + 1)
                            // First profile wins — SYSTEM_DEFINED regional profiles come
                            // first and are the ones we want to surface.
                            if (!profileByModelId.contains(modelId)) profileByModelId.put(modelId, profileId)
                        }
                    }
                }
            }
        }

        val out = new JsonArray()
        val fm = JsonParser.parseString(foundationModelsJson).getAsJsonObject
        val summaries = if (fm.has("modelSummaries")) fm.getAsJsonArray("modelSummaries") else new JsonArray()
        summaries.asScala.map(_.getAsJsonObject).foreach { m =>
            val modelId = if (m.has("modelId")) m.get("modelId").getAsString else ""
            val name = if (m.has("modelName")) m.get("modelName").getAsString else modelId
            val provider = if (m.has("providerName")) m.get("providerName").getAsString else ""
            val active =
                !m.has("modelLifecycle") ||
                    m.getAsJsonObject("modelLifecycle").get("status").getAsString == "ACTIVE"
            val textOutput =
                !m.has("outputModalities") ||
                    m.getAsJsonArray("outputModalities").asScala.exists(_.getAsString == "TEXT")
            val onDemand =
                m.has("inferenceTypesSupported") &&
                    m.getAsJsonArray("inferenceTypesSupported").asScala.exists(_.getAsString == "ON_DEMAND")

            if (modelId.nonEmpty && active && textOutput && provider.equalsIgnoreCase("Anthropic")) {
                val invokableId = if (onDemand) modelId else profileByModelId.getOrElse(modelId, "")
                if (invokableId.nonEmpty) {
                    val entry = new JsonObject()
                    entry.addProperty("value", invokableId)
                    entry.addProperty("label", name)
                    out.add(entry)
                }
            }
        }
        out
    }
}
