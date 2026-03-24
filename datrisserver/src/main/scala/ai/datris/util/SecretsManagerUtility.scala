package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
*/

trait SecretsManagerUtility {
    def getSecretMap(secretName: String): Option[java.util.Map[String, String]]
    def listSecrets(path: String): List[String]
    def writeSecret(secretName: String, data: java.util.Map[String, Object]): Unit
    def deleteSecret(secretName: String): Unit
}
