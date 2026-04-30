import React from 'react';
import ReactDOM from 'react-dom/client';
import App from './App';
import './style.css';
import axios from 'axios';
import { isDemoMode } from './demo/demoConfig';
import { resolveDemoResponse } from './demo/demoRouter';

// ─── Global Fetch Interceptor ────────────────────────────────────────────────
// Patches window.fetch so ALL fetch() calls in the app automatically include
// X-Tenant-ID, X-Impersonate-User and X-XSRF-TOKEN headers for internal APIs.
// This covers the 120+ hooks/services that use raw fetch() directly and would
// otherwise route to the wrong tenant DB on prod/preprod.
const _nativeFetch = window.fetch.bind(window);
window.fetch = async (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
  const url = typeof input === 'string' ? input : input instanceof URL ? input.href : (input as Request).url;
  const isInternal = url.startsWith('/') || url.includes(window.location.origin);

  // Demo mode: short-circuit before any network call
  if (isInternal && isDemoMode()) {
    const mockResponse = resolveDemoResponse(url);
    if (mockResponse) return mockResponse;
  }

  if (isInternal) {
    const tenantId    = sessionStorage.getItem('selectedTenantId');
    const impersonate = sessionStorage.getItem('impersonateUserId');
    const csrfToken   = document.cookie.split('; ').find(r => r.startsWith('XSRF-TOKEN='))?.split('=')[1];

    const headers = new Headers(init?.headers as HeadersInit);
    if (tenantId    && tenantId    !== 'null') headers.set('X-Tenant-ID',        tenantId);
    if (impersonate && impersonate !== 'null') headers.set('X-Impersonate-User', impersonate);
    if (csrfToken)                             headers.set('X-XSRF-TOKEN',       csrfToken);

    return _nativeFetch(input, { credentials: 'include', ...init, headers });
  }

  return _nativeFetch(input, init);
};
// ─────────────────────────────────────────────────────────────────────────────

// ─── Axios Demo Interceptor ──────────────────────────────────────────────────
// gcpDashboardApi.ts (and any other file) uses axios directly instead of
// window.fetch, so the fetch patch above doesn't catch those calls.
// This adapter replaces axios's network layer in demo mode so all axios
// requests are also short-circuited through the same mock router.
if (isDemoMode()) {
  const _nativeAxiosAdapter = axios.defaults.adapter;
  axios.defaults.adapter = async (config) => {
    const url = config.url ?? '';
    const isInternal = url.startsWith('/') || url.startsWith(window.location.origin);
    if (isInternal) {
      const mockResponse = resolveDemoResponse(url);
      if (mockResponse) {
        const data = mockResponse.status === 404 ? null : await mockResponse.json().catch(() => null);
        return { data, status: mockResponse.status, statusText: 'OK', headers: {}, config, request: {} };
      }
    }
    return (_nativeAxiosAdapter as any)(config);
  };
}
// ─────────────────────────────────────────────────────────────────────────────

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>
);
