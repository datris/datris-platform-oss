package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.api.TapReferenceRules
import ai.datris.model.{CodeRepoConfig, TapConfig}

import scala.collection.JavaConverters._
import scala.util.Try

/** What a tap's own script file is, and what a script store may delete or
  * replace for a tap (plans/stories/tap-script-store-writes-and-deletes-in-scope.md).
  * Pure: every store read comes through the arguments, made at most once per
  * call and only when needed. These rules hold for every caller,
  * administrators included: they say which file a store may touch, not who
  * may ask.
  *
  * The own script file of the tap named N:
  *  - built-in: the key `tap-scripts/` + N + `_` + eight lowercase
  *    hexadecimal characters + `.py`, exactly as the built-in store writes it;
  *  - code repository: `<p>N.py` once normalised
  *    ([[TapReferenceRules.normalizedRef]]), for `p` the configured path
  *    prefix or one of the repository's recorded earlier prefixes, and not
  *    in the pipelines folder of any of them. */
object TapScriptOwnership {

    /** The one warning a delete gives for a script file it leaves in place, whatever the cause. */
    val KeptWarning: String =
        "The tap's script file was left in place because it is not a file this tap alone owns; remove it by hand if it is no longer wanted."

    /** Store reads the rules need. `allTaps`: every stored tap; `repoConfig`:
      * the enabled code repository configuration (None when none is enabled).
      * Either may throw. */
    case class Lookups(allTaps: () => Seq[TapConfig], repoConfig: () => Option[CodeRepoConfig])

    private val HexId = java.util.regex.Pattern.compile("[0-9a-f]{8}\\.py")

    private def nonEmpty(s: String): Option[String] = Option(s).filter(_.nonEmpty)

    /** A tap name the stores accept: no empty, `.` or `..` segment once `.py` is appended. */
    private def storableName(name: String): Boolean = name != null && name.nonEmpty && ScriptPaths.tapNameRefusal(name).isEmpty

    /** True when `key` is exactly `tap-scripts/` + `tapName` + `_` + eight
      * hexadecimal characters + `.py`. */
    def ownBuiltIn(tapName: String, key: String): Boolean =
        storableName(tapName) && key != null && ScriptPaths.plainSegments(key) && {
            val start = MinioCodeStore.TapPrefix + tapName + "_"
            key.startsWith(start) && HexId.matcher(key.substring(start.length)).matches()
        }

    /** The folder form of a prefix: "" or the normalised prefix plus `/`. */
    private def folderOf(prefix: String): String = {
        val n = TapReferenceRules.normalizedRef(Option(prefix).getOrElse(""))
        if (n.isEmpty) "" else n + "/"
    }

    /** True when `path`, normalised, is `<p><tapName>.py` for one of
      * `prefixes`, and is not in the pipelines folder of any of them. */
    def ownRepo(tapName: String, path: String, prefixes: Seq[String]): Boolean =
        storableName(tapName) && path != null && path.nonEmpty && {
            val p = TapReferenceRules.normalizedRef(path)
            val folders = Option(prefixes).getOrElse(Nil).map(folderOf).distinct
            val inPipelines = folders.exists(f => p.startsWith(f + ScriptPaths.PipelinesFolder))
            !inPipelines && folders.exists(f => p == f + tapName + ".py")
        }

    /** The configured `pathPrefix`, then every recorded earlier prefix (a null list is none). */
    def prefixesOf(cfg: CodeRepoConfig): Seq[String] =
        if (cfg == null) Nil
        else Option(cfg.pathPrefix).getOrElse("") +: earlierOf(cfg)

    /** The recorded earlier prefixes of `cfg` (nulls dropped; a null list is none). */
    def earlierOf(cfg: CodeRepoConfig): Seq[String] =
        Option(cfg).flatMap(c => Option(c.earlierPathPrefixes)).map(_.asScala.toList.filter(_ != null)).getOrElse(Nil)

    sealed trait DeletePlan
    case object Delete extends DeletePlan
    case object Keep extends DeletePlan

    /** True when the stored tap `t` currently references `file` (normalised),
      * on the store `repo` names. */
    private def references(t: TapConfig, file: String, repo: Boolean): Boolean =
        t != null && {
            val ref = if (repo) t.scriptRepoPath else t.scriptPath
            nonEmpty(ref).exists(r => TapReferenceRules.normalizedRef(r) == file)
        }

    /** Delete only when `ref` (ref.name is the tap) is that tap's own file and
      * no other stored tap's current reference is the same file once
      * normalised. The repository configuration is read only for a
      * repository file; the tap listing only when the own-file test passed.
      * A listing or configuration read that fails is Keep, and so is a
      * repository file while no repository is enabled. */
    def deletePlan(ref: ScriptRef, lookups: Lookups): DeletePlan =
        if (ref == null) Keep
        else {
            val repo = ref.storage == "github"
            val path = if (repo) ref.scriptRepoPath else ref.scriptPath
            val own =
                if (path == null || path.isEmpty) false
                else if (repo)
                    Try(lookups.repoConfig()).toOption.flatten match {
                        case Some(cfg) => ownRepo(ref.name, path, prefixesOf(cfg))
                        case None => false
                    }
                else ownBuiltIn(ref.name, path)
            if (!own) Keep
            else {
                val file = TapReferenceRules.normalizedRef(path)
                Try(Option(lookups.allTaps()).getOrElse(Nil)) match {
                    case scala.util.Success(taps) =>
                        if (taps.exists(t => t != null && t.name != ref.name && references(t, file, repo))) Keep else Delete
                    case scala.util.Failure(_) => Keep
                }
            }
        }

    /** True when `targetPath` is, normalised, the current repository script
      * of a stored tap of another name. */
    def uploadTargetTaken(tapName: String, targetPath: String, allTaps: Seq[TapConfig]): Boolean =
        targetPath != null && targetPath.nonEmpty && {
            val file = TapReferenceRules.normalizedRef(targetPath)
            Option(allTaps).getOrElse(Nil).exists(t => t != null && t.name != tapName && t.scriptStorage == "github" && references(t, file, repo = true))
        }

    /** The built-in script objects of the tap `tapName` among `paths` that may
      * be deleted: its own files ([[ownBuiltIn]]) that no stored tap
      * currently references. `allTaps` is read once, only when some path is
      * the tap's own; a listing that cannot be read deletes nothing. */
    def deletableBuiltIn(tapName: String, paths: Seq[String], allTaps: () => Seq[TapConfig]): Seq[String] = {
        val own = Option(paths).getOrElse(Nil).filter(p => ownBuiltIn(tapName, p)).distinct
        if (own.isEmpty) Nil
        else
            Try(Option(allTaps()).getOrElse(Nil)) match {
                case scala.util.Success(taps) =>
                    val inUse = taps.filter(_ != null).flatMap(t => nonEmpty(t.scriptPath)).map(TapReferenceRules.normalizedRef).toSet
                    own.filterNot(p => inUse.contains(TapReferenceRules.normalizedRef(p)))
                case scala.util.Failure(_) => Nil
            }
    }
}
