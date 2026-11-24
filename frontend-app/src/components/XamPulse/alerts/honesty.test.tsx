import { afterEach, describe, expect, it } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RuleEditor, blankRule } from './RuleEditor';
import { demoRequest } from '../../../demo/xampulseDemo';
import type { XpInsight } from '../../../types/xampulse';

/**
 * XamPulse never offers what it cannot do and never shows what it has not measured. These pin the two places that
 * once did: a ticket option the server refuses, and a rightsizing insight only the demo produced.
 */
afterEach(cleanup);

describe('XamPulse does not offer what it cannot do', () => {
  it('has no "create a ticket" option, because the server refuses ticketing until it exists', () => {
    render(
      <QueryClientProvider client={new QueryClient()}>
        <RuleEditor initial={blankRule(1)} channels={[]} onSaved={() => undefined} onCancel={() => undefined} onReload={() => undefined} />
      </QueryClientProvider>,
    );
    expect(screen.queryByText(/ticket/i)).toBeNull();
    expect(blankRule(1).routing.createTicket).toBe(false);
  });

  it('shows no rightsizing insight in the demo, because no real account gets one yet', async () => {
    const feed = await demoRequest<XpInsight[]>('GET', '/insights/feed', { accountIds: '1' });
    expect(feed.length).toBeGreaterThan(0);
    expect(feed.some((i) => i.kind === 'RIGHTSIZING')).toBe(false);
  });
});
