import apiClient from '../utils/apiClient';
import type { MisconfigurationDetail, MisconfigurationList } from '../types/dspmMisconfigurations';
import { useLazyResource } from './useDspmDatastoreDrawer';

const BASE = '/api/dspm/misconfigurations';

/**
 * The Misconfigurations endpoints. Both are GET: XamLens reads Matters and never changes anything there.
 * Each promise rejects with the `apiClient` error, which `useLazyResource` turns into a `DspmError`.
 */
export const misconfigurationApi = {
  list: () => apiClient.get<MisconfigurationList>(BASE),
  detail: (policyId: string) =>
    apiClient.get<MisconfigurationDetail>(`${BASE}/policies/${encodeURIComponent(policyId)}`),
};

/** The page's list and cards, loaded once on mount; `reload` is the Retry action. */
export function useMisconfigurationList() {
  return useLazyResource(misconfigurationApi.list, true);
}
