package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{DatrisEnvironment, MongoDBConfig, User}
import com.google.gson.{Gson, JsonParser}

import scala.collection.mutable

/** In-memory `{env}-user` table for specs that drive the real [[UserStore]]
  * through `UserStore.dbOverride`. Rows are the Gson JSON UserStore writes,
  * keyed on `username`, so a row stored with `putJson` can stand in for a
  * document written before a field existed (upgrade path). */
class InMemoryUserDb extends NoSQLDbUtility {
    private val gson = new Gson
    val rows: mutable.LinkedHashMap[String, String] = mutable.LinkedHashMap()
    private def keyOf(json: String): String = JsonParser.parseString(json).getAsJsonObject.get("username").getAsString

    def put(u: User): Unit = rows.put(u.username, gson.toJson(u))
    def putJson(json: String): Unit = rows.put(keyOf(json), json)
    def user(name: String): Option[User] = rows.get(name).map(gson.fromJson(_, classOf[User]))

    override def getAllItemsAsJSON(tableName: String): List[String] = rows.values.toList
    override def getItemJSON(tableName: String, keyName: String, key: String, valueName: String): Option[String] = rows.get(key)
    override def insertJSON(tableName: String, json: String): Unit = rows.put(keyOf(json), json)
    override def upsertJSON(tableName: String, keyFields: java.util.List[String], json: String): Unit = rows.put(keyOf(json), json)
    override def deleteItemJSON(tableName: String, keyName: String, key: String, sortKeyName: String, sortKeyValue: Number): Unit =
        rows.remove(key)
    override def deleteAll(tableName: String): Long = { val n = rows.size; rows.clear(); n.toLong }
    override def getItemsKeysByKeyName(tableName: String, keyName: String): List[String] = rows.keys.toList
    override def getItemAttribute[T](tableName: String, keyName: String, key: String, attributeName: String): T = null.asInstanceOf[T]
    override def setItemNameValue(tableName: String, keyName: String, key: String, valueName: String, value: String): Unit = ()
    override def putItemJSON(
        tableName: String,
        keyName: String,
        key: String,
        valueName: String,
        value: String,
        sortKeyName: String,
        sortKeyValue: Number,
        extraFields: java.util.Map[String, AnyRef]
    ): Unit = ()
    override def updateItemJSON(
        tableName: String,
        keyName: String,
        key: String,
        valueName: String,
        value: String,
        sortKeyName: String,
        sortKeyValue: Number
    ): Unit = ()
    override def queryJSONItemsByKey(tableName: String, keyName: String, key: String): List[String] = rows.get(key).toList
    override def getPageOfItemsAsJSON(tableName: String, pageNbr: Int, maxPageSize: Int, sortField: String, sortDescending: Boolean): List[String] =
        rows.values.toList
    override def getItemsSinceAsJSON(tableName: String, sortField: String, sinceEpochMs: Long, maxItems: Int): List[String] = Nil
}

object InMemoryUserDb {

    /** Single-tenant test environment: keys off, user auth on, optional audit log. */
    def env(useAuditLog: Boolean = false): DatrisEnvironment = DatrisEnvironment(
        initialized = true,
        environment = "test",
        fileNotifierQueue = null,
        ttlFileNotifierQueueMessages = 0,
        pipelineTopic = null,
        pipelineTableName = null,
        archivedMetadataTableName = null,
        pipelineStatusTableName = null,
        fileNotifierMessageTableName = null,
        dataPullTableName = null,
        useApiKeys = false,
        apiKeysSecretName = null,
        postgresSecretName = null,
        mongoDbSecretName = null,
        kafkaProducerSecretName = null,
        kafkaConsumerConfig = null,
        mongoDbConfig = MongoDBConfig("mongodb://unused", "datris", "datris"),
        minIOConfig = null,
        activeMQConfig = null,
        aiConfig = null,
        aiEnabled = false,
        embeddingSecretName = null,
        qdrantSecretName = null,
        weaviateSecretName = null,
        milvusSecretName = null,
        chromaSecretName = null,
        pgvectorSecretName = null,
        multiTenant = false,
        useUserAuth = true,
        userTableName = "test-user",
        useAuditLog = useAuditLog,
        auditLogTableName = "test-audit-log"
    )

    /** java.util.Map literal for grants (catalog -> level). */
    def grants(pairs: (String, String)*): java.util.Map[String, String] = {
        val m = new java.util.LinkedHashMap[String, String]()
        pairs.foreach { case (k, v) => m.put(k, v) }
        m
    }
}
