/**
 * Remembers the last cluster the signed-in identity opened on a given account, so the CloudK8s
 * landing resolver can reopen it instead of dropping the user on the fleet view every time.
 *
 * Stored in localStorage under `xamops.cloudk8s.lastCluster.v1:${userKey(email)}:${accountId}`,
 * where `userKey` (src/utils/k8sCache.ts) is the lowercased email plus the impersonated user id when
 * impersonating — so one identity's memory is never read for another. `accountId` is always the
 * account the cluster BELONGS to.
 *
 * Every read and write is wrapped in try/catch: a storage failure is simply "no memory", never an
 * error the caller has to handle. On read we re-validate the value (shape, length, and the same link
 * rules the cluster page applies via {@link isSwitchableCluster}) and remove anything invalid.
 */
import { userKey } from './k8sCache';
import { isSwitchableCluster } from './k8sClusterLink';

/** Namespace + version for the key, so a format change can be rolled without colliding. */
const KEY_PREFIX = 'xamops.cloudk8s.lastCluster.v1';

/** A stored value longer than this is treated as corrupt and removed. */
const MAX_VALUE_LENGTH = 512;

export interface LastCluster {
  name: string;
  region: string;
}

function storage(): Storage | null {
  try {
    return typeof localStorage !== 'undefined' ? localStorage : null;
  } catch {
    return null;
  }
}

/** `xamops.cloudk8s.lastCluster.v1:<userKey>:<accountId>`. */
function keyFor(email: string | null, accountId: string): string {
  return `${KEY_PREFIX}:${userKey(email)}:${accountId}`;
}

/**
 * The last cluster remembered for this identity on this account, or null when there is none, the
 * stored value is corrupt, or it no longer passes the page's link rules (in which case it is removed).
 */
export function readLastCluster(email: string | null, accountId: string): LastCluster | null {
  const store = storage();
  if (!store) return null;
  const key = keyFor(email, accountId);
  let raw: string | null;
  try {
    raw = store.getItem(key);
  } catch {
    return null;
  }
  if (!raw) return null;

  const remove = () => {
    try {
      store.removeItem(key);
    } catch {
      /* ignore */
    }
  };

  if (raw.length > MAX_VALUE_LENGTH) {
    remove();
    return null;
  }

  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch {
    remove();
    return null;
  }

  if (!parsed || typeof parsed !== 'object') {
    remove();
    return null;
  }
  const { name, region } = parsed as { name?: unknown; region?: unknown };
  if (typeof name !== 'string' || typeof region !== 'string' || !isSwitchableCluster({ name, region })) {
    remove();
    return null;
  }
  return { name, region };
}

/** Remembers `{name, region}` for this identity on this account. A storage failure is a silent no-op. */
export function writeLastCluster(email: string | null, accountId: string, cluster: LastCluster): void {
  const store = storage();
  if (!store) return;
  try {
    store.setItem(keyFor(email, accountId), JSON.stringify({ name: cluster.name, region: cluster.region }));
  } catch {
    /* ignore */
  }
}

/** Forgets the remembered cluster for this identity on this account. A storage failure is a silent no-op. */
export function forgetLastCluster(email: string | null, accountId: string): void {
  const store = storage();
  if (!store) return;
  try {
    store.removeItem(keyFor(email, accountId));
  } catch {
    /* ignore */
  }
}
