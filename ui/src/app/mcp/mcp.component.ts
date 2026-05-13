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
  styleUrls: ['./mcp.component.css']
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

  // Full tool catalog
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
    // --- Discovery ---
    {
      name: 'discover_source',
      description: 'Discover available datasets from any data source. Chat with AI to enumerate datasets from a Python package, API, website, or database. Returns a structured dataset catalog with parameters and tap instruction templates.',
      category: 'Discovery',
      parameters: [
        { name: 'message', type: 'string', description: 'What to discover (e.g., "What datasets are available in yfinance?")', required: true, inputType: 'textarea' },
        { name: 'messages', type: 'array', description: 'Full conversation history for multi-turn discovery (optional)', required: false, inputType: 'textarea' }
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
      description: 'Create a tap from an instruction (AI generates script), a user-provided script, or config only.',
      category: 'Taps',
      parameters: [
        { name: 'name', type: 'string', description: 'Unique tap name', required: true, inputType: 'text' },
        { name: 'instruction', type: 'string', description: 'Plain-English instruction for AI script generation', required: false, inputType: 'textarea' },
        { name: 'script', type: 'string', description: 'Python source code with a fetch() function', required: false, inputType: 'textarea' },
        { name: 'target_pipeline', type: 'string', description: 'Pipeline to push fetched data into', required: false, inputType: 'text' },
        { name: 'cron_expression', type: 'string', description: 'Quartz CRON schedule (e.g., 0 0 * * * ?)', required: false, inputType: 'text' },
        { name: 'secret_name', type: 'string', description: 'Vault secret name for credentials', required: false, inputType: 'text' },
        { name: 'tap_type', type: 'string', description: 'structured (default) or document (for PDFs/Word/HTML into vector-store pipelines)', required: false, inputType: 'text' }
      ],
      playgroundEnabled: true
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
      description: 'Execute a tap and push fetched data to the target pipeline. Response carries persisted, persistedReason, publisherToken, and pipelineTokens so the caller can confirm what actually landed.',
      category: 'Taps',
      parameters: [
        { name: 'name', type: 'string', description: 'Name of the tap to run', required: true, inputType: 'text' }
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
      description: 'Update a tap\'s config without regenerating the script.',
      category: 'Taps',
      parameters: [
        { name: 'name', type: 'string', description: 'Name of the tap to update', required: true, inputType: 'text' },
        { name: 'enabled', type: 'boolean', description: 'Enable or disable the tap', required: false, inputType: 'text' },
        { name: 'cron_expression', type: 'string', description: 'New CRON schedule', required: false, inputType: 'text' },
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
      name: 'delete_tap',
      description: 'Delete a tap and its stored script.',
      category: 'Taps',
      parameters: [
        { name: 'name', type: 'string', description: 'Name of the tap to delete', required: true, inputType: 'text' }
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
      description: 'Create OR UPDATE a pipeline. Schema is auto-detected from a sample file for structured destinations. Upserts by name — calling again with the same name replaces the config in place without dropping the destination data, so you can change knobs (keyFields, truncate, codegen_rule) without delete-then-recreate.',
      category: 'Pipeline Management',
      parameters: [
        { name: 'content', type: 'string', description: 'Base64-encoded sample data. Required for structured destinations; omit for vector destinations.', required: false, inputType: 'textarea' },
        { name: 'filename', type: 'string', description: 'Filename (e.g., data.csv). Required for structured destinations.', required: false, inputType: 'text' },
        { name: 'pipeline', type: 'string', description: 'Pipeline name', required: true, inputType: 'text' },
        { name: 'destination', type: 'string', description: 'Destination: postgres, mongodb, qdrant, weaviate, milvus, chroma, pgvector', required: false, inputType: 'text' },
        { name: 'table', type: 'string', description: 'Table/collection name (default: pipeline name)', required: false, inputType: 'text' },
        { name: 'database', type: 'string', description: 'Database name (default: datris)', required: false, inputType: 'text' },
        { name: 'keyFields', type: 'array', description: 'Optional natural-key columns used to dedupe / upsert rows on every run (postgres / mongodb only). E.g. ["user_id", "event_date"] — rows with the same key replace the existing row instead of appending.', required: false, inputType: 'text' },
        { name: 'truncate', type: 'boolean', description: 'Optional. Wipe destination table/collection before each run (postgres / mongodb only). Default false. Distinct from keyFields: truncate clears everything; keyFields upserts per key.', required: false, inputType: 'text' },
        { name: 'catalog', type: 'string', description: 'Optional catalog label to group this pipeline with related ones (free-form). Omit by default — users assign catalogs explicitly.', required: false, inputType: 'text' }
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
      name: 'profile_data',
      description: 'AI-profile data with summary stats and suggested DQ rules.',
      category: 'Pipeline Management',
      parameters: [
        { name: 'content', type: 'string', description: 'Base64-encoded file content', required: true, inputType: 'textarea' },
        { name: 'filename', type: 'string', description: 'Filename (e.g., sample.csv)', required: true, inputType: 'text' }
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
      name: 'update_secret',
      description: 'Update an AI provider secret (anthropic, openai, ollama, embedding) to configure API keys for AI features.',
      category: 'Configuration',
      parameters: [
        { name: 'name', type: 'string', description: 'Secret name: anthropic, openai, ollama, or embedding', required: true, inputType: 'text' },
        { name: 'fields', type: 'object', description: 'JSON with endpoint, model, apiKey fields', required: true, inputType: 'textarea' }
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
      'Discovery': 'manage_search',
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
