package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

/** An iterator that holds a resource (a staged-file reader) until it is either
  * exhausted or explicitly closed. `close()` is idempotent and a closed
  * iterator reports `hasNext == false`. Readers of a staged payload should
  * always close in a `finally` — see [[CloseableIterator.using]].
  */
trait CloseableIterator[A] extends Iterator[A] with AutoCloseable

object CloseableIterator {

    def empty[A]: CloseableIterator[A] = new CloseableIterator[A] {
        override def hasNext: Boolean = false
        override def next(): A = throw new NoSuchElementException("empty iterator")
        override def close(): Unit = ()
    }

    /** Wrap a plain iterator (usually a `map`/`filter` over another
      * CloseableIterator) so the underlying resource is still closeable. */
    def apply[A](it: Iterator[A], onClose: () => Unit): CloseableIterator[A] = new CloseableIterator[A] {
        private var closed = false
        override def hasNext: Boolean = !closed && it.hasNext
        override def next(): A = {
            if (closed) throw new NoSuchElementException("iterator closed")
            it.next()
        }
        override def close(): Unit =
            if (!closed) {
                closed = true
                onClose()
            }
    }

    /** Run `body` over the iterator and close it afterwards, whatever happens. */
    def using[A, T](it: CloseableIterator[A])(body: Iterator[A] => T): T =
        try body(it)
        finally it.close()
}
