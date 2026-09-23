/**
 * Story: Scratch destination in the UI and the docs
 * (plans/stories/scratch-ui-docs.md) — pipelines.component.spec.ts bullet.
 *
 * Pins: `getDestinations()` returns "Scratch" for `{ destination: { scratch: {} } }`;
 * `isScratch(ds)` is true for that shape and false for a postgres pipeline;
 * both destination cells (the catalog-embedded table — the live one — and the
 * full table) render a `span.scratch-badge` reading "Scratch" on the scratch
 * row and none on the postgres row.
 */
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { CUSTOM_ELEMENTS_SCHEMA, NO_ERRORS_SCHEMA } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { provideRouter } from '@angular/router';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { of } from 'rxjs';

import { PipelinesComponent } from './pipelines.component';
import { PipelineService } from '../pipeline.service';
import { PipelineStatusService } from '../pipeline-status.service';
import { TapService } from '../tap.service';
import { AuthService } from '../auth.service';

const scratchPipeline = {
  name: 'answer-now',
  catalog: 'demo',
  source: { fileAttributes: { csvAttributes: { delimiter: ',' } } },
  destination: { schemaProperties: { fields: [{ name: 'id', type: 'string' }] }, scratch: {} }
};

const postgresPipeline = {
  name: 'orders',
  catalog: 'demo',
  source: { fileAttributes: { csvAttributes: { delimiter: ',' } } },
  destination: {
    schemaProperties: { fields: [{ name: 'id', type: 'string' }] },
    database: { dbName: 'datris', schema: 'public', table: 'orders', usePostgres: true }
  }
};

describe('PipelinesComponent — Live Read badge', () => {
  let fixture: ComponentFixture<PipelinesComponent>;
  let component: PipelinesComponent;
  let el: HTMLElement;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      declarations: [PipelinesComponent],
      imports: [FormsModule],
      schemas: [CUSTOM_ELEMENTS_SCHEMA, NO_ERRORS_SCHEMA],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: PipelineService, useValue: { getPipelines: () => of([scratchPipeline, postgresPipeline]) } },
        { provide: PipelineStatusService, useValue: {} },
        { provide: TapService, useValue: { getTaps: () => of([]) } },
        { provide: AuthService, useValue: { canWrite: () => false } }
      ]
    }).compileComponents();

    fixture = TestBed.createComponent(PipelinesComponent);
    component = fixture.componentInstance;
    el = fixture.nativeElement as HTMLElement;
  });

  afterEach(() => fixture.destroy());

  function rowFor(name: string): HTMLElement {
    const row = (Array.from(el.querySelectorAll('tbody tr')) as HTMLElement[])
      .find(tr => (tr.textContent || '').includes(name));
    expect(row).withContext('row for ' + name).toBeDefined();
    return row!;
  }

  it('getDestinations lists Live Read', () => {
    expect(component.getDestinations(scratchPipeline)).toBe('Live Read');
    expect(component.getDestinations(postgresPipeline)).toBe('PostgreSQL');
    const c = component as any;
    expect(c.isScratch(scratchPipeline)).toBeTrue();
    expect(c.isScratch(postgresPipeline)).toBeFalse();
  });

  it('a pipeline with destination.scratch shows the Live Read badge (embedded catalog table)', () => {
    component.embedCatalog = 'demo';
    fixture.detectChanges();
    const badge = rowFor('answer-now').querySelector('.scratch-badge');
    expect(badge).withContext('span.scratch-badge in the embedded destination cell').not.toBeNull();
    expect((badge!.textContent || '').trim()).toBe('Live Read');
  });

  it('a pipeline with destination.scratch shows the Live Read badge (full table)', () => {
    fixture.detectChanges();
    component.catalogGroups.forEach(g => g.expanded = true);
    fixture.detectChanges();
    const badge = rowFor('answer-now').querySelector('.scratch-badge');
    expect(badge).withContext('span.scratch-badge in the full-table destination cell').not.toBeNull();
    expect((badge!.textContent || '').trim()).toBe('Live Read');
  });

  it('a postgres pipeline shows no Live Read badge', () => {
    component.embedCatalog = 'demo';
    fixture.detectChanges();
    expect(rowFor('orders').querySelector('.scratch-badge')).toBeNull();
    expect((rowFor('orders').textContent || '')).toContain('PostgreSQL');

    // Full table too.
    fixture.destroy();
    fixture = TestBed.createComponent(PipelinesComponent);
    component = fixture.componentInstance;
    el = fixture.nativeElement as HTMLElement;
    fixture.detectChanges();
    component.catalogGroups.forEach(g => g.expanded = true);
    fixture.detectChanges();
    expect(rowFor('orders').querySelector('.scratch-badge')).toBeNull();
  });
});
