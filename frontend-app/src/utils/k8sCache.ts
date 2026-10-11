/**
 * Identity-keyed module caches for CloudK8s reads (docs/cloudk8s/ui-revamp-design.md §15).
 *
 * Automation and engine GETs are cached in module scope for 60 s so a subtab switch never refetches.
 * Every key is `userKey|accountId|region|clusterName`, where `userKey` is the signed-in profile email
 * plus the impersonated user id when there is one, read at access time, so an entry cached for one
 * identity or impersonation is never served to another. `useK8sCacheIdentity` also clears every cache
 * when the selected account, the profile email or the impersonation id changes. Logout leaves the
 * app through a full navigation, which drops module state.
 */

export const DEFAULT_K8S_CACHE_TTL_MS = 60_000;

/** sessionStorage key the app keeps the impersonated user id under (AuthContext, apiClient). */
export const IMPERSONATE_STORAGE_KEY = 'impersonateUserId';

export interface K8sCacheIdentity {
  /** AuthContext.userProfile.email; null before the profile loads. */
  email: string | null;
  /** sessionStorage.impersonateUserId; null when not impersonating. */
  impersonateUserId: string | null;
  /** The selected account id(s), joined; only used to notice an account switch. */
  accountKey: string | null;
}

let identity: K8sCacheIdentity = { email: null, impersonateUserId: null, accountKey: null };

function readImpersonation(): string | null {
  try {
    const value = typeof sessionStorage !== 'undefined' ? sessionStorage.getItem(IMPERSONATE_STORAGE_KEY) : null;
    return value && value !== 'null' && value !== 'undefined' ? value : null;
  } catch {
    return null;
  }
}

/** "jane@corp.example", or "jane@corp.example#as:77" while impersonating user 77. */
export function userKey(email: string | null = identity.email, impersonateUserId: string | null = readImpersonation()): string {
  const base = (email ?? '').trim().toLowerCase() || 'anonymous';
  return impersonateUserId ? `${base}#as:${impersonateUserId}` : base;
}

export interface K8sCacheScope {
  accountId: string;
  region?: string | null;
  clusterName?: string | null;
}

/** `userKey|accountId|region|clusterName`, with the identity read now. */
export function k8sCacheKey(scope: K8sCacheScope): string {
  return [userKey(), scope.accountId, scope.region ?? '', scope.clusterName ?? ''].join('|');
}

interface Entry<T> {
  value: T;
  expiresAt: number;
}

export interface K8sCache<T> {
  readonly name: string;
  /** The cached value for this scope and the current identity, or undefined when absent or expired. */
  get(scope: K8sCacheScope): T | undefined;
  set(scope: K8sCacheScope, value: T, ttlMs?: number): void;
  delete(scope: K8sCacheScope): void;
  clear(): void;
  /** For tests and diagnostics: live entries. */
  size(): number;
}

const registry = new Set<K8sCache<unknown>>();

/** A named cache registered for {@link clearK8sCaches}. Entries expire after `ttlMs`. */
export function createK8sCache<T>(name: string, ttlMs: number = DEFAULT_K8S_CACHE_TTL_MS): K8sCache<T> {
  const entries = new Map<string, Entry<T>>();
  const cache: K8sCache<T> = {
    name,
    get(scope) {
      const key = k8sCacheKey(scope);
      const entry = entries.get(key);
      if (!entry) return undefined;
      if (Date.now() >= entry.expiresAt) {
        entries.delete(key);
        return undefined;
      }
      return entry.value;
    },
    set(scope, value, ttl = ttlMs) {
      entries.set(k8sCacheKey(scope), { value, expiresAt: Date.now() + ttl });
    },
    delete(scope) {
      entries.delete(k8sCacheKey(scope));
    },
    clear() {
      entries.clear();
    },
    size() {
      const now = Date.now();
      let live = 0;
      for (const entry of entries.values()) if (now < entry.expiresAt) live++;
      return live;
    },
  };
  registry.add(cache as K8sCache<unknown>);
  return cache;
}

/** Empties every CloudK8s module cache. */
export function clearK8sCaches(): void {
  for (const cache of registry) cache.clear();
}

/**
 * Records the current identity and clears every cache when the account, the profile email or the
 * impersonation id changed since the last call. The first call only records. Returns whether the
 * caches were cleared.
 */
export function syncK8sCacheIdentity(next: Partial<K8sCacheIdentity> & { email: string | null }): boolean {
  const resolved: K8sCacheIdentity = {
    email: next.email ? next.email.trim().toLowerCase() : null,
    impersonateUserId: next.impersonateUserId !== undefined ? next.impersonateUserId : readImpersonation(),
    accountKey: next.accountKey ?? null,
  };
  const changed = resolved.email !== identity.email
    || resolved.impersonateUserId !== identity.impersonateUserId
    || resolved.accountKey !== identity.accountKey;
  const first = identity.email === null && identity.impersonateUserId === null && identity.accountKey === null;
  identity = resolved;
  if (changed && !first) {
    clearK8sCaches();
    return true;
  }
  return false;
}

/** For tests: forget the recorded identity and every cached entry. */
export function resetK8sCacheIdentityForTests(): void {
  identity = { email: null, impersonateUserId: null, accountKey: null };
  clearK8sCaches();
}
