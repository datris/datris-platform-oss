package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.DatrisException
import org.scalatest.BeforeAndAfterEach
import org.scalatest.funsuite.AnyFunSuite

import scala.collection.mutable.ListBuffer
import scala.util.{Failure, Success, Try}

/** FieldProtectionKey.ensure fails closed: a read error never mints a key.
  *
  * Story 5 (plans/stories/field-protection-5-encrypt-reveal.md) adds versioned
  * encryption keys beside the hmac `key` in `<env>/field-protection`: fields
  * `enc.v<n>` (64 hex chars) and `encCurrent` = `<n>`. Seams this spec calls,
  * the same (env, read, write, audit) shape as `ensure`:
  *
  * {{{
  * object FieldProtectionKey {
  *     // current (version, key); the first call issues enc.v1 + encCurrent=1, audit("issue", "enc.v1")
  *     private[datris] def encryptionKey(
  *         env: String,
  *         read: String => Try[Option[Map[String, String]]],
  *         write: (String, java.util.Map[String, Object]) => Unit,
  *         audit: (String, String) => Unit          // (action, secret field), e.g. ("rotate", "enc.v2")
  *     ): (Int, Array[Byte])
  *     // one version, for reveal; unknown version → DatrisException
  *     private[datris] def encryptionKey(env: String, version: Int, read: String => Try[Option[Map[String, String]]]): Array[Byte]
  *     // writes enc.v<n+1>, bumps encCurrent, keeps every other field, audit("rotate", "enc.v<n+1>"); returns n+1
  *     private[datris] def rotateEncryptionKey(
  *         env: String,
  *         read: String => Try[Option[Map[String, String]]],
  *         write: (String, java.util.Map[String, Object]) => Unit,
  *         audit: (String, String) => Unit
  *     ): Int
  *     private[datris] def clearCache(): Unit   // also forgets cached encryption keys
  * }
  * }}}
  *
  * A secret write replaces the whole secret (Vault KV), so every write must
  * carry all existing fields; the fake store below enforces that by replacing. */
class FieldProtectionKeySpec extends AnyFunSuite with BeforeAndAfterEach {

    override def beforeEach(): Unit = FieldProtectionKey.clearCache()
    override def afterEach(): Unit = FieldProtectionKey.clearCache()

    private val Hex = "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff"

    private class Recorder {
        val writes = new ListBuffer[(String, java.util.Map[String, Object])]()
        var audits = 0
        def write(n: String, d: java.util.Map[String, Object]): Unit = writes += ((n, d))
        def audit(): Unit = audits += 1
    }

    private def run(env: String, read: String => Try[Option[Map[String, String]]], r: Recorder): Array[Byte] =
        FieldProtectionKey.ensure(env, read, r.write, () => r.audit())

    test("a failed secret read throws and never writes a key") {
        val r = new Recorder
        val e = intercept[DatrisException](run("t1", _ => Failure(new RuntimeException("connection refused")), r))
        assert(e.getMessage.contains("could not read the hmac key secret"))
        assert(r.writes.isEmpty && r.audits == 0)
    }

    test("an existing secret without a key value is an error, never overwritten") {
        val r = new Recorder
        intercept[DatrisException](run("t2", _ => Success(Some(Map("other" -> "x"))), r))
        intercept[DatrisException](run("t2", _ => Success(Some(Map("key" -> " "))), r))
        assert(r.writes.isEmpty && r.audits == 0)
    }

    test("an existing key is read, decoded and cached, with no write") {
        val r = new Recorder
        var reads = 0
        val read: String => Try[Option[Map[String, String]]] = n => { reads += 1; assert(n == "t3/field-protection"); Success(Some(Map("key" -> Hex))) }
        val k = run("t3", read, r)
        assert(k.length == 32 && k(1) == 0x11.toByte)
        assert(run("t3", read, r) sameElements k)
        assert(reads == 1, "cached per environment")
        assert(r.writes.isEmpty && r.audits == 0)
    }

    test("an absent secret issues one 64-hex key and audits it") {
        val r = new Recorder
        val k = run("t4", _ => Success(None), r)
        assert(r.writes.size == 1 && r.audits == 1)
        val (name, data) = r.writes.head
        assert(name == "t4/field-protection")
        val hex = data.get("key").toString
        assert(hex.matches("[0-9a-f]{64}"))
        assert(FieldProtectionKey.decodeHex(hex) sameElements k)
    }

    // ==========================================================================
    // Story 5: versioned encryption keys
    // ==========================================================================

    /** A secret store that replaces the whole secret on write, like Vault KV. */
    private class Store(initial: Option[Map[String, String]]) {
        var secret: Option[Map[String, String]] = initial
        var writes = 0
        val audits = new ListBuffer[(String, String)]()
        val read: String => Try[Option[Map[String, String]]] = _ => Success(secret)
        val write: (String, java.util.Map[String, Object]) => Unit = (_, d) => {
            writes += 1
            import scala.collection.JavaConverters._
            secret = Some(d.asScala.map { case (k, v) => k -> String.valueOf(v) }.toMap)
        }
        val audit: (String, String) => Unit = (a, f) => audits += ((a, f))
    }

    private def current(env: String, s: Store): (Int, Array[Byte]) = FieldProtectionKey.encryptionKey(env, s.read, s.write, s.audit)
    private def rotate(env: String, s: Store): Int = FieldProtectionKey.rotateEncryptionKey(env, s.read, s.write, s.audit)

    test("the first encryptionKey() issues enc.v1 and encCurrent, audited, and leaves the hmac key alone") {
        val s = new Store(Some(Map("key" -> Hex)))
        val (v, k) = current("e1", s)
        assert(v == 1)
        assert(k.length == 32)
        val m = s.secret.get
        assert(m.get("key").contains(Hex), s"hmac key untouched: $m")
        assert(m.get("encCurrent").contains("1"), s"$m")
        assert(m.get("enc.v1").exists(_.matches("[0-9a-f]{64}")), s"$m")
        assert(FieldProtectionKey.decodeHex(m("enc.v1")) sameElements k)
        assert(!(k sameElements FieldProtectionKey.decodeHex(Hex)), "the encryption key is not the hmac key")
        assert(s.audits.toList == List(("issue", "enc.v1")))
        // Second call: same key, no new write or audit (cached / already issued).
        val (v2, k2) = current("e1", s)
        assert(v2 == 1 && (k2 sameElements k))
        assert(s.writes == 1 && s.audits.size == 1)
    }

    test("an existing enc version is read, not re-issued") {
        val enc = "ffeeddccbbaa99887766554433221100ffeeddccbbaa99887766554433221100"
        val s = new Store(Some(Map("key" -> Hex, "enc.v1" -> Hex, "enc.v3" -> enc, "encCurrent" -> "3")))
        val (v, k) = current("e2", s)
        assert(v == 3 && (k sameElements FieldProtectionKey.decodeHex(enc)))
        assert(s.writes == 0 && s.audits.isEmpty)
        assert(FieldProtectionKey.encryptionKey("e2", 1, s.read) sameElements FieldProtectionKey.decodeHex(Hex))
        intercept[DatrisException](FieldProtectionKey.encryptionKey("e2", 2, s.read))
    }

    test("rotate issues v2, keeps v1 readable and the hmac key untouched") {
        val s = new Store(Some(Map("key" -> Hex)))
        val (_, k1) = current("e3", s)
        val v1Hex = s.secret.get("enc.v1")
        assert(rotate("e3", s) == 2)
        val m = s.secret.get
        assert(m.get("key").contains(Hex), s"hmac key never rotated: $m")
        assert(m.get("enc.v1").contains(v1Hex), "v1 kept")
        assert(m.get("enc.v2").exists(h => h.matches("[0-9a-f]{64}") && h != v1Hex), s"$m")
        assert(m.get("encCurrent").contains("2"))
        assert(s.audits.toList == List(("issue", "enc.v1"), ("rotate", "enc.v2")))
        // Cache invalidated on rotate: the current key is v2 now.
        val (v, k2) = current("e3", s)
        assert(v == 2 && (k2 sameElements FieldProtectionKey.decodeHex(m("enc.v2"))))
        assert(!(k2 sameElements k1))
        // Both versions stay readable for reveal.
        assert(FieldProtectionKey.encryptionKey("e3", 1, s.read) sameElements k1)
        assert(FieldProtectionKey.encryptionKey("e3", 2, s.read) sameElements k2)
        // The hmac path still reads the same key.
        val r = new Recorder
        assert(FieldProtectionKey.ensure("e3", s.read, r.write, () => r.audit()) sameElements FieldProtectionKey.decodeHex(Hex))
        assert(r.writes.isEmpty)
    }

    test("a failed secret read never issues or rotates an encryption key") {
        val failing: String => Try[Option[Map[String, String]]] = _ => Failure(new RuntimeException("connection refused"))
        var writes = 0
        var audits = 0
        intercept[DatrisException](FieldProtectionKey.encryptionKey("e4", failing, (_, _) => writes += 1, (_, _) => audits += 1))
        intercept[DatrisException](FieldProtectionKey.rotateEncryptionKey("e4", failing, (_, _) => writes += 1, (_, _) => audits += 1))
        assert(writes == 0 && audits == 0)
    }

    test("encrypt first on a fresh environment does not break the hmac key later") {
        // Story problem guard: the secret may not exist yet when the first encrypt run needs it.
        val s = new Store(None)
        val (v, _) = current("e5", s)
        assert(v == 1)
        val r = new Recorder
        val hmacKey = FieldProtectionKey.ensure("e5", s.read, (n, d) => { r.write(n, d); s.write(n, d) }, () => r.audit())
        assert(hmacKey.length == 32, "ensure() still yields an hmac key after encrypt created the secret")
        assert(s.secret.get.contains("enc.v1") && s.secret.get.contains("encCurrent"), s"enc fields survive: ${s.secret}")
    }

    test("invalidate drops the cached encryption key so an edited encCurrent takes effect") {
        val v9 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        val s = new Store(Some(Map("key" -> Hex, "enc.v1" -> Hex, "encCurrent" -> "1")))
        assert(current("e6", s)._1 == 1)
        // An admin PUT through the secrets API (not FieldProtectionKey) moves to v9.
        s.secret = Some(Map("key" -> Hex, "enc.v1" -> Hex, "enc.v9" -> v9, "encCurrent" -> "9"))
        assert(current("e6", s)._1 == 1, "still cached before invalidate")
        FieldProtectionKey.invalidate("e6")
        val (v, k) = current("e6", s)
        assert(v == 9 && (k sameElements FieldProtectionKey.decodeHex(v9)))
        assert(s.writes == 0 && s.audits.isEmpty)
    }

    test("non-canonical enc field names never count as versions on rotate") {
        val s = new Store(Some(Map("key" -> Hex, "enc.v1" -> Hex, "encCurrent" -> "1", "enc.v+2147483647" -> "x", "enc.v05" -> Hex)))
        assert(rotate("e7", s) == 2)
        assert(s.secret.get.get("encCurrent").contains("2"))
    }
}
