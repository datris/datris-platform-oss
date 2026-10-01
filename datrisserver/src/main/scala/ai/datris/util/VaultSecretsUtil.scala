package ai.datris.util

import io.github.jopenlibs.vault.{Vault, VaultConfig}
import org.slf4j.{Logger, LoggerFactory}

class VaultSecretsUtil(val vault: Vault) extends SecretsManagerUtility {

    private val logger: Logger = LoggerFactory.getLogger(getClass)

    override def getSecretMap(secretName: String): Option[java.util.Map[String, String]] = {
        try {
            val response = vault.logical().read(s"secret/$secretName")
            val data = response.getData
            if (data == null || data.isEmpty) None
            else Some(data)
        } catch {
            case e: Exception =>
                logger.error("Vault read failed for secret path: secret/" + secretName, e)
                None
        }
    }

    /** Absent path (Vault 404, or no data) → Success(None); any other read
      * error → Failure, so callers can fail closed.
      *
      * The vault-java-driver does NOT throw for 4xx statuses (other than
      * 412): a 403 from an expired server token comes back as a response
      * with empty data, indistinguishable from an absent secret unless the
      * HTTP status is inspected. So the decision is made on the status, not
      * on exceptions. */
    override def tryGetSecretMap(secretName: String): scala.util.Try[Option[java.util.Map[String, String]]] = {
        try {
            val response = vault.logical().read(s"secret/$secretName")
            val status = Option(response.getRestResponse).map(_.getStatus).getOrElse(200)
            val result = VaultSecretsUtil.readResult(status, response.getData)
            result.failed.foreach(e => logger.error("Vault read failed for secret path: secret/" + secretName + ": " + e.getMessage))
            result
        } catch {
            case e: Exception =>
                logger.error("Vault read failed for secret path: secret/" + secretName, e)
                scala.util.Failure(e)
        }
    }

    def getSecretField(secretName: String, field: String): Option[String] = {
        getSecretMap(secretName).flatMap(map => Option(map.get(field)))
    }

    override def listSecrets(path: String): List[String] = {
        try {
            val response = vault.logical().list(s"secret/$path")
            val keys = response.getListData
            if (keys == null || keys.isEmpty) List.empty
            else {
                import scala.collection.JavaConverters._
                keys.asScala.toList.sorted
            }
        } catch {
            case e: Exception =>
                logger.error("Vault list failed for path: secret/" + path, e)
                List.empty
        }
    }

    override def writeSecret(secretName: String, data: java.util.Map[String, Object]): Unit = {
        vault.logical().write(s"secret/$secretName", data)
    }

    override def deleteSecret(secretName: String): Unit = {
        vault.logical().delete(s"secret/$secretName")
    }
}

object VaultSecretsUtil {

    /** Pure classification of a Vault KV read: 200 with data → Some, 200
      * without data or 404 → None (absent), anything else → Failure. */
    def readResult(status: Int, data: java.util.Map[String, String]): scala.util.Try[Option[java.util.Map[String, String]]] =
        status match {
            case 200 => scala.util.Success(if (data == null || data.isEmpty) None else Some(data))
            case 404 => scala.util.Success(None)
            case other => scala.util.Failure(new ai.datris.model.DatrisException(s"Vault read returned HTTP $other"))
        }
}

object VaultSecretsUtilBuilder {

    /** Vault base URL the server talks to (also used by the doctor's raw
      * `lookup-self` probe, which the KV-v2 client can't express). */
    def vaultAddress: String = sys.env.getOrElse("VAULT_ADDR", "http://127.0.0.1:8200")

    def build(): SecretsManagerUtility = {
        val address = vaultAddress
        val token = resolveToken()

        val config = new VaultConfig()
            .address(address)
            .token(token)
            .engineVersion(2)
            .build()

        new VaultSecretsUtil(Vault.create(config))
    }

    /** Resolve the Vault token. Prefer VAULT_TOKEN_FILE (a path to a file
      * holding the token) so the bootstrap can hand the server a RANDOM,
      * per-install token instead of the old well-known `root-token` — the
      * file never appears in `docker inspect`/process env the way a value does.
      * Falls back to the VAULT_TOKEN env var for setups that still pass it
      * directly (local dev, existing deployments). */
    private[util] def resolveToken(): String = {
        val fromFile = sys.env.get("VAULT_TOKEN_FILE")
            .map(_.trim)
            .filter(_.nonEmpty)
            .flatMap { path =>
                try {
                    val t = new String(
                        java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path)),
                        java.nio.charset.StandardCharsets.UTF_8
                    ).trim
                    if (t.nonEmpty) Some(t) else None
                } catch {
                    case _: Exception => None
                }
            }
        fromFile.orElse(sys.env.get("VAULT_TOKEN")).getOrElse {
            val hint = sys.env
                .get("VAULT_TOKEN_FILE")
                .map(p =>
                    " VAULT_TOKEN_FILE=" + p + " is set but the file was missing, empty, or unreadable" +
                        " (the server runs as a non-root user — ensure the token file is world-readable)."
                )
                .getOrElse("")
            throw new IllegalStateException(
                "No Vault token available: neither VAULT_TOKEN_FILE nor VAULT_TOKEN provided one." + hint
            )
        }
    }
}
