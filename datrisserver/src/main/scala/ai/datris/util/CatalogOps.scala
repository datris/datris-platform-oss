package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{Capability, PipelineConfig, TapConfig}
import com.google.gson.JsonParser

import scala.collection.JavaConverters._
import scala.util.matching.Regex

/** Pure planning and execution helpers for catalog rename/delete
  * (`CatalogAPIController`). A catalog is not an entity: it is the nullable
  * `catalog` string on every tap and pipeline, plus an optional hidden
  * placeholder tap `__catalog__<name>` that keeps an empty catalog visible.
  * Nothing here touches Mongo; the controller supplies the data and the
  * write function, so the logic is testable in isolation. */
object CatalogOps {

    object Placeholder {
        val prefix: String = "__catalog__"

        def name(catalog: String): String = prefix + catalog

        def is(itemName: String): Boolean = itemName != null && itemName.startsWith(prefix)

        /** Does placeholder tap `t` keep `catalog` alive? The UI groups a
          * placeholder by its `catalog` field, so that field wins; the name
          * suffix is only a fallback when the field is empty. Legacy
          * placeholders can carry a name that differs from their catalog. */
        def belongsTo(t: TapConfig, catalog: String): Boolean =
            t != null && is(t.name) && catalog != null && {
                if (t.catalog != null && t.catalog.nonEmpty) t.catalog == catalog
                else t.name.substring(prefix.length) == catalog
            }

        /** Every placeholder of `catalog` (there may be more than one). */
        def of(taps: Seq[TapConfig], catalog: String): Seq[TapConfig] = taps.filter(belongsTo(_, catalog))

        /** Name for a new placeholder of `catalog`: `__catalog__<catalog>`,
          * or with a numeric suffix when that tap name is already taken (by a
          * legacy placeholder of another catalog). */
        def freshName(catalog: String, takenTapNames: Set[String]): String = {
            val base = name(catalog)
            if (!takenTapNames.contains(base)) base
            else Iterator.from(2).map(i => base + "-" + i).find(n => !takenTapNames.contains(n)).get
        }
    }

    /** The UI's label rule (`ui/src/app/shared/sanitize.ts` sanitizeLabel). */
    val LabelRule: Regex = "^[a-z0-9_-]+$".r

    val Uncataloged: String = "Uncataloged"

    /** Uncataloged is a client-side pseudo-catalog (items with no catalog);
      * it cannot be renamed or deleted. Blank names are refused too. */
    def isReserved(name: String): Boolean =
        name == null || name.trim.isEmpty || name.trim.equalsIgnoreCase(Uncataloged)

    def isValidLabel(name: String): Boolean =
        name != null && LabelRule.pattern.matcher(name).matches()

    case class Members(taps: Seq[TapConfig], pipelines: Seq[PipelineConfig]) {
        def names: Seq[String] = taps.map(_.name) ++ pipelines.map(_.name)
        def isEmpty: Boolean = taps.isEmpty && pipelines.isEmpty
    }

    /** Taps and pipelines whose `catalog` equals `catalog` exactly (the UI
      * groups by exact value). Placeholders are never members. */
    def members(taps: Seq[TapConfig], pipelines: Seq[PipelineConfig], catalog: String): Members =
        Members(
            taps = taps.filter(t => t != null && t.catalog == catalog && !Placeholder.is(t.name)),
            pipelines = pipelines.filter(p => p != null && p.catalog == catalog)
        )

    /** Member names that would collide with an item already in the target
      * catalog — tap-vs-tap, pipeline-vs-pipeline and cross-type, as the UI's
      * per-item move check does. The target's placeholder never clashes. */
    def clashes(members: Members, targetTaps: Seq[TapConfig], targetPipelines: Seq[PipelineConfig]): Seq[String] = {
        val taken: Set[String] =
            targetTaps.filter(t => t != null && !Placeholder.is(t.name)).map(_.name).toSet ++
                targetPipelines.filter(_ != null).map(_.name).toSet
        members.names.filter(taken.contains).distinct
    }

    /** Labels of non-revoked API keys holding any capability scoped
      * `catalog=<old>`. `metadata` is the `{env}/api-key-metadata` map
      * (label -> metadata JSON). Malformed entries are skipped. */
    def affectedKeys(metadata: Map[String, String], old: String): Seq[String] =
        metadata.toSeq.sortBy(_._1).flatMap { case (label, json) =>
            val hit =
                try {
                    val meta = JsonParser.parseString(json).getAsJsonObject
                    val revoked = meta.has("revoked") && !meta.get("revoked").isJsonNull && meta.get("revoked").getAsBoolean
                    !revoked && meta.has("capabilities") && meta.get("capabilities").isJsonArray &&
                    meta.getAsJsonArray("capabilities").asScala.exists { el =>
                        try Capability.parse(el.getAsString).scope.get("catalog").contains(old)
                        catch { case _: Exception => false }
                    }
                } catch { case _: Exception => false }
            if (hit) Some(label) else None
        }.distinct

    /** Capability scope context for one member: its catalog and owner, as
      * `CatalogFindAPIController` builds it, so keys scoped `catalog=<name>`
      * as well as `owner=self` keys are evaluated correctly. Blank values
      * are omitted (an absent key never satisfies a scoped grant). */
    def scopeContext(catalog: String, owner: String): Map[String, String] = {
        var ctx = Map.empty[String, String]
        if (catalog != null && catalog.nonEmpty) ctx += ("catalog" -> catalog)
        if (owner != null && owner.nonEmpty) ctx += ("owner" -> owner)
        ctx
    }

    /** What to do with the `__catalog__` placeholder(s) after the member phase. */
    sealed trait PlaceholderAction
    object PlaceholderAction {

        /** Members moved (or failed for non-scope reasons): proceed. */
        case object Proceed extends PlaceholderAction

        /** Placeholder-only catalog: the placeholder itself must pass the
          * caller's scope check before it is touched. */
        case object CheckPlaceholderScope extends PlaceholderAction

        /** A member was refused by the caller's scope: leave placeholders
          * alone so an owner-scoped key cannot rename/delete a catalog it
          * does not own. */
        case object Skip extends PlaceholderAction
    }

    /** `anyOk` is false when the catalog had members and none of them was
      * moved (e.g. all were moved away concurrently): placeholders are then
      * left alone so no empty target catalog is conjured. */
    def placeholderAction(membersEmpty: Boolean, denied: Seq[String], anyOk: Boolean = true): PlaceholderAction =
        if (membersEmpty) PlaceholderAction.CheckPlaceholderScope
        else if (denied.nonEmpty || !anyOk) PlaceholderAction.Skip
        else PlaceholderAction.Proceed

    /** Guard for the per-member write: the member is re-read just before
      * writing, and must still be in the catalog being operated on. A
      * concurrent rename or `set_catalog` may have moved it; it must not be
      * moved again. Throws so `execute` lists it under `failed`. */
    def requireInCatalog(itemName: String, itemCatalog: String, catalog: String): Unit =
        if (itemCatalog != catalog)
            throw new ai.datris.model.DatrisException(
                "no longer in catalog " + catalog +
                    (if (itemCatalog == null || itemCatalog.isEmpty) " (now Uncataloged)" else " (now in " + itemCatalog + ")")
            )

    /** One process-wide lock for every catalog operation (rename, detach,
      * cascade). Held from the member snapshot through the placeholder step
      * so a concurrent operation sees the finished result (and gets 404/207)
      * instead of re-moving the same items. Catalog operations are rare admin
      * actions on a single server instance; a single lock avoids the lock
      * ordering a per-name scheme would need (rename touches two names). */
    private val catalogLock = new java.util.concurrent.locks.ReentrantLock(true)

    def withCatalogLock[T](body: => T): T = {
        catalogLock.lock()
        try body
        finally catalogLock.unlock()
    }

    case class Result(ok: Seq[String], failed: Seq[(String, String)])

    /** 403 body for a scope denial, in the same shape as CapabilityInterceptor
      * and SecretsAPIController, so clients branching on `errorKind` see a
      * permission boundary rather than a generic error. */
    def capabilityDeniedBody(message: String): String = {
        val o = new com.google.gson.JsonObject
        o.addProperty("error", "capability denied")
        o.addProperty("errorKind", "capability_denied")
        o.addProperty("message", Option(message).getOrElse(""))
        o.toString
    }

    /** Report `names` as failed without attempting them (e.g. cascade skips
      * pipeline deletes when a tap delete in the same catalog failed, so no
      * surviving tap is left pointing at a deleted pipeline). */
    def skipped(names: Seq[String], reason: String): Result = Result(Nil, names.map(_ -> reason))

    /** Apply `write` to every name, continuing past failures. Not
      * transactional: the caller reports `failed` back to the user. */
    def execute(names: Seq[String])(write: String => Unit): Result = {
        val ok = Seq.newBuilder[String]
        val failed = Seq.newBuilder[(String, String)]
        names.foreach { n =>
            try {
                write(n)
                ok += n
            } catch {
                case e: Exception =>
                    failed += (n -> Option(e.getMessage).getOrElse(e.getClass.getSimpleName))
            }
        }
        Result(ok.result(), failed.result())
    }
}
