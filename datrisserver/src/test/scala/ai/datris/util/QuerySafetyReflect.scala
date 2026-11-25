package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import java.lang.reflect.{InvocationTargetException, Method}

/** Calls members of the query utilities by name, so the specs for
  * plans/stories/query-sqlserver-snowflake-databricks-side-effects.md compile
  * before the members the story adds exist (`SqlServerQueryUtil.singleStatementProblem`,
  * `SnowflakeQueryUtil.validateQuery` / `DatabricksQueryUtil.validateQuery`
  * becoming `private[util]`, the per-store `BLOCKED_FUNCTIONS`, the timeout
  * choice for `limit < 0`). A missing member fails the test that needs it with
  * a message naming it, instead of breaking the whole test build. Works for
  * Scala `private` and `private[util]` members of an `object` alike. */
private[util] object QuerySafetyReflect {

    private def matches(m: Method, name: String): Boolean =
        m.getName == name || m.getName.endsWith("$$" + name)

    def methods(target: AnyRef, name: String): Seq[Method] =
        target.getClass.getDeclaredMethods.toSeq.filter(matches(_, name))

    /** The single method `name` on `target` whose parameter types are `params`. */
    def method(target: AnyRef, name: String, params: Class[_]*): Method = {
        val found = methods(target, name).filter(_.getParameterTypes.toSeq == params.toSeq)
        if (found.isEmpty)
            throw new AssertionError(
                target.getClass.getName + " has no member " + name + params.map(_.getSimpleName).mkString("(", ", ", ")") +
                    " (added by the story)"
            )
        val m = found.head
        m.setAccessible(true)
        m
    }

    /** Invoke `m` on `target`, rethrowing what the method itself threw. */
    def invoke(target: AnyRef, m: Method, args: AnyRef*): AnyRef =
        try m.invoke(target, args: _*)
        catch { case e: InvocationTargetException => throw e.getCause }

    def call(target: AnyRef, name: String, arg: String): AnyRef =
        invoke(target, method(target, name, classOf[String]), arg)

    /** The value of a no-argument member (a Scala `val` compiles to an
      * accessor method), as a list of strings whatever its collection type. */
    def stringList(target: AnyRef, name: String): Seq[String] = {
        val v = invoke(target, method(target, name))
        v match {
            case it: scala.collection.Iterable[_] => it.toSeq.map(String.valueOf)
            case c: java.util.Collection[_] => c.toArray.toSeq.map(String.valueOf)
            case a: Array[_] => a.toSeq.map(String.valueOf)
            case other => throw new AssertionError(name + " is not a collection of names: " + other)
        }
    }

    /** The Scala `object` named `name` (fully qualified), for an object a
      * story adds (plans/stories/query-validation-bounded-time.md adds
      * `QueryTextLimit`). A missing object fails the test that needs it. */
    def scalaObject(name: String): AnyRef =
        try Class.forName(name + "$").getField("MODULE$").get(null)
        catch {
            case _: ClassNotFoundException | _: NoSuchFieldException =>
                throw new AssertionError("no object " + name + " (added by the story)")
        }

    /** The timeout (seconds) the object chooses for a query `limit`. Looks for
      * the Postgres-shaped seam `timeoutSeconds(limit: Int): Int`, else any
      * `(Int): Int` member whose name mentions a timeout. */
    def timeoutFor(target: AnyRef, limit: Int): Int = {
        val candidates = target.getClass.getDeclaredMethods.toSeq.filter { m =>
            m.getParameterTypes.toSeq == Seq(classOf[Int]) && m.getReturnType == classOf[Int] &&
            m.getName.toLowerCase(java.util.Locale.ROOT).contains("timeout")
        }
        val m = candidates.find(matches(_, "timeoutSeconds")).orElse(candidates.headOption).getOrElse(
            throw new AssertionError(
                target.getClass.getName + " has no timeout choice for a limit: expected timeoutSeconds(limit: Int): Int as in PostgresQueryUtil"
            )
        )
        m.setAccessible(true)
        invoke(target, m, Integer.valueOf(limit)).asInstanceOf[Integer].intValue
    }
}
