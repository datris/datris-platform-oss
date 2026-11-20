package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.DatrisEnvironment

/** Test environments carrying the platform's collection names as the server
  * sets them (`<environment>-<name>`, StartupRunner / forEnvironment). */
object PlatformCollectionFixtures {

    def named(e: DatrisEnvironment): DatrisEnvironment = {
        val p = e.environment + "-"
        e.copy(
            pipelineTableName = p + "pipeline",
            archivedMetadataTableName = p + "archived-metadata",
            pipelineStatusTableName = p + "pipeline-status",
            fileNotifierMessageTableName = p + "file-notifier-message",
            dataPullTableName = p + "data-pull",
            tapTableName = p + "tap",
            tapLogTableName = p + "tap-log",
            tapLedgerTableName = p + "tap-ledger",
            tapPromptTableName = p + "tap-prompt",
            userTableName = p + "user",
            userSessionTableName = p + "user-session",
            auditLogTableName = p + "audit-log",
            agentPolicyTableName = p + "agent-policy",
            pendingActionTableName = p + "pending-action",
            incidentTableName = p + "incident"
        )
    }
}
