// Minimal POSIX path helpers for hosts without `node:path` (Android WebView).
// Android paths are POSIX; these deliberately do not model Windows drives.

export function isAbsolute(path) {
  return typeof path === 'string' && path.startsWith('/');
}

/** Collapse `.`/`..` and duplicate separators, preserving a leading `/`. */
export function normalize(path) {
  if (typeof path !== 'string' || path.length === 0) return '.';
  const absolute = path.startsWith('/');
  const parts = [];
  for (const segment of path.split('/')) {
    if (segment === '' || segment === '.') continue;
    if (segment === '..') {
      if (parts.length > 0 && parts[parts.length - 1] !== '..') parts.pop();
      else if (!absolute) parts.push('..');
      continue;
    }
    parts.push(segment);
  }
  const body = parts.join('/');
  if (absolute) return `/${body}`.replace(/\/+$/, '') || '/';
  return body === '' ? '.' : body;
}

export function join(...parts) {
  const present = parts.filter(part => typeof part === 'string' && part.length > 0);
  return present.length === 0 ? '.' : normalize(present.join('/'));
}

/** POSIX `resolve(cwd, path)`: an absolute `path` wins, otherwise it joins `cwd`. */
export function resolve(cwd, path) {
  const base = typeof cwd === 'string' && cwd.length > 0 ? cwd : '/';
  const target = typeof path === 'string' ? path : '';
  return normalize(isAbsolute(target) ? target : join(base, target));
}

export function dirname(path) {
  if (typeof path !== 'string' || path.length === 0) return '.';
  const normalized = normalize(path);
  const index = normalized.replace(/\/+$/, '').lastIndexOf('/');
  if (index < 0) return '.';
  if (index === 0) return '/';
  return normalized.slice(0, index);
}

export function basename(path) {
  if (typeof path !== 'string' || path.length === 0) return '';
  const normalized = normalize(path).replace(/\/+$/, '');
  const index = normalized.lastIndexOf('/');
  return index < 0 ? normalized : normalized.slice(index + 1);
}
