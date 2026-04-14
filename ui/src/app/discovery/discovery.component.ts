import { Component, ViewChild, ElementRef } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Router } from '@angular/router';
import { Subscription, forkJoin } from 'rxjs';
import { DiscoveryService } from './discovery.service';
import { TapService } from '../tap.service';
import { PipelineService } from '../pipeline.service';
import { SecretsService } from '../secrets.service';
import { sanitizeLabel } from '../shared/sanitize';

interface DatasetParam {
  name: string;
  type: string;
  label: string;
  placeholder?: string;
  options?: string[];
  default?: string;
  required?: boolean;
  multiple?: boolean;
}

interface ParamSourceConfig {
  source: 'manual' | 'table' | 'file' | 'ai';
  dbType?: 'postgres' | 'mongodb';
  tableRef?: string;
  columnRef?: string;
  // Metadata for dropdowns
  availableTables?: string[];
  availableColumns?: string[];
  loadingTables?: boolean;
  loadingColumns?: boolean;
  aiPrompt?: string;
  aiGenerating?: boolean;
  aiResult?: string[];
  fileName?: string;
  fileCount?: number;
}

interface Dataset {
  id: string;
  category: string;
  name: string;
  description: string;
  parameters: DatasetParam[];
  tapInstruction: string;
  packages?: string[];
  requiresAuth?: boolean;
  suggestedEnvVars?: string[];
  selected?: boolean;
  paramValues?: Record<string, string>;
}

type BuildStatus = 'pending' | 'generating' | 'testing' | 'fixing' | 'retesting' | 'saving' | 'done' | 'error';

interface BuildItem {
  datasetId: string;
  datasetName: string;
  tapName: string;
  instruction: string;
  status: BuildStatus;
  statusLabel: string;
  error?: string;
  pipelineCreated?: boolean;
  recordCount?: number;
  testRecords?: any[];
  testColumns?: string[];
  testDataType?: string;
  runStatus?: 'pending' | 'validating' | 'running' | 'done' | 'error' | 'schema_mismatch';
  runStatusLabel?: string;
  runError?: string;
  runRecordCount?: number;
  validationColumns?: string[];
  customCron?: string;
  cronPrompt?: string;
  generatingCron?: boolean;
  scheduleExpanded?: boolean;
  scriptExpanded?: boolean;
  editScript?: string;
  editPackages?: string[];
  retesting?: boolean;
  retrying?: boolean;
  fixAttempts?: number;
  truncateBeforeWrite?: boolean;
  pipelineStatus?: 'pending' | 'creating' | 'done' | 'error';
  pipelineError?: string;
  pipelineExpanded?: boolean;
}

const STATUS_LABELS: Record<BuildStatus, string> = {
  pending: 'Waiting...',
  generating: 'Generating script...',
  testing: 'Testing script...',
  fixing: 'Fixing script...',
  retesting: 'Re-testing script...',
  saving: 'Saving tap...',
  done: 'Complete',
  error: 'Failed'
};

@Component({
  selector: 'app-discovery',
  templateUrl: './discovery.component.html',
  styleUrls: ['./discovery.component.css']
})
export class DiscoveryComponent {
  phase = 1;
  error = '';

  // Phase 1 — Chat
  messages: Array<{role: string, content: string}> = [];
  chatInput = '';
  discovering = false;
  discoveringQuery = '';
  chatting = false;
  sourceIdentified = false;
  discoverStatusMessage = '';
  private statusInterval: any;
  private statusMessages = [
    'Analyzing data source...',
    'Enumerating available datasets...',
    'Identifying parameters and options...',
    'Building tap instructions...',
    'Compiling dataset catalog...',
    'Finalizing results...'
  ];
  private statusIndex = 0;
  datasets: Dataset[] = [];
  ready = false;
  @ViewChild('chatInputEl') chatInputEl?: ElementRef<HTMLInputElement>;

  // Phase 2 — Selection (derived from datasets)
  categories: Array<{name: string, datasets: Dataset[]}> = [];

  // Phase 3 — Build
  prefix = '';
  selectedCatalog = '';
  availableCatalogs: string[] = [];
  showNewCatalogInput = false;
  newCatalogName = '';

  // Phase 3 — Credentials
  secretName = '';
  availableSecrets: string[] = [];
  showCreateSecret = false;
  editingSecret = false;
  newSecretName = '';
  newSecretFields: {key: string, value: string}[] = [{key: '', value: ''}];
  savingSecret = false;
  secretError = '';
  allSuggestedEnvVars: string[] = [];
  sharedParams: DatasetParam[] = [];
  sharedParamValues: Record<string, string> = {};
  sharedParamSources: Record<string, ParamSourceConfig> = {};
  building = false;
  buildCancelled = false;
  buildItems: BuildItem[] = [];
  buildComplete = false;
  private activeWorkers = 0;

  // Phase 3 — CRON scheduling
  useSchedule = false;
  sharedCron = '';
  cronPreset = 'custom';
  cronPrompt = '';
  generatingCron = false;
  enableSchedule = true;

  // Preview modal
  previewItem: BuildItem | null = null;

  // Phase 4 — Create Pipelines
  truncateAll = false;
  creatingPipelines = false;
  pipelinesCreated = false;

  // Phase 4 — Edit config modal (step 5 DQ + step 6 Transformation)
  editingOpen = false;
  editingLoading = false;
  editingConfigs: {
    tapName: string;
    config: any;
    originalDq: any;
    originalTx: any;
    dirty: boolean;
    saveError: string;
  }[] = [];
  editingIndex = 0;
  savingConfigs = false;

  // Phase 5 — Schedule
  applyingSchedules = false;

  // Phase 6 — Run
  running = false;
  runCancelled = false;
  runComplete = false;
  private activeRunWorkers = 0;

  private activeSub: Subscription | null = null;
  private activeSubs: Subscription[] = [];

  constructor(
    private discoveryService: DiscoveryService,
    private tapService: TapService,
    private pipelineService: PipelineService,
    private secretsService: SecretsService,
    private http: HttpClient,
    private router: Router
  ) {
    this.loadAvailableCatalogs();
  }

  // ---------- Phase 1: Chat ----------

  sendMessage(): void {
    if (!this.chatInput.trim() || this.chatting || this.discovering) return;
    const userMsg = { role: 'user', content: this.chatInput.trim() };
    this.messages.push(userMsg);
    this.discoveringQuery = this.chatInput.trim();
    this.chatInput = '';
    this.chatting = true;
    this.error = '';

    this.activeSub = this.discoveryService.chat(this.messages).subscribe({
      next: (result) => {
        let parsed = result;
        if (typeof result === 'string') {
          try { parsed = JSON.parse(result); } catch { parsed = { reply: result, sourceIdentified: false }; }
        }
        const reply = parsed?.reply || '';
        this.messages.push({ role: 'assistant', content: reply });
        this.sourceIdentified = !!parsed?.sourceIdentified;
        this.chatting = false;
        requestAnimationFrame(() => this.chatInputEl?.nativeElement.focus());
      },
      error: (err) => {
        this.error = 'Chat failed: ' + (typeof err.error === 'string' ? err.error : err.message || 'Unknown error');
        this.chatting = false;
      }
    });
  }

  private startStatusCycling(): void {
    this.statusIndex = 0;
    this.discoverStatusMessage = this.statusMessages[0];
    this.statusInterval = setInterval(() => {
      this.statusIndex = Math.min(this.statusIndex + 1, this.statusMessages.length - 1);
      this.discoverStatusMessage = this.statusMessages[this.statusIndex];
    }, 10000);
  }

  private stopStatusCycling(): void {
    if (this.statusInterval) {
      clearInterval(this.statusInterval);
      this.statusInterval = null;
    }
    this.discoverStatusMessage = '';
  }

  discoverDatasets(): void {
    if (this.discovering || this.messages.length === 0) return;
    this.discovering = true;
    this.error = '';
    this.startStatusCycling();

    // Ensure messages end with a user message (Anthropic API requirement).
    const discoverMessages = [...this.messages];
    if (discoverMessages.length > 0 && discoverMessages[discoverMessages.length - 1].role === 'assistant') {
      discoverMessages.push({ role: 'user', content: 'Based on our conversation, discover all available datasets from the data source(s) we discussed.' });
    }

    // If the conversation mentions authentication, first ask the LLM for auth specifics
    const conversationText = this.messages.map(m => m.content).join(' ').toLowerCase();
    const mentionsAuth = ['api key', 'api_key', 'apikey', 'authentication', 'credentials', 'token', 'secret', 'auth'].some(term => conversationText.includes(term));

    if (mentionsAuth) {
      // Quick call to get auth configuration instructions
      const authMessages = [{ role: 'user', content:
        'Based on our conversation, how does this data source authenticate in Python? ' +
        'What exact Python code is needed to configure the API key/token from an environment variable before making API calls? ' +
        'Be specific — include the exact library configuration line. Return ONLY the Python auth setup instructions, nothing else.'
      }];
      this.discoveryService.chat([...this.messages, ...authMessages]).subscribe({
        next: (authResult) => {
          let authParsed = authResult;
          if (typeof authResult === 'string') {
            try { authParsed = JSON.parse(authResult); } catch { authParsed = { reply: authResult }; }
          }
          const authContext = authParsed?.reply || '';
          this.executeDiscover(discoverMessages, authContext);
        },
        error: () => {
          // Auth lookup failed — proceed without it
          this.executeDiscover(discoverMessages, '');
        }
      });
    } else {
      this.executeDiscover(discoverMessages, '');
    }
  }

  private executeDiscover(discoverMessages: Array<{role: string, content: string}>, authContext: string): void {
    this.activeSub = this.discoveryService.discover(discoverMessages, authContext).subscribe({
      next: (result) => {
        let parsed = result;
        if (typeof result === 'string') {
          try { parsed = JSON.parse(result); } catch { parsed = { reply: result, datasets: [], ready: false }; }
        }
        const reply = parsed?.reply || '';
        if (reply) {
          this.messages.push({ role: 'assistant', content: reply });
        }
        if (Array.isArray(parsed?.datasets) && parsed.datasets.length > 0) {
          this.datasets = parsed.datasets.map((d: any) => ({
            ...d,
            selected: false,
            paramValues: this.buildDefaultParamValues(d.parameters || [])
          }));
        }
        this.ready = !!parsed?.ready;
        this.discovering = false;
        this.stopStatusCycling();
      },
      error: (err) => {
        this.error = 'Discovery failed: ' + (typeof err.error === 'string' ? err.error : err.message || 'Unknown error');
        this.discovering = false;
        this.stopStatusCycling();
      }
    });
  }

  stopDiscovery(): void {
    if (this.activeSub) {
      this.activeSub.unsubscribe();
      this.activeSub = null;
    }
    this.discovering = false;
    this.chatting = false;
    this.stopStatusCycling();
    if (this.messages.length > 0 && this.messages[this.messages.length - 1].role === 'user') {
      this.chatInput = this.messages.pop()!.content;
    }
    requestAnimationFrame(() => this.chatInputEl?.nativeElement.focus());
  }

  private buildDefaultParamValues(params: DatasetParam[]): Record<string, string> {
    const values: Record<string, string> = {};
    for (const p of params) {
      values[p.name] = p.default || '';
    }
    return values;
  }

  goToSelection(): void {
    this.buildCategories();
    this.phase = 2;
  }

  // ---------- Phase 2: Selection ----------

  private buildCategories(): void {
    const catMap = new Map<string, Dataset[]>();
    for (const ds of this.datasets) {
      const list = catMap.get(ds.category) || [];
      list.push(ds);
      catMap.set(ds.category, list);
    }
    this.categories = Array.from(catMap.entries()).map(([name, datasets]) => ({ name, datasets }));
  }

  toggleDataset(ds: Dataset): void {
    ds.selected = !ds.selected;
  }

  selectAllInCategory(cat: {name: string, datasets: Dataset[]}, selected: boolean): void {
    cat.datasets.forEach(d => d.selected = selected);
  }

  allSelectedInCategory(cat: {name: string, datasets: Dataset[]}): boolean {
    return cat.datasets.every(d => d.selected);
  }

  noneSelectedInCategory(cat: {name: string, datasets: Dataset[]}): boolean {
    return cat.datasets.every(d => !d.selected);
  }

  selectedCount(): number {
    return this.datasets.filter(d => d.selected).length;
  }

  // ---------- Credentials ----------

  loadAvailableSecrets(): void {
    this.secretsService.listSecrets('tap').subscribe({
      next: (secrets) => this.availableSecrets = secrets || [],
      error: () => {}
    });
  }

  needsCredentials(): boolean {
    return this.selectedDatasets().some(ds => ds.requiresAuth);
  }

  private computeSuggestedEnvVars(selected: Dataset[]): void {
    const envVarSet = new Set<string>();
    for (const ds of selected) {
      if (ds.requiresAuth && ds.suggestedEnvVars) {
        for (const v of ds.suggestedEnvVars) envVarSet.add(v);
      }
    }
    this.allSuggestedEnvVars = Array.from(envVarSet);
  }

  onSecretChange(value: string): void {
    if (value === '__create__') {
      this.showCreateSecret = true;
      this.secretName = '';
    } else {
      this.showCreateSecret = false;
      this.secretName = value;
    }
  }

  editSecret(): void {
    this.editingSecret = true;
    this.showCreateSecret = false;
    this.newSecretFields = [{key: '', value: ''}];
    this.secretsService.getSecret(this.secretName).subscribe({
      next: (data) => {
        const fields = data.fields || {};
        this.newSecretFields = Object.entries(fields)
          .filter(([k]) => k !== '_type')
          .map(([k, v]) => ({key: k, value: v as string}));
        if (this.newSecretFields.length === 0) this.newSecretFields = [{key: '', value: ''}];
      },
      error: () => { this.editingSecret = false; }
    });
  }

  addSecretField(): void {
    this.newSecretFields.push({key: '', value: ''});
  }

  removeSecretField(index: number): void {
    this.newSecretFields.splice(index, 1);
  }

  saveNewSecret(): void {
    const name = this.editingSecret ? this.secretName : this.newSecretName.trim();
    this.secretError = '';
    if (!name) { this.secretError = 'Secret name is required'; return; }
    const fields: Record<string, string> = {};
    this.newSecretFields.filter(f => f.key.trim()).forEach(f => fields[f.key.trim()] = f.value);
    if (Object.keys(fields).length === 0) { this.secretError = 'At least one key-value pair is required'; return; }
    fields['_type'] = 'tap';

    this.savingSecret = true;
    this.secretsService.putSecret(name, fields).subscribe({
      next: () => {
        this.secretName = name;
        if (!this.availableSecrets.includes(name)) {
          this.availableSecrets.push(name);
          this.availableSecrets.sort();
        }
        this.showCreateSecret = false;
        this.editingSecret = false;
        this.newSecretName = '';
        this.newSecretFields = [{key: '', value: ''}];
        this.savingSecret = false;
      },
      error: () => { this.savingSecret = false; }
    });
  }

  cancelCreateSecret(): void {
    this.showCreateSecret = false;
    this.editingSecret = false;
    if (!this.editingSecret) this.secretName = '';
    this.newSecretName = '';
    this.newSecretFields = [{key: '', value: ''}];
    this.secretError = '';
  }

  useSuggestedEnvVars(): void {
    if (this.allSuggestedEnvVars.length === 0) return;
    this.showCreateSecret = true;
    this.editingSecret = false;
    this.secretName = '';
    this.newSecretFields = this.allSuggestedEnvVars.map(k => ({key: k, value: ''}));
  }

  loadAvailableCatalogs(): void {
    this.tapService.getTaps().subscribe({
      next: (taps) => {
        const cats = new Set<string>();
        (taps || []).forEach((t: any) => { if (t.catalog) cats.add(t.catalog); });
        this.availableCatalogs = Array.from(cats).sort();
      },
      error: () => {}
    });
  }

  onCatalogChange(value: string): void {
    if (value === '__new__') {
      this.showNewCatalogInput = true;
      this.newCatalogName = '';
      this.selectedCatalog = '';
    } else {
      this.showNewCatalogInput = false;
    }
  }

  confirmNewCatalog(): void {
    const name = sanitizeLabel(this.newCatalogName);
    if (!name) return;
    this.selectedCatalog = name;
    if (!this.availableCatalogs.includes(name)) {
      this.availableCatalogs.push(name);
      this.availableCatalogs.sort();
    }
    this.showNewCatalogInput = false;
    this.newCatalogName = '';
  }

  hasMixedMultiplicity(): boolean {
    const selected = this.datasets.filter(d => d.selected);
    if (selected.length < 2) return false;
    // Check if any parameter name appears with both multiple=true and multiple=false
    const paramMultiple = new Map<string, Set<boolean>>();
    for (const ds of selected) {
      for (const p of (ds.parameters || [])) {
        if (!paramMultiple.has(p.name)) paramMultiple.set(p.name, new Set());
        paramMultiple.get(p.name)!.add(!!p.multiple);
      }
    }
    return Array.from(paramMultiple.values()).some(s => s.size > 1);
  }

  goToBuild(): void {
    const selected = this.datasets.filter(d => d.selected);
    if (selected.length === 0) return;

    // Only compute shared params the first time (not on back/next)
    if (this.sharedParams.length === 0) {
      this.computeSharedParams(selected);
    }
    this.computeSuggestedEnvVars(selected);
    this.loadAvailableCatalogs();
    this.loadAvailableSecrets();
    this.phase = 3;
  }

  private computeSharedParams(selected: Dataset[]): void {
    // A parameter becomes "shared" if:
    // 1. It appears in 2+ selected datasets with the same name, OR
    // 2. It has multiple=true (even in just one dataset — needs the source selector)
    const paramInfo = new Map<string, {count: number, param: DatasetParam, datasetNames: string[]}>();
    for (const ds of selected) {
      for (const p of (ds.parameters || [])) {
        const existing = paramInfo.get(p.name);
        if (existing) {
          existing.count++;
          existing.datasetNames.push(ds.name);
        } else {
          paramInfo.set(p.name, { count: 1, param: p, datasetNames: [ds.name] });
        }
      }
    }
    this.sharedParams = [];
    this.sharedParamValues = {};
    for (const [name, {count, param}] of paramInfo.entries()) {
      if (count >= 2 || param.multiple) {
        this.sharedParams.push(param);
        this.sharedParamValues[name] = param.default || '';
      }
    }
  }

  getParamDatasetNames(paramName: string): string[] {
    return this.selectedDatasets()
      .filter(ds => (ds.parameters || []).some(p => p.name === paramName))
      .map(ds => ds.name);
  }

  getUniqueParams(ds: Dataset): DatasetParam[] {
    return (ds.parameters || []).filter(p => !this.sharedParams.some(sp => sp.name === p.name));
  }

  isSharedParam(paramName: string): boolean {
    return this.sharedParams.some(p => p.name === paramName);
  }

  getEffectiveParamValue(ds: Dataset, paramName: string): string {
    if (this.isSharedParam(paramName)) {
      return this.sharedParamValues[paramName] || '';
    }
    return ds.paramValues?.[paramName] || '';
  }

  // ---------- Phase 3: Build ----------

  selectedDatasets(): Dataset[] {
    return this.datasets.filter(d => d.selected);
  }

  // ---------- Parameter Sources ----------

  getParamSource(paramName: string): ParamSourceConfig {
    if (!this.sharedParamSources[paramName]) {
      this.sharedParamSources[paramName] = { source: 'manual' };
    }
    return this.sharedParamSources[paramName];
  }

  setParamSource(paramName: string, source: 'manual' | 'table' | 'file' | 'ai'): void {
    const config = this.getParamSource(paramName);
    config.source = source;
    if (source === 'table' && !config.dbType) {
      config.dbType = 'postgres';
      this.loadTablesForParam(paramName);
    }
  }

  onDbTypeChange(paramName: string): void {
    const config = this.getParamSource(paramName);
    config.tableRef = '';
    config.columnRef = '';
    config.availableTables = [];
    config.availableColumns = [];
    this.loadTablesForParam(paramName);
  }

  loadTablesForParam(paramName: string): void {
    const config = this.getParamSource(paramName);
    config.loadingTables = true;
    config.availableTables = [];

    if (config.dbType === 'postgres') {
      this.http.get<any[]>('/api/v1/metadata/postgres/tables').subscribe({
        next: (tables) => {
          // API returns flat string array: ["table1", "table2"]
          config.availableTables = (tables || []).map((t: any) => typeof t === 'string' ? t : (t.name || t)).sort();
          config.loadingTables = false;
        },
        error: () => { config.loadingTables = false; }
      });
    } else {
      this.http.get<any[]>('/api/v1/metadata/mongodb/collections').subscribe({
        next: (collections) => {
          // API returns flat string array: ["collection1", "collection2"]
          config.availableTables = (collections || []).map((c: any) => typeof c === 'string' ? c : (c.name || c)).sort();
          config.loadingTables = false;
        },
        error: () => { config.loadingTables = false; }
      });
    }
  }

  onTableChange(paramName: string): void {
    const config = this.getParamSource(paramName);
    config.columnRef = '';
    config.availableColumns = [];
    if (!config.tableRef) return;
    config.loadingColumns = true;

    if (config.dbType === 'postgres') {
      const parts = config.tableRef.split('.');
      const schema = parts.length > 1 ? parts[0] : 'public';
      const table = parts.length > 1 ? parts[1] : parts[0];
      this.http.get<any[]>('/api/v1/metadata/postgres/columns?table=' + encodeURIComponent(table) + '&schema=' + encodeURIComponent(schema)).subscribe({
        next: (cols) => {
          config.availableColumns = (cols || []).map((c: any) => c.name || c).sort();
          config.loadingColumns = false;
        },
        error: () => { config.loadingColumns = false; }
      });
    } else {
      // MongoDB: sample a document to get field names
      this.http.post<any>('/api/v1/query/mongodb', { collection: config.tableRef, limit: 1 }).subscribe({
        next: (result) => {
          const docs = result?.results || result || [];
          if (Array.isArray(docs) && docs.length > 0) {
            config.availableColumns = Object.keys(docs[0]).filter(k => k !== '_id').sort();
          }
          config.loadingColumns = false;
        },
        error: () => { config.loadingColumns = false; }
      });
    }
  }

  onFileUpload(event: Event, paramName: string): void {
    const input = event.target as HTMLInputElement;
    if (!input.files || input.files.length === 0) return;
    const file = input.files[0];
    const config = this.getParamSource(paramName);
    config.fileName = file.name;

    const reader = new FileReader();
    reader.onload = () => {
      const text = reader.result as string;
      // Parse: split by newlines, then commas, trim, deduplicate
      const values = text.split(/[\n\r,]+/)
        .map(v => v.trim())
        .filter(v => v.length > 0);
      const unique = [...new Set(values)];
      this.sharedParamValues[paramName] = unique.join(', ');
      config.fileCount = unique.length;
      config.source = 'file';
    };
    reader.readAsText(file);
    // Reset input so same file can be re-selected
    input.value = '';
  }

  generateAiList(paramName: string): void {
    const config = this.getParamSource(paramName);
    if (!config.aiPrompt?.trim() || config.aiGenerating) return;
    config.aiGenerating = true;
    config.aiResult = undefined;

    const messages = [{ role: 'user', content:
      'Generate a complete list of: ' + config.aiPrompt +
      '. Return ONLY a JSON array of strings with the values, no commentary, no markdown. Example: ["AAPL", "MSFT", "GOOGL"]'
    }];

    this.discoveryService.chat(messages).subscribe({
      next: (result) => {
        let parsed = result;
        if (typeof result === 'string') {
          try { parsed = JSON.parse(result); } catch { parsed = { reply: result }; }
        }
        const reply = parsed?.reply || '';
        // Try to extract JSON array from the reply
        try {
          const arrMatch = reply.match(/\[[\s\S]*\]/);
          if (arrMatch) {
            const arr = JSON.parse(arrMatch[0]);
            if (Array.isArray(arr)) {
              config.aiResult = arr.map((v: any) => String(v).trim()).filter((v: string) => v);
              config.aiGenerating = false;
              return;
            }
          }
        } catch {}
        // Fallback: split by commas/newlines
        config.aiResult = reply.split(/[\n\r,]+/).map((v: string) => v.trim().replace(/^["'\s]+|["'\s]+$/g, '')).filter((v: string) => v);
        config.aiGenerating = false;
      },
      error: () => {
        config.aiGenerating = false;
      }
    });
  }

  useAiResult(paramName: string): void {
    const config = this.getParamSource(paramName);
    if (config.aiResult && config.aiResult.length > 0) {
      this.sharedParamValues[paramName] = config.aiResult.join(', ');
    }
  }

  isDateParam(p: DatasetParam): boolean {
    const name = (p.name || '').toLowerCase();
    const label = (p.label || '').toLowerCase();
    return p.type === 'string' && (
      name.includes('date') || label.includes('date') ||
      name.includes('start') || name.includes('end') ||
      (p.placeholder || '').match(/^\d{4}-\d{2}-\d{2}/) !== null
    );
  }

  setToday(paramName: string, target: 'shared' | Dataset): void {
    if (target === 'shared') {
      this.sharedParamValues[paramName] = '{{TODAY}}';
    } else {
      target.paramValues![paramName] = '{{TODAY}}';
    }
  }

  private interpolateInstruction(ds: Dataset): string {
    let instruction = ds.tapInstruction;
    let runtimePreamble = '';

    for (const p of (ds.parameters || [])) {
      let value = this.getEffectiveParamValue(ds, p.name);
      const sourceConfig = this.sharedParamSources[p.name];

      // Handle runtime table/collection source
      if (sourceConfig?.source === 'table' && sourceConfig.tableRef && sourceConfig.columnRef) {
        if (sourceConfig.dbType === 'mongodb') {
          runtimePreamble += 'First, query the Datris platform\'s MongoDB database using the injected DATRIS_PLATFORM_HOST, DATRIS_PLATFORM_PORT, and DATRIS_MONGODB_DATABASE environment variables. '
            + 'Call POST /api/v1/query/mongodb with collection "' + sourceConfig.tableRef + '" to get all documents, then extract distinct values from the "' + sourceConfig.columnRef + '" field. '
            + 'Use those values as the ' + p.name + ' parameter for the following task. ';
        } else {
          runtimePreamble += 'First, query the Datris platform\'s Postgres database using the injected DATRIS_PLATFORM_HOST and DATRIS_PLATFORM_PORT environment variables. '
            + 'Execute: SELECT DISTINCT ' + sourceConfig.columnRef + ' FROM ' + sourceConfig.tableRef + ' ORDER BY ' + sourceConfig.columnRef + '. '
            + 'Use those values as the ' + p.name + ' parameter for the following task. ';
        }
        const dbLabel = sourceConfig.dbType === 'mongodb' ? 'MongoDB collection' : 'Datris table';
        instruction = instruction.replace(new RegExp('\\{\\{' + p.name + '\\}\\}', 'g'),
          '{values from ' + dbLabel + ' ' + sourceConfig.tableRef + '.' + sourceConfig.columnRef + '}');
        continue;
      }

      // Resolve {{TODAY}} — tell the script to use today's date at runtime
      if (value === '{{TODAY}}') {
        instruction = instruction.replace(new RegExp('\\{\\{' + p.name + '\\}\\}', 'g'),
          "today's date (use datetime.date.today().isoformat() in the script)");
        continue;
      }
      instruction = instruction.replace(new RegExp('\\{\\{' + p.name + '\\}\\}', 'g'), value);
    }

    if (runtimePreamble) {
      instruction = runtimePreamble + '\n\n' + instruction;
    }
    return instruction;
  }

  buildAll(): void {
    if (this.building) return;
    if (this.needsCredentials() && !this.secretName) {
      this.error = 'Some datasets require credentials. Please select or create a secret before building.';
      return;
    }
    this.building = true;
    this.buildComplete = false;
    this.buildCancelled = false;
    this.error = '';
    this.activeWorkers = 0;
    this.activeSubs = [];

    const selected = this.selectedDatasets();

    this.buildItems = selected.map(ds => ({
      datasetId: ds.id,
      datasetName: ds.name,
      tapName: sanitizeLabel((this.selectedCatalog || 'discovery') + '_' + ds.id),
      instruction: this.interpolateInstruction(ds),
      status: 'pending' as BuildStatus,
      statusLabel: STATUS_LABELS['pending']
    }));

    // Launch all builds in parallel
    for (const item of this.buildItems) {
      this.activeWorkers++;
      this.generateTap(item);
    }
  }

  resetBuild(): void {
    // Delete any taps that were created in the previous build
    for (const item of this.buildItems) {
      if (item.status === 'done') {
        this.tapService.deleteTap(item.tapName).subscribe({ next: () => {}, error: () => {} });
      }
    }
    this.buildItems = [];
    this.buildComplete = false;
    this.building = false;
    this.buildCancelled = false;
    this.activeWorkers = 0;
    this.activeSubs = [];
  }

  stopBuild(): void {
    this.buildCancelled = true;
    for (const sub of this.activeSubs) {
      sub.unsubscribe();
    }
    this.activeSubs = [];
    for (const item of this.buildItems) {
      if (item.status !== 'done' && item.status !== 'error') {
        item.status = 'error';
        item.statusLabel = 'Cancelled';
        item.error = 'Build cancelled by user';
      }
    }
    this.activeWorkers = 0;
    this.building = false;
    this.buildComplete = true;
  }

  private setItemStatus(item: BuildItem, status: BuildStatus): void {
    item.status = status;
    item.statusLabel = STATUS_LABELS[status];
  }

  /** Pick the next pending item and start processing it. */
  /** Called when a worker finishes (success or error). */
  private workerDone(): void {
    this.activeWorkers--;
    if (this.activeWorkers === 0) {
      this.building = false;
      this.buildComplete = true;
    }
  }

  private trackSub(sub: Subscription): void {
    this.activeSubs.push(sub);
  }

  private readonly MAX_FIX_ATTEMPTS = 3;

  private generateTap(item: BuildItem): void {
    this.setItemStatus(item, 'generating');
    item.fixAttempts = 0;

    const sub = this.tapService.generateScript(item.instruction, item.tapName, undefined, this.secretName || undefined).subscribe({
      next: (result) => {
        (item as any)._script = result.script || '';
        (item as any)._scriptPath = result.scriptPath || '';
        (item as any)._packages = result.packages || [];
        // Save tap config immediately so the script is always accessible (even if test fails)
        const tapConfig: any = {
          name: item.tapName,
          description: item.instruction,
          scriptPath: result.scriptPath || '',
          packages: result.packages?.length > 0 ? result.packages : null,
          secretName: this.secretName || null,
          targetPipeline: null,
          cronExpression: null,
          enabled: false,
          catalog: this.selectedCatalog || null
        };
        this.tapService.createOrUpdateTap(tapConfig).subscribe({
          next: () => this.testTap(item),
          error: () => this.testTap(item) // test anyway even if save fails
        });
      },
      error: (err) => {
        this.setItemStatus(item, 'error');
        item.error = 'Script generation failed: ' + (typeof err.error === 'string' ? err.error : err.message).substring(0, 200);
        this.workerDone();
      }
    });
    this.trackSub(sub);
  }

  private testTap(item: BuildItem): void {
    this.setItemStatus(item, 'testing');

    const config = {
      name: item.tapName,
      description: item.instruction,
      scriptPath: (item as any)._scriptPath,
      packages: (item as any)._packages?.length > 0 ? (item as any)._packages : null,
      secretName: this.secretName || null
    };

    const sub = this.tapService.testTap(config).subscribe({
      next: (result) => {
        const recordCount = result.recordCount || 0;
        const testError = result.error || '';
        const aiExplanation = result.aiExplanation || '';

        if (recordCount > 0 && !testError) {
          item.recordCount = recordCount;
          item.testRecords = result.records || [];
          item.testColumns = result.columns || [];
          item.testDataType = result.dataType || 'json';
          this.saveTap(item, result);
        } else if (aiExplanation) {
          this.fixTap(item, aiExplanation, result.logs || '', testError);
        } else {
          this.setItemStatus(item, 'error');
          item.error = testError || 'Test returned 0 records';
          this.workerDone();
        }
      },
      error: (err) => {
        this.setItemStatus(item, 'error');
        item.error = 'Test failed: ' + (typeof err.error === 'string' ? err.error : err.message).substring(0, 200);
        this.workerDone();
      }
    });
    this.trackSub(sub);
  }

  private fixTap(item: BuildItem, diagnosis: string, logs: string, error: string): void {
    item.fixAttempts = (item.fixAttempts || 0) + 1;
    this.setItemStatus(item, 'fixing');
    item.statusLabel = 'Fixing (attempt ' + item.fixAttempts + '/' + this.MAX_FIX_ATTEMPTS + ')...';

    const sub = this.tapService.fixScript(
      item.tapName,
      (item as any)._script,
      diagnosis,
      logs,
      error,
      (item as any)._scriptPath
    ).subscribe({
      next: (result) => {
        (item as any)._script = result.script || (item as any)._script;
        (item as any)._scriptPath = result.scriptPath || (item as any)._scriptPath;
        (item as any)._packages = result.packages || (item as any)._packages;
        this.retestTap(item);
      },
      error: (err) => {
        this.setItemStatus(item, 'error');
        item.error = 'Fix failed: ' + (typeof err.error === 'string' ? err.error : err.message).substring(0, 200);
        this.workerDone();
      }
    });
    this.trackSub(sub);
  }

  private retestTap(item: BuildItem): void {
    this.setItemStatus(item, 'retesting');

    const config = {
      name: item.tapName,
      description: item.instruction,
      scriptPath: (item as any)._scriptPath,
      packages: (item as any)._packages?.length > 0 ? (item as any)._packages : null,
      secretName: this.secretName || null
    };

    const sub = this.tapService.testTap(config).subscribe({
      next: (result) => {
        const recordCount = result.recordCount || 0;
        const testError = result.error || '';

        if (recordCount > 0 && !testError) {
          item.recordCount = recordCount;
          item.testRecords = result.records || [];
          item.testColumns = result.columns || [];
          item.testDataType = result.dataType || 'json';
          this.saveTap(item, result);
        } else if ((item.fixAttempts || 0) < this.MAX_FIX_ATTEMPTS && (testError || result.aiExplanation)) {
          // Try fixing again
          const aiExplanation = result.aiExplanation || testError;
          this.fixTap(item, aiExplanation, result.logs || '', testError);
        } else {
          this.setItemStatus(item, 'error');
          item.error = testError || 'Test returned 0 records after ' + (item.fixAttempts || 0) + ' fix attempts';
          this.workerDone();
        }
      },
      error: (err) => {
        this.setItemStatus(item, 'error');
        item.error = 'Re-test failed: ' + (typeof err.error === 'string' ? err.error : err.message).substring(0, 200);
        this.workerDone();
      }
    });
    this.trackSub(sub);
  }

  private saveTap(item: BuildItem, testResult: any): void {
    this.setItemStatus(item, 'saving');

    const scriptPath = (item as any)._scriptPath;
    if (!scriptPath) {
      this.setItemStatus(item, 'error');
      item.error = 'Script path is missing — script generation may have failed silently';
      this.workerDone();
      return;
    }

    const tapConfig: any = {
      name: item.tapName,
      description: item.instruction,
      scriptPath: scriptPath,
      packages: (item as any)._packages?.length > 0 ? (item as any)._packages : null,
      secretName: this.secretName || null,
      targetPipeline: null,
      cronExpression: null,
      enabled: false,
      catalog: this.selectedCatalog || null,
      lastTestRunDataType: testResult.dataType || null,
      lastTestRunColumns: testResult.columns || null,
      lastTestRunRecordCount: testResult.recordCount || 0
    };

    const sub = this.tapService.createOrUpdateTap(tapConfig).subscribe({
      next: () => {
        this.setItemStatus(item, 'done');
        this.workerDone();
      },
      error: (err) => {
        this.setItemStatus(item, 'error');
        item.error = 'Save failed: ' + (typeof err.error === 'string' ? err.error : err.message).substring(0, 200);
        this.workerDone();
      }
    });
    this.trackSub(sub);
  }

  // Pipeline creation is now handled in runTap (Step 5)

  toggleScript(item: BuildItem): void {
    if (item.scriptExpanded) {
      item.scriptExpanded = false;
      return;
    }
    // Load the script from the server if not already loaded
    if (!item.editScript) {
      this.tapService.getTap(item.tapName).subscribe({
        next: (tap) => {
          item.editScript = tap.script || '';
          item.editPackages = tap.packages || [];
          item.scriptExpanded = true;
        },
        error: () => {
          // Try using the cached script from generation
          item.editScript = (item as any)._script || '(Script not available)';
          item.editPackages = (item as any)._packages || [];
          item.scriptExpanded = true;
        }
      });
    } else {
      item.scriptExpanded = true;
    }
  }

  saveAndRetest(item: BuildItem): void {
    if (item.retesting || !item.editScript) return;
    item.retesting = true;
    item.error = undefined;
    this.setItemStatus(item, 'testing');

    // Save the edited script via tap/script endpoint
    const oldPath = (item as any)._scriptPath || '';
    this.tapService.storeScript(item.tapName, item.editScript, oldPath).subscribe({
      next: (result: any) => {
        // Update scriptPath from the response
        const newPath = result?.scriptPath || oldPath;
        (item as any)._scriptPath = newPath;

        // Also update the tap config in the DB with the new scriptPath
        this.tapService.getTap(item.tapName).subscribe({
          next: (existing: any) => {
            if (existing) {
              existing.scriptPath = newPath;
              this.tapService.createOrUpdateTap(existing).subscribe();
            }
          },
          error: () => {} // ignore
        });

        // Re-test the tap
        const config = {
          name: item.tapName,
          description: item.instruction,
          scriptPath: newPath,
          packages: item.editPackages?.length ? item.editPackages : null,
          secretName: this.secretName || null
        };
        this.tapService.testTap(config).subscribe({
          next: (testResult) => {
            const recordCount = testResult.recordCount || 0;
            const testError = testResult.error || '';
            if (recordCount > 0 && !testError) {
              item.recordCount = recordCount;
              item.testRecords = testResult.records || [];
              item.testColumns = testResult.columns || [];
              item.testDataType = testResult.dataType || 'json';
              item.status = 'done';
              item.statusLabel = 'Complete';
              item.error = undefined;
              item.scriptExpanded = false;
              // Update tap config with test results
              this.tapService.getTap(item.tapName).subscribe({
                next: (existing) => {
                  existing.lastTestRunDataType = testResult.dataType || null;
                  existing.lastTestRunColumns = testResult.columns || null;
                  existing.lastTestRunRecordCount = testResult.recordCount || 0;
                  this.tapService.createOrUpdateTap(existing).subscribe();
                }
              });
            } else {
              item.status = 'error';
              item.statusLabel = 'Failed';
              item.error = testError || 'Test returned 0 records';
            }
            item.retesting = false;
          },
          error: (err) => {
            item.status = 'error';
            item.statusLabel = 'Failed';
            item.error = 'Re-test failed: ' + (typeof err.error === 'string' ? err.error : err.message).substring(0, 300);
            item.retesting = false;
          }
        });
      },
      error: (err: any) => {
        item.error = 'Failed to save script: ' + (typeof err.error === 'string' ? err.error : err.message).substring(0, 200);
        item.retesting = false;
      }
    });
  }

  retryBuildItem(item: BuildItem): void {
    if (item.retrying) return;
    item.retrying = true;
    item.error = undefined;
    item.scriptExpanded = false;
    // Track as an active worker so workerDone handles completion
    this.activeWorkers++;
    this.building = true;
    this.buildComplete = false;
    // Delete the old tap if it exists, then re-generate
    this.tapService.deleteTap(item.tapName).subscribe({
      next: () => this.generateTap(item),
      error: () => this.generateTap(item)
    });
  }

  removeBuildItem(item: BuildItem): void {
    // Delete the tap from the server, then remove from the list
    this.tapService.deleteTap(item.tapName).subscribe({
      next: () => {
        this.buildItems = this.buildItems.filter(i => i !== item);
      },
      error: () => {
        // Remove from UI even if server delete fails
        this.buildItems = this.buildItems.filter(i => i !== item);
      }
    });
  }

  completedCount(): number {
    return this.buildItems.filter(i => i.status === 'done').length;
  }

  errorCount(): number {
    return this.buildItems.filter(i => i.status === 'error').length;
  }

  // ---------- Preview Modal ----------

  showPreview(item: BuildItem): void {
    if (item.testRecords && item.testRecords.length > 0) {
      this.previewItem = item;
    }
  }

  closePreview(): void {
    this.previewItem = null;
  }

  getPreviewColumns(): string[] {
    return this.previewItem?.testColumns || [];
  }

  getPreviewRows(): any[] {
    if (!this.previewItem?.testRecords) return [];
    return this.previewItem.testRecords.slice(0, 20);
  }

  // ---------- CRON Scheduling ----------

  setCronPreset(preset: string): void {
    this.cronPreset = preset;
    switch (preset) {
      case 'hourly': this.sharedCron = '0 0 * * * ?'; break;
      case 'daily': this.sharedCron = '0 0 0 * * ?'; break;
      case 'weekdays': this.sharedCron = '0 0 0 ? * MON-FRI'; break;
      case 'weekly': this.sharedCron = '0 0 0 ? * MON'; break;
      case 'custom': this.sharedCron = ''; break;
    }
  }

  generateCronForItem(item: BuildItem): void {
    if (!(item.cronPrompt || '').trim() || item.generatingCron) return;
    item.generatingCron = true;
    this.tapService.generateCron(item.cronPrompt!).subscribe({
      next: (result) => {
        item.customCron = result.cronExpression || '';
        item.generatingCron = false;
      },
      error: () => { item.generatingCron = false; }
    });
  }

  generateCron(): void {
    if (!this.cronPrompt.trim()) return;
    this.generatingCron = true;
    this.tapService.generateCron(this.cronPrompt).subscribe({
      next: (result) => {
        this.sharedCron = result.cronExpression || '';
        this.generatingCron = false;
      },
      error: () => { this.generatingCron = false; }
    });
  }

  describeCron(expr: string): string {
    if (!expr || !expr.trim()) return '';
    const parts = expr.trim().split(/\s+/);
    if (parts.length < 6) return expr;
    const [sec, min, hour, dom, mon, dow] = parts;
    if (sec === '0' && min === '0' && hour === '0' && dom === '*' && mon === '*' && dow === '?') return 'Every day at midnight';
    if (sec === '0' && min === '0' && hour === '*' && dom === '*' && mon === '*' && dow === '?') return 'Every hour';
    if (sec === '0' && min === '0' && hour === '0' && dom === '?' && mon === '*' && dow === 'MON') return 'Every Monday at midnight';
    if (sec === '0' && min === '0' && hour === '0' && dom === '?' && mon === '*' && dow === 'MON-FRI') return 'Weekdays at midnight';
    if (hour !== '*' && hour !== '?' && !hour.includes('/')) {
      const h = parseInt(hour);
      const m = parseInt(min) || 0;
      const ampm = h >= 12 ? 'PM' : 'AM';
      const h12 = h === 0 ? 12 : h > 12 ? h - 12 : h;
      const mStr = m < 10 ? '0' + m : '' + m;
      const pieces = ['at ' + h12 + ':' + mStr + ' ' + ampm];
      if (dow !== '?' && dow !== '*') pieces.push('on ' + dow);
      return pieces.join(' ');
    }
    return expr;
  }

  // ---------- Phase 4: Create Pipelines ----------

  goToPipelines(): void {
    // Initialize pipeline state for each successful build item
    for (const item of this.runnableItems()) {
      if (item.truncateBeforeWrite === undefined) item.truncateBeforeWrite = false;
      item.pipelineStatus = 'pending';
      item.pipelineError = undefined;
      item.pipelineCreated = false;
    }
    this.creatingPipelines = false;
    this.pipelinesCreated = false;
    this.phase = 4;
  }

  applyTruncateAll(): void {
    for (const item of this.runnableItems()) {
      item.truncateBeforeWrite = this.truncateAll;
    }
  }

  createAllPipelines(): void {
    if (this.creatingPipelines) return;
    this.creatingPipelines = true;
    this.pipelinesCreated = false;
    this.error = '';
    this.activeWorkers = 0;
    this.activeSubs = [];
    this.startNextPipelineWorker();
  }

  private startNextPipelineWorker(): void {
    const item = this.runnableItems().find(i => i.pipelineStatus === 'pending');
    if (!item) {
      if (this.activeWorkers === 0) {
        this.creatingPipelines = false;
        this.pipelinesCreated = true;
      }
      return;
    }
    this.activeWorkers++;
    this.createSinglePipeline(item);
  }

  private pipelineWorkerDone(): void {
    this.activeWorkers--;
    this.startNextPipelineWorker();
  }

  private createSinglePipeline(item: BuildItem): void {
    item.pipelineStatus = 'creating';

    const tableName = item.tapName.replace(/-/g, '_');

    const source: any = { fileAttributes: {} as any };
    // Taps always return JSON → MongoDB
    source.schemaProperties = { fields: [{ name: '_json', type: 'string' }] };
    source.fileAttributes.jsonAttributes = { everyRowContainsObject: false, encoding: 'UTF-8' };

    const destination: any = {
      database: { dbName: 'DATABASE_NAME', table: tableName, useMongoDB: true, truncateBeforeWrite: !!item.truncateBeforeWrite }
    };

    const sub = this.pipelineService.createPipeline({ name: item.tapName, source, destination, catalog: this.selectedCatalog || null }).subscribe({
      next: () => {
        item.pipelineCreated = true;
        item.pipelineStatus = 'done';
        // Link tap to pipeline
        this.tapService.getTap(item.tapName).subscribe({
          next: (existing) => {
            existing.targetPipeline = item.tapName;
            this.tapService.createOrUpdateTap(existing).subscribe({
              next: () => this.pipelineWorkerDone(),
              error: () => this.pipelineWorkerDone()
            });
          },
          error: () => this.pipelineWorkerDone()
        });
      },
      error: (err) => {
        item.pipelineStatus = 'error';
        item.pipelineError = 'Failed: ' + (typeof err.error === 'string' ? err.error : err.message || 'unknown').substring(0, 200);
        this.pipelineWorkerDone();
      }
    });
    this.trackSub(sub);
  }

  // ---------- Phase 4: Edit Config Modal ----------

  openConfigEditor(item: BuildItem): void {
    const doneItems = this.runnableItems().filter(i => i.pipelineStatus === 'done');
    if (doneItems.length === 0) return;
    const startIdx = Math.max(0, doneItems.findIndex(i => i.tapName === item.tapName));

    this.editingOpen = true;
    this.editingLoading = true;
    this.editingConfigs = [];
    this.editingIndex = startIdx;

    const requests = doneItems.map(di => this.pipelineService.getPipeline(di.tapName));
    forkJoin(requests).subscribe({
      next: (configs) => {
        this.editingConfigs = configs.map((cfg, i) => ({
          tapName: doneItems[i].tapName,
          config: cfg,
          originalDq: JSON.parse(JSON.stringify(cfg.dataQuality || null)),
          originalTx: JSON.parse(JSON.stringify(cfg.transformation || null)),
          dirty: false,
          saveError: ''
        }));
        this.editingLoading = false;
      },
      error: (err) => {
        this.editingLoading = false;
        this.editingOpen = false;
        this.error = 'Failed to load pipeline configs: ' + (typeof err.error === 'string' ? err.error : err.message || 'Unknown error');
      }
    });
  }

  closeConfigEditor(): void {
    if (this.savingConfigs) return;
    this.editingOpen = false;
    this.editingConfigs = [];
    this.editingIndex = 0;
  }

  prevConfig(): void {
    if (this.editingIndex > 0) this.editingIndex--;
  }

  nextConfig(): void {
    if (this.editingIndex < this.editingConfigs.length - 1) this.editingIndex++;
  }

  onDqTxChange(value: { dataQuality: any | null; transformation: any | null }): void {
    const entry = this.editingConfigs[this.editingIndex];
    if (!entry) return;
    if (value.dataQuality) entry.config.dataQuality = value.dataQuality;
    else delete entry.config.dataQuality;
    if (value.transformation) entry.config.transformation = value.transformation;
    else delete entry.config.transformation;
    entry.dirty =
      JSON.stringify(entry.config.dataQuality || null) !== JSON.stringify(entry.originalDq) ||
      JSON.stringify(entry.config.transformation || null) !== JSON.stringify(entry.originalTx);
    entry.saveError = '';
  }

  hasDirtyConfigs(): boolean {
    return this.editingConfigs.some(e => e.dirty);
  }

  currentDqSourceType(): string {
    const entry = this.editingConfigs[this.editingIndex];
    if (!entry) return 'json';
    const src = entry.config?.source;
    if (src?.fileAttributes?.csvAttributes) return 'csv';
    if (src?.fileAttributes?.xmlAttributes) return 'xml';
    return 'json';
  }

  saveAllConfigs(): void {
    if (this.savingConfigs) return;

    const dirty = this.editingConfigs.filter(e => e.dirty);
    if (dirty.length === 0) {
      this.closeConfigEditor();
      return;
    }

    this.savingConfigs = true;
    const saves = dirty.map(e => this.pipelineService.createPipeline(e.config));
    forkJoin(saves).subscribe({
      next: () => {
        dirty.forEach(e => {
          e.originalDq = JSON.parse(JSON.stringify(e.config.dataQuality || null));
          e.originalTx = JSON.parse(JSON.stringify(e.config.transformation || null));
          e.dirty = false;
        });
        this.savingConfigs = false;
        this.editingOpen = false;
        this.editingConfigs = [];
        this.editingIndex = 0;
      },
      error: (err) => {
        this.savingConfigs = false;
        const msg = 'Failed to save: ' + (typeof err.error === 'string' ? err.error : err.message || 'Unknown error');
        const entry = this.editingConfigs[this.editingIndex];
        if (entry) entry.saveError = msg;
      }
    });
  }

  pipelinesCompletedCount(): number {
    return this.runnableItems().filter(i => i.pipelineStatus === 'done').length;
  }

  pipelinesErrorCount(): number {
    return this.runnableItems().filter(i => i.pipelineStatus === 'error').length;
  }

  // ---------- Phase 5: Schedule ----------

  goToSchedule(): void {
    this.phase = 5;
  }

  applySchedulesAndGoToRun(): void {
    this.applyingSchedules = true;
    this.error = '';

    const items = this.runnableItems();
    let completed = 0;

    if (items.length === 0) {
      this.applyingSchedules = false;
      this.goToRun();
      return;
    }

    // Fetch each tap's full config, merge CRON fields, then save back.
    // createOrUpdateTap replaces the entire config, so we must send all fields.
    for (const item of items) {
      const cron = item.customCron || (this.useSchedule ? this.sharedCron : '');
      const enabled = !!cron && this.enableSchedule;

      this.tapService.getTap(item.tapName).subscribe({
        next: (existing) => {
          existing.cronExpression = cron || null;
          existing.enabled = enabled;
          this.tapService.createOrUpdateTap(existing).subscribe({
            next: () => { completed++; if (completed === items.length) { this.applyingSchedules = false; this.goToRun(); } },
            error: () => { completed++; if (completed === items.length) { this.applyingSchedules = false; this.goToRun(); } }
          });
        },
        error: () => {
          // Can't fetch tap — skip schedule for this one
          completed++;
          if (completed === items.length) { this.applyingSchedules = false; this.goToRun(); }
        }
      });
    }
  }

  // ---------- Phase 5: Run ----------

  goToRun(): void {
    for (const item of this.buildItems) {
      if (item.status === 'done') {
        item.runStatus = 'pending';
        item.runError = undefined;
        item.runRecordCount = undefined;
      }
    }
    this.running = false;
    this.runComplete = false;
    this.runCancelled = false;
    this.phase = 6;
  }

  runnableItems(): BuildItem[] {
    return this.buildItems.filter(i => i.status === 'done');
  }

  runAll(): void {
    if (this.running) return;
    this.running = true;
    this.runComplete = false;
    this.runCancelled = false;
    this.activeRunWorkers = 0;
    this.activeSubs = [];
    this.error = '';

    const items = this.runnableItems();
    for (const item of items) {
      item.runStatus = 'pending';
    }

    // Launch all taps in parallel
    for (const item of items) {
      this.activeRunWorkers++;
      this.runTap(item);
    }
  }

  stopRun(): void {
    this.runCancelled = true;
    for (const sub of this.activeSubs) {
      sub.unsubscribe();
    }
    this.activeSubs = [];
    for (const item of this.runnableItems()) {
      if (item.runStatus !== 'done' && item.runStatus !== 'error') {
        item.runStatus = 'error';
        item.runError = 'Cancelled';
      }
    }
    this.activeRunWorkers = 0;
    this.running = false;
    this.runComplete = true;
  }


  private runWorkerDone(): void {
    this.activeRunWorkers--;
    if (this.activeRunWorkers === 0) {
      this.running = false;
      this.runComplete = true;
    }
  }

  private runTap(item: BuildItem): void {
    // Pipeline already created and linked in Step 4 — just run
    this.executeRun(item);
  }

  private executeRun(item: BuildItem): void {
    item.runStatus = 'running';
    item.runStatusLabel = 'Running and pushing to pipeline...';

    const sub = this.tapService.runTap(item.tapName, true).subscribe({
      next: (result) => {
        item.runStatus = 'done';
        item.runStatusLabel = 'Complete';
        item.runRecordCount = result?.recordCount || item.recordCount;
        this.runWorkerDone();
      },
      error: (err) => {
        item.runStatus = 'error';
        item.runStatusLabel = 'Failed';
        item.runError = 'Run failed: ' + (typeof err.error === 'string' ? err.error : err.message).substring(0, 200);
        this.runWorkerDone();
      }
    });
    this.trackSub(sub);
  }

  runSuccessCount(): number {
    return this.runnableItems().filter(i => i.runStatus === 'done').length;
  }

  runErrorCount(): number {
    return this.runnableItems().filter(i => i.runStatus === 'error').length;
  }

  goToTaps(): void {
    this.router.navigate(['/taps']);
  }

  // ---------- Navigation ----------

  backToChat(): void {
    this.phase = 1;
  }

  backToSelection(): void {
    this.phase = 2;
  }

  backToBuild(): void {
    this.phase = 3;
  }

  backToSchedule(): void {
    this.phase = 5;
  }

  backToPipelines(): void {
    this.phase = 4;
  }

  startOver(): void {
    this.phase = 1;
    this.messages = [];
    this.datasets = [];
    this.categories = [];
    this.ready = false;
    this.chatting = false;
    this.sourceIdentified = false;
    this.prefix = '';
    this.selectedCatalog = '';
    this.showNewCatalogInput = false;
    this.newCatalogName = '';
    this.secretName = '';
    this.showCreateSecret = false;
    this.editingSecret = false;
    this.newSecretName = '';
    this.newSecretFields = [{key: '', value: ''}];
    this.allSuggestedEnvVars = [];
    this.sharedParams = [];
    this.sharedParamValues = {};
    this.sharedParamSources = {};
    this.buildItems = [];
    this.buildComplete = false;
    this.building = false;
    this.buildCancelled = false;
    this.activeWorkers = 0;
    this.activeSubs = [];
    this.truncateAll = false;
    this.creatingPipelines = false;
    this.pipelinesCreated = false;
    this.useSchedule = false;
    this.sharedCron = '';
    this.cronPreset = 'custom';
    this.running = false;
    this.runComplete = false;
    this.runCancelled = false;
    this.activeRunWorkers = 0;
    this.error = '';
  }
}
