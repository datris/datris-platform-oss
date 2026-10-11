name := "datris-server"
ThisBuild / organization := "ai.datris"
ThisBuild / scalaVersion := "2.12.21"
ThisBuild / version := "1.47.0"

// Match the Docker runtime (eclipse-temurin:17-jre). Without this, javac uses the
// build host's JDK (e.g. 25), producing class files the runtime can't load.
ThisBuild / javacOptions ++= Seq("--release", "17")

lazy val global = project
    .in(file("."))
    .disablePlugins(AssemblyPlugin)
    .aggregate(
        datrisserver
    )

lazy val datrisserver = project
    .enablePlugins(BuildInfoPlugin)
    .settings(
        name := "datrisserver",
        assemblySettings,
        libraryDependencies ++= allDependencies,
        libraryDependencySchemes += "com.github.luben" % "zstd-jni" % VersionScheme.Always,
        // Pin the entire Jackson family to ONE version. jackson-module-scala
        // strictly checks that jackson-databind matches its own minor version
        // ([2.18.0, 2.19.0) for 2.18.x) — a mismatched pair blows up with
        // ExceptionInInitializerError the first time RDDOperationScope loads
        // (which is any time a destination uses SparkSession — objectStore
        // writes, in particular). Spark 3.5.x ships 2.15.2; overriding all four
        // artifacts together to 2.18.11 keeps the pair consistent and clears the
        // jackson-core/databind CVEs (2.18.11 closes the four core/databind advisories
        // published 2026-10-02 on top of the 2.18.10 and 2.18.9 @JsonView/@JsonIgnoreProperties
        // fixes). Bump all four together or none. Jackson 3 (tools.jackson) is
        // deliberately kept off the classpath (spring-boot-starter-jackson is
        // excluded below): Spark, jackson-module-scala and logstash-logback-encoder
        // all need Jackson 2, and Boot 4's Jackson 2 HTTP converters must stay the
        // only ones that deserialise @RequestBody payloads.
        dependencyOverrides ++= Seq(
            "com.fasterxml.jackson.core" % "jackson-core" % "2.18.11",
            "com.fasterxml.jackson.core" % "jackson-annotations" % "2.18.11",
            "com.fasterxml.jackson.core" % "jackson-databind" % "2.18.11",
            "com.fasterxml.jackson.module" %% "jackson-module-scala" % "2.18.11",
            // Lock the entire Hadoop family to 3.3.4 — what Spark 3.5.x ships.
            // S3A and the rest of the Hadoop FileSystem layer share private
            // interfaces (IOStatistics, DurationTracker, CallableRaisingIOE);
            // a version skew between hadoop-aws and hadoop-common surfaces as
            // NoSuchMethodError on the first S3A read. The bump-together rule
            // applies to every transitive consumer too.
            "org.apache.hadoop" % "hadoop-common" % "3.3.4",
            "org.apache.hadoop" % "hadoop-client-api" % "3.3.4",
            "org.apache.hadoop" % "hadoop-client-runtime" % "3.3.4",
            // Embedded Tomcat (Spring Boot's servlet container — the process
            // serving the API). Pinned ahead of what Boot 4.0.8 manages
            // (11.0.24) so the override acts as an explicit floor: a Boot downgrade can't
            // silently reintroduce the critical CVEs fixed here (partial-PUT
            // RCE, HTTP/2 header validation, digest-auth bypass and replay,
            // security-constraint / FORM-auth authorization bypasses —
            // GHSA-9xv2-5v5q-p794, GHSA-gcx9-497g-6cp6, GHSA-h3x4-894j-xpx5).
            // All three tomcat-embed-* artifacts MUST move together — a version
            // mismatch between them makes the embedded server fail to start.
            "org.apache.tomcat.embed" % "tomcat-embed-core" % "11.0.26",
            "org.apache.tomcat.embed" % "tomcat-embed-el" % "11.0.26",
            "org.apache.tomcat.embed" % "tomcat-embed-websocket" % "11.0.26",
            // CVE patch bumps over what Spark 3.5.x pulls transitively. Avro
            // 1.11.4 is a patch release over Spark's 1.11.2 (CVE-2024-47561,
            // code execution reading untrusted Avro). ZooKeeper 3.8.6 replaces
            // the 3.6.3 client jar Spark/Curator drag in (CVE-2023-44981 +
            // CVE-2024-23944 persistent-watcher info disclosure — no fix on
            // the 3.7 line — plus the 3.8.0–3.8.5 ZKTrustManager reverse-DNS
            // hostname-verification bypass and config-handling advisories);
            // nothing in the stack runs a ZooKeeper server, and the 3.8 client
            // wire protocol is compatible with the 3.5+ servers Spark supports.
            "org.apache.avro" % "avro" % "1.11.4",
            "org.apache.zookeeper" % "zookeeper" % "3.8.6",
            // minio still ships a stale bcprov. 1.86 patches the name-
            // constraints bypass (trailing dot in rfc822Name/URI) and the
            // lazy ASN.1 nesting-depth guard reset, on top of the earlier
            // LDAP-injection and GOST fixes — none of these code paths are
            // used here, but the override clears the alerts. Keep this
            // override when bumping minio.
            "org.bouncycastle" % "bcprov-jdk18on" % "1.86",
            // Netty: Spark/azure-core-http-netty/qdrant drag in assorted 4.1.x
            // jars with HTTP/2 + SPDY decoder DoS CVEs; 4.1.137.Final also
            // patches the CORS Vary-header cache-poisoning advisory. All
            // io.netty artifacts MUST stay on one version — mixed netty jars
            // fail at runtime with NoSuchMethodError. The full family is
            // listed; overrides are inert for absent artifacts.
            "io.netty" % "netty-all" % "4.1.137.Final",
            "io.netty" % "netty-buffer" % "4.1.137.Final",
            "io.netty" % "netty-codec" % "4.1.137.Final",
            "io.netty" % "netty-codec-http" % "4.1.137.Final",
            "io.netty" % "netty-codec-http2" % "4.1.137.Final",
            "io.netty" % "netty-codec-socks" % "4.1.137.Final",
            "io.netty" % "netty-common" % "4.1.137.Final",
            "io.netty" % "netty-handler" % "4.1.137.Final",
            "io.netty" % "netty-handler-proxy" % "4.1.137.Final",
            "io.netty" % "netty-resolver" % "4.1.137.Final",
            "io.netty" % "netty-resolver-dns" % "4.1.137.Final",
            "io.netty" % "netty-resolver-dns-classes-macos" % "4.1.137.Final",
            "io.netty" % "netty-resolver-dns-native-macos" % "4.1.137.Final",
            "io.netty" % "netty-transport" % "4.1.137.Final",
            "io.netty" % "netty-transport-classes-epoll" % "4.1.137.Final",
            "io.netty" % "netty-transport-classes-kqueue" % "4.1.137.Final",
            "io.netty" % "netty-transport-native-epoll" % "4.1.137.Final",
            "io.netty" % "netty-transport-native-kqueue" % "4.1.137.Final",
            "io.netty" % "netty-transport-native-unix-common" % "4.1.137.Final",
            // gRPC (qdrant + milvus clients): MadeYouReset HTTP/2 DDoS
            // (CVE in grpc-netty-shaded < 1.75.0). All io.grpc artifacts move
            // together — mixed grpc versions misbehave at runtime.
            "io.grpc" % "grpc-api" % "1.75.0",
            "io.grpc" % "grpc-core" % "1.75.0",
            "io.grpc" % "grpc-context" % "1.75.0",
            "io.grpc" % "grpc-netty-shaded" % "1.75.0",
            "io.grpc" % "grpc-protobuf" % "1.75.0",
            "io.grpc" % "grpc-protobuf-lite" % "1.75.0",
            "io.grpc" % "grpc-stub" % "1.75.0",
            "io.grpc" % "grpc-util" % "1.75.0",
            "io.grpc" % "grpc-services" % "1.75.0",
            "io.grpc" % "grpc-inprocess" % "1.75.0",
            // Apache HttpComponents 5.x (transitive via weaviate/snowflake):
            // HTTP/1 header-parsing memory exhaustion + HPACK decoder DoS +
            // disabled domain checks (5.4.3), plus the connection-pool
            // exhaustion leak on Content-Encoding decode errors (client5
            // 5.6.3). httpclient5 5.6.3 builds against httpcore5 5.4.3, so
            // core stays put — check the httpclient5-parent pom before moving
            // either one.
            "org.apache.httpcomponents.client5" % "httpclient5" % "5.6.3",
            "org.apache.httpcomponents.core5" % "httpcore5" % "5.4.3",
            "org.apache.httpcomponents.core5" % "httpcore5-h2" % "5.4.3",
            // Log4j 2.x (transitive via Spark, which ships 2.20.0): TLS
            // hostname-verification gaps in the Socket Appender config and
            // XML/JSON layout encoding flaws, patched across 2.25.3–2.25.5.
            // None of these appenders/layouts are used here, but the bump
            // clears the alerts. All four artifacts move together — a
            // core/api skew breaks logging init at startup.
            "org.apache.logging.log4j" % "log4j-api" % "2.25.5",
            "org.apache.logging.log4j" % "log4j-core" % "2.25.5",
            "org.apache.logging.log4j" % "log4j-1.2-api" % "2.25.5",
            "org.apache.logging.log4j" % "log4j-slf4j2-impl" % "2.25.5",
            // nimbus-jose-jwt (transitive via weaviate's oauth2-oidc-sdk, same
            // tree as the json-smart override below): DoS on deeply nested
            // JSON, patched in 10.0.2. OIDC single sign-on (ai.datris.auth.
            // OidcLogin) now uses it directly too: the direct dependency in
            // allDependencies and this override move together.
            "com.nimbusds" % "nimbus-jose-jwt" % "10.0.2",
            // Single-artifact CVE patch bumps over stale transitives:
            // beanutils RCE/deserialization (everit), json-smart recursion DoS
            // (azure msal), org.json DoS (everit), aircompressor buffer leak +
            // lz4 OOB (Spark compression codecs), ivy XXE (Spark).
            "commons-beanutils" % "commons-beanutils" % "1.11.0",
            "net.minidev" % "json-smart" % "2.5.2",
            "org.json" % "json" % "20231013",
            "io.airlift" % "aircompressor" % "2.0.3",
            "org.lz4" % "lz4-java" % "1.8.1",
            // kafka-clients 3.9.x switched lz4 to the at.yawk.lz4 fork (the
            // org.lz4 pin above still covers Spark). 1.11.1 patched the native
            // XXHash JVM crash on invalid byte ranges; 1.11.4 adds the
            // unvalidated-length allocation, empty-block recursion, per-frame
            // buffer reallocation and shared-tmpdir native extraction fixes.
            // Both lz4 jars ship the same net.jpountz classes; the assembly
            // MergeStrategy.first dedupe handles the overlap, as it already
            // did before this bump.
            "at.yawk.lz4" % "lz4-java" % "1.11.4",
            "org.apache.ivy" % "ivy" % "2.5.2"
        ),
        // gitHeadCommit + builtAtMillis let /api/v1/version say WHICH jar is
        // running — `datris doctor` compares them with the checkout to catch a
        // stale jar served from the Docker build cache (bit us in v1.17.0).
        buildInfoKeys := Seq[BuildInfoKey](
            name,
            version,
            scalaVersion,
            sbtVersion,
            BuildInfoKey.action("gitHeadCommit") {
                scala.util.Try(scala.sys.process.Process("git rev-parse HEAD").!!.trim).getOrElse("unknown")
            },
            BuildInfoKey.action("builtAtMillis") { System.currentTimeMillis() }
        ),
        buildInfoPackage := "ai.datris.build.sbt",
        libraryDependencies ++= Seq(
            "org.scalatest"     %% "scalatest"    % "3.2.19"   % Test,
            "org.scalatestplus" %% "mockito-5-12" % "3.2.19.0" % Test
        ),
        Test / fork := true,
        // Spark 3.5 on JDK 17 --add-opens (the same set docker-init.sh passes to
        // the server, so a spec that runs a local SparkSession — e.g. the CSV
        // read in SparkObjectStoreLoaderSpec, which touches sun.util.calendar —
        // behaves as production does); harmless for pure tests. The bytebuddy
        // flag lets mockito mock JDK interfaces on JVMs newer than it knows about
        // (e.g. a Java 25 dev machine); no-op where the JVM is already supported.
        Test / javaOptions ++= Seq(
            "-Dnet.bytebuddy.experimental=true",
            "--add-opens=java.base/java.lang=ALL-UNNAMED",
            "--add-opens=java.base/java.lang.invoke=ALL-UNNAMED",
            "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED",
            "--add-opens=java.base/java.io=ALL-UNNAMED",
            "--add-opens=java.base/java.net=ALL-UNNAMED",
            "--add-opens=java.base/java.nio=ALL-UNNAMED",
            "--add-opens=java.base/java.util=ALL-UNNAMED",
            "--add-opens=java.base/java.util.concurrent=ALL-UNNAMED",
            "--add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED",
            "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
            "--add-opens=java.base/sun.nio.cs=ALL-UNNAMED",
            "--add-opens=java.base/sun.security.action=ALL-UNNAMED",
            "--add-opens=java.base/sun.util.calendar=ALL-UNNAMED"
        ),
        // Opt-in heap cap for the forked test JVM (e.g. DATRIS_TEST_XMX=512m) so
        // the streaming specs can prove bounded memory; default heap unchanged.
        Test / javaOptions ++= sys.env.get("DATRIS_TEST_XMX").map("-Xmx" + _).toSeq
    )

lazy val allDependencies = Seq(
    // Object store
    "io.minio" % "minio" % "8.6.0",
    // minio 8.6.0 depends on okhttp 5.x, whose base `okhttp` artifact is a
    // Gradle-metadata redirect with NO classes under Maven/sbt resolution —
    // the fat jar builds green and then dies at runtime with
    // ClassNotFoundException: okhttp3.RequestBody on the first MinIO call.
    // okhttp-jvm is the artifact that actually contains the classes.
    "com.squareup.okhttp3" % "okhttp-jvm" % "5.1.0",

    // HTTP
    "org.apache.httpcomponents" % "httpclient" % "4.5.14",

    // OIDC single sign-on: JWKS + ID-token validation. Same version as the
    // dependencyOverrides pin above; the two move together.
    "com.nimbusds" % "nimbus-jose-jwt" % "10.0.2",

    // Google
    "com.google.guava" % "guava" % "33.0.0-jre",
    "com.google.code.gson" % "gson" % "2.11.0",

    // Apache commons
    "org.apache.commons" % "commons-compress" % "1.28.0",
    "org.apache.commons" % "commons-csv" % "1.12.0",

    // JSON schema validation
    "org.everit.json" % "org.everit.json.schema" % "1.5.1",

    // Scheduling
    "org.quartz-scheduler" % "quartz" % "2.5.0",

    // Databases
    "org.postgresql" % "postgresql" % "42.7.12",
    // Connection pooling for Postgres (slf4j-only transitives; no Spark conflicts)
    "com.zaxxer" % "HikariCP" % "5.1.0",
    "com.mysql" % "mysql-connector-j" % "8.4.0",
    // 4.3.4 patches the sensitive-values-in-logs advisory and is past the
    // SdkProxyRoutePlanner resource-consumption range (<= 4.0.1). 4.x is a
    // major line; the Snowflake destination E2E must pass before release.
    "net.snowflake" % "snowflake-jdbc" % "4.4.0",
    // Databricks OSS JDBC driver (Apache 2.0) — an uber jar with its own deps
    // shaded under com.databricks.jdbc.internal.*, so it can't collide with
    // Spark's arrow/netty. Used by DatabricksLoader / DatabricksQueryUtil.
    "com.databricks" % "databricks-jdbc" % "3.4.1",

    // Kafka
    "org.apache.kafka" % "kafka-clients" % "3.9.2",

    // Logging
    "org.slf4j" % "slf4j-api" % "2.0.16",

    // Spring Boot. 4.0.x because the Framework 6.2 / Boot 3.5 lines are past
    // OSS support. 4.0.8 manages Spring Framework 7.0.9, which carries the OSS
    // fixes for the spring-webmvc advisories GHSA-j9f9-w8pj-32f8 (SSE stream
    // corruption with view fragments) and GHSA-pc63-qcmh-9cmg (XsltView SSRF/RCE).
    // spring-boot-starter-jackson is excluded so Jackson 3 never reaches the
    // classpath; spring-boot-jackson2 keeps the Jackson 2 ObjectMapper
    // (ParameterNamesModule) and HTTP converters (SpringBootVersionSpec guards this).
    "org.springframework.boot" % "spring-boot-starter" % "4.0.8",
    ("org.springframework.boot" % "spring-boot-starter-webmvc" % "4.0.8")
        .exclude("org.springframework.boot", "spring-boot-starter-jackson"),
    "org.springframework.boot" % "spring-boot-jackson2" % "4.0.8",
    // Actuator + Prometheus metrics (/actuator/prometheus). The registry version
    // must match what Boot 4.0.8 manages (micrometer 1.16.7) — sbt has no BOM.
    "org.springframework.boot" % "spring-boot-starter-actuator" % "4.0.8",
    "io.micrometer" % "micrometer-registry-prometheus" % "1.16.7",
    // JSON console logs when the production profile is active (see logback-spring.xml).
    // Stays on 8.1: 9.0 depends on Jackson 3.
    "net.logstash.logback" % "logstash-logback-encoder" % "8.1",

    // Password hashing for UI user auth (BCrypt). The crypto module is
    // standalone (no spring-framework deps); 7.0.7 is the version Boot 4.0.8
    // manages.
    "org.springframework.security" % "spring-security-crypto" % "7.0.7",

    // Spark
    "org.apache.spark" %% "spark-core" % "3.5.9",
    "org.apache.spark" %% "spark-sql" % "3.5.9",
    // hadoop-aws must match the hadoop-common that Spark ships. Spark 3.5.x
    // bundles hadoop 3.3.4 — using a newer hadoop-aws (3.3.5+) leaves it
    // calling IOStatisticsBinding overloads that don't exist in 3.3.4, with
    // a NoSuchMethodError on the first Parquet read from S3A. Keep these
    // versions locked together; bumping one requires bumping the other.
    "org.apache.hadoop" % "hadoop-aws" % "3.3.4",
    // Apache Iceberg table format for objectStore destinations. The
    // spark-runtime jar is the shaded bundle (own Jackson, Avro, Caffeine,
    // RoaringBitmap under org.apache.iceberg.shaded), so it does not disturb
    // the Jackson quad-pin or the Avro CVE pin above — SparkJacksonCompatSpec
    // is the guard. Must track the Spark minor (3.5) and Scala binary (2.12)
    // versions pinned here; bump it when Spark moves.
    "org.apache.iceberg" % "iceberg-spark-runtime-3.5_2.12" % "1.11.0",

    // AWS SigV4 signing + credential chain for the Bedrock AI provider.
    // Signer-only: requests are signed here and executed on the shared Apache
    // client in AIHttp — the full Bedrock runtime SDK client is deliberately
    // not used. SDK v2 (software.amazon.awssdk.*) coexists with the v1 classes
    // hadoop-aws drags in (com.amazonaws.*) — different packages, no conflict.
    "software.amazon.awssdk" % "auth" % "2.51.4",
    "software.amazon.awssdk" % "http-auth-aws" % "2.51.4",
    "software.amazon.awssdk" % "regions" % "2.51.4",

    // Entra ID token acquisition for Azure OpenAI keyless auth (service
    // principal / managed identity) — the Azure analogue of the Bedrock
    // credential chain above. Token-fetch-only: AI requests still execute on
    // the shared Apache client in AIHttp; azure-identity only talks to the
    // Entra token endpoints. azure-identity declares its own azure-core +
    // azure-core-http-netty transport (milvus-sdk-java 2.5+ no longer ships
    // the azure-storage-blob stack it used to piggyback on). Jackson stays
    // safe: the 2.15.2 dependencyOverrides pin above applies to azure-core's
    // transitives too.
    "com.azure" % "azure-identity" % "1.18.4",

    // Secrets: HashiCorp Vault
    "io.github.jopenlibs" % "vault-java-driver" % "6.2.1",

    // Queue + notifications: ActiveMQ
    "org.apache.activemq" % "activemq-client" % "5.19.4",
    "org.apache.activemq" % "activemq-pool" % "5.19.4",

    // NoSQL: MongoDB
    "org.mongodb" % "mongodb-driver-sync" % "4.11.4",

    // Vector database: Qdrant
    "io.qdrant" % "client" % "1.12.0",

    // Vector database: Weaviate
    "io.weaviate" % "client" % "4.9.0",

    // Vector database: Milvus. 2.5.x dropped the bulkwriter dependency tree
    // (hadoop-client, parquet-avro, minio, azure-storage-blob) that 2.4.x
    // shipped — we only use the io.milvus.v2 client API, never the bulkwriter,
    // and that tree carried three critical CVEs (parquet-avro RCE, the
    // unpatchable jackson-mapper-asl, and hadoop's old avro/zookeeper).
    // 2.5.10 rather than 2.6.x: users bring their own Milvus server, and the
    // 2.5 SDK has the wider server-compat window.
    "io.milvus" % "milvus-sdk-java" % "2.5.10",

    // Document text extraction
    "org.apache.pdfbox" % "pdfbox" % "3.0.4",
    // POI 5.4.x patches the OOXML input-validation advisory; jsoup 1.23.2
    // patches the Cleaner raw-text-element exposure and its follow-up. All three POI artifacts
    // move together.
    "org.apache.poi" % "poi" % "5.4.1",
    "org.apache.poi" % "poi-ooxml" % "5.4.1",
    "org.apache.poi" % "poi-scratchpad" % "5.4.1",
    "org.jsoup" % "jsoup" % "1.23.2",

    // Email parsing
    "org.eclipse.angus" % "angus-mail" % "2.0.3",

    // JavaScript engine (Nashorn removed in Java 15+)
    "org.openjdk.nashorn" % "nashorn-core" % "15.4",

    // Exact OpenAI tokenization (cl100k_base / o200k_base / p50k_base / r50k_base).
    // ~150 KB, MIT, pure JVM. Used by TokenGuard's OpenAITokenCounter when the
    // embedding model name matches an OpenAI family; otherwise the heuristic
    // counter is used and this dependency is dormant.
    "com.knuddels" % "jtokkit" % "1.1.0"
)

lazy val assemblySettings = Seq(
    assembly / assemblyJarName := ("datris-" + name.value + "-assembly-" + version.value + ".jar"),
    assembly / assemblyMergeStrategy := {
        case PathList("META-INF", "spring.factories") => MergeStrategy.concat
        case PathList("META-INF", "spring", _@_*) => MergeStrategy.concat
        case PathList("META-INF", "services", _@_*) => MergeStrategy.filterDistinctLines
        case PathList("META-INF", _@_*) => MergeStrategy.discard
        case "module-info.class" => MergeStrategy.discard
        case "reference.conf" => MergeStrategy.concat
        case _ => MergeStrategy.first
    }
)
