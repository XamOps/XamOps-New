/**
 * CloudList API Service
 *
 * Converted from: cloudlist.html (API fetch functions)
 *
 * All API endpoints match the original cloudlist.html exactly.
 * No changes to endpoints, methods, or request/response handling.
 */

import apiClient from '../utils/apiClient';
import type { PaginatedServicesResponse } from '../types/cloudlist';

/**
 * Fetch paginated cloud resources by service
 * Endpoint: GET /api/xamops/cloudlist/services-paginated
 */
export const fetchCloudlistServicesPaginated = async (
  accountId: string,
  page: number = 0,
  servicesPerPage: number = 5
): Promise<PaginatedServicesResponse> => {
  const url = `/api/xamops/cloudlist/services-paginated?accountId=${accountId}&page=${page}&servicesPerPage=${servicesPerPage}`;
  console.log('📡 [CloudList] Fetching services:', url);

  try {
    const result = await apiClient.get<PaginatedServicesResponse>(url);
    console.log('✅ [CloudList] Services fetched successfully:', result);
    return result;
  } catch (error: any) {
    console.error('❌ [CloudList] Error fetching services:', error);

    // Handle timeout or incomplete chunked encoding errors
    if (error?.message?.includes('timeout') ||
        error?.message?.includes('INCOMPLETE') ||
        error?.code === 'ECONNABORTED') {
      throw new Error('Backend is still fetching data from AWS. Please try again in a few moments.');
    }

    throw error;
  }
};

/**
 * Trigger backend refresh for cloudlist resources
 * Endpoint: GET /api/xamops/cloudlist/resources?forceRefresh=true
 */
export const triggerCloudlistRefresh = async (accountIds: string): Promise<void> => {
  const url = `/api/xamops/cloudlist/resources?accountIds=${accountIds}&forceRefresh=true`;
  console.log('🔄 [CloudList] Triggering refresh:', url);

  try {
    await apiClient.get(url);
    console.log('✅ [CloudList] Refresh triggered successfully');
  } catch (error) {
    console.error('❌ [CloudList] Error triggering refresh:', error);
    throw error;
  }
};
