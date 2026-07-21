package ai.datris.model

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

object TenantContext {
    private val threadLocal = new ThreadLocal[DatrisEnvironment]()

    def set(env: DatrisEnvironment): Unit = threadLocal.set(env)
    def get(): Option[DatrisEnvironment] = Option(threadLocal.get())
    def clear(): Unit = threadLocal.remove()
}
