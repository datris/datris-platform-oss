package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.DatrisException
import org.scalatest.funsuite.AnyFunSuite

import scala.collection.JavaConverters._

/** Story: Microsoft SQL Server destination (plans/stories/mssql-destination.md),
  * Acceptance bullet 2 and Step 2/3.
  *
  * API the story names:
  *   - `CredentialResolver.hasSqlServerCredentials(fields: java.util.Map[String, String]): Boolean`
  *   - `CredentialResolver.resolveSqlServer(secretName: String): ResolvedSqlServerCredentials`
  *
  * `resolveSqlServer` reads Vault, which a unit spec cannot reach, so the spec
  * pins two pure seams the implementation exposes (resolveSqlServer = Vault
  * read + the first one; withConnection = the second two + DriverManager):
  *
  * {{{
  * object CredentialResolver {
  *     def resolveSqlServerFromSecret(secretName: String, secret: java.util.Map[String, String]): ResolvedSqlServerCredentials
  * }
  * object SqlServerConnectionUtil {
  *     def jdbcUrl(creds: ResolvedSqlServerCredentials, dbName: String): String
  *     def connectionProperties(creds: ResolvedSqlServerCredentials): java.util.Properties
  * }
  * }}}
  *
  * Driver settings are read from the properties first, then from the
  * `;key=value` segments of the URL, so either placement satisfies the spec.
  *
  * Secret fields per authMethod (absent authMethod = `sql`):
  *   sql                   host, user, password
  *   entraServicePrincipal host, clientId, clientSecret, tenantId
  *   entraPassword         host, user, password
  *   entraManagedIdentity  host (+ optional managedIdentityClientId)
  *   kerberos              host, principal, password
  */
class SqlServerCredentialsSpec extends AnyFunSuite {

    private def m(kv: (String, String)*): java.util.Map[String, String] =
        new java.util.HashMap[String, String](kv.toMap.asJava)

    private val required: Map[String, Seq[(String, String)]] = Map(
        "sql" -> Seq("host" -> "db.example.com", "user" -> "loader", "password" -> "pw1"),
        "entraServicePrincipal" -> Seq(
            "host" -> "srv.database.windows.net",
            "clientId" -> "11111111-aaaa-bbbb-cccc-000000000001",
            "clientSecret" -> "s3cr3t",
            "tenantId" -> "22222222-aaaa-bbbb-cccc-000000000002"
        ),
        "entraPassword" -> Seq("host" -> "srv.database.windows.net", "user" -> "loader@contoso.com", "password" -> "pw2"),
        "entraManagedIdentity" -> Seq("host" -> "srv.database.windows.net"),
        "kerberos" -> Seq("host" -> "sql01.corp.example.com", "principal" -> "svc_datris@CORP.EXAMPLE.COM", "password" -> "pw3")
    )

    private def secretFor(method: String, extra: (String, String)*): java.util.Map[String, String] =
        m((("authMethod" -> method) +: required(method)) ++ extra: _*)

    // ---- hasSqlServerCredentials -------------------------------------------

    test("hasSqlServerCredentials is true for each of the five authMethod shapes with their required fields") {
        required.keys.foreach { method =>
            assert(CredentialResolver.hasSqlServerCredentials(secretFor(method)), s"$method with ${required(method).map(_._1)} must be complete")
        }
    }

    test("hasSqlServerCredentials defaults a missing authMethod to sql") {
        assert(CredentialResolver.hasSqlServerCredentials(m(required("sql"): _*)))
    }

    test("hasSqlServerCredentials is false when any required field is missing") {
        required.foreach { case (method, fields) =>
            fields.map(_._1).foreach { drop =>
                val partial = m((("authMethod" -> method) +: fields.filterNot(_._1 == drop)): _*)
                assert(!CredentialResolver.hasSqlServerCredentials(partial), s"$method without '$drop' must be incomplete")
            }
        }
        // sql (the default) without authMethod and without password
        assert(!CredentialResolver.hasSqlServerCredentials(m("host" -> "h", "user" -> "u")))
    }

    test("hasSqlServerCredentials is false when authMethod is unknown") {
        assert(!CredentialResolver.hasSqlServerCredentials(m("authMethod" -> "ntlm", "host" -> "h", "user" -> "u", "password" -> "p")))
        assert(!CredentialResolver.hasSqlServerCredentials(m("authMethod" -> "integratedSecurity", "host" -> "h", "user" -> "u", "password" -> "p")))
    }

    test("hasSqlServerCredentials does not match a Databricks or Snowflake secret") {
        assert(!CredentialResolver.hasSqlServerCredentials(m("host" -> "dbc-1.cloud.databricks.com", "clientId" -> "c", "clientSecret" -> "s")))
        assert(!CredentialResolver.hasSqlServerCredentials(m("account" -> "xy12345", "user" -> "u", "privateKey" -> "k")))
    }

    // ---- resolveSqlServer --------------------------------------------------

    test("resolveSqlServer without a secret name points at Configuration → Secrets → Platform") {
        Seq(null, "", "  ").foreach { name =>
            val e = intercept[DatrisException](CredentialResolver.resolveSqlServer(name))
            assert(e.getMessage.contains("Configuration → Secrets → Platform"), e.getMessage)
        }
    }

    test("resolveSqlServer on a kerberos secret without principal names the field and the method") {
        val e = intercept[DatrisException] {
            CredentialResolver.resolveSqlServerFromSecret("mssql-krb", m("authMethod" -> "kerberos", "host" -> "sql01", "password" -> "pw"))
        }
        assert(e.getMessage.contains("'principal'"), e.getMessage)
        assert(e.getMessage.contains("kerberos"), e.getMessage)
        assert(e.getMessage.contains("mssql-krb"), e.getMessage)
        assert(e.getMessage.contains("Configuration → Secrets → Platform"), e.getMessage)
    }

    test("resolveSqlServer rejects an unknown authMethod and names it") {
        val e = intercept[DatrisException] {
            CredentialResolver.resolveSqlServerFromSecret("mssql", m("authMethod" -> "ntlm", "host" -> "h", "user" -> "u", "password" -> "p"))
        }
        assert(e.getMessage.contains("ntlm"), e.getMessage)
    }

    test("resolveSqlServer defaults authMethod to sql and port to 1433") {
        val c = CredentialResolver.resolveSqlServerFromSecret("mssql", m(required("sql"): _*))
        assert(c.authMethod == "sql")
        assert(c.host == "db.example.com")
        assert(c.port.toString == "1433")
    }

    // ---- URL / property assembly (Step 2 + Step 3) -------------------------

    private def assemble(secret: java.util.Map[String, String], dbName: String = "datris"): (String, java.util.Properties) = {
        val c = CredentialResolver.resolveSqlServerFromSecret("mssql", secret)
        (SqlServerConnectionUtil.jdbcUrl(c, dbName), SqlServerConnectionUtil.connectionProperties(c))
    }

    /** A driver setting, case-insensitive key, from the properties or the URL. */
    private def setting(url: String, props: java.util.Properties, key: String): Option[String] = {
        val fromProps = props.stringPropertyNames().asScala.find(_.equalsIgnoreCase(key)).map(props.getProperty)
        fromProps.orElse(
            url.split(";").drop(1).map(_.split("=", 2)).collect { case Array(k, v) if k.trim.equalsIgnoreCase(key) => v }.headOption
        )
    }

    test("sql: URL carries host:port, databaseName, encrypt=true, trustServerCertificate=false; user/password set; no Entra auth") {
        val (url, props) = assemble(secretFor("sql"))
        assert(url.startsWith("jdbc:sqlserver://db.example.com:1433;"), url)
        assert(setting(url, props, "databaseName").contains("datris"), url)
        assert(setting(url, props, "encrypt").contains("true"), url)
        assert(setting(url, props, "trustServerCertificate").contains("false"), url)
        assert(setting(url, props, "user").contains("loader"))
        assert(setting(url, props, "password").contains("pw1"))
        assert(!setting(url, props, "authentication").exists(_.startsWith("ActiveDirectory")))
        assert(!setting(url, props, "authenticationScheme").contains("JavaKerberos"))
    }

    test("sql: port, encrypt and trustServerCertificate come from the secret when present") {
        val (url, props) = assemble(secretFor("sql", "port" -> "14330", "encrypt" -> "false", "trustServerCertificate" -> "true"))
        assert(url.startsWith("jdbc:sqlserver://db.example.com:14330;"), url)
        assert(setting(url, props, "encrypt").contains("false"), url)
        assert(setting(url, props, "trustServerCertificate").contains("true"), url)
    }

    test("entraServicePrincipal: authentication=ActiveDirectoryServicePrincipal, user=clientId, password=clientSecret, tenant passed") {
        val (url, props) = assemble(secretFor("entraServicePrincipal"))
        assert(url.startsWith("jdbc:sqlserver://srv.database.windows.net:1433;"), url)
        assert(setting(url, props, "authentication").contains("ActiveDirectoryServicePrincipal"))
        assert(setting(url, props, "user").contains("11111111-aaaa-bbbb-cccc-000000000001"))
        assert(setting(url, props, "password").contains("s3cr3t"))
        val tenant = "22222222-aaaa-bbbb-cccc-000000000002"
        assert(
            setting(url, props, "tenantId").contains(tenant) || setting(url, props, "authority").exists(_.contains(tenant)),
            s"tenantId must reach the driver via tenantId or authority: url=$url props=$props"
        )
    }

    test("entraPassword: authentication=ActiveDirectoryPassword with user and password") {
        val (url, props) = assemble(secretFor("entraPassword"))
        assert(setting(url, props, "authentication").contains("ActiveDirectoryPassword"))
        assert(setting(url, props, "user").contains("loader@contoso.com"))
        assert(setting(url, props, "password").contains("pw2"))
    }

    test("entraManagedIdentity: authentication=ActiveDirectoryManagedIdentity, msiClientId only for a user-assigned identity") {
        val (url, props) = assemble(secretFor("entraManagedIdentity"))
        assert(setting(url, props, "authentication").contains("ActiveDirectoryManagedIdentity"))
        assert(setting(url, props, "msiClientId").isEmpty, "system-assigned identity must not send msiClientId")
        assert(setting(url, props, "password").isEmpty)

        val (url2, props2) = assemble(secretFor("entraManagedIdentity", "managedIdentityClientId" -> "33333333-aaaa-bbbb-cccc-000000000003"))
        assert(setting(url2, props2, "authentication").contains("ActiveDirectoryManagedIdentity"))
        assert(setting(url2, props2, "msiClientId").contains("33333333-aaaa-bbbb-cccc-000000000003"))
    }

    test("kerberos: authenticationScheme=JavaKerberos, user=principal, password; no Entra auth") {
        val (url, props) = assemble(secretFor("kerberos"))
        assert(url.startsWith("jdbc:sqlserver://sql01.corp.example.com:1433;"), url)
        assert(setting(url, props, "authenticationScheme").contains("JavaKerberos"))
        assert(setting(url, props, "user").contains("svc_datris@CORP.EXAMPLE.COM"))
        assert(setting(url, props, "password").contains("pw3"))
        assert(!setting(url, props, "authentication").exists(_.startsWith("ActiveDirectory")))
        assert(setting(url, props, "jaasConfigurationName").isEmpty)

        val (url2, props2) = assemble(secretFor("kerberos", "jaasConfigurationName" -> "DatrisKeytab"))
        assert(setting(url2, props2, "jaasConfigurationName").contains("DatrisKeytab"))
    }
}
