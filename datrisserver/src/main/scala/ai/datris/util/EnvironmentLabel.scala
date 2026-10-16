package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

/** The operator-chosen environment label (`DATRIS_ENV`), e.g. `dev`, `qa`,
  * `production`. Any value is a label shown in the UI header, on
  * `/api/v1/version` (`environmentLabel`), in catalog archives and in
  * `datris doctor`; only `production` also hardens (see [[PostgresTlsGuard]]).
  *
  * The `datris.env` system property is read first: env vars are fixed at
  * process start, so tests set the property twin.
  */
object EnvironmentLabel {

    /** Trimmed, non-empty label, or None when unset or blank. */
    def current: Option[String] =
        sys.props.get("datris.env").orElse(sys.env.get("DATRIS_ENV"))
            .map(_.trim)
            .filter(_.nonEmpty)

    def isProduction: Boolean = current.exists(_.equalsIgnoreCase("production"))
}
