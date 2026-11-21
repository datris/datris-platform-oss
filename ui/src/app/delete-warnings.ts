/**
 * The `warnings` strings of a DELETE /api/v1/pipeline 200 body (a delete or
 * a reset), whether the body arrives parsed or as text. Anything else (empty,
 * not JSON, no or non-array `warnings`) is no warnings; never throws.
 */
export function deleteWarnings(body: any): string[] {
  let parsed: any = body;
  if (typeof body === 'string') {
    const text = body.trim();
    if (!text) return [];
    try {
      parsed = JSON.parse(text);
    } catch {
      return [];
    }
  }
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) return [];
  const warnings = parsed.warnings;
  if (!Array.isArray(warnings)) return [];
  return warnings.filter((w: any) => typeof w === 'string' && w.length > 0);
}
