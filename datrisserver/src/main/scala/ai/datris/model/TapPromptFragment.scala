package ai.datris.model

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
*/

case class TapPromptFragment(
    key: String,
    aliases: java.util.List[String] = null,
    content: String = "",
    enabled: Boolean = true,
    createdAt: String = null,
    updatedAt: String = null
)
