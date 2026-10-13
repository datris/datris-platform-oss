/**
 * Story: Catalog backup and restore: CLI commands, Catalog-tab controls, docs
 * (plans/stories/catalog-backup-restore-ui-cli.md) — Catalog tab
 * (DataCatalogComponent). Covers UI Acceptance bullets 3-5.
 *
 * Contract pinned here (names the story left open are chosen here; keep
 * them or update this spec):
 *  Card header (named catalogs, any role; export is read-gated): `button.download-catalog-btn`;
 *    click -> `PipelineService.exportCatalog(name)`. Uncataloged: none.
 *  Page header (next to + Create Catalog): `button.import-catalog-btn` opens
 *    `.import-panel` containing
 *      `input[type=file]`, `input.import-name-input` (target name),
 *      `input[type=radio][value=new]` (default) / `input[type=radio][value=overwrite]`,
 *      overwrite reveals `input.import-confirm-input` (type-to-confirm),
 *      `input[type=checkbox].import-skip-state` ("Skip incremental tap state"),
 *      `button.import-restore-btn`.
 *    Restore calls `PipelineService.restoreCatalog(name, file, mode, confirm?, restoreState)`;
 *    the service resolves `HttpResponse` (observe:'response') on 200/207.
 *  Responses: 409 -> each `clashes[]` entry in `.move-error-banner`, nothing
 *    else changes; 207 -> each `failed[]` name and error on the page;
 *    200 -> "Restored N taps (disabled) and M pipelines" plus the missing
 *    secret names, list reloaded, restored card shown expanded.
 */
import { ComponentFixture, TestBed, fakeAsync, tick, flush, discardPeriodicTasks } from '@angular/core/testing';
import { CUSTOM_ELEMENTS_SCHEMA, NO_ERRORS_SCHEMA } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { provideRouter } from '@angular/router';
import { HttpErrorResponse, HttpResponse } from '@angular/common/http';
import { of, throwError, Subject, Observable } from 'rxjs';

import { DataCatalogComponent } from './data-catalog.component';
import { TapService } from '../tap.service';
import { PipelineService } from '../pipeline.service';
import { AuthService } from '../auth.service';
import { CatalogAssistantStateService } from '../catalog-chat/catalog-assistant-state.service';

function httpError(status: number, body: any): Observable<never> {
  return throwError(() => new HttpErrorResponse({ status, error: body, statusText: 'x' }));
}

describe('DataCatalogComponent — catalog download and import', () => {
  let fixture: ComponentFixture<DataCatalogComponent>;
  let component: any;
  let el: HTMLElement;
  let tapsData: any[];
  let pipelinesData: any[];
  let tapSvc: any;
  let pipeSvc: any;

  beforeEach(async () => {
    sessionStorage.removeItem('catalog.expanded');
    tapsData = [
      { name: '__catalog__e2e_a', catalog: 'e2e_a', description: 'Catalog placeholder' },
      { name: 't_a', catalog: 'e2e_a' },
      { name: 't_u' }
    ];
    pipelinesData = [
      { name: 'p_a', catalog: 'e2e_a' },
      { name: 'p_u' }
    ];
    tapSvc = {
      getTaps: jasmine.createSpy('getTaps').and.callFake(() => of(tapsData)),
      deleteTap: jasmine.createSpy('deleteTap').and.returnValue(of({})),
      createOrUpdateTap: jasmine.createSpy('createOrUpdateTap').and.returnValue(of({}))
    };
    pipeSvc = {
      getPipelines: jasmine.createSpy('getPipelines').and.callFake(() => of(pipelinesData)),
      deletePipeline: jasmine.createSpy('deletePipeline').and.returnValue(of({})),
      createPipeline: jasmine.createSpy('createPipeline').and.returnValue(of('ok')),
      renameCatalog: jasmine.createSpy('renameCatalog').and.returnValue(of({ renamed: [], failed: [], affectedKeys: [] })),
      deleteCatalog: jasmine.createSpy('deleteCatalog').and.returnValue(of({ detached: [], failed: [] })),
      // Never emits: the test only checks the call and the in-flight state.
      exportCatalog: jasmine.createSpy('exportCatalog').and.callFake(() => new Subject<any>()),
      restoreCatalog: jasmine.createSpy('restoreCatalog').and.returnValue(of(new HttpResponse({
        status: 200,
        body: { restored: { taps: [], pipelines: [] }, skipped: [], failed: [], missingSecrets: [], warnings: [], tapsDisabled: true }
      })))
    };

    await TestBed.configureTestingModule({
      declarations: [DataCatalogComponent],
      imports: [FormsModule],
      schemas: [CUSTOM_ELEMENTS_SCHEMA, NO_ERRORS_SCHEMA],
      providers: [
        provideRouter([]),
        { provide: TapService, useValue: tapSvc },
        { provide: PipelineService, useValue: pipeSvc },
        { provide: AuthService, useValue: { canWrite: () => true } },
        { provide: CatalogAssistantStateService, useValue: { changed$: new Subject<void>(), seedDraft: () => {} } }
      ]
    }).compileComponents();

    fixture = TestBed.createComponent(DataCatalogComponent);
    component = fixture.componentInstance as any;
    el = fixture.nativeElement as HTMLElement;
  });

  function fa(body: () => void) {
    return fakeAsync(() => {
      body();
      fixture.destroy();
      discardPeriodicTasks();
      flush();
    });
  }

  function settle(): void {
    fixture.detectChanges();
    tick();
    fixture.detectChanges();
  }

  function cards(): HTMLElement[] {
    return Array.from(el.querySelectorAll('.catalog-card')) as HTMLElement[];
  }

  function cardNames(): string[] {
    return cards().map(c => (c.querySelector('.catalog-name')?.textContent || '').trim());
  }

  function card(name: string): HTMLElement {
    const c = cards().find(x => (x.querySelector('.catalog-name')?.textContent || '').trim() === name);
    expect(c).withContext('card for ' + name).toBeDefined();
    return c!;
  }

  function mutatingCalls(): number {
    return tapSvc.deleteTap.calls.count() + tapSvc.createOrUpdateTap.calls.count() +
      pipeSvc.deletePipeline.calls.count() + pipeSvc.createPipeline.calls.count();
  }

  function typeInto(input: HTMLInputElement, value: string): void {
    input.value = value;
    input.dispatchEvent(new Event('input'));
    settle();
  }

  function openImport(): HTMLElement | null {
    const btn = el.querySelector('button.import-catalog-btn') as HTMLButtonElement | null;
    expect(btn).withContext('Import catalog button in the page header').not.toBeNull();
    if (!btn) return null;
    btn.click();
    settle();
    const panel = el.querySelector('.import-panel') as HTMLElement | null;
    expect(panel).withContext('.import-panel after clicking Import catalog').not.toBeNull();
    return panel;
  }

  function pickFile(panel: HTMLElement, name = 'e2e_src.zip'): File {
    const file = new File([new Uint8Array([0x50, 0x4b, 0x03, 0x04, 1, 2, 3])], name, { type: 'application/zip' });
    const input = panel.querySelector('input[type=file]') as HTMLInputElement | null;
    expect(input).withContext('file input in the import panel').not.toBeNull();
    if (input) {
      const dt = new DataTransfer();
      dt.items.add(file);
      input.files = dt.files;
      input.dispatchEvent(new Event('change'));
      settle();
    }
    return file;
  }

  function nameInput(panel: HTMLElement): HTMLInputElement {
    const i = panel.querySelector('input.import-name-input') as HTMLInputElement | null;
    expect(i).withContext('input.import-name-input').not.toBeNull();
    return i!;
  }

  function restoreBtn(panel: HTMLElement): HTMLButtonElement {
    const b = panel.querySelector('button.import-restore-btn') as HTMLButtonElement | null;
    expect(b).withContext('button.import-restore-btn').not.toBeNull();
    return b!;
  }

  /** Opens the panel, picks a file and sets the target name. */
  function prepareImport(target: string): HTMLElement | null {
    settle();
    const panel = openImport();
    if (!panel) return null;
    pickFile(panel);
    const ni = nameInput(panel);
    if (!ni) return null;
    typeInto(ni, target);
    return panel;
  }

  // ── Acceptance bullet 3: skip-state checkbox ───────────────────────────

  it('skip-state checkbox checked passes restoreState=false to restoreCatalog', fa(() => {
    const panel = prepareImport('e2e_clone');
    if (!panel) return;
    const cb = panel.querySelector('input[type=checkbox].import-skip-state') as HTMLInputElement | null;
    expect(cb).withContext('skip-state checkbox').not.toBeNull();
    if (!cb) return;
    cb.click();
    settle();
    restoreBtn(panel).click();
    settle();
    expect(pipeSvc.restoreCatalog).toHaveBeenCalledTimes(1);
    const args = pipeSvc.restoreCatalog.calls.mostRecent().args;
    expect(args[0]).toBe('e2e_clone');
    expect(args[1] instanceof File).withContext('file passed through').toBeTrue();
    expect(args[2]).toBe('new');
    expect(args[4]).withContext('restoreState').toBe(false);
  }));

  it('skip-state checkbox unchecked passes restoreState omitted or true', fa(() => {
    const panel = prepareImport('e2e_clone');
    if (!panel) return;
    restoreBtn(panel).click();
    settle();
    expect(pipeSvc.restoreCatalog).toHaveBeenCalledTimes(1);
    const args = pipeSvc.restoreCatalog.calls.mostRecent().args;
    expect(args[4] === undefined || args[4] === true).withContext('restoreState was ' + args[4]).toBeTrue();
  }));

  // ── Acceptance bullet 4: download icon, import panel validation ───────

  it('named catalog cards show the download icon; Uncataloged shows none', fa(() => {
    settle();
    const named = card('e2e_a');
    expect(named.querySelector('.download-catalog-btn')).withContext('download icon on a named card').not.toBeNull();
    const unc = card('Uncataloged');
    expect(unc.querySelector('.download-catalog-btn')).withContext('no download icon on Uncataloged').toBeNull();
  }));

  it('clicking the download icon calls exportCatalog with the catalog name', fa(() => {
    settle();
    const btn = card('e2e_a').querySelector('.download-catalog-btn') as HTMLButtonElement | null;
    expect(btn).withContext('download icon').not.toBeNull();
    if (!btn) return;
    btn.click();
    settle();
    expect(pipeSvc.exportCatalog).toHaveBeenCalledWith('e2e_a');
    expect(mutatingCalls()).toBe(0);
  }));

  it('import panel keeps Restore disabled for an empty name and makes no call', fa(() => {
    const panel = prepareImport('');
    if (!panel) return;
    const ni = nameInput(panel);
    if (ni) typeInto(ni, '');
    const b = restoreBtn(panel);
    expect(b.disabled).withContext('Restore disabled with an empty name').toBeTrue();
    b.click();
    settle();
    expect(pipeSvc.restoreCatalog).not.toHaveBeenCalled();
  }));

  it('import panel refuses an invalid name (never sends it to the server)', fa(() => {
    const panel = prepareImport('bad name!/x');
    if (!panel) return;
    const b = restoreBtn(panel);
    b.click();
    settle();
    const calls = pipeSvc.restoreCatalog.calls.all();
    for (const c of calls) {
      expect(/^[A-Za-z0-9_-]+$/.test(c.args[0])).withContext('name sent: ' + c.args[0]).toBeTrue();
    }
    if (calls.length === 0) {
      expect(b.disabled).withContext('Restore disabled for an invalid name').toBeTrue();
    }
  }));

  it('overwrite mode keeps Restore disabled until the catalog name is typed, then sends confirm', fa(() => {
    const panel = prepareImport('e2e_a');
    if (!panel) return;
    const ow = panel.querySelector('input[type=radio][value=overwrite]') as HTMLInputElement | null;
    expect(ow).withContext('overwrite radio').not.toBeNull();
    if (!ow) return;
    ow.click();
    settle();
    const confirm = panel.querySelector('input.import-confirm-input') as HTMLInputElement | null;
    expect(confirm).withContext('type-to-confirm input in overwrite mode').not.toBeNull();
    if (!confirm) return;
    expect(restoreBtn(panel).disabled).withContext('disabled before typing').toBeTrue();
    typeInto(confirm, 'e2e_');
    expect(restoreBtn(panel).disabled).withContext('disabled on a partial name').toBeTrue();
    restoreBtn(panel).click();
    settle();
    expect(pipeSvc.restoreCatalog).not.toHaveBeenCalled();
    typeInto(confirm, 'e2e_a');
    expect(restoreBtn(panel).disabled).withContext('enabled once the name matches').toBeFalse();
    restoreBtn(panel).click();
    settle();
    expect(pipeSvc.restoreCatalog).toHaveBeenCalledTimes(1);
    const args = pipeSvc.restoreCatalog.calls.mostRecent().args;
    expect(args[0]).toBe('e2e_a');
    expect(args[2]).toBe('overwrite');
    expect(args[3]).toBe('e2e_a');
  }));

  it('new mode is the default', fa(() => {
    settle();
    const panel = openImport();
    if (!panel) return;
    const nw = panel.querySelector('input[type=radio][value=new]') as HTMLInputElement | null;
    expect(nw).withContext('new radio').not.toBeNull();
    expect(nw?.checked).withContext('new is the default mode').toBeTrue();
  }));

  // ── Acceptance bullet 5: response handling ────────────────────────────

  it('a 409 lists each clash in the banner and nothing changes on the page', fa(() => {
    pipeSvc.restoreCatalog.and.returnValue(httpError(409, {
      error: "Catalog 'e2e_a' already exists",
      clashes: ["tap 't_a' already exists", "pipeline 'p_a' already exists"]
    }));
    const panel = prepareImport('e2e_a');
    if (!panel) return;
    const before = cardNames();
    restoreBtn(panel).click();
    settle();
    const banner = el.querySelector('.move-error-banner') as HTMLElement | null;
    expect(banner).withContext('.move-error-banner on 409').not.toBeNull();
    const text = banner?.textContent || '';
    expect(text).toContain("tap 't_a' already exists");
    expect(text).toContain("pipeline 'p_a' already exists");
    expect(cardNames()).toEqual(before);
    expect(mutatingCalls()).toBe(0);
  }));

  it('a 207 lists each failed item and its error', fa(() => {
    pipeSvc.restoreCatalog.and.returnValue(of(new HttpResponse({
      status: 207,
      body: {
        restored: { taps: ['t_ok'], pipelines: ['p_ok'] }, skipped: [],
        failed: [{ name: 't_broken', error: 'script write failed' }],
        missingSecrets: [], warnings: [], tapsDisabled: true
      }
    })));
    const panel = prepareImport('e2e_r');
    if (!panel) return;
    restoreBtn(panel).click();
    settle();
    const text = el.textContent || '';
    expect(text).toContain('t_broken');
    expect(text).toContain('script write failed');
  }));

  it('a 200 shows the counts and missing secrets, reloads, and shows the restored card expanded', fa(() => {
    pipeSvc.restoreCatalog.and.callFake(() => {
      tapsData = tapsData.concat([
        { name: '__catalog__e2e_r', catalog: 'e2e_r', description: 'Catalog placeholder', enabled: false },
        { name: 't_r', catalog: 'e2e_r', enabled: false }
      ]);
      pipelinesData = pipelinesData.concat([{ name: 'p_r', catalog: 'e2e_r' }]);
      return of(new HttpResponse({
        status: 200,
        body: {
          restored: { taps: ['t_r'], pipelines: ['p_r'] }, skipped: [], failed: [],
          missingSecrets: [{ name: 'stripe_key', usedBy: ['t_r'] }], warnings: [],
          tapsDisabled: true, stateRestored: true
        }
      }));
    });
    const panel = prepareImport('e2e_r');
    if (!panel) return;
    const loadsBefore = tapSvc.getTaps.calls.count();
    restoreBtn(panel).click();
    settle();
    const text = (el.textContent || '').replace(/\s+/g, ' ');
    expect(text).toMatch(/Restored 1 taps? \(disabled\) and 1 pipelines?/);
    expect(text).toMatch(/missing secrets?:[^]*stripe_key/i);
    expect(tapSvc.getTaps.calls.count()).withContext('list reloaded').toBeGreaterThan(loadsBefore);
    const c = card('e2e_r');
    expect(c?.querySelector('.catalog-card-body')).withContext('restored card expanded').not.toBeNull();
  }));

  // ── E2E follow-up: the no-state sentence only when skip-state was ticked ─

  function overwriteResult(): void {
    pipeSvc.restoreCatalog.and.returnValue(of(new HttpResponse({
      status: 200,
      body: {
        restored: { taps: ['t_a'], pipelines: ['p_a'] }, skipped: [], failed: [],
        missingSecrets: [], warnings: [], tapsDisabled: true, stateRestored: false
      }
    })));
  }

  it('overwrite with stateRestored=false and skip-state unticked says nothing about state', fa(() => {
    overwriteResult();
    const panel = prepareImport('e2e_a');
    if (!panel) return;
    (panel.querySelector('input[type=radio][value=overwrite]') as HTMLInputElement).click();
    settle();
    typeInto(panel.querySelector('input.import-confirm-input') as HTMLInputElement, 'e2e_a');
    restoreBtn(panel).click();
    settle();
    const text = (el.textContent || '').replace(/\s+/g, ' ');
    expect(text).toMatch(/Restored 1 taps? \(disabled\) and 1 pipelines?/);
    expect(text).not.toContain('without incremental state');
  }));

  it('skip-state ticked says the taps start without incremental state', fa(() => {
    overwriteResult();
    const panel = prepareImport('e2e_clone');
    if (!panel) return;
    (panel.querySelector('input[type=checkbox].import-skip-state') as HTMLInputElement).click();
    settle();
    restoreBtn(panel).click();
    settle();
    const text = (el.textContent || '').replace(/\s+/g, ' ');
    expect(text).toContain('Taps start without incremental state.');
  }));

  // ── Info icons in the import panel ────────────────────────────────────

  for (const [label, iconSel, infoSel, phrase] of [
    ['overwrite', '.overwrite-info-icon', '.overwrite-info', 'Nothing is merged'],
    ['skip-state', '.skip-state-info-icon', '.skip-state-info', 'sync position']
  ]) {
    it(`the ${label} info icon toggles its explanation`, fa(() => {
      settle();
      const panel = openImport();
      if (!panel) return;
      const icon = panel.querySelector(iconSel) as HTMLElement | null;
      expect(icon).withContext(label + ' info icon').not.toBeNull();
      if (!icon) return;
      expect(icon.getAttribute('role')).toBe('button');
      expect(icon.getAttribute('aria-label')).withContext('aria-label').toBeTruthy();
      expect(panel.querySelector(infoSel)).withContext('hidden by default').toBeNull();
      expect(panel.textContent || '').not.toContain(phrase);
      icon.click();
      settle();
      expect(panel.querySelector(infoSel)).withContext('shown after click').not.toBeNull();
      expect(panel.textContent || '').toContain(phrase);
      icon.click();
      settle();
      expect(panel.querySelector(infoSel)).withContext('hidden after second click').toBeNull();
      expect(panel.textContent || '').not.toContain(phrase);
      icon.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter' }));
      settle();
      expect(panel.querySelector(infoSel)).withContext('Enter opens it').not.toBeNull();
      icon.dispatchEvent(new KeyboardEvent('keydown', { key: ' ' }));
      settle();
      expect(panel.querySelector(infoSel)).withContext('Space closes it').toBeNull();
    }));
  }
});
