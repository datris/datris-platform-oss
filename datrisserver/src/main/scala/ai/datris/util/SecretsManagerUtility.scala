package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

trait SecretsManagerUtility {
    def getSecretMap(secretName: String): Option[java.util.Map[String, String]]

    /** Like [[getSecretMap]] but keeps "absent" (Success(None)) apart from
      * "read failed" (Failure) — for security-sensitive callers that must
      * fail closed when the secret store errors. The default delegates to
      * getSecretMap and so cannot tell the two apart; backends override. */
    def tryGetSecretMap(secretName: String): scala.util.Try[Option[java.util.Map[String, String]]] =
        scala.util.Try(getSecretMap(secretName))
    def listSecrets(path: String): List[String]
    def writeSecret(secretName: String, data: java.util.Map[String, Object]): Unit
    def deleteSecret(secretName: String): Unit
}
