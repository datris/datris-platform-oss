/**
 * Story: Catalog backup and restore: CLI commands, Catalog-tab controls, docs
 * (plans/stories/catalog-backup-restore-ui-cli.md), Acceptance bullet 5:
 * "the restored card appears with its taps marked disabled".
 *
 * The embedded taps table (inside a Catalog-tab card) shows a `.disabled-badge`
 * ("Disabled") on every tap row whose `enabled` is false, and none on rows
 * that are enabled or carry no `enabled` field. The badge sits in the
 * Schedule cell in place of the cron (the name cell's ellipsis clipped it
 * there), and never in the name cell.
 *
 * For users with write access the badge is a menu button
 * (role=button, aria-haspopup=menu) opening `.enable-menu` with
 * `.enable-tap-item` ("Enable tap": getTap -> createOrUpdateTap enabled=true
 * -> reload) and, for a scheduled tap without a passing test,
 * `.test-enable-item` ("Test, then enable": runTap(name, 'test') -> on
 * success the same save). Errors (e.g. the 409 test-before-cron refusal)
 * show in `.enable-menu-error` and the tap stays disabled. Escape and an
 * outside click close the menu. Read-only users get a plain badge.
 *
 * Story: Tap dependencies (plans/stories/tap-dependency-order.md), Acceptance
 * bullet 7 (second describe block below). Contract pinned here (names the
 * story left open are chosen here; keep them or update this spec):
 *  - Embedded, the component calls `PipelineService.enableOrder(embedCatalog)`
 *    once per load. Body: `{catalog, levels: [{level, taps: [{name, blockedBy,
 *    hasPassingTest, lastRealRunLanded, ready, ...}]}], cycles, warnings}`;
 *    `level` is shown as given (1 = leaf producers).
 *  - A disabled row shows `.dep-hint` in the Schedule cell, under the badge:
 *    `needs: x, y` when blockedBy is non-empty, else `Level N`.
 *  - In `.enable-menu`, a blocked tap shows `.dep-note`
 *    "Reads tables produced by x, y. Run them first."
 *  - `.run-now-item` ("Run now") shows only when the tap has a passing test
 *    (enable-order `hasPassingTest` when loaded for the tap, else
 *    lastTestRunStatus === 'success'; see the third describe block) and a
 *    targetPipeline; click ->
 *    `TapService.runTap(name, 'run')` -> `.run-now-result` with status and
 *    recordCount (and persistedReason when present) -> `loadTaps()`.
 */
import { ComponentFixture, TestBed, fakeAsync, tick, flush, discardPeriodicTasks } from '@angular/core/testing';
import { CUSTOM_ELEMENTS_SCHEMA, NO_ERRORS_SCHEMA } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { provideRouter } from '@angular/router';
import { of, throwError, Subject } from 'rxjs';
import { HttpErrorResponse } from '@angular/common/http';

import { TapsComponent } from './taps.component';
import { TapService } from '../tap.service';
import { PipelineService } from '../pipeline.service';
import { AuthService } from '../auth.service';
import { CodeRepoService } from '../configuration/code-repo/code-repo.service';

describe('TapsComponent — disabled badge in the embedded table', () => {
  let fixture: ComponentFixture<TapsComponent>;
  let el: HTMLElement;
  let writer: boolean;
  let tapSvc: any;

  beforeEach(async () => {
    writer = false;
    tapSvc = {
      getTaps: jasmine.createSpy('getTaps').and.callFake(() => of([
        { name: '__catalog__grp', catalog: 'grp', enabled: false },
        { name: 'tap_off', catalog: 'grp', enabled: false },
        { name: 'tap_off_cron', catalog: 'grp', enabled: false, cronExpression: '0 0 6 * * ?' },
        { name: 'tap_off_tested', catalog: 'grp', enabled: false, cronExpression: '0 0 8 * * ?', lastTestRunStatus: 'success' },
        { name: 'tap_on', catalog: 'grp', enabled: true, cronExpression: '0 0 7 * * ?' },
        { name: 'tap_unset', catalog: 'grp' }
      ])),
      getPipelines: () => of([]),
      getTap: jasmine.createSpy('getTap').and.callFake((name: string) => of({
        name, catalog: 'grp', enabled: false, description: 'stored', targetPipeline: 'p1',
        cronExpression: name === 'tap_off' ? null : '0 0 6 * * ?', script: 'def fetch(): ...'
      })),
      createOrUpdateTap: jasmine.createSpy('createOrUpdateTap').and.returnValue(of({})),
      runTap: jasmine.createSpy('runTap').and.returnValue(of({ status: 'success', error: null, recordCount: 3 }))
    };
    await TestBed.configureTestingModule({
      declarations: [TapsComponent],
      imports: [FormsModule],
      schemas: [CUSTOM_ELEMENTS_SCHEMA, NO_ERRORS_SCHEMA],
      providers: [
        provideRouter([]),
        { provide: TapService, useValue: tapSvc },
        { provide: PipelineService, useValue: { getPipelines: () => of([]) } },
        { provide: AuthService, useValue: { canWrite: () => writer } },
        { provide: CodeRepoService, useValue: { get: () => of({ enabled: false }) } }
      ]
    }).compileComponents();
    fixture = TestBed.createComponent(TapsComponent);
    fixture.componentInstance.embedCatalog = 'grp';
    el = fixture.nativeElement as HTMLElement;
  });

  function row(name: string): HTMLElement {
    const r = (Array.from(el.querySelectorAll('tr')) as HTMLElement[])
      .find(x => (x.querySelector('.embed-name-link')?.textContent || '').trim() === name);
    expect(r).withContext('row for ' + name).toBeDefined();
    return r!;
  }

  it('marks only taps with enabled === false as Disabled', fakeAsync(() => {
    fixture.detectChanges();
    tick();
    fixture.detectChanges();
    const off = row('tap_off').querySelector('.disabled-badge');
    expect(off).withContext('badge on a disabled tap').not.toBeNull();
    expect((off?.textContent || '').trim()).toBe('Disabled');
    expect(row('tap_on').querySelector('.disabled-badge')).withContext('no badge on an enabled tap').toBeNull();
    expect(row('tap_unset').querySelector('.disabled-badge')).withContext('no badge without an enabled field').toBeNull();
    expect(el.querySelectorAll('.disabled-badge').length).withContext('placeholder is not listed').toBe(3);
  }));

  function cellUnder(r: HTMLElement, heading: string): HTMLElement {
    const heads = Array.from(el.querySelectorAll('thead th')).map(th => (th.textContent || '').trim().toLowerCase());
    const i = heads.indexOf(heading.toLowerCase());
    expect(i).withContext('column ' + heading).toBeGreaterThanOrEqual(0);
    return r.querySelectorAll('td')[i] as HTMLElement;
  }

  it('shows the badge in the Schedule cell in place of the cron, not in the name cell', fakeAsync(() => {
    fixture.detectChanges();
    tick();
    fixture.detectChanges();
    for (const name of ['tap_off', 'tap_off_cron']) {
      const r = row(name);
      expect(cellUnder(r, 'Name').querySelector('.disabled-badge')).withContext(name + ': not in the name cell').toBeNull();
      const sched = cellUnder(r, 'Schedule');
      expect(sched.querySelector('span.storage-badge.disabled-badge')).withContext(name + ': badge in Schedule').not.toBeNull();
      expect(sched.querySelector('.embed-cron')).withContext(name + ': cron replaced by the badge').toBeNull();
    }
    expect(cellUnder(row('tap_off_cron'), 'Schedule').querySelector('.disabled-badge')!.getAttribute('title') || '')
      .toContain('0 0 6 * * ?');
    expect((cellUnder(row('tap_on'), 'Schedule').querySelector('.embed-cron')?.textContent || '').trim()).toBe('0 0 7 * * ?');
    fixture.destroy();
    discardPeriodicTasks();
    flush();
    fixture.destroy();
    discardPeriodicTasks();
    flush();
  }));

  // ── Enable menu on the badge ───────────────────────────────────────────

  function settle(): void { fixture.detectChanges(); tick(); fixture.detectChanges(); }
  function done(): void { fixture.destroy(); discardPeriodicTasks(); flush(); }
  function badge(name: string): HTMLElement {
    const b = row(name).querySelector('span.storage-badge.disabled-badge') as HTMLElement | null;
    expect(b).withContext('badge for ' + name).not.toBeNull();
    return b!;
  }
  function menu(name: string): HTMLElement | null {
    return row(name).querySelector('.enable-menu') as HTMLElement | null;
  }

  it('read-only users get a plain badge with no menu', fakeAsync(() => {
    settle();
    const b = badge('tap_off');
    expect(b.getAttribute('role')).toBeNull();
    expect(b.getAttribute('aria-haspopup')).toBeNull();
    b.click();
    settle();
    expect(menu('tap_off')).withContext('no menu for read-only').toBeNull();
    done();
  }));

  it('clicking the badge opens the menu; Enter toggles it too', fakeAsync(() => {
    writer = true;
    settle();
    const b = badge('tap_off');
    expect(b.getAttribute('role')).toBe('button');
    expect(b.getAttribute('tabindex')).toBe('0');
    expect(b.getAttribute('aria-haspopup')).toBe('menu');
    expect(b.getAttribute('aria-expanded')).toBe('false');
    b.click();
    settle();
    expect(menu('tap_off')).withContext('menu after click').not.toBeNull();
    expect(b.getAttribute('aria-expanded')).toBe('true');
    expect(menu('tap_off')!.querySelector('.enable-tap-item')).not.toBeNull();
    b.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter' }));
    settle();
    expect(menu('tap_off')).withContext('Enter closes it').toBeNull();
    b.dispatchEvent(new KeyboardEvent('keydown', { key: ' ' }));
    settle();
    expect(menu('tap_off')).withContext('Space opens it').not.toBeNull();
    done();
  }));

  it('only one menu is open at a time', fakeAsync(() => {
    writer = true;
    settle();
    badge('tap_off').click();
    settle();
    badge('tap_off_cron').click();
    settle();
    expect(menu('tap_off')).toBeNull();
    expect(menu('tap_off_cron')).not.toBeNull();
    expect(el.querySelectorAll('.enable-menu').length).toBe(1);
    done();
  }));

  it('Enable fetches the full tap, saves enabled=true and reloads', fakeAsync(() => {
    writer = true;
    settle();
    badge('tap_off').click();
    settle();
    const loads = tapSvc.getTaps.calls.count();
    (menu('tap_off')!.querySelector('.enable-tap-item') as HTMLElement).click();
    settle();
    expect(tapSvc.getTap).toHaveBeenCalledWith('tap_off');
    expect(tapSvc.createOrUpdateTap).toHaveBeenCalledTimes(1);
    const saved = tapSvc.createOrUpdateTap.calls.mostRecent().args[0];
    expect(saved.enabled).toBe(true);
    expect(saved.description).withContext('stored fields kept').toBe('stored');
    expect(saved.targetPipeline).toBe('p1');
    expect('script' in saved).withContext('script text not sent').toBeFalse();
    expect(tapSvc.getTap.calls.first().invocationOrder).toBeLessThan(tapSvc.createOrUpdateTap.calls.first().invocationOrder);
    expect(tapSvc.getTaps.calls.count()).withContext('reloaded').toBeGreaterThan(loads);
    expect(menu('tap_off')).withContext('closed on success').toBeNull();
    done();
  }));

  it('a 409 shows the server message, keeps the menu and the badge', fakeAsync(() => {
    writer = true;
    const msg = "Tap 'tap_off_cron' cannot be scheduled: its script has never passed a test run";
    tapSvc.createOrUpdateTap.and.returnValue(throwError(() => new HttpErrorResponse({ status: 409, error: { error: msg } })));
    settle();
    badge('tap_off_cron').click();
    settle();
    (menu('tap_off_cron')!.querySelector('.enable-tap-item') as HTMLElement).click();
    settle();
    const m = menu('tap_off_cron');
    expect(m).withContext('menu stays open on error').not.toBeNull();
    expect(m!.querySelector('.enable-menu-error')?.textContent || '').toContain(msg);
    expect(badge('tap_off_cron')).toBeTruthy();
    done();
  }));

  it('Test, then enable shows only for a scheduled tap without a passing test', fakeAsync(() => {
    writer = true;
    settle();
    for (const [name, expected] of [['tap_off', false], ['tap_off_cron', true], ['tap_off_tested', false]] as [string, boolean][]) {
      badge(name).click();
      settle();
      const m = menu(name)!;
      expect(!!m.querySelector('.test-enable-item')).withContext(name + ' test-then-enable').toBe(expected);
      expect((m.textContent || '').includes('Scheduled taps need a passing test before they can be enabled.'))
        .withContext(name + ' note').toBe(name !== 'tap_off');
    }
    done();
  }));

  it('Test, then enable runs a test and on success saves enabled=true', fakeAsync(() => {
    writer = true;
    settle();
    badge('tap_off_cron').click();
    settle();
    (menu('tap_off_cron')!.querySelector('.test-enable-item') as HTMLElement).click();
    settle();
    expect(tapSvc.runTap).toHaveBeenCalledWith('tap_off_cron', 'test');
    expect(tapSvc.createOrUpdateTap).toHaveBeenCalledTimes(1);
    expect(tapSvc.createOrUpdateTap.calls.mostRecent().args[0].enabled).toBe(true);
    expect(tapSvc.runTap.calls.first().invocationOrder).toBeLessThan(tapSvc.createOrUpdateTap.calls.first().invocationOrder);
    expect(menu('tap_off_cron')).toBeNull();
    done();
  }));

  it('a failed test shows the error and does not enable', fakeAsync(() => {
    writer = true;
    tapSvc.runTap.and.returnValue(of({ status: 'failure', error: 'HTTP 401 from source', recordCount: 0 }));
    settle();
    badge('tap_off_cron').click();
    settle();
    (menu('tap_off_cron')!.querySelector('.test-enable-item') as HTMLElement).click();
    settle();
    expect(tapSvc.createOrUpdateTap).not.toHaveBeenCalled();
    expect(menu('tap_off_cron')!.querySelector('.enable-menu-error')?.textContent || '').toContain('HTTP 401 from source');
    done();
  }));

  it('keeps the menu open with items disabled while an action runs', fakeAsync(() => {
    writer = true;
    const pending = new Subject<any>();
    tapSvc.runTap.and.returnValue(pending);
    settle();
    badge('tap_off_cron').click();
    settle();
    (menu('tap_off_cron')!.querySelector('.test-enable-item') as HTMLElement).click();
    settle();
    const m = menu('tap_off_cron')!;
    expect((m.querySelector('.enable-tap-item') as HTMLButtonElement).disabled).toBeTrue();
    expect((m.querySelector('.test-enable-item') as HTMLButtonElement).disabled).toBeTrue();
    document.body.click();
    settle();
    expect(menu('tap_off_cron')).withContext('outside click ignored while busy').not.toBeNull();
    pending.next({ status: 'success', error: null });
    pending.complete();
    settle();
    expect(menu('tap_off_cron')).toBeNull();
    done();
  }));

  it('Escape and an outside click close the menu', fakeAsync(() => {
    writer = true;
    settle();
    badge('tap_off').click();
    settle();
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
    settle();
    expect(menu('tap_off')).withContext('Escape closes').toBeNull();
    badge('tap_off').click();
    settle();
    expect(menu('tap_off')).not.toBeNull();
    document.body.click();
    settle();
    expect(menu('tap_off')).withContext('outside click closes').toBeNull();
    done();
  }));
});

describe('TapsComponent — dependency hints and Run now in the embedded table', () => {
  let fixture: ComponentFixture<TapsComponent>;
  let el: HTMLElement;
  let tapSvc: any;
  let pipeSvc: any;

  const order = {
    catalog: 'grp',
    levels: [
      { level: 1, taps: [
        { name: 'prod', blockedBy: [], hasPassingTest: true, lastRealRunLanded: false, ready: true },
        { name: 'prod2', blockedBy: [], hasPassingTest: false, lastRealRunLanded: false, ready: false }
      ] },
      { level: 2, taps: [
        { name: 'cons_blocked', blockedBy: ['prod', 'prod2'], hasPassingTest: false, lastRealRunLanded: false, ready: false },
        { name: 'cons_ready', blockedBy: [], hasPassingTest: false, lastRealRunLanded: false, ready: false }
      ] }
    ],
    cycles: [],
    warnings: []
  };

  beforeEach(async () => {
    tapSvc = {
      getTaps: jasmine.createSpy('getTaps').and.callFake(() => of([
        { name: '__catalog__grp', catalog: 'grp', enabled: false },
        { name: 'prod', catalog: 'grp', enabled: false, targetPipeline: 'p_prod', lastTestRunStatus: 'success' },
        { name: 'prod2', catalog: 'grp', enabled: false, targetPipeline: 'p_prod2' },
        { name: 'cons_blocked', catalog: 'grp', enabled: false, targetPipeline: 'p_cons', lastTestRunStatus: 'error' },
        { name: 'cons_ready', catalog: 'grp', enabled: false, targetPipeline: 'p_ready' }
      ])),
      getPipelines: () => of([]),
      getTap: jasmine.createSpy('getTap').and.callFake((name: string) => of({ name, catalog: 'grp', enabled: false })),
      createOrUpdateTap: jasmine.createSpy('createOrUpdateTap').and.returnValue(of({})),
      runTap: jasmine.createSpy('runTap').and.returnValue(of({ status: 'success', error: null, recordCount: 4242 }))
    };
    pipeSvc = {
      getPipelines: () => of([]),
      enableOrder: jasmine.createSpy('enableOrder').and.callFake(() => of(order))
    };
    await TestBed.configureTestingModule({
      declarations: [TapsComponent],
      imports: [FormsModule],
      schemas: [CUSTOM_ELEMENTS_SCHEMA, NO_ERRORS_SCHEMA],
      providers: [
        provideRouter([]),
        { provide: TapService, useValue: tapSvc },
        { provide: PipelineService, useValue: pipeSvc },
        { provide: AuthService, useValue: { canWrite: () => true } },
        { provide: CodeRepoService, useValue: { get: () => of({ enabled: false }) } }
      ]
    }).compileComponents();
    fixture = TestBed.createComponent(TapsComponent);
    fixture.componentInstance.embedCatalog = 'grp';
    el = fixture.nativeElement as HTMLElement;
  });

  function settle(): void { fixture.detectChanges(); tick(); fixture.detectChanges(); }
  function done(): void { fixture.destroy(); discardPeriodicTasks(); flush(); }
  function norm(s: string | null | undefined): string { return (s || '').replace(/\s+/g, ' ').trim(); }
  function row(name: string): HTMLElement {
    const r = (Array.from(el.querySelectorAll('tr')) as HTMLElement[])
      .find(x => (x.querySelector('.embed-name-link')?.textContent || '').trim() === name);
    expect(r).withContext('row for ' + name).toBeDefined();
    return r!;
  }
  function hint(name: string): string {
    const h = row(name).querySelector('.dep-hint');
    expect(h).withContext('.dep-hint on ' + name).not.toBeNull();
    return norm(h?.textContent);
  }
  function openMenu(name: string): HTMLElement {
    (row(name).querySelector('span.storage-badge.disabled-badge') as HTMLElement).click();
    settle();
    const m = row(name).querySelector('.enable-menu') as HTMLElement | null;
    expect(m).withContext('menu for ' + name).not.toBeNull();
    return m!;
  }

  it('loads the enable order for the embedded catalog', fakeAsync(() => {
    settle();
    expect(pipeSvc.enableOrder).toHaveBeenCalledWith('grp');
    done();
  }));

  it('a disabled tap blocked by x shows "needs: x"', fakeAsync(() => {
    settle();
    expect(hint('cons_blocked')).toBe('needs: prod, prod2');
    done();
  }));

  it('a disabled tap at level 2 with nothing blocking shows "Level 2"; a producer shows "Level 1"', fakeAsync(() => {
    settle();
    expect(hint('cons_ready')).toBe('Level 2');
    expect(hint('prod')).toBe('Level 1');
    done();
  }));

  it('the needs hint carries the full source list in its title', fakeAsync(() => {
    settle();
    const h = row('cons_blocked').querySelector('.dep-hint') as HTMLElement;
    expect(h.getAttribute('title')).toBe('needs: prod, prod2');
    expect(getComputedStyle(h).textOverflow).not.toBe('ellipsis');
    done();
  }));

  it('a burst of reloads while the enable order is loading makes one follow-up request', fakeAsync(() => {
    const pending = new Subject<any>();
    pipeSvc.enableOrder.and.callFake(() => pending.asObservable());
    settle();
    const comp = fixture.componentInstance;
    comp.loadTaps(); comp.loadTaps(); comp.loadTaps();
    expect(pipeSvc.enableOrder).toHaveBeenCalledTimes(1);
    pipeSvc.enableOrder.and.callFake(() => of(order));
    pending.next(order); pending.complete();
    settle();
    expect(pipeSvc.enableOrder).toHaveBeenCalledTimes(2);
    done();
  }));

  it('the menu of a blocked tap says which sources to run first', fakeAsync(() => {
    settle();
    const note = openMenu('cons_blocked').querySelector('.dep-note');
    expect(note).withContext('.dep-note').not.toBeNull();
    expect(norm(note?.textContent)).toContain('Reads tables produced by prod, prod2. Run them first.');
    done();
  }));

  it('Run now appears only when the last test passed', fakeAsync(() => {
    settle();
    for (const [name, expected] of [['prod', true], ['prod2', false], ['cons_blocked', false], ['cons_ready', false]] as [string, boolean][]) {
      const m = openMenu(name);
      expect(!!m.querySelector('.run-now-item')).withContext(name + ' run-now').toBe(expected);
    }
    done();
  }));

  it('Run now calls runTap(name, "run"), shows the record count and reloads', fakeAsync(() => {
    settle();
    const loads = tapSvc.getTaps.calls.count();
    const item = openMenu('prod').querySelector('.run-now-item') as HTMLElement | null;
    expect(item).withContext('.run-now-item').not.toBeNull();
    if (!item) { done(); return; }
    item.click();
    settle();
    expect(tapSvc.runTap).toHaveBeenCalledOnceWith('prod', 'run');
    expect(tapSvc.createOrUpdateTap).withContext('Run now does not enable').not.toHaveBeenCalled();
    const out = el.querySelector('.run-now-result');
    expect(out).withContext('.run-now-result').not.toBeNull();
    expect(norm(out?.textContent)).toContain('4242');
    expect(norm(out?.textContent)).toContain('success');
    expect(tapSvc.getTaps.calls.count()).withContext('reloaded').toBeGreaterThan(loads);
    done();
  }));
});

/**
 * Follow-up to Story 1 (plans/stories/tap-dependency-order.md): Run now and
 * "Test, then enable" follow the server's enable-order readiness
 * `hasPassingTest` (green last test stamped for the tap's CURRENT script)
 * when it is loaded for the tap. A green `lastTestRunStatus` on a script
 * edited since (e.g. after an overwrite restore) has `hasPassingTest=false`:
 * no Run now, and "Test, then enable" is offered. With no readiness loaded
 * for a tap (Uncataloged, enable-order failed or still in flight), the old
 * `lastTestRunStatus === 'success'` rule applies.
 */
describe('TapsComponent — Run now follows enable-order hasPassingTest', () => {
  let fixture: ComponentFixture<TapsComponent>;
  let el: HTMLElement;
  let tapSvc: any;
  let pipeSvc: any;

  const order = {
    catalog: 'grp',
    levels: [
      { level: 1, taps: [
        { name: 'fresh', blockedBy: [], hasPassingTest: true, lastRealRunLanded: false, ready: true },
        { name: 'stale', blockedBy: [], hasPassingTest: false, lastRealRunLanded: false, ready: false },
        { name: 'stale_on', enabled: true, blockedBy: [], hasPassingTest: false, lastRealRunLanded: false, ready: false }
      ] }
    ],
    cycles: [],
    warnings: []
  };

  const taps = [
    { name: '__catalog__grp', catalog: 'grp', enabled: false },
    { name: 'fresh', catalog: 'grp', enabled: false, cronExpression: '0 0 6 * * ?', targetPipeline: 'p_fresh', lastTestRunStatus: 'success' },
    { name: 'stale', catalog: 'grp', enabled: false, cronExpression: '0 0 6 * * ?', targetPipeline: 'p_stale', lastTestRunStatus: 'success' },
    { name: 'stale_on', catalog: 'grp', enabled: true, cronExpression: '0 0 6 * * ?', targetPipeline: 'p_on', lastTestRunStatus: 'success' },
    // Not in the enable order: falls back to lastTestRunStatus.
    { name: 'unlisted_green', catalog: 'grp', enabled: false, cronExpression: '0 0 6 * * ?', targetPipeline: 'p_u', lastTestRunStatus: 'success' },
    { name: 'unlisted_red', catalog: 'grp', enabled: false, cronExpression: '0 0 6 * * ?', targetPipeline: 'p_r', lastTestRunStatus: 'error' }
  ];

  beforeEach(async () => {
    tapSvc = {
      getTaps: jasmine.createSpy('getTaps').and.callFake(() => of(taps)),
      getPipelines: () => of([]),
      getTap: jasmine.createSpy('getTap').and.callFake((name: string) => of({ name, catalog: 'grp', enabled: false })),
      createOrUpdateTap: jasmine.createSpy('createOrUpdateTap').and.returnValue(of({})),
      runTap: jasmine.createSpy('runTap').and.returnValue(of({ status: 'success', error: null, recordCount: 7 }))
    };
    pipeSvc = {
      getPipelines: () => of([]),
      enableOrder: jasmine.createSpy('enableOrder').and.callFake(() => of(order))
    };
    await TestBed.configureTestingModule({
      declarations: [TapsComponent],
      imports: [FormsModule],
      schemas: [CUSTOM_ELEMENTS_SCHEMA, NO_ERRORS_SCHEMA],
      providers: [
        provideRouter([]),
        { provide: TapService, useValue: tapSvc },
        { provide: PipelineService, useValue: pipeSvc },
        { provide: AuthService, useValue: { canWrite: () => true } },
        { provide: CodeRepoService, useValue: { get: () => of({ enabled: false }) } }
      ]
    }).compileComponents();
    fixture = TestBed.createComponent(TapsComponent);
    fixture.componentInstance.embedCatalog = 'grp';
    el = fixture.nativeElement as HTMLElement;
  });

  function settle(): void { fixture.detectChanges(); tick(); fixture.detectChanges(); }
  function done(): void { fixture.destroy(); discardPeriodicTasks(); flush(); }
  function row(name: string): HTMLElement {
    const r = (Array.from(el.querySelectorAll('tr')) as HTMLElement[])
      .find(x => (x.querySelector('.embed-name-link')?.textContent || '').trim() === name);
    expect(r).withContext('row for ' + name).toBeDefined();
    return r!;
  }
  function openMenu(name: string): HTMLElement {
    (row(name).querySelector('span.storage-badge.disabled-badge') as HTMLElement).click();
    settle();
    const m = row(name).querySelector('.enable-menu') as HTMLElement | null;
    expect(m).withContext('menu for ' + name).not.toBeNull();
    return m!;
  }

  it('green lastTestRunStatus but hasPassingTest=false: no Run now, "Test, then enable" shown', fakeAsync(() => {
    settle();
    const m = openMenu('stale');
    expect(m.querySelector('.run-now-item')).withContext('no Run now on a stale test').toBeNull();
    expect(m.querySelector('.test-enable-item')).withContext('Test, then enable offered').not.toBeNull();
    expect(m.querySelector('.enable-tap-item')).withContext('Enable tap still listed').not.toBeNull();
    done();
  }));

  it('hasPassingTest=true: Run now shown, no "Test, then enable"', fakeAsync(() => {
    settle();
    const m = openMenu('fresh');
    expect(m.querySelector('.run-now-item')).withContext('Run now').not.toBeNull();
    expect(m.querySelector('.test-enable-item')).withContext('no Test, then enable').toBeNull();
    done();
  }));

  it('an enabled tap with hasPassingTest=false has no Run now button in its actions', fakeAsync(() => {
    settle();
    expect(row('stale_on').querySelector('.run-now-btn')).toBeNull();
    done();
  }));

  it('no readiness for the tap: the lastTestRunStatus rule applies', fakeAsync(() => {
    settle();
    const g = openMenu('unlisted_green');
    expect(g.querySelector('.run-now-item')).withContext('green, unlisted: Run now').not.toBeNull();
    expect(g.querySelector('.test-enable-item')).withContext('green, unlisted: no Test, then enable').toBeNull();
    const r = openMenu('unlisted_red');
    expect(r.querySelector('.run-now-item')).withContext('red, unlisted: no Run now').toBeNull();
    expect(r.querySelector('.test-enable-item')).withContext('red, unlisted: Test, then enable').not.toBeNull();
    done();
  }));

  it('enable order failed: the lastTestRunStatus rule applies', fakeAsync(() => {
    pipeSvc.enableOrder.and.callFake(() => throwError(() => new HttpErrorResponse({ status: 500 })));
    settle();
    const m = openMenu('stale');
    expect(m.querySelector('.run-now-item')).withContext('fallback: green test -> Run now').not.toBeNull();
    expect(m.querySelector('.test-enable-item')).toBeNull();
    done();
  }));

  it('enable order still loading: the lastTestRunStatus rule applies, then readiness takes over', fakeAsync(() => {
    const pending = new Subject<any>();
    pipeSvc.enableOrder.and.callFake(() => pending.asObservable());
    settle();
    expect(openMenu('stale').querySelector('.run-now-item')).withContext('before readiness').not.toBeNull();
    pending.next(order); pending.complete();
    settle();
    const m = row('stale').querySelector('.enable-menu') as HTMLElement;
    expect(m.querySelector('.run-now-item')).withContext('after readiness').toBeNull();
    expect(m.querySelector('.test-enable-item')).not.toBeNull();
    done();
  }));

  it('Run now on a ready tap runs once and never saves the tap', fakeAsync(() => {
    settle();
    (openMenu('fresh').querySelector('.run-now-item') as HTMLElement).click();
    settle();
    expect(tapSvc.runTap).toHaveBeenCalledOnceWith('fresh', 'run');
    expect(tapSvc.getTap).not.toHaveBeenCalled();
    expect(tapSvc.createOrUpdateTap).not.toHaveBeenCalled();
    done();
  }));
});
