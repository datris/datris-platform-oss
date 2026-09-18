package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import org.apache.hadoop.fs.Path
import org.slf4j.{Logger, LoggerFactory}

/** Hourly retention sweep for scratch results. Deletes every file under
  * `<rootUri>/_scratch/` whose modification time is older than
  * `now - settings.retentionHours` (retentionHours <= 0: cutoff = now). Never
  * touches anything outside `_scratch/`, never deletes directories or the
  * `_scratch/` root itself (MinIO leaves a 0-byte folder marker there), and
  * returns the number of objects deleted — 0 when the root is absent.
  */
object ScratchSweeper {
    private val logger: Logger = LoggerFactory.getLogger(ScratchSweeper.getClass)

    private val Marker = "/" + ScratchPaths.Root

    def sweep(settings: ScratchLoader.Settings): Int = {
        val rootUri = settings.rootUri.stripSuffix("/")
        val root = new Path(rootUri + "/" + ScratchPaths.Root.stripSuffix("/"))
        val fs = root.getFileSystem(ScratchLoader.hadoopConfiguration(settings.rootUri))
        if (!fs.exists(root)) {
            logger.info("Scratch sweep: no " + ScratchPaths.Root + " root at " + rootUri + "; nothing to do")
            return 0
        }

        val retentionMillis = math.max(0, settings.retentionHours).toLong * 60L * 60L * 1000L
        val cutoff = System.currentTimeMillis() - retentionMillis

        var deleted = 0
        val files = fs.listFiles(root, true)
        while (files.hasNext) {
            val status = files.next()
            val path = status.getPath
            if (status.isFile && status.getModificationTime < cutoff) {
                // Belt and braces: never delete anything whose path is not under _scratch/.
                if (!path.toString.contains(Marker))
                    throw new IllegalStateException("refusing to delete '" + path + "': not under " + ScratchPaths.Root)
                if (fs.delete(path, false)) {
                    deleted += 1
                    logger.info("Scratch sweep: deleted expired result " + path)
                }
            }
        }
        logger.info("Scratch sweep: deleted " + deleted + " expired object(s) under " + root)
        deleted
    }
}
