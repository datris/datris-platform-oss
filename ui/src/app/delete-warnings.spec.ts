/**
 * Story: Pipeline delete: a table, collection or prefix that could not be
 * removed is reported, and no cleanup can hang the request
 * (plans/stories/pipeline-delete-reports-failed-cleanup.md), Part B,
 * `delete-warnings.spec.ts`.
 *
 * Pinned seam: `ui/src/app/delete-warnings.ts` exports a pure
 * `deleteWarnings(body: any): string[]` — the `warnings` strings of a
 * DELETE /api/v1/pipeline 200 body, whether the body arrives parsed (the
 * delete) or as text (`deletePipelineData` asks for `responseType: 'text'`).
 * Anything else is no warnings, never a throw.
 */
import { deleteWarnings } from './delete-warnings';

describe('deleteWarnings', () => {
  const w1 = 'Could not remove postgres table datris.public.cf_pg: the store could not be reached. ' +
    'Its pipeline configuration was deleted; remove it in the store.';
  const w2 = 'Kept object-store prefix lake.cf_os: another pipeline also writes to it.';

  it('object body', () => {
    expect(deleteWarnings({ warnings: [w1, w2] })).toEqual([w1, w2]);
    expect(deleteWarnings({ warnings: [w2], kept: ['postgres table datris.public.t'] })).toEqual([w2]);
    expect(deleteWarnings({ warnings: [] })).toEqual([]);
  });

  it('JSON string body', () => {
    expect(deleteWarnings(JSON.stringify({ warnings: [w1] }))).toEqual([w1]);
    expect(deleteWarnings('{"warnings":[]}')).toEqual([]);
    expect(deleteWarnings(JSON.stringify({ warnings: [w1, w2], kept: [] }))).toEqual([w1, w2]);
  });

  it('empty body', () => {
    expect(deleteWarnings('')).toEqual([]);
    expect(deleteWarnings('   ')).toEqual([]);
    expect(deleteWarnings(null)).toEqual([]);
    expect(deleteWarnings(undefined)).toEqual([]);
    expect(deleteWarnings({})).toEqual([]);
  });

  it('missing or non-array warnings', () => {
    expect(deleteWarnings({ kept: [w2] })).toEqual([]);
    expect(deleteWarnings({ warnings: null })).toEqual([]);
    expect(deleteWarnings({ warnings: w1 })).toEqual([]);
    expect(deleteWarnings({ warnings: { message: w1 } })).toEqual([]);
    expect(deleteWarnings('{"warnings":"x"}')).toEqual([]);
    expect(deleteWarnings('not json')).toEqual([]);
    expect(deleteWarnings('[]')).toEqual([]);
    expect(deleteWarnings(42)).toEqual([]);
  });
});
