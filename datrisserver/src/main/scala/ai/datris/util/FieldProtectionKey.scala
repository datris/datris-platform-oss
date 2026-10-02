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

/** The per-environment keys behind field protection, all in the secret
  * `<environment>/field-protection`:
  *
  *  - `key`: the HMAC key behind `protect: {"method": "hmac"}`. 64 hex
  *    characters from SecureRandom, issued by the server itself the first
  *    time a run needs it (audited as `key / issue / field-protection`).
  *    Never rotated: a new key would change every token and break joins on
  *    existing data.
  *  - `enc.v<n>` + `encCurrent`: the versioned AES-256 keys behind
  *    `{"method": "encrypt"}` (story 5). `encCurrent` names the version new
  *    values are encrypted with; every older version stays readable for
  *    reveal until an operator deletes it from the secret by hand. Rotation
  *    ([[rotateEncryptionKey]]) adds a version, never replaces one.
  *
  * Whichever of `ensure()` / `encryptionKey()` finds the secret absent issues
  * `key` AND `enc.v1`/`encCurrent` in one write, so a fresh environment never
  * ends up with a secret holding only one of them. A Vault KV write replaces
  * the whole secret, so every write carries every existing field. Keys never
  * leave the server. Cached per environment once read. */
object FieldProtectionKey {
    private val logger: Logger = LoggerFactory.getLogger(getClass)

    val Field = "key"
    val EncCurrent = "encCurrent"
    val EncPrefix = "enc.v"

    private val cache = new ConcurrentHashMap[String, Array[Byte]]()
    private val encCache = new ConcurrentHashMap[String, (Int, Array[Byte])]()
    private val random = new SecureRandom()

    private def secretName(env: String): String = env + "/field-protection"

    private def encField(version: Int): String = EncPrefix + version

    private type Read = String => Try[Option[Map[String, String]]]
    private type Write = (String, java.util.Map[String, Object]) => Unit

    private def productionRead: Read = name => SecretsUtil.tryGetSecretMap(name).map(_.map(_.asScala.toMap))
    private def productionWrite: Write = (name, data) => SecretsUtil.writeSecret(name, data)
    private def productionAudit: (String, String) => Unit =
        (action, field) => ai.datris.audit.AuditLog.system("key", action, "field-protection", field)

    /** The HMAC key bytes (the 32 bytes the stored hex encodes), issuing the secret on first use. */
    def ensure(): Array[Byte] =
        ensure(DatrisEnvironment.current.environment, productionRead, productionWrite, () => productionAudit("issue", Field), productionAudit)

    /** Fails closed: a read error never issues a key (that would silently
      * rotate it and break every existing token). Only a secret that does
      * not exist is created; an existing secret without a usable `key` is an
      * error, never overwritten. */
    private[datris] def ensure(env: String, read: Read, write: Write, audit: () => Unit): Array[Byte] =
        ensure(env, read, write, audit, (_, _) => ())

    private def ensure(env: String, read: Read, write: Write, audit: () => Unit, encAudit: (String, String) => Unit): Array[Byte] = {
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
                    val (_, h, e) = issueAll(env, name, write)
                    audit()
                    encAudit("issue", encField(1))
                    encCache.put(env, (1, decodeHex(e)))
                    h
            }
            val bytes = decodeHex(hex)
            cache.put(env, bytes)
            bytes
        }
    }

    /** First write of the secret: `key`, `enc.v1` and `encCurrent` together. */
    private def issueAll(env: String, name: String, write: Write): (java.util.Map[String, Object], String, String) = {
        val h = randomHex(32)
        val e = randomHex(32)
        val data = new java.util.LinkedHashMap[String, Object]()
        data.put(Field, h)
        data.put(encField(1), e)
        data.put(EncCurrent, "1")
        write(name, data)
        logger.info("Issued the field-protection keys for environment " + env)
        (data, h, e)
    }

    private def copyOf(m: Map[String, String]): java.util.LinkedHashMap[String, Object] = {
        val data = new java.util.LinkedHashMap[String, Object]()
        m.foreach { case (k, v) => data.put(k, v) }
        data
    }

    private def encVersions(m: Map[String, String]): Seq[Int] =
        m.keys.toSeq.flatMap { k =>
            if (k.startsWith(EncPrefix)) Try(k.substring(EncPrefix.length).toInt).toOption.filter(_ > 0) else None
        }

    private def encKeyOf(m: Map[String, String], version: Int): Array[Byte] = {
        val hex = m.get(encField(version)).map(_.trim).filter(_.nonEmpty).getOrElse(
            throw new DatrisException("Field protection: encryption key version " + version + " does not exist")
        )
        val bytes = decodeHex(hex)
        if (bytes.length != 32) throw new DatrisException("Field protection: encryption key version " + version + " is not a 256-bit key")
        bytes
    }

    private def currentOf(m: Map[String, String]): Option[Int] =
        m.get(EncCurrent).map(_.trim).filter(_.nonEmpty).map { s =>
            Try(s.toInt).toOption.filter(_ > 0).getOrElse(
                throw new DatrisException("Field protection: the key secret's '" + EncCurrent + "' is not a version number")
            )
        }

    /** The current encryption key version and bytes, issuing `enc.v1` on first use. */
    def encryptionKey(): (Int, Array[Byte]) =
        encryptionKey(DatrisEnvironment.current.environment, productionRead, productionWrite, productionAudit)

    /** Fails closed like [[ensure]]: a read error never issues; an existing
      * secret with versions but no usable `encCurrent` is an error. An
      * existing secret with no encryption key yet gains `enc.v1` +
      * `encCurrent` (every other field carried over); an absent secret gets
      * `key` too. `audit` is (action, secret field). */
    private[datris] def encryptionKey(env: String, read: Read, write: Write, audit: (String, String) => Unit): (Int, Array[Byte]) = {
        val cached = encCache.get(env)
        if (cached != null) return cached
        this.synchronized {
            val again = encCache.get(env)
            if (again != null) return again
            val name = secretName(env)
            val current: (Int, Array[Byte]) = read(name) match {
                case Failure(_) =>
                    throw new DatrisException("Field protection: could not read the encryption key secret")
                case Success(Some(m)) =>
                    currentOf(m) match {
                        case Some(v) => (v, encKeyOf(m, v))
                        case None =>
                            if (encVersions(m).nonEmpty)
                                throw new DatrisException("Field protection: the key secret has encryption keys but no '" + EncCurrent + "'")
                            val e = randomHex(32)
                            val data = copyOf(m)
                            data.put(encField(1), e)
                            data.put(EncCurrent, "1")
                            write(name, data)
                            audit("issue", encField(1))
                            logger.info("Issued the field-protection encryption key v1 for environment " + env)
                            (1, decodeHex(e))
                    }
                case Success(None) =>
                    val (_, h, e) = issueAll(env, name, write)
                    audit("issue", Field)
                    audit("issue", encField(1))
                    cache.put(env, decodeHex(h))
                    (1, decodeHex(e))
            }
            encCache.put(env, current)
            current
        }
    }

    /** One encryption key version, for reveal. Never issues anything. */
    def encryptionKey(version: Int): Array[Byte] = encryptionKey(DatrisEnvironment.current.environment, version, productionRead)

    private[datris] def encryptionKey(env: String, version: Int, read: Read): Array[Byte] = {
        val cached = encCache.get(env)
        if (cached != null && cached._1 == version) return cached._2
        read(secretName(env)) match {
            case Failure(_) => throw new DatrisException("Field protection: could not read the encryption key secret")
            case Success(None) => throw new DatrisException("Field protection: encryption key version " + version + " does not exist")
            case Success(Some(m)) => encKeyOf(m, version)
        }
    }

    /** Issue `enc.v<n+1>` and make it current; every older version stays. */
    def rotateEncryptionKey(): Int =
        rotateEncryptionKey(DatrisEnvironment.current.environment, productionRead, productionWrite, productionAudit)

    /** As [[rotateEncryptionKey()]], with the caller's audit (the REST
      * controller records it against the request so the actor is named). */
    def rotateEncryptionKey(audit: (String, String) => Unit): Int =
        rotateEncryptionKey(DatrisEnvironment.current.environment, productionRead, productionWrite, audit)

    /** The new version is one above the highest of `encCurrent` and every
      * stored `enc.v<n>`, so a retired version number is never reused. The
      * hmac `key` is carried over untouched. A secret with no encryption key
      * yet gets `enc.v1` (an issue, not a rotation). Fails closed on a read
      * error. */
    private[datris] def rotateEncryptionKey(env: String, read: Read, write: Write, audit: (String, String) => Unit): Int =
        this.synchronized {
            val name = secretName(env)
            val m = read(name) match {
                case Failure(_) => throw new DatrisException("Field protection: could not read the encryption key secret")
                case Success(None) => None
                case Success(Some(x)) => Some(x)
            }
            val version = m match {
                case None =>
                    issueAll(env, name, write)
                    audit("issue", Field)
                    audit("issue", encField(1))
                    1
                case Some(existing) =>
                    val highest = (encVersions(existing) ++ currentOf(existing).toSeq).foldLeft(0)(math.max)
                    val next = highest + 1
                    val data = copyOf(existing)
                    data.put(encField(next), randomHex(32))
                    data.put(EncCurrent, next.toString)
                    write(name, data)
                    audit(if (highest == 0) "issue" else "rotate", encField(next))
                    logger.info("Rotated the field-protection encryption key to v" + next + " for environment " + env)
                    next
            }
            encCache.remove(env)
            version
        }

    /** Test seam: forget cached keys (hmac and encryption). */
    private[datris] def clearCache(): Unit = {
        cache.clear()
        encCache.clear()
    }

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
