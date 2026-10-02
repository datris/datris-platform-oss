package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{DatrisEnvironment, DatrisException}
import org.slf4j.{Logger, LoggerFactory}

import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import scala.collection.JavaConverters._
import scala.util.{Failure, Success, Try}

/** The per-environment HMAC key behind `protect: {"method": "hmac"}`.
  *
  * Lives in the secret `<environment>/field-protection`, field `key`: 64 hex
  * characters from SecureRandom, issued by the server itself the first time a
  * run needs it (audited as `key / issue / field-protection`). Never rotated:
  * a new key would change every token and break joins on existing data.
  * Cached per environment name once read. */
object FieldProtectionKey {
    private val logger: Logger = LoggerFactory.getLogger(getClass)

    val Field = "key"

    private val cache = new ConcurrentHashMap[String, Array[Byte]]()
    private val random = new SecureRandom()

    private def secretName(env: String): String = env + "/field-protection"

    /** The key bytes (the 32 bytes the stored hex encodes), issuing the secret on first use. */
    def ensure(): Array[Byte] =
        ensure(
            DatrisEnvironment.current.environment,
            name => SecretsUtil.tryGetSecretMap(name).map(_.map(_.asScala.toMap)),
            (name, data) => SecretsUtil.writeSecret(name, data),
            () => ai.datris.audit.AuditLog.system("key", "issue", "field-protection", Field)
        )

    /** Fails closed: a read error never issues a key (that would silently
      * rotate it and break every existing token). Only a secret that does
      * not exist is created; an existing secret without a usable `key` is an
      * error, never overwritten. */
    private[datris] def ensure(
        env: String,
        read: String => Try[Option[Map[String, String]]],
        write: (String, java.util.Map[String, Object]) => Unit,
        audit: () => Unit
    ): Array[Byte] = {
        val cached = cache.get(env)
        if (cached != null) return cached
        this.synchronized {
            val again = cache.get(env)
            if (again != null) return again
            val name = secretName(env)
            val hex = read(name) match {
                case Failure(_) =>
                    throw new DatrisException("Field protection: could not read the hmac key secret")
                case Success(Some(m)) =>
                    m.get(Field).map(_.trim).filter(_.nonEmpty).getOrElse(
                        throw new DatrisException("Field protection: the hmac key secret has no '" + Field + "' value")
                    )
                case Success(None) =>
                    val h = randomHex(32)
                    val data = new java.util.LinkedHashMap[String, Object]()
                    data.put(Field, h)
                    write(name, data)
                    audit()
                    logger.info("Issued the field-protection key for environment " + env)
                    h
            }
            val bytes = decodeHex(hex)
            cache.put(env, bytes)
            bytes
        }
    }

    /** Test seam: forget cached keys. */
    private[datris] def clearCache(): Unit = cache.clear()

    private def randomHex(bytes: Int): String = {
        val b = new Array[Byte](bytes)
        random.nextBytes(b)
        b.map(x => f"${x & 0xff}%02x").mkString
    }

    private[datris] def decodeHex(hex: String): Array[Byte] = {
        if (hex.length % 2 != 0 || !hex.matches("[0-9a-fA-F]+"))
            throw new DatrisException("The field-protection key secret is not a hex string")
        hex.grouped(2).map(Integer.parseInt(_, 16).toByte).toArray
    }
}
