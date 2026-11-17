package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{DatrisEnvironment, PipelineConfig}

import scala.collection.JavaConverters._

/** Stored records (run status, run lineage, provenance, activity signals,
  * vector chunks' `source_pipeline`) keep the pipeline name they were written
  * under. After a rename the pipeline carries its old names in
  * `previousNames`; these helpers map a record's name back to the live
  * pipeline it belongs to, so the users who can see the pipeline keep seeing
  * its history and those who cannot do not.
  *
  * The rule, the same everywhere: a name a current pipeline carries belongs
  * to that pipeline (a pipeline later created under an old name wins the
  * records filed under it); otherwise a name in exactly one pipeline's
  * `previousNames` belongs to that pipeline; a name several pipelines list,
  * or none, belongs to nobody (hidden from restricted sessions). */
object PipelineAliases {

    /** `c`'s previous names, nulls and blanks dropped. */
    def previousNamesOf(c: PipelineConfig): Seq[String] =
        if (c == null || c.previousNames == null) Nil
        else c.previousNames.asScala.toSeq.filter(n => n != null && n.nonEmpty)

    /** previous name -> the single pipeline that lists it, for names no current pipeline carries. */
    def aliasOwners(all: Seq[PipelineConfig]): Map[String, PipelineConfig] = {
        val configs = all.filter(_ != null)
        val current = configs.map(_.name).toSet
        configs
            .flatMap(c => previousNamesOf(c).distinct.map(_ -> c))
            .filterNot { case (n, _) => current.contains(n) }
            .groupBy(_._1)
            .collect { case (n, Seq((_, c))) => n -> c }
    }

    /** The live pipeline a stored record named `name` belongs to, or null. */
    def ownerOf(name: String, all: Seq[PipelineConfig]): PipelineConfig =
        if (name == null) null
        else all.find(c => c != null && c.name == name).getOrElse(aliasOwners(all).getOrElse(name, null))

    /** Every record name that belongs to one of `visible` (their names and
      * the previous names that resolve to them under the rule above). */
    def recordNames(visible: Seq[PipelineConfig], all: Seq[PipelineConfig]): Set[String] = {
        val shown = visible.filter(_ != null).map(_.name).toSet
        shown ++ aliasOwners(all).collect { case (n, c) if shown.contains(c.name) => n }
    }

    /** `previousNames` after renaming `c` to `newName`: its list plus its
      * current name, without `newName`, de-duplicated, order kept. */
    def afterRename(c: PipelineConfig, newName: String): java.util.List[String] = {
        val names = (previousNamesOf(c) :+ c.name).filter(_ != newName).distinct
        new java.util.ArrayList[String](names.asJava)
    }

    /** `candidate` with the stored config's `previousNames` (none for a new
      * pipeline). The list is server-maintained: a value in a save body or a
      * restored snapshot is never taken, or a caller could claim another
      * pipeline's old name and read the records filed under it. */
    def carriedOver(candidate: PipelineConfig, stored: PipelineConfig): PipelineConfig =
        if (candidate == null) null
        else candidate.copy(previousNames = if (stored == null) null else stored.previousNames)

    /** I/O: the config of `name`, else (for a name no config carries) the
      * pipeline that lists it as a previous name; null when neither. */
    def readOwner(name: String): PipelineConfig = {
        val table = DatrisEnvironment.current.pipelineTableName
        val direct = PipelineConfigIO.read(table, name)
        if (direct != null) direct
        else ownerOf(name, PipelineConfigIO.readAll(table).filter(_ != null))
    }
}
