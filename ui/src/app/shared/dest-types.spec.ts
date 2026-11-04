/**
 * Catalog "text" badge rule (isAllTextDestination).
 *
 * Story: SQL Server read-back and on-demand typing
 * (plans/stories/mssql-query-and-typing.md), Files + Step 3/4: a SQL Server
 * destination (`db.useSqlServer`) supports on-demand typing, so an all-string
 * SQL Server pipeline shows the badge. `_json` / `_xml` document columns
 * (JSON/XML-sourced SQL Server pipelines) are never retyped and do not count
 * toward the badge. The postgres/snowflake/databricks cases pin existing
 * behaviour so the change does not regress it.
 */
import { isAllTextDestination } from './dest-types';

function cfg(database: any, fields: any[] | null): any {
  return { destination: { database, schemaProperties: fields === null ? undefined : { fields } } };
}

const allText = [{ name: 'a', type: 'string' }, { name: 'b', type: 'string' }];
const typed = [{ name: 'a', type: 'int' }, { name: 'b', type: 'string' }];

describe('isAllTextDestination', () => {
  it('existing destinations keep their behaviour', () => {
    expect(isAllTextDestination(cfg({ usePostgres: true }, allText))).toBeTrue();
    expect(isAllTextDestination(cfg({ useSnowflake: true }, allText))).toBeTrue();
    expect(isAllTextDestination(cfg({ useDatabricks: true }, allText))).toBeTrue();
    expect(isAllTextDestination(cfg({ usePostgres: true }, typed))).toBeFalse();
    expect(isAllTextDestination(cfg({ useMongoDB: true }, allText))).toBeFalse();
    expect(isAllTextDestination(null)).toBeFalse();
  });

  it('an all-string SQL Server destination shows the text badge', () => {
    expect(isAllTextDestination(cfg({ useSqlServer: true }, allText))).toBeTrue();
  });

  it('a typed SQL Server destination does not', () => {
    expect(isAllTextDestination(cfg({ useSqlServer: true }, typed))).toBeFalse();
    expect(isAllTextDestination(cfg({ useSqlServer: true }, []))).toBeFalse();
  });

  it('SQL Server _json / _xml document columns do not count toward the badge', () => {
    // Document columns stay as-is; the remaining all-string columns still qualify.
    expect(isAllTextDestination(cfg({ useSqlServer: true },
      [{ name: '_json', type: 'string' }, { name: 'a', type: 'string' }]))).toBeTrue();
    expect(isAllTextDestination(cfg({ useSqlServer: true },
      [{ name: '_xml', type: 'xml' }, { name: 'a', type: 'string' }]))).toBeTrue();
    // A typed real column still turns the badge off.
    expect(isAllTextDestination(cfg({ useSqlServer: true },
      [{ name: '_json', type: 'string' }, { name: 'a', type: 'int' }]))).toBeFalse();
    // Nothing but a document column: nothing to type, no badge.
    expect(isAllTextDestination(cfg({ useSqlServer: true }, [{ name: '_json', type: 'string' }]))).toBeFalse();
  });
});
