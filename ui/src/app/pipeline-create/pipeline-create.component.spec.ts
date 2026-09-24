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
import { provideHttpClientTesting } from '@angular/common/http/testing';
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
});
