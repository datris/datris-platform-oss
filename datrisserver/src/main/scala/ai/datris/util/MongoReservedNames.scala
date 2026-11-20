package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{Database, DatrisEnvironment}

import java.util.Locale

/** The MongoDB databases and collections that hold no pipeline data: the
  * platform's internal database (`mongodb.internalDatabase`), MongoDB's own
  * system databases, and `system.` collections. The one rule shared by the
  * query route, the metadata listings, pipeline saves, the MongoDB loader
  * and pipeline delete. Pure, no I/O. */
object MongoReservedNames {

    /** MongoDB's own databases. */
    val SystemDatabases: Seq[String] = Seq("admin", "local", "config")

    /** Body text of a save refusal and of a failed load. */
    val DestinationRefusal: String =
        "A MongoDB destination must be an ordinary collection in a data database. Choose another database or collection name."

    /** Whether `dbName` is the platform's internal database or a system
      * database. Compared ignoring case and surrounding whitespace. */
    def databaseReserved(dbName: String, internalDatabase: String): Boolean =
        if (dbName == null) false
        else {
            val reserved = SystemDatabases ++ Option(internalDatabase).map(_.trim).filter(_.nonEmpty).toSeq
            val name = dbName.trim
            reserved.exists(r =>
                name.equalsIgnoreCase(r) ||
                    name.toLowerCase(Locale.ROOT) == r.toLowerCase(Locale.ROOT) ||
                    name.toUpperCase(Locale.ROOT) == r.toUpperCase(Locale.ROOT)
            )
        }

    /** Whether `collection` is a `system.` collection (ignoring case and
      * surrounding whitespace). A dotted name such as `a.b` is an ordinary
      * collection in the destination database. */
    def collectionReserved(collection: String): Boolean =
        collection != null && collection.trim.toLowerCase(Locale.ROOT).startsWith("system.")

    /** The database the MongoDB loader writes `db` to: its `dbName`, or the
      * save-time default when blank or the placeholder
      * ([[PipelineValidatorUtil.applyDefaults]]). */
    def loadDatabase(db: Database, env: DatrisEnvironment): String =
        if (db.dbName == null || db.dbName.isEmpty || db.dbName == "DATABASE_NAME") Option(env).map(_.postgresDatabase).orNull else db.dbName

    /** The database a pipeline delete drops `db`'s collection from: the
      * tenant's own database in a multi-tenant install, else `dbName`, else
      * `datris`. */
    def deleteDatabase(db: Database, env: DatrisEnvironment): String =
        if (env != null && env.multiTenant) env.environment else if (db.dbName != null) db.dbName else "datris"

    /** [[DestinationRefusal]] when `db` is a MongoDB destination whose load
      * or delete database is reserved, or whose collection is a `system.`
      * collection; None for every other block (a null one included). */
    def destinationProblem(db: Database, env: DatrisEnvironment): Option[String] =
        if (db == null || !db.useMongoDB) None
        else {
            // No environment (a bare unit context): the system databases and
            // collections are still checked.
            val internal = Option(env).flatMap(e => Option(e.mongoDbConfig)).map(_.internalDatabase).orNull
            val databases = Seq(loadDatabase(db, env), deleteDatabase(db, env))
            if (databases.exists(databaseReserved(_, internal)) || collectionReserved(db.table)) Some(DestinationRefusal) else None
        }
}
