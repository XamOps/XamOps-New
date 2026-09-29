import { useMemo } from 'react';
import { useQuery } from '@tanstack/react-query';
import { queryClient } from '../lib/queryClient';
import { fetchLabels, fetchResourceLabelMap } from '../services/resourceLabelsApi';
import type { ResourceLabel, ResourceLabelMap } from '../types/resourceLabels';

/**
 * Shared reads for XamOps resource labels, so two pages can show one set of them.
 *
 * <p>Labels live in XamOps alone — assigning one writes a row to
 * {@code resource_label_assignments} and touches nothing in AWS. That makes them shared
 * state by construction: the Cloud List and the Cost page's per-resource view read and write
 * the same table. What was missing was making the client agree. The original label code holds
 * everything in page-local {@code useState} filled from a bare promise, so a label added on
 * one page was invisible to the other until that page happened to remount, and there was no
 * mechanism to say otherwise.
 *
 * <h2>Why staleTime is zero here</h2>
 * Deliberately against the client default of five minutes. The point of these queries is to
 * reflect a write that may have happened on a different page, and a cache that serves a
 * five-minute-old label map cannot do that — the reader tags a bucket on the cost view,
 * opens the Cloud List and finds it untagged, which reads as the write having failed. These
 * are small indexed reads against our own database, not Athena scans, so refetching on mount
 * is the cheap side of the trade.
 *
 * <p>The cached value is still served immediately while the refetch runs, so this costs a
 * request rather than a loading state.
 */

const LABELS_KEY = 'resource-labels';
const LABEL_MAP_KEY = 'resource-label-map';

/** Every label defined for the client, for the assign picker. */
export function useLabels() {
  return useQuery({
    queryKey: [LABELS_KEY],
    queryFn: fetchLabels,
    staleTime: 0,
  });
}

/**
 * resourceId → labels, for a page of resources in one round-trip.
 *
 * <p>Ids may be in whichever form the caller holds. The Cloud List passes the inventory's
 * short id; the cost view passes the Cost and Usage Report's resource id, which is a full ARN
 * for several services. The backend reconciles the two and keys the response by what was
 * asked for, so both callers look up their own rows directly.
 *
 * @param accountId   a single provider account id, or null to fetch nothing
 * @param resourceIds ids to look up; blanks are dropped, since resource-less charges
 *                    (tax, support) carry an empty id and are not labellable
 */
export function useResourceLabelMap(accountId: string | null, resourceIds: string[]) {
  const ids = useMemo(
    () => Array.from(new Set(resourceIds.filter(Boolean))),
    [resourceIds],
  );

  /*
   * Sorted and joined rather than used as an array key. react-query hashes the key
   * structurally, so ['…', ['b','a']] and ['…', ['a','b']] are different cache entries and
   * a list that merely reordered would refetch and lose its cached value. Sorting makes the
   * key describe the set, which is what the request actually depends on.
   */
  const signature = useMemo(() => [...ids].sort().join('|'), [ids]);

  return useQuery({
    queryKey: [LABEL_MAP_KEY, accountId, signature],
    // Only ever called with `enabled` satisfied, so accountId is non-null here.
    queryFn: () => fetchResourceLabelMap({ accountId: accountId as string, resourceIds: ids }),
    enabled: Boolean(accountId) && ids.length > 0,
    staleTime: 0,
  });
}

/**
 * Drops every cached label read, so the next render reflects the database.
 *
 * <p>Called after an assign, a remove or a create. Coarse on purpose: a label's
 * {@code resourceCount} changes when any resource is tagged, and the map is cached per set of
 * ids, so there is no single entry to patch and no benefit in trying.
 */
export const invalidateResourceLabels = (): void => {
  void queryClient.invalidateQueries({ queryKey: [LABELS_KEY] });
  void queryClient.invalidateQueries({ queryKey: [LABEL_MAP_KEY] });
};

/**
 * Writes one resource's label set straight into the cache, without refetching.
 *
 * <p>For showing a change before the request that makes it has finished, and for undoing that
 * if it fails. It deliberately does not invalidate: a refetch issued before the write lands
 * returns the old row and overwrites the optimistic value with it, so the chip reappears and
 * nothing afterwards corrects it. Pair this with {@link invalidateResourceLabels} once the
 * write has returned.
 */
export const setResourceLabels = (
  accountId: string | null,
  resourceId: string,
  labels: ResourceLabel[],
): void => {
  queryClient.setQueriesData<ResourceLabelMap>(
    { queryKey: [LABEL_MAP_KEY, accountId] },
    (prev) => ({ ...(prev ?? {}), [resourceId]: labels }),
  );
};

/**
 * Publishes a completed write: shows it immediately, then confirms it against the database.
 *
 * <p>Only safe once the write has returned, which is the case for everything
 * {@code LabelAssignPopover} reports — it awaits its own call before calling back. The patch
 * keeps the chip from lagging a round-trip behind the click; the invalidation is what makes
 * the result authoritative, since the server may have stored the label under a different id
 * than the one passed in — ids are normalised on write — and is also what lets the other page
 * see this change.
 */
export const publishResourceLabels = (
  accountId: string | null,
  resourceId: string,
  labels: ResourceLabel[],
): void => {
  setResourceLabels(accountId, resourceId, labels);
  invalidateResourceLabels();
};
