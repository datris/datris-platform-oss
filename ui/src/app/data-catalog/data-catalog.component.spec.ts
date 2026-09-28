/**
 * Story: Catalog rename and delete: UI, docs, changelog
 * (plans/stories/catalog-ops-ui-docs.md) — Catalog tab (DataCatalogComponent).
 *
 * Covers Acceptance bullets 1-6 through the DOM plus the method names the
 * story prescribes. Contract pinned here (names the story left open are
 * chosen here; keep them or update this spec):
 *
 *  Card header (named catalogs, write role):
 *   - `button.rename-catalog-btn` (pencil). Click -> `startRename(cat)` turns
 *     the name into `input.rename-catalog-input` pre-filled with the name.
 *     Enter commits via `commitRename(cat)`, Escape cancels. Commit runs
 *     `sanitizeCatalogName` (case kept) and refuses empty/unchanged values, then calls
 *     `PipelineService.renameCatalog(old, sanitized)`.
 *   - `button.delete-catalog-btn` opens `.delete-confirm` with two radios
 *     `input[type=radio][value=detach]` (default, "Keep items") and
 *     `input[type=radio][value=cascade]` ("Delete items and their data").
 *     Cascade reveals `input[type=text]` inside `.delete-confirm`; the
 *     `.del-yes` button is disabled until the text equals the catalog name.
 *     Confirm calls `PipelineService.deleteCatalog(name, mode, confirm?)`
 *     (component state: `deleteMode`, `confirmText`).
 *  Uncataloged card: neither `.rename-catalog-btn` nor `.delete-catalog-btn`.
 *  Responses: 409 (target exists) and 400 -> server
 *  message in `.move-error-banner`; 200/207 -> each `failed[]` name and error
 *  rendered on the page, list reloaded; non-empty `affectedKeys` ->
 *  `.affected-keys-banner` reading "API keys scoped to '<old>' no longer
 *  match: <labels>", persistent (survives the 6 s move-error timeout) with a
 *  close button.
 *  404 on either endpoint (catalog renamed or deleted elsewhere, card is
 *  stale): the not-found message is shown, the list is reloaded so the stale
 *  card disappears, and NOTHING is written: no per-item fan-out, no retry. "Keep items" never deletes members.
 *  Rename into a name that differs from an existing catalog only by case is
 *  refused client-side (server names are case-sensitive).
 *  "Move all contents": after every move succeeds, the source catalog's
 *  placeholder is removed by its `catalog` FIELD (a legacy placeholder may be
 *  named differently) — either `tapService.deleteTap(<that placeholder>)` or
 *  `pipelineService.deleteCatalog(source, 'detach')`. On any failure, no
 *  placeholder removal.
 */
import { ComponentFixture, TestBed, fakeAsync, tick, flush, discardPeriodicTasks } from '@angular/core/testing';
import { CUSTOM_ELEMENTS_SCHEMA, NO_ERRORS_SCHEMA } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { provideRouter } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { of, throwError, Subject, Observable } from 'rxjs';

import { DataCatalogComponent } from './data-catalog.component';
import { TapService } from '../tap.service';
import { PipelineService } from '../pipeline.service';
import { AuthService } from '../auth.service';
import { CatalogAssistantStateService } from '../catalog-chat/catalog-assistant-state.service';

function httpError(status: number, body: any): Observable<never> {
  return throwError(() => new HttpErrorResponse({ status, error: body, statusText: 'x' }));
}

describe('DataCatalogComponent — catalog rename and delete', () => {
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
      deleteCatalog: jasmine.createSpy('deleteCatalog').and.returnValue(of({ detached: [], failed: [] }))
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

  /** fakeAsync wrapper that tears down the 10 s refresh interval and any
   *  pending banner timeout so the zone exits clean. */
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

  function openRename(name: string): HTMLInputElement | null {
    const c = card(name);
    const btn = c.querySelector('.rename-catalog-btn') as HTMLButtonElement | null;
    expect(btn).withContext('rename control on ' + name).not.toBeNull();
    if (!btn) return null;
    btn.click();
    settle();
    const input = c.querySelector('input.rename-catalog-input') as HTMLInputElement | null;
    expect(input).withContext('rename input after clicking the pencil').not.toBeNull();
    return input;
  }

  function submitRename(input: HTMLInputElement, value: string): void {
    typeInto(input, value);
    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter' }));
    settle();
  }

  function openDelete(name: string): HTMLElement | null {
    const c = card(name);
    const btn = c.querySelector('.delete-catalog-btn') as HTMLButtonElement | null;
    expect(btn).withContext('delete control on ' + name).not.toBeNull();
    if (!btn) return null;
    btn.click();
    settle();
    const confirm = c.querySelector('.delete-confirm') as HTMLElement | null;
    expect(confirm).withContext('.delete-confirm after clicking delete').not.toBeNull();
    return confirm;
  }

  // ── Acceptance 1 ────────────────────────────────────────────────────────

  it('named catalog card shows rename and delete controls; Uncataloged shows neither', fa(() => {
    settle();
    const named = card('e2e_a');
    expect(named.querySelector('.rename-catalog-btn')).withContext('rename control on a named card').not.toBeNull();
    expect(named.querySelector('.delete-catalog-btn')).withContext('delete control on a named card').not.toBeNull();
    const unc = card('Uncataloged');
    expect(unc.querySelector('.rename-catalog-btn')).withContext('no rename on Uncataloged').toBeNull();
    expect(unc.querySelector('.delete-catalog-btn')).withContext('no delete on Uncataloged').toBeNull();
  }));

  it('rename control opens an input pre-filled with the current name; Escape cancels without a call', fa(() => {
    settle();
    const input = openRename('e2e_a');
    if (!input) return;
    expect(input.value).toBe('e2e_a');
    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
    settle();
    expect(card('e2e_a').querySelector('input.rename-catalog-input')).withContext('editor closed by Escape').toBeNull();
    expect(pipeSvc.renameCatalog).not.toHaveBeenCalled();
  }));

  // ── Acceptance 2 ────────────────────────────────────────────────────────

  it('rename input is corrected by sanitizeCatalogName before submit', fa(() => {
    settle();
    const input = openRename('e2e_a');
    if (!input) return;
    submitRename(input, '  My New--Cat!! ');
    expect(pipeSvc.renameCatalog).toHaveBeenCalledTimes(1);
    expect(pipeSvc.renameCatalog.calls.mostRecent().args.slice(0, 2)).toEqual(['e2e_a', 'My_New-Cat']);
  }));

  it('a mixed-case rename such as Sales_Q3 is sent unchanged', fa(() => {
    settle();
    const input = openRename('e2e_a');
    if (!input) return;
    submitRename(input, 'Sales_Q3');
    expect(pipeSvc.renameCatalog).toHaveBeenCalledTimes(1);
    expect(pipeSvc.renameCatalog.calls.mostRecent().args.slice(0, 2)).toEqual(['e2e_a', 'Sales_Q3']);
  }));

  it('the rename editor shows the name rule', fa(() => {
    settle();
    const input = openRename('e2e_a');
    if (!input) return;
    expect(card('e2e_a').querySelector('.rename-rule')?.textContent || '').toContain('Letters, digits, _ and - only');
  }));

  it('rename refuses an unchanged or empty-after-sanitize value without calling the server', fa(() => {
    settle();
    const input = openRename('e2e_a');
    if (!input) return;
    submitRename(input, 'e2e_a');
    const again = card('e2e_a').querySelector('input.rename-catalog-input') as HTMLInputElement | null
      || openRename('e2e_a');
    if (!again) return;
    submitRename(again, '!!!');
    expect(pipeSvc.renameCatalog).not.toHaveBeenCalled();
  }));

  it('rename 409 "already exists" (race) shows the server message with the editor open and reloads the list', fa(() => {
    pipeSvc.renameCatalog.and.callFake(() => {
      // Another client created e2e_b after this page loaded.
      tapsData.push({ name: '__catalog__e2e_b', catalog: 'e2e_b' });
      return httpError(409, { error: "Catalog 'e2e_b' already exists. Use move to merge catalogs." });
    });
    settle();
    const loadsBefore = tapSvc.getTaps.calls.count();
    const input = openRename('e2e_a');
    if (!input) return;
    submitRename(input, 'e2e_b');
    expect(pipeSvc.renameCatalog).toHaveBeenCalledTimes(1);
    expect(tapSvc.getTaps.calls.count()).withContext('list reloaded after 409').toBeGreaterThan(loadsBefore);
    const names = cards().map(c => (c.querySelector('.catalog-name')?.textContent || '').trim());
    expect(names).withContext('new catalog shown after reload').toContain('e2e_b');
    const banner = el.querySelector('.move-error-banner');
    expect(banner).withContext('move-error-banner on 409').not.toBeNull();
    expect(banner?.textContent || '').toContain("Catalog 'e2e_b' already exists. Use move to merge catalogs.");
    expect(mutatingCalls()).withContext('no per-item writes on a refused rename').toBe(0);
    const still = el.querySelector('input.rename-catalog-input') as HTMLInputElement | null;
    expect(still).withContext('editor stays open after a 409').not.toBeNull();
    expect(still?.value).withContext('draft kept after a 409').toBe('e2e_b');
  }));

  it('rename 400 (label rule / reserved name) shows the server message', fa(() => {
    pipeSvc.renameCatalog.and.returnValue(httpError(400, { error: 'newName must match [a-z0-9_-]+' }));
    settle();
    const input = openRename('e2e_a');
    if (!input) return;
    submitRename(input, 'e2e_b');
    expect(el.querySelector('.move-error-banner')?.textContent || '').toContain('newName must match [a-z0-9_-]+');
    expect(mutatingCalls()).toBe(0);
    const still = el.querySelector('input.rename-catalog-input') as HTMLInputElement | null;
    expect(still).withContext('editor stays open after a 400').not.toBeNull();
    expect(still?.value).withContext('draft kept after a 400').toBe('e2e_b');
  }));

  it('a pending rename ignores a second Enter and disables the check button', fa(() => {
    pipeSvc.renameCatalog.and.returnValue(new Observable<any>(() => {}));
    settle();
    const input = openRename('e2e_a');
    if (!input) return;
    submitRename(input, 'e2e_c');
    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter' }));
    settle();
    expect(pipeSvc.renameCatalog).toHaveBeenCalledTimes(1);
    const ok = el.querySelector('.rename-ok') as HTMLButtonElement | null;
    expect(ok).withContext('check button while pending').not.toBeNull();
    expect(ok?.disabled).withContext('check button disabled while pending').toBeTrue();
  }));

  // ── Acceptance 3 ────────────────────────────────────────────────────────

  it('rename to an exact existing catalog name is refused client-side with zero requests', fa(() => {
    tapsData.push({ name: '__catalog__e2e_b', catalog: 'e2e_b' }, { name: 't_b', catalog: 'e2e_b' });
    settle();
    const input = openRename('e2e_a');
    if (!input) return;
    submitRename(input, 'e2e_b');
    expect(pipeSvc.renameCatalog).not.toHaveBeenCalled();
    expect(mutatingCalls()).toBe(0);
    expect(el.querySelector('.move-error-banner')?.textContent || '')
      .toContain(`'e2e_b' already exists. To combine catalogs, use "Move all contents" instead.`);
    const still = el.querySelector('input.rename-catalog-input') as HTMLInputElement | null;
    expect(still).withContext('editor stays open').not.toBeNull();
    expect(still?.value).withContext('draft kept').toBe('e2e_b');
  }));

  it('"move all contents" removes the source placeholder by its catalog field after a full move', fa(() => {
    // Legacy placeholder whose name differs from its catalog field.
    tapsData[0] = { name: '__catalog__legacy', catalog: 'e2e_a', description: 'Catalog placeholder' };
    tapsData.push({ name: '__catalog__e2e_z', catalog: 'e2e_z' });
    settle();
    const src = component.catalogs.find((c: any) => c.name === 'e2e_a');
    component.moveCatalogContents(src, 'e2e_z', new MouseEvent('click'));
    settle();
    const byTap = tapSvc.deleteTap.calls.allArgs().map((a: any[]) => a[0]);
    const byEndpoint = pipeSvc.deleteCatalog.calls.allArgs()
      .some((a: any[]) => a[0] === 'e2e_a' && a[1] === 'detach');
    expect(byTap.includes('__catalog__legacy') || byEndpoint)
      .withContext('placeholder removed via deleteTap(<field-matched placeholder>) or deleteCatalog(source, "detach")')
      .toBeTrue();
    expect(byTap).not.toContain('t_a');
    expect(byTap).not.toContain('__catalog__e2e_z');
  }));

  it('"move all contents" with a failed item leaves the source placeholder alone', fa(() => {
    pipeSvc.createPipeline.and.returnValue(httpError(500, { error: 'boom' }));
    tapsData.push({ name: '__catalog__e2e_z', catalog: 'e2e_z' });
    settle();
    const src = component.catalogs.find((c: any) => c.name === 'e2e_a');
    component.moveCatalogContents(src, 'e2e_z', new MouseEvent('click'));
    settle();
    expect(tapSvc.deleteTap).not.toHaveBeenCalled();
    expect(pipeSvc.deleteCatalog).not.toHaveBeenCalled();
  }));

  // ── Acceptance 4 ────────────────────────────────────────────────────────

  it('delete confirm defaults to "Keep items" and sends mode=detach without deleting members', fa(() => {
    settle();
    const confirm = openDelete('e2e_a');
    if (!confirm) return;
    const detach = confirm.querySelector('input[type=radio][value=detach]') as HTMLInputElement | null;
    const cascade = confirm.querySelector('input[type=radio][value=cascade]') as HTMLInputElement | null;
    expect(detach).withContext('Keep items radio').not.toBeNull();
    expect(cascade).withContext('Delete items and their data radio').not.toBeNull();
    expect(detach?.checked).withContext('Keep items is the default').toBeTrue();
    expect(confirm.querySelector('input[type=text]')).withContext('no typed confirm in keep mode').toBeNull();
    const yes = confirm.querySelector('.del-yes') as HTMLButtonElement;
    expect(yes.disabled).toBeFalse();
    const loadsBefore = tapSvc.getTaps.calls.count();
    yes.click();
    settle();
    expect(pipeSvc.deleteCatalog).toHaveBeenCalledTimes(1);
    const args = pipeSvc.deleteCatalog.calls.mostRecent().args;
    expect(args[0]).toBe('e2e_a');
    expect(args[1]).toBe('detach');
    expect(args[2]).toBeFalsy();
    expect(mutatingCalls()).withContext('keep-items never deletes or rewrites members client-side').toBe(0);
    expect(tapSvc.getTaps.calls.count()).toBeGreaterThan(loadsBefore);
  }));

  it('"Delete items and their data" is disabled until the typed name matches, then sends cascade + confirm', fa(() => {
    settle();
    const confirm = openDelete('e2e_a');
    if (!confirm) return;
    const cascade = confirm.querySelector('input[type=radio][value=cascade]') as HTMLInputElement | null;
    expect(cascade).withContext('cascade radio').not.toBeNull();
    if (!cascade) return;
    cascade.click();
    settle();
    const text = confirm.querySelector('input[type=text]') as HTMLInputElement | null;
    expect(text).withContext('cascade reveals a type-the-name input').not.toBeNull();
    if (!text) return;
    const yes = () => confirm.querySelector('.del-yes') as HTMLButtonElement;
    expect(yes().disabled).withContext('disabled with empty confirm').toBeTrue();
    typeInto(text, 'e2e_');
    expect(yes().disabled).withContext('disabled on partial match').toBeTrue();
    typeInto(text, 'E2E_A');
    expect(yes().disabled).withContext('match is exact, not case-folded').toBeTrue();
    typeInto(text, 'e2e_a');
    expect(yes().disabled).withContext('enabled on exact match').toBeFalse();
    yes().click();
    settle();
    expect(pipeSvc.deleteCatalog).toHaveBeenCalledTimes(1);
    expect(pipeSvc.deleteCatalog.calls.mostRecent().args).toEqual(['e2e_a', 'cascade', 'e2e_a']);
    expect(mutatingCalls()).withContext('no client-side fan-out').toBe(0);
  }));

  it('deleteCatalog() in cascade mode with a non-matching confirm does not call the server', fa(() => {
    settle();
    const cat = component.catalogs.find((c: any) => c.name === 'e2e_a');
    component.deleteTarget = 'e2e_a';
    component.deleteMode = 'cascade';
    component.confirmText = 'nope';
    component.deleteCatalog(cat);
    settle();
    expect(pipeSvc.deleteCatalog).not.toHaveBeenCalled();
    expect(mutatingCalls()).toBe(0);
  }));

  // ── Acceptance 5 ────────────────────────────────────────────────────────

  it('a 207 from delete renders each failed item with its error and reloads the list', fa(() => {
    pipeSvc.deleteCatalog.and.returnValue(of({
      detached: ['p_a'], failed: [{ name: 'ghost_tap', error: 'capability denied for ghost' }]
    }));
    settle();
    const loadsBefore = tapSvc.getTaps.calls.count();
    const confirm = openDelete('e2e_a');
    if (!confirm) return;
    (confirm.querySelector('.del-yes') as HTMLButtonElement).click();
    settle();
    const text = el.textContent || '';
    expect(text).toContain('ghost_tap');
    expect(text).toContain('capability denied for ghost');
    expect(tapSvc.getTaps.calls.count()).toBeGreaterThan(loadsBefore);
  }));

  it('a 207 from rename renders each failed item with its error and reloads the list', fa(() => {
    pipeSvc.renameCatalog.and.returnValue(of({
      renamed: ['t_a'], failed: [{ name: 'ghost_pipe', error: 'no longer in catalog' }], affectedKeys: []
    }));
    settle();
    const loadsBefore = tapSvc.getTaps.calls.count();
    const input = openRename('e2e_a');
    if (!input) return;
    submitRename(input, 'e2e_c');
    const text = el.textContent || '';
    expect(text).toContain('ghost_pipe');
    expect(text).toContain('no longer in catalog');
    expect(tapSvc.getTaps.calls.count()).toBeGreaterThan(loadsBefore);
  }));

  // ── Acceptance 6 ────────────────────────────────────────────────────────

  it('rename with affectedKeys shows a persistent, dismissable affected-keys banner', fa(() => {
    pipeSvc.renameCatalog.and.returnValue(of({ renamed: ['t_a', 'p_a'], failed: [], affectedKeys: ['ops-key', 'bi-key'] }));
    settle();
    const input = openRename('e2e_a');
    if (!input) return;
    submitRename(input, 'e2e_c');
    let banner = el.querySelector('.affected-keys-banner');
    expect(banner).withContext('.affected-keys-banner').not.toBeNull();
    const text = banner?.textContent || '';
    expect(text).toContain("API keys scoped to 'e2e_a' no longer match");
    expect(text).toContain('ops-key');
    expect(text).toContain('bi-key');
    tick(10000);
    fixture.detectChanges();
    expect(el.querySelector('.affected-keys-banner')).withContext('persists past the move-error timeout').not.toBeNull();
    const close = el.querySelector('.affected-keys-banner button') as HTMLButtonElement | null;
    expect(close).withContext('dismiss button').not.toBeNull();
    close?.click();
    settle();
    expect(el.querySelector('.affected-keys-banner')).withContext('dismissed').toBeNull();
  }));

  it('rename with empty affectedKeys shows no affected-keys banner', fa(() => {
    settle();
    const input = openRename('e2e_a');
    if (!input) return;
    submitRename(input, 'e2e_c');
    expect(pipeSvc.renameCatalog).toHaveBeenCalledTimes(1);
    expect(el.querySelector('.affected-keys-banner')).toBeNull();
  }));

  // ── 404: catalog gone on the server (no fallback) ───────────────────────

  function writeCalls(): number {
    return tapSvc.deleteTap.calls.count() + tapSvc.createOrUpdateTap.calls.count() +
      pipeSvc.deletePipeline.calls.count() + pipeSvc.createPipeline.calls.count();
  }

  const NOT_FOUND = 'Catalog not found. It may have been renamed or deleted elsewhere; the list has been refreshed.';

  it('cascade delete 404 shows the not-found message and performs NO writes', fa(() => {
    pipeSvc.deleteCatalog.and.returnValue(httpError(404, { error: "Catalog 'e2e_a' not found" }));
    settle();
    const loadsBefore = tapSvc.getTaps.calls.count();
    const cat = component.catalogs.find((c: any) => c.name === 'e2e_a');
    component.deleteTarget = 'e2e_a';
    component.deleteMode = 'cascade';
    component.confirmText = 'e2e_a';
    component.deleteCatalog(cat);
    settle();
    expect(pipeSvc.deleteCatalog).toHaveBeenCalledTimes(1);
    expect(writeCalls()).withContext('no deleteTap/deletePipeline/tap or pipeline updates').toBe(0);
    expect(pipeSvc.renameCatalog).not.toHaveBeenCalled();
    expect(tapSvc.getTaps.calls.count()).withContext('list reloaded after 404').toBeGreaterThan(loadsBefore);
    expect(el.querySelector('.move-error-banner')?.textContent || '').toContain(NOT_FOUND);
  }));

  it('"Keep items" 404 shows the not-found message, never deletes members and performs NO writes', fa(() => {
    pipeSvc.deleteCatalog.and.returnValue(httpError(404, { error: "Catalog 'e2e_a' not found" }));
    settle();
    const loadsBefore = tapSvc.getTaps.calls.count();
    const cat = component.catalogs.find((c: any) => c.name === 'e2e_a');
    component.deleteTarget = 'e2e_a';
    component.deleteMode = 'detach';
    component.confirmText = '';
    component.deleteCatalog(cat);
    settle();
    expect(pipeSvc.deleteCatalog).toHaveBeenCalledTimes(1);
    expect(tapSvc.deleteTap.calls.allArgs().map((a: any[]) => a[0])).not.toContain('t_a');
    expect(pipeSvc.deletePipeline).not.toHaveBeenCalled();
    expect(writeCalls()).withContext('no writes at all').toBe(0);
    expect(tapSvc.getTaps.calls.count()).withContext('list reloaded after 404').toBeGreaterThan(loadsBefore);
    expect(el.querySelector('.move-error-banner')?.textContent || '').toContain(NOT_FOUND);
  }));

  it('rename 404 shows the not-found message and performs NO writes', fa(() => {
    pipeSvc.renameCatalog.and.returnValue(httpError(404, { error: "Catalog 'e2e_a' not found" }));
    settle();
    const loadsBefore = tapSvc.getTaps.calls.count();
    const input = openRename('e2e_a');
    if (!input) return;
    submitRename(input, 'e2e_c');
    expect(pipeSvc.renameCatalog).toHaveBeenCalledTimes(1);
    expect(writeCalls()).withContext('no per-item moves').toBe(0);
    expect(pipeSvc.deleteCatalog).not.toHaveBeenCalled();
    expect(tapSvc.getTaps.calls.count()).withContext('list reloaded after 404').toBeGreaterThan(loadsBefore);
    expect(el.querySelector('.move-error-banner')?.textContent || '').toContain(NOT_FOUND);
    expect(el.querySelector('input.rename-catalog-input')).withContext('editor closed on 404').toBeNull();
  }));

  // ── Case-only clash with a legacy mixed-case catalog ────────────────────

  it('rename into a name differing from an existing catalog only by case is refused client-side', fa(() => {
    tapsData.push({ name: '__catalog__DatrisFund', catalog: 'DatrisFund' });
    settle();
    const input = openRename('e2e_a');
    if (!input) return;
    submitRename(input, 'datrisfund');
    expect(pipeSvc.renameCatalog).not.toHaveBeenCalled();
    expect(el.querySelector('.move-error-banner')?.textContent || '')
      .toContain("'DatrisFund' already exists with different capitalisation. Catalog names are case-sensitive, so this would create a second catalog.");
    const still = el.querySelector('input.rename-catalog-input') as HTMLInputElement | null;
    expect(still).withContext('editor stays open').not.toBeNull();
  }));

  it('a mixed-case catalog can be renamed to its own different-case form', fa(() => {
    tapsData.push({ name: '__catalog__DatrisFund', catalog: 'DatrisFund' });
    settle();
    const input = openRename('DatrisFund');
    if (!input) return;
    submitRename(input, 'datrisfund');
    expect(pipeSvc.renameCatalog).toHaveBeenCalledTimes(1);
    expect(pipeSvc.renameCatalog.calls.mostRecent().args.slice(0, 2)).toEqual(['DatrisFund', 'datrisfund']);
  }));

  it('after a 404 the reloaded list drops the stale card', fa(() => {
    pipeSvc.deleteCatalog.and.callFake(() => {
      // Another client renamed e2e_a to e2e_moved before this delete.
      tapsData = [
        { name: '__catalog__e2e_moved', catalog: 'e2e_moved' },
        { name: 't_a', catalog: 'e2e_moved' }, { name: 't_u' }
      ];
      pipelinesData = [{ name: 'p_a', catalog: 'e2e_moved' }, { name: 'p_u' }];
      return httpError(404, { error: "Catalog 'e2e_a' not found" });
    });
    settle();
    const confirm = openDelete('e2e_a');
    if (!confirm) return;
    (confirm.querySelector('.del-yes') as HTMLButtonElement).click();
    settle();
    const names = cards().map(c => (c.querySelector('.catalog-name')?.textContent || '').trim());
    expect(names).not.toContain('e2e_a');
    expect(names).toContain('e2e_moved');
    expect(writeCalls()).toBe(0);
  }));

  // ── Stale error banner ──────────────────────────────────────────────────

  it('a successful rename clears an earlier refusal banner', fa(() => {
    tapsData.push({ name: '__catalog__DatrisFund', catalog: 'DatrisFund' });
    settle();
    const input = openRename('e2e_a');
    if (!input) return;
    submitRename(input, 'datrisfund');
    expect(el.querySelector('.move-error-banner')).withContext('refusal shown').not.toBeNull();
    const again = el.querySelector('input.rename-catalog-input') as HTMLInputElement;
    submitRename(again, 'e2e_c');
    expect(pipeSvc.renameCatalog).toHaveBeenCalledTimes(1);
    expect(el.querySelector('.move-error-banner')).withContext('cleared by the successful rename').toBeNull();
  }));

  it('a successful catalog delete clears an earlier error banner', fa(() => {
    settle();
    component.moveError = 'earlier failure';
    const cat = component.catalogs.find((c: any) => c.name === 'e2e_a');
    component.deleteTarget = 'e2e_a';
    component.deleteMode = 'detach';
    component.deleteCatalog(cat);
    settle();
    expect(el.querySelector('.move-error-banner')).toBeNull();
  }));

  it('opening the rename editor or the delete dialog clears the error banner but not the affected-keys banner', fa(() => {
    pipeSvc.renameCatalog.and.returnValue(of({ renamed: ['t_a'], failed: [], affectedKeys: ['ops-key'] }));
    settle();
    const input = openRename('e2e_a');
    if (!input) return;
    // The stubbed list does not change, so the card is still e2e_a after reload.
    submitRename(input, 'e2e_c');
    expect(el.querySelector('.affected-keys-banner')).not.toBeNull();
    component.moveError = 'earlier failure';
    settle();
    expect(el.querySelector('.move-error-banner')).not.toBeNull();
    openRename('e2e_a');
    expect(el.querySelector('.move-error-banner')).withContext('cleared by opening rename').toBeNull();
    (card('e2e_a').querySelector('.rename-cancel') as HTMLButtonElement).click();
    settle();
    component.moveError = 'earlier failure';
    settle();
    openDelete('e2e_a');
    expect(el.querySelector('.move-error-banner')).withContext('cleared by opening delete').toBeNull();
    expect(el.querySelector('.affected-keys-banner')).withContext('affected-keys banner persists').not.toBeNull();
  }));

  it('Create Catalog keeps case (sanitizeCatalogName)', fa(() => {
    settle();
    component.newCatalogName = '  Sales Q3! ';
    component.createCatalog();
    settle();
    const sent = tapSvc.createOrUpdateTap.calls.mostRecent().args[0];
    expect(sent.name).toBe('__catalog__Sales_Q3');
    expect(sent.catalog).toBe('Sales_Q3');
  }));
  // ── Create guard: case-only twin (shared findCaseTwin helper) ───────────

  it('Create Catalog refuses a name differing from an existing catalog only by case', fa(() => {
    tapsData.push({ name: '__catalog__DatrisFund', catalog: 'DatrisFund' });
    settle();
    component.showCreateModal = true;
    component.newCatalogName = 'datrisfund';
    component.createCatalog();
    settle();
    expect(tapSvc.createOrUpdateTap).not.toHaveBeenCalled();
    expect(el.querySelector('.move-error-banner')?.textContent || '')
      .toContain("'DatrisFund' already exists with different capitalisation. Catalog names are case-sensitive, so this would create a second catalog.");
    expect(component.showCreateModal).withContext('create row stays open').toBeTrue();
    expect(component.newCatalogName).withContext('typed value kept').toBe('datrisfund');
  }));

  it('Create Catalog with an exact existing name creates nothing and closes quietly', fa(() => {
    settle();
    component.showCreateModal = true;
    component.newCatalogName = 'e2e_a';
    component.createCatalog();
    settle();
    expect(tapSvc.createOrUpdateTap).not.toHaveBeenCalled();
    expect(component.showCreateModal).toBeFalse();
    expect(el.querySelector('.move-error-banner')).toBeNull();
  }));

  it('Create Catalog ignores Uncataloged when checking for a case twin', fa(() => {
    settle();
    component.newCatalogName = 'uncataloged';
    component.createCatalog();
    settle();
    expect(tapSvc.createOrUpdateTap).toHaveBeenCalledTimes(1);
    expect(tapSvc.createOrUpdateTap.calls.mostRecent().args[0].catalog).toBe('uncataloged');
  }));

  it('rename guard is unchanged after moving to the shared helper', fa(() => {
    tapsData.push({ name: '__catalog__DatrisFund', catalog: 'DatrisFund' });
    settle();
    const cat = component.catalogs.find((c: any) => c.name === 'e2e_a');
    component.renameTarget = 'e2e_a';
    component.renameValue = 'DATRISFUND';
    component.commitRename(cat);
    settle();
    expect(pipeSvc.renameCatalog).not.toHaveBeenCalled();
    expect(component.moveError).toContain("'DatrisFund' already exists with different capitalisation.");
    // Own different-case form is still allowed.
    const own = component.catalogs.find((c: any) => c.name === 'DatrisFund');
    component.renameTarget = 'DatrisFund';
    component.renameValue = 'DATRISFUND';
    component.commitRename(own);
    expect(pipeSvc.renameCatalog).toHaveBeenCalledTimes(1);
    expect(pipeSvc.renameCatalog.calls.mostRecent().args.slice(0, 2)).toEqual(['DatrisFund', 'DATRISFUND']);
  }));
});
