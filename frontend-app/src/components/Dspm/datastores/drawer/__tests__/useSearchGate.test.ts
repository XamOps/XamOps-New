import { describe, expect, it, vi } from 'vitest';
import { act, renderHook } from '@testing-library/react';
import { useSearchGate, type TableRequest } from '../useSearchGate';

const request = (q: string, over: Partial<TableRequest> = {}): TableRequest => ({
  parentKey: 's:schema-public', q, page: 1, pageSize: 10, ...over,
});

/** A `send` whose requests stay pending until the test settles them, oldest first. */
function deferredSend() {
  const pending: Array<() => void> = [];
  const send = vi.fn((_r: TableRequest) => new Promise<void>(resolve => { pending.push(resolve); }));
  const settleNext = () => act(async () => { pending.shift()?.(); });
  return { send, settleNext };
}

describe('useSearchGate', () => {
  it('sends the first and the newest of three runs made while the first is pending', async () => {
    const { send, settleNext } = deferredSend();
    const { result } = renderHook(() => useSearchGate(send, () => true));

    act(() => {
      result.current(request('pay'));
      result.current(request('paym'));
      result.current(request('payme'));
    });
    expect(send).toHaveBeenCalledTimes(1);

    await settleNext();
    expect(send.mock.calls.map(([r]) => r.q)).toEqual(['pay', 'payme']);
  });

  it('does not re-send a queued request equal to the one that settled', async () => {
    const { send, settleNext } = deferredSend();
    const { result } = renderHook(() => useSearchGate(send, () => true));

    act(() => {
      result.current(request('pay'));
      result.current(request('pay'));
    });
    await settleNext();
    expect(send).toHaveBeenCalledTimes(1);
  });

  it('drops a queued request that is no longer wanted', async () => {
    const { send, settleNext } = deferredSend();
    const isWanted = vi.fn(() => false);
    const { result } = renderHook(() => useSearchGate(send, isWanted));

    act(() => {
      result.current(request('pay'));
      result.current(request('pay', { parentKey: 's:schema-audit' }));
    });
    await settleNext();
    expect(isWanted).toHaveBeenCalledWith(request('pay', { parentKey: 's:schema-audit' }));
    expect(send).toHaveBeenCalledTimes(1);
  });

  it('sends the next run at once after a settle', async () => {
    const { send, settleNext } = deferredSend();
    const { result } = renderHook(() => useSearchGate(send, () => true));

    act(() => result.current(request('pay')));
    await settleNext();
    act(() => result.current(request('refund')));
    expect(send).toHaveBeenCalledTimes(2);
    expect(send).toHaveBeenLastCalledWith(request('refund'));
  });
});
