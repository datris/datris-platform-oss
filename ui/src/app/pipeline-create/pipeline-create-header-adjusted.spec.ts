/**
 * Story: field protection 9, follow-up 2 (2026-10-05) — wizard side.
 *
 * With DATRIS_AI_SAMPLE_VALUES=false, /pipeline/generate numbers the columns
 * when line 1 is data and returns `source.fileAttributes.csvAttributes.header:
 * false`. The wizard adopts that flag (never turns Header on by itself), shows
 * a `.header-adjusted` note, and buildConfig() emits `header: false`.
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

describe('PipelineCreateComponent — header adopted from generated config', () => {
  let fixture: ComponentFixture<PipelineCreateComponent>;
  let component: PipelineCreateComponent;
  let el: HTMLElement;
  let generateResponse: any;

  const numbered = [{ name: 'column_1', type: 'string' }, { name: 'column_2', type: 'string' }];

  function responseWithHeader(header: boolean | undefined): any {
    const csvAttributes: any = { delimiter: ',', encoding: 'UTF-8' };
    if (header !== undefined) csvAttributes.header = header;
    return {
      name: 'people', valuesWithheld: true,
      source: { schemaProperties: { fields: numbered }, fileAttributes: { csvAttributes } }
    };
  }

  beforeEach(async () => {
    generateResponse = responseWithHeader(false);
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
    component.schemaFile = new File(['Jane,42\n'], 'people.csv', { type: 'text/csv' });
    fixture.detectChanges();
    component.generateSchema();
    fixture.detectChanges();
  }

  function analyzeSampleOnFirstStep(): void {
    component.pipelineName = 'people';
    component.pipelineSource = 'file';
    component.step = 1;
    component.sampleFile = new File(['Jane,42\n'], 'people.csv', { type: 'text/csv' });
    component.analyzeSampleFile();
    fixture.detectChanges();
  }

  it('Generate Schema with header false unticks Header, shows the note, and buildConfig emits header false', () => {
    component.csvHeader = true;
    generateOnSchemaStep();
    expect(component.csvHeader).toBeFalse();
    const note = el.querySelector('.header-adjusted') as HTMLElement | null;
    expect(note).withContext('.header-adjusted note').not.toBeNull();
    expect(note?.textContent || '').toContain('Header was turned off');
    expect(component.buildConfig().source.fileAttributes.csvAttributes.header).toBeFalse();
  });

  it('the sample-file analysis with header false unticks Header and shows the note', () => {
    component.csvHeader = true;
    analyzeSampleOnFirstStep();
    expect(component.csvHeader).toBeFalse();
    expect(el.querySelector('.header-adjusted')).not.toBeNull();
    component.sourceType = 'csv';
    expect(component.buildConfig().source.fileAttributes.csvAttributes.header).toBeFalse();
  });

  it('a response with header true leaves the checkbox as the user set it', () => {
    generateResponse = responseWithHeader(true);
    component.csvHeader = true;
    generateOnSchemaStep();
    expect(component.csvHeader).toBeTrue();
    expect(el.querySelector('.header-adjusted')).toBeNull();

    component.csvHeader = false;
    generateOnSchemaStep();
    expect(component.csvHeader).withContext('never turned on by the response').toBeFalse();
    expect(el.querySelector('.header-adjusted')).toBeNull();
  });

  it('a response without the flag leaves the checkbox as the user set it', () => {
    generateResponse = responseWithHeader(undefined);
    component.csvHeader = true;
    generateOnSchemaStep();
    expect(component.csvHeader).toBeTrue();
    expect(el.querySelector('.header-adjusted')).toBeNull();

    generateResponse = { name: 'people', source: { schemaProperties: { fields: numbered } } };
    component.csvHeader = false;
    generateOnSchemaStep();
    expect(component.csvHeader).toBeFalse();
    expect(el.querySelector('.header-adjusted')).toBeNull();
  });
});
