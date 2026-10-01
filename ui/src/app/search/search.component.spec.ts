/**
 * Story: Iceberg in MCP tools, wizard and prompt wording
 * (plans/stories/iceberg-mcp-ui-prompts.md) — Search tab bullet.
 *
 * Pins: when POST /api/v1/query/objectstore returns a `snapshotId`, the
 * Traditional results header shows it next to the result count; when the
 * response carries `snapshotId: null` (a non-Iceberg format) the header shows the
 * count and nothing about snapshots.
 */
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { CUSTOM_ELEMENTS_SCHEMA, NO_ERRORS_SCHEMA } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { provideRouter } from '@angular/router';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { of, throwError } from 'rxjs';
import { HttpErrorResponse } from '@angular/common/http';

import { SearchComponent } from './search.component';
import { SearchService } from '../search.service';
import { HealthService } from '../health.service';

describe('SearchComponent — object-store snapshot id', () => {
  let fixture: ComponentFixture<SearchComponent>;
  let component: SearchComponent;
  let el: HTMLElement;
  let searchService: { queryObjectstore: jasmine.Spy; getPipelines: jasmine.Spy; getPostgresSchemas: jasmine.Spy; getMongoCollections: jasmine.Spy };

  const icebergPipeline = {
    name: 'orders',
    destination: { objectStore: { prefixKey: 'orders/daily', fileFormat: 'iceberg' } }
  };

  beforeEach(async () => {
    searchService = {
      queryObjectstore: jasmine.createSpy('queryObjectstore'),
      getPipelines: jasmine.createSpy('getPipelines').and.returnValue(of([icebergPipeline])),
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
        { provide: SearchService, useValue: searchService },
        { provide: HealthService, useValue: { isAvailable: () => true, refresh: () => Promise.resolve() } }
      ]
    }).compileComponents();

    fixture = TestBed.createComponent(SearchComponent);
    component = fixture.componentInstance;
    el = fixture.nativeElement as HTMLElement;
    fixture.detectChanges();
    component.setActiveView('traditional');
    component.queryType = 'objectstore';
    component.osSelectedPipeline = 'orders';
    fixture.detectChanges();
  });

  afterEach(() => { try { sessionStorage.removeItem('search.activeView'); } catch { /* ignore */ } });

  function headerText(): string {
    const header = el.querySelector('.results-header');
    expect(header).withContext('results header rendered').not.toBeNull();
    return (header!.textContent || '').replace(/\s+/g, ' ').trim();
  }

  it('shows the snapshot id next to the result count for an Iceberg result', () => {
    searchService.queryObjectstore.and.returnValue(of({
      pipeline: 'orders', path: 's3a://bucket/orders/daily', format: 'iceberg',
      columns: ['id', 'amount'], results: [{ id: 1, amount: 10 }, { id: 2, amount: 20 }], count: 2,
      snapshotId: '8533883885102256461', snapshotTimestamp: '2026-09-16T12:34:56Z'
    }));

    component.execute();
    fixture.detectChanges();

    const text = headerText();
    expect(text).toContain('2 results');
    expect(text).toContain('8533883885102256461');
    expect(text).toMatch(/snapshot/i);
  });

  it('shows only the result count for a parquet result (snapshotId null)', () => {
    searchService.queryObjectstore.and.returnValue(of({
      pipeline: 'orders', path: 's3a://bucket/orders/daily', format: 'parquet',
      columns: ['id'], results: [{ id: 1 }], count: 1,
      snapshotId: null, snapshotTimestamp: null
    }));

    component.execute();
    fixture.detectChanges();

    const text = headerText();
    expect(text).toContain('1 result');
    expect(text).not.toMatch(/snapshot/i);
    expect(text).not.toContain('null');
  });

  it('shows the server error message from a 400 {"error": ...} response', () => {
    searchService.queryObjectstore.and.returnValue(throwError(() => new HttpErrorResponse({
      status: 400, error: { error: 'x' }
    })));

    component.execute();
    fixture.detectChanges();

    const box = el.querySelector('.error-box');
    expect(box).not.toBeNull();
    expect((box!.textContent || '').trim()).toBe('x');
  });

  it('shows the HTTP message, never a ProgressEvent, for a status-0 network failure', () => {
    searchService.queryObjectstore.and.returnValue(throwError(() => new HttpErrorResponse({
      status: 0, statusText: 'Unknown Error', url: '/api/v1/query/objectstore', error: new ProgressEvent('error')
    })));

    component.execute();
    fixture.detectChanges();

    const text = (el.querySelector('.error-box')!.textContent || '').trim();
    expect(text).toContain('0 Unknown Error');
    expect(text).not.toContain('[object');
  });

  it('prefers a string "message" when the body has no "error"', () => {
    searchService.queryObjectstore.and.returnValue(throwError(() => new HttpErrorResponse({
      status: 403, error: { message: 'capability denied' }
    })));

    component.execute();
    fixture.detectChanges();

    expect((el.querySelector('.error-box')!.textContent || '').trim()).toBe('capability denied');
  });

  it('shows "0 results" when a query succeeds with no rows, and nothing before executing', () => {
    expect(el.querySelector('.results-header')).toBeNull();
    searchService.queryObjectstore.and.returnValue(of({ results: [], count: 0, snapshotId: null, snapshotTimestamp: null }));

    component.execute();
    fixture.detectChanges();

    expect(headerText()).toBe('0 results');
    expect(el.querySelector('table')).toBeNull();
  });

  it('renders object and array cells as JSON, not [object Object]', () => {
    searchService.queryObjectstore.and.returnValue(of({
      results: [{ id: 1, attrs: { a: 1 }, tags: ['x', 'y'], note: null }], count: 1, snapshotId: null, snapshotTimestamp: null
    }));

    component.execute();
    fixture.detectChanges();

    const cells = Array.from(el.querySelectorAll('tbody td')).map(td => (td.textContent || '').replace(/\s+/g, ''));
    expect(cells).toEqual(['1', '{"a":1}', '["x","y"]', '']);
    expect(el.querySelector('tbody')!.textContent).not.toContain('[object Object]');
  });
});
