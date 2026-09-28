export function sanitizeLabel(name: string): string {
  return name.toLowerCase().trim()
    .replace(/\s+/g, '_')
    .replace(/[^a-z0-9_-]/g, '')
    .replace(/_+/g, '_').replace(/-+/g, '-')
    .replace(/^[_-]+|[_-]+$/g, '');
}

/** Catalog names: same shape as sanitizeLabel but case is kept. Catalogs are
 *  display groupings, so "Sales_Q3" stays "Sales_Q3". Whitespace runs become
 *  `_`, characters outside [A-Za-z0-9_-] are dropped, repeats of `_` / `-`
 *  collapse and leading/trailing `_` / `-` are trimmed. Must stay in step with
 *  the server's CatalogOps.LabelRule (^[A-Za-z0-9_-]+$). */
export function sanitizeCatalogName(name: string): string {
  return name.trim()
    .replace(/\s+/g, '_')
    .replace(/[^A-Za-z0-9_-]/g, '')
    .replace(/_+/g, '_').replace(/-+/g, '-')
    .replace(/^[_-]+|[_-]+$/g, '');
}

/** Catalog names compare case-sensitively on the server, so a name that
 *  differs from an existing catalog only by case would create a second catalog
 *  next to it. Returns the existing name that matches `name` ignoring case but
 *  not exactly, or null when there is none (an exact match is not a twin). */
export function findCaseTwin(name: string, existing: string[]): string | null {
  const lower = name.toLowerCase();
  return existing.find(e => e !== name && e.toLowerCase() === lower) ?? null;
}

/** Message shown when a catalog create or rename hits a case-only twin. */
export function caseTwinMessage(twin: string): string {
  return `'${twin}' already exists with different capitalisation. Catalog names are case-sensitive, so this would create a second catalog.`;
}

export function sanitizeIdentifier(name: string): string {
  return name.toLowerCase().trim()
    .replace(/[\s-]+/g, '_')
    .replace(/[^a-z0-9_]/g, '')
    .replace(/_+/g, '_').replace(/^_|_$/g, '');
}
