package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
*/

import java.util.UUID

object GuidV5 {
    // The nameUUIDFrom(name: String) method previously lived here. It was a
    // deterministic, SHA-1-based UUID v5 generator — same input string always
    // produced the same UUID. Every caller in the codebase was passing
    // `System.currentTimeMillis().toString`, treating it as a random UUID
    // factory. That was a serious bug: two calls within the same millisecond
    // produced the SAME UUID, which on concurrent loads caused staging file
    // paths to collide and pipelines to read each other's data. All callsites
    // now use `java.util.UUID.randomUUID()`. The method was removed to keep
    // anyone from reaching for the same footgun.
    //
    // isValidUUID remains because it's a real format check, used by the
    // metadata parser to distinguish UUID-shaped tokens from other strings.

    def isValidUUID(uuid: String): Boolean = {
        try {
            UUID.fromString(uuid)
            true
        } catch {
            case _: IllegalArgumentException => false
        }
    }
}
