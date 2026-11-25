import React from 'react';
import { Link } from 'react-router-dom';
import { useXpAccounts, useXpStatus } from '../../../hooks/xampulse/useXamPulseQueries';
import { useViewContext } from '../../../hooks/xampulse/useViewContext';
import { EmptyState, ErrorState, LoadingGrid } from '../ui/ui';
import type { XamPulseTab } from '../../../constants/xampulseTabs';

/**
 * Five gate states before any tab renders: loading, feature disabled, no accounts, none usable, and
 * connected-but-not-set-up. The Setup tab is exempt from the last one so the CTA has somewhere to land.
 */
export const GlobalGate: React.FC<{ tab: XamPulseTab | 'host'; children: React.ReactNode }> = ({ tab, children }) => {
  const status = useXpStatus();
  const accounts = useXpAccounts();
  const ctx = useViewContext();

  if (status.isLoading || accounts.isLoading) return <LoadingGrid count={4} height={110} />;
  if (status.error) return <ErrorState error={status.error} onRetry={() => { void status.refetch(); }} />;
  if (status.data && !status.data.enabled) {
    return <EmptyState title="XamPulse is not enabled on this server" body="Observability 1.0 is switched off in this environment. Ask your administrator to enable it, then reload this page." />;
  }
  if (accounts.error) return <ErrorState error={accounts.error} onRetry={() => { void accounts.refetch(); }} />;
  const list = accounts.data ?? [];
  if (list.length === 0) {
    return (
      <EmptyState title="Connect an AWS account first" body="XamPulse watches the EC2 instances in your connected AWS accounts."
        steps={['Connect an AWS account in Account Manager', 'Come back here and press Start setup']}
        action={<Link className="xp-btn xp-btn--primary" to="/account-manager">Open Account Manager</Link>} />
    );
  }
  const selected = list.filter((a) => ctx.effectiveAccountIds.includes(a.cloudAccountId));
  if (selected.length === 0) {
    return <EmptyState title="Pick an account" body="Choose an AWS account in the bar above to see its observability data." />;
  }
  if (tab !== 'setup' && selected.every((a) => a.state === 'NOT_SET_UP')) {
    return (
      <EmptyState title="One step to see everything about your servers" body="Metrics, logs and alerts appear here as soon as this account is connected. Nothing runs on your instances until you approve it."
        steps={['Choose your instances and an S3 bucket for archives', 'Press Deploy to my AWS account', 'Watch the live checklist turn green']}
        action={<Link className="xp-btn xp-btn--primary xp-btn--lg" to={ctx.link('/xampulse/setup')}>Start setup</Link>} />
    );
  }
  return <>{children}</>;
};
