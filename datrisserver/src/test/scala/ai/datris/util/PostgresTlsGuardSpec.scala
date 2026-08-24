package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.DatrisException
import org.scalatest.BeforeAndAfterEach
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class PostgresTlsGuardSpec extends AnyFlatSpec with Matchers with BeforeAndAfterEach {

    override def afterEach(): Unit = {
        sys.props -= "datris.env"
        sys.props -= "datris.allowPlaintextDb"
    }

    private def production(): Unit = sys.props("datris.env") = "production"

    behavior of "jdbcHost"

    it should "extract the host from a standard JDBC URL" in {
        PostgresTlsGuard.jdbcHost("jdbc:postgresql://db.example.com:5432/datris") shouldBe Some("db.example.com")
    }

    it should "extract the host when params are present" in {
        PostgresTlsGuard.jdbcHost("jdbc:postgresql://postgres:5432/datris?ApplicationName=x") shouldBe Some("postgres")
    }

    it should "return None for garbage" in {
        PostgresTlsGuard.jdbcHost("not a url") shouldBe None
        PostgresTlsGuard.jdbcHost(null) shouldBe None
    }

    behavior of "hasSecureSslMode"

    it should "accept require, verify-ca, verify-full" in {
        PostgresTlsGuard.hasSecureSslMode("jdbc:postgresql://h:5432/d?sslmode=require") shouldBe true
        PostgresTlsGuard.hasSecureSslMode("jdbc:postgresql://h:5432/d?a=b&sslmode=verify-ca") shouldBe true
        PostgresTlsGuard.hasSecureSslMode("jdbc:postgresql://h:5432/d?sslmode=verify-full&a=b") shouldBe true
    }

    it should "reject absent, weaker, or malformed sslmode" in {
        PostgresTlsGuard.hasSecureSslMode("jdbc:postgresql://h:5432/d") shouldBe false
        PostgresTlsGuard.hasSecureSslMode("jdbc:postgresql://h:5432/d?sslmode=prefer") shouldBe false
        PostgresTlsGuard.hasSecureSslMode("jdbc:postgresql://h:5432/d?sslmode=disable") shouldBe false
        PostgresTlsGuard.hasSecureSslMode("jdbc:postgresql://h:5432/d?sslmode") shouldBe false
    }

    behavior of "validate without DATRIS_ENV (every existing install)"

    it should "be a no-op for plaintext external URLs" in {
        noException should be thrownBy
            PostgresTlsGuard.validate("jdbc:postgresql://db.example.com:5432/datris", "platform Postgres")
    }

    it should "be a no-op for the bundled and local hosts" in {
        noException should be thrownBy
            PostgresTlsGuard.validate("jdbc:postgresql://postgres:5432/datris", "platform Postgres")
        noException should be thrownBy
            PostgresTlsGuard.validate("jdbc:postgresql://localhost:5432/datris", "platform Postgres")
    }

    behavior of "validate with DATRIS_ENV=production"

    it should "fail fast on a plaintext external URL" in {
        production()
        val e = intercept[DatrisException] {
            PostgresTlsGuard.validate("jdbc:postgresql://db.prod-example.com:5432/datris", "platform Postgres")
        }
        e.getMessage should include("sslmode=require")
        e.getMessage should include("DATRIS_ALLOW_PLAINTEXT_DB")
    }

    it should "start with sslmode=require on an external URL" in {
        production()
        noException should be thrownBy
            PostgresTlsGuard.validate("jdbc:postgresql://db.prod-example.com:5432/datris?sslmode=require", "platform Postgres")
    }

    it should "not demand TLS from the bundled in-network postgres" in {
        production()
        noException should be thrownBy
            PostgresTlsGuard.validate("jdbc:postgresql://postgres:5432/datris", "platform Postgres")
        noException should be thrownBy
            PostgresTlsGuard.validate("jdbc:postgresql://pgvector:5432/vectors", "pgvector")
    }

    it should "start plaintext-external when DATRIS_ALLOW_PLAINTEXT_DB=true" in {
        production()
        sys.props("datris.allowPlaintextDb") = "true"
        noException should be thrownBy
            PostgresTlsGuard.validate("jdbc:postgresql://db.optout-example.com:5432/datris", "platform Postgres")
    }

    it should "treat DATRIS_ENV values other than production as unset" in {
        sys.props("datris.env") = "oss"
        noException should be thrownBy
            PostgresTlsGuard.validate("jdbc:postgresql://db.example.com:5432/datris", "platform Postgres")
    }

    it should "stay out of the way for unparseable URLs even in production" in {
        production()
        noException should be thrownBy PostgresTlsGuard.validate("not a url", "platform Postgres")
    }
}
