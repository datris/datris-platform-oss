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

export function sanitizeIdentifier(name: string): string {
  return name.toLowerCase().trim()
    .replace(/[\s-]+/g, '_')
    .replace(/[^a-z0-9_]/g, '')
    .replace(/_+/g, '_').replace(/^_|_$/g, '');
}
