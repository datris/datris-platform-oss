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
  * Every store read comes through the arguments, made at most once per
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
  *    in the pipelines folder of any of them.
  *
  * A built-in key of the store's form written under another name (a tap
  * renamed in the wizard before its first save, or a script generated
  * before the tap had its name) belongs to no stored tap: such a key may be
  * deleted for the tap that references it when no stored tap has the name
  * in the key and no other stored tap's current script is that key
  * ([[builtInKeyName]]).
  *
  * The only side effect: one WARN line when a file is kept because the tap
  * listing could not be read. */
object TapScriptOwnership {

    private val logger = org.slf4j.LoggerFactory.getLogger("ai.datris.util.TapScriptOwnership")

    /** One WARN line for a script file kept because the tap listing could
      * not be read: names only the tap whose script it is, and no error text. */
    private def keptUnlisted(tapName: String, cause: Throwable): Unit =
        logger.warn(
            "Script of tap '" + LogSafe.text(tapName) + "' left in place: the stored taps could not be listed to check that no other tap uses it (" +
                Option(cause).map(_.getClass.getSimpleName).getOrElse("unknown") + ")"
        )

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

    /** The tap name N of a built-in key of the store's form
      * `tap-scripts/` + N + `_` + eight hexadecimal characters + `.py`; None
      * for any other key. [[ownBuiltIn]] of (N, key) holds exactly for that N. */
    def builtInKeyName(key: String): Option[String] =
        if (key == null || !ScriptPaths.plainSegments(key) || !key.startsWith(MinioCodeStore.TapPrefix)) None
        else {
            val rest = key.substring(MinioCodeStore.TapPrefix.length)
            val cut = rest.length - 12 // "_" + 8 hex + ".py"
            if (cut < 1 || rest.charAt(cut) != '_' || !HexId.matcher(rest.substring(cut + 1)).matches()) None
            else Some(rest.substring(0, cut)).filter(n => storableName(n) && ownBuiltIn(n, key))
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

    /** Delete only when no other stored tap's current reference is the same
      * file once normalised and the file is one this tap may remove: its own
      * file, or a built-in key of the store's form whose name no stored tap
      * has ([[builtInKeyName]]). The repository configuration is read only
      * for a repository file; the tap listing only when the file passed the
      * first test. A listing or configuration read that fails is Keep (a
      * listing failure with one WARN line), and so is a repository file while
      * no repository is enabled. */
    def deletePlan(ref: ScriptRef, lookups: Lookups): DeletePlan =
        if (ref == null) Keep
        else {
            val repo = ref.storage == "github"
            val path = if (repo) ref.scriptRepoPath else ref.scriptPath
            val keyName = if (repo || path == null) None else builtInKeyName(path)
            val candidate =
                if (path == null || path.isEmpty) false
                else if (repo)
                    Try(lookups.repoConfig()).toOption.flatten match {
                        case Some(cfg) => ownRepo(ref.name, path, prefixesOf(cfg))
                        case None => false
                    }
                else keyName.isDefined
            if (!candidate) Keep
            else {
                val file = TapReferenceRules.normalizedRef(path)
                Try(Option(lookups.allTaps()).getOrElse(Nil)) match {
                    case scala.util.Success(taps) =>
                        val others = taps.filter(t => t != null && t.name != ref.name)
                        val nameTaken = keyName.exists(n => n != ref.name && others.exists(_.name == n))
                        if (nameTaken || others.exists(t => references(t, file, repo))) Keep else Delete
                    case scala.util.Failure(e) =>
                        keptUnlisted(ref.name, e)
                        Keep
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

    /** The built-in script objects among `paths` (scripts the tap
      * `tapName`'s versions recorded) that may be deleted: keys no stored tap
      * currently references that are the tap's own ([[ownBuiltIn]]) or of
      * the store's form under a name no stored tap has ([[builtInKeyName]]).
      * `allTaps` is read once, only when some path is of the store's form; a
      * listing that cannot be read deletes nothing (one WARN line). */
    def deletableBuiltIn(tapName: String, paths: Seq[String], allTaps: () => Seq[TapConfig]): Seq[String] = {
        val candidates = Option(paths).getOrElse(Nil).filter(p => builtInKeyName(p).isDefined).distinct
        if (candidates.isEmpty) Nil
        else
            Try(Option(allTaps()).getOrElse(Nil)) match {
                case scala.util.Success(taps) =>
                    val live = taps.filter(_ != null)
                    val inUse = live.flatMap(t => nonEmpty(t.scriptPath)).map(TapReferenceRules.normalizedRef).toSet
                    val names = live.map(_.name).toSet
                    candidates.filter { p =>
                        !inUse.contains(TapReferenceRules.normalizedRef(p)) &&
                        builtInKeyName(p).exists(n => n == tapName || !names.contains(n))
                    }
                case scala.util.Failure(e) =>
                    keptUnlisted(tapName, e)
                    Nil
            }
    }
}
