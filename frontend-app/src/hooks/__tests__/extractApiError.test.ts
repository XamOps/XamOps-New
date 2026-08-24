import { describe, it, expect } from 'vitest';
import { extractApiError } from '../useObservabilityData';

/**
 * Pins what a user actually reads when a request fails.
 *
 * apiClient throws `Error("HTTP <status>: <raw body>")`. Everything useful about a failure is in
 * that body, and it arrives in two shapes: JSON `{error, retryable}` from the JSON endpoints, and
 * a `#`-commented plain-text explanation from the collector-config generators.
 *
 * The regression that prompted these tests: a 300-character cap meant to stop HTML error pages
 * reaching the UI also discarded the genuine explanations, which run to about 330 characters. The
 * Setup tab showed "Could not generate the configuration (HTTP 409)" while the server had sent a
 * paragraph naming the org and the remedy.
 */
describe('extractApiError', () => {
  it('uses the error field from a JSON body', () => {
    const e = new Error('HTTP 400: {"error":"Only SELECT queries are allowed.","retryable":false}');
    expect(extractApiError(e)).toEqual({
      message: 'Only SELECT queries are allowed.',
      retryable: false,
    });
  });

  it('honours the retryable flag the backend sets on infrastructure failures', () => {
    const e = new Error('HTTP 503: {"error":"Observability data is temporarily unavailable.","retryable":true}');
    expect(extractApiError(e).retryable).toBe(true);
  });

  it('treats 503 and 504 as retryable even without a flag', () => {
    expect(extractApiError(new Error('HTTP 503: upstream down')).retryable).toBe(true);
    expect(extractApiError(new Error('HTTP 504: gateway timeout')).retryable).toBe(true);
    expect(extractApiError(new Error('HTTP 409: nope')).retryable).toBe(false);
  });

  it('keeps a long plain-text explanation instead of replacing it with the status', () => {
    // Verbatim shape of the 409 the config generators return.
    const body =
      "# Configuration was not generated.\n#\n# The stored ingestion credential for org " +
      "'org_tenant_a' is the OpenObserve admin credential, left behind by an earlier " +
      'provisioning fallback. Issuing it would give this host access to every tenant\'s data. ' +
      'Re-provision observability for this account to replace it with a scoped service account.';
    expect(body.length).toBeGreaterThan(300); // the case the old cap silently dropped

    const { message } = extractApiError(new Error(`HTTP 409: ${body}`));

    expect(message).toContain('admin credential');
    expect(message).toContain('Re-provision observability');
    expect(message).not.toContain('HTTP 409');
  });

  it('does not dump an HTML error page into the UI', () => {
    const html = '<!DOCTYPE html><html><body><h1>502 Bad Gateway</h1>' + 'x'.repeat(50) + '</body></html>';
    const { message } = extractApiError(new Error(`HTTP 502: ${html}`), 'Request failed');
    expect(message).toBe('Request failed (HTTP 502)');
  });

  it('falls back for an implausibly long body', () => {
    const { message } = extractApiError(new Error(`HTTP 500: ${'y'.repeat(5000)}`), 'Boom');
    expect(message).toBe('Boom (HTTP 500)');
  });

  it('passes through client-side failures unchanged', () => {
    const { message, retryable } = extractApiError(new Error('Request timed out after 45000ms: /api/x'));
    expect(message).toContain('timed out');
    expect(retryable).toBe(true);
  });

  it('falls back when the value is not an Error', () => {
    expect(extractApiError('nope', 'Fallback').message).toBe('Fallback');
  });
});
