/**
 * Story: Generating a validation schema needs the right to author pipelines
 * and never replaces a schema another pipeline depends on.
 *
 * Both Generate buttons (the pipeline-create wizard's data-quality step and
 * the pipeline editor's data-quality panel) show the server's refusal in
 * full: the in-use sentence of a 409, and the explanatory `message` of a
 * capability-denied 403 rather than only "capability denied". Every other
 * error reads as before.
 */
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { CUSTOM_ELEMENTS_SCHEMA, NO_ERRORS_SCHEMA } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { HttpErrorResponse, provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { of, throwError } from 'rxjs';

import { PipelineDqTxEditorComponent } from './pipeline-dq-tx-editor.component';
import { PipelineCreateComponent } from '../pipeline-create/pipeline-create.component';
import { PipelineService } from '../pipeline.service';
import { SearchService } from '../search.service';
import { HealthService } from '../health.service';
import { TapService } from '../tap.service';
import { httpErrorExplanation, httpErrorText } from '../shared/http-error';

const IN_USE = "A validation schema named 'orders.json' is already in use. Choose another schema name.";
const DENIED_MESSAGE =
  "API key 'reader' does not hold capability 'pipeline:update'. This is a permission boundary on the API key, not a problem with the upstream service.";

function inUse(): HttpErrorResponse {
  return new HttpErrorResponse({ error: { error: IN_USE }, status: 409, statusText: 'Conflict' });
}

function denied(): HttpErrorResponse {
  return new HttpErrorResponse({
    error: { error: 'capability denied', errorKind: 'capability_denied', key: 'reader', required: 'pipeline:update', message: DENIED_MESSAGE },
    status: 403,
    statusText: 'Forbidden'
  });
}

describe('httpErrorExplanation', () => {
  it('a capability-denied body gives its message, as an object or as text', () => {
    expect(httpErrorExplanation(denied())).toBe(DENIED_MESSAGE);
    const asText = new HttpErrorResponse({ error: JSON.stringify(denied().error), status: 403 });
    expect(httpErrorExplanation(asText)).toBe(DENIED_MESSAGE);
  });

  it('every other error gives exactly what httpErrorText gives', () => {
    const cases: any[] = [
      inUse(),
      { error: { error: 'capability denied' } },
      { error: { error: 'x', errorKind: 'capability_denied', message: '  ' } },
      { error: { error: 'x', errorKind: 'other', message: 'm' } },
      { error: 'upstream timed out', status: 502 },
      { error: '{"error": 42}' },
      { error: { message: 'm' } },
      { error: null, message: 'Http failure response' },
      { error: '' },
      null
    ];
    cases.forEach(c => expect(httpErrorExplanation(c, 'fallback')).toBe(httpErrorText(c, 'fallback')));
  });
});

describe('Pipeline editor data-quality panel: Generate refusals', () => {
  let generateError: HttpErrorResponse;
  let component: PipelineDqTxEditorComponent;

  beforeEach(() => {
    const service = { generateValidationSchema: () => throwError(() => generateError) } as unknown as PipelineService;
    component = new PipelineDqTxEditorComponent(service);
    component.sourceType = 'json';
    component.dqSchemaName = 'orders';
    component.dqSampleData = '[{"id":1}]';
  });

  it('a 409 shows the in-use sentence in full', () => {
    generateError = inUse();
    component.generateValidationSchema();
    expect(component.dqGenerateError).toBe('Failed to generate schema: ' + IN_USE);
    expect(component.dqGenerating).toBeFalse();
    expect(component.dqValidationSchema).toBe('');
  });

  it('a 403 shows the server explanation, not only "capability denied"', () => {
    generateError = denied();
    component.generateValidationSchema();
    expect(component.dqGenerateError).toBe('Failed to generate schema: ' + DENIED_MESSAGE);
  });

  it('a successful generate still sets the generated file name', () => {
    const service = { generateValidationSchema: () => of({ filename: 'orders.json' }) } as unknown as PipelineService;
    const ok = new PipelineDqTxEditorComponent(service);
    ok.dqSchemaName = 'orders';
    ok.dqSampleData = '[{"id":1}]';
    ok.generateValidationSchema();
    expect(ok.dqValidationSchema).toBe('orders.json');
    expect(ok.dqGenerateError).toBe('');
  });
});

describe('PipelineCreateComponent data-quality step: Generate refusals', () => {
  let fixture: ComponentFixture<PipelineCreateComponent>;
  let component: PipelineCreateComponent;
  let generateError: HttpErrorResponse;

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
            getPipeline: () => of({}),
            generateSchema: () => of({}),
            generateValidationSchema: () => throwError(() => generateError)
        } },
        { provide: SearchService, useValue: { getPipelines: () => of([]) } },
        { provide: HealthService, useValue: { isAvailable: () => true, refresh: () => Promise.resolve() } },
        { provide: TapService, useValue: { getTaps: () => of([]) } },
        { provide: ActivatedRoute, useValue: { snapshot: { queryParamMap: convertToParamMap({}), paramMap: convertToParamMap({}) } } }
      ]
    }).compileComponents();

    fixture = TestBed.createComponent(PipelineCreateComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
    component.sourceType = 'json';
    component.dqSchemaName = 'orders';
    component.dqSampleData = '[{"id":1}]';
  });

  it('a 409 shows the in-use sentence in full', () => {
    generateError = inUse();
    component.generateValidationSchema();
    expect(component.dqGenerateError).toBe('Failed to generate schema: ' + IN_USE);
    expect(component.dqGenerating).toBeFalse();
  });

  it('a 403 shows the server explanation, not only "capability denied"', () => {
    generateError = denied();
    component.generateValidationSchema();
    expect(component.dqGenerateError).toBe('Failed to generate schema: ' + DENIED_MESSAGE);
  });
});
