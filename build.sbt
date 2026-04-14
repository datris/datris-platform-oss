name := "datris-server"
ThisBuild / organization := "ai.datris"
ThisBuild / scalaVersion := "2.12.21"
ThisBuild / version := "1.6.2"

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
        buildInfoKeys := Seq[BuildInfoKey](name, version, scalaVersion, sbtVersion),
        buildInfoPackage := "ai.datris.build.sbt"
    )

lazy val allDependencies = Seq(
    // Object store
    "io.minio" % "minio" % "8.5.14",

    // HTTP
    "org.apache.httpcomponents" % "httpclient" % "4.5.14",

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
    "org.postgresql" % "postgresql" % "42.7.7",
    "com.mysql" % "mysql-connector-j" % "8.4.0",

    // Kafka
    "org.apache.kafka" % "kafka-clients" % "3.9.1",

    // Logging
    "org.slf4j" % "slf4j-api" % "2.0.16",

    // Spring Boot
    "org.springframework.boot" % "spring-boot-starter" % "3.2.12",
    "org.springframework.boot" % "spring-boot-starter-web" % "3.2.12",

    // Spark
    "org.apache.spark" %% "spark-core" % "3.5.4",
    "org.apache.spark" %% "spark-sql" % "3.5.4",
    "org.apache.hadoop" % "hadoop-aws" % "3.3.6",

    // Secrets: HashiCorp Vault
    "io.github.jopenlibs" % "vault-java-driver" % "6.2.1",

    // Queue + notifications: ActiveMQ
    "org.apache.activemq" % "activemq-client" % "5.18.6",
    "org.apache.activemq" % "activemq-pool" % "5.18.6",

    // NoSQL: MongoDB
    "org.mongodb" % "mongodb-driver-sync" % "4.11.4",

    // Vector database: Qdrant
    "io.qdrant" % "client" % "1.12.0",

    // Vector database: Weaviate
    "io.weaviate" % "client" % "4.9.0",

    // Vector database: Milvus
    "io.milvus" % "milvus-sdk-java" % "2.4.4",

    // Document text extraction
    "org.apache.pdfbox" % "pdfbox" % "3.0.4",
    "org.apache.poi" % "poi" % "5.3.0",
    "org.apache.poi" % "poi-ooxml" % "5.3.0",
    "org.apache.poi" % "poi-scratchpad" % "5.3.0",
    "org.jsoup" % "jsoup" % "1.17.2",

    // Email parsing
    "org.eclipse.angus" % "angus-mail" % "2.0.3",

    // JavaScript engine (Nashorn removed in Java 15+)
    "org.openjdk.nashorn" % "nashorn-core" % "15.4"
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
