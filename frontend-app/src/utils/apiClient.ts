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

interface FetchOptions extends RequestInit {
  headers?: HeadersInit;
}

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
export const apiFetch = async (url: string | URL, options: FetchOptions = {}): Promise<Response> => {
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

  // Execute the request
  try {
    const response = await fetch(url, {
      ...options,
      headers,
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
    console.error('API Fetch Error:', error);
    throw error;
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
      body: body instanceof FormData ? body : JSON.stringify(body),
    }),
  put: <T = any>(url: string, body?: any) =>
    apiFetchJson<T>(url, {
      method: 'PUT',
      body: JSON.stringify(body),
    }),
  delete: <T = any>(url: string) => apiFetchJson<T>(url, { method: 'DELETE' }),
};
