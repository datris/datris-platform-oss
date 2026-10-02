/**
 * Story: Field protection 4 — Protect column in the wizard schema step +
 * Suggest button (plans/stories/field-protection-4-wizard-ui.md), Files:
 * pipeline-view "Protected fields" row (the detail-page half of the Live
 * acceptance bullet: "the detail page lists the three fields").
 *
 * Pins: a pipeline whose source schema carries `protect` shows one row
 * labelled "Protected fields" reading `mrn → hmac · email → mask (domain) ·
 * ssn → dropped` (fields without protect are not listed); a pipeline with no
 * `protect` anywhere shows no such row.
 *
 * DOM contract (the story names no class): the row is any element whose text
 * contains "Protected fields"; its entries are matched on the page text with
 * whitespace collapsed. The new `protectedFields` getter is reached through
 * `any`.
 */
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NO_ERRORS_SCHEMA } from '@angular/core';
import { ActivatedRoute, Router, convertToParamMap } from '@angular/router';
import { of } from 'rxjs';

import { PipelineViewComponent } from './pipeline-view.component';
import { PipelineService } from '../pipeline.service';
import { LineageService } from '../lineage.service';
import { AuthService } from '../auth.service';

const NAME = 'patients';

function cfg(fields: any[]): any {
  return {
    name: NAME,
    source: { fileAttributes: { csvAttributes: { delimiter: ',' } }, schemaProperties: { dbName: 'datris', fields } },
    destination: { database: { dbName: 'datris', schema: 'public', table: 'patients', usePostgres: true } }
  };
}

describe('PipelineViewComponent — Protected fields row', () => {
  let fixture: ComponentFixture<PipelineViewComponent>;

  function render(config: any): HTMLElement {
    TestBed.configureTestingModule({
      declarations: [PipelineViewComponent],
      schemas: [NO_ERRORS_SCHEMA],
      providers: [
        { provide: ActivatedRoute, useValue: { snapshot: { paramMap: convertToParamMap({ name: NAME }) } } },
        { provide: Router, useValue: jasmine.createSpyObj('Router', ['navigate']) },
        { provide: PipelineService, useValue: { getPipeline: () => of(config), getUnityCatalog: () => of({ enabled: false }) } },
        { provide: LineageService, useValue: { neighborhood: () => of(null) } },
        { provide: AuthService, useValue: { canWrite: () => false } }
      ]
    });
    fixture = TestBed.createComponent(PipelineViewComponent);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  afterEach(() => fixture?.destroy());

  function text(el: HTMLElement): string {
    return (el.textContent || '').replace(/\s+/g, ' ');
  }

  it('lists protected fields and their methods', () => {
    const el = render(cfg([
      { name: 'mrn', type: 'string', protect: { method: 'hmac' } },
      { name: 'email', type: 'string', protect: { method: 'mask', preserve: 'domain' } },
      { name: 'ssn', type: 'string', protect: { method: 'drop' } },
      { name: 'city', type: 'string' }
    ]));
    const t = text(el);
    expect(t).toContain('Protected fields');
    expect(t).toContain('mrn → hmac');
    expect(t).toContain('email → mask (domain)');
    expect(t).toContain('ssn → dropped');
    expect(t).not.toContain('city →');
    const pf = (fixture.componentInstance as any).protectedFields;
    expect(Array.isArray(pf) ? pf.length : -1).withContext('protectedFields getter').toBe(3);
  });

  it('a mask without preserve shows no parenthesis', () => {
    const el = render(cfg([{ name: 'card', type: 'string', protect: { method: 'mask' } }]));
    const t = text(el);
    expect(t).toContain('card → mask');
    expect(t).not.toContain('card → mask (');
  });

  it('no row for a pipeline without protect', () => {
    const el = render(cfg([{ name: 'id', type: 'string' }, { name: 'amount', type: 'double' }]));
    expect(text(el)).not.toContain('Protected fields');
  });
});
