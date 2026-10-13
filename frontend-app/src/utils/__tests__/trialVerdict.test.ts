import { describe, it, expect } from 'vitest';
import { deriveTrialVerdict, parseServerDateTime } from '../trialVerdict';
import type { TrialAdminRow } from '../../types/billopsAdminTrials';

const NOW = new Date('2026-10-13T12:00:00');

/**
 * The backend's zone-less LocalDateTime.toString() shape, written in BROWSER-LOCAL time.
 * toISOString() would convert to UTC and, since the parser reads a zone-less string as local,
 * shift every offset by the runner's UTC offset — enough to move a few-hours case across the
 * day boundary the rules key on.
 */
const serverDate = (offsetDays: number) => {
  const d = new Date(NOW.getTime() + offsetDays * 86_400_000);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
    + `T${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
};

const row = (over: Partial<TrialAdminRow>): TrialAdminRow => ({
  clientId: 135,
  companyName: 'Nowageeks',
  email: 'ashish@example.com',
  planType: 'TRIAL',
  trialStatus: 'ACTIVE',
  trialEndAt: serverDate(10),
  daysRemaining: 10,
  ...over,
});

describe('deriveTrialVerdict', () => {
  it.each([
    [
      'EXPIRED status outranks a future end date',
      row({ trialStatus: 'EXPIRED', trialEndAt: serverDate(30), daysRemaining: 30 }),
      { state: 'EXPIRED', blocked: true, labelMatch: /EXPIRED — tenant blocked \(402\)/ },
    ],
    [
      'a past end date on an ACTIVE row reads EXPIRED',
      row({ trialStatus: 'ACTIVE', trialEndAt: serverDate(-2), daysRemaining: 0 }),
      { state: 'EXPIRED', blocked: true, labelMatch: /EXPIRED — lapsed 2 days ago/ },
    ],
    [
      'a single lapsed day is singular',
      row({ trialStatus: 'ACTIVE', trialEndAt: serverDate(-1), daysRemaining: 0 }),
      { state: 'EXPIRED', blocked: true, labelMatch: /lapsed 1 day ago/ },
    ],
    [
      'a trial that lapsed hours ago never says "0 days ago"',
      row({ trialStatus: 'ACTIVE', trialEndAt: serverDate(-3 / 24), daysRemaining: 0 }),
      { state: 'EXPIRED', blocked: true, labelMatch: /^EXPIRED — lapsed less than a day ago$/ },
    ],
    [
      'PAID with a positive daysRemaining never claims days left',
      row({ planType: 'PAID', trialStatus: null, trialEndAt: serverDate(12), daysRemaining: 12 }),
      { state: 'NONE', blocked: false, labelMatch: /^Paid — not trial-gated$/ },
    ],
    [
      'PAID + CONVERTED says it came from a trial',
      row({ planType: 'PAID', trialStatus: 'CONVERTED', trialEndAt: serverDate(-20), daysRemaining: 0 }),
      { state: 'NONE', blocked: false, labelMatch: /^Paid — converted from trial$/ },
    ],
    [
      'a trial with no end date is not gated',
      row({ trialEndAt: null, daysRemaining: 0 }),
      { state: 'ACTIVE', blocked: false, labelMatch: /^Active — no end date set$/ },
    ],
    [
      'daysRemaining 0 with a future end date reads as less than a day',
      row({ trialEndAt: serverDate(0.5), daysRemaining: 0 }),
      { state: 'ACTIVE', blocked: false, labelMatch: /^Active — less than a day left$/ },
    ],
    [
      'a live trial quotes the server figure',
      row({ trialEndAt: serverDate(12), daysRemaining: 12 }),
      { state: 'ACTIVE', blocked: false, labelMatch: /^Active — 12 days left$/ },
    ],
    [
      'one day left is singular',
      row({ trialEndAt: serverDate(1), daysRemaining: 1 }),
      { state: 'ACTIVE', blocked: false, labelMatch: /^Active — 1 day left$/ },
    ],
  ])('%s', (_label, input, expected) => {
    const verdict = deriveTrialVerdict(input, NOW);
    expect(verdict.state).toBe(expected.state);
    expect(verdict.blocked).toBe(expected.blocked);
    expect(verdict.label).toMatch(expected.labelMatch);
  });

  it('never reports days left for a tenant that is not on TRIAL', () => {
    const paid = deriveTrialVerdict(
      row({ planType: 'PAID', trialStatus: 'CONVERTED', daysRemaining: 12 }),
      NOW,
    );
    expect(paid.label).not.toMatch(/left/);
    expect(paid.label).not.toMatch(/12/);
    expect(paid.converted).toBe(true);
  });

  it('treats an unreadable end date as no end date rather than as expired', () => {
    const verdict = deriveTrialVerdict(row({ trialEndAt: 'not-a-date' }), NOW);
    expect(verdict.state).toBe('ACTIVE');
    expect(verdict.label).toBe('Active — no end date set');
  });
});

describe('parseServerDateTime', () => {
  it('reads the zone-less backend format', () => {
    expect(parseServerDateTime('2026-10-09T12:23:57.50031')?.getFullYear()).toBe(2026);
  });

  it('returns null for null and for garbage', () => {
    expect(parseServerDateTime(null)).toBeNull();
    expect(parseServerDateTime('')).toBeNull();
    expect(parseServerDateTime('nope')).toBeNull();
  });
});
