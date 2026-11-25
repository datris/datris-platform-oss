package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

/** What the tap prompts (script generator, brainstormer, Assistant) tell a
  * model about reading platform data back through `POST /api/v1/query/mongodb`
  * from a tap script. Kept in one place so the three prompts cannot drift from
  * what the route reads and refuses ([[MongoDBQueryUtil]]). General words only:
  * a model copies literal examples. */
object TapPlatformQueryGuidance {

    /** The body fields the MongoDB query route reads. */
    val MongoBodyFields: String = "`filter` (a find filter object; omit it for the whole collection), `database`, `collection`, `limit`"

    /** The bounds on one MongoDB query and what a tap script does about them. */
    val MongoReadRules: String =
        "A single MongoDB query is stopped after 30 seconds, or 300 seconds with `\"limit\": -1`, and has a result-size ceiling " +
            "(32 MB of JSON text by default): a larger result is refused, never cut short. To read more than that, split the read " +
            "into disjoint ranges with `filter` (for example ranges of `_id` or of a timestamp field) and query each range; each " +
            "range read may keep `\"limit\": -1`, since the range bounds it. `filter` is parsed as MongoDB extended JSON, so a range " +
            "bound on an ObjectId is written `{\"$oid\": \"<24 hex characters>\"}` and on a date `{\"$date\": \"<ISO-8601 timestamp>\"}`. " +
            "A refused MongoDB read answers HTTP 400 with `{\"error\": ...}` and no `results`; a failed PostgreSQL query answers an " +
            "error status too. The script must check the status (`resp.raise_for_status()`, or fail on a non-200) before reading " +
            "`results`, so a refused read fails the run instead of loading nothing."
}
