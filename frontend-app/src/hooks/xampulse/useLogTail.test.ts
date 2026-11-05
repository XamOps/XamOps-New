import { describe, expect, it } from 'vitest';
import { INITIAL_TAIL, TAIL_BUFFER_CAP, applyTailFrame, buildTailUrl, pushTailRows } from './useLogTail';
import type { XpLogRow } from '../../types/xampulse';

const row = (i: number): XpLogRow => ({ id: `r${i}`, ts: new Date(i).toISOString(), accountId: 1, region: 'us-east-1', instanceId: 'i-1', service: 's', source: 'x', severity: 'INFO', body: `line ${i}`, attrs: {} });

describe('tail ring buffer', () => {
  it('puts the newest rows first and caps the buffer', () => {
    const first = pushTailRows([], [row(1), row(2)]);
    expect(first.map((r) => r.id)).toEqual(['r2', 'r1']);
    const many = pushTailRows([], Array.from({ length: TAIL_BUFFER_CAP + 50 }, (_, i) => row(i)));
    expect(many).toHaveLength(TAIL_BUFFER_CAP);
    expect(many[0].id).toBe(`r${TAIL_BUFFER_CAP + 49}`);
  });
});

describe('applyTailFrame', () => {
  it('handles rows, stats, notices and end frames', () => {
    let s = applyTailFrame(INITIAL_TAIL, { t: 'rows', rows: [row(1)], cursor: 'c', rate: 5 });
    expect(s.rows).toHaveLength(1);
    expect(s.rate).toBe(5);
    s = applyTailFrame(s, { t: 'stats', matched: 10, dropped: 2 });
    expect(s).toMatchObject({ matched: 10, dropped: 2 });
    s = applyTailFrame(s, { t: 'notice', code: 'RATE_CAPPED' });
    expect(s.notice).toMatch(/sample/);
    s = applyTailFrame(s, { t: 'end', reason: 'IDLE_TIMEOUT' });
    expect(s).toMatchObject({ status: 'ended', endReason: 'IDLE_TIMEOUT' });
  });
});

describe('buildTailUrl', () => {
  const loc = { protocol: 'https:', host: 'app.example.com' };
  it('resolves relative urls to wss and appends the ticket once', () => {
    expect(buildTailUrl('/xampulse-ws/tail', 't 1', loc)).toBe('wss://app.example.com/xampulse-ws/tail?ticket=t%201');
    expect(buildTailUrl('/xampulse-ws/tail?ticket=abc', 'abc', loc)).toBe('wss://app.example.com/xampulse-ws/tail?ticket=abc');
  });
});
