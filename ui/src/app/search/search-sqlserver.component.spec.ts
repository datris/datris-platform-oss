/**
 * Search tab, Traditional mode: SQL Server query type.
 *
 * Story: SQL Server read-back and on-demand typing
 * (plans/stories/mssql-query-and-typing.md), Acceptance bullet 5 (the
 * offline half): a pipeline with `destination.database.useSqlServer` is listed
 * under the Warehouse queries as kind 'sqlserver'; picking it lists only SQL
 * Server pipelines; Execute calls `SearchService.querySqlServer(pipeline, sql,
 * limit)` and renders the rows; the service posts `{pipeline, sql, limit}` to
 * /api/v1/query/sqlserver. Mirrors search-warehouse.component.spec.ts.
 */
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { CUSTOM_ELEMENTS_SCHEMA, NO_ERRORS_SCHEMA } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { provideRouter } from '@angular/router';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { of } from 'rxjs';

import { SearchComponent } from './search.component';
import { SearchService } from '../search.service';
import { HealthService } from '../health.service';

const mssqlPipeline = {
  name: 'orders_mssql',
  destination: { database: { useSqlServer: true, dbName: 'datris', schema: 'dbo', table: 'orders' } }
};
const sfPipeline = {
  name: 'orders_sf',
  destination: { database: { useSnowflake: true, dbName: 'ANALYTICS', schema: 'PUBLIC', table: 'ORDERS' } }
};
const pgPipeline = {
  name: 'orders_pg',
  destination: { database: { usePostgres: true, dbName: 'datris', schema: 'public', table: 'orders' } }
};

type ServiceSpy = {
  querySqlServer: jasmine.Spy; queryDatabricks: jasmine.Spy; querySnowflake: jasmine.Spy; queryObjectstore: jasmine.Spy;
  getPipelines: jasmine.Spy; getPostgresSchemas: jasmine.Spy; getMongoCollections: jasmine.Spy;
};

async function setup(pipelines: any[]): Promise<{ fixture: ComponentFixture<SearchComponent>; component: SearchComponent; el: HTMLElement; svc: ServiceSpy }> {
  const svc: ServiceSpy = {
    querySqlServer: jasmine.createSpy('querySqlServer'),
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

describe('SearchComponent — SQL Server query type', () => {
  afterEach(() => { try { sessionStorage.removeItem('search.activeView'); } catch { /* ignore */ } });

  it('lists a SQL Server pipeline as a warehouse pipeline of kind sqlserver', async () => {
    const { component } = await setup([mssqlPipeline, sfPipeline, pgPipeline]);
    const kinds = component.whPipelines.map(p => [p.name, p.kind as string]);
    expect(kinds).toContain(['orders_mssql', 'sqlserver']);
    expect(kinds).toContain(['orders_sf', 'snowflake']);
    expect(component.hasWarehousePipelines('sqlserver')).toBeTrue();
  });

  it('shows the SQL Server option under Warehouse only when a SQL Server pipeline exists', async () => {
    const { el } = await setup([mssqlPipeline, pgPipeline]);
    expect(optionValues(el)).toContain('sqlserver');
    expect(el.querySelector('optgroup[label="Warehouse"]')).not.toBeNull();
    TestBed.resetTestingModule();
    const other = await setup([sfPipeline, pgPipeline]);
    expect(optionValues(other.el)).not.toContain('sqlserver');
  });

  it('selecting SQL Server lists only SQL Server pipelines', async () => {
    const { fixture, component, el } = await setup([mssqlPipeline, sfPipeline, pgPipeline]);
    selectQueryType(fixture, component, 'sqlserver');
    expect(component.whSelectedPipeline).toBe('orders_mssql');
    const pickerOptions = Array.from(el.querySelectorAll('select'))[1].querySelectorAll('option');
    const names = Array.from(pickerOptions).map(o => (o as HTMLOptionElement).value).filter(v => v);
    expect(names).toEqual(['orders_mssql']);
  });

  it('execute calls querySqlServer with {pipeline, sql, limit} and renders the rows', async () => {
    const { fixture, component, el, svc } = await setup([mssqlPipeline, sfPipeline]);
    selectQueryType(fixture, component, 'sqlserver');
    component.whSql = ' SELECT id, amount FROM dbo.orders ';
    component.whLimit = 5;
    svc.querySqlServer.and.returnValue(of({
      pipeline: 'orders_mssql', sql: 'SELECT TOP (5) * FROM (SELECT id, amount FROM dbo.orders) q',
      results: [{ id: 1, amount: 10 }, { id: 2, amount: 20 }], count: 2
    }));

    component.execute();
    fixture.detectChanges();

    expect(svc.querySqlServer).toHaveBeenCalledWith('orders_mssql', 'SELECT id, amount FROM dbo.orders', 5);
    expect(svc.querySnowflake).not.toHaveBeenCalled();
    expect(svc.queryDatabricks).not.toHaveBeenCalled();
    expect(component.columns).toEqual(['id', 'amount']);
    expect(el.querySelectorAll('.table-container tbody tr').length).toBe(2);
  });
});

describe('SearchService — SQL Server endpoint', () => {
  it('posts {pipeline, sql, limit} to /api/v1/query/sqlserver', () => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    const service = TestBed.inject(SearchService) as any;
    const http = TestBed.inject(HttpTestingController);

    service.querySqlServer('orders_mssql', 'SELECT 1', 10).subscribe();
    const req = http.expectOne('/api/v1/query/sqlserver');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ pipeline: 'orders_mssql', sql: 'SELECT 1', limit: 10 });
    req.flush({ results: [], count: 0 });
    http.verify();
  });
});
