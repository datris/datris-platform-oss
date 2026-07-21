package ai.datris.model

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import scala.collection.mutable.ListBuffer

object GlobalJobContext {
    // private object Locker
    private val jobContexts: ListBuffer[JobContext] = new ListBuffer()

    def getAll: ListBuffer[JobContext] = jobContexts

    // def getNumberOfRunningJobs: Int = jobContexts.count(_.state == RUNNING)

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

    def killJob(pipelineToken: String): Unit = {
        synchronized {
            val jobContext = findJobContext(pipelineToken)
            if (jobContext == null)
                throw new DatrisException("Job not found for pipeline token: " + pipelineToken)
            if (jobContext.state != PROCESSING)
                throw new DatrisException("Job is not running (state: " + jobContext.state + ") for pipeline token: " + pipelineToken)
            if (jobContext.thread == null || !jobContext.thread.isAlive)
                throw new DatrisException("Job thread is not alive for pipeline token: " + pipelineToken)

            // Interrupt the thread
            jobContext.thread.interrupt()

            // Log the cancellation
            jobContext.statusUtil.error("end", "Job cancelled by user")

            // Update state
            deleteJobContext(jobContext)
            addJobContext(jobContext.copy(state = CANCELLED))
        }
    }
}
