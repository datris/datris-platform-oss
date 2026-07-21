package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import com.zaxxer.hikari.{HikariConfig, HikariDataSource}

import java.sql.Connection
import java.util.concurrent.ConcurrentHashMap

/** Pooled Postgres connections, one small pool per (jdbcUrl, user).
  *
  * Replaces raw DriverManager.getConnection per job/query. Sizing is modest on
  * purpose — multi-tenant mode means one pool per tenant database, so many
  * small pools beat one big one. minimumIdle=0 lets idle tenants drop to zero
  * connections after idleTimeout.
  *
  * Hikari resets autoCommit/readOnly/etc. when a connection is returned, so
  * callers may set them freely inside withConnection.
  */
object PostgresPool {

    private val pools = new ConcurrentHashMap[(String, String), HikariDataSource]()

    def withConnection[A](jdbcUrl: String, user: String, password: String)(f: Connection => A): A =
        Loan.withResource(dataSource(jdbcUrl, user, password).getConnection)(f)

    private def dataSource(jdbcUrl: String, user: String, password: String): HikariDataSource = {
        val ds = pools.computeIfAbsent((jdbcUrl, user), _ => build(jdbcUrl, user, password))
        // Secret rotation: if the cached pool was built with a stale password,
        // swap it for a fresh one. In-flight connections on the old pool finish
        // normally; evictConnections + close reaps them as they return.
        if (ds.getPassword != password) {
            pools.synchronized {
                val current = pools.get((jdbcUrl, user))
                if (current != null && current.getPassword != password) {
                    val replacement = build(jdbcUrl, user, password)
                    pools.put((jdbcUrl, user), replacement)
                    current.close()
                    replacement
                } else current
            }
        } else ds
    }

    private def build(jdbcUrl: String, user: String, password: String): HikariDataSource = {
        val cfg = new HikariConfig()
        cfg.setJdbcUrl(jdbcUrl)
        cfg.setUsername(user)
        cfg.setPassword(password)
        cfg.setMaximumPoolSize(5)
        cfg.setMinimumIdle(0)
        cfg.setIdleTimeout(60000)
        // Fail fast when Postgres is unreachable instead of hanging the request:
        // Hikari's own acquire timeout plus pgjdbc's TCP connect + login caps.
        cfg.setConnectionTimeout(10000)
        cfg.addDataSourceProperty("connectTimeout", "10")
        cfg.addDataSourceProperty("loginTimeout", "10")
        cfg.setPoolName("pg-" + Integer.toHexString((jdbcUrl + "|" + user).hashCode))
        new HikariDataSource(cfg)
    }
}
