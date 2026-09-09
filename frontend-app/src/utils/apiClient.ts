/**
 * API Client with Fetch Interceptor
 *
 * Converted from: _sidebar.html (fetch interceptor script)
 *
 * This utility wraps the native fetch API to automatically inject:
 * - CSRF Token (X-XSRF-TOKEN) from cookies
 * - Tenant ID (X-Tenant-ID) from sessionStorage
 * - Impersonation User ID (X-Impersonate-User) from sessionStorage
 * - Content-Type header for JSON requests
 *
 * Same logic as the original fetch interceptor - preserves all backend integration.
 */

import { isDemoMode } from '../demo/demoConfig';
import { resolveDemoResponse } from '../demo/demoRouter';

interface FetchOptions extends RequestInit {
  headers?: HeadersInit;
  /**
   * Abort the request after this many ms. Defaults to DEFAULT_TIMEOUT_MS.
   * Pass 0 to disable (e.g. for genuinely long-running uploads/exports).
   */
  timeoutMs?: number;
}

/**
 * Every request gets a client-side deadline.
 *
 * Without this, a hung backend leaves requests pending until the browser or nginx
 * gives up (nginx proxy_read_timeout is 300s). Polling hooks then stack up requests
 * on top of each other, multiplying load on an already-struggling backend. Bounding
 * the client side means a stalled call fails, gets rendered as an error, and does not
 * silently hold a connection slot.
 */
const DEFAULT_TIMEOUT_MS = 45_000;

/**
 * Get CSRF token from cookies
 */
const getCsrfToken = (): string | undefined => {
  const cookie = document.cookie
    .split('; ')
    .find(row => row.startsWith('XSRF-TOKEN='));
  return cookie?.split('=')[1];
};

/**
 * Get tenant context from session storage
 */
const getTenantContext = () => {
  return {
    tenantId: sessionStorage.getItem('selectedTenantId'),
    impersonateId: sessionStorage.getItem('impersonateUserId'),
  };
};

/**
 * Check if header exists (works with Headers object and plain object)
 */
const hasHeader = (headers: HeadersInit, key: string): boolean => {
  if (headers instanceof Headers) {
    return headers.has(key);
  } else if (typeof headers === 'object' && headers !== null) {
    return key in headers && (headers as Record<string, string>)[key] !== undefined;
  }
  return false;
};

/**
 * Add header safely (only if value exists and is not "null" string)
 */
const addHeader = (headers: HeadersInit, key: string, value: string | null | undefined): HeadersInit => {
  // Skip if value is null, undefined, "null" string, or empty string
  if (!value || value === 'null' || value === 'undefined') {
    return headers;
  }

  if (headers instanceof Headers) {
    headers.set(key, value);
    return headers;
  } else {
    return {
      ...headers as Record<string, string>,
      [key]: value,
    };
  }
};

/**
 * Delete header safely
 */
const deleteHeader = (headers: HeadersInit, key: string): HeadersInit => {
  if (headers instanceof Headers) {
    headers.delete(key);
    return headers;
  } else {
    const newHeaders = { ...(headers as Record<string, string>) };
    delete newHeaders[key];
    return newHeaders;
  }
};

/**
 * Enhanced fetch with automatic header injection
 * Mimics the exact behavior of the original fetch interceptor
 */
/**
 * Which source a *forced* cost refresh should read from.
 *
 * A sticky mode, not a scoped window. The first attempt at this wrapped the CUR button's
 * reload in a suppression scope, which cannot work: refreshDashboard is fire-and-forget
 * and defers its fetches with setTimeout(0), so the scope had already closed by the time
 * any request was issued — the CUR figures landed and Cost Explorer overwrote them
 * seconds later.
 *
 * Modelling it as "what the user last asked for" removes the timing dependency entirely.
 * Only forced refreshes consult it; ordinary page loads are untouched and keep reading
 * whatever the nightly job stored.
 */
export type CostSourceMode = 'CE' | 'CUR';

let costSourceMode: CostSourceMode = 'CE';

/** Called by whichever refresh control the user pressed, before it triggers the reload. */
export const setCostSourceMode = (mode: CostSourceMode): void => {
  costSourceMode = mode;
};

/**
 * Tags a forced cost refresh with the source the user asked for.
 *
 * CUR needs no parameter — the backend prefers the report by default, so omitting it is
 * how "use the report" is expressed.
 */
const withCostSourceForManualRefresh = (url: string | URL): string | URL => {
  const raw = url.toString();
  if (!raw.includes('forceRefresh=true')) return url;
  // The CUR-only endpoint is never a Cost Explorer request.
  if (raw.includes('/costs/refresh-from-cur')) return url;
  if (raw.includes('costSource=')) return url;
  if (costSourceMode !== 'CE') return url;
  return raw + (raw.includes('?') ? '&' : '?') + 'costSource=CE';
};

export const apiFetch = async (url: string | URL, options: FetchOptions = {}): Promise<Response> => {
  // Demo mode: return static mock response without hitting the network
  if (isDemoMode()) {
    const mockResponse = resolveDemoResponse(url.toString());
    if (mockResponse) return mockResponse;
  }

  // A manual Refresh means "give me a live figure now", which for cost data means the
  // Cost Explorer API rather than the Cost and Usage Report.
  //
  // Applied here rather than at each call site because one Refresh click fans out to a
  // dozen cost endpoints across the Dashboard, Cost and FinOps pages, and a new endpoint
  // would otherwise honour it only if whoever added it remembered to. The backend reads
  // the parameter once in CostSourceFilter.
  //
  // Scheduled refreshes are backend-side and never pass through here, so the nightly job
  // keeps reading the report. /costs/refresh-from-cur is excluded for the obvious reason.
  url = withCostSourceForManualRefresh(url);

  // Get context from session
  const { tenantId, impersonateId } = getTenantContext();
  const csrfToken = getCsrfToken();

  // Initialize headers if they don't exist
  let headers: HeadersInit = options.headers || {};

  // Check if this is an internal API call
  const urlStr = url.toString();
  const isInternal = urlStr.startsWith('/') || urlStr.includes(window.location.origin);

  if (isInternal) {
    // Only add Content-Type if there's a body and it's not FormData
    const methodsWithBody = ['POST', 'PUT', 'PATCH'];
    const hasBody = options.body !== undefined && options.body !== null;
    const isFormData = options.body instanceof FormData;

    if (methodsWithBody.includes(options.method || 'GET') && hasBody && !isFormData && !hasHeader(headers, 'Content-Type')) {
      headers = addHeader(headers, 'Content-Type', 'application/json');
    }

    // Always request JSON so Spring Boot error handler returns JSON (not HTML Whitelabel page)
    if (!hasHeader(headers, 'Accept')) {
      headers = addHeader(headers, 'Accept', 'application/json');
    }

    // Add security and tenant headers
    headers = addHeader(headers, 'X-XSRF-TOKEN', csrfToken);
    headers = addHeader(headers, 'X-Tenant-ID', tenantId);
    headers = addHeader(headers, 'X-Impersonate-User', impersonateId);

    // SPECIAL CASE: Remove tenant ID for superadmin endpoints
    if (urlStr.includes('/api/xamops/superadmin/tenants')) {
      headers = deleteHeader(headers, 'X-Tenant-ID');
      console.log('🔧 Fetch Interceptor: Removed X-Tenant-ID for superadmin/tenants endpoint');
    }

    // DEBUG: Log headers for CloudMap and CloudSitter endpoints
    if (urlStr.includes('/cloudmap/') || urlStr.includes('/cloudsitter/')) {
      console.log('🔍 Fetch Interceptor Debug:', {
        url: urlStr,
        tenantId: tenantId || 'NOT SET',
        impersonateId: impersonateId || 'NOT SET',
        csrfToken: csrfToken ? 'SET' : 'NOT SET',
        headers: headers instanceof Headers ? Object.fromEntries([...headers.entries()]) : headers,
      });
    }
  }

  // Apply a client-side deadline unless the caller opted out or supplied its own signal.
  const { timeoutMs, ...fetchOptions } = options;
  const effectiveTimeout = timeoutMs ?? DEFAULT_TIMEOUT_MS;
  let timeoutId: ReturnType<typeof setTimeout> | undefined;
  let signal = options.signal ?? undefined;

  if (effectiveTimeout > 0 && !options.signal && typeof AbortController !== 'undefined') {
    const controller = new AbortController();
    signal = controller.signal;
    timeoutId = setTimeout(() => controller.abort(), effectiveTimeout);
  }

  // Execute the request
  try {
    const response = await fetch(url, {
      ...fetchOptions,
      headers,
      signal,
      credentials: 'include', // Always include credentials for session management
    });

    // Handle unauthorized responses
    // Only treat 401 as session expiry if the user previously had an active session.
    // Pre-login 401s (e.g. on mount) are expected and should not trigger a redirect.
    if (response.status === 401 && !urlStr.includes('/login')) {
      const hadSession = !!sessionStorage.getItem('cached_user_roles');
      if (hadSession) {
        console.warn('Global Interceptor: Session expired — user had an active session.');
        window.dispatchEvent(new CustomEvent('session-expired'));
      }
    }

    return response;
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError' && timeoutId) {
      const timeoutError = new Error(`Request timed out after ${effectiveTimeout}ms: ${urlStr}`);
      console.error('API Fetch Timeout:', urlStr);
      throw timeoutError;
    }
    console.error('API Fetch Error:', error);
    throw error;
  } finally {
    if (timeoutId) clearTimeout(timeoutId);
  }
};

/**
 * Convenience wrapper for JSON responses
 */
export const apiFetchJson = async <T = any>(url: string | URL, options: FetchOptions = {}): Promise<T> => {
  console.log('🌐 [apiClient] Calling apiFetch for:', url.toString(), 'options:', options);

  const response = await apiFetch(url, options);

  console.log('🌐 [apiClient] Response received:', {
    url: url.toString(),
    status: response.status,
    statusText: response.statusText,
    ok: response.ok,
    headers: Object.fromEntries([...response.headers.entries()]),
  });

  if (!response.ok) {
    const errorText = await response.text();
    console.error('❌ [apiClient] API Error:', response.status, errorText);

    // Check if the error is HTML (likely 404 or 500 error page)
    if (errorText.trim().startsWith('<!DOCTYPE') || errorText.trim().startsWith('<html')) {
      throw new Error(`API endpoint not found (HTTP ${response.status}). The backend may not be implemented yet.`);
    }

    throw new Error(`HTTP ${response.status}: ${errorText}`);
  }

  // Success response handling
  const text = await response.text();

  // Handle empty bodies (common for 201, 202, 204 or just successful actions)
  if (!text || text.trim() === '') {
    return {} as T;
  }

  const contentType = response.headers.get('content-type');
  const isJson = contentType && contentType.includes('application/json');

  if (isJson) {
    try {
      return JSON.parse(text) as T;
    } catch (e) {
      console.warn('⚠️ [apiClient] Failed to parse JSON, returning raw text:', e);
      return text as unknown as T;
    }
  }

  // If not JSON, check if it's an HTML error page (common for misconfigured proxies or missing endpoints)
  if (text.trim().startsWith('<!DOCTYPE') || text.trim().startsWith('<html')) {
    throw new Error(`API endpoint returned HTML instead of JSON. Status: ${response.status}`);
  }

  // Fallback for plain text success messages
  console.log('📡 [apiClient] Returning raw text response');
  return text as unknown as T;
};

/**
 * Export a configured instance that can be used throughout the app
 */
export default {
  fetch: apiFetch,
  fetchJson: apiFetchJson,
  get: <T = any>(url: string, options?: FetchOptions) => apiFetchJson<T>(url, { method: 'GET', ...options }),
  post: <T = any>(url: string, body?: any) =>
    apiFetchJson<T>(url, {
      method: 'POST',
      // Don't send a body (or Content-Type) when body is null/undefined
      ...(body != null ? { body: body instanceof FormData ? body : JSON.stringify(body) } : {}),
    }),
  put: <T = any>(url: string, body?: any) =>
    apiFetchJson<T>(url, {
      method: 'PUT',
      body: JSON.stringify(body),
    }),
  delete: <T = any>(url: string) => apiFetchJson<T>(url, { method: 'DELETE' }),
};
