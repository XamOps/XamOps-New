import { describe, expect, it } from 'vitest';
import { classifyApiError } from '../k8sApiError';

describe('classifyApiError', () => {
  it('maps 401/403 to unauthorized with the status', () => {
    expect(classifyApiError(new Error('HTTP 401: Unauthorized'))).toEqual({ kind: 'unauthorized', status: 401 });
    expect(classifyApiError(new Error('HTTP 403: Forbidden'))).toEqual({ kind: 'unauthorized', status: 403 });
  });

  it('maps 404 to notFound with the status', () => {
    expect(classifyApiError(new Error('HTTP 404: Not Found'))).toEqual({ kind: 'notFound', status: 404 });
  });

  it('maps 5xx to server with the status', () => {
    expect(classifyApiError(new Error('HTTP 500: Internal Server Error'))).toEqual({ kind: 'server', status: 500 });
    expect(classifyApiError(new Error('HTTP 503: Service Unavailable'))).toEqual({ kind: 'server', status: 503 });
  });

  it('derives the kind from the status code first, not the wording (Finding 4)', () => {
    expect(classifyApiError(new Error('API endpoint not found (HTTP 500). Something else.'))).toEqual({
      kind: 'server',
      status: 500,
    });
    expect(classifyApiError(new Error('API endpoint not found (HTTP 404). ...'))).toEqual({
      kind: 'notFound',
      status: 404,
    });
  });

  it('maps the apiClient HTML-instead-of-JSON shape to html (its "Status: <code>" is not an HTTP code)', () => {
    // apiClient throws `...Status: 502`, not `...HTTP 502`, so the status regex does not match and
    // the wording fallback classifies it as html.
    expect(classifyApiError(new Error('API endpoint returned HTML instead of JSON. Status: 502'))).toEqual({
      kind: 'html',
    });
  });

  it('falls back to html wording when there is no HTTP status code', () => {
    expect(classifyApiError(new Error('The response returned HTML instead of JSON.'))).toEqual({ kind: 'html' });
  });

  it('maps a timeout to network', () => {
    expect(classifyApiError(new Error('Request timed out after 15000ms: /api/xamops/k8s/clusters'))).toEqual({
      kind: 'network',
    });
  });

  it('maps a dropped connection (TypeError / Failed to fetch) to network', () => {
    expect(classifyApiError(new TypeError('Failed to fetch'))).toEqual({ kind: 'network' });
    expect(classifyApiError(new Error('Failed to fetch'))).toEqual({ kind: 'network' });
  });

  it('defaults an unrecognised error to server', () => {
    expect(classifyApiError(new Error('boom'))).toEqual({ kind: 'server' });
    expect(classifyApiError(undefined)).toEqual({ kind: 'server' });
  });
});
