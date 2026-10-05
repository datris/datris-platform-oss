/**
 * Story: field protection 9, one switch so no row value is sent to a model
 * (plans/stories/field-protection-9-ai-values-switch.md) — UI note.
 *
 * With DATRIS_AI_SAMPLE_VALUES=false, POST /api/v1/pipeline/generate returns
 * the generated config with a top-level `valuesWithheld: true`. The wizard
 * shows a one-line note wherever it renders a Generate Schema result: the
 * Source Schema step's "Generate Schema" button (step 3) and the sample-file
 * analysis on step 1. No note when the flag is absent or false.
 *
 * DOM contract pinned here: the note is an element with class
 * `.values-withheld` whose text mentions "withheld" (case-insensitive).
 *
 * The UI has no separate profile panel today (profiling is reachable only via
 * the MCP tab's tool playground, which prints the raw JSON response, so
 * `valuesWithheld` is already visible there); only the wizard is covered.
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

describe('PipelineCreateComponent — values withheld note', () => {
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

  it('Generate Schema shows the note when the result has valuesWithheld: true', () => {
    generateResponse = { ...generateResponse, valuesWithheld: true };
    generateOnSchemaStep();
    expect(component.schemaFields.map(f => f.name)).toEqual(['full_name', 'ssn']);
    const note = el.querySelector('.values-withheld') as HTMLElement | null;
    expect(note).withContext('.values-withheld note on the Source Schema step').not.toBeNull();
    expect((note?.textContent || '').toLowerCase()).toContain('withheld');
  });

  it('Generate Schema shows no note when valuesWithheld is absent or false', () => {
    generateOnSchemaStep();
    expect(el.querySelector('.values-withheld')).withContext('flag absent').toBeNull();
    generateResponse = { ...generateResponse, valuesWithheld: false };
    generateOnSchemaStep();
    expect(el.querySelector('.values-withheld')).withContext('flag false').toBeNull();
  });

  it('the sample-file analysis shows the note when the result has valuesWithheld: true', () => {
    generateResponse = { ...generateResponse, valuesWithheld: true };
    analyzeSampleOnFirstStep();
    const note = el.querySelector('.values-withheld') as HTMLElement | null;
    expect(note).withContext('.values-withheld note on the first step').not.toBeNull();
    expect((note?.textContent || '').toLowerCase()).toContain('withheld');
  });

  it('a later result without the flag clears the note', () => {
    generateResponse = { ...generateResponse, valuesWithheld: true };
    generateOnSchemaStep();
    expect(el.querySelector('.values-withheld')).not.toBeNull();
    generateResponse = { name: 'people', source: { schemaProperties: { fields } } };
    generateOnSchemaStep();
    expect(el.querySelector('.values-withheld')).withContext('stale note after a sampled result').toBeNull();
  });
});
