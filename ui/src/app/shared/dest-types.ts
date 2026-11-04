/** True when this pipeline's destination supports on-demand typing
 *  (postgres/snowflake/databricks/sqlserver) and every effective destination
 *  column is still stored as text — the condition behind the Catalog "text"
 *  badge. On SQL Server the `_json` / `_xml` document columns are never
 *  retyped, so they do not count: the remaining columns decide, and a config
 *  with nothing but a document column has nothing to type. Computed from
 *  config already in hand; never stored. The server enforces the same rule,
 *  so a stale positive just opens a dialog that explains. */
export function isAllTextDestination(config: any): boolean {
  const db = config?.destination?.database;
  if (!db || !(db.usePostgres || db.useSnowflake || db.useDatabricks || db.useSqlServer)) return false;
  let fields = config?.destination?.schemaProperties?.fields;
  if (!Array.isArray(fields)) return false;
  if (db.useSqlServer) {
    fields = fields.filter((f: any) => {
      const name = String(f?.name ?? '').toLowerCase();
      return name !== '_json' && name !== '_xml';
    });
  }
  if (fields.length === 0) return false;
  return fields.every((f: any) => !f?.type || String(f.type).toLowerCase() === 'string');
}
