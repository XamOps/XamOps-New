import { describe, it, expect, afterEach, vi } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { installFetch, renderWithProviders } from '../../pages/autospotting/__tests__/helpers';
import DlqPanel from './DlqPanel';

afterEach(() => vi.unstubAllGlobals());

const summary = (over: Record<string, unknown> = {}) => ({
  configured: true, queue: 'xamops-autospotting-events-uat-dlq.fifo', depth: 2, redriveRunning: false,
  messages: [
    { messageId: 'm1', detailType: 'EC2 Instance Launch Unsuccessful', account: '111111111111', groupId: 'g', sentAt: '2026-11-09T10:00:00Z', receiveCount: 5 },
    { messageId: 'm2', detailType: null, account: null, groupId: 'g', sentAt: null, receiveCount: 5 },
  ],
  ...over,
});

describe('DlqPanel', () => {
  it('lists the depth and message summaries without bodies', async () => {
    installFetch([{ match: /\/events\/dlq\?provider=aws$/, responses: [{ body: summary() }] }]);
    renderWithProviders(<DlqPanel />);
    expect(await screen.findByTestId('dlq-depth')).toHaveTextContent('2');
    expect(screen.getByText('EC2 Instance Launch Unsuccessful')).toBeInTheDocument();
    expect(screen.getByText('Unreadable')).toBeInTheDocument();
  });

  it('replays with a required reason, the intent header and an idempotency key', async () => {
    const fx = installFetch([
      { match: /\/events\/dlq\?provider=aws$/, responses: [{ body: summary() }, { body: summary({ depth: 0, messages: [] }) }] },
      { method: 'POST', match: /\/events\/dlq\/redrive/, responses: [{ body: { mode: 'MOVE_TASK', taskHandle: 't', moved: 0 } }] },
    ]);
    renderWithProviders(<DlqPanel />);
    await screen.findByTestId('dlq-depth');
    const replay = screen.getByRole('button', { name: 'Replay' });
    expect(replay).toBeDisabled();
    await userEvent.type(screen.getByLabelText('Reason'), 'Fixed the parser');
    expect(replay).toBeEnabled();
    await userEvent.click(replay);
    await waitFor(() => expect(fx.calls.some(c => c.method === 'POST')).toBe(true));
    const post = fx.calls.find(c => c.method === 'POST')!;
    expect(post.body).toEqual({ reason: 'Fixed the parser' });
    expect(post.headers['X-XamOps-Intent']).toBe('autospotting');
    expect(post.headers['Idempotency-Key']).toBeTruthy();
    expect(await screen.findByRole('status')).toHaveTextContent('Replay started.');
  });

  it('names a replay already in progress', async () => {
    installFetch([
      { match: /\/events\/dlq\?provider=aws$/, responses: [{ body: summary() }] },
      { method: 'POST', match: /\/events\/dlq\/redrive/, responses: [{ status: 409, body: { code: 'REDRIVE_IN_PROGRESS' } }] },
    ]);
    renderWithProviders(<DlqPanel />);
    await screen.findByTestId('dlq-depth');
    await userEvent.type(screen.getByLabelText('Reason'), 'again');
    await userEvent.click(screen.getByRole('button', { name: 'Replay' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('A replay is already running.');
  });

  it('says so when the environment has no events queue', async () => {
    installFetch([{ match: /\/events\/dlq\?provider=aws$/, responses: [{ body: { configured: false, queue: null, depth: 0, messages: [], redriveRunning: false } }] }]);
    renderWithProviders(<DlqPanel />);
    expect(await screen.findByText('No events queue in this environment.')).toBeInTheDocument();
  });
});
