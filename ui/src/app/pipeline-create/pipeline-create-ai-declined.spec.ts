/**
 * Story: when the model declines, schema generation and profiling fall back
 * instead of failing (plans/stories/ai-refusal-fallback.md) — UI note.
 *
 * With DATRIS_AI_SAMPLE_VALUES at its default, a model decline on
 * POST /api/v1/pipeline/generate now returns 200 with all-string fields and a
 * top-level `aiDeclined: true`. The wizard shows a one-line note beside the
 * existing `.values-withheld` note wherever it renders a Generate Schema
 * result (steps 1, 3 and 5), and clears it on the next result without the flag.
 *
 * DOM contract pinned here: the note is an element with class `.ai-declined`
 * whose text mentions "declined" (case-insensitive).
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

describe('PipelineCreateComponent — ai declined note', () => {
  let fixture: ComponentFixture<PipelineCreateComponent>;
  let component: PipelineCreateComponent;
  let el: HTMLElement;
  let generateResponse: any;

  const fields = [{ name: 'full_name', type: 'string' }, { name: 'ssn', type: 'string' }];

  beforeEach(async () => {
    generateResponse = { name: 'people', source: { schemaProperties: { fields } } };
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
            getPipeline: () => of({}),
            generateSchema: () => of(generateResponse)
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

  function generateOnSchemaStep(): void {
    component.pipelineName = 'people';
    component.sourceType = 'csv';
    component.step = 3;
    component.schemaFile = new File(['full_name,ssn\n'], 'people.csv', { type: 'text/csv' });
    fixture.detectChanges();
    component.generateSchema();
    fixture.detectChanges();
  }

  function analyzeSampleOnFirstStep(): void {
    component.pipelineName = 'people';
    component.pipelineSource = 'file';
    component.step = 1;
    component.sampleFile = new File(['full_name,ssn\n'], 'people.csv', { type: 'text/csv' });
    component.analyzeSampleFile();
    fixture.detectChanges();
  }

  it('the wizard shows the ai-declined note after a declined Generate Schema and clears it on the next normal result', () => {
    generateResponse = { ...generateResponse, aiDeclined: true };
    generateOnSchemaStep();
    expect(component.schemaFields.map(f => f.name)).toEqual(['full_name', 'ssn']);
    const note = el.querySelector('.ai-declined') as HTMLElement | null;
    expect(note).withContext('.ai-declined note on the Source Schema step').not.toBeNull();
    expect((note?.textContent || '').toLowerCase()).toContain('declined');
    expect(el.querySelector('.values-withheld')).withContext('a decline is not a withheld-values result').toBeNull();

    generateResponse = { name: 'people', source: { schemaProperties: { fields } } };
    generateOnSchemaStep();
    expect(el.querySelector('.ai-declined')).withContext('stale note after a normal result').toBeNull();
  });

  it('the sample-file analysis on step 1 shows the ai-declined note and clears it on the next normal result', () => {
    generateResponse = { ...generateResponse, aiDeclined: true };
    analyzeSampleOnFirstStep();
    const note = el.querySelector('.ai-declined') as HTMLElement | null;
    expect(note).withContext('.ai-declined note on the first step').not.toBeNull();
    expect((note?.textContent || '').toLowerCase()).toContain('declined');

    generateResponse = { name: 'people', source: { schemaProperties: { fields } } };
    analyzeSampleOnFirstStep();
    expect(el.querySelector('.ai-declined')).withContext('stale note after a normal result').toBeNull();
  });

  it('no ai-declined note when the flag is absent or false', () => {
    generateOnSchemaStep();
    expect(el.querySelector('.ai-declined')).withContext('flag absent').toBeNull();
    generateResponse = { ...generateResponse, aiDeclined: false };
    generateOnSchemaStep();
    expect(el.querySelector('.ai-declined')).withContext('flag false').toBeNull();
  });

  // Implementer addition: a declined fallback on a headerless file comes back
  // with csvAttributes.header false; the wizard adopts it and shows both notes.
  it('a declined fallback that turns Header off shows the header-adjusted note too, and both clear on a normal result', () => {
    component.csvHeader = true;
    generateResponse = {
      name: 'people', aiDeclined: true,
      source: {
        schemaProperties: { fields: [{ name: 'column_1', type: 'string' }, { name: 'column_2', type: 'string' }] },
        fileAttributes: { csvAttributes: { delimiter: ',', header: false, encoding: 'UTF-8' } }
      }
    };
    generateOnSchemaStep();
    expect(component.csvHeader).toBeFalse();
    expect(el.querySelector('.ai-declined')).not.toBeNull();
    expect(el.querySelector('.header-adjusted')).withContext('header-adjusted note with a declined fallback').not.toBeNull();

    generateResponse = { name: 'people', source: { schemaProperties: { fields } } };
    generateOnSchemaStep();
    expect(el.querySelector('.ai-declined')).toBeNull();
    expect(el.querySelector('.header-adjusted')).toBeNull();
  });
});
