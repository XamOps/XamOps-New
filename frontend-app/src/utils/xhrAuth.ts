/**
 * Applies the app's auth to a raw XMLHttpRequest.
 *
 * main.tsx patches window.fetch to attach tenant, impersonation and CSRF headers, but
 * media players (hls.js, dash.js) load playlists and segments over XHR, which that patch
 * never sees. During a BillOps "Login As" session the app authenticates with an
 * Authorization: Bearer header and no cookie at all, so an unpatched XHR arrives
 * unauthenticated — playback fails with 401/403 while every fetch-based API call on the
 * same page succeeds.
 *
 * Call this from a player's XHR hook (hls.js `xhrSetup`, dash.js request modifier) for
 * any request to a session-protected XamOps endpoint.
 */
export function applyAuthToXhr(xhr: XMLHttpRequest): void {
  const impersonateJwt = sessionStorage.getItem('impersonation_jwt');

  if (impersonateJwt) {
    // Impersonated session: JWT only. Sending the cookie too would authenticate as the
    // admin rather than the impersonated user, so credentials stay off — mirroring the
    // credentials: 'omit' the fetch patch uses.
    xhr.setRequestHeader('Authorization', `Bearer ${impersonateJwt}`);
    return;
  }

  // Normal session: same-origin XHR sends JSESSIONID on its own, but set this explicitly
  // so playback still works if a player is ever pointed at another origin.
  xhr.withCredentials = true;

  const tenantId = sessionStorage.getItem('selectedTenantId');
  if (tenantId && tenantId !== 'null') xhr.setRequestHeader('X-Tenant-ID', tenantId);

  const impersonateUser = sessionStorage.getItem('impersonateUserId');
  if (impersonateUser && impersonateUser !== 'null') xhr.setRequestHeader('X-Impersonate-User', impersonateUser);
}
