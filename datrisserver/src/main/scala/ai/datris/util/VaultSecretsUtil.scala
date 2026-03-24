package ai.datris.util

import io.github.jopenlibs.vault.{Vault, VaultConfig}

class VaultSecretsUtil(val vault: Vault) extends SecretsManagerUtility {

    override def getSecretMap(secretName: String): Option[java.util.Map[String, String]] = {
        try {
            val response = vault.logical().read(s"secret/$secretName")
            val data = response.getData
            if (data == null || data.isEmpty) None
            else Some(data)
        } catch {
            case e: Exception =>
                e.printStackTrace()
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
                e.printStackTrace()
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
        val token = sys.env("VAULT_TOKEN")

        val config = new VaultConfig()
            .address(address)
            .token(token)
            .engineVersion(2)
            .build()

        new VaultSecretsUtil(Vault.create(config))
    }
}
