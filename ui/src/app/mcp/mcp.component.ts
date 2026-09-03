import { Component, OnInit, ViewChild, ElementRef } from '@angular/core';
import { McpService } from '../mcp.service';

export interface McpToolParam {
  name: string;
  type: string;
  description: string;
  required: boolean;
  defaultValue?: any;
  inputType: 'text' | 'number' | 'textarea' | 'checkbox' | 'select';
  optionsLoader?: string;  // method name to load select options
}

export interface McpTool {
  name: string;
  description: string;
  category: string;
  parameters: McpToolParam[];
  playgroundEnabled: boolean;
}

@Component({
    selector: 'app-mcp',
    templateUrl: './mcp.component.html',
    styleUrls: ['./mcp.component.css'],
    standalone: false
})
export class McpComponent implements OnInit {
  @ViewChild('playgroundSection') playgroundSection!: ElementRef;

  // Section 1: Overview
  serverVersion = '';
  healthServices: { name: string; status: string; message?: string }[] = [];
  healthLoading = false;
  healthError = '';

  // Section 2: Tool Catalog
  categories: string[] = [];
  toolsByCategory: Record<string, McpTool[]> = {};

  // Section 3: Config Generator
  // The connect-your-agent snippet uses the `npx mcp-remote` stdio bridge,
  // which Claude Desktop / Claude Code / Cursor all support uniformly. The
  // bridge connects to the bundled mcp-server's SSE endpoint and transparently
  // reconnects on restarts. `--transport sse-only` pins the protocol so the
  // bridge doesn't try its streamable-HTTP fallback. The default local server
  // runs without auth, so the API key field is optional — when blank, the
  // snippet omits the `--header` flag entirely. Hosted / trial / dedicated
  // deployments require a key, which mcp-remote forwards as `x-api-key`.
  selectedAgent = 'claude-desktop';
  mcpServerUrl = 'http://localhost:3000/sse';
  agentApiKey = localStorage.getItem('datris-api-key') || '';
  copySuccess = false;

  // Section 4: Tool Playground
  selectedToolName = '';
  selectedTool: McpTool | null = null;
  playgroundParams: Record<string, any> = {};
  playgroundLoading = false;
  playgroundError = '';
  playgroundResult = '';
  paramOptions: Record<string, string[]> = {};  // options for select params

  // Full tool catalog.
  // KEEP IN SYNC with mcp-server/server.py's _base_tools() and the server's
  // auth/MCPToolRoutes.scala — one entry here per MCP tool (72 as of v1.26).
  toolCatalog: McpTool[] = [
    // --- System ---
    {
      name: 'get_version',
      description: 'Get the Datris server version.',
      category: 'System',
      parameters: [],
      playgroundEnabled: true
    },
    {
      name: 'check_service_health',
      description: 'Check which backend services are up, down, or not configured. Returns status of PostgreSQL, MongoDB, MinIO, ActiveMQ, Kafka, and vector databases.',
      category: 'System',
      parameters: [],
      playgroundEnabled: true
    },
    {
      name: 'wait_seconds',
      description: 'Sleep 1–120 seconds, then return. Use to pace polling of long-running pipeline work without burning tool calls. Typical pattern: run_tap → get_pipeline_status → wait_seconds(5) → get_pipeline_status → wait_seconds(10) → ... with exponential backoff capped at 60s (120s only if progress is genuinely glacial). Reset to a short wait whenever a poll shows new jobs flipped to a terminal state.',
      category: 'System',
      parameters: [
        { name: 'seconds', type: 'integer', description: 'How long to sleep, in seconds. Range: 1–120. Values outside this range are clamped.', required: true, inputType: 'number' }
      ],
      playgroundEnabled: true
    },
    // --- Taps ---
    {
      name: 'list_tap_secrets',
      description: 'List the names of existing tap secrets (tagged _type=tap). Always call this before create_tap_secret — if a suitable secret already exists, reuse it by passing its name as secret_name to create_tap.',
      category: 'Taps',
      parameters: [],
      playgroundEnabled: true
    },
    {
      name: 'get_tap_secret_fields',
      description: 'Return the field NAMES (keys only — never values) of an existing tap secret. Use after list_tap_secrets to confirm a candidate secret has the keys your tap script needs.',
      category: 'Taps',
      parameters: [
        { name: 'name', type: 'string', description: 'Tap secret name (from list_tap_secrets)', required: true, inputType: 'text' }
      ],
      playgroundEnabled: true
    },
    {
      name: 'create_tap_secret',
      description: 'Create or update a tap secret. Fields are injected as env vars into the tap script. Call list_tap_secrets first to check for an existing match. Agents can only overwrite secrets tagged _type=tap.',
      category: 'Taps',
      parameters: [
        { name: 'name', type: 'string', description: 'Secret name (lowercase, hyphenated, e.g. stripe-api-key). Reserved AI-slot names are blocked.', required: true, inputType: 'text' },
        { name: 'fields', type: 'object', description: 'Key-value object. Each key becomes an env var name, e.g. {"apiKey": "sk_..."}', required: true, inputType: 'textarea' },
        { name: 'overwrite', type: 'boolean', description: 'Replace existing secret with the same name (default false). Only _type=tap secrets can be overwritten.', required: false, inputType: 'text' }
      ],
      playgroundEnabled: true
    },
    {
      name: 'delete_tap_secret',
      description: 'Delete a tap secret. Only secrets tagged _type=tap (agent-created) can be deleted via this tool.',
      category: 'Taps',
      parameters: [
        { name: 'name', type: 'string', description: 'Name of the tap secret to delete', required: true, inputType: 'text' }
      ],
      playgroundEnabled: true
    },
    {
      name: 'create_tap',
      description: 'Create a tap from an instruction (AI generates script), a user-provided script, or config only. Agent-only in this playground — it is a multi-step flow (codegen, script store, config save); use the Create Tap wizard on the Ingestion tab instead.',
      category: 'Taps',
      parameters: [
        { name: 'name', type: 'string', description: 'Unique tap name', required: true, inputType: 'text' },
        { name: 'instruction', type: 'string', description: 'Plain-English instruction for AI script generation', required: false, inputType: 'textarea' },
        { name: 'script', type: 'string', description: 'Python source code with a fetch() function', required: false, inputType: 'textarea' },
        { name: 'target_pipeline', type: 'string', description: 'Pipeline to push fetched data into', required: false, inputType: 'text' },
        { name: 'cron_expression', type: 'string', description: 'Quartz CRON schedule (e.g., 0 0 * * * ?)', required: false, inputType: 'text' },
        { name: 'secret_name', type: 'string', description: 'Vault secret name for credentials', required: false, inputType: 'text' },
        { name: 'tap_type', type: 'string', description: 'structured (default) or document (for PDFs/Word/HTML into vector-store pipelines)', required: false, inputType: 'text' },
        { name: 'packages', type: 'array', description: 'Optional list of pip packages the tap script imports beyond the base set. Auto-detected from the script when omitted.', required: false, inputType: 'text' }
      ],
      playgroundEnabled: false
    },
    {
      name: 'list_taps',
      description: 'List all taps with status, target pipeline, schedule, and last run info.',
      category: 'Taps',
      parameters: [],
      playgroundEnabled: true
    },
    {
      name: 'get_tap',
      description: 'Get the full details of a single tap including its Python script.',
      category: 'Taps',
      parameters: [
        { name: 'name', type: 'string', description: 'Name of the tap', required: true, inputType: 'text' }
      ],
      playgroundEnabled: true
    },
    {
      name: 'run_tap',
      description: 'Execute a tap and push fetched data to the target pipeline. Response carries `recordCount`, `publisherToken`, `pipelineTokens`, `persisted`, `persistedReason`, and the script\'s `logs`. Records themselves are not returned — use `test_tap` to preview what a script produces. Call `test_tap` first before the first run of a newly-created or just-updated script. Pass optional `params` to drive a single run with caller-supplied values (date windows, id lists, page cursors, etc.) — each key/value becomes a `DATRIS_TAP_PARAM_<key>` env var the script can read.',
      category: 'Taps',
      parameters: [
        { name: 'name', type: 'string', description: 'Name of the tap to run', required: true, inputType: 'text' },
        { name: 'params', type: 'object', description: 'Optional per-run values injected as DATRIS_TAP_PARAM_<key> env vars. Keys must match [A-Za-z_][A-Za-z0-9_]*. Strings pass through; numbers/booleans get stringified; nested objects/arrays are JSON-encoded.', required: false, inputType: 'textarea' }
      ],
      playgroundEnabled: true
    },
    {
      name: 'get_pipeline_status',
      description: 'Poll pipeline ingestion status after run_tap. Pass publisher_token (covers every job the run submitted — recommended) or pipeline_token (one job). Returns {rollup, events} — poll rollup.allDone, then read rollup.status (success / warning / error) and per-job lastError.',
      category: 'Taps',
      parameters: [
        { name: 'publisher_token', type: 'string', description: 'UUID from run_tap response — returns rollup + events for every job the run submitted', required: false, inputType: 'text' },
        { name: 'pipeline_token', type: 'string', description: 'UUID for a single ingestion job', required: false, inputType: 'text' }
      ],
      playgroundEnabled: true
    },
    {
      name: 'test_tap',
      description: 'Test-run a tap without pushing data to the pipeline.',
      category: 'Taps',
      parameters: [
        { name: 'name', type: 'string', description: 'Name of the tap to test', required: true, inputType: 'text' }
      ],
      playgroundEnabled: true
    },
    {
      name: 'update_tap',
      description: 'Update a tap\'s CONFIG (schedule, target pipeline, enabled flag, description) without touching the script. To change the SCRIPT itself, call create_tap again with the same name — it upserts and replaces the script.',
      category: 'Taps',
      parameters: [
        { name: 'name', type: 'string', description: 'Name of the tap to update', required: true, inputType: 'text' },
        { name: 'enabled', type: 'boolean', description: 'Enable or disable the tap', required: false, inputType: 'text' },
        { name: 'cron_expression', type: 'string', description: 'New CRON schedule (Quartz syntax: seconds minutes hours dom month dow). Examples: "0 0 * * * ?" hourly, "0 30 5 ? * MON-FRI" weekdays 5:30am.', required: false, inputType: 'text' },
        { name: 'target_pipeline', type: 'string', description: 'New target pipeline', required: false, inputType: 'text' },
        { name: 'description', type: 'string', description: 'New description', required: false, inputType: 'text' }
      ],
      playgroundEnabled: true
    },
    {
      name: 'get_tap_logs',
      description: 'Get run history for a tap (last 50 entries). Each entry that submitted records includes its publisherToken — pivot to get_pipeline_status to verify a run actually landed in the destination.',
      category: 'Taps',
      parameters: [
        { name: 'name', type: 'string', description: 'Name of the tap', required: true, inputType: 'text' }
      ],
      playgroundEnabled: true
    },
    {
      name: 'get_tap_ledger',
      description: 'Document taps only: return the ledger of discovered documents (URI, filename, status, hashes, timestamps). Pass clear_uri to force-reprocess one file, or clear_all=true to wipe the ledger for a full re-scan.',
      category: 'Taps',
      parameters: [
        { name: 'name', type: 'string', description: 'Name of the document tap', required: true, inputType: 'text' },
        { name: 'clear_uri', type: 'string', description: 'Optional — URI whose ledger entry to delete, forcing re-processing on next run', required: false, inputType: 'text' },
        { name: 'clear_all', type: 'boolean', description: 'Optional — if true, wipes the entire ledger for this tap', required: false, inputType: 'text' }
      ],
      playgroundEnabled: true
    },
    {
      name: 'get_tap_state',
      description: 'Read a tap\'s incremental-sync state — the bookmark/cursor its script committed after the last successful run (injected into the next run as DATRIS_TAP_STATE). Returns {tap, state, updatedAt, updatedBy}; state is null for non-incremental taps or before the first successful run.',
      category: 'Taps',
      parameters: [
        { name: 'name', type: 'string', description: 'Name of the tap', required: true, inputType: 'text' }
      ],
      playgroundEnabled: true
    },
    {
      name: 'set_tap_state',
      description: 'Overwrite or reset a tap\'s incremental-sync state. Pass state (a JSON object matching what the tap\'s script reads from DATRIS_TAP_STATE) to set the bookmark — e.g. rewind a cursor so a window is re-fetched. Pass reset=true to delete the state entirely: the next run does a full first-run fetch.',
      category: 'Taps',
      parameters: [
        { name: 'name', type: 'string', description: 'Name of the tap', required: true, inputType: 'text' },
        { name: 'state', type: 'object', description: 'The state object the next run should receive via DATRIS_TAP_STATE (shape is defined by the tap\'s own script)', required: false, inputType: 'textarea' },
        { name: 'reset', type: 'boolean', description: 'If true, deletes the stored state — next run is a full first-run fetch. Ignores state.', required: false, inputType: 'text' }
      ],
      playgroundEnabled: true
    },
    {
      name: 'delete_tap',
      description: 'Delete a tap and its stored script.',
      category: 'Taps',
      parameters: [
        { name: 'name', type: 'string', description: 'Name of the tap to delete', required: true, inputType: 'text' }
      ],
      playgroundEnabled: true
    },
    {
      name: 'list_tap_versions',
      description: 'List a tap\'s definition-change history (newest first): version, createdAt, createdBy, changeNote. The platform snapshots a tap\'s config + script on every create/update. Read-only. NOTE: an empty result means the tap hasn\'t been edited since versioning was enabled — not that it has no version. The current version number is the `version` field from list_taps / get_tap (≥ 1 for every tap).',
      category: 'Taps',
      parameters: [
        { name: 'name', type: 'string', description: 'Name of the tap', required: true, inputType: 'text' }
      ],
      playgroundEnabled: true
    },
    {
      name: 'get_tap_version',
      description: 'View one historical snapshot of a tap\'s definition: the full config and the pinned Python script as they were at that version. Read-only; does not change the live tap.',
      category: 'Taps',
      parameters: [
        { name: 'name', type: 'string', description: 'Name of the tap', required: true, inputType: 'text' },
        { name: 'version', type: 'integer', description: 'Version number to view (from list_tap_versions)', required: true, inputType: 'number' }
      ],
      playgroundEnabled: true
    },
    {
      name: 'diff_tap_versions',
      description: 'Compare two versions of a tap\'s definition. Returns a server-computed field-by-field config diff and a line-level script diff. Read-only. `version` is the selected/newer snapshot; `against` is the baseline to compare it to.',
      category: 'Taps',
      parameters: [
        { name: 'name', type: 'string', description: 'Name of the tap', required: true, inputType: 'text' },
        { name: 'version', type: 'integer', description: 'Selected version', required: true, inputType: 'number' },
        { name: 'against', type: 'integer', description: 'Baseline version to compare against', required: true, inputType: 'number' }
      ],
      playgroundEnabled: true
    },
    {
      name: 'restore_tap_version',
      description: 'Roll a tap back (or forward) to a prior definition version. APPEND-ONLY and SIDE-EFFECTING: reads the chosen snapshot and writes it as a NEW latest version (config + that version\'s script), preserving full history — nothing is overwritten. "Rolling forward" is the same call with a higher version number. Only call when the user explicitly asks to restore this specific tap to a specific version. Does NOT run the tap — report the new version and stop.',
      category: 'Taps',
      parameters: [
        { name: 'name', type: 'string', description: 'Name of the tap', required: true, inputType: 'text' },
        { name: 'version', type: 'integer', description: 'Version to restore (becomes a new latest version)', required: true, inputType: 'number' }
      ],
      playgroundEnabled: true
    },
    // --- Pipeline Management ---
    {
      name: 'list_pipelines',
      description: 'List all registered pipeline configurations.',
      category: 'Pipeline Management',
      parameters: [],
      playgroundEnabled: true
    },
    {
      name: 'get_pipeline',
      description: 'Get a specific pipeline configuration by name.',
      category: 'Pipeline Management',
      parameters: [
        { name: 'pipeline', type: 'string', description: 'Pipeline name', required: true, inputType: 'text' }
      ],
      playgroundEnabled: true
    },
    {
      name: 'create_pipeline',
      description: 'Create OR UPDATE a pipeline. Schema is auto-detected from a sample file for structured destinations. Upserts by name — calling again with the same name replaces the config in place without dropping the destination data, so you can change knobs (keyFields, truncate, codegen_rule, objectstore settings) without delete-then-recreate. Supports three destination categories: structured (postgres, mongodb, snowflake, databricks), objectstore (Parquet/ORC files in MinIO or AWS S3), and vector (pgvector, qdrant, weaviate, milvus, chroma). Snowflake and Databricks additionally require credentialsSecret, warehouse, and database.',
      category: 'Pipeline Management',
      parameters: [
        { name: 'content', type: 'string', description: 'Base64-encoded sample data. Required for structured destinations AND objectstore; omit for vector destinations.', required: false, inputType: 'textarea' },
        { name: 'filename', type: 'string', description: 'Filename (e.g., data.csv). Required for structured destinations and objectstore.', required: false, inputType: 'text' },
        { name: 'pipeline', type: 'string', description: 'Pipeline name', required: true, inputType: 'text' },
        { name: 'destination', type: 'string', description: 'Destination: postgres, mongodb, snowflake, databricks, objectstore, qdrant, weaviate, milvus, chroma, pgvector', required: false, inputType: 'text' },
        { name: 'table', type: 'string', description: 'Table/collection name (default: pipeline name). Ignored for objectstore.', required: false, inputType: 'text' },
        { name: 'database', type: 'string', description: 'Database name (default: datris). Ignored for objectstore. REQUIRED for snowflake (the Snowflake database) and databricks (the Unity Catalog name).', required: false, inputType: 'text' },
        { name: 'schema', type: 'string', description: 'Destination schema. Applies to snowflake (default: PUBLIC) and databricks (default: default).', required: false, inputType: 'text' },
        { name: 'warehouse', type: 'string', description: 'Snowflake virtual warehouse NAME, or Databricks SQL warehouse ID (the trailing segment of the HTTP path in Connection details). REQUIRED for those destinations.', required: false, inputType: 'text' },
        { name: 'role', type: 'string', description: 'Optional Snowflake role to assume. Snowflake only.', required: false, inputType: 'text' },
        { name: 'delimiter', type: 'string', description: 'CSV delimiter (default: comma)', required: false, inputType: 'text' },
        { name: 'header', type: 'boolean', description: 'Whether CSV has a header row (default: true)', required: false, inputType: 'text' },
        { name: 'keyFields', type: 'array', description: 'Optional natural-key columns used to dedupe / upsert rows on every run (postgres, mongodb, snowflake, databricks).', required: false, inputType: 'text' },
        { name: 'truncate', type: 'boolean', description: 'Optional. Wipe destination table/collection before each run (postgres, mongodb, snowflake, databricks — atomic replace on Databricks). Default false.', required: false, inputType: 'text' },
        { name: 'bucket', type: 'string', description: 'Object-store bucket. Optional for MinIO (default: {environment}-data). REQUIRED when provider=s3.', required: false, inputType: 'text' },
        { name: 'prefix', type: 'string', description: 'Object-store key prefix under the bucket (e.g. "events/orders"). REQUIRED for destination=objectstore.', required: false, inputType: 'text' },
        { name: 'fileFormat', type: 'string', description: 'Object-store file format: parquet (default) or orc.', required: false, inputType: 'text' },
        { name: 'partitionBy', type: 'array', description: 'Optional partition columns for objectstore writes. Field names must be in the destination schema.', required: false, inputType: 'text' },
        { name: 'writeMode', type: 'string', description: 'Object-store write mode: append (default), overwrite, ignore, errorifexists.', required: false, inputType: 'text' },
        { name: 'deleteBeforeWrite', type: 'boolean', description: 'Object-store only. When true, delete existing objects under the prefix before writing. Default false.', required: false, inputType: 'text' },
        { name: 'provider', type: 'string', description: 'Object-store provider: minio (default, built-in) or s3 (AWS S3). When s3, bucket and credentialsSecret are required.', required: false, inputType: 'text' },
        { name: 'endpoint', type: 'string', description: 'S3 endpoint URL override (objectstore + provider=s3 only). Must use https://. Leave unset for the AWS regional default.', required: false, inputType: 'text' },
        { name: 'credentialsSecret', type: 'string', description: 'PLATFORM secret holding destination credentials. For objectstore + provider=s3: accessKey, secretKey, region (optional sessionToken); required unless Datris runs on an EC2 instance role. For snowflake: account, user, privateKey (or password); always required. For databricks: host, plus clientId/clientSecret or token; always required. Discover via list_platform_secrets.', required: false, inputType: 'text' },
        { name: 'codegen_rule', type: 'string', description: 'Optional plain-English data quality validation rule. Datris generates a Python validation script from it and runs it against ingested data. Only add when the user explicitly asks for validation.', required: false, inputType: 'textarea' },
        { name: 'codegen_transform', type: 'string', description: 'Optional plain-English transformation instruction. Datris generates a Python script and runs it against ingested data. Only add when the user explicitly asks for a transformation.', required: false, inputType: 'textarea' },
        { name: 'catalog', type: 'string', description: 'Optional catalog label to group this pipeline with related ones. Omit by default.', required: false, inputType: 'text' }
      ],
      playgroundEnabled: true
    },
    {
      name: 'set_catalog',
      description: 'Set or clear the catalog grouping label on an existing pipeline or tap. ONLY call when the user has explicitly asked to organize work under a named catalog — do not call proactively. Pass exactly one of pipeline or tap. Omit catalog (or pass empty) to clear.',
      category: 'Pipeline Management',
      parameters: [
        { name: 'pipeline', type: 'string', description: 'Pipeline name to update. Mutually exclusive with tap.', required: false, inputType: 'text' },
        { name: 'tap', type: 'string', description: 'Tap name to update. Mutually exclusive with pipeline.', required: false, inputType: 'text' },
        { name: 'catalog', type: 'string', description: 'Catalog label. Empty string clears the label.', required: false, inputType: 'text' }
      ],
      playgroundEnabled: true
    },
    {
      name: 'delete_pipeline',
      description: 'DESTRUCTIVE. Deletes BOTH the pipeline config AND the destination data (rows, collection contents, vector store entries). Also wipes document-tap ledgers and staged files for any tap targeting this pipeline. Config-only delete (orphaning the data) is not supported — the platform forces data delete to come along to prevent ghost state. Pass keep_config=true to reset: wipes data, keeps config.',
      category: 'Pipeline Management',
      parameters: [
        { name: 'pipeline', type: 'string', description: 'Pipeline name to delete', required: true, inputType: 'text' },
        { name: 'keep_config', type: 'boolean', description: 'Optional. If true, delete only the destination data and keep the pipeline config (clean reset). Default false (full delete).', required: false, inputType: 'text' }
      ],
      playgroundEnabled: true
    },
    {
      name: 'upload_data',
      description: 'Upload data to a pipeline for processing. Send file content as base64.',
      category: 'Pipeline Management',
      parameters: [
        { name: 'content', type: 'string', description: 'Base64-encoded file content', required: true, inputType: 'textarea' },
        { name: 'filename', type: 'string', description: 'Filename (e.g., data.csv)', required: true, inputType: 'text' },
        { name: 'pipeline', type: 'string', description: 'Pipeline name', required: true, inputType: 'text' }
      ],
      playgroundEnabled: true
    },
    {
      name: 'get_job_status',
      description: 'Get job status. Pass pipeline_token for a {rollup, events} response — poll rollup.allDone, then read rollup.status (success / warning / error). Pass pipeline_name for a paginated summary across recent jobs.',
      category: 'Pipeline Management',
      parameters: [
        { name: 'pipeline_token', type: 'string', description: 'Pipeline token from upload_data — returns rollup + events for a single job', required: false, inputType: 'text' },
        { name: 'pipeline_name', type: 'string', description: 'Pipeline name — returns a paginated summary array of recent jobs', required: false, inputType: 'text' },
        { name: 'page', type: 'integer', description: 'Page number (default: 1)', required: false, inputType: 'number' }
      ],
      playgroundEnabled: true
    },
    {
      name: 'kill_job',
      description: 'Kill a running pipeline job by its pipeline token.',
      category: 'Pipeline Management',
      parameters: [
        { name: 'pipeline_token', type: 'string', description: 'Pipeline token of the running job', required: true, inputType: 'text' }
      ],
      playgroundEnabled: true
    },
    {
      name: 'get_dest_types',
      description: 'Propose real destination column types for a pipeline whose columns landed as text. Types are inferred from the data already loaded, with sample values per column and the offending value named when a column must stay text. Postgres, Snowflake, and Databricks destinations only.',
      category: 'Pipeline Management',
      parameters: [
        { name: 'pipeline', type: 'string', description: 'Pipeline name', required: true, inputType: 'text' }
      ],
      playgroundEnabled: true
    },
    {
      name: 'apply_dest_types',
      description: 'Apply destination column types to an all-string pipeline. REQUIRES explicit user approval first. Landed data is migrated before the config changes; any value that will not cast fails the whole apply with the column named and nothing changed. `fields` must list EVERY destination column with its intended type.',
      category: 'Pipeline Management',
      parameters: [
        { name: 'pipeline', type: 'string', description: 'Pipeline name', required: true, inputType: 'text' },
        { name: 'fields', type: 'array', description: 'Every destination column as {"name": ..., "type": ...}. Types: string, boolean, int, bigint, float, double, date, timestamp.', required: true, inputType: 'textarea' }
      ],
      playgroundEnabled: false
    },
    {
      name: 'profile_data',
      description: 'Send data and use AI to generate a comprehensive data profile: summary statistics per column, data quality issues detected, and suggested validation rules. Use the suggested aiRule when building a pipeline\'s dataQuality section.',
      category: 'Pipeline Management',
      parameters: [
        { name: 'content', type: 'string', description: 'Base64-encoded file content', required: true, inputType: 'textarea' },
        { name: 'filename', type: 'string', description: 'Filename (e.g., sample.csv)', required: true, inputType: 'text' },
        { name: 'delimiter', type: 'string', description: 'CSV delimiter (default: comma)', required: false, inputType: 'text' },
        { name: 'header', type: 'boolean', description: 'Whether CSV has a header row (default: true)', required: false, inputType: 'text' },
        { name: 'sample_size', type: 'integer', description: 'Number of rows to sample for profiling (default: 200)', required: false, inputType: 'number' }
      ],
      playgroundEnabled: true
    },
    {
      name: 'list_pipeline_versions',
      description: 'List a pipeline\'s definition-change history (newest first): version, createdAt, createdBy, changeNote. The platform snapshots a pipeline\'s full config on every create/update. Read-only. NOTE: an empty result means the pipeline hasn\'t been edited since versioning was enabled — not that it has no version. The current version number is the `version` field from list_pipelines / get_pipeline (≥ 1 for every pipeline).',
      category: 'Pipeline Management',
      parameters: [
        { name: 'name', type: 'string', description: 'Name of the pipeline', required: true, inputType: 'text' }
      ],
      playgroundEnabled: true
    },
    {
      name: 'get_pipeline_version',
      description: 'View one historical snapshot of a pipeline\'s definition: the full config as it was at that version. Read-only; does not change the live pipeline.',
      category: 'Pipeline Management',
      parameters: [
        { name: 'name', type: 'string', description: 'Name of the pipeline', required: true, inputType: 'text' },
        { name: 'version', type: 'integer', description: 'Version number to view (from list_pipeline_versions)', required: true, inputType: 'number' }
      ],
      playgroundEnabled: true
    },
    {
      name: 'diff_pipeline_versions',
      description: 'Compare two versions of a pipeline\'s definition. Returns a server-computed field-by-field config diff. Read-only. `version` is the selected/newer snapshot; `against` is the baseline.',
      category: 'Pipeline Management',
      parameters: [
        { name: 'name', type: 'string', description: 'Name of the pipeline', required: true, inputType: 'text' },
        { name: 'version', type: 'integer', description: 'Selected version', required: true, inputType: 'number' },
        { name: 'against', type: 'integer', description: 'Baseline version to compare against', required: true, inputType: 'number' }
      ],
      playgroundEnabled: true
    },
    {
      name: 'restore_pipeline_version',
      description: 'Roll a pipeline back (or forward) to a prior definition version. APPEND-ONLY and SIDE-EFFECTING: reads the chosen snapshot and writes it as a NEW latest version, preserving full history. "Rolling forward" is the same call with a higher version number. Only call when the user explicitly asks to restore this specific pipeline to a specific version. Report the new version and stop.',
      category: 'Pipeline Management',
      parameters: [
        { name: 'name', type: 'string', description: 'Name of the pipeline', required: true, inputType: 'text' },
        { name: 'version', type: 'integer', description: 'Version to restore (becomes a new latest version)', required: true, inputType: 'number' }
      ],
      playgroundEnabled: true
    },
    // --- Vector Search ---
    {
      name: 'search_qdrant',
      description: 'Semantic search across a Qdrant vector database collection.',
      category: 'Vector Search',
      parameters: [
        { name: 'query', type: 'string', description: 'Natural language search query', required: true, inputType: 'text' },
        { name: 'collection', type: 'string', description: 'Qdrant collection name', required: false, inputType: 'select', optionsLoader: 'loadQdrantCollections' },
        { name: 'top_k', type: 'integer', description: 'Number of results (default: 5)', required: false, inputType: 'number' }
      ],
      playgroundEnabled: true
    },
    {
      name: 'search_weaviate',
      description: 'Semantic search across a Weaviate vector database class.',
      category: 'Vector Search',
      parameters: [
        { name: 'query', type: 'string', description: 'Natural language search query', required: true, inputType: 'text' },
        { name: 'class_name', type: 'string', description: 'Weaviate class name', required: false, inputType: 'select', optionsLoader: 'loadWeaviateClasses' },
        { name: 'top_k', type: 'integer', description: 'Number of results (default: 5)', required: false, inputType: 'number' }
      ],
      playgroundEnabled: true
    },
    {
      name: 'search_milvus',
      description: 'Semantic search across a Milvus vector database collection.',
      category: 'Vector Search',
      parameters: [
        { name: 'query', type: 'string', description: 'Natural language search query', required: true, inputType: 'text' },
        { name: 'collection', type: 'string', description: 'Milvus collection name', required: false, inputType: 'select', optionsLoader: 'loadMilvusCollections' },
        { name: 'top_k', type: 'integer', description: 'Number of results (default: 5)', required: false, inputType: 'number' }
      ],
      playgroundEnabled: true
    },
    {
      name: 'search_chroma',
      description: 'Semantic search across a Chroma vector database collection.',
      category: 'Vector Search',
      parameters: [
        { name: 'query', type: 'string', description: 'Natural language search query', required: true, inputType: 'text' },
        { name: 'collection', type: 'string', description: 'Chroma collection name', required: false, inputType: 'select', optionsLoader: 'loadChromaCollections' },
        { name: 'top_k', type: 'integer', description: 'Number of results (default: 5)', required: false, inputType: 'number' }
      ],
      playgroundEnabled: true
    },
    {
      name: 'search_pgvector',
      description: 'Semantic search across a PostgreSQL pgvector table.',
      category: 'Vector Search',
      parameters: [
        { name: 'query', type: 'string', description: 'Natural language search query', required: true, inputType: 'text' },
        { name: 'table', type: 'string', description: 'Table name', required: false, inputType: 'select', optionsLoader: 'loadPgvectorTables' },
        { name: 'schema', type: 'string', description: 'PostgreSQL schema (default: public)', required: false, inputType: 'text' },
        { name: 'top_k', type: 'integer', description: 'Number of results (default: 5)', required: false, inputType: 'number' }
      ],
      playgroundEnabled: true
    },
    // --- Database Query ---
    {
      name: 'query_postgres',
      description: 'Execute a read-only SQL SELECT query against PostgreSQL.',
      category: 'Database Query',
      parameters: [
        { name: 'sql', type: 'string', description: 'SQL SELECT query', required: true, inputType: 'textarea' },
        { name: 'limit', type: 'integer', description: 'Maximum rows (default: 100)', required: false, inputType: 'number' }
      ],
      playgroundEnabled: true
    },
    {
      name: 'query_mongodb',
      description: 'Query a MongoDB collection with optional filter and projection.',
      category: 'Database Query',
      parameters: [
        { name: 'collection', type: 'string', description: 'MongoDB collection name', required: true, inputType: 'text' },
        { name: 'filter', type: 'object', description: 'MongoDB query filter JSON (default: {})', required: false, inputType: 'textarea' },
        { name: 'projection', type: 'object', description: 'Fields to include/exclude JSON', required: false, inputType: 'textarea' },
        { name: 'limit', type: 'integer', description: 'Maximum documents (default: 20)', required: false, inputType: 'number' }
      ],
      playgroundEnabled: true
    },
    {
      name: 'query_objectstore',
      description: 'Read rows from a pipeline\'s objectStore destination (Parquet or ORC files in MinIO or AWS S3). Pass the pipeline name; the server resolves the bucket, prefix, format, and credentials from the pipeline config. Returns up to `limit` rows as JSON. Use when list_pipelines shows objectStore as the destination — query_postgres / query_mongodb / search_* will not work against Parquet/ORC files.',
      category: 'Database Query',
      parameters: [
        { name: 'pipeline', type: 'string', description: 'Pipeline name (from list_pipelines)', required: true, inputType: 'text' },
        { name: 'limit', type: 'integer', description: 'Maximum rows to return (default: 100, hard cap: 10000)', required: false, inputType: 'number' }
      ],
      playgroundEnabled: true
    },
    {
      name: 'query_snowflake',
      description: 'Run a read-only query against the Snowflake account a pipeline loads into. Pass the pipeline name; the server resolves the account, credentials, warehouse, and role from the pipeline\'s config — credentials never leave the server. Only works for pipelines with a Snowflake destination. Allowed statements: SELECT (WITH/CTE), SHOW, DESCRIBE — LIMIT is auto-appended to SELECTs. Omit sql to preview the pipeline\'s destination table. Queries run on the customer\'s warehouse and consume their compute.',
      category: 'Database Query',
      parameters: [
        { name: 'pipeline', type: 'string', description: 'Pipeline name (from list_pipelines). Must have a Snowflake destination.', required: true, inputType: 'text' },
        { name: 'sql', type: 'string', description: 'Read-only statement: SELECT/WITH, SHOW, or DESCRIBE. Omit to preview the destination table.', required: false, inputType: 'textarea' },
        { name: 'limit', type: 'integer', description: 'Maximum rows (default: 100). Pass -1 for unlimited.', required: false, inputType: 'number' }
      ],
      playgroundEnabled: true
    },
    {
      name: 'query_databricks',
      description: 'Run a read-only query against the Databricks workspace a pipeline loads into. Pass the pipeline name; the server resolves the workspace, credentials, SQL warehouse, and catalog from the pipeline\'s config — credentials never leave the server. Only works for pipelines with a Databricks destination. Allowed statements: SELECT (WITH/CTE), SHOW, DESCRIBE — LIMIT is auto-appended to SELECTs. Omit sql to preview the pipeline\'s destination table. If the SQL warehouse is stopped, the first query auto-starts it and may take longer.',
      category: 'Database Query',
      parameters: [
        { name: 'pipeline', type: 'string', description: 'Pipeline name (from list_pipelines). Must have a Databricks destination.', required: true, inputType: 'text' },
        { name: 'sql', type: 'string', description: 'Read-only statement: SELECT/WITH, SHOW, or DESCRIBE. Omit to preview the destination table.', required: false, inputType: 'textarea' },
        { name: 'limit', type: 'integer', description: 'Maximum rows (default: 100). Pass -1 for unlimited.', required: false, inputType: 'number' }
      ],
      playgroundEnabled: true
    },
    {
      name: 'query_natural',
      description: 'Ask a natural-language question about a PostgreSQL table. The AI generates the SQL from the question and table schema, executes it, and returns results.',
      category: 'Database Query',
      parameters: [
        { name: 'question', type: 'string', description: 'Natural language question about the data', required: true, inputType: 'textarea' },
        { name: 'table', type: 'string', description: 'PostgreSQL table name to query', required: true, inputType: 'text' },
        { name: 'schema', type: 'string', description: 'PostgreSQL schema (default: public)', required: false, inputType: 'text' },
        { name: 'database', type: 'string', description: 'Database name (default: datris)', required: false, inputType: 'text' },
        { name: 'limit', type: 'integer', description: 'Max rows (default: 100). Pass -1 for unlimited.', required: false, inputType: 'number' }
      ],
      playgroundEnabled: true
    },
    // --- Metadata Discovery ---
    {
      name: 'list_postgres_databases',
      description: 'List all PostgreSQL databases.',
      category: 'Metadata Discovery',
      parameters: [],
      playgroundEnabled: true
    },
    {
      name: 'list_postgres_schemas',
      description: 'List schemas in a PostgreSQL database.',
      category: 'Metadata Discovery',
      parameters: [
        { name: 'database', type: 'string', description: 'Database name (default: datris)', required: false, inputType: 'text' }
      ],
      playgroundEnabled: true
    },
    {
      name: 'list_postgres_tables',
      description: 'List tables in a PostgreSQL schema. Set vector_only to show only pgvector tables.',
      category: 'Metadata Discovery',
      parameters: [
        { name: 'database', type: 'string', description: 'Database name (default: datris)', required: false, inputType: 'text' },
        { name: 'schema', type: 'string', description: 'Schema name (default: public)', required: false, inputType: 'text' },
        { name: 'vector_only', type: 'boolean', description: 'Only return pgvector tables', required: false, inputType: 'checkbox' }
      ],
      playgroundEnabled: true
    },
    {
      name: 'list_postgres_columns',
      description: 'List columns and types for a PostgreSQL table.',
      category: 'Metadata Discovery',
      parameters: [
        { name: 'table', type: 'string', description: 'Table name', required: true, inputType: 'text' },
        { name: 'database', type: 'string', description: 'Database name (default: datris)', required: false, inputType: 'text' },
        { name: 'schema', type: 'string', description: 'Schema name (default: public)', required: false, inputType: 'text' }
      ],
      playgroundEnabled: true
    },
    {
      name: 'list_mongodb_databases',
      description: 'List all MongoDB databases.',
      category: 'Metadata Discovery',
      parameters: [],
      playgroundEnabled: true
    },
    {
      name: 'list_mongodb_collections',
      description: 'List MongoDB collections, optionally filtered by database.',
      category: 'Metadata Discovery',
      parameters: [
        { name: 'database', type: 'string', description: 'Database name (optional)', required: false, inputType: 'text' }
      ],
      playgroundEnabled: true
    },
    // --- Vector Store Metadata ---
    {
      name: 'list_qdrant_collections',
      description: 'List all collections in the Qdrant vector database.',
      category: 'Metadata Discovery',
      parameters: [],
      playgroundEnabled: true
    },
    {
      name: 'list_weaviate_classes',
      description: 'List all classes in the Weaviate vector database.',
      category: 'Metadata Discovery',
      parameters: [],
      playgroundEnabled: true
    },
    {
      name: 'list_milvus_collections',
      description: 'List all collections in the Milvus vector database.',
      category: 'Metadata Discovery',
      parameters: [],
      playgroundEnabled: true
    },
    {
      name: 'list_chroma_collections',
      description: 'List all collections in the Chroma vector database.',
      category: 'Metadata Discovery',
      parameters: [],
      playgroundEnabled: true
    },
    {
      name: 'list_pgvector_collections',
      description: 'List all pgvector tables (tables with an embedding column) in PostgreSQL.',
      category: 'Metadata Discovery',
      parameters: [],
      playgroundEnabled: true
    },
    // --- AI ---
    {
      name: 'ai_answer',
      description: 'Answer a question using AI based on provided context (RAG).',
      category: 'AI',
      parameters: [
        { name: 'query', type: 'string', description: 'The question to answer', required: true, inputType: 'text' },
        { name: 'context', type: 'string', description: 'Context text (e.g., retrieved document chunks)', required: true, inputType: 'textarea' }
      ],
      playgroundEnabled: true
    },
    // --- Configuration ---
    {
      name: 'upload_config',
      description: 'Upload a JSON Schema or JavaScript config file to the platform. Send content as base64.',
      category: 'Configuration',
      parameters: [
        { name: 'content', type: 'string', description: 'Base64-encoded file content', required: true, inputType: 'textarea' },
        { name: 'filename', type: 'string', description: 'Filename (e.g., schema.json, transform.js)', required: true, inputType: 'text' },
        { name: 'type', type: 'string', description: 'Config type: validation-schema or javascript', required: true, inputType: 'text' }
      ],
      playgroundEnabled: true
    },
    // --- Secrets ---
    {
      name: 'list_platform_secrets',
      description: 'List the names of PLATFORM secrets (all secrets NOT tagged _type=tap — the Platform tab in the Secrets section). These are human-owned credentials for destinations and infrastructure (S3 credentials, database connections, vector-store endpoints). The agent can READ these (discover names + field shape) but cannot create, update, or delete them — that\'s the user\'s responsibility via the Secrets tab. Use whenever a pipeline destination needs a credentialsSecret reference (e.g. objectStore + provider=s3).',
      category: 'Configuration',
      parameters: [],
      playgroundEnabled: true
    },
    {
      name: 'get_platform_secret_fields',
      description: 'Return the field NAMES (keys only — never values) of an existing platform secret. Use after list_platform_secrets to verify a candidate has the keys a destination config requires (e.g. an S3 credentialsSecret must contain accessKey, secretKey, region). Refuses tap-tagged secrets — use get_tap_secret_fields for those.',
      category: 'Configuration',
      parameters: [
        { name: 'name', type: 'string', description: 'Platform secret name (from list_platform_secrets)', required: true, inputType: 'text' }
      ],
      playgroundEnabled: true
    },
    {
      name: 'update_secret',
      description: 'Update an AI provider secret (anthropic, openai, azure, grok, ollama, embedding) to configure API keys for AI features.',
      category: 'Configuration',
      parameters: [
        { name: 'name', type: 'string', description: 'Secret name: anthropic, openai, azure, grok, ollama, or embedding', required: true, inputType: 'text' },
        { name: 'fields', type: 'object', description: 'JSON with endpoint, model, apiKey fields', required: true, inputType: 'textarea' }
      ],
      playgroundEnabled: true
    },
    // --- Agent Policy ---
    {
      name: 'get_agent_policy',
      description: "Read this instance's agent policy: for each action whether an agent may do it on its own (auto), must wait for a person to approve it (approve), or is refused (deny) — plus the recovery agent's mode and limits. Call before a delete or destination-type migration to know whether it will run or queue. When the policy is disabled, every action is auto.",
      category: 'Agent Policy',
      parameters: [],
      playgroundEnabled: true
    },
    {
      name: 'list_pending_approvals',
      description: 'List the actions this agent queued for human approval, newest first — id, action, resource, state (pending | approved | rejected | expired | executed | failed), and the decision once made.',
      category: 'Agent Policy',
      parameters: [
        { name: 'state', type: 'string', description: 'Filter by state (pending, approved, rejected, expired, executed, failed). Omit for all.', required: false, inputType: 'text' },
        { name: 'limit', type: 'integer', description: 'Maximum entries to return (default 100)', required: false, inputType: 'number' }
      ],
      playgroundEnabled: true
    },
    {
      name: 'get_approval',
      description: "Poll one queued approval by the approvalId a mutating tool returned with status pending_approval. Returns pending, executed (with the original call's result), failed, rejected, or expired.",
      category: 'Agent Policy',
      parameters: [
        { name: 'approval_id', type: 'string', description: 'The approvalId returned with pending_approval', required: true, inputType: 'text' }
      ],
      playgroundEnabled: true
    },
    // --- Incidents ---
    {
      name: 'list_incidents',
      description: "List the platform's recovery-agent incidents, newest first — opened by the platform itself for failed, stale, or anomalous data flows, with kind, resource, state, classification, and a step-by-step narrative. Read-only: only the platform opens incidents.",
      category: 'Incidents',
      parameters: [
        { name: 'state', type: 'string', description: 'Filter: open (any active state) or a specific state name. Omit for all.', required: false, inputType: 'text' },
        { name: 'limit', type: 'integer', description: 'Maximum incidents to return (default 50)', required: false, inputType: 'number' }
      ],
      playgroundEnabled: true
    },
    {
      name: 'get_incident',
      description: 'Read one recovery-agent incident by id — its trigger, classification, proposal, step-by-step narrative, approvals it waits on, and outcome.',
      category: 'Incidents',
      parameters: [
        { name: 'incident_id', type: 'string', description: 'The incident id (inc_…)', required: true, inputType: 'text' }
      ],
      playgroundEnabled: true
    },
    {
      name: 'find_data',
      description: 'Find datasets by meaning: ranks the pipelines your key can read against a natural-language query and returns location, freshness, provenance handles, lineage, and a pre-filled howToQuery hint. Discovery only — the query call stays yours to make.',
      category: 'Discovery & Provenance',
      parameters: [
        { name: 'query', type: 'string', description: 'Natural-language description of the data you need', required: true, inputType: 'text' },
        { name: 'limit', type: 'integer', description: 'Maximum results (default 5, max 25)', required: false, inputType: 'number' },
        { name: 'ai', type: 'boolean', description: 'Rerank top candidates with the primary AI model (default false)', required: false, inputType: 'text' }
      ],
      playgroundEnabled: true
    },
    {
      name: 'get_provenance',
      description: 'Resolve a stamped _datris_run_id back to its origin: the pipeline run, the tap run that fed it, the script commit, the config version, and the declared source.',
      category: 'Discovery & Provenance',
      parameters: [
        { name: 'run_id', type: 'string', description: 'The _datris_run_id value from the data', required: true, inputType: 'text' },
        { name: 'pipeline', type: 'string', description: 'Pipeline name, if known', required: false, inputType: 'text' },
        { name: 'tap_run', type: 'string', description: 'The _datris_tap_run value, if present', required: false, inputType: 'text' },
        { name: 'config_version', type: 'integer', description: 'The _datris_config_version value, if present', required: false, inputType: 'number' }
      ],
      playgroundEnabled: true
    },
    {
      name: 'get_lineage',
      description: 'Traverse the lineage graph from one node: what feeds it and what depends on it, the edges between them, freshness, and optionally the most recent recorded runs with what each read and wrote per destination. Datasets landed under an earlier configuration are marked historical.',
      category: 'Discovery & Provenance',
      parameters: [
        { name: 'node_type', type: 'string', description: 'source | tap | pipeline | dataset | catalog', required: true, inputType: 'text' },
        { name: 'name', type: 'string', description: 'The node name (the part after type: in a lineage id)', required: true, inputType: 'text' },
        { name: 'direction', type: 'string', description: 'up | down | both (default both)', required: false, inputType: 'text' },
        { name: 'depth', type: 'integer', description: 'Maximum hops (default unbounded)', required: false, inputType: 'number' },
        { name: 'runs', type: 'integer', description: 'Recent recorded runs to include (default 0, max 50)', required: false, inputType: 'number' }
      ],
      playgroundEnabled: true
    },
  ];

  constructor(private mcpService: McpService) { }

  ngOnInit(): void {
    this.buildCategoryIndex();
    this.loadVersion();
    this.loadHealth();
  }

  private buildCategoryIndex(): void {
    this.toolsByCategory = {};
    for (const tool of this.toolCatalog) {
      if (!this.toolsByCategory[tool.category]) {
        this.toolsByCategory[tool.category] = [];
      }
      this.toolsByCategory[tool.category].push(tool);
    }
    this.categories = Object.keys(this.toolsByCategory);
  }

  loadVersion(): void {
    this.mcpService.getVersion().subscribe({
      next: (res) => { this.serverVersion = typeof res === 'string' ? res : (res.version || JSON.stringify(res)); },
      error: () => { this.serverVersion = 'unavailable'; }
    });
  }

  loadHealth(): void {
    this.healthLoading = true;
    this.healthError = '';
    this.mcpService.getServiceHealth().subscribe({
      next: (res) => {
        this.healthServices = Object.entries(res).map(([name, val]: [string, any]) => ({
          name,
          status: val.status || 'unknown',
          message: val.message
        }));
        this.healthLoading = false;
      },
      error: (err) => {
        this.healthError = err.error || err.message || 'Failed to load health status';
        this.healthLoading = false;
      }
    });
  }

  getHealthIcon(status: string): string {
    switch (status) {
      case 'up': return 'check_circle';
      case 'down': return 'cancel';
      case 'not_configured': return 'remove_circle_outline';
      default: return 'help_outline';
    }
  }

  getServiceDisplayName(name: string): string {
    const names: Record<string, string> = {
      postgres: 'PostgreSQL',
      mongodb: 'MongoDB',
      minio: 'MinIO',
      activemq: 'ActiveMQ',
      kafka: 'Kafka',
      qdrant: 'Qdrant',
      weaviate: 'Weaviate',
      milvus: 'Milvus',
      chroma: 'Chroma',
      pgvector: 'pgvector'
    };
    return names[name] || name;
  }

  getCategoryIcon(category: string): string {
    const icons: Record<string, string> = {
      'System': 'monitor_heart',
      'Pipeline Management': 'account_tree',
      'Vector Search': 'search',
      'Database Query': 'storage',
      'Metadata Discovery': 'explore',
      'AI': 'auto_awesome',
      'Taps': 'water_drop',
      'Configuration': 'settings'
    };
    return icons[category] || 'extension';
  }

  getPlaygroundTools(): McpTool[] {
    return this.toolCatalog.filter(t => t.playgroundEnabled);
  }

  // Config Generator
  get configSnippet(): string {
    // `npx mcp-remote` stdio bridge with --transport sse-only. No header flag
    // for the default local server (no auth). When the user pastes a key, we
    // append `--header x-api-key:<key>` so it's forwarded on every call.
    const args: string[] = ['-y', 'mcp-remote', this.mcpServerUrl, '--transport', 'sse-only'];
    if (this.agentApiKey && this.agentApiKey.length > 0) {
      args.push('--header', `x-api-key:${this.agentApiKey}`);
    }
    return JSON.stringify({
      mcpServers: {
        datris: { command: 'npx', args }
      }
    }, null, 2);
  }

  get configFileHint(): string {
    switch (this.selectedAgent) {
      case 'claude-desktop':
        return '~/Library/Application Support/Claude/claude_desktop_config.json';
      case 'claude-code':
        return '.mcp.json (project root)';
      case 'cursor':
        return '.cursor/mcp.json (project root)';
      default:
        return '';
    }
  }

  copyConfig(): void {
    navigator.clipboard.writeText(this.configSnippet).then(() => {
      this.copySuccess = true;
      setTimeout(() => this.copySuccess = false, 2000);
    });
  }

  // Tool Playground
  onToolSelect(): void {
    this.selectedTool = this.toolCatalog.find(t => t.name === this.selectedToolName) || null;
    this.playgroundParams = {};
    this.paramOptions = {};
    this.playgroundError = '';
    this.playgroundResult = '';
    if (this.selectedTool) {
      for (const param of this.selectedTool.parameters) {
        this.playgroundParams[param.name] = param.defaultValue ?? (param.inputType === 'checkbox' ? false : '');
        if (param.inputType === 'select' && param.optionsLoader) {
          this.loadParamOptions(param);
        }
      }
    }
  }

  private loadParamOptions(param: McpToolParam): void {
    this.paramOptions[param.name] = [];
    const loadFromTool = (toolName: string) => {
      this.mcpService.executeTool(toolName, {}).subscribe({
        next: (res) => { this.paramOptions[param.name] = Array.isArray(res) ? res : []; },
        error: () => { this.paramOptions[param.name] = []; }
      });
    };
    const loaders: Record<string, () => void> = {
      loadPgvectorTables: () => {
        this.mcpService.executeTool('list_postgres_tables', { vector_only: 'true' }).subscribe({
          next: (res) => { this.paramOptions[param.name] = Array.isArray(res) ? res : []; },
          error: () => { this.paramOptions[param.name] = []; }
        });
      },
      loadQdrantCollections: () => loadFromTool('list_qdrant_collections'),
      loadWeaviateClasses: () => loadFromTool('list_weaviate_classes'),
      loadMilvusCollections: () => loadFromTool('list_milvus_collections'),
      loadChromaCollections: () => loadFromTool('list_chroma_collections')
    };
    const loader = loaders[param.optionsLoader!];
    if (loader) loader();
  }

  tryTool(tool: McpTool): void {
    if (!tool.playgroundEnabled) return;
    this.selectedToolName = tool.name;
    this.onToolSelect();
    setTimeout(() => {
      this.playgroundSection?.nativeElement?.scrollIntoView({ behavior: 'smooth', block: 'start' });
    }, 100);
  }

  executeTool(): void {
    if (!this.selectedTool) return;

    // Validate required params
    for (const param of this.selectedTool.parameters) {
      if (param.required && !this.playgroundParams[param.name] && this.playgroundParams[param.name] !== false) {
        this.playgroundError = 'Required parameter: ' + param.name;
        return;
      }
    }

    // Build clean params (skip empty optional values)
    const cleanParams: Record<string, any> = {};
    for (const param of this.selectedTool.parameters) {
      const val = this.playgroundParams[param.name];
      if (val !== '' && val !== undefined && val !== null) {
        cleanParams[param.name] = val;
      }
    }

    this.playgroundLoading = true;
    this.playgroundError = '';
    this.playgroundResult = '';

    try {
      this.mcpService.executeTool(this.selectedTool.name, cleanParams).subscribe({
        next: (res) => {
          this.playgroundResult = JSON.stringify(res, null, 2);
          this.playgroundLoading = false;
        },
        error: (err) => {
          this.playgroundError = typeof err.error === 'string' ? err.error : (err.message || 'Execution failed');
          this.playgroundLoading = false;
        }
      });
    } catch (e: any) {
      this.playgroundError = e.message || 'Invalid input';
      this.playgroundLoading = false;
    }
  }
}
