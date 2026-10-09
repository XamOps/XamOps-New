import { describe, it, expect } from 'vitest';
import {
  AUTOSPOTTING_CONVERSIONS_PAUSED,
  PAUSED_MESSAGE,
  isConversionsPausedError,
  parseAutoSpottingError,
} from '../autospottingErrors';

const httpError = (status: number, body: string) => new Error(`HTTP ${status}: ${body}`);

describe('parseAutoSpottingError', () => {
  it('maps a 409 paused refusal to the paused copy', () => {
    const err = httpError(409, JSON.stringify({
      success: false,
      code: AUTOSPOTTING_CONVERSIONS_PAUSED,
      error: 'server text',
    }));
    expect(parseAutoSpottingError(err)).toEqual({
      status: 409,
      code: AUTOSPOTTING_CONVERSIONS_PAUSED,
      message: PAUSED_MESSAGE,
    });
    expect(isConversionsPausedError(err)).toBe(true);
  });

  it('keeps the backend message for a 409 read-only refusal', () => {
    const err = httpError(409, JSON.stringify({
      success: false,
      code: 'AUTOSPOTTING_READ_ONLY_CONNECTION',
      error: 'This AWS connection is read-only.',
    }));
    const parsed = parseAutoSpottingError(err);
    expect(parsed.status).toBe(409);
    expect(parsed.code).toBe('AUTOSPOTTING_READ_ONLY_CONNECTION');
    expect(parsed.message).toBe('This AWS connection is read-only.');
    expect(isConversionsPausedError(err)).toBe(false);
  });

  it('uses fixed copy for a plain 403', () => {
    const parsed = parseAutoSpottingError(httpError(403, 'Forbidden'));
    expect(parsed.status).toBe(403);
    expect(parsed.message).toBe("You don't have access to this account, or it isn't connected to XamOps.");
  });

  it('uses fixed copy for a 401', () => {
    expect(parseAutoSpottingError(httpError(401, '')).message).toBe('Your session has expired. Sign in again.');
  });

  it('uses the error field of a 500 JSON body', () => {
    const parsed = parseAutoSpottingError(httpError(500, JSON.stringify({ success: false, error: 'Failed to disable: boom' })));
    expect(parsed).toEqual({ status: 500, code: undefined, message: 'Failed to disable: boom' });
  });

  it('falls back to the message field', () => {
    expect(parseAutoSpottingError(httpError(400, JSON.stringify({ message: 'Bad region' }))).message).toBe('Bad region');
  });

  it('never shows an HTML body', () => {
    const parsed = parseAutoSpottingError(httpError(502, '<html><body>Bad Gateway</body></html>'), 'Request failed');
    expect(parsed.status).toBe(502);
    expect(parsed.message).toBe('Request failed (HTTP 502)');
    expect(parsed.message).not.toContain('<');
  });

  it('uses the message of a network error', () => {
    const parsed = parseAutoSpottingError(new Error('Request timed out after 30000ms: /api/x'));
    expect(parsed).toEqual({ message: 'Request timed out after 30000ms: /api/x' });
    expect(isConversionsPausedError(new Error('Failed to fetch'))).toBe(false);
  });

  it('uses the fallback for a non-Error value', () => {
    expect(parseAutoSpottingError('oops', 'Toggle failed')).toEqual({ message: 'Toggle failed' });
    expect(parseAutoSpottingError(undefined).message).toBe('Request failed. Try again.');
    expect(isConversionsPausedError(null)).toBe(false);
  });
});
