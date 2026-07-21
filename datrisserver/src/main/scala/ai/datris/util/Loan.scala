package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

/** Loan pattern for AutoCloseable resources. scala.util.Using is 2.13+, so this
  * is the 2.12 stand-in: close is guaranteed, close failures never mask the
  * primary result or exception.
  */
object Loan {
    def withResource[R <: AutoCloseable, A](resource: R)(f: R => A): A =
        try f(resource)
        finally if (resource != null)
                try resource.close()
                catch { case _: Exception => () }

    def withResources[A](resources: AutoCloseable*)(f: => A): A =
        try f
        finally resources.reverse.foreach(r =>
                if (r != null)
                    try r.close()
                    catch { case _: Exception => () }
            )
}
