/**
 * Story: Microsoft SQL Server destination (plans/stories/mssql-destination.md),
 * Acceptance bullet 8 (wizard).
 *
 *  - The SQL Server option shows "— Not installed" (disabled) when the
 *    instance does not advertise `sqlserver` (no Platform secret).
 *  - With a secret, a CSV pipeline saves `destination.database.useSqlServer: true`
 *    (reopen + build round-trip, so the spec does not pin the form's field names).
 *  - Switching the source to JSON keeps SQL Server selected; Postgres still
 *    flips to MongoDB. The option is offered for csv, json and xml sources.
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

function setup(available: string[]) {
  return async () => {
    await TestBed.configureTestingModule({
      declarations: [PipelineCreateComponent],
      imports: [FormsModule],
      schemas: [CUSTOM_ELEMENTS_SCHEMA, NO_ERRORS_SCHEMA],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: PipelineService, useValue: {
            getAvailableDestinations: () => of(available),
            getPipelines: () => of([]),
            getPipeline: () => of({})
        } },
        { provide: SearchService, useValue: { getPipelines: () => of([]) } },
        { provide: HealthService, useValue: { isAvailable: () => true, refresh: () => Promise.resolve() } },
        { provide: TapService, useValue: { getTaps: () => of([]) } },
        { provide: ActivatedRoute, useValue: { snapshot: { queryParamMap: convertToParamMap({}), paramMap: convertToParamMap({}) } } }
      ]
    }).compileComponents();
  };
}

function destTypeSelect(el: HTMLElement): HTMLSelectElement | null {
  const rows = Array.from(el.querySelectorAll('.form-row')) as HTMLElement[];
  const row = rows.find(r => /destination\s*type/i.test((r.querySelector('label.form-label')?.textContent || '').trim()));
  return row ? (row.querySelector('select') as HTMLSelectElement | null) : null;
}

function sqlServerOption(el: HTMLElement): HTMLOptionElement | undefined {
  const select = destTypeSelect(el);
  if (!select) return undefined;
  return Array.from(select.querySelectorAll('option'))
    .find(o => (o as HTMLOptionElement).value === 'sqlserver') as HTMLOptionElement | undefined;
}

const sqlServerCsvConfig = {
  name: 'orders',
  source: {
    fileAttributes: { csvAttributes: { delimiter: ',', header: true } },
    schemaProperties: { fields: [{ name: 'id', type: 'string' }, { name: 'amount', type: 'double' }] }
  },
  destination: {
    schemaProperties: { fields: [{ name: 'id', type: 'string' }, { name: 'amount', type: 'double' }] },
    database: {
      useSqlServer: true, credentialsSecret: 'mssql', dbName: 'datris', schema: 'dbo', table: 'orders',
      keyFields: ['id'], truncateBeforeWrite: false
    }
  }
};

describe('PipelineCreateComponent — SQL Server destination (not installed)', () => {
  let fixture: ComponentFixture<PipelineCreateComponent>;
  let component: PipelineCreateComponent;
  let el: HTMLElement;

  beforeEach(setup(['postgres', 'mongodb', 'objectstore']));
  beforeEach(() => {
    fixture = TestBed.createComponent(PipelineCreateComponent);
    component = fixture.componentInstance;
    el = fixture.nativeElement as HTMLElement;
    fixture.detectChanges();
  });

  it('shows SQL Server as "— Not installed" and disabled when no secret advertises sqlserver', () => {
    expect(component.isDestAvailable('sqlserver')).toBeFalse();
    component.sourceType = 'csv';
    component.step = 8;
    component.destType = 'postgres';
    fixture.detectChanges();
    const opt = sqlServerOption(el);
    expect(opt).withContext('option[value=sqlserver]').toBeDefined();
    expect((opt!.textContent || '').trim()).toMatch(/^SQL Server/);
    expect(opt!.textContent || '').toContain('Not installed');
    expect(opt!.disabled).toBeTrue();
  });
});

describe('PipelineCreateComponent — SQL Server destination (installed)', () => {
  let fixture: ComponentFixture<PipelineCreateComponent>;
  let component: PipelineCreateComponent;
  let el: HTMLElement;

  beforeEach(setup(['postgres', 'mongodb', 'objectstore', 'sqlserver']));
  beforeEach(() => {
    fixture = TestBed.createComponent(PipelineCreateComponent);
    component = fixture.componentInstance;
    el = fixture.nativeElement as HTMLElement;
    fixture.detectChanges();
  });

  it('offers SQL Server enabled for csv, json and xml sources', () => {
    expect(component.isDestAvailable('sqlserver')).toBeTrue();
    for (const src of ['csv', 'json', 'xml']) {
      component.sourceType = src;
      component.step = 8;
      component.destType = 'sqlserver';
      fixture.detectChanges();
      const opt = sqlServerOption(el);
      expect(opt).withContext(`option[value=sqlserver] for ${src}`).toBeDefined();
      expect(opt!.disabled).withContext(`enabled for ${src}`).toBeFalse();
      expect(opt!.textContent || '').not.toContain('Not installed');
    }
  });

  it('a saved CSV SQL Server pipeline reopens as sqlserver and saves destination.database.useSqlServer: true', () => {
    component.loadFromConfig(JSON.parse(JSON.stringify(sqlServerCsvConfig)));
    expect(component.destType).toBe('sqlserver');
    const db = component.buildConfig().destination.database;
    expect(db.useSqlServer).toBeTrue();
    expect(db.usePostgres).toBeFalsy();
    expect(db.useMongoDB).toBeFalsy();
    expect(db.useSnowflake).toBeFalsy();
    expect(db.useDatabricks).toBeFalsy();
    expect(db).toEqual(jasmine.objectContaining({
      credentialsSecret: 'mssql', dbName: 'datris', schema: 'dbo', table: 'orders', keyFields: ['id']
    }));
  });

  it('switching the source to JSON keeps SQL Server selected; Postgres still flips to MongoDB', () => {
    component.sourceType = 'json';
    component.destType = 'sqlserver';
    component.onSourceTypeChange();
    expect(component.destType).toBe('sqlserver');

    component.sourceType = 'xml';
    component.destType = 'sqlserver';
    component.onSourceTypeChange();
    expect(component.destType).withContext('XML keeps SQL Server too').toBe('sqlserver');

    component.sourceType = 'json';
    component.destType = 'postgres';
    component.onSourceTypeChange();
    expect(component.destType).toBe('mongodb');
  });
});
