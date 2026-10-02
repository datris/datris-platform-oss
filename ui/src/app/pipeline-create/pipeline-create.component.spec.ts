/**
 * Story: Iceberg in MCP tools, wizard and prompt wording
 * (plans/stories/iceberg-mcp-ui-prompts.md) — wizard bullets.
 *
 * Pins: on the Destination step, choosing Iceberg as the object-store format
 * reveals a Write mode select (offering `merge`) and a Key Fields multi-select
 * that mirrors Partition By; choosing Parquet hides both; a saved Iceberg
 * pipeline reopens with its write mode and key fields populated, and
 * buildConfig() emits them back — and emits neither for a parquet pipeline
 * that never set them.
 *
 * DOM contract used here (the story names no ids/classes): each new control
 * sits in a `.form-row` whose `label.form-label` text contains "Write mode" /
 * "Key Fields" (case-insensitive), and the Key Fields control is a
 * `select[multiple]` bound to the destination schema, like Partition By.
 *
 * The new component fields (osWriteMode / osKeyFields) are reached through an
 * `any` cast so this file compiles before they exist and fails on behaviour,
 * not on a TypeScript symbol error that would also mask the other specs.
 */
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { CUSTOM_ELEMENTS_SCHEMA, NO_ERRORS_SCHEMA } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { of } from 'rxjs';

import { PipelineCreateComponent } from './pipeline-create.component';
import { PipelineService } from '../pipeline.service';
import { SearchService } from '../search.service';
import { HealthService } from '../health.service';
import { TapService } from '../tap.service';

describe('PipelineCreateComponent — Iceberg object-store format', () => {
  let fixture: ComponentFixture<PipelineCreateComponent>;
  let component: PipelineCreateComponent;
  let el: HTMLElement;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      declarations: [PipelineCreateComponent],
      imports: [FormsModule],
      schemas: [CUSTOM_ELEMENTS_SCHEMA, NO_ERRORS_SCHEMA],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: PipelineService, useValue: {
            getAvailableDestinations: () => of(['postgres', 'mongodb', 'objectstore']),
            getPipelines: () => of([]),
            getPipeline: () => of({})
        } },
        { provide: SearchService, useValue: { getPipelines: () => of([]) } },
        { provide: HealthService, useValue: { isAvailable: () => true, refresh: () => Promise.resolve() } },
        { provide: TapService, useValue: { getTaps: () => of([]) } },
        { provide: ActivatedRoute, useValue: { snapshot: { queryParamMap: convertToParamMap({}), paramMap: convertToParamMap({}) } } }
      ]
    }).compileComponents();

    fixture = TestBed.createComponent(PipelineCreateComponent);
    component = fixture.componentInstance;
    el = fixture.nativeElement as HTMLElement;
    fixture.detectChanges();
  });

  /** Put the wizard on the Destination step with an object-store destination
   *  and a two-field destination schema (so schema-bound multi-selects render). */
  function onObjectStoreStep(format: string): void {
    component.sourceType = 'csv';
    component.step = 8;
    component.destType = 'objectstore';
    component.destSchemaFields = [{ name: 'id', type: 'string' }, { name: 'dt', type: 'string' }];
    component.osFormat = format;
    fixture.detectChanges();
  }

  function formRowWithLabel(re: RegExp): HTMLElement | null {
    const rows = Array.from(el.querySelectorAll('.form-row, .form-field')) as HTMLElement[];
    return rows.find(r => {
      const label = r.querySelector('label.form-label');
      return !!label && re.test((label.textContent || '').trim());
    }) || null;
  }

  it('offers Iceberg in the Format select after Parquet and ORC', () => {
    onObjectStoreStep('parquet');
    const formatRow = formRowWithLabel(/^Format$/i);
    expect(formatRow).withContext('Format select row').not.toBeNull();
    const values = Array.from(formatRow!.querySelectorAll('option')).map(o => (o as HTMLOptionElement).value);
    expect(values).toEqual(['parquet', 'orc', 'iceberg']);
  });

  it('selecting Iceberg reveals a Write mode select that includes merge', () => {
    onObjectStoreStep('iceberg');
    const row = formRowWithLabel(/write\s*mode/i);
    expect(row).withContext('Write mode row visible for iceberg').not.toBeNull();
    const select = row!.querySelector('select');
    expect(select).withContext('Write mode is a <select>').not.toBeNull();
    const values = Array.from(select!.querySelectorAll('option')).map(o => (o as HTMLOptionElement).value);
    expect(values).toContain('merge');
    expect(values).toContain('append');
  });

  it('selecting Iceberg reveals a Key Fields multi-select bound to the destination schema', () => {
    onObjectStoreStep('iceberg');
    const row = formRowWithLabel(/key\s*fields/i);
    expect(row).withContext('Key Fields row visible for iceberg').not.toBeNull();
    const select = row!.querySelector('select[multiple]') as HTMLSelectElement | null;
    expect(select).withContext('Key Fields is a multi-select').not.toBeNull();
    const values = Array.from(select!.querySelectorAll('option')).map(o => (o as HTMLOptionElement).value);
    expect(values).toEqual(['id', 'dt']);
  });

  it('selecting Parquet hides Write mode and Key Fields', () => {
    onObjectStoreStep('iceberg');
    expect(formRowWithLabel(/write\s*mode/i)).not.toBeNull();
    component.osFormat = 'parquet';
    fixture.detectChanges();
    expect(formRowWithLabel(/write\s*mode/i)).withContext('Write mode hidden for parquet').toBeNull();
    expect(formRowWithLabel(/key\s*fields/i)).withContext('Key Fields hidden for parquet').toBeNull();
    // Partition By is untouched by the format switch.
    expect(formRowWithLabel(/partition\s*by/i)).withContext('Partition By still present').not.toBeNull();
  });

  it('a saved Iceberg pipeline reopens with write mode and key fields populated', () => {
    component.loadFromConfig({
      name: 'orders',
      source: { fileAttributes: { csvAttributes: { delimiter: ',' } } },
      destination: {
        schemaProperties: { fields: [{ name: 'id', type: 'string' }, { name: 'amount', type: 'double' }] },
        objectStore: { prefixKey: 'orders/daily', fileFormat: 'iceberg', writeMode: 'merge', keyFields: ['id'] }
      }
    });
    const c = component as any;
    expect(component.destType).toBe('objectstore');
    expect(component.osFormat).toBe('iceberg');
    expect(c.osWriteMode).toBe('merge');
    expect(c.osKeyFields).toEqual(['id']);

    component.step = 8;
    fixture.detectChanges();
    const keyRow = formRowWithLabel(/key\s*fields/i);
    expect(keyRow).not.toBeNull();
    const selected = Array.from(keyRow!.querySelectorAll('option'))
      .filter(o => (o as HTMLOptionElement).selected)
      .map(o => (o as HTMLOptionElement).value);
    expect(selected).toEqual(['id']);
  });

  it('buildConfig emits writeMode and keyFields for an Iceberg pipeline', () => {
    component.loadFromConfig({
      name: 'orders',
      source: { fileAttributes: { csvAttributes: { delimiter: ',' } } },
      destination: { objectStore: { prefixKey: 'orders/daily', fileFormat: 'iceberg', writeMode: 'merge', keyFields: ['id'] } }
    });
    const os = component.buildConfig().destination.objectStore;
    expect(os.fileFormat).toBe('iceberg');
    expect(os.writeMode).toBe('merge');
    expect(os.keyFields).toEqual(['id']);
    expect(os.prefixKey).toBe('orders/daily');
  });

  it('buildConfig emits neither writeMode=merge nor keyFields for a parquet pipeline that never set them', () => {
    component.loadFromConfig({
      name: 'orders',
      source: { fileAttributes: { csvAttributes: { delimiter: ',' } } },
      destination: { objectStore: { prefixKey: 'orders/daily', fileFormat: 'parquet' } }
    });
    const os = component.buildConfig().destination.objectStore;
    expect(os.fileFormat).toBe('parquet');
    expect(os.keyFields).toBeUndefined();
    expect(os.writeMode === undefined || os.writeMode === 'append').withContext('writeMode: ' + os.writeMode).toBeTrue();
  });

  // Review-fix behaviours (round 2): the server rejects merge without keyFields
  // and keyFields without merge, so the wizard must not submit either shape.

  it('refuses to leave the Destination step for Iceberg merge with no key fields selected', () => {
    onObjectStoreStep('iceberg');
    component.osPrefix = 'orders/daily';
    (component as any).osWriteMode = 'merge';
    (component as any).osKeyFields = [];
    component.nextStep();
    expect(component.step).toBe(8);
    expect(component.error).toMatch(/key fields are required for merge/i);
  });

  it('switching a merge pipeline to append drops keyFields from buildConfig', () => {
    component.loadFromConfig({
      name: 'orders',
      source: { fileAttributes: { csvAttributes: { delimiter: ',' } } },
      destination: { objectStore: { prefixKey: 'orders/daily', fileFormat: 'iceberg', writeMode: 'merge', keyFields: ['id'] } }
    });
    (component as any).osWriteMode = 'append';
    const os = component.buildConfig().destination.objectStore;
    expect(os.fileFormat).toBe('iceberg');
    expect(os.writeMode).toBe('append');
    expect(os.keyFields).toBeUndefined();
  });
});

/**
 * Story: Scratch destination in the UI and the docs
 * (plans/stories/scratch-ui-docs.md) — pipeline-create.component.spec.ts bullet.
 *
 * Pins: the Destination Type select offers a `scratch` option (only when the
 * instance advertises it, like the other structured destinations); choosing
 * it renders no destination field rows, only the hint "Nothing is landed;
 * results expire."; buildConfig() emits `destination.scratch = {}` and neither
 * `database` nor `objectStore`; a postgres config round-trips unchanged; a
 * saved `{ destination: { scratch: {} } }` reopens with destType 'scratch'.
 */
describe('PipelineCreateComponent — Live Read destination', () => {
  let fixture: ComponentFixture<PipelineCreateComponent>;
  let component: PipelineCreateComponent;
  let el: HTMLElement;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      declarations: [PipelineCreateComponent],
      imports: [FormsModule],
      schemas: [CUSTOM_ELEMENTS_SCHEMA, NO_ERRORS_SCHEMA],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: PipelineService, useValue: {
            // The real server shape: scratch is never advertised — it follows objectstore.
            getAvailableDestinations: () => of(['postgres', 'mongodb', 'objectstore']),
            getPipelines: () => of([]),
            getPipeline: () => of({})
        } },
        { provide: SearchService, useValue: { getPipelines: () => of([]) } },
        { provide: HealthService, useValue: { isAvailable: () => true, refresh: () => Promise.resolve() } },
        { provide: TapService, useValue: { getTaps: () => of([]) } },
        { provide: ActivatedRoute, useValue: { snapshot: { queryParamMap: convertToParamMap({}), paramMap: convertToParamMap({}) } } }
      ]
    }).compileComponents();

    fixture = TestBed.createComponent(PipelineCreateComponent);
    component = fixture.componentInstance;
    el = fixture.nativeElement as HTMLElement;
    fixture.detectChanges();
  });

  function onDestinationStep(destType: string): void {
    component.sourceType = 'csv';
    component.step = 8;
    component.destType = destType;
    component.destSchemaFields = [{ name: 'id', type: 'string' }, { name: 'amount', type: 'double' }];
    fixture.detectChanges();
  }

  function destTypeSelect(): HTMLSelectElement | null {
    const rows = Array.from(el.querySelectorAll('.form-row')) as HTMLElement[];
    const row = rows.find(r => /destination\s*type/i.test((r.querySelector('label.form-label')?.textContent || '').trim()));
    return row ? (row.querySelector('select') as HTMLSelectElement | null) : null;
  }

  /** Labelled form rows/fields on the step other than the Destination Type select itself. */
  function fieldLabelsBesidesDestType(): string[] {
    return (Array.from(el.querySelectorAll('.form-row, .form-field')) as HTMLElement[])
      .map(r => (r.querySelector('label.form-label')?.textContent || '').trim())
      .filter(t => t && !/destination\s*type/i.test(t));
  }

  it('the destination select offers Live Read', () => {
    onDestinationStep('postgres');
    const select = destTypeSelect();
    expect(select).withContext('Destination Type select').not.toBeNull();
    const scratch = Array.from(select!.querySelectorAll('option'))
      .find(o => (o as HTMLOptionElement).value === 'scratch') as HTMLOptionElement | undefined;
    expect(scratch).withContext('option[value=scratch]').toBeDefined();
    expect((scratch!.textContent || '').trim()).toMatch(/^Live Read/);
    expect(scratch!.disabled).withContext('enabled when the instance advertises scratch').toBeFalse();
    expect(component.isDestAvailable('scratch')).toBeTrue();
  });

  it('Live Read is disabled when the instance does not advertise objectstore', () => {
    component.availableDestinations = ['postgres'];
    expect(component.isDestAvailable('scratch')).toBeFalse();
    onDestinationStep('postgres');
    const scratch = Array.from(destTypeSelect()!.querySelectorAll('option'))
      .find(o => (o as HTMLOptionElement).value === 'scratch') as HTMLOptionElement | undefined;
    expect(scratch).toBeDefined();
    expect(scratch!.disabled).withContext('rendered but disabled, like the other structured destinations').toBeTrue();
    expect((scratch!.textContent || '')).toContain('Not installed');
  });

  it('choosing Live Read shows no destination fields', () => {
    onDestinationStep('postgres');
    expect(fieldLabelsBesidesDestType().length).withContext('postgres renders field rows').toBeGreaterThan(0);

    onDestinationStep('scratch');
    expect(fieldLabelsBesidesDestType()).withContext('scratch renders no field rows').toEqual([]);
    expect(el.querySelectorAll('select[multiple]').length).withContext('no key-field multi-select').toBe(0);
    const step = el.textContent || '';
    expect(step).toContain('Nothing is landed.');
    expect(step).toContain('Live Read runs the pipeline and hands the rows back');
  });

  it('buildConfig emits destination.scratch = {} and no database or objectStore key', () => {
    component.loadFromConfig({
      name: 'answer-now',
      source: { fileAttributes: { csvAttributes: { delimiter: ',' } } },
      destination: { schemaProperties: { fields: [{ name: 'id', type: 'string' }] } }
    });
    component.destType = 'scratch';
    const dest = component.buildConfig().destination;
    expect(dest.scratch).toEqual({});
    expect(dest.database).toBeUndefined();
    expect(dest.objectStore).toBeUndefined();
    expect(dest.kafka).toBeUndefined();
    // The server requires the sample: schemaProperties is left alone.
    expect(dest.schemaProperties?.fields?.map((f: any) => f.name)).toEqual(['id']);
  });

  it('a postgres config is unchanged', () => {
    component.loadFromConfig({
      name: 'orders',
      source: { fileAttributes: { csvAttributes: { delimiter: ',' } } },
      destination: {
        schemaProperties: { fields: [{ name: 'id', type: 'string' }] },
        database: { dbName: 'datris', schema: 'public', table: 'orders', usePostgres: true, truncateBeforeWrite: false, keyFields: ['id'] }
      }
    });
    expect(component.destType).toBe('postgres');
    const dest = component.buildConfig().destination;
    expect(dest.scratch).toBeUndefined();
    expect(dest.database).toEqual(jasmine.objectContaining({
      dbName: 'datris', schema: 'public', table: 'orders', usePostgres: true, truncateBeforeWrite: false, keyFields: ['id']
    }));
  });

  it('a saved scratch pipeline reopens as Scratch', () => {
    component.loadFromConfig({
      name: 'answer-now',
      source: { fileAttributes: { csvAttributes: { delimiter: ',' } } },
      destination: {
        schemaProperties: { fields: [{ name: 'id', type: 'string' }] },
        scratch: {}
      }
    });
    expect(component.destType).toBe('scratch');

    component.step = 8;
    fixture.detectChanges();
    const select = destTypeSelect();
    expect(select).not.toBeNull();
    const selected = Array.from(select!.querySelectorAll('option'))
      .find(o => (o as HTMLOptionElement).selected) as HTMLOptionElement | undefined;
    expect(selected?.value).toBe('scratch');
    // Round-trips: saving again still emits scratch and nothing else.
    const dest = component.buildConfig().destination;
    expect(dest.scratch).toEqual({});
    expect(dest.database).toBeUndefined();
  });

  // Catalog rename and delete story: catalog names keep their case everywhere
  // in the UI (sanitizeCatalogName), including the wizard's inline "new catalog".
  it('inline new catalog keeps case (sanitizeCatalogName)', () => {
    const c: any = component;
    c.availableCatalogs = [];
    c.newCatalogName = '  Sales Q3! ';
    c.confirmNewCatalog();
    expect(c.catalog).toBe('Sales_Q3');
    expect(c.availableCatalogs).toContain('Sales_Q3');
  });
  it('inline new catalog refuses a case-only twin and shows the message under the field', () => {
    const c: any = component;
    c.availableCatalogs = ['DatrisFund', 'e2e_a'];
    c.onCatalogChange('__new__');
    c.catalog = '__new__';
    c.newCatalogName = 'datrisfund';
    c.confirmNewCatalog();
    fixture.detectChanges();
    expect(c.catalog).not.toBe('datrisfund');
    expect(c.availableCatalogs).toEqual(['DatrisFund', 'e2e_a']);
    expect(c.showNewCatalog).toBeTrue();
    expect(c.newCatalogName).toBe('datrisfund');
    const msg = "'DatrisFund' already exists with different capitalisation. Catalog names are case-sensitive, so this would create a second catalog.";
    expect(c.newCatalogError).toBe(msg);
    expect(el.querySelector('.error-hint')?.textContent || '').toContain(msg);
  });

  it('inline new catalog with an exact existing name selects it', () => {
    const c: any = component;
    c.availableCatalogs = ['DatrisFund', 'e2e_a'];
    c.onCatalogChange('__new__');
    c.newCatalogName = 'DatrisFund';
    c.confirmNewCatalog();
    expect(c.catalog).toBe('DatrisFund');
    expect(c.availableCatalogs).toEqual(['DatrisFund', 'e2e_a']);
    expect(c.showNewCatalog).toBeFalse();
    expect(c.newCatalogError).toBe('');
  });
  it('a placeholder-only legacy catalog (empty catalog field) is listed and its case twin is refused', () => {
    const c: any = component;
    const tapSvc: any = TestBed.inject(TapService);
    tapSvc.getTaps = () => of([
      { name: '__catalog__e2e_nm_e' },
      { name: '__catalog__DatrisFund', catalog: 'DatrisFund' },
      { name: 'real_tap', catalog: 'e2e_a' }
    ]);
    component.ngOnInit();
    expect(c.availableCatalogs).toEqual(['DatrisFund', 'e2e_a', 'e2e_nm_e']);
    expect(c.availableCatalogs.some((n: string) => n.startsWith('__catalog__'))).toBeFalse();
    c.onCatalogChange('__new__');
    c.newCatalogName = 'E2E_NM_E';
    c.confirmNewCatalog();
    expect(c.catalog).not.toBe('E2E_NM_E');
    expect(c.newCatalogError).toContain("'e2e_nm_e' already exists with different capitalisation.");
    expect(c.availableCatalogs).toEqual(['DatrisFund', 'e2e_a', 'e2e_nm_e']);
  });
});

/**
 * Story: Field protection 4 — Protect column in the wizard schema step +
 * Suggest button (plans/stories/field-protection-4-wizard-ui.md), Acceptance
 * bullet 1 (the nine pipeline-create.component.spec.ts cases), plus the
 * story-3 rule that a keyFields column may carry only hmac.
 *
 * DOM contract (from the story's Files section): on the Source Schema step
 * (step 3) each `.field-row` holds a `select.field-protect` (None + hmac, mask,
 * redact, drop) and, only when its method is mask, a `select.field-preserve`
 * (Mask all, last 4, email domain, year). A suggested field shows a
 * `.field-suggestion` line carrying the reason; a type-guard reset shows a
 * `.field-error` line. The "Suggest protection" control is a `button` whose
 * text is "Suggest protection". Options are read by their visible text, so
 * either `[value]` or `[ngValue]` bindings satisfy the contract; an option the
 * user cannot pick (removed or `disabled`) counts as not offered.
 *
 * New members (protect / suggested on SchemaField, onProtectChange,
 * suggestProtection, keepSuggestion, clearSuggestion, preserveOptions,
 * PipelineService.suggestFieldProtection) are reached through `any` so this
 * file compiles before they exist and fails on behaviour.
 */
describe('PipelineCreateComponent — field protection', () => {
  let fixture: ComponentFixture<PipelineCreateComponent>;
  let component: PipelineCreateComponent;
  let el: HTMLElement;
  let suggestSpy: jasmine.Spy;

  const SUGGEST_RESPONSE = {
    model: 'claude-opus-5-5',
    fields: [
      { name: 'mrn', type: 'string', current: null, suggested: { method: 'hmac', preserve: null }, reason: 'stable identifier' },
      { name: 'email', type: 'string', current: null, suggested: { method: 'mask', preserve: 'domain' }, reason: 'contact address; the domain is still useful' },
      { name: 'notes', type: 'string', current: { method: 'drop' }, suggested: { method: 'redact', preserve: null }, reason: 'free text' },
      { name: 'visit_count', type: 'int', current: null, suggested: null, reason: 'a count' }
    ]
  };

  beforeEach(async () => {
    suggestSpy = jasmine.createSpy('suggestFieldProtection').and.returnValue(of(SUGGEST_RESPONSE));
    await TestBed.configureTestingModule({
      declarations: [PipelineCreateComponent],
      imports: [FormsModule],
      schemas: [CUSTOM_ELEMENTS_SCHEMA, NO_ERRORS_SCHEMA],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: PipelineService, useValue: {
            getAvailableDestinations: () => of(['postgres', 'mongodb', 'objectstore', 'snowflake', 'databricks']),
            getPipelines: () => of([]),
            getPipeline: () => of({}),
            suggestFieldProtection: suggestSpy
        } },
        { provide: SearchService, useValue: { getPipelines: () => of([]) } },
        { provide: HealthService, useValue: { isAvailable: () => true, refresh: () => Promise.resolve() } },
        { provide: TapService, useValue: { getTaps: () => of([]) } },
        { provide: ActivatedRoute, useValue: { snapshot: { queryParamMap: convertToParamMap({}), paramMap: convertToParamMap({}) } } }
      ]
    }).compileComponents();

    fixture = TestBed.createComponent(PipelineCreateComponent);
    component = fixture.componentInstance;
    el = fixture.nativeElement as HTMLElement;
    fixture.detectChanges();
  });

  function csvConfig(fields: any[], destination: any = {}): any {
    return {
      name: 'patients',
      source: { fileAttributes: { csvAttributes: { delimiter: ',' } }, schemaProperties: { dbName: 'datris', fields } },
      destination
    };
  }

  /** Load a CSV pipeline through the edit-mode entry point and show the Source Schema step. */
  async function onSchemaStep(fields: any[], destination: any = {}): Promise<void> {
    component.loadFromConfig(csvConfig(fields, destination));
    component.step = 3;
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  function fieldRows(): HTMLElement[] {
    return Array.from(el.querySelectorAll('.field-row')) as HTMLElement[];
  }

  function rowFor(name: string): HTMLElement {
    const row = fieldRows().find(r => (r.querySelector('input.field-name') as HTMLInputElement | null)?.value === name);
    expect(row).withContext('field row for ' + name).toBeDefined();
    return row!;
  }

  /** Visible, pickable option texts of a select. */
  function offered(select: Element | null): string[] {
    return Array.from(select?.querySelectorAll('option') || [])
      .filter(o => !(o as HTMLOptionElement).disabled)
      .map(o => (o.textContent || '').trim());
  }

  function selectedText(select: Element | null): string {
    const opt = Array.from(select?.querySelectorAll('option') || []).find(o => (o as HTMLOptionElement).selected);
    return (opt?.textContent || '').trim();
  }

  /** What actually goes over the wire (drops undefined-valued keys). */
  function wire(v: any): any {
    return JSON.parse(JSON.stringify(v));
  }

  function keyFieldsOptions(): string[] {
    const rows = Array.from(el.querySelectorAll('.form-row, .form-field')) as HTMLElement[];
    const row = rows.find(r => /key\s*fields/i.test((r.querySelector('label.form-label')?.textContent || '').trim()));
    expect(row).withContext('Key Fields row on the destination step').toBeDefined();
    // Option text, not value: the ngModel multi-select accessor rewrites values to "0: 'id'".
    return Array.from(row!.querySelectorAll('select[multiple] option')).map(o => (o.textContent || '').trim());
  }

  it('schema step renders a Protect select per field', async () => {
    await onSchemaStep([{ name: 'mrn', type: 'string' }, { name: 'email', type: 'string' }, { name: 'visit_count', type: 'int' }]);
    const rows = fieldRows();
    expect(rows.length).toBe(3);
    for (const r of rows) {
      const sel = r.querySelector('select.field-protect');
      expect(sel).withContext('select.field-protect in every field row').not.toBeNull();
      const texts = offered(sel).map(t => t.toLowerCase());
      expect(texts.length).withContext('None + four methods: ' + texts.join(',')).toBe(5);
      expect(texts[0]).toMatch(/^none/);
      expect(texts.some(t => /^hmac/.test(t))).withContext('hmac offered').toBeTrue();
      expect(texts.some(t => /^mask/.test(t))).withContext('mask offered').toBeTrue();
      expect(texts.some(t => /^redact/.test(t))).withContext('redact offered').toBeTrue();
      expect(texts.some(t => /^drop/.test(t))).withContext('drop offered').toBeTrue();
      // A pipeline without protect shows None everywhere (backward compat).
      expect(selectedText(sel).toLowerCase()).toMatch(/^none/);
    }
  });

  it('choosing mask reveals a Keep select', async () => {
    await onSchemaStep([{ name: 'mrn', type: 'string' }, { name: 'email', type: 'string' }]);
    expect(el.querySelector('select.field-preserve')).withContext('no Keep select while nothing is masked').toBeNull();

    const c: any = component;
    const email = c.schemaFields[1];
    email.protect = { ...(email.protect || {}), method: 'mask' };
    c.onProtectChange(email);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(rowFor('mrn').querySelector('select.field-preserve')).withContext('unmasked row has no Keep select').toBeNull();
    const keep = rowFor('email').querySelector('select.field-preserve');
    expect(keep).withContext('masked row shows select.field-preserve').not.toBeNull();
    const texts = offered(keep).map(t => t.toLowerCase());
    expect(texts.length).toBe(4);
    expect(texts[0]).toMatch(/mask(ed)? all|all masked/);
    expect(texts.some(t => /last\s*4/.test(t))).toBeTrue();
    expect(texts.some(t => /domain/.test(t))).toBeTrue();
    expect(texts.some(t => /year/.test(t))).toBeTrue();
    expect((c.preserveOptions || []).map((o: any) => o.value)).toEqual([null, 'last4', 'domain', 'year']);
    expect(c.protectMethods).toEqual(['hmac', 'mask', 'redact', 'drop']);
  });

  it('buildConfig emits protect only for fields with a method and omits preserve when null', () => {
    component.loadFromConfig(csvConfig([{ name: 'id', type: 'string' }]));
    const c: any = component;
    c.schemaFields = [
      { name: 'mrn', type: 'string', protect: { method: 'hmac', preserve: null } },
      { name: 'email', type: 'string', protect: { method: 'mask', preserve: 'domain' } },
      { name: 'card', type: 'string', protect: { method: 'mask', preserve: null } },
      { name: 'ssn', type: 'string', protect: { method: 'drop' } },
      { name: 'city', type: 'string', protect: { method: '', preserve: null } },
      { name: 'zip', type: 'string', protect: { method: null } },
      { name: 'visits', type: 'int' }
    ];
    const fields = wire(component.buildConfig()).source.schemaProperties.fields;
    expect(fields).toEqual([
      { name: 'mrn', type: 'string', protect: { method: 'hmac' } },
      { name: 'email', type: 'string', protect: { method: 'mask', preserve: 'domain' } },
      { name: 'card', type: 'string', protect: { method: 'mask' } },
      { name: 'ssn', type: 'string', protect: { method: 'drop' } },
      { name: 'city', type: 'string' },
      { name: 'zip', type: 'string' },
      { name: 'visits', type: 'int' }
    ]);
  });

  it('a pipeline without protect saves the same source fields as today (no protect key anywhere)', async () => {
    const original = [{ name: 'id', type: 'string' }, { name: 'amount', type: 'double' }];
    await onSchemaStep(original);
    const cfg = wire(component.buildConfig());
    expect(cfg.source.schemaProperties.fields).toEqual(original);
    expect(JSON.stringify(cfg)).not.toContain('protect');
    expect(JSON.stringify(cfg)).not.toContain('suggested');
  });

  it('a saved pipeline reopens with its protect values', async () => {
    const saved = [
      { name: 'mrn', type: 'string', protect: { method: 'hmac' } },
      { name: 'email', type: 'string', protect: { method: 'mask', preserve: 'domain' } },
      { name: 'ssn', type: 'string', protect: { method: 'drop' } },
      { name: 'city', type: 'string' }
    ];
    await onSchemaStep(saved);
    const c: any = component;
    expect(c.schemaFields[0].protect?.method).toBe('hmac');
    expect(c.schemaFields[1].protect?.method).toBe('mask');
    expect(c.schemaFields[1].protect?.preserve).toBe('domain');
    expect(c.schemaFields[2].protect?.method).toBe('drop');
    expect(c.schemaFields[3].protect?.method || null).toBeNull();

    expect(selectedText(rowFor('mrn').querySelector('select.field-protect')).toLowerCase()).toMatch(/^hmac/);
    expect(selectedText(rowFor('email').querySelector('select.field-protect')).toLowerCase()).toMatch(/^mask/);
    expect(selectedText(rowFor('email').querySelector('select.field-preserve')).toLowerCase()).toMatch(/domain/);
    expect(selectedText(rowFor('ssn').querySelector('select.field-protect')).toLowerCase()).toMatch(/^drop/);
    expect(selectedText(rowFor('city').querySelector('select.field-protect')).toLowerCase()).toMatch(/^none/);

    // Saving untouched round-trips the same fields.
    expect(wire(component.buildConfig()).source.schemaProperties.fields).toEqual(saved);
  });

  it('hmac on an int field resets to None and shows an error', async () => {
    await onSchemaStep([{ name: 'mrn', type: 'string' }, { name: 'visit_count', type: 'int' }]);
    const c: any = component;
    const f = c.schemaFields[1];
    f.protect = { ...(f.protect || {}), method: 'hmac' };
    c.onProtectChange(f);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(f.protect?.method || null).withContext('method reset to None').toBeNull();
    expect(typeof f.protectError === 'string' && f.protectError.length > 0).withContext('protectError set').toBeTrue();
    const err = Array.from(el.querySelectorAll('.field-error')).map(e => (e.textContent || '').trim()).join(' ');
    expect(err).withContext('inline .field-error line').toContain(f.protectError);
    expect(selectedText(rowFor('visit_count').querySelector('select.field-protect')).toLowerCase()).toMatch(/^none/);
    expect(wire(component.buildConfig()).source.schemaProperties.fields[1].protect).toBeUndefined();

    // drop is allowed on a non-string field.
    f.protect = { ...(f.protect || {}), method: 'drop' };
    c.onProtectChange(f);
    expect(f.protect.method).toBe('drop');
    expect(f.protectError || '').toBe('');
  });

  it('a key-field column offers only None and hmac', async () => {
    await onSchemaStep(
      [{ name: 'account_no', type: 'string' }, { name: 'email', type: 'string' }],
      {
        schemaProperties: { fields: [{ name: 'account_no', type: 'string' }, { name: 'email', type: 'string' }] },
        database: { dbName: 'datris', schema: 'public', table: 'accounts', usePostgres: true, keyFields: ['account_no'] }
      }
    );
    expect(component.destType).toBe('postgres');
    const keyTexts = offered(rowFor('account_no').querySelector('select.field-protect')).map(t => t.toLowerCase());
    expect(keyTexts.length).withContext('key column offers: ' + keyTexts.join(',')).toBe(2);
    expect(keyTexts[0]).toMatch(/^none/);
    expect(keyTexts[1]).toMatch(/^hmac/);
    // A non-key column still offers everything.
    expect(offered(rowFor('email').querySelector('select.field-protect')).length).toBe(5);
  });

  it('a dropped field is absent from the Key Fields select', () => {
    const dests: Array<[string, () => void]> = [
      ['postgres', () => {}],
      ['snowflake', () => {}],
      ['databricks', () => {}],
      ['mongodb', () => {}],
      ['objectstore', () => { component.osFormat = 'iceberg'; (component as any).osWriteMode = 'merge'; }]
    ];
    for (const [destType, extra] of dests) {
      component.loadFromConfig(csvConfig([
        { name: 'id', type: 'string' },
        { name: 'ssn', type: 'string', protect: { method: 'drop' } },
        { name: 'email', type: 'string', protect: { method: 'hmac' } }
      ], { schemaProperties: { fields: [{ name: 'id', type: 'string' }, { name: 'ssn', type: 'string' }, { name: 'email', type: 'string' }] } }));
      component.sourceType = 'csv';
      component.step = 8;
      component.destType = destType;
      extra();
      fixture.detectChanges();
      expect(keyFieldsOptions()).withContext(destType + ' Key Fields').toEqual(['id', 'email']);
    }
    expect((component as any).keyFieldCandidates.map((f: any) => f.name ?? f)).toEqual(['id', 'email']);
  });

  it('Suggest protection calls the service with names and types only and marks fields as suggested', async () => {
    await onSchemaStep([
      { name: 'mrn', type: 'string' },
      { name: 'email', type: 'string' },
      { name: 'notes', type: 'string', protect: { method: 'drop' } },
      { name: 'visit_count', type: 'int' }
    ]);
    const btn = (Array.from(el.querySelectorAll('button')) as HTMLButtonElement[])
      .find(b => /^suggest protection$/i.test((b.textContent || '').trim()));
    expect(btn).withContext('"Suggest protection" button on the schema step').toBeDefined();
    expect(btn!.disabled).toBeFalse();
    expect(el.textContent || '').toContain('Sends field names and types only, never data.');
    btn!.click();
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(suggestSpy).toHaveBeenCalledTimes(1);
    expect(suggestSpy.calls.mostRecent().args[0]).toEqual([
      { name: 'mrn', type: 'string' },
      { name: 'email', type: 'string' },
      { name: 'notes', type: 'string' },
      { name: 'visit_count', type: 'int' }
    ]);

    const f: any[] = (component as any).schemaFields;
    expect(f[0].suggested).toEqual(jasmine.objectContaining({ method: 'hmac', reason: 'stable identifier' }));
    expect(f[0].protect?.method).toBe('hmac');
    expect(f[1].suggested).toEqual(jasmine.objectContaining({ method: 'mask', preserve: 'domain' }));
    expect(f[1].protect?.method).toBe('mask');
    expect(f[1].protect?.preserve).toBe('domain');
    // A value the user already chose is not overwritten by the suggestion.
    expect(f[2].suggested).toEqual(jasmine.objectContaining({ method: 'redact', reason: 'free text' }));
    expect(f[2].protect?.method).toBe('drop');
    // No suggestion -> no marker, no protect.
    expect(f[3].suggested).toBeFalsy();
    expect(f[3].protect?.method || null).toBeNull();
    expect((component as any).suggestingProtection).toBeFalse();

    const lines = Array.from(el.querySelectorAll('.field-suggestion')).map(e => (e.textContent || '').trim());
    expect(lines.length).withContext('one .field-suggestion per suggested field').toBe(3);
    expect(lines.join(' ')).toContain('stable identifier');
    expect(lines.join(' ')).toContain('contact address; the domain is still useful');
  });

  it('Clear removes a suggestion and its protect value; Keep removes only the suggested marker', async () => {
    await onSchemaStep([{ name: 'mrn', type: 'string' }, { name: 'email', type: 'string' }]);
    const c: any = component;
    c.suggestProtection();
    fixture.detectChanges();
    expect(el.querySelectorAll('.field-suggestion').length).toBe(2);

    c.keepSuggestion(0);
    c.clearSuggestion(1);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(c.schemaFields[0].suggested).toBeFalsy();
    expect(c.schemaFields[0].protect?.method).toBe('hmac');
    expect(c.schemaFields[1].suggested).toBeFalsy();
    expect(c.schemaFields[1].protect?.method || null).toBeNull();
    expect(el.querySelectorAll('.field-suggestion').length).toBe(0);
    expect(wire(component.buildConfig()).source.schemaProperties.fields).toEqual([
      { name: 'mrn', type: 'string', protect: { method: 'hmac' } },
      { name: 'email', type: 'string' }
    ]);
  });

  it('Keep adopts a suggestion over a value the user had already chosen', async () => {
    await onSchemaStep([
      { name: 'mrn', type: 'string' },
      { name: 'notes', type: 'string', protect: { method: 'drop' } }
    ]);
    const c: any = component;
    c.suggestProtection();
    // The user's drop survives Suggest; the redact suggestion is only shown.
    expect(c.schemaFields[1].protect?.method).toBe('drop');
    expect(c.schemaFields[1].suggested).toEqual(jasmine.objectContaining({ method: 'redact' }));

    c.keepSuggestion(1);
    fixture.detectChanges();
    expect(c.schemaFields[1].suggested).toBeFalsy();
    expect(c.schemaFields[1].protect?.method).toBe('redact');
    expect(wire(component.buildConfig()).source.schemaProperties.fields).toEqual([
      { name: 'mrn', type: 'string', protect: { method: 'hmac' } },
      { name: 'notes', type: 'string', protect: { method: 'redact' } }
    ]);
  });

  it('an iceberg key field outside merge mode is not treated as a key column', async () => {
    await onSchemaStep([{ name: 'email', type: 'string' }]);
    const c: any = component;
    c.destType = 'objectstore';
    c.osFormat = 'iceberg';
    c.osKeyFields = ['email'];
    c.osWriteMode = 'merge';
    expect(c.isKeyField('email')).toBeTrue();
    c.osWriteMode = 'append';
    expect(c.isKeyField('email')).toBeFalse();
    const f = c.schemaFields[0];
    f.protect = { method: 'mask', preserve: null };
    c.onProtectChange(f);
    expect(f.protect.method).toBe('mask');
    expect(f.protectError || '').toBe('');
  });

  /** Pick an option the way a user does: set the select's DOM value and fire change. */
  async function pick(select: HTMLSelectElement, value: string): Promise<void> {
    select.value = value;
    select.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  it('a refused choice made in the select resets the select itself to None (type guard)', async () => {
    await onSchemaStep([{ name: 'mrn', type: 'string' }, { name: 'age', type: 'int' }]);
    const c: any = component;
    const sel = rowFor('age').querySelector('select.field-protect') as HTMLSelectElement;
    await pick(sel, 'hmac');

    expect(c.schemaFields[1].protect?.method || null).toBeNull();
    expect(c.schemaFields[1].protectError).toContain('hmac applies only to string fields');
    const after = rowFor('age').querySelector('select.field-protect') as HTMLSelectElement;
    expect(after.value).withContext('select DOM value after the refused choice').toBe('');
    expect(selectedText(after).toLowerCase()).toMatch(/^none/);
    expect(el.textContent || '').toContain('hmac applies only to string fields; age is int.');

    // A second refused pick of the same method still resets the select.
    await pick(after, 'hmac');
    expect((rowFor('age').querySelector('select.field-protect') as HTMLSelectElement).value).toBe('');
  });

  it('a refused choice made in the select resets the select itself to None (key column)', async () => {
    await onSchemaStep(
      [{ name: 'account_no', type: 'string' }],
      {
        schemaProperties: { fields: [{ name: 'account_no', type: 'string' }] },
        database: { dbName: 'datris', schema: 'public', table: 'accounts', usePostgres: true, keyFields: ['account_no'] }
      }
    );
    const c: any = component;
    // The option is disabled in the UI; force it through the DOM anyway.
    const sel = rowFor('account_no').querySelector('select.field-protect') as HTMLSelectElement;
    await pick(sel, 'mask');

    expect(c.schemaFields[0].protect?.method || null).toBeNull();
    expect(c.schemaFields[0].protectError).toContain('key field');
    const after = rowFor('account_no').querySelector('select.field-protect') as HTMLSelectElement;
    expect(after.value).withContext('select DOM value after the refused choice').toBe('');
    expect(selectedText(after).toLowerCase()).toMatch(/^none/);
    expect(rowFor('account_no').querySelector('select.field-preserve')).toBeNull();

    // hmac is still accepted on the key column.
    await pick(after, 'hmac');
    expect(c.schemaFields[0].protect?.method).toBe('hmac');
    expect((rowFor('account_no').querySelector('select.field-protect') as HTMLSelectElement).value).toBe('hmac');
  });

  it('Keep keeps the user value when the adopted suggestion is refused', async () => {
    await onSchemaStep(
      [{ name: 'account_no', type: 'string', protect: { method: 'hmac' } }, { name: 'visits', type: 'int', protect: { method: 'drop' } }],
      {
        schemaProperties: { fields: [{ name: 'account_no', type: 'string' }, { name: 'visits', type: 'int' }] },
        database: { dbName: 'datris', schema: 'public', table: 'accounts', usePostgres: true }
      }
    );
    const c: any = component;
    // Unapplied suggestions that became invalid after Suggest ran.
    c.schemaFields[0].suggested = { method: 'mask', preserve: 'last4', reason: 'r', applied: false };
    c.schemaFields[1].suggested = { method: 'redact', preserve: null, reason: 'r', applied: false };
    c.pgKeyFields = ['account_no'];

    c.keepSuggestion(0);
    c.keepSuggestion(1);
    fixture.detectChanges();

    expect(c.schemaFields[0].protect.method).toBe('hmac');
    expect(c.schemaFields[0].protectError).toContain('key field');
    expect(c.schemaFields[0].suggested).toBeFalsy();
    expect(c.schemaFields[1].protect.method).toBe('drop');
    expect(c.schemaFields[1].protectError).toContain('applies only to string fields');
    expect(wire(component.buildConfig()).source.schemaProperties.fields).toEqual([
      { name: 'account_no', type: 'string', protect: { method: 'hmac' } },
      { name: 'visits', type: 'int', protect: { method: 'drop' } }
    ]);
  });

  it('a suggestion the guard would refuse is shown with its reason and offers no Keep', async () => {
    suggestSpy.and.returnValue(of({
      model: 'm',
      fields: [
        { name: 'visits', type: 'int', current: { method: 'drop' }, suggested: { method: 'redact', preserve: null }, reason: 'free text' },
        { name: 'age', type: 'int', current: null, suggested: { method: 'hmac', preserve: null }, reason: 'identifier' },
        { name: 'account_no', type: 'string', current: null, suggested: { method: 'mask', preserve: 'last4' }, reason: 'account number' }
      ]
    }));
    await onSchemaStep(
      [{ name: 'visits', type: 'int', protect: { method: 'drop' } }, { name: 'age', type: 'int' }, { name: 'account_no', type: 'string' }],
      {
        schemaProperties: { fields: [{ name: 'visits', type: 'int' }, { name: 'age', type: 'int' }, { name: 'account_no', type: 'string' }] },
        database: { dbName: 'datris', schema: 'public', table: 'accounts', usePostgres: true, keyFields: ['account_no'] }
      }
    );
    const c: any = component;
    c.suggestProtection();
    fixture.detectChanges();

    const f: any[] = c.schemaFields;
    expect(f[0].protect.method).toBe('drop');
    expect(f[1].protect.method || null).toBeNull();
    expect(f[2].protect.method || null).toBeNull();
    for (const x of f) {
      expect(x.suggested?.method || null).withContext(x.name + ' has no Keep target').toBeNull();
      expect(x.suggested?.refused?.problem).withContext(x.name + ' refusal reason').toBeTruthy();
      expect(x.protectError || '').toBe('');
    }
    const lines = Array.from(el.querySelectorAll('.field-suggestion')) as HTMLElement[];
    expect(lines.length).toBe(3);
    for (const line of lines) {
      const buttons = Array.from(line.querySelectorAll('button')).map(b => (b.textContent || '').trim());
      expect(buttons).not.toContain('Keep');
      expect(line.textContent || '').toContain('not applied');
    }
    expect(lines[1].textContent || '').toContain('hmac applies only to string fields; age is int.');
    expect(lines[2].textContent || '').toContain('account_no is a key field');

    c.clearSuggestion(1);
    expect(f[1].suggested).toBeFalsy();
    expect(f[1].protect.method || null).toBeNull();
  });

  it('json source shows no Protect select', async () => {
    // Same wizard, CSV first: the Protect column and the Suggest button are there...
    await onSchemaStep([{ name: 'mrn', type: 'string' }]);
    expect(el.querySelector('select.field-protect')).withContext('csv shows the Protect select').not.toBeNull();
    // ...and a JSON pipeline (single _json field) shows neither.
    component.loadFromConfig({
      name: 'events',
      source: { fileAttributes: { jsonAttributes: { everyRowContainsObject: true } }, schemaProperties: { fields: [{ name: '_json', type: 'string' }] } },
      destination: {}
    });
    expect(component.sourceType).toBe('json');
    component.step = 3;
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    expect(el.querySelector('select.field-protect')).toBeNull();
    const btn = (Array.from(el.querySelectorAll('button')) as HTMLButtonElement[])
      .find(b => /suggest protection/i.test(b.textContent || ''));
    expect(btn).withContext('no Suggest button for json').toBeUndefined();
    expect(JSON.stringify(wire(component.buildConfig()))).not.toContain('protect');
  });
});

describe('PipelineService.suggestFieldProtection', () => {
  it('posts {fields} to /api/v1/pipeline/protect/suggest', () => {
    TestBed.configureTestingModule({ providers: [PipelineService, provideHttpClient(), provideHttpClientTesting()] });
    const svc: any = TestBed.inject(PipelineService);
    const http = TestBed.inject(HttpTestingController);
    let got: any = null;
    const fields = [{ name: 'mrn', type: 'string' }];
    expect(typeof svc.suggestFieldProtection).withContext('PipelineService.suggestFieldProtection').toBe('function');
    svc.suggestFieldProtection(fields).subscribe((r: any) => got = r);
    const req = http.expectOne('/api/v1/pipeline/protect/suggest');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ fields });
    req.flush({ model: 'm', fields: [] });
    expect(got).toEqual({ model: 'm', fields: [] });
    http.verify();
  });
});
