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
 *
 * Story: Tap dependencies (plans/stories/tap-dependency-order.md), Acceptance
 * bullet 8: the restore response's `dependentTaps: N` (N > 0) appends to the
 * `.import-result-banner` "N taps read tables produced by other taps in this
 * catalog; run their sources first (see enable order)." `0` or absent: nothing.
 *
 * Story: Enable a catalog's taps in dependency order
 * (plans/stories/tap-dependency-order-enable.md), Acceptance bullet 7.
 * Contract pinned here (names the story left open are chosen here):
 *  Card header: `button.enable-ordered-btn` ("Enable in order") for write
 *    users on named catalogs with at least one disabled (`enabled === false`)
 *    non-placeholder tap; never on Uncataloged; absent when every tap is enabled.
 *  Click -> `PipelineService.enableOrder(name)`, then `.enable-ordered-confirm`
 *    (copy of the delete-confirm block) lists each level's tap names and has
 *    `button.enable-ordered-start` -> `PipelineService.enableOrdered(name)`.
 *  On the 202 body `{jobId, catalog, plan}`: poll
 *    `PipelineService.enableOrderedStatus(name)` every 5 s; each status
 *    `taps[]` entry `{name, level, phase, reason}` renders one
 *    `.enable-ordered-row` (tap name, level, phase, reason) inside
 *    `.enable-ordered-progress`, with `button.enable-ordered-cancel` ->
 *    `PipelineService.cancelEnableOrdered(name)`.
 *  A status with `active: false` (finished: `finishedAt` set) stops polling
 *    and calls loadCatalogs(). 403/409 from enableOrdered -> `.move-error-banner`.
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
  let enableOrderBody: any;
  let jobStarted: any;
  let jobStatus: any;

  beforeEach(async () => {
    sessionStorage.removeItem('catalog.expanded');
    enableOrderBody = {
      catalog: 'e2e_a', cycles: [], warnings: [],
      levels: [
        { level: 1, taps: [{ name: 't_a', enabled: false, blockedBy: [], hasPassingTest: false, ready: false }] },
        { level: 2, taps: [{ name: 't_b', enabled: false, blockedBy: ['t_a'], hasPassingTest: false, ready: false }] }
      ]
    };
    jobStarted = { jobId: 'job-1', catalog: 'e2e_a', plan: { levels: enableOrderBody.levels, cycles: [] } };
    jobStatus = { active: false };
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
      enableOrder: jasmine.createSpy('enableOrder').and.callFake(() => of(enableOrderBody)),
      enableOrdered: jasmine.createSpy('enableOrdered').and.callFake(() => of(jobStarted)),
      enableOrderedStatus: jasmine.createSpy('enableOrderedStatus').and.callFake(() => of(jobStatus)),
      cancelEnableOrdered: jasmine.createSpy('cancelEnableOrdered').and.returnValue(of({ cancelled: true })),
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

  // ── Info icons in the import panel (popovers) ─────────────────────────

  for (const [label, iconSel, infoSel, phrase] of [
    ['overwrite', '.overwrite-info-icon', '.overwrite-info', 'Nothing is merged'],
    ['skip-state', '.skip-state-info-icon', '.skip-state-info', 'sync position']
  ]) {
    it(`the ${label} info icon toggles its popover`, fa(() => {
      settle();
      const panel = openImport();
      if (!panel) return;
      const icon = panel.querySelector(iconSel) as HTMLElement | null;
      expect(icon).withContext(label + ' info icon').not.toBeNull();
      if (!icon) return;
      expect(icon.getAttribute('role')).toBe('button');
      expect(icon.getAttribute('aria-label')).withContext('aria-label').toBeTruthy();
      expect(icon.getAttribute('aria-haspopup')).toBe('dialog');
      expect(panel.querySelector(infoSel)).withContext('absent by default').toBeNull();
      expect(panel.textContent || '').not.toContain(phrase);
      icon.click();
      settle();
      const pop = panel.querySelector(infoSel) as HTMLElement | null;
      expect(pop).withContext('present after click').not.toBeNull();
      expect(pop?.classList.contains('info-popover')).withContext('rendered as a popover').toBeTrue();
      expect(pop?.getAttribute('role')).toBe('dialog');
      expect(pop?.getAttribute('aria-label')).withContext('popover aria-label').toBeTruthy();
      expect(panel.textContent || '').toContain(phrase);
      expect(icon.getAttribute('aria-expanded')).toBe('true');
      // A click inside the popover keeps it open.
      pop!.click();
      settle();
      expect(panel.querySelector(infoSel)).withContext('click inside keeps it open').not.toBeNull();
      icon.click();
      settle();
      expect(panel.querySelector(infoSel)).withContext('absent after second click').toBeNull();
      expect(panel.textContent || '').not.toContain(phrase);
      icon.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter' }));
      settle();
      expect(panel.querySelector(infoSel)).withContext('Enter opens it').not.toBeNull();
      icon.dispatchEvent(new KeyboardEvent('keydown', { key: ' ' }));
      settle();
      expect(panel.querySelector(infoSel)).withContext('Space closes it').toBeNull();
    }));

    it(`the ${label} popover closes on Escape`, fa(() => {
      settle();
      const panel = openImport();
      if (!panel) return;
      (panel.querySelector(iconSel) as HTMLElement).click();
      settle();
      expect(panel.querySelector(infoSel)).not.toBeNull();
      document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
      settle();
      expect(panel.querySelector(infoSel)).withContext('Escape closes it').toBeNull();
    }));

    it(`the ${label} popover closes on a click outside`, fa(() => {
      settle();
      const panel = openImport();
      if (!panel) return;
      (panel.querySelector(iconSel) as HTMLElement).click();
      settle();
      expect(panel.querySelector(infoSel)).not.toBeNull();
      document.body.click();
      settle();
      expect(panel.querySelector(infoSel)).withContext('outside click closes it').toBeNull();
    }));

    it(`the ${label} popover closes with its close button`, fa(() => {
      settle();
      const panel = openImport();
      if (!panel) return;
      (panel.querySelector(iconSel) as HTMLElement).click();
      settle();
      (panel.querySelector(infoSel + ' .info-popover-close') as HTMLElement).click();
      settle();
      expect(panel.querySelector(infoSel)).withContext('close button closes it').toBeNull();
    }));
  }

  it('opening one info popover closes the other', fa(() => {
    settle();
    const panel = openImport();
    if (!panel) return;
    (panel.querySelector('.overwrite-info-icon') as HTMLElement).click();
    settle();
    expect(panel.querySelector('.overwrite-info')).not.toBeNull();
    (panel.querySelector('.skip-state-info-icon') as HTMLElement).click();
    settle();
    expect(panel.querySelector('.skip-state-info')).withContext('second open').not.toBeNull();
    expect(panel.querySelector('.overwrite-info')).withContext('first closed').toBeNull();
    expect(panel.querySelectorAll('.info-popover').length).toBe(1);
  }));

  // ── Tap dependencies: dependentTaps sentence in the restore banner ─────

  const depSentence = 'taps read tables produced by other taps in this catalog; run their sources first (see enable order).';

  function restoreWith(extra: any): string {
    pipeSvc.restoreCatalog.and.returnValue(of(new HttpResponse({
      status: 200,
      body: Object.assign({
        restored: { taps: ['t1', 't2', 't3', 't4'], pipelines: ['p1'] }, skipped: [], failed: [],
        missingSecrets: [], warnings: [], tapsDisabled: true, stateRestored: true
      }, extra)
    })));
    const panel = prepareImport('e2e_dep');
    if (!panel) return '';
    restoreBtn(panel).click();
    settle();
    const banner = el.querySelector('.import-result-banner') as HTMLElement | null;
    expect(banner).withContext('.import-result-banner after restore').not.toBeNull();
    return (banner?.textContent || '').replace(/\s+/g, ' ');
  }

  it('dependentTaps: 3 appends the dependency sentence to the restore banner', fa(() => {
    const text = restoreWith({ dependentTaps: 3 });
    expect(text).toContain('3 ' + depSentence);
  }));

  it('the dependency sentence is separated from the sentence before it by a space', fa(() => {
    const text = restoreWith({ dependentTaps: 2 });
    expect(text).toMatch(/\. 2 taps read tables produced/);
    expect(text).not.toMatch(/\.2 taps/);
  }));

  it('dependentTaps: 1 uses the singular', fa(() => {
    const text = restoreWith({ dependentTaps: 1 });
    expect(text).toContain('1 tap reads tables produced by other taps in this catalog; run their sources first (see enable order).');
  }));

  it('dependentTaps: 0 renders no dependency sentence', fa(() => {
    const text = restoreWith({ dependentTaps: 0 });
    expect(text).toMatch(/Restored 4 taps \(disabled\)/);
    expect(text).not.toContain('read tables produced by other taps');
  }));

  it('a restore body without dependentTaps renders no dependency sentence', fa(() => {
    const text = restoreWith({});
    expect(text).toMatch(/Restored 4 taps \(disabled\)/);
    expect(text).not.toContain('read tables produced by other taps');
  }));

  // ── Enable in order (tap-dependency-order-enable.md, bullet 7) ────────

  function withEnableTaps(): void {
    tapsData = [
      { name: '__catalog__e2e_a', catalog: 'e2e_a', description: 'Catalog placeholder' },
      { name: 't_a', catalog: 'e2e_a', enabled: false },
      { name: 't_b', catalog: 'e2e_a', enabled: false },
      { name: '__catalog__e2e_on', catalog: 'e2e_on', description: 'Catalog placeholder' },
      { name: 't_on', catalog: 'e2e_on', enabled: true },
      { name: 't_u', enabled: false }
    ];
  }

  function runningStatus(): any {
    return {
      active: true, jobId: 'job-1', catalog: 'e2e_a',
      taps: [
        { name: 't_a', level: 1, phase: 'landed', reason: null },
        { name: 't_b', level: 2, phase: 'running', reason: null }
      ]
    };
  }

  /** Clicks Enable in order on e2e_a, then Start in the confirm panel. */
  function startJob(): boolean {
    const btn = card('e2e_a').querySelector('button.enable-ordered-btn') as HTMLButtonElement | null;
    expect(btn).withContext('Enable in order button on e2e_a').not.toBeNull();
    if (!btn) return false;
    btn.click();
    settle();
    expect(pipeSvc.enableOrder).toHaveBeenCalledWith('e2e_a');
    const panel = el.querySelector('.enable-ordered-confirm') as HTMLElement | null;
    expect(panel).withContext('.enable-ordered-confirm plan panel').not.toBeNull();
    if (!panel) return false;
    const start = panel.querySelector('button.enable-ordered-start') as HTMLButtonElement | null;
    expect(start).withContext('button.enable-ordered-start').not.toBeNull();
    if (!start) return false;
    start.click();
    settle();
    return true;
  }

  it('the Enable in order button shows on a catalog with a disabled tap, not when every tap is enabled, not on Uncataloged', fa(() => {
    withEnableTaps();
    settle();
    expect(card('e2e_a').querySelector('button.enable-ordered-btn')).withContext('catalog with disabled taps').not.toBeNull();
    expect(card('e2e_on').querySelector('button.enable-ordered-btn')).withContext('every tap enabled').toBeNull();
    expect(card('Uncataloged').querySelector('button.enable-ordered-btn')).withContext('Uncataloged').toBeNull();
  }));

  it('the confirm panel lists the plan levels and starts nothing until Start is clicked', fa(() => {
    withEnableTaps();
    settle();
    const btn = card('e2e_a').querySelector('button.enable-ordered-btn') as HTMLButtonElement | null;
    expect(btn).not.toBeNull();
    if (!btn) return;
    btn.click();
    settle();
    const panel = el.querySelector('.enable-ordered-confirm') as HTMLElement | null;
    expect(panel).withContext('.enable-ordered-confirm').not.toBeNull();
    const text = (panel?.textContent || '').replace(/\s+/g, ' ');
    expect(text).toContain('t_a');
    expect(text).toContain('t_b');
    expect(text.indexOf('t_a')).withContext('level 1 before level 2').toBeLessThan(text.indexOf('t_b'));
    expect(pipeSvc.enableOrdered).not.toHaveBeenCalled();
    expect(mutatingCalls()).toBe(0);
  }));

  it('a 202 starts polling every 5 s and renders one row per tap with its phase', fa(() => {
    withEnableTaps();
    jobStatus = runningStatus();
    settle();
    tick(3000);
    if (!startJob()) return;
    expect(pipeSvc.enableOrdered).toHaveBeenCalledWith('e2e_a');
    tick(5000);
    fixture.detectChanges();
    expect(pipeSvc.enableOrderedStatus).toHaveBeenCalledWith('e2e_a');
    const before = pipeSvc.enableOrderedStatus.calls.count();
    tick(5000);
    fixture.detectChanges();
    expect(pipeSvc.enableOrderedStatus.calls.count()).withContext('polled again after 5 s').toBeGreaterThan(before);
    const progress = el.querySelector('.enable-ordered-progress') as HTMLElement | null;
    expect(progress).withContext('.enable-ordered-progress').not.toBeNull();
    const rows = Array.from(el.querySelectorAll('.enable-ordered-row')) as HTMLElement[];
    expect(rows.length).withContext('one row per tap').toBe(2);
    const rowA = rows.find(r => (r.textContent || '').includes('t_a'));
    const rowB = rows.find(r => (r.textContent || '').includes('t_b'));
    expect(rowA?.textContent || '').toContain('landed');
    expect(rowB?.textContent || '').toContain('running');
  }));

  it('Cancel calls cancelEnableOrdered with the catalog name', fa(() => {
    withEnableTaps();
    jobStatus = runningStatus();
    settle();
    if (!startJob()) return;
    tick(5000);
    fixture.detectChanges();
    const cancel = el.querySelector('button.enable-ordered-cancel') as HTMLButtonElement | null;
    expect(cancel).withContext('button.enable-ordered-cancel while the job runs').not.toBeNull();
    if (!cancel) return;
    cancel.click();
    settle();
    expect(pipeSvc.cancelEnableOrdered).toHaveBeenCalledWith('e2e_a');
  }));

  it('a finished job stops polling and reloads catalogs', fa(() => {
    withEnableTaps();
    jobStatus = runningStatus();
    settle();
    tick(3000);
    if (!startJob()) return;
    tick(5000);              // t = 8 s: a running poll
    fixture.detectChanges();
    tick(3000);              // t = 11 s: past the page's own 10 s refresh
    fixture.detectChanges();
    expect(pipeSvc.enableOrderedStatus).toHaveBeenCalled();
    jobStatus = Object.assign(runningStatus(), {
      active: false, finishedAt: 1760000000000,
      taps: [
        { name: 't_a', level: 1, phase: 'landed', reason: null },
        { name: 't_b', level: 2, phase: 'skipped', reason: 'source t_a did not land data' }
      ]
    });
    const loads = tapSvc.getTaps.calls.count();
    tick(5000);              // t = 16 s: the finished poll (before the 20 s refresh)
    fixture.detectChanges();
    expect(tapSvc.getTaps.calls.count()).withContext('catalogs reloaded on finish').toBeGreaterThan(loads);
    const polls = pipeSvc.enableOrderedStatus.calls.count();
    tick(30000);
    fixture.detectChanges();
    expect(pipeSvc.enableOrderedStatus.calls.count()).withContext('no polling after finish').toBe(polls);
  }));

  it('a 409 from enableOrdered shows in the error banner', fa(() => {
    withEnableTaps();
    pipeSvc.enableOrdered.and.returnValue(httpError(409, { error: "An enable job for catalog 'e2e_a' is already running" }));
    settle();
    if (!startJob()) return;
    const banner = el.querySelector('.move-error-banner') as HTMLElement | null;
    expect(banner).withContext('.move-error-banner on 409').not.toBeNull();
    expect(banner?.textContent || '').toContain('already running');
    tick(10000);
    expect(pipeSvc.enableOrderedStatus).not.toHaveBeenCalled();
  }));

  it('a job already running on page load (started elsewhere) shows its progress and Cancel, not the button, and polls', fa(() => {
    withEnableTaps();
    enableOrderBody = Object.assign({}, enableOrderBody, { job: runningStatus() });
    jobStatus = runningStatus();
    settle();
    expect(pipeSvc.enableOrder).toHaveBeenCalledWith('e2e_a');
    expect(pipeSvc.enableOrder).not.toHaveBeenCalledWith('e2e_on');
    expect(pipeSvc.enableOrder).not.toHaveBeenCalledWith('Uncataloged');
    expect(pipeSvc.enableOrdered).withContext('the probe starts nothing').not.toHaveBeenCalled();
    expect(card('e2e_a').querySelector('button.enable-ordered-btn')).withContext('no Start button while a job runs').toBeNull();
    const rows = Array.from(el.querySelectorAll('.enable-ordered-row')) as HTMLElement[];
    expect(rows.length).toBe(2);
    const cancel = el.querySelector('button.enable-ordered-cancel') as HTMLButtonElement | null;
    expect(cancel).withContext('Cancel offered for a job started elsewhere').not.toBeNull();
    tick(5000);
    fixture.detectChanges();
    expect(pipeSvc.enableOrderedStatus).toHaveBeenCalledWith('e2e_a');
    cancel?.click();
    settle();
    expect(pipeSvc.cancelEnableOrdered).toHaveBeenCalledWith('e2e_a');
  }));

  it('the page-load probe seeds nothing when no job is running', fa(() => {
    withEnableTaps();
    settle();
    expect(pipeSvc.enableOrder).toHaveBeenCalledWith('e2e_a');
    expect(el.querySelector('.enable-ordered-progress')).toBeNull();
    expect(card('e2e_a').querySelector('button.enable-ordered-btn')).not.toBeNull();
    tick(10000);
    expect(pipeSvc.enableOrderedStatus).not.toHaveBeenCalled();
    expect(pipeSvc.enableOrdered).not.toHaveBeenCalled();
  }));
});
