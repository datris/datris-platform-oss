package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{CodeRepoConfig, DatrisEnvironment, DatrisException}
import com.google.gson.{Gson, JsonObject, JsonParser}
import org.apache.http.client.config.RequestConfig
import org.apache.http.client.methods.{HttpDelete, HttpEntityEnclosingRequestBase, HttpGet, HttpPut, HttpUriRequest}
import org.apache.http.entity.StringEntity
import org.apache.http.impl.client.HttpClients
import org.apache.http.util.EntityUtils
import org.slf4j.{Logger, LoggerFactory}

import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.Base64

/** A file read from the repo: decoded source plus the shas needed to pin,
  * cache, and compare-and-swap it. `blobSha` is the file's git blob sha (what
  * the contents API wants back on update/delete); `commitSha` is the branch
  * head the read was resolved against (what TapConfig pins). */
case class RepoFile(content: String, blobSha: String, commitSha: String)

/** Thrown when a write is rejected because the file changed in the repo since
  * the caller's base commit. Surfaced as HTTP 409 by the controller. */
class CodeRepoConflictException(message: String) extends RuntimeException(message)

/** Minimal server-side GitHub REST client for tap script storage. All calls
  * are authenticated with the tenant's Vault-held token; the UI never talks
  * to GitHub directly. Provider-neutral callers go through TapCodeStore —
  * this class is the github-provider implementation detail.
  */
object GithubClient {
    private val logger: Logger = LoggerFactory.getLogger(getClass)
    private val TimeoutMillis = 30000

    private def token(config: CodeRepoConfig): String = {
        val secretName = DatrisEnvironment.current.environment + "/" + config.authSecretName
        SecretsUtil.getSecretMap(secretName)
            .flatMap(m => Option(m.get("token")))
            .filter(_.nonEmpty)
            .getOrElse(throw new DatrisException(
                "Code repository token not found in secret '" + config.authSecretName + "' (expected a 'token' field)."
            ))
    }

    private def apiBase(config: CodeRepoConfig): String =
        Option(config.apiBaseUrl).filter(_.nonEmpty).getOrElse("https://api.github.com").stripSuffix("/")

    private def contentsUrl(config: CodeRepoConfig, path: String): String =
        apiBase(config) + "/repos/" + config.repo + "/contents/" +
            path.split("/").filter(_.nonEmpty).map(seg => java.net.URLEncoder.encode(seg, "UTF-8").replace("+", "%20")).mkString("/")

    /** Executes with auth headers; returns (status, body). */
    private def execute(request: HttpUriRequest, config: CodeRepoConfig): (Int, String) = {
        request.setHeader("Authorization", "Bearer " + token(config))
        request.setHeader("Accept", "application/vnd.github+json")
        request.setHeader("X-GitHub-Api-Version", "2022-11-28")
        val requestConfig = RequestConfig.custom()
            .setConnectTimeout(TimeoutMillis)
            .setSocketTimeout(TimeoutMillis)
            .setConnectionRequestTimeout(TimeoutMillis)
            .build()
        val client = HttpClients.custom().setDefaultRequestConfig(requestConfig).build()
        try {
            val response = client.execute(request)
            try {
                val entity = response.getEntity
                val body = if (entity != null) EntityUtils.toString(entity, StandardCharsets.UTF_8) else ""
                (response.getStatusLine.getStatusCode, body)
            } finally response.close()
        } finally client.close()
    }

    private def parse(body: String): JsonObject = JsonParser.parseString(body).getAsJsonObject

    /** Maps GitHub's 4xx flavors onto messages the UI can show verbatim. */
    private def fail(status: Int, body: String, context: String): Nothing = {
        val detail =
            try { Option(parse(body).get("message")).map(_.getAsString).getOrElse("") }
            catch { case _: Exception => "" }
        val hint = status match {
            case 401 => "Token was rejected. It may be expired or revoked — update the repo token secret."
            case 403 if detail.toLowerCase.contains("rate limit") => "GitHub rate limit exceeded. Retry later."
            case 403 if detail.toLowerCase.contains("sso") || detail.toLowerCase.contains("saml") =>
                "The organization enforces SSO — authorize the token for this organization in GitHub settings."
            case 403 => "Token lacks access. Fine-grained tokens may be disabled by org policy, or the token is missing Contents read/write on this repo."
            case 404 => "Repository or path not found — check the repo name, or the token cannot see this repository."
            case _ => ""
        }
        throw new DatrisException(("Code repository " + context + " failed (HTTP " + status + "). " + detail + " " + hint).trim)
    }

    /** GET /repos/{repo} — used by save-time validation and Test connection.
      * Returns (defaultBranch, canPush). Write access comes from the
      * `permissions` block; no probe commits. */
    def repoInfo(config: CodeRepoConfig): (String, Boolean) = {
        val (status, body) = execute(new HttpGet(apiBase(config) + "/repos/" + config.repo), config)
        if (status != 200) fail(status, body, "validation")
        val json = parse(body)
        val defaultBranch = Option(json.get("default_branch")).map(_.getAsString).getOrElse("main")
        val canPush = Option(json.getAsJsonObject("permissions")).exists(p => Option(p.get("push")).exists(_.getAsBoolean))
        (defaultBranch, canPush)
    }

    def listBranches(config: CodeRepoConfig): List[String] = {
        val (status, body) = execute(new HttpGet(apiBase(config) + "/repos/" + config.repo + "/branches?per_page=100"), config)
        if (status != 200) fail(status, body, "branch listing")
        val arr = JsonParser.parseString(body).getAsJsonArray
        (0 until arr.size()).map(i => arr.get(i).getAsJsonObject.get("name").getAsString).toList
    }

    /** Resolve the configured branch to its current head commit sha. */
    def branchHeadSha(config: CodeRepoConfig): String = {
        val (status, body) = execute(
            new HttpGet(apiBase(config) + "/repos/" + config.repo + "/branches/" + java.net.URLEncoder.encode(config.branch, "UTF-8")),
            config
        )
        if (status != 200) fail(status, body, "branch lookup for '" + config.branch + "'")
        parse(body).getAsJsonObject("commit").get("sha").getAsString
    }

    /** Read a file at a ref (commit sha or branch). None if it doesn't exist
      * at that ref. `commitShaForPin` is what the caller records as the pin —
      * pass the resolved head when reading a branch. */
    def getFile(config: CodeRepoConfig, path: String, ref: String): Option[RepoFile] = {
        val (status, body) = execute(new HttpGet(contentsUrl(config, path) + "?ref=" + java.net.URLEncoder.encode(ref, "UTF-8")), config)
        if (status == 404) return None
        if (status != 200) fail(status, body, "read of '" + path + "'")
        val json = parse(body)
        val encoded = json.get("content").getAsString.replaceAll("\\s", "")
        val content = new String(Base64.getDecoder.decode(encoded), StandardCharsets.UTF_8)
        Some(RepoFile(content, json.get("sha").getAsString, ref))
    }

    /** Create or update a file; returns the new commit sha.
      *
      * Optimistic concurrency (plan decision #4): when `baseCommitSha` is
      * given and the file already exists, the write only proceeds if the
      * file's blob at the branch head matches its blob at `baseCommitSha` —
      * i.e. nobody changed THIS file since the caller last read it. GitHub's
      * own `sha` parameter enforces the same check race-free at commit time.
      */
    def putFile(config: CodeRepoConfig, path: String, content: String, message: String, baseCommitSha: String): String = {
        val headBlob = getFile(config, path, config.branch)
        if (baseCommitSha != null && baseCommitSha.nonEmpty && headBlob.isDefined) {
            val baseBlob = getFile(config, path, baseCommitSha)
            if (baseBlob.isDefined && baseBlob.get.blobSha != headBlob.get.blobSha)
                throw new CodeRepoConflictException(
                    "'" + path + "' changed in the repository since this tap was loaded. Pull the latest script, reapply your edits, and save again."
                )
        }

        val payload = new JsonObject
        payload.addProperty("message", message)
        payload.addProperty("content", Base64.getEncoder.encodeToString(content.getBytes(StandardCharsets.UTF_8)))
        payload.addProperty("branch", config.branch)
        headBlob.foreach(f => payload.addProperty("sha", f.blobSha))
        commitIdentity(config).foreach { identity =>
            payload.add("committer", identity)
            payload.add("author", identity)
        }

        val put = new HttpPut(contentsUrl(config, path))
        put.setEntity(new StringEntity(new Gson().toJson(payload), StandardCharsets.UTF_8))
        val (status, body) = execute(put, config)
        if (status == 409 || status == 422)
            throw new CodeRepoConflictException(
                "'" + path + "' changed in the repository since this tap was loaded. Pull the latest script, reapply your edits, and save again."
            )
        if (status != 200 && status != 201) fail(status, body, "write of '" + path + "'")
        parse(body).getAsJsonObject("commit").get("sha").getAsString
    }

    /** Commit a file removal. No-op if the file is already absent. */
    def deleteFile(config: CodeRepoConfig, path: String, message: String): Unit = {
        getFile(config, path, config.branch) match {
            case None => // already gone
            case Some(file) =>
                val payload = new JsonObject
                payload.addProperty("message", message)
                payload.addProperty("sha", file.blobSha)
                payload.addProperty("branch", config.branch)
                commitIdentity(config).foreach { identity =>
                    payload.add("committer", identity)
                    payload.add("author", identity)
                }
                val delete = new HttpDeleteWithBody(contentsUrl(config, path))
                delete.setEntity(new StringEntity(new Gson().toJson(payload), StandardCharsets.UTF_8))
                val (status, body) = execute(delete, config)
                if (status != 200) fail(status, body, "delete of '" + path + "'")
        }
    }

    /** Parses "Name <email>" into the contents-API identity object. None when
      * unset/unparseable — GitHub then attributes the commit to the token. */
    private[util] def commitIdentity(config: CodeRepoConfig): Option[JsonObject] = {
        Option(config.commitAuthor).map(_.trim).filter(_.nonEmpty).flatMap { author =>
            val pattern = "^(.+?)\\s*<([^>]+)>$".r
            author match {
                case pattern(name, email) =>
                    val identity = new JsonObject
                    identity.addProperty("name", name.trim)
                    identity.addProperty("email", email.trim)
                    Some(identity)
                case _ =>
                    logger.warn("commitAuthor '" + author + "' is not in 'Name <email>' form; letting GitHub attribute the commit")
                    None
            }
        }
    }

    /** Apache's HttpDelete does not carry a body; GitHub's contents DELETE
      * requires one (message + sha + branch). */
    private class HttpDeleteWithBody(url: String) extends HttpEntityEnclosingRequestBase {
        setURI(URI.create(url))
        override def getMethod: String = "DELETE"
    }
}
