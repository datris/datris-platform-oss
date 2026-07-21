package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import org.scalatest.funsuite.AnyFunSuite

class LogRedactUtilSpec extends AnyFunSuite {

    test("userinfo password is masked, username kept") {
        assert(LogRedactUtil.redactJdbcUrl("jdbc:postgresql://bob:s3cret@db:5432/datris")
            == "jdbc:postgresql://bob:***@db:5432/datris")
    }

    test("password query parameter is masked") {
        assert(LogRedactUtil.redactJdbcUrl("jdbc:postgresql://db/datris?password=s3cret&ssl=true")
            == "jdbc:postgresql://db/datris?password=***&ssl=true")
    }

    test("sslpassword and semicolon-separated params are masked") {
        assert(LogRedactUtil.redactJdbcUrl("jdbc:sqlserver://db;user=sa;password=abc;sslpassword=def;app=x")
            == "jdbc:sqlserver://db;user=sa;password=***;sslpassword=***;app=x")
    }

    test("matching is case-insensitive") {
        assert(LogRedactUtil.redactJdbcUrl("jdbc:postgresql://db/d?PASSWORD=abc")
            == "jdbc:postgresql://db/d?PASSWORD=***")
    }

    test("token and accessToken params are masked") {
        assert(LogRedactUtil.redactJdbcUrl("jdbc:databricks://host:443;token=dapi123;httpPath=/sql/1.0")
            == "jdbc:databricks://host:443;token=***;httpPath=/sql/1.0")
    }

    test("URLs without credentials pass through unchanged") {
        val clean = "jdbc:snowflake://acct.snowflakecomputing.com/"
        assert(LogRedactUtil.redactJdbcUrl(clean) == clean)
        val plain = "jdbc:postgresql://db:5432/datris"
        assert(LogRedactUtil.redactJdbcUrl(plain) == plain)
    }

    test("null stays null") {
        assert(LogRedactUtil.redactJdbcUrl(null) == null)
    }

    test("userinfo without a password is left alone") {
        val url = "jdbc:postgresql://bob@db:5432/datris"
        assert(LogRedactUtil.redactJdbcUrl(url) == url)
    }
}
