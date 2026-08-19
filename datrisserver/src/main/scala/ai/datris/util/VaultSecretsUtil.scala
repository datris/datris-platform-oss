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

object VaultSecretsUtilBuilder {
    def build(): SecretsManagerUtility = {
        val address = sys.env.getOrElse("VAULT_ADDR", "http://127.0.0.1:8200")
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
    private def resolveToken(): String = {
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
