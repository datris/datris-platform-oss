/**
 * Human-readable text for an Angular HttpErrorResponse.
 *
 * The server answers failures with a JSON body `{"error": "..."}` (older
 * routes may still return a plain string). Angular parses JSON bodies into
 * an object, so `err.error` is sometimes an object and sometimes a string;
 * concatenating the object renders "[object Object]". This picks the
 * message out of either shape and falls back to the transport message.
 */
export function httpErrorText(err: any, fallback = ''): string {
  const body = err?.error;
  if (typeof body === 'string' && body.trim()) return body;
  if (body && typeof body === 'object') {
    const inner = body.error ?? body.message ?? body.detail;
    if (typeof inner === 'string' && inner.trim()) return inner;
  }
  if (typeof err?.message === 'string' && err.message.trim()) return err.message;
  return fallback;
}
