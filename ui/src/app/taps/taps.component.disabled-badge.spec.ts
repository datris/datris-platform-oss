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
