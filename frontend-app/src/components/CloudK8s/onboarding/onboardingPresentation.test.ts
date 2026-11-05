import { describe, it, expect } from 'vitest';
import { presentOnboarding } from './onboardingPresentation';
import type { CapabilitiesOnboarding } from '../../../types/k8sAutomation';

const NOW = Date.parse('2026-10-30T12:00:00Z');

function onboarding(overrides: Partial<CapabilitiesOnboarding>): CapabilitiesOnboarding {
  return {
    state: 'NOT_INSTALLED',
    awaitingAgent: false,
    stale: false,
    automationDisabled: false,
    automationSubState: null,
    automationVerified: false,
    identitySeenAt: null,
    identityMode: null,
    nodeScaler: null,
    dataKeyScope: null,
    dataKey: null,
    automationKey: null,
    ...overrides,
  };
}

describe('presentOnboarding (design §9.1)', () => {
  it('absent block → Not installed / Connect', () => {
    const p = presentOnboarding(undefined, null, NOW);
    expect(p).toMatchObject({ word: 'Not installed', tone: 'off', nextAction: 'Connect' });
  });

  it('UNKNOWN → Unknown / Retry', () => {
    expect(presentOnboarding(onboarding({ state: 'UNKNOWN' }), null, NOW).word).toBe('Unknown');
  });

  it('not installed, recent exchange → Waiting for agent', () => {
    const lastExchange = new Date(NOW - 2 * 60 * 1000).toISOString();
    const p = presentOnboarding(onboarding({ awaitingAgent: true }), lastExchange, NOW);
    expect(p).toMatchObject({ word: 'Waiting for agent', tone: 'info' });
  });

  it('awaiting ≥ 15 min → Agent not reporting (warn)', () => {
    const lastExchange = new Date(NOW - 20 * 60 * 1000).toISOString();
    const p = presentOnboarding(onboarding({ awaitingAgent: true }), lastExchange, NOW);
    expect(p).toMatchObject({ word: 'Agent not reporting', tone: 'warn', nextAction: 'Troubleshoot' });
  });

  it('read-only → Read-only / Enable automation', () => {
    const p = presentOnboarding(onboarding({ state: 'CONNECTED_READ_ONLY' }), null, NOW);
    expect(p).toMatchObject({ word: 'Read-only', tone: 'good', nextAction: 'Enable automation' });
    expect(p.secondary).toBeNull();
  });

  it('read-only + stale → Data stale (warn)', () => {
    const p = presentOnboarding(onboarding({ state: 'CONNECTED_READ_ONLY', stale: true }), null, NOW);
    expect(p).toMatchObject({ word: 'Data stale', tone: 'warn' });
  });

  it('read-only + automationDisabled → Read-only with disabled secondary line', () => {
    const p = presentOnboarding(
      onboarding({ state: 'CONNECTED_READ_ONLY', automationDisabled: true, stale: true }),
      null,
      NOW,
    );
    expect(p.word).toBe('Read-only');
    expect(p.tone).toBe('good');
    expect(p.secondary).toContain('Automation disabled');
  });

  it('automation verifying when identitySeenAt null', () => {
    const p = presentOnboarding(
      onboarding({ state: 'AUTOMATION_ENABLED', automationVerified: false, identitySeenAt: null }),
      null,
      NOW,
    );
    expect(p.word).toBe('Automation · verifying');
    expect(p.tone).toBe('info');
  });

  it('automation identity not reported when identity older than 2h', () => {
    const seen = new Date(NOW - 3 * 60 * 60 * 1000).toISOString();
    const p = presentOnboarding(
      onboarding({ state: 'AUTOMATION_ENABLED', automationVerified: false, identitySeenAt: seen }),
      null,
      NOW,
    );
    expect(p.word).toBe('Automation · identity not reported');
    expect(p.tone).toBe('warn');
    expect(p.secondary).toContain('Identity not reported since');
  });

  it('automation verified sub-states map to Off/Analysis/Automated', () => {
    const base = { state: 'AUTOMATION_ENABLED' as const, automationVerified: true, identitySeenAt: new Date(NOW).toISOString() };
    expect(presentOnboarding(onboarding({ ...base, automationSubState: 'OFF' }), null, NOW).word).toBe('Automation · Off');
    expect(presentOnboarding(onboarding({ ...base, automationSubState: 'ANALYSIS' }), null, NOW)).toMatchObject({
      word: 'Automation · Analysis',
      tone: 'info',
    });
    expect(presentOnboarding(onboarding({ ...base, automationSubState: 'AUTOMATED' }), null, NOW)).toMatchObject({
      word: 'Automation · Automated',
      tone: 'good',
    });
  });
});
