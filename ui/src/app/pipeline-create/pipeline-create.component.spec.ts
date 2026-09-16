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
});
