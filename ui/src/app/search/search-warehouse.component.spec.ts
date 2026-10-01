/**
 * Search tab, Traditional mode: Databricks and Snowflake query types.
 *
 * Pins: the "Warehouse" options appear only when at least one pipeline has that
 * destination; picking Databricks lists only Databricks pipelines and builds the
 * `SELECT * FROM catalog.schema.table LIMIT 10` placeholder; Execute posts
 * `{pipeline, sql, limit}` to the matching endpoint and renders the rows; a
 * `{"error": "..."}` response shows the message in the error box.
 */
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { CUSTOM_ELEMENTS_SCHEMA, NO_ERRORS_SCHEMA } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { provideRouter } from '@angular/router';
import { HttpErrorResponse, provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { of, throwError } from 'rxjs';

import { SearchComponent } from './search.component';
import { SearchService } from '../search.service';
import { HealthService } from '../health.service';

const dbxPipeline = {
  name: 'orders_dbx',
  destination: { database: { useDatabricks: true, dbName: 'main', schema: 'sales', table: 'orders' } }
};
const sfPipeline = {
  name: 'orders_sf',
  destination: { database: { useSnowflake: true, dbName: 'ANALYTICS', schema: 'PUBLIC', table: 'ORDERS' } }
};
const pgPipeline = {
  name: 'orders_pg',
  destination: { database: { usePostgres: true, dbName: 'datris', schema: 'public', table: 'orders' } }
};
const osPipeline = {
  name: 'orders_os',
  destination: { objectStore: { prefixKey: 'orders', fileFormat: 'parquet' } }
};

type ServiceSpy = {
  queryDatabricks: jasmine.Spy; querySnowflake: jasmine.Spy; queryObjectstore: jasmine.Spy;
  getPipelines: jasmine.Spy; getPostgresSchemas: jasmine.Spy; getMongoCollections: jasmine.Spy;
};

async function setup(pipelines: any[]): Promise<{ fixture: ComponentFixture<SearchComponent>; component: SearchComponent; el: HTMLElement; svc: ServiceSpy }> {
  const svc: ServiceSpy = {
    queryDatabricks: jasmine.createSpy('queryDatabricks'),
    querySnowflake: jasmine.createSpy('querySnowflake'),
    queryObjectstore: jasmine.createSpy('queryObjectstore'),
    getPipelines: jasmine.createSpy('getPipelines').and.returnValue(of(pipelines)),
    getPostgresSchemas: jasmine.createSpy('getPostgresSchemas').and.returnValue(of([])),
    getMongoCollections: jasmine.createSpy('getMongoCollections').and.returnValue(of([]))
  };
  await TestBed.configureTestingModule({
    declarations: [SearchComponent],
    imports: [FormsModule],
    schemas: [CUSTOM_ELEMENTS_SCHEMA, NO_ERRORS_SCHEMA],
    providers: [
      provideHttpClient(),
      provideHttpClientTesting(),
      provideRouter([]),
      { provide: SearchService, useValue: svc },
      { provide: HealthService, useValue: { isAvailable: () => true, refresh: () => Promise.resolve() } }
    ]
  }).compileComponents();

  const fixture = TestBed.createComponent(SearchComponent);
  const component = fixture.componentInstance;
  fixture.detectChanges();
  // ngOnInit loads pipelines after /api/v1/version answers.
  TestBed.inject(HttpTestingController).expectOne('/api/v1/version').flush({ postgresDatabase: 'datris', mongodbDatabase: 'datris' });
  component.setActiveView('traditional');
  fixture.detectChanges();
  await fixture.whenStable();
  fixture.detectChanges();
  return { fixture, component, el: fixture.nativeElement as HTMLElement, svc };
}

function optionValues(el: HTMLElement): string[] {
  const select = el.querySelector('select') as HTMLSelectElement;
  return Array.from(select.querySelectorAll('option')).map(o => (o as HTMLOptionElement).value);
}

function selectQueryType(fixture: ComponentFixture<SearchComponent>, component: SearchComponent, type: string): void {
  component.queryType = type;
  component.onQueryTypeChange();
  fixture.detectChanges();
}

describe('SearchComponent — warehouse query types', () => {
  afterEach(() => { try { sessionStorage.removeItem('search.activeView'); } catch { /* ignore */ } });

  it('hides the Warehouse options when no pipeline has a Databricks or Snowflake destination', async () => {
    const { el } = await setup([pgPipeline, osPipeline]);
    const values = optionValues(el);
    expect(values).toContain('objectstore');
    expect(values).not.toContain('databricks');
    expect(values).not.toContain('snowflake');
    expect(el.querySelector('optgroup[label="Warehouse"]')).toBeNull();
  });

  it('shows only the option whose destination exists', async () => {
    const { el } = await setup([dbxPipeline, pgPipeline]);
    const values = optionValues(el);
    expect(values).toContain('databricks');
    expect(values).not.toContain('snowflake');
  });

  it('shows both options when both destinations exist', async () => {
    const { el } = await setup([dbxPipeline, sfPipeline]);
    const values = optionValues(el);
    expect(values).toContain('databricks');
    expect(values).toContain('snowflake');
  });

  it('selecting Databricks lists only Databricks pipelines, shows the target and builds the placeholder', async () => {
    const { fixture, component, el } = await setup([dbxPipeline, sfPipeline, pgPipeline, osPipeline]);
    selectQueryType(fixture, component, 'databricks');

    expect(component.whSelectedPipeline).toBe('orders_dbx');
    const pickerOptions = Array.from(el.querySelectorAll('select'))[1].querySelectorAll('option');
    const names = Array.from(pickerOptions).map(o => (o as HTMLOptionElement).value).filter(v => v);
    expect(names).toEqual(['orders_dbx']);

    const textarea = el.querySelector('textarea') as HTMLTextAreaElement;
    expect(textarea.getAttribute('placeholder')).toBe('SELECT * FROM main.sales.orders LIMIT 10');
    expect((el.textContent || '')).toContain('main.sales.orders');
    // No DATABASE field and no Retrieve All for warehouse types.
    expect(Array.from(el.querySelectorAll('.form-label')).map(l => (l.textContent || '').trim())).not.toContain('Database');
    expect(el.querySelector('.retrieve-all-btn')).toBeNull();
  });

  it('switching to Snowflake re-points the picker and placeholder', async () => {
    const { fixture, component, el } = await setup([dbxPipeline, sfPipeline]);
    selectQueryType(fixture, component, 'databricks');
    selectQueryType(fixture, component, 'snowflake');
    expect(component.whSelectedPipeline).toBe('orders_sf');
    expect((el.querySelector('textarea') as HTMLTextAreaElement).getAttribute('placeholder'))
      .toBe('SELECT * FROM ANALYTICS.PUBLIC.ORDERS LIMIT 10');
  });

  it('execute posts {pipeline, sql, limit} to the Databricks query and renders the rows', async () => {
    const { fixture, component, el, svc } = await setup([dbxPipeline, sfPipeline]);
    selectQueryType(fixture, component, 'databricks');
    component.whSql = ' SELECT id, amount FROM main.sales.orders ';
    component.whLimit = 50;
    svc.queryDatabricks.and.returnValue(of({
      pipeline: 'orders_dbx', sql: 'SELECT id, amount FROM main.sales.orders LIMIT 50',
      results: [{ id: 1, amount: 10 }, { id: 2, amount: 20 }], count: 2
    }));

    component.execute();
    fixture.detectChanges();

    expect(svc.queryDatabricks).toHaveBeenCalledWith('orders_dbx', 'SELECT id, amount FROM main.sales.orders', 50);
    expect(svc.querySnowflake).not.toHaveBeenCalled();
    expect(component.columns).toEqual(['id', 'amount']);
    expect(el.querySelectorAll('.table-container tbody tr').length).toBe(2);
    expect((el.querySelector('.results-header')!.textContent || '')).toContain('2 results');
  });

  it('execute posts to the Snowflake query for a Snowflake pipeline', async () => {
    const { fixture, component, svc } = await setup([dbxPipeline, sfPipeline]);
    selectQueryType(fixture, component, 'snowflake');
    svc.querySnowflake.and.returnValue(of({ results: [], count: 0 }));

    component.execute();

    expect(svc.querySnowflake).toHaveBeenCalledWith('orders_sf', '', 100);
    expect(svc.queryDatabricks).not.toHaveBeenCalled();
  });

  it('shows the server error message from a {"error": ...} response', async () => {
    const { fixture, component, el, svc } = await setup([dbxPipeline]);
    selectQueryType(fixture, component, 'databricks');
    component.whSql = 'DELETE FROM main.sales.orders';
    svc.queryDatabricks.and.returnValue(throwError(() => new HttpErrorResponse({
      status: 400, error: { error: 'Only read-only queries are allowed' }
    })));

    component.execute();
    fixture.detectChanges();

    const box = el.querySelector('.error-box');
    expect(box).not.toBeNull();
    expect((box!.textContent || '').trim()).toBe('Only read-only queries are allowed');
    expect(component.loading).toBeFalse();
  });
});

describe('SearchService — warehouse endpoints', () => {
  it('posts {pipeline, sql, limit} to /api/v1/query/databricks and /api/v1/query/snowflake', () => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    const service = TestBed.inject(SearchService);
    const http = TestBed.inject(HttpTestingController);

    service.queryDatabricks('orders_dbx', 'SELECT 1', 10).subscribe();
    const dbx = http.expectOne('/api/v1/query/databricks');
    expect(dbx.request.method).toBe('POST');
    expect(dbx.request.body).toEqual({ pipeline: 'orders_dbx', sql: 'SELECT 1', limit: 10 });
    dbx.flush({ results: [], count: 0 });

    service.querySnowflake('orders_sf', 'SELECT 2', 20).subscribe();
    const sf = http.expectOne('/api/v1/query/snowflake');
    expect(sf.request.method).toBe('POST');
    expect(sf.request.body).toEqual({ pipeline: 'orders_sf', sql: 'SELECT 2', limit: 20 });
    sf.flush({ results: [], count: 0 });

    http.verify();
  });
});
