/**
 * Story: an invalid pipeline config is a 400, not a 500
 * (plans/stories/pipeline-save-validation-400.md), review round 1.
 *
 * Calls made with responseType 'text' (pipeline save from the wizard, the
 * JSON editor, tap-create, the catalog moves) receive the server's
 * {"error": "<message>"} body as a string. httpErrorText must show the
 * message, not the raw JSON, while every other shape behaves as before.
 */
import { HttpErrorResponse } from '@angular/common/http';
import { httpErrorText } from './http-error';

describe('httpErrorText', () => {
  const MESSAGE = "Preset 'hipaa-safe-harbor': field 'phone' looks like phone and has no protection.";

  it('a string body that is JSON with a string error gives the message', () => {
    const err = new HttpErrorResponse({ error: JSON.stringify({ error: MESSAGE }), status: 400 });
    expect(httpErrorText(err)).toBe(MESSAGE);
  });

  it('Gson-escaped apostrophes in the wire body come out as plain text', () => {
    const wire = '{"error": "Preset \\u0027hipaa-safe-harbor\\u0027: field \\u0027phone\\u0027 looks like phone and has no protection."}';
    const err = new HttpErrorResponse({ error: wire, status: 400 });
    expect(httpErrorText(err)).toBe(MESSAGE);
    expect(httpErrorText(err)).not.toContain('\\u0027');
  });

  it('a non-JSON string body is returned as is', () => {
    expect(httpErrorText({ error: 'upstream timed out', status: 502 })).toBe('upstream timed out');
  });

  it('an object body with an error gives the message', () => {
    expect(httpErrorText({ error: { error: MESSAGE }, status: 400 })).toBe(MESSAGE);
  });

  it('a JSON string body whose error is not a string is returned as the original text', () => {
    const numeric = '{"error": 42}';
    expect(httpErrorText({ error: numeric })).toBe(numeric);
    const blank = '{"error": "  "}';
    expect(httpErrorText({ error: blank })).toBe(blank);
    const array = '["error"]';
    expect(httpErrorText({ error: array })).toBe(array);
    const noError = '{"message": "x"}';
    expect(httpErrorText({ error: noError })).toBe(noError);
  });

  it('existing shapes are unchanged: object message/detail, transport message, fallback', () => {
    expect(httpErrorText({ error: { message: 'm' } })).toBe('m');
    expect(httpErrorText({ error: { detail: 'd' } })).toBe('d');
    expect(httpErrorText({ error: null, message: 'Http failure response' })).toBe('Http failure response');
    expect(httpErrorText({ error: '' }, 'fallback')).toBe('fallback');
    expect(httpErrorText(null, 'fallback')).toBe('fallback');
  });
});
