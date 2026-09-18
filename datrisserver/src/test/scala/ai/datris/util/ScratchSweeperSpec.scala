package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import org.scalatest.funsuite.AnyFunSuite

import java.nio.charset.StandardCharsets
import java.nio.file.attribute.FileTime
import java.nio.file.{Files, Path => NioPath}
import java.time.{Duration, Instant}

/** Hourly retention sweep (plans/stories/scratch-result-endpoint-retention.md).
  *
  *  {{{
  *  object ScratchSweeper {
  *      // Deletes every file under <rootUri>/_scratch/ whose modification time is
  *      // older than now - settings.retentionHours (retentionHours <= 0: cutoff =
  *      // now). Never touches anything outside _scratch/, never deletes
  *      // directories or the _scratch/ root itself, and returns the number of
  *      // objects deleted (0 when the root is absent).
  *      def sweep(settings: ScratchLoader.Settings): Int
  *  }
  *  }}}
  *
  *  `settings.rootUri` is a `file://` directory here (the `ScratchLoaderSpec`
  *  seam); mtimes are set on the fixtures directly.
  */
class ScratchSweeperSpec extends AnyFunSuite {

    private def tmpRoot(): NioPath = Files.createTempDirectory("scratch-sweeper-spec")

    private def settings(root: NioPath, retentionHours: Int): ScratchLoader.Settings =
        ScratchLoader.Settings(root.toUri.toString.stripSuffix("/"), 200, retentionHours)

    private def scratchRoot(root: NioPath): NioPath = root.resolve(ScratchPaths.Root.stripSuffix("/"))

    /** Writes a small file at `relative` under `root` and stamps its mtime `age` ago. */
    private def fixture(root: NioPath, relative: String, age: Duration, content: String = "{\"id\":1}\n"): NioPath = {
        val file = root.resolve(relative)
        Files.createDirectories(file.getParent)
        Files.write(file, content.getBytes(StandardCharsets.UTF_8))
        Files.setLastModifiedTime(file, FileTime.from(Instant.now().minus(age)))
        file
    }

    test("files older than retentionHours are deleted, newer ones kept") {
        val root = tmpRoot()
        val old = fixture(root, ScratchPaths.key("orders", "tok-old"), Duration.ofHours(48))
        val fresh = fixture(root, ScratchPaths.key("orders", "tok-fresh"), Duration.ofHours(1))
        val otherPipe = fixture(root, ScratchPaths.key("users", "tok-recent"), Duration.ofHours(23))

        val deleted = ScratchSweeper.sweep(settings(root, retentionHours = 24))

        assert(deleted == 1, s"exactly the 48h-old file is past a 24h retention, got $deleted")
        assert(!Files.exists(old), "the expired object must be gone")
        assert(Files.exists(fresh), "a 1h-old object survives")
        assert(Files.exists(otherPipe), "a 23h-old object survives a 24h retention")
        assert(Files.exists(scratchRoot(root)), "the _scratch/ root survives")
    }

    test("retentionHours=0 deletes everything under _scratch/") {
        val root = tmpRoot()
        val a = fixture(root, ScratchPaths.key("orders", "a"), Duration.ofSeconds(5))
        val b = fixture(root, ScratchPaths.key("orders", "b"), Duration.ofSeconds(5))
        val c = fixture(root, ScratchPaths.key("users", "c"), Duration.ofSeconds(5))

        val deleted = ScratchSweeper.sweep(settings(root, retentionHours = 0))

        assert(deleted == 3, s"retention 0 expires immediately; expected 3, got $deleted")
        assert(!Files.exists(a) && !Files.exists(b) && !Files.exists(c))
        assert(Files.exists(scratchRoot(root)), "the _scratch/ root survives an expire-everything sweep")
        // A negative value behaves the same as 0
        val d = fixture(root, ScratchPaths.key("orders", "d"), Duration.ofSeconds(5))
        assert(ScratchSweeper.sweep(settings(root, retentionHours = -1)) == 1)
        assert(!Files.exists(d))
    }

    test("a file outside _scratch/ is never touched") {
        val root = tmpRoot()
        val landed = fixture(root, "orders/part-0000.parquet", Duration.ofDays(30))
        val sibling = fixture(root, "_scratch_archive/old.jsonl", Duration.ofDays(30))
        val topLevel = fixture(root, "stray.jsonl", Duration.ofDays(30))
        val expired = fixture(root, ScratchPaths.key("orders", "tok-old"), Duration.ofDays(30))

        val deleted = ScratchSweeper.sweep(settings(root, retentionHours = 0))

        assert(deleted == 1, s"only the object under _scratch/ counts, got $deleted")
        assert(!Files.exists(expired))
        assert(Files.exists(landed), "real destination data next to _scratch/ must survive")
        assert(Files.exists(sibling), "a prefix that merely starts with _scratch must survive")
        assert(Files.exists(topLevel), "a top-level object in the bucket must survive")
    }

    test("the 0-byte folder marker survives") {
        // MinIO leaves a 0-byte `_scratch/` folder marker (seen in the story-1
        // E2E). On file:// the analogue is the _scratch/ directory itself: the
        // sweep must delete expired result objects, leave the root (and the
        // per-pipeline directory) in place even when they are older than the
        // cutoff, and keep reporting 0 on an already-clean root rather than
        // trying (and failing) to delete the marker every hour.
        val root = tmpRoot()
        val expired = fixture(root, ScratchPaths.key("orders", "tok-old"), Duration.ofDays(30))
        val pipeDir = scratchRoot(root).resolve("orders")
        val thirtyDaysAgo = FileTime.from(Instant.now().minus(Duration.ofDays(30)))
        Files.setLastModifiedTime(pipeDir, thirtyDaysAgo)
        Files.setLastModifiedTime(scratchRoot(root), thirtyDaysAgo)

        assert(ScratchSweeper.sweep(settings(root, retentionHours = 0)) == 1, "only the result object counts as deleted")
        assert(!Files.exists(expired))
        assert(Files.exists(scratchRoot(root)), "the _scratch/ root (folder marker) survives")

        // Second sweep on the now-empty root: nothing to delete, no error, marker still there
        assert(ScratchSweeper.sweep(settings(root, retentionHours = 0)) == 0)
        assert(Files.exists(scratchRoot(root)))
    }

    test("a missing _scratch/ root is a no-op returning 0") {
        val root = tmpRoot()
        // Something else in the bucket, but no _scratch/ at all
        val landed = fixture(root, "orders/part-0000.parquet", Duration.ofDays(30))

        assert(ScratchSweeper.sweep(settings(root, retentionHours = 0)) == 0)
        assert(Files.exists(landed))
        assert(!Files.exists(scratchRoot(root)), "the sweep must not create the root either")

        // A root that does not exist at all
        val ghost = root.resolve("no-such-bucket")
        assert(ScratchSweeper.sweep(settings(ghost, retentionHours = 0)) == 0)
        assert(!Files.exists(ghost))
    }
}
