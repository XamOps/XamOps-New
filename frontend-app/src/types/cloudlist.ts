/**
 * CloudList Type Definitions
 *
 * Converted from: cloudlist.html
 *
 * All types match the backend API responses exactly.
 */

/**
 * Cloud Resource Details
 */
export interface ResourceDetails {
  [key: string]: string | number | boolean | null | undefined;
}

/**
 * Cloud Resource
 */
export interface CloudResource {
  id: string;
  name: string | null;
  type: string;
  region: string;
  state: string;
  launchTime: string | null;
  details: ResourceDetails;
}

/**
 * Service Group
 */
export interface ServiceGroup {
  serviceType: string;
  resources: CloudResource[];
}

/**
 * Paginated Services Response
 */
export interface PaginatedServicesResponse {
  services: ServiceGroup[];
  currentPage: number;
  totalPages: number;
  totalServices: number;
  hasNext: boolean;
  hasPrevious: boolean;
}

/**
 * WebSocket Refresh Message
 */
export interface CloudlistRefreshMessage {
  accountId: string;
  status: 'refresh-complete' | 'refresh-failed';
  message?: string;
}
