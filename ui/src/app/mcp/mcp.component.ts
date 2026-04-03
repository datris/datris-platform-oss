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
  selectedAgent = 'claude-desktop';
  pipelineUrl = 'http://localhost:8080';
  pipelineApiKey = localStorage.getItem('datris-api-key') || '';
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
      description: 'Create a pipeline from sample data. Schema is auto-detected.',
      category: 'Pipeline Management',
      parameters: [
        { name: 'content', type: 'string', description: 'Base64-encoded sample data', required: true, inputType: 'textarea' },
        { name: 'filename', type: 'string', description: 'Filename (e.g., data.csv)', required: true, inputType: 'text' },
        { name: 'pipeline', type: 'string', description: 'Pipeline name', required: true, inputType: 'text' },
        { name: 'destination', type: 'string', description: 'Destination: postgres, mongodb, qdrant, weaviate, milvus, chroma, pgvector', required: false, inputType: 'text' },
        { name: 'table', type: 'string', description: 'Table/collection name (default: pipeline name)', required: false, inputType: 'text' },
        { name: 'database', type: 'string', description: 'Database name (default: datris)', required: false, inputType: 'text' }
      ],
      playgroundEnabled: true
    },
    {
      name: 'delete_pipeline',
      description: 'Delete a pipeline configuration by name.',
      category: 'Pipeline Management',
      parameters: [
        { name: 'pipeline', type: 'string', description: 'Pipeline name to delete', required: true, inputType: 'text' }
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
      description: 'Get job status by pipeline token or pipeline name.',
      category: 'Pipeline Management',
      parameters: [
        { name: 'pipeline_token', type: 'string', description: 'Pipeline token from upload_data', required: false, inputType: 'text' },
        { name: 'pipeline_name', type: 'string', description: 'Pipeline name for latest status', required: false, inputType: 'text' },
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
    // --- Managed Service ---
    {
      name: 'signup_trial',
      description: 'Sign up for a free 14-day Datris trial. Returns an API key for the hosted MCP endpoint. No API key required.',
      category: 'Managed Service',
      parameters: [
        { name: 'email', type: 'string', description: 'Email address', required: true, inputType: 'text' },
        { name: 'password', type: 'string', description: 'Password (min 8 characters)', required: true, inputType: 'text' },
        { name: 'company', type: 'string', description: 'Company or project name', required: true, inputType: 'text' },
        { name: 'ai_provider', type: 'string', description: 'AI provider: anthropic or openai (default: anthropic)', required: false, inputType: 'text' }
      ],
      playgroundEnabled: false
    },
    {
      name: 'upgrade_to_dedicated',
      description: 'Upgrade from shared trial to a dedicated instance. Returns a Stripe checkout URL for payment.',
      category: 'Managed Service',
      parameters: [
        { name: 'droplet_size', type: 'string', description: 'Compute size (default: s-2vcpu-8gb)', required: false, inputType: 'text' },
        { name: 'storage_gb', type: 'integer', description: 'Block storage in GB (default: 25)', required: false, inputType: 'text' },
        { name: 'region', type: 'string', description: 'Datacenter region (default: nyc1)', required: false, inputType: 'text' }
      ],
      playgroundEnabled: false
    },
    {
      name: 'check_upgrade_status',
      description: 'Check dedicated instance provisioning status. Returns new MCP endpoint URL and API key when ready.',
      category: 'Managed Service',
      parameters: [],
      playgroundEnabled: false
    }
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
      'Configuration': 'settings'
    };
    return icons[category] || 'extension';
  }

  getPlaygroundTools(): McpTool[] {
    return this.toolCatalog.filter(t => t.playgroundEnabled);
  }

  // Config Generator
  get configSnippet(): string {
    const config: any = {
      mcpServers: {
        datris: {
          command: 'python',
          args: ['mcp-server/server.py'],
          env: {
            PIPELINE_URL: this.pipelineUrl
          }
        }
      }
    };

    if (this.pipelineApiKey) {
      config.mcpServers.datris.env.PIPELINE_API_KEY = this.pipelineApiKey;
    }

    return JSON.stringify(config, null, 2);
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
