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

/** FieldProtectionKey.ensure fails closed: a read error never mints a key. */
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
}
