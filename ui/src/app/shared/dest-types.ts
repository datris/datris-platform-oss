/** True when this pipeline's destination supports on-demand typing
 *  (postgres/snowflake/databricks) and every effective destination column is
 *  still stored as text — the condition behind the Catalog "text" badge.
 *  Computed from config already in hand; never stored. The server enforces
 *  the same rule, so a stale positive just opens a dialog that explains. */
export function isAllTextDestination(config: any): boolean {
  const db = config?.destination?.database;
  if (!db || !(db.usePostgres || db.useSnowflake || db.useDatabricks)) return false;
  const fields = config?.destination?.schemaProperties?.fields;
  if (!Array.isArray(fields) || fields.length === 0) return false;
  return fields.every((f: any) => !f?.type || String(f.type).toLowerCase() === 'string');
}
