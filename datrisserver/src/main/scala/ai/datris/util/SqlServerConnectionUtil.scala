package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model._
import org.slf4j.{Logger, LoggerFactory}

import java.sql.{Connection, DriverManager, SQLException}
import java.util.Properties
import scala.util.Try

/** Opens JDBC connections to the Microsoft SQL Server / Azure SQL database a
 *  pipeline's `Database` destination points at, plus the identifier-emission
 *  rules that go with it. Shared by [[SqlServerLoader]] (writes) and pipeline
 *  delete-with-data so the per-`authMethod` driver settings and the
 *  actionable errors live in one place.
 *
 *  Credentials never go in the URL: user/password/client secret travel as
 *  connection properties, so the logged URL carries nothing to redact. */
object SqlServerConnectionUtil {
    private val logger: Logger = LoggerFactory.getLogger(getClass)

    /** Default schema when the pipeline leaves `schema` blank. */
    val DefaultSchema = "dbo"

    /** Resolve the pipeline's `credentialsSecret`, open a connection to
     *  `dbName`, run `f`, and close the connection. `onInfo` lets callers mirror
     *  progress into their own status log (the loader routes it to the job
     *  status). */
    def withConnection[T](db: Database, onInfo: String => Unit = _ => ())(f: Connection => T): T = {
        val creds = CredentialResolver.resolveSqlServer(db.credentialsSecret)

        Class.forName("com.microsoft.sqlserver.jdbc.SQLServerDriver")

        val url = jdbcUrl(creds, db.dbName)
        logger.info("SQL Server jdbc url: " + LogRedactUtil.redactJdbcUrl(url) + " (authMethod " + creds.authMethod + ")")
        onInfo("jdbc url: " + LogRedactUtil.redactJdbcUrl(url) + " (authMethod " + creds.authMethod + ")")

        var conn: Connection = null
        try {
            conn =
                try DriverManager.getConnection(url, connectionProperties(creds))
                catch {
                    case e: SQLException => throw connectError(e, creds, db.dbName)
                }
            onInfo("SQL Server connection acquired")
            f(conn)
        } finally {
            if (conn != null) Try(conn.close())
        }
    }

    /** `jdbc:sqlserver://host:port;databaseName=...;encrypt=...;trustServerCertificate=...`.
     *  Values holding `;`, `=` or braces are brace-escaped per the driver's
     *  connection-string rules. */
    def jdbcUrl(creds: ResolvedSqlServerCredentials, dbName: String): String = {
        val sb = new StringBuilder("jdbc:sqlserver://" + creds.host + ":" + creds.port)
        if (dbName != null && dbName.trim.nonEmpty) sb.append(";databaseName=" + urlValue(dbName.trim))
        sb.append(";encrypt=" + creds.encrypt)
        sb.append(";trustServerCertificate=" + creds.trustServerCertificate)
        sb.toString
    }

    /** Driver properties per `authMethod` (Step 2 of the destination story):
     *    sql                    user, password
     *    entraServicePrincipal  authentication=ActiveDirectoryServicePrincipal, user=clientId, password=clientSecret, tenantId
     *    entraPassword          authentication=ActiveDirectoryPassword, user, password
     *    entraManagedIdentity   authentication=ActiveDirectoryManagedIdentity (+ msiClientId for a user-assigned identity)
     *    kerberos               authenticationScheme=JavaKerberos, integratedSecurity=true, user=principal, password
     *                           (or, with no password, jaasConfigurationName for a keytab entry in a
     *                           mounted JAAS file)
     *  Every method batches inserts through the bulk-copy API. */
    def connectionProperties(creds: ResolvedSqlServerCredentials): Properties = {
        val p = new Properties()
        def set(k: String, v: Option[String]): Unit = v.foreach(p.setProperty(k, _))
        creds.authMethod match {
            case "sql" =>
                set("user", creds.user)
                set("password", creds.password)
            case "entraServicePrincipal" =>
                p.setProperty("authentication", "ActiveDirectoryServicePrincipal")
                set("user", creds.clientId)
                set("password", creds.clientSecret)
                // The 12.x driver takes the authority from the server's
                // federated-auth handshake; the tenant rides along so an
                // operator reading the properties sees which tenant the
                // principal belongs to.
                set("tenantId", creds.tenantId)
            case "entraPassword" =>
                p.setProperty("authentication", "ActiveDirectoryPassword")
                set("user", creds.user)
                set("password", creds.password)
            case "entraManagedIdentity" =>
                p.setProperty("authentication", "ActiveDirectoryManagedIdentity")
                set("msiClientId", creds.managedIdentityClientId)
            case "kerberos" =>
                p.setProperty("integratedSecurity", "true")
                p.setProperty("authenticationScheme", "JavaKerberos")
                set("user", creds.principal)
                // A keytab entry signs in on its own: never send a password with it.
                if (creds.jaasConfigurationName.isEmpty) set("password", creds.password)
                set("jaasConfigurationName", creds.jaasConfigurationName)
            case other =>
                throw new DatrisException("Unsupported SQL Server authMethod '" + other + "'")
        }
        p.setProperty("useBulkCopyForBatchInsert", "true")
        p.setProperty("applicationName", "Datris")
        p
    }

    /** Translate the driver's connect-time failures into text that names the
     *  auth method and the fix. Anything unrecognised keeps the driver's own
     *  message after the context. */
    private[util] def connectError(e: SQLException, creds: ResolvedSqlServerCredentials, dbName: String): DatrisException = {
        val msg = Option(e.getMessage).getOrElse(e.getClass.getSimpleName)
        val where = creds.host + ":" + creds.port
        val method = "authMethod '" + creds.authMethod + "'"
        val text =
            if (msg.contains("Cannot open database"))
                "SQL Server at " + where + " refused database '" + dbName + "' (" + method + "): it does not exist or the login has no user in it. " +
                    "Create the database, or map the login to a user there (CREATE USER ... FOR LOGIN ...) and grant it CREATE TABLE plus ALTER on the schema."
            else if (msg.contains("Login failed"))
                "SQL Server at " + where + " rejected the login (" + method + "). Check the credentials secret's " +
                    (creds.authMethod match {
                        case "sql" => "'user' and 'password' (a SQL login, and the server must allow SQL Server authentication)"
                        case "entraServicePrincipal" =>
                            "'clientId', 'clientSecret' and 'tenantId', and that the app has a contained user in the database (CREATE USER [app-name] FROM EXTERNAL PROVIDER)"
                        case "entraPassword" => "'user' and 'password' (a Microsoft Entra user without MFA) and that it has a contained user in the database"
                        case "entraManagedIdentity" =>
                            "identity: the container must run on Azure with a managed identity that has a contained user in the database"
                        case _ => "'principal' and 'password'"
                    }) + "."
            else if (msg.toLowerCase.contains("kerberos") || msg.toLowerCase.contains("krb5") || msg.contains("GSS"))
                "SQL Server Kerberos login failed for '" + creds.principal.getOrElse("") + "' at " + where + " (" + method + "). " +
                    "Put krb5.conf in docker/config/ and add -Djava.security.krb5.conf=/config/krb5.conf to JAVA_OPTS on the datris service, " +
                    "check the principal and password (or the keytab named by jaasConfigurationName), and that the server has an SPN for MSSQLSvc/" + creds.host + "."
            else if (
                msg.contains("TCP/IP connection") || msg.contains("connect timed out") || msg.contains("UnknownHost") || msg.contains("Connection refused")
            )
                "Cannot reach SQL Server at " + where + " (" + method + "). Check the credentials secret's 'host' and 'port', that TCP/IP is enabled on the server, " +
                    "and that a firewall (or the Azure SQL server firewall) lets this host in."
            else if (msg.toLowerCase.contains("certificate") || msg.contains("PKIX"))
                "SQL Server at " + where + " presented a certificate this host does not trust (" + method + "). Install a trusted certificate on the server, " +
                    "or set 'trustServerCertificate' to true in the credentials secret for a self-signed test server."
            else if (msg.contains("ActiveDirectory") || msg.contains("MSAL") || msg.contains("msal") || msg.contains("azure"))
                "SQL Server Microsoft Entra sign-in failed at " + where + " (" + method + "). Check the credentials secret's fields for that method."
            else "SQL Server connection to " + where + " failed (" + method + ")."
        val out = new DatrisException(text + " Driver error: " + msg)
        out.initCause(e)
        out
    }

    /** Emit a SQL Server identifier. Simple names (letters/digits/underscore,
     *  not digit-leading) that are not T-SQL reserved keywords go unquoted;
     *  anything else (including `order`, `key`, `user`) is bracket-quoted
     *  with a closing bracket doubled. Bracketing does not change how SQL
     *  Server resolves the name, so quoting a keyword is always safe. */
    def ident(identifier: String): String =
        if (isSimpleIdent(identifier) && !isReservedKeyword(identifier)) identifier
        else "[" + identifier.replace("]", "]]") + "]"
    def isSimpleIdent(s: String): Boolean = s != null && s.matches("[A-Za-z_][A-Za-z0-9_]*")
    def isReservedKeyword(s: String): Boolean = s != null && ReservedKeywords.contains(s.toUpperCase(java.util.Locale.ROOT))

    /** Microsoft's documented Transact-SQL reserved keywords ("Reserved
     *  Keywords (Transact-SQL)": the SQL Server / Azure Synapse table, the
     *  Synapse-only LABEL, and WITHIN from WITHIN GROUP). Matched
     *  case-insensitively. ODBC and future keywords are not reserved by the
     *  parser and stay unquoted. */
    val ReservedKeywords: Set[String] = Set(
        "ADD",
        "ALL",
        "ALTER",
        "AND",
        "ANY",
        "AS",
        "ASC",
        "AUTHORIZATION",
        "BACKUP",
        "BEGIN",
        "BETWEEN",
        "BREAK",
        "BROWSE",
        "BULK",
        "BY",
        "CASCADE",
        "CASE",
        "CHECK",
        "CHECKPOINT",
        "CLOSE",
        "CLUSTERED",
        "COALESCE",
        "COLLATE",
        "COLUMN",
        "COMMIT",
        "COMPUTE",
        "CONSTRAINT",
        "CONTAINS",
        "CONTAINSTABLE",
        "CONTINUE",
        "CONVERT",
        "CREATE",
        "CROSS",
        "CURRENT",
        "CURRENT_DATE",
        "CURRENT_TIME",
        "CURRENT_TIMESTAMP",
        "CURRENT_USER",
        "CURSOR",
        "DATABASE",
        "DBCC",
        "DEALLOCATE",
        "DECLARE",
        "DEFAULT",
        "DELETE",
        "DENY",
        "DESC",
        "DISK",
        "DISTINCT",
        "DISTRIBUTED",
        "DOUBLE",
        "DROP",
        "DUMP",
        "ELSE",
        "END",
        "ERRLVL",
        "ESCAPE",
        "EXCEPT",
        "EXEC",
        "EXECUTE",
        "EXISTS",
        "EXIT",
        "EXTERNAL",
        "FETCH",
        "FILE",
        "FILLFACTOR",
        "FOR",
        "FOREIGN",
        "FREETEXT",
        "FREETEXTTABLE",
        "FROM",
        "FULL",
        "FUNCTION",
        "GOTO",
        "GRANT",
        "GROUP",
        "HAVING",
        "HOLDLOCK",
        "IDENTITY",
        "IDENTITYCOL",
        "IDENTITY_INSERT",
        "IF",
        "IN",
        "INDEX",
        "INNER",
        "INSERT",
        "INTERSECT",
        "INTO",
        "IS",
        "JOIN",
        "KEY",
        "KILL",
        "LABEL",
        "LEFT",
        "LIKE",
        "LINENO",
        "LOAD",
        "MERGE",
        "NATIONAL",
        "NOCHECK",
        "NONCLUSTERED",
        "NOT",
        "NULL",
        "NULLIF",
        "OF",
        "OFF",
        "OFFSETS",
        "ON",
        "OPEN",
        "OPENDATASOURCE",
        "OPENQUERY",
        "OPENROWSET",
        "OPENXML",
        "OPTION",
        "OR",
        "ORDER",
        "OUTER",
        "OVER",
        "PERCENT",
        "PIVOT",
        "PLAN",
        "PRECISION",
        "PRIMARY",
        "PRINT",
        "PROC",
        "PROCEDURE",
        "PUBLIC",
        "RAISERROR",
        "READ",
        "READTEXT",
        "RECONFIGURE",
        "REFERENCES",
        "REPLICATION",
        "RESTORE",
        "RESTRICT",
        "RETURN",
        "REVERT",
        "REVOKE",
        "RIGHT",
        "ROLLBACK",
        "ROWCOUNT",
        "ROWGUIDCOL",
        "RULE",
        "SAVE",
        "SCHEMA",
        "SECURITYAUDIT",
        "SELECT",
        "SEMANTICKEYPHRASETABLE",
        "SEMANTICSIMILARITYDETAILSTABLE",
        "SEMANTICSIMILARITYTABLE",
        "SESSION_USER",
        "SET",
        "SETUSER",
        "SHUTDOWN",
        "SOME",
        "STATISTICS",
        "SYSTEM_USER",
        "TABLE",
        "TABLESAMPLE",
        "TEXTSIZE",
        "THEN",
        "TO",
        "TOP",
        "TRAN",
        "TRANSACTION",
        "TRIGGER",
        "TRUNCATE",
        "TRY_CONVERT",
        "TSEQUAL",
        "UNION",
        "UNIQUE",
        "UNPIVOT",
        "UPDATE",
        "UPDATETEXT",
        "USE",
        "USER",
        "VALUES",
        "VARYING",
        "VIEW",
        "WAITFOR",
        "WHEN",
        "WHERE",
        "WHILE",
        "WITH",
        "WITHIN",
        "WRITETEXT"
    )

    /** The schema a pipeline loads into: its `schema`, else `dbo`. */
    def schemaName(db: Database): String =
        Option(db.schema).map(_.trim).filter(_.nonEmpty).getOrElse(DefaultSchema)

    /** `schema.table`; `dbName` is the URL's `databaseName`, never part of the reference. */
    def qualifiedTable(db: Database): String = ident(schemaName(db)) + "." + ident(db.table)

    /** N'...' literal for names compared against the catalog views. */
    def nLiteral(value: String): String = "N'" + value.replace("'", "''") + "'"

    private def urlValue(v: String): String =
        if (v.exists(c => c == ';' || c == '=' || c == '{' || c == '}')) "{" + v.replace("}", "}}") + "}" else v
}
