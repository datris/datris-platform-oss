export function sanitizeLabel(name: string): string {
  return name.toLowerCase().trim()
    .replace(/\s+/g, '_')
    .replace(/[^a-z0-9_-]/g, '')
    .replace(/_+/g, '_').replace(/-+/g, '-')
    .replace(/^[_-]+|[_-]+$/g, '');
}

export function sanitizeIdentifier(name: string): string {
  return name.toLowerCase().trim()
    .replace(/[\s-]+/g, '_')
    .replace(/[^a-z0-9_]/g, '')
    .replace(/_+/g, '_').replace(/^_|_$/g, '');
}
