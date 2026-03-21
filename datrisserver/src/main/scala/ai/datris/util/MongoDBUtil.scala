package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
*/

import com.mongodb.client.model.{Filters, ReplaceOptions}
import com.mongodb.client.{ClientSession, MongoClient, MongoDatabase}
import ai.datris.model.{DatrisEnvironment, DatrisException}
import org.bson.Document
import org.bson.json.{JsonMode, JsonWriterSettings}

import scala.collection.JavaConverters._

class MongoDBUtil(database: MongoDatabase) extends NoSQLDbUtility {

    private val jsonSettings = JsonWriterSettings.builder().outputMode(JsonMode.RELAXED).build()

    override def getItemsKeysByKeyName(tableName: String, keyName: String): List[String] = {
        val collection = database.getCollection(tableName)
        collection.find()
            .projection(new Document(keyName, 1).append("_id", 0))
            .asScala
            .map(doc => doc.getString(keyName))
            .toList
    }

    override def getItemAttribute[T](tableName: String, keyName: String, key: String, attributeName: String): T = {
        val collection = database.getCollection(tableName)
        val doc = collection.find(Filters.eq(keyName, key)).first()
        if (doc == null)
            throw new DatrisException(s"Configuration error: item with $keyName=$key was not found in collection $tableName")
        doc.get(attributeName).asInstanceOf[T]
    }

    override def setItemNameValue(tableName: String, keyName: String, key: String, valueName: String, value: String): Unit = {
        val collection = database.getCollection(tableName)
        val doc = new Document(keyName, key).append(valueName, value)
        collection.replaceOne(
            Filters.eq(keyName, key),
            doc,
            new ReplaceOptions().upsert(true)
        )
    }

    override def getItemJSON(tableName: String, keyName: String, key: String, valueName: String): Option[String] = {
        val collection = database.getCollection(tableName)
        val doc = collection.find(Filters.eq(keyName, key)).first()

        if (doc == null)
            None
        else if (valueName == null)
            Some(doc.toJson(jsonSettings))
        else {
            val value = doc.get(valueName)
            if (value == null) None
            else value match {
                case d: Document => Some(d.toJson(jsonSettings))
                case s: String => Some(s)
                case other => Some(other.toString)
            }
        }
    }

    override def putItemJSON(tableName: String, keyName: String, key: String, valueName: String, value: String, sortKeyName: String = null, sortKeyValue: Number = null): Unit = {
        val collection = database.getCollection(tableName)
        val valueDoc = Document.parse(value)

        val doc = new Document(keyName, key)
            .append(valueName, valueDoc)

        if (sortKeyName != null)
            doc.append(sortKeyName, sortKeyValue.longValue(): java.lang.Long)

        collection.replaceOne(
            buildKeyFilter(keyName, key, sortKeyName, sortKeyValue),
            doc,
            new ReplaceOptions().upsert(true)
        )
    }

    override def updateItemJSON(tableName: String, keyName: String, key: String, valueName: String, value: String, sortKeyName: String = null, sortKeyValue: Number = null): Unit = {
        val collection = database.getCollection(tableName)
        val valueDoc = Document.parse(value)

        val update = new Document("$set", new Document(valueName, valueDoc))

        collection.updateOne(
            buildKeyFilter(keyName, key, sortKeyName, sortKeyValue),
            update
        )
    }

    override def deleteItemJSON(tableName: String, keyName: String, key: String, sortKeyName: String = null, sortKeyValue: Number = null): Unit = {
        val collection = database.getCollection(tableName)
        collection.deleteOne(buildKeyFilter(keyName, key, sortKeyName, sortKeyValue))
    }

    override def queryJSONItemsByKey(tableName: String, keyName: String, key: String): List[String] = {
        val collection = database.getCollection(tableName)
        collection.find(Filters.eq(keyName, key))
            .asScala
            .map(doc => doc.toJson(jsonSettings))
            .toList
    }

    override def getAllItemsAsJSON(tableName: String): List[String] = {
        val collection = database.getCollection(tableName)
        collection.find()
            .asScala
            .map(doc => doc.toJson(jsonSettings))
            .toList
    }

    override def getPageOfItemsAsJSON(tableName: String, pageNbr: Int, maxPageSize: Int,
                                      sortField: String = null, sortDescending: Boolean = true): List[String] = {
        val collection = database.getCollection(tableName)
        var query = collection.find()
        if (sortField != null)
            query = query.sort(new Document(sortField, if (sortDescending) -1 else 1))
        query.skip(pageNbr * maxPageSize)
            .limit(maxPageSize)
            .asScala
            .map(doc => doc.toJson(jsonSettings))
            .toList
    }

    override def insertJSON(tableName: String, json: String): Unit = {
        val collection = database.getCollection(tableName)
        val doc = Document.parse(json)
        collection.insertOne(doc)
    }

    def insertJSON(tableName: String, json: String, session: ClientSession): Unit = {
        val collection = database.getCollection(tableName)
        val doc = Document.parse(json)
        collection.insertOne(session, doc)
    }

    override def deleteAll(tableName: String): Long = {
        val collection = database.getCollection(tableName)
        collection.deleteMany(new Document()).getDeletedCount
    }

    def deleteAll(tableName: String, session: ClientSession): Long = {
        val collection = database.getCollection(tableName)
        collection.deleteMany(session, new Document()).getDeletedCount
    }

    override def upsertJSON(tableName: String, keyFields: java.util.List[String], json: String): Unit = {
        val collection = database.getCollection(tableName)
        val doc = Document.parse(json)

        // Build a compound filter from all key fields
        val filters = keyFields.asScala.map(keyField => {
            val value = doc.get(keyField)
            if (value == null)
                throw new DatrisException("Key field '" + keyField + "' not found in document")
            Filters.eq(keyField, value)
        })

        val filter = if (filters.size == 1) filters.head else Filters.and(filters.asJava)

        collection.replaceOne(filter, doc, new ReplaceOptions().upsert(true))
    }

    def upsertJSON(tableName: String, keyFields: java.util.List[String], json: String, session: ClientSession): Unit = {
        val collection = database.getCollection(tableName)
        val doc = Document.parse(json)

        val filters = keyFields.asScala.map(keyField => {
            val value = doc.get(keyField)
            if (value == null)
                throw new DatrisException("Key field '" + keyField + "' not found in document")
            Filters.eq(keyField, value)
        })

        val filter = if (filters.size == 1) filters.head else Filters.and(filters.asJava)

        collection.replaceOne(session, filter, doc, new ReplaceOptions().upsert(true))
    }

    private def buildKeyFilter(keyName: String, key: String, sortKeyName: String, sortKeyValue: Number): org.bson.conversions.Bson = {
        if (sortKeyName != null) {
            val concreteValue: java.lang.Long = sortKeyValue.longValue()
            Filters.and(Filters.eq(keyName, key), Filters.eq(sortKeyName, concreteValue))
        }
        else
            Filters.eq(keyName, key)
    }
}

object MongoDBUtilBuilder {
    private def createClient(connectionString: String): MongoClient = {
        val connString = new com.mongodb.ConnectionString(connectionString)
        val settings = com.mongodb.MongoClientSettings.builder()
            .applyConnectionString(connString)
            .build()
        com.mongodb.client.MongoClients.create(settings)
    }

    // Default client from DatrisEnvironment (cached)
    private lazy val defaultClient: MongoClient =
        createClient(DatrisEnvironment.values.mongoDbConfig.connectionString)

    // Default: uses DatrisEnvironment for both connection string and database
    def build(): NoSQLDbUtility = {
        new MongoDBUtil(defaultClient.getDatabase(DatrisEnvironment.values.mongoDbConfig.database))
    }

    // Override database only (reuses default connection)
    def build(databaseName: String): NoSQLDbUtility = {
        new MongoDBUtil(defaultClient.getDatabase(databaseName))
    }

    // Override both connection string and database
    def build(connectionString: String, databaseName: String): NoSQLDbUtility = {
        val client = createClient(connectionString)
        new MongoDBUtil(client.getDatabase(databaseName))
    }

    // Returns the MongoClient alongside MongoDBUtil — needed for session-based transactions
    def buildWithClient(connectionString: String, databaseName: String): (MongoClient, MongoDBUtil) = {
        val client = createClient(connectionString)
        (client, new MongoDBUtil(client.getDatabase(databaseName)))
    }
}
