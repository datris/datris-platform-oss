/**
 * Story: an invalid pipeline config is a 400, not a 500
 * (plans/stories/pipeline-save-validation-400.md), Step 3.
 *
 * POST /api/v1/pipeline answers a validator refusal with {"error": "<message>"}
 * (400 now, 500 on older servers). createPipeline uses responseType 'text', so
 * the HttpErrorResponse carries that body as a string. The wizard's error box
 * must read "Failed to create dataset: <message>" — the message text, not the
 * raw JSON with braces, the "error" key, or escaped quotes.
 */
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { CUSTOM_ELEMENTS_SCHEMA, NO_ERRORS_SCHEMA } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { HttpErrorResponse, provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { of, throwError } from 'rxjs';

import { PipelineCreateComponent } from './pipeline-create.component';
import { PipelineService } from '../pipeline.service';
import { SearchService } from '../search.service';
import { HealthService } from '../health.service';
import { TapService } from '../tap.service';

describe('PipelineCreateComponent — refused save shows the server message', () => {
  const MESSAGE = "Preset 'hipaa-safe-harbor': field 'phone' looks like phone and has no protection.";
  const BODY = JSON.stringify({ error: MESSAGE });

  let fixture: ComponentFixture<PipelineCreateComponent>;
  let component: PipelineCreateComponent;
  let el: HTMLElement;
  let saveError: HttpErrorResponse;

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
            createPipeline: () => throwError(() => saveError)
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

  function refusedSave(status: number, statusText: string): string {
    saveError = new HttpErrorResponse({ error: BODY, status, statusText, url: '/api/v1/pipeline' });
    component.pipelineName = 'patients';
    component.sourceType = 'csv';
    component.create();
    fixture.detectChanges();
    expect(component.creating).toBeFalse();
    const box = Array.from(el.querySelectorAll('.error-box'))
      .find(b => (b.textContent || '').includes('Failed to create dataset')) as HTMLElement | undefined;
    expect(box).withContext('the save error is shown in an .error-box').toBeTruthy();
    return (box?.textContent || '').trim();
  }

  function expectMessageText(text: string): void {
    expect(text).toBe('Failed to create dataset: ' + MESSAGE);
    expect(text).not.toContain('{');
    expect(text).not.toContain('}');
    expect(text).not.toContain('"error"');
    expect(text).not.toContain('\\"');
    expect(component.error).toBe('Failed to create dataset: ' + MESSAGE);
  }

  it('a refused save (400) shows the message text, not raw JSON', () => {
    expectMessageText(refusedSave(400, 'Bad Request'));
  });

  it('a refused save from an older server (500) shows the message text, not raw JSON', () => {
    expectMessageText(refusedSave(500, 'Internal Server Error'));
  });

  it('the server wire body (Gson escapes apostrophes as \\u0027) still shows the plain message', () => {
    // QueryAPIController.errorBody serialises with Gson, which writes ' as \u0027.
    const wire = '{"error": "Preset \\u0027hipaa-safe-harbor\\u0027: field \\u0027phone\\u0027 looks like phone and has no protection."}';
    expect(JSON.parse(wire).error).toBe(MESSAGE);
    saveError = new HttpErrorResponse({ error: wire, status: 400, statusText: 'Bad Request', url: '/api/v1/pipeline' });
    component.pipelineName = 'patients';
    component.create();
    fixture.detectChanges();
    expect(component.error).toBe('Failed to create dataset: ' + MESSAGE);
    expect(component.error).not.toContain('\\u0027');
  });

  it('a non-JSON error body is still shown as is', () => {
    saveError = new HttpErrorResponse({ error: 'upstream timed out', status: 502, statusText: 'Bad Gateway' });
    component.pipelineName = 'patients';
    component.create();
    fixture.detectChanges();
    expect(component.error).toBe('Failed to create dataset: upstream timed out');
  });
});
