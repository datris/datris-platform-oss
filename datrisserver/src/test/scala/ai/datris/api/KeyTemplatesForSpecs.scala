package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

/** Test-only bridge to the `private[api]` capability templates the Keys UI
  * offers (`KeysAPIController.Templates`), so specs outside `ai.datris.api`
  * (CallerVisibilitySpec) read the real lists instead of a copy that could
  * drift. */
object KeyTemplatesForSpecs {

    /** The capability strings of the template called `name`. */
    def capabilities(name: String): Seq[String] =
        KeysAPIController.Templates.find(_._1 == name).map(_._3).getOrElse(throw new NoSuchElementException("no template " + name))

    /** Every template name, in the order the Keys UI lists them. */
    def names: Seq[String] = KeysAPIController.Templates.map(_._1)
}
