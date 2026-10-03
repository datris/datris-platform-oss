/**
 * Search tab, Traditional mode: field-protection ciphertext badges and Reveal
 * (plans/stories/field-protection-6-search-reveal.md).
 *
 * DOM contracts pinned here:
 *   - `.cell-encrypted`        a cell holding `enc:v<n>:...` in an `encrypt` column, not yet revealed;
 *                              shows `enc:v<n>` (never the base64); `title` = server error for that value
 *                              when reveal failed for it.
 *   - `.cell-revealed`         a cell whose ciphertext was revealed; shows the plaintext.
 *   - `.reveal-btn`            "Reveal encrypted values" in the results header.
 *   - `.reveal-error`          the 403/400 message line.
 *   - `select.reveal-pipeline` pipeline picker beside Reveal (postgres/mongodb when not exactly one match).
 *   - `pre.json-results`       MongoDB JSON view (kept for nested documents only).
 *   - `.table-container`       the results grid (also used for flat MongoDB documents).
 *
 * New component members are reached through `any` so this file compiles before
 * the implementation exists: `revealed`, `revealAll()`.
 */
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { CUSTOM_ELEMENTS_SCHEMA, NO_ERRORS_SCHEMA } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { provideRouter } from '@angular/router';
import { HttpErrorResponse, provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Subject, of, throwError } from 'rxjs';

import { SearchComponent } from './search.component';
import { SearchService } from '../search.service';
import { HealthService } from '../health.service';
import { PipelineService } from '../pipeline.service';
import { AuthService } from '../auth.service';

// ---------------------------------------------------------------------------
// Fixtures
// ---------------------------------------------------------------------------

function fields(spec: Record<string, string | null>): any[] {
  return Object.keys(spec).map(name => {
    const f: any = { name, type: 'string' };
    if (spec[name]) f.protect = { method: spec[name] };
    return f;
  });
}

const weatherDbx = {
  name: 'weather-forecast',
  source: { schemaProperties: { fields: fields({ city: null, latitude: 'encrypt', longitude: 'encrypt', station: 'hmac' }) } },
  destination: { database: { useDatabricks: true, dbName: 'main', schema: 'weather', table: 'forecast' } }
};
const fpPg = {
  name: 'fp_pg',
  source: { schemaProperties: { fields: fields({ id: null, email: 'encrypt' }) } },
  destination: { database: { usePostgres: true, dbName: 'datris', schema: 'public', table: 'fp_pg' } }
};
const fpPgTwo = {
  name: 'fp_pg_two',
  source: { schemaProperties: { fields: fields({ id: null, email: 'encrypt' }) } },
  destination: { database: { usePostgres: true, dbName: 'datris', schema: 'public', table: 'fp_pg' } }
};
const otherPg = {
  name: 'other_pg',
  source: { schemaProperties: { fields: fields({ id: null, ssn: 'encrypt' }) } },
  destination: { database: { usePostgres: true, dbName: 'datris', schema: 'public', table: 'other' } }
};
const plainPg = {
  name: 'plain_pg',
  source: { schemaProperties: { fields: fields({ id: null, email: 'mask' }) } },
  destination: { database: { usePostgres: true, dbName: 'datris', schema: 'public', table: 'plain' } }
};
const fpMongo = {
  name: 'fp_mongo',
  source: { schemaProperties: { fields: fields({ id: null, name: null, email: 'encrypt' }) } },
  destination: { database: { useMongoDB: true, dbName: 'datris', table: 'fp_mongo' } }
};
const ALL = [weatherDbx, fpPg, fpPgTwo, otherPg, plainPg, fpMongo];
const ENCRYPT_PIPELINES = ['weather-forecast', 'fp_pg', 'fp_pg_two', 'other_pg', 'fp_mongo'];

const B64 = 'QUJDREVGR0hJSktMTU5PUFFSU1RVVldYWVo0NTY3ODkw';
/** A realistic-looking Datris ciphertext; `i` makes it distinct. */
function ct(i: number | string, version = 2): string {
  return 'enc:v' + version + ':' + B64 + '_' + i;
}
/** Plaintext the fake reveal endpoint returns for ct(i). */
function plain(token: string): string {
  return 'plain-' + token.split('_').pop();
}

const BAD_MESSAGE = 'Value is not a Datris ciphertext for this pipeline and field';

type Svc = {
  queryPostgres: jasmine.Spy; queryMongodb: jasmine.Spy; queryDatabricks: jasmine.Spy;
  querySnowflake: jasmine.Spy; queryObjectstore: jasmine.Spy; reveal: jasmine.Spy;
  getPipelines: jasmine.Spy; getPostgresSchemas: jasmine.Spy; getPostgresTables: jasmine.Spy;
  getMongoCollections: jasmine.Spy;
};

interface Ctx {
  fixture: ComponentFixture<SearchComponent>;
  component: SearchComponent;
  el: HTMLElement;
  svc: Svc;
  pipelineSvc: { getPipeline: jasmine.Spy; getPipelines: jasmine.Spy };
}

/** Default fake reveal: every value reveals to plain(value), except those in `bad`. */
function fakeReveal(bad: string[] = []) {
  return (pipeline: string, field: string, values: string[]) => {
    const errors: any[] = [];
    const out = values.map((v, index) => {
      if (bad.includes(v)) { errors.push({ index, message: BAD_MESSAGE }); return null; }
      return plain(v);
    });
    return of({ pipeline, field, values: out, revealed: out.length - errors.length, failed: errors.length, errors });
  };
}

async function setup(opts: { pipelines?: any[]; userAuthEnabled?: boolean; role?: 'admin' | 'editor' | 'viewer' | null } = {}): Promise<Ctx> {
  const pipelines = opts.pipelines || ALL;
  const userAuthEnabled = opts.userAuthEnabled === undefined ? true : opts.userAuthEnabled;
  const role = opts.role === undefined ? 'admin' : opts.role;

  const svc: Svc = {
    queryPostgres: jasmine.createSpy('queryPostgres'),
    queryMongodb: jasmine.createSpy('queryMongodb'),
    queryDatabricks: jasmine.createSpy('queryDatabricks'),
    querySnowflake: jasmine.createSpy('querySnowflake'),
    queryObjectstore: jasmine.createSpy('queryObjectstore'),
    reveal: jasmine.createSpy('reveal').and.callFake(fakeReveal()),
    getPipelines: jasmine.createSpy('getPipelines').and.returnValue(of(pipelines)),
    getPostgresSchemas: jasmine.createSpy('getPostgresSchemas').and.returnValue(of([])),
    getPostgresTables: jasmine.createSpy('getPostgresTables').and.returnValue(of([])),
    getMongoCollections: jasmine.createSpy('getMongoCollections').and.returnValue(of([]))
  };
  const pipelineSvc = {
    getPipeline: jasmine.createSpy('getPipeline').and.callFake((name: string) =>
      of(JSON.parse(JSON.stringify(pipelines.find(p => p.name === name) || null)))),
    getPipelines: jasmine.createSpy('getPipelines').and.returnValue(of(pipelines))
  };
  const user = role ? { username: 'u_' + role, role, mustSetPassword: false } : null;
  const auth = {
    userAuthEnabled,
    isAdmin: () => !!user && user.role === 'admin',
    isViewer: () => userAuthEnabled && !!user && user.role === 'viewer',
    canWrite: () => !userAuthEnabled || (!!user && user.role !== 'viewer'),
    current: () => user,
    user: () => of(user)
  };

  await TestBed.configureTestingModule({
    declarations: [SearchComponent],
    imports: [FormsModule],
    schemas: [CUSTOM_ELEMENTS_SCHEMA, NO_ERRORS_SCHEMA],
    providers: [
      provideHttpClient(),
      provideHttpClientTesting(),
      provideRouter([]),
      { provide: SearchService, useValue: svc },
      { provide: PipelineService, useValue: pipelineSvc },
      { provide: AuthService, useValue: auth },
      { provide: HealthService, useValue: { isAvailable: () => true, refresh: () => Promise.resolve() } }
    ]
  }).compileComponents();

  const fixture = TestBed.createComponent(SearchComponent);
  const component = fixture.componentInstance;
  fixture.detectChanges();
  TestBed.inject(HttpTestingController).match('/api/v1/version')
    .forEach(r => r.flush({ postgresDatabase: 'datris', mongodbDatabase: 'datris' }));
  component.setActiveView('traditional');
  fixture.detectChanges();
  await fixture.whenStable();
  fixture.detectChanges();
  return { fixture, component, el: fixture.nativeElement as HTMLElement, svc, pipelineSvc };
}

async function settle(fixture: ComponentFixture<SearchComponent>): Promise<void> {
  fixture.detectChanges();
  await fixture.whenStable();
  fixture.detectChanges();
  await fixture.whenStable();
  fixture.detectChanges();
}

async function runDatabricks(ctx: Ctx, rows: any[]): Promise<void> {
  ctx.component.queryType = 'databricks';
  ctx.component.onQueryTypeChange();
  ctx.component.whSelectedPipeline = 'weather-forecast';
  ctx.svc.queryDatabricks.and.returnValue(of({ results: rows, count: rows.length }));
  ctx.component.execute();
  await settle(ctx.fixture);
}

async function runPostgres(ctx: Ctx, sql: string, rows: any[]): Promise<void> {
  ctx.component.queryType = 'postgres';
  ctx.component.onQueryTypeChange();
  ctx.component.pgDatabase = 'datris';
  ctx.component.pgSql = sql;
  ctx.svc.queryPostgres.and.returnValue(of({ results: rows, count: rows.length }));
  ctx.component.execute();
  await settle(ctx.fixture);
}

async function runMongo(ctx: Ctx, collection: string, docs: any[]): Promise<void> {
  ctx.component.queryType = 'mongodb';
  ctx.component.onQueryTypeChange();
  ctx.component.mongoDatabase = 'datris';
  ctx.component.mongoCollection = collection;
  ctx.component.mongoFilter = '{}';
  ctx.svc.queryMongodb.and.returnValue(of({ results: docs, count: docs.length }));
  ctx.component.execute();
  await settle(ctx.fixture);
}

async function clickReveal(ctx: Ctx): Promise<void> {
  const btn = ctx.el.querySelector('.reveal-btn') as HTMLButtonElement | null;
  expect(btn).withContext('Reveal button').not.toBeNull();
  if (btn) btn.click();
  await settle(ctx.fixture);
}

function pickerOptions(el: HTMLElement): string[] {
  const sel = el.querySelector('select.reveal-pipeline');
  if (!sel) return [];
  return Array.from(sel.querySelectorAll('option')).map(o => (o as HTMLOptionElement).value).filter(v => !!v);
}

async function pick(ctx: Ctx, name: string): Promise<void> {
  const sel = ctx.el.querySelector('select.reveal-pipeline') as HTMLSelectElement;
  sel.value = Array.from(sel.options).find(o => o.value === name || o.value.endsWith(name))!.value;
  sel.dispatchEvent(new Event('change'));
  await settle(ctx.fixture);
}

function texts(el: HTMLElement, selector: string): string[] {
  return Array.from(el.querySelectorAll(selector)).map(e => (e.textContent || '').trim());
}

/** All values sent to reveal for (pipeline, field), across calls. */
function sentValues(svc: Svc, pipeline: string, field: string): string[] {
  return svc.reveal.calls.allArgs()
    .filter(a => a[0] === pipeline && a[1] === field)
    .reduce((acc: string[], a) => acc.concat(a[2]), []);
}

// ---------------------------------------------------------------------------
// Specs
// ---------------------------------------------------------------------------

describe('SearchComponent — field-protection reveal', () => {
  afterEach(() => {
    try { sessionStorage.removeItem('search.activeView'); } catch { /* ignore */ }
  });

  it('a ciphertext cell in an encrypt column renders a lock badge with the version and not the base64', async () => {
    const ctx = await setup();
    await runDatabricks(ctx, [{ city: 'Shanghai', latitude: ct(1) }]);

    const badges = ctx.el.querySelectorAll('.table-container .cell-encrypted');
    expect(badges.length).toBe(1);
    expect((badges[0].textContent || '')).toContain('enc:v2');
    expect(ctx.el.querySelector('.table-container')!.textContent || '').not.toContain(B64);
    expect(badges[0].getAttribute('title')).toBe('Encrypted by field protection');
  });

  it('a ciphertext-looking value in a column that is not an encrypt field is rendered as plain text', async () => {
    const ctx = await setup();
    // city has no protect; station is hmac. Both carry enc:-looking values.
    await runDatabricks(ctx, [{ city: ct('c'), station: ct('s'), latitude: ct(1) }]);

    expect(ctx.el.querySelectorAll('.table-container .cell-encrypted').length).toBe(1);
    const table = ctx.el.querySelector('.table-container')!.textContent || '';
    expect(table).toContain(ct('c'));
    expect(table).toContain(ct('s'));
  });

  it('no Reveal button when the result set has no encrypted cells', async () => {
    const ctx = await setup();
    await runDatabricks(ctx, [{ city: 'Shanghai', latitude: '31.22222' }, { city: 'Austin', latitude: '' }]);

    expect(ctx.el.querySelectorAll('.table-container tbody tr').length).toBe(2);
    expect(ctx.el.querySelector('.reveal-btn')).toBeNull();
    expect(ctx.el.querySelector('.cell-encrypted')).toBeNull();
    expect(ctx.svc.reveal).not.toHaveBeenCalled();
  });

  it('Reveal posts one call per encrypted column with the distinct ciphertexts and the pipeline and field names', async () => {
    const ctx = await setup();
    await runDatabricks(ctx, [
      { city: 'A', latitude: ct(1), longitude: ct(10) },
      { city: 'B', latitude: ct(1), longitude: ct(11) },
      { city: 'C', latitude: ct(2), longitude: ct(10) }
    ]);
    expect(ctx.svc.reveal).not.toHaveBeenCalled();

    await clickReveal(ctx);

    expect(ctx.svc.reveal).toHaveBeenCalledTimes(2);
    expect(ctx.svc.reveal).toHaveBeenCalledWith('weather-forecast', 'latitude', jasmine.arrayWithExactContents([ct(1), ct(2)]));
    expect(ctx.svc.reveal).toHaveBeenCalledWith('weather-forecast', 'longitude', jasmine.arrayWithExactContents([ct(10), ct(11)]));
  });

  it('more than 1000 distinct values in a column are sent in chunks', async () => {
    const ctx = await setup();
    const rows = Array.from({ length: 2501 }, (_, i) => ({ city: 'c' + i, latitude: ct(i) }));
    await runDatabricks(ctx, rows);

    await clickReveal(ctx);

    const calls = ctx.svc.reveal.calls.allArgs().filter(a => a[1] === 'latitude');
    expect(calls.length).toBe(3);
    calls.forEach(a => {
      expect(a[0]).toBe('weather-forecast');
      expect(a[2].length).toBeLessThanOrEqual(1000);
    });
    const sent = sentValues(ctx.svc, 'weather-forecast', 'latitude');
    expect(sent.length).toBe(2501);
    expect(new Set(sent).size).toBe(2501);
    // Every chunk mapped back: no cell is left locked.
    expect(ctx.el.querySelectorAll('.cell-encrypted').length).toBe(0);
    expect(ctx.el.querySelectorAll('.cell-revealed').length).toBe(2501);
  });

  it('revealed cells show the plaintext with an unlocked badge and unrevealed ones keep the lock with the server\'s message as title', async () => {
    const ctx = await setup();
    ctx.svc.reveal.and.callFake(fakeReveal([ct(2)]));
    await runDatabricks(ctx, [{ city: 'A', latitude: ct(1) }, { city: 'B', latitude: ct(2) }]);

    await clickReveal(ctx);

    const revealed = ctx.el.querySelectorAll('.table-container .cell-revealed');
    expect(revealed.length).toBe(1);
    expect(revealed[0].textContent || '').toContain('plain-1');
    expect(revealed[0].textContent || '').not.toContain(B64);
    const locked = ctx.el.querySelectorAll('.table-container .cell-encrypted');
    expect(locked.length).toBe(1);
    expect(locked[0].getAttribute('title')).toBe(BAD_MESSAGE);
    expect(locked[0].textContent || '').toContain('enc:v2');
  });

  it('a 403 shows the capability message and leaves every cell locked', async () => {
    const ctx = await setup();
    ctx.svc.reveal.and.returnValue(throwError(() => new HttpErrorResponse({
      status: 403, error: { error: 'Forbidden: missing capability protect:reveal' }
    })));
    await runDatabricks(ctx, [{ city: 'A', latitude: ct(1) }, { city: 'B', latitude: ct(2) }]);

    await clickReveal(ctx);

    const err = ctx.el.querySelector('.reveal-error');
    expect(err).not.toBeNull();
    expect((err!.textContent || '')).toContain('You do not have the protect:reveal capability');
    expect(ctx.el.querySelectorAll('.cell-revealed').length).toBe(0);
    expect(ctx.el.querySelectorAll('.cell-encrypted').length).toBe(2);
    // Never retried.
    expect(ctx.svc.reveal).toHaveBeenCalledTimes(1);
  });

  it('the Reveal button is hidden for editor and viewer sessions and shown for admins', async () => {
    const rows = [{ city: 'A', latitude: ct(1) }];
    for (const role of ['editor', 'viewer', 'admin'] as const) {
      TestBed.resetTestingModule();
      const ctx = await setup({ userAuthEnabled: true, role });
      await runDatabricks(ctx, rows);
      // Badges render for everyone; only the button is gated.
      expect(ctx.el.querySelectorAll('.cell-encrypted').length).withContext(role + ' badges').toBe(1);
      if (role === 'admin') {
        expect(ctx.el.querySelector('.reveal-btn')).withContext(role).not.toBeNull();
      } else {
        expect(ctx.el.querySelector('.reveal-btn')).withContext(role).toBeNull();
      }
    }
  });

  it('the Reveal button is shown when user auth is off', async () => {
    const ctx = await setup({ userAuthEnabled: false, role: null });
    await runDatabricks(ctx, [{ city: 'A', latitude: ct(1) }]);

    expect(ctx.el.querySelector('.reveal-btn')).not.toBeNull();
  });

  it('a postgres query whose FROM table matches exactly one pipeline binds it without a picker', async () => {
    const ctx = await setup({ pipelines: [weatherDbx, fpPg, otherPg, plainPg, fpMongo] });
    await runPostgres(ctx, 'SELECT id, email FROM Public.FP_PG WHERE id > 0', [
      { id: 1, email: ct(1) }, { id: 2, email: ct(2) }
    ]);

    expect(ctx.el.querySelectorAll('.cell-encrypted').length).toBe(2);
    expect(ctx.el.querySelector('select.reveal-pipeline')).toBeNull();

    await clickReveal(ctx);

    expect(ctx.svc.reveal).toHaveBeenCalledTimes(1);
    expect(ctx.svc.reveal).toHaveBeenCalledWith('fp_pg', 'email', jasmine.arrayWithExactContents([ct(1), ct(2)]));
    expect(ctx.el.querySelectorAll('.cell-revealed').length).toBe(2);
  });

  it('a postgres query matching two pipelines shows a picker listing both', async () => {
    const ctx = await setup();
    await runPostgres(ctx, 'SELECT * FROM "public"."fp_pg"', [{ id: 1, email: ct(1) }]);

    const sel = ctx.el.querySelector('select.reveal-pipeline');
    expect(sel).not.toBeNull();
    expect(pickerOptions(ctx.el).sort()).toEqual(['fp_pg', 'fp_pg_two']);
    expect(ctx.svc.reveal).not.toHaveBeenCalled();

    if (sel) {
      await pick(ctx, 'fp_pg_two');
      await clickReveal(ctx);
      expect(ctx.svc.reveal).toHaveBeenCalledWith('fp_pg_two', 'email', [ct(1)]);
    }
  });

  it('a postgres query matching no pipeline shows a picker listing every pipeline with an encrypt field', async () => {
    const ctx = await setup();
    await runPostgres(ctx, 'SELECT * FROM archive_copy', [{ id: 1, email: ct(1) }]);

    expect(ctx.el.querySelector('select.reveal-pipeline')).not.toBeNull();
    const options = pickerOptions(ctx.el);
    expect(options).toEqual(jasmine.arrayWithExactContents(ENCRYPT_PIPELINES));
    expect(options).not.toContain('plain_pg');
  });

  it('a mongodb query binds the pipeline by database and collection', async () => {
    const ctx = await setup();
    await runMongo(ctx, 'fp_mongo', [{ id: '1', name: 'a', email: ct(1) }]);

    expect(ctx.el.querySelector('select.reveal-pipeline')).toBeNull();
    await clickReveal(ctx);

    expect(ctx.svc.reveal).toHaveBeenCalledTimes(1);
    expect(ctx.svc.reveal).toHaveBeenCalledWith('fp_mongo', 'email', [ct(1)]);
  });

  it('flat mongodb documents render as a table with lock badges and reveal like any other grid', async () => {
    const ctx = await setup();
    await runMongo(ctx, 'fp_mongo', [
      { id: '1', name: 'a', email: ct(1) },
      { id: '2', email: ct(2), phone: '555' }
    ]);

    expect(ctx.el.querySelector('pre.json-results')).toBeNull();
    const table = ctx.el.querySelector('.table-container');
    expect(table).not.toBeNull();
    const headers = texts(ctx.el, '.table-container th');
    expect(headers).toEqual(jasmine.arrayWithExactContents(['id', 'name', 'email', 'phone']));
    expect(ctx.el.querySelectorAll('.table-container tbody tr').length).toBe(2);
    expect(ctx.el.querySelectorAll('.table-container .cell-encrypted').length).toBe(2);
    expect((table && table.textContent) || '').not.toContain(B64);

    await clickReveal(ctx);

    const revealed = texts(ctx.el, '.table-container .cell-revealed');
    expect(revealed.length).toBe(2);
    expect(revealed.join(' ')).toContain('plain-1');
    expect(revealed.join(' ')).toContain('plain-2');
  });

  it('nested mongodb documents keep the JSON view with top-level ciphertext swapped after reveal', async () => {
    const ctx = await setup();
    const nested = ct('nested');
    await runMongo(ctx, 'fp_mongo', [
      { id: '1', email: ct(1), address: { city: 'Austin', email: nested } }
    ]);

    const pre = () => ctx.el.querySelector('pre.json-results');
    expect(pre()).not.toBeNull();
    expect(ctx.el.querySelector('.table-container')).toBeNull();
    // Top-level ciphertext shown as a badge, not the base64; nested left alone.
    expect(pre()!.textContent || '').not.toContain(ct(1));
    expect(pre()!.textContent || '').toContain('enc:v2');
    expect(pre()!.textContent || '').toContain(nested);

    await clickReveal(ctx);

    expect(ctx.svc.reveal).toHaveBeenCalledTimes(1);
    expect(ctx.svc.reveal).toHaveBeenCalledWith('fp_mongo', 'email', [ct(1)]);
    const text = pre()!.textContent || '';
    expect(text).toContain('plain-1');
    expect(text).not.toContain('plain-nested');
    expect(text).toContain(nested);
  });

  it('re-executing the query clears revealed values', async () => {
    const ctx = await setup();
    const rows = [{ city: 'A', latitude: ct(1) }, { city: 'B', latitude: ct(2) }];
    await runDatabricks(ctx, rows);
    await clickReveal(ctx);
    expect(ctx.el.querySelectorAll('.cell-revealed').length).toBe(2);

    ctx.component.execute();
    await settle(ctx.fixture);

    expect(ctx.el.querySelectorAll('.cell-revealed').length).toBe(0);
    expect(ctx.el.querySelectorAll('.cell-encrypted').length).toBe(2);
    expect(ctx.el.querySelector('.reveal-btn')).not.toBeNull();
    expect(((ctx.component as any).revealed as Map<string, string>).size).toBe(0);
    expect(ctx.svc.reveal).toHaveBeenCalledTimes(1);
    // Nothing kept in browser storage.
    const stored: string[] = [];
    for (const s of [sessionStorage, localStorage]) {
      for (let i = 0; i < s.length; i++) stored.push(s.getItem(s.key(i)!) || '');
    }
    expect(stored.join('\n')).not.toContain('plain-1');
  });

  it('a reveal still in flight when the query is re-executed does not reveal the new result set', async () => {
    const ctx = await setup();
    const pending = new Subject<any>();
    ctx.svc.reveal.and.returnValue(pending.asObservable());
    const rows = [{ city: 'A', latitude: ct(1) }];
    await runDatabricks(ctx, rows);
    await clickReveal(ctx);

    ctx.component.execute();
    await settle(ctx.fixture);
    // The stale response arrives after the re-run.
    pending.next({ values: [plain(ct(1))], revealed: 1, failed: 0, errors: [] });
    pending.complete();
    await settle(ctx.fixture);

    expect(((ctx.component as any).revealed as Map<string, string>).size).toBe(0);
    expect(ctx.el.querySelectorAll('.cell-revealed').length).toBe(0);
    expect(ctx.el.querySelectorAll('.cell-encrypted').length).toBe(1);
    expect(ctx.el.querySelector('.reveal-btn')).not.toBeNull();
  });

  it('a pipeline picked for one postgres table is not carried over to a query on another table', async () => {
    const ctx = await setup();
    await runPostgres(ctx, 'SELECT * FROM public.fp_pg', [{ id: 1, email: ct(1) }]);
    await pick(ctx, 'fp_pg_two');
    expect((ctx.component as any).revealPipeline).toBe('fp_pg_two');

    await runPostgres(ctx, 'SELECT * FROM archive_copy', [{ id: 1, email: ct(2) }]);

    expect(ctx.el.querySelector('select.reveal-pipeline')).not.toBeNull();
    expect((ctx.component as any).revealPipeline).toBe('');
    expect((ctx.component as any).revealPick).toBe('');
    const btn = ctx.el.querySelector('.reveal-btn') as HTMLButtonElement | null;
    expect(btn).not.toBeNull();
    expect(btn!.disabled).toBeTrue();
  });

  it('picking another pipeline after a reveal clears the revealed values and offers Reveal again', async () => {
    const ctx = await setup();
    await runPostgres(ctx, 'SELECT * FROM public.fp_pg', [{ id: 1, email: ct(1) }]);
    await pick(ctx, 'fp_pg');
    await clickReveal(ctx);
    expect(ctx.el.querySelectorAll('.cell-revealed').length).toBe(1);
    expect(ctx.el.querySelector('.reveal-btn')).toBeNull();

    await pick(ctx, 'fp_pg_two');

    expect(ctx.el.querySelectorAll('.cell-revealed').length).toBe(0);
    expect(ctx.el.querySelectorAll('.cell-encrypted').length).toBe(1);
    await clickReveal(ctx);
    expect(ctx.svc.reveal).toHaveBeenCalledWith('fp_pg_two', 'email', [ct(1)]);
  });

  it('mongodb documents whose only objects are extended-JSON scalars render as a table', async () => {
    const ctx = await setup();
    await runMongo(ctx, 'fp_mongo', [
      { _id: { $oid: '65f0c0ffee' }, email: ct(1), created: { $date: '2026-10-03T00:00:00Z' }, n: { $numberLong: '42' } }
    ]);

    expect(ctx.el.querySelector('pre.json-results')).toBeNull();
    const table = ctx.el.querySelector('.table-container');
    expect(table).not.toBeNull();
    const text = (table && table.textContent) || '';
    expect(text).toContain('65f0c0ffee');
    expect(text).toContain('2026-10-03T00:00:00Z');
    expect(text).toContain('42');
    expect(ctx.el.querySelectorAll('.table-container .cell-encrypted').length).toBe(1);
  });
});

describe('SearchService — reveal', () => {
  it('posts {pipeline, field, values} to /api/v1/protect/reveal', () => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    const service: any = TestBed.inject(SearchService);
    const http = TestBed.inject(HttpTestingController);

    expect(typeof service.reveal).toBe('function');
    if (typeof service.reveal !== 'function') return;
    let body: any = null;
    service.reveal('fp_pg', 'email', [ct(1)]).subscribe((r: any) => body = r);
    const req = http.expectOne('/api/v1/protect/reveal');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ pipeline: 'fp_pg', field: 'email', values: [ct(1)] });
    req.flush({ pipeline: 'fp_pg', field: 'email', values: ['a@b.c'], revealed: 1, failed: 0, errors: [] });
    expect(body.values).toEqual(['a@b.c']);
    http.verify();
  });
});
