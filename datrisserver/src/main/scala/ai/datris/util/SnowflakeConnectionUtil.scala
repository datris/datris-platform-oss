package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
*/

import ai.datris.model._
import org.slf4j.{Logger, LoggerFactory}

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.sql.{Connection, DriverManager}
import java.util.Properties
import scala.util.Try

/** Opens JDBC connections to the Snowflake account a pipeline's `Database`
 *  destination points at, plus the identifier-emission rules that go with it.
 *  Shared by [[SnowflakeLoader]] (writes) and [[SnowflakeQueryUtil]] (reads)
 *  so the paste-shape normalizations and actionable errors live in one place. */
object SnowflakeConnectionUtil {
    private val logger: Logger = LoggerFactory.getLogger(getClass)

    /** Resolve the pipeline's `credentialsSecret`, open a connection with the
     *  config's db/schema/warehouse/role, run `f`, and clean up the connection
     *  and the temporary key file. `onInfo` lets callers mirror progress into
     *  their own status log (the loader routes it to the job status). */
    def withConnection[T](db: Database, onInfo: String => Unit = _ => ())(f: Connection => T): T = {
        val creds = CredentialResolver.resolveSnowflake(db.credentialsSecret)

        Class.forName("net.snowflake.client.jdbc.SnowflakeDriver")

        var conn: Connection = null
        var keyFile: Path = null
        try {
            val properties = new Properties()
            properties.setProperty("user", creds.user)
            if (db.dbName != null) properties.setProperty("db", db.dbName)
            if (db.schema != null) properties.setProperty("schema", db.schema)
            if (db.warehouse != null) properties.setProperty("warehouse", db.warehouse)
            if (db.role != null) properties.setProperty("role", db.role)

            // Key-pair auth is the default (write the PEM to a temp file and let the
            // driver parse/decrypt it — handles encrypted keys without BouncyCastle).
            // Password is the fallback when no private key is present.
            creds.privateKey match {
                case Some(pem) =>
                    val normalizedPem = normalizePrivateKeyPem(pem, creds.privateKeyPassphrase.isDefined)
                    keyFile = Files.createTempFile("snowflake-key-", ".p8")
                    keyFile.toFile.deleteOnExit()
                    Files.setPosixFilePermissions(keyFile, java.util.EnumSet.of(
                        java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                        java.nio.file.attribute.PosixFilePermission.OWNER_WRITE))
                    Files.write(keyFile, normalizedPem.getBytes(StandardCharsets.UTF_8))
                    properties.setProperty("private_key_file", keyFile.toAbsolutePath.toString)
                    creds.privateKeyPassphrase.foreach(p => properties.setProperty("private_key_file_pwd", p))
                case None =>
                    properties.setProperty("password", creds.password.get)
            }

            val jdbcUrl = "jdbc:snowflake://" + normalizeAccount(creds.account) + ".snowflakecomputing.com/"
            logger.info("Snowflake jdbc url: " + LogRedactUtil.redactJdbcUrl(jdbcUrl))
            onInfo("jdbc url: " + LogRedactUtil.redactJdbcUrl(jdbcUrl))
            conn = try {
                DriverManager.getConnection(jdbcUrl, properties)
            } catch {
                // The driver reports a nonexistent account hostname as an opaque
                // "communication error ... HTTP status=404".
                case e: java.sql.SQLException if e.getMessage != null && e.getMessage.contains("404") =>
                    throw new DatrisException("No Snowflake account answers at " + jdbcUrl +
                        " (HTTP 404). The credentials secret's 'account' field must be the full account identifier — " +
                        "usually 'orgname-accountname'. An account name alone (missing the org prefix) causes exactly this. " +
                        "Find it in Snowsight via SELECT CURRENT_ORGANIZATION_NAME() || '-' || CURRENT_ACCOUNT_NAME(). " +
                        "Underlying driver error: " + e.getMessage)
            }
            onInfo("Snowflake connection acquired")
            f(conn)
        } finally {
            if (conn != null) Try(conn.close())
            if (keyFile != null) Try(Files.deleteIfExists(keyFile))
        }
    }

    /** The secret's `account` field should be a bare account identifier
     *  (`orgname-accountname` or `locator.region`), but Snowsight's copy
     *  buttons hand out the full account URL — and a doubled
     *  `<host>.snowflakecomputing.com.snowflakecomputing.com` still resolves
     *  via wildcard DNS, failing later with an opaque HTTP 404 at session
     *  open. Accept any of the shapes: strip the protocol, the
     *  snowflakecomputing.com suffix, and trailing slashes; swap underscores
     *  for hyphens (required in hostnames per Snowflake's URL rules). */
    def normalizeAccount(raw: String): String =
        raw.trim
            .replaceFirst("(?i)^[a-z]+://", "")
            .replaceFirst("(?i)\\.snowflakecomputing\\.(com|cn).*$", "")
            .stripSuffix("/")
            .replace('_', '-')

    /** Secret values are entered through single-line form fields, which strip
     *  the newlines out of a pasted PEM — and the driver's PemReader requires
     *  strict PEM framing, so the stored key fails with "readPemObject()
     *  returned null". Rebuild it: honor literal \n sequences, pull the base64
     *  body out from between the BEGIN/END markers (or treat the whole value
     *  as the body when the markers are missing), and re-wrap at 64 columns. */
    def normalizePrivateKeyPem(raw: String, hasPassphrase: Boolean): String = {
        val withNewlines = raw.trim.replace("\\n", "\n")
        val headerRe = "-----BEGIN ([A-Z0-9 ]+?)-----".r
        val label = headerRe.findFirstMatchIn(withNewlines).map(_.group(1)).getOrElse("PRIVATE KEY")

        if (label == "RSA PRIVATE KEY")
            throw new DatrisException("Snowflake 'privateKey' is a PKCS#1 key (BEGIN RSA PRIVATE KEY), which the Snowflake driver does not accept. Convert it to PKCS#8 first: openssl pkcs8 -topk8 -nocrypt -in <key-file>")
        if (label == "ENCRYPTED PRIVATE KEY" && !hasPassphrase)
            throw new DatrisException("Snowflake 'privateKey' is encrypted (BEGIN ENCRYPTED PRIVATE KEY) but the secret has no 'privateKeyPassphrase' field. Add the passphrase to the secret, or store an unencrypted PKCS#8 key")

        val body = "-----(BEGIN|END) [A-Z0-9 ]+?-----".r
            .replaceAllIn(withNewlines, "")
            .replaceAll("\\s", "")
        if (body.isEmpty)
            throw new DatrisException("Snowflake 'privateKey' has no key material after the PEM header — re-paste the full contents of the key file (the form field may have truncated it)")

        val wrapped = body.grouped(64).mkString("\n")
        s"-----BEGIN $label-----\n$wrapped\n-----END $label-----\n"
    }

    /** Emit a Snowflake identifier. Simple names (letters/digits/underscore/$,
     *  not digit-leading) go UNQUOTED so Snowflake folds them to uppercase —
     *  the convention every Snowflake tool follows, and what lets a config
     *  that says `datris` find the DATRIS database. Anything else (hyphens,
     *  spaces, punctuation) is double-quoted and case-sensitive. */
    def ident(identifier: String): String =
        if (isSimpleIdent(identifier)) identifier else quote(identifier)
    def isSimpleIdent(s: String): Boolean = s.matches("[A-Za-z_][A-Za-z0-9_$]*")
    /** The name information_schema stores for an identifier as `ident` emits it:
     *  unquoted names fold to uppercase, quoted names keep their exact case. */
    def effectiveName(s: String): String =
        if (isSimpleIdent(s)) s.toUpperCase else s
    def quote(identifier: String): String = "\"" + identifier.replace("\"", "\"\"") + "\""

    def qualifiedTable(db: Database): String =
        ident(db.dbName) + "." + ident(db.schema) + "." + ident(db.table)
}
