package ai.datris.model

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
*/


import scala.collection.mutable.ListBuffer

object GlobalJobContext {
    //private object Locker
    private val jobContexts: ListBuffer[JobContext] = new ListBuffer()

    def getAll: ListBuffer[JobContext] = jobContexts

    //def getNumberOfRunningJobs: Int = jobContexts.count(_.state == RUNNING)

    def addJobContext(jobContext: JobContext): Unit = {
        synchronized {
            jobContexts += jobContext
        }
    }

    private def deleteJobContext(jobContext: JobContext): Unit = {
        synchronized {
            val job = jobContexts.find(_.pipelineToken.compareTo(jobContext.pipelineToken) == 0)
                .getOrElse(throw new DatrisException("Internal error - could not find the JobContext for pipeline token: " + jobContext.pipelineToken))
            jobContexts -= job
        }
    }

    def replaceJobContext(jobContext: JobContext): Unit = {
        synchronized {
            deleteJobContext(jobContext)
            addJobContext(jobContext)
        }
    }

    def findJobContext(pipelineToken: String): JobContext = {
        jobContexts.find(jobContext => jobContext.pipelineToken.compareTo(pipelineToken) == 0).orNull
    }
}
