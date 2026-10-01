/**
 * Story: Unity Catalog metadata on by default (`DATRIS_UNITY_CATALOG_DEFAULT`)
 * (plans/stories/uc-default-enabled.md), Acceptance bullet 5.
 *
 * A Databricks pipeline with no `unityCatalog` block that the install default
 * turned on (GET /pipelines/{name}/unity-catalog → enabled:true,
 * enabledBy:"default") shows the Unity Catalog card with an "enabled by
 * default" marker, and the Lineage row is driven by the state's
 * `lineageEnabled` — never by `config.unityCatalog` (undefined here).
 *
 * DOM contract: the card is `.uc-panel` (existing); the marker is any element
 * inside it whose text matches /enabled by default/i.
 */
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NO_ERRORS_SCHEMA } from '@angular/core';
import { ActivatedRoute, Router, convertToParamMap } from '@angular/router';
import { of } from 'rxjs';

import { PipelineViewComponent } from './pipeline-view.component';
import { PipelineService } from '../pipeline.service';
import { LineageService } from '../lineage.service';
import { AuthService } from '../auth.service';

const NAME = 'orders_daily';

const dbxConfig: any = {
  name: NAME,
  destination: { database: { dbName: 'datris', schema: 'default', table: 'orders', useDatabricks: true, credentialsSecret: 'dbx' } }
};

function ucState(over: any): any {
  return {
    pipeline: NAME,
    enabled: false,
    enabledBy: null,
    registerEnabled: false,
    register: 'off',
    lineageEnabled: false,
    lineage: 'off',
    state: 'never',
    coordinates: { catalog: 'datris', schema: 'default', table: 'orders', qualified: 'datris.default.orders' },
    ...over
  };
}

describe('PipelineViewComponent — Unity Catalog enabled by default', () => {
  let fixture: ComponentFixture<PipelineViewComponent>;

  function render(config: any, state: any): HTMLElement {
    TestBed.configureTestingModule({
      declarations: [PipelineViewComponent],
      schemas: [NO_ERRORS_SCHEMA],
      providers: [
        { provide: ActivatedRoute, useValue: { snapshot: { paramMap: convertToParamMap({ name: NAME }) } } },
        { provide: Router, useValue: jasmine.createSpyObj('Router', ['navigate']) },
        { provide: PipelineService, useValue: { getPipeline: () => of(config), getUnityCatalog: () => of(state) } },
        { provide: LineageService, useValue: { neighborhood: () => of(null) } },
        { provide: AuthService, useValue: { canWrite: () => false } }
      ]
    });
    fixture = TestBed.createComponent(PipelineViewComponent);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  afterEach(() => fixture?.destroy());

  function lineageRow(el: HTMLElement): string {
    const rows = Array.from(el.querySelectorAll('.uc-panel .lineage-row'));
    const row = rows.find(r => /^\s*Lineage/.test(r.querySelector('.lineage-label')?.textContent || ''));
    expect(row).withContext('Lineage row in the Unity Catalog card').toBeTruthy();
    const parts = Array.from(row?.querySelectorAll('.lineage-detail, .fresh-chip') || []).map(e => (e.textContent || '').trim());
    return parts.join(' ').replace(/\s+/g, ' ').trim();
  }

  it('shows the card with an "enabled by default" marker for a defaulted pipeline (no unityCatalog block)', () => {
    const el = render(dbxConfig, ucState({ enabled: true, enabledBy: 'default', lineageEnabled: true, lineage: 'never' }));
    const panel = el.querySelector('.uc-panel');
    expect(panel).withContext('Unity Catalog card must render for enabledBy=default').toBeTruthy();
    expect(panel?.textContent || '').toMatch(/enabled by default/i);
    expect(lineageRow(el)).toMatch(/not published yet/);
  });

  it('Lineage row reads lineageEnabled from the state, not config.unityCatalog', () => {
    const el = render(dbxConfig, ucState({ enabled: true, enabledBy: 'default', lineageEnabled: false, lineage: 'off' }));
    expect(lineageRow(el)).toMatch(/\boff\b/);
  });

  it('an explicit opt-in shows the card without the default marker', () => {
    const cfg = { ...dbxConfig, unityCatalog: { enabled: true } };
    const el = render(cfg, ucState({ enabled: true, enabledBy: 'pipeline', lineageEnabled: true, lineage: 'never' }));
    const panel = el.querySelector('.uc-panel');
    expect(panel).toBeTruthy();
    expect(panel?.textContent || '').not.toMatch(/enabled by default/i);
  });

  it('no card when Unity Catalog is off (default disabled, or explicit opt-out)', () => {
    let el = render(dbxConfig, ucState({ enabled: false, enabledBy: null }));
    expect(el.querySelector('.uc-panel')).toBeNull();
    fixture.destroy();
    TestBed.resetTestingModule();
    el = render({ ...dbxConfig, unityCatalog: { enabled: false } }, ucState({ enabled: false, enabledBy: 'pipeline' }));
    expect(el.querySelector('.uc-panel')).toBeNull();
  });
});
