/**
 * Story: Self-healing cost loop follow-up — the "Self-healing" row is also
 * mounted on the pipeline edit page (/pipelines/:name/edit), where the Data
 * Catalog links pipeline names. It is absent on create (no name yet).
 */
import { TestBed } from '@angular/core/testing';
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

describe('PipelineCreateComponent — recovery mode row', () => {
  async function render(params: Record<string, string>): Promise<HTMLElement> {
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
            getPipeline: () => of({ name: 'orders', source: {}, destination: {} })
        } },
        { provide: SearchService, useValue: { getPipelines: () => of([]) } },
        { provide: HealthService, useValue: { isAvailable: () => true, refresh: () => Promise.resolve() } },
        { provide: TapService, useValue: { getTaps: () => of([]) } },
        { provide: ActivatedRoute, useValue: { snapshot: { queryParamMap: convertToParamMap({}), paramMap: convertToParamMap(params) } } }
      ]
    }).compileComponents();
    const fixture = TestBed.createComponent(PipelineCreateComponent);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  it('renders the row for the pipeline being edited', async () => {
    const el = await render({ name: 'orders' });
    const row = el.querySelector('app-recovery-mode-row');
    expect(row).withContext('app-recovery-mode-row on /pipelines/:name/edit').toBeTruthy();
    expect(row!.getAttribute('resourcetype')).toBe('pipeline');
  });

  it('does not render the row on create', async () => {
    const el = await render({});
    expect(el.querySelector('app-recovery-mode-row')).toBeNull();
  });
});
