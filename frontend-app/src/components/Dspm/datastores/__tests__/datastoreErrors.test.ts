import { createElement } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import type { DspmError, DspmReason } from '../../../../types/dspm';
import type { LazyResource } from '../../../../hooks/useDspmDatastoreDrawer';
import { datastoreErrorCopy } from '../datastoreErrors';
import { ErrorNote, ResourceView } from '../drawer/TabParts';

const DATASTORE_TEXT = 'This datastore could not be found. It may have been removed.';
const FILE_TEXT = 'This file could not be found. It may have been removed.';

const notFound: DspmError = { code: 'ASSET_NOT_FOUND', message: 'gone' };

const OTHER_CODES: DspmReason[] = [
  'NOT_ENTITLED', 'NOT_CONFIGURED', 'DISABLED', 'KEY_EXPIRED', 'INVALID_KEY', 'WRONG_ENVIRONMENT', 'FORBIDDEN_SCOPE',
  'DECRYPT_FAILED', 'MALFORMED_KEY', 'RATE_LIMITED', 'UPSTREAM_UNAVAILABLE', 'BAD_UPSTREAM_RESPONSE',
  'TAB_ACCESS_DENIED', 'INVALID_REQUEST',
];

function failed<T>(error: DspmError): LazyResource<T> {
  return { data: null, error, loading: false, reload: vi.fn() };
}

describe('datastoreErrorCopy', () => {
  it('says a datastore could not be found by default, without Retry', () => {
    expect(datastoreErrorCopy(notFound)).toEqual({ text: DATASTORE_TEXT, retry: false });
    expect(datastoreErrorCopy(notFound, 'datastore')).toEqual({ text: DATASTORE_TEXT, retry: false });
  });

  it("says a file could not be found with the 'file' subject, without Retry", () => {
    expect(datastoreErrorCopy(notFound, 'file')).toEqual({ text: FILE_TEXT, retry: false });
  });

  it("says a misconfiguration is no longer reported with the 'misconfiguration' subject, without Retry", () => {
    expect(datastoreErrorCopy(notFound, 'misconfiguration'))
      .toEqual({ text: 'This misconfiguration is no longer reported.', retry: false });
    const outage = { code: 'UPSTREAM_UNAVAILABLE' as DspmReason };
    expect(datastoreErrorCopy(outage, 'misconfiguration')).toEqual(datastoreErrorCopy(outage));
  });

  it("says an item could not be found with the 'item' subject, without Retry", () => {
    expect(datastoreErrorCopy(notFound, 'item')).toEqual({
      text: 'This item could not be found. It may have been removed.', retry: false,
    });
  });

  it.each(OTHER_CODES)('gives %s the same copy for both subjects', code => {
    const error = { code, retryAfterSeconds: code === 'RATE_LIMITED' ? 7 : undefined };
    const copy = datastoreErrorCopy(error);
    expect(datastoreErrorCopy(error, 'datastore')).toEqual(copy);
    expect(datastoreErrorCopy(error, 'file')).toEqual(copy);
    expect(datastoreErrorCopy(error, 'item')).toEqual(copy);
    expect(copy.text).not.toMatch(/could not be found/);
  });

  it('gives an unknown code the same outage copy for both subjects', () => {
    const error = { code: 'SOMETHING_NEW' as DspmReason };
    expect(datastoreErrorCopy(error, 'file')).toEqual(datastoreErrorCopy(error));
    expect(datastoreErrorCopy(error).retry).toBe(true);
  });
});

describe('ErrorNote and ResourceView subject (review fix R8)', () => {
  it('ErrorNote reads the datastore text by default and the file text with the subject', () => {
    const { unmount } = render(createElement(ErrorNote, { error: notFound, onRetry: vi.fn() }));
    expect(screen.getByRole('alert')).toHaveTextContent(DATASTORE_TEXT);
    expect(screen.queryByRole('button', { name: 'Retry' })).toBeNull();
    unmount();

    render(createElement(ErrorNote, { error: notFound, onRetry: vi.fn(), subject: 'file' }));
    expect(screen.getByRole('alert')).toHaveTextContent(FILE_TEXT);
    expect(screen.queryByRole('button', { name: 'Retry' })).toBeNull();
  });

  it('ResourceView passes its subject to ErrorNote', () => {
    const { unmount } = render(createElement(ResourceView<string>, {
      resource: failed<string>(notFound), subject: 'file', children: (data: string) => data,
    }));
    expect(screen.getByRole('alert')).toHaveTextContent(FILE_TEXT);
    unmount();

    render(createElement(ResourceView<string>, { resource: failed<string>(notFound), children: (data: string) => data }));
    expect(screen.getByRole('alert')).toHaveTextContent(DATASTORE_TEXT);
  });

  it('keeps Retry for a retryable code whatever the subject', () => {
    const reload = vi.fn();
    render(createElement(ResourceView<string>, {
      resource: { ...failed<string>({ code: 'UPSTREAM_UNAVAILABLE', message: 'down' }), reload },
      subject: 'file',
      children: (data: string) => data,
    }));
    expect(screen.getByRole('alert')).toHaveTextContent('Matters is not responding right now. Try again shortly.');
    screen.getByRole('button', { name: 'Retry' }).click();
    expect(reload).toHaveBeenCalledTimes(1);
  });
});
