import { AuthService, CatalogGrantLevel } from '../auth.service';

/**
 * Catalog-scoped user access helpers (plans/stories/catalog-scoped-user-access-ui-assistant.md).
 *
 * Every restricted-user branch in the UI reads the /me fields through
 * AuthService (`isRestricted()`, `editableCatalogs()`), never infers access
 * from list contents. These wrappers tolerate an AuthService stand-in that
 * predates those helpers (older specs), treating it as unrestricted.
 */
export function isRestrictedUser(auth: AuthService | null | undefined): boolean {
  const a: any = auth;
  return !!a && typeof a.isRestricted === 'function' && a.isRestricted() === true;
}

/** Catalogs a restricted user can file work in (edit or own). */
export function editableCatalogsOf(auth: AuthService | null | undefined): string[] {
  const a: any = auth;
  if (!isRestrictedUser(auth) || typeof a.editableCatalogs !== 'function') return [];
  return a.editableCatalogs() || [];
}

/** The restricted user's level on a catalog; null when not granted. */
export function catalogLevelOf(auth: AuthService | null | undefined, catalog: string | null | undefined): CatalogGrantLevel | null {
  if (!isRestrictedUser(auth) || !catalog) return null;
  const a: any = auth;
  const me = typeof a.current === 'function' ? a.current() : null;
  const grants = (me && me.catalogGrants) || {};
  return grants[catalog] || null;
}

/** True when a restricted user may delete or move items in this catalog (own). */
export function canOwnCatalog(auth: AuthService | null | undefined, catalog: string | null | undefined): boolean {
  if (!isRestrictedUser(auth)) return true;
  return catalogLevelOf(auth, catalog) === 'own';
}

/** True when a restricted user may change items in this catalog (edit or own). */
export function canEditCatalog(auth: AuthService | null | undefined, catalog: string | null | undefined): boolean {
  if (!isRestrictedUser(auth)) return true;
  const l = catalogLevelOf(auth, catalog);
  return l === 'edit' || l === 'own';
}

/** Server refusal texts about a missing/invalid catalog for restricted users. */
export function isCatalogRefusal(text: string | null | undefined): boolean {
  return /^(Choose a catalog|No catalog you can edit)/.test((text || '').trim());
}
