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

  /** Page text without the raw config JSON dump (pre.config-json), so the
   *  preset assertions read what the row shows, not the stored JSON. */
  function shown(el: HTMLElement): string {
    const clone = el.cloneNode(true) as HTMLElement;
    clone.querySelectorAll('pre.config-json').forEach(p => p.remove());
    return text(clone);
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

  // Field protection 11 (plans/stories/field-protection-11-safe-harbor-preset-surfaces.md),
  // Acceptance "Pipeline page spec": the Protected fields row reads
  // "Preset: HIPAA Safe Harbor (enforced)" and names the exempt fields when
  // config.protection.preset is set; nothing about a preset otherwise.
  it('a preset pipeline shows the preset and its exempt fields', () => {
    const config = cfg([
      { name: 'mrn', type: 'string', protect: { method: 'hmac' } },
      { name: 'ssn', type: 'string', protect: { method: 'drop' } },
      { name: 'phone', type: 'string' },
      { name: 'fax', type: 'string' }
    ]);
    config.protection = { preset: 'hipaa-safe-harbor', presetExempt: ['phone', 'fax'] };
    const el = render(config);
    const t = shown(el);
    expect(t).toContain('Protected fields');
    expect(t).toContain('Preset: HIPAA Safe Harbor (enforced)');
    expect(t).toMatch(/exempt:?\s*phone,?\s*fax/i);
    expect(t).toContain('mrn → hmac');
    // The raw preset id is not what the user reads.
    expect(t).not.toContain('hipaa-safe-harbor');
  });

  it('a preset pipeline with no exemptions shows the preset and no exempt list', () => {
    const config = cfg([{ name: 'mrn', type: 'string', protect: { method: 'hmac' } }]);
    config.protection = { preset: 'hipaa-safe-harbor' };
    const t = shown(render(config));
    expect(t).toContain('Preset: HIPAA Safe Harbor (enforced)');
    expect(t).not.toMatch(/exempt/i);
  });

  it('a pipeline without a preset shows no preset line', () => {
    const config = cfg([{ name: 'mrn', type: 'string', protect: { method: 'hmac' } }]);
    config.protection = { purgeSource: false };
    const t = shown(render(config));
    expect(t).toContain('mrn → hmac');
    expect(t).not.toContain('Preset:');
    expect(t).not.toMatch(/exempt/i);
  });

  it('no row for a pipeline without protect', () => {
    const el = render(cfg([{ name: 'id', type: 'string' }, { name: 'amount', type: 'double' }]));
    expect(text(el)).not.toContain('Protected fields');
  });
});
