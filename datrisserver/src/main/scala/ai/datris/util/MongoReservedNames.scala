package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{Database, DatrisEnvironment, MongoDBConfig}

import java.util.Locale

/** The MongoDB names that hold no pipeline data, and the rule built on them,
  * shared by the query route, the metadata listings, pipeline saves, the
  * MongoDB loader and pipeline delete. Pure, no I/O.
  *
  *   - MongoDB's own databases ([[SystemDatabases]]): refused outright.
  *   - `system.` collections, in every database.
  *   - Platform collections ([[platformCollection]]) in the internal database
  *     (`mongodb.internalDatabase`): the collections the platform keeps its
  *     own state in. Other collections there are data collections.
  *
  * Database names are compared ignoring case and surrounding whitespace
  * (MongoDB refuses two databases differing only in case, and a
  * case-insensitive file system can map them to one). Collection names are
  * compared exactly: MongoDB treats them case-sensitively on every platform
  * (storage files are not named after collections), and the platform spells
  * each of its own one way. */
object MongoReservedNames {

    /** MongoDB's own databases. */
    val SystemDatabases: Seq[String] = Seq("admin", "local", "config")

    /** Body text of a save refusal and of a failed load. */
    val DestinationRefusal: String =
        "A MongoDB destination must be a data collection, and a new destination must be in a data database. Choose another database or collection name."

    private def norm(s: String): String = s.trim

    /** Whether `a` and `b` name the same database (ignoring case and
      * surrounding whitespace). False when either is null or blank. */
    def sameDatabase(a: String, b: String): Boolean =
        a != null && b != null && norm(b).nonEmpty && {
            val x = norm(a)
            val y = norm(b)
            x.equalsIgnoreCase(y) || x.toLowerCase(Locale.ROOT) == y.toLowerCase(Locale.ROOT) || x.toUpperCase(Locale.ROOT) == y.toUpperCase(Locale.ROOT)
        }

    /** Whether `dbName` is one of MongoDB's own databases. */
    def systemDatabase(dbName: String): Boolean = SystemDatabases.exists(sameDatabase(dbName, _))

    private def internalName(env: DatrisEnvironment): String =
        Option(env).flatMap(e => Option(e.mongoDbConfig)).map(_.internalDatabase).orNull

    /** Whether `dbName` is the platform's internal database. */
    def isInternal(dbName: String, env: DatrisEnvironment): Boolean = sameDatabase(dbName, internalName(env))

    /** Whether `collection` is a `system.` collection (ignoring case and
      * surrounding whitespace). A dotted name such as `a.b` is an ordinary
      * collection in its database. */
    def collectionReserved(collection: String): Boolean =
        collection != null && collection.trim.toLowerCase(Locale.ROOT).startsWith("system.")

    /** This environment's platform collection names, current and legacy. */
    def platformNames(env: DatrisEnvironment): Seq[String] =
        if (env == null) Nil else env.platformCollectionNames ++ env.legacyPlatformCollectionNames

    /** The part after the environment prefix of each of [[platformNames]].
      * The same for every tenant: each tenant's collections are
      * `<tenant>-<part>`. */
    def platformSuffixes(env: DatrisEnvironment): Seq[String] =
        if (env == null) Nil
        else {
            val prefix = env.environment + "-"
            platformNames(env).filter(_.startsWith(prefix)).map(_.stripPrefix(prefix)).filter(_.nonEmpty).distinct
        }

    /** Whether `collection` is a platform collection. Single-tenant: exactly
      * one of this environment's names ([[platformNames]]). Multi-tenant,
      * where the internal database holds every tenant's collections: also any
      * name ending in `-` and one of [[platformSuffixes]], whatever comes
      * before. Exact, case-sensitive. */
    def platformCollection(collection: String, env: DatrisEnvironment): Boolean =
        collection != null && env != null &&
            (platformNames(env).contains(collection) ||
                (env.multiTenant && platformSuffixes(env).exists(s => collection.endsWith("-" + s))))

    /** Whether `collection` in `dbName` is never read, listed or written: in a
      * system database, a `system.` collection, or a platform collection in
      * the internal database. */
    def protectedCollection(dbName: String, collection: String, env: DatrisEnvironment): Boolean =
        systemDatabase(dbName) || collectionReserved(collection) || (isInternal(dbName, env) && platformCollection(collection, env))

    /** The install's data database: the tenant's own in a multi-tenant
      * install, else `mongodb.database`. */
    def dataDatabase(env: DatrisEnvironment): String =
        if (env == null) null else if (env.multiTenant) env.environment else Option(env.mongoDbConfig).map(_.database).orNull

    /** Whether the install's data database is its internal database. */
    def dataDatabaseIsInternal(env: DatrisEnvironment): Boolean = isInternal(dataDatabase(env), env)

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

    /** [[DestinationRefusal]] when `db` is a MongoDB destination whose
      * collection is protected ([[protectedCollection]]) in the database it
      * loads to or deletes from. Applies to every pipeline, stored or new:
      * the loader, delete and every save use it. None otherwise. */
    def protectedProblem(db: Database, env: DatrisEnvironment): Option[String] =
        if (db == null || !db.useMongoDB) None
        else if (Seq(loadDatabase(db, env), deleteDatabase(db, env)).exists(protectedCollection(_, db.table, env))) Some(DestinationRefusal)
        else None

    /** Whether `db` and `stored` name the same MongoDB destination: both
      * MongoDB, the same collection, and databases that resolve to the same
      * one ([[loadDatabase]], so a blank or placeholder name and the default
      * name it resolves to are the same). */
    def sameMongoDestination(db: Database, stored: Database, env: DatrisEnvironment): Boolean =
        db != null && stored != null && db.useMongoDB && stored.useMongoDB && db.table == stored.table &&
            sameDatabase(loadDatabase(db, env), loadDatabase(stored, env))

    /** The save rule: [[protectedProblem]], and for a destination that is not
      * `stored`'s ([[sameMongoDestination]]) one in the internal database,
      * unless the install's data database is the internal database. */
    def destinationProblem(db: Database, env: DatrisEnvironment, stored: Database): Option[String] =
        protectedProblem(db, env).orElse(
            if (db == null || !db.useMongoDB || sameMongoDestination(db, stored, env) || dataDatabaseIsInternal(env)) None
            else if (Seq(loadDatabase(db, env), deleteDatabase(db, env)).exists(isInternal(_, env))) Some(DestinationRefusal)
            else None
        )

    /** The startup warning for a configuration whose data database is its
      * internal database; None when they differ. */
    def sharedDatabaseWarning(cfg: MongoDBConfig): Option[String] =
        if (cfg != null && sameDatabase(cfg.database, cfg.internalDatabase))
            Some(
                "mongodb.database and mongodb.internalDatabase name the same database; pipeline data and platform state share it. " +
                    "Use separate databases."
            )
        else None
}
