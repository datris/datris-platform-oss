/**
 * Human-readable text for an Angular HttpErrorResponse.
 *
 * The server answers failures with a JSON body `{"error": "..."}` (older
 * routes may still return a plain string). Angular parses JSON bodies into
 * an object, so `err.error` is sometimes an object and sometimes a string;
 * concatenating the object renders "[object Object]". This picks the
 * message out of either shape (including a JSON string body, which calls
 * made with responseType 'text' receive) and falls back to the transport
 * message.
 */
export function httpErrorText(err: any, fallback = ''): string {
  const body = err?.error;
  if (typeof body === 'string' && body.trim()) {
    // Calls made with responseType 'text' (pipeline save, among others) get
    // the JSON body as a string: show its `error` message, not the raw JSON.
    const message = errorField(body);
    return message ?? body;
  }
  if (body && typeof body === 'object') {
    const inner = body.error ?? body.message ?? body.detail;
    if (typeof inner === 'string' && inner.trim()) return inner;
  }
  if (typeof err?.message === 'string' && err.message.trim()) return err.message;
  return fallback;
}

/** The non-blank string `error` of a JSON object body, or null when the
 *  text is not JSON, not an object, or has no such field. */
function errorField(text: string): string | null {
  try {
    const parsed = JSON.parse(text);
    if (parsed && typeof parsed === 'object' && typeof parsed.error === 'string' && parsed.error.trim()) return parsed.error;
  } catch {
    // not JSON
  }
  return null;
}
