import { useCallback, useEffect, useRef, useState } from 'react';
import { xampulseApi } from '../../api/xampulseApi';
import { demoTailBatch } from '../../demo/xampulseDemo';
import type { XpLogRow, XpScope, XpTailClientFrame, XpTailServerFrame } from '../../types/xampulse';

export const TAIL_BUFFER_CAP = 5000;
export type TailStatus = 'idle' | 'connecting' | 'live' | 'paused' | 'reconnecting' | 'ended' | 'error';

export interface TailState {
  rows: XpLogRow[];
  status: TailStatus;
  matched: number;
  dropped: number;
  rate: number;
  notice: string | null;
  endReason: string | null;
}

export const INITIAL_TAIL: TailState = { rows: [], status: 'idle', matched: 0, dropped: 0, rate: 0, notice: null, endReason: null };

/** Newest first, capped. Pure so the ring-buffer behaviour is unit-testable. */
export function pushTailRows(existing: XpLogRow[], incoming: XpLogRow[], cap: number = TAIL_BUFFER_CAP): XpLogRow[] {
  if (incoming.length === 0) return existing;
  const merged = incoming.slice().reverse().concat(existing);
  return merged.length > cap ? merged.slice(0, cap) : merged;
}

/** Applies one server frame to the state. */
export function applyTailFrame(s: TailState, f: XpTailServerFrame): TailState {
  switch (f.t) {
    case 'rows': return { ...s, rows: pushTailRows(s.rows, f.rows), rate: f.rate };
    case 'stats': return { ...s, matched: f.matched, dropped: f.dropped };
    case 'notice': return { ...s, notice: f.code === 'RATE_CAPPED' ? 'Showing a sample: the stream exceeds 2,000 lines per second.' : f.code === 'IDLE_WARNING' ? 'Idle: the tail will close soon.' : f.message ?? null };
    case 'end': return { ...s, status: 'ended', endReason: f.reason };
  }
}

export function resolveWsUrl(url: string, loc: Pick<Location, 'protocol' | 'host'> = window.location): string {
  if (/^wss?:\/\//.test(url)) return url;
  const proto = loc.protocol === 'https:' ? 'wss:' : 'ws:';
  return `${proto}//${loc.host}${url.startsWith('/') ? url : `/${url}`}`;
}

/** Adds the ticket unless the server already put it in the URL. */
export function buildTailUrl(url: string, ticket: string, loc?: Pick<Location, 'protocol' | 'host'>): string {
  const full = loc ? resolveWsUrl(url, loc) : resolveWsUrl(url);
  if (/[?&]ticket=/.test(full)) return full;
  return `${full}${full.includes('?') ? '&' : '?'}ticket=${encodeURIComponent(ticket)}`;
}

export interface UseLogTail extends TailState {
  pause: () => void;
  resume: () => void;
  clear: () => void;
  stop: () => void;
}

export function useLogTail(scope: XpScope, query: string, enabled: boolean): UseLogTail {
  const [state, setState] = useState<TailState>(INITIAL_TAIL);
  const wsRef = useRef<WebSocket | null>(null);
  const pausedRef = useRef(false);
  const scopeRef = useRef(scope);
  const queryRef = useRef(query);
  scopeRef.current = scope;
  queryRef.current = query;
  const send = useCallback((f: XpTailClientFrame) => {
    const ws = wsRef.current;
    if (ws && ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify(f));
  }, []);

  const accountKey = scope.accountIds.join(',');
  useEffect(() => {
    if (!enabled || scopeRef.current.accountIds.length === 0) {
      setState((s) => (s.status === 'idle' ? s : { ...s, status: 'idle' }));
      return undefined;
    }
    let closed = false;
    let retries = 0;
    let ticketRetried = false;
    let timer: ReturnType<typeof setTimeout> | undefined;
    let demoTimer: ReturnType<typeof setInterval> | undefined;
    pausedRef.current = false;
    setState({ ...INITIAL_TAIL, status: 'connecting' });

    const connect = async () => {
      if (closed) return;
      try {
        const t = await xampulseApi.tailTicket({ scope: scopeRef.current, query: queryRef.current });
        if (closed) return;
        if (t.url === 'demo:tail') {
          let seq = 0;
          setState((s) => ({ ...s, status: 'live' }));
          demoTimer = setInterval(() => {
            if (pausedRef.current) return;
            seq += 1;
            const rows = demoTailBatch(seq);
            setState((s) => applyTailFrame(applyTailFrame(s, { t: 'rows', rows, cursor: String(seq), rate: rows.length * 2 }), { t: 'stats', matched: s.matched + rows.length, dropped: 0 }));
          }, 500);
          return;
        }
        const ws = new WebSocket(buildTailUrl(t.url, t.ticket));
        wsRef.current = ws;
        ws.onopen = () => { retries = 0; setState((s) => ({ ...s, status: pausedRef.current ? 'paused' : 'live' })); };
        ws.onmessage = (ev: MessageEvent<string>) => {
          try {
            const f = JSON.parse(ev.data) as XpTailServerFrame;
            setState((s) => applyTailFrame(s, f));
          } catch {
            /* ignore malformed frame */
          }
        };
        ws.onclose = (ev: CloseEvent) => {
          wsRef.current = null;
          if (closed) return;
          if (ev.code === 4401 && !ticketRetried) { ticketRetried = true; void connect(); return; }
          setState((s) => {
            if (s.status === 'ended') return s;
            return { ...s, status: ev.code === 4401 ? 'error' : 'reconnecting' };
          });
          if (ev.code === 4401) return;
          retries += 1;
          if (retries > 8) { setState((s) => ({ ...s, status: 'error' })); return; }
          const wait = Math.min(15_000, 500 * 2 ** retries) * (0.5 + Math.random() / 2);
          timer = setTimeout(() => { void connect(); }, wait);
        };
        ws.onerror = () => { /* onclose follows and handles the retry */ };
      } catch {
        if (closed) return;
        setState((s) => ({ ...s, status: 'error' }));
      }
    };
    void connect();

    return () => {
      closed = true;
      if (timer) clearTimeout(timer);
      if (demoTimer) clearInterval(demoTimer);
      const ws = wsRef.current;
      wsRef.current = null;
      if (ws) ws.close(1000);
    };
  }, [enabled, accountKey, query]);

  const pause = useCallback(() => {
    pausedRef.current = true;
    send({ t: 'pause' });
    setState((s) => (s.status === 'live' ? { ...s, status: 'paused' } : s));
  }, [send]);
  const resume = useCallback(() => {
    pausedRef.current = false;
    send({ t: 'resume' });
    setState((s) => (s.status === 'paused' ? { ...s, status: 'live' } : s));
  }, [send]);
  const clear = useCallback(() => setState((s) => ({ ...s, rows: [], matched: 0, dropped: 0 })), []);
  const stop = useCallback(() => {
    wsRef.current?.close(1000);
    setState((s) => ({ ...s, status: 'ended', endReason: 'CLIENT_CLOSED' }));
  }, []);

  return { ...state, pause, resume, clear, stop };
}
