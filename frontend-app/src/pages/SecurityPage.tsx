import React, { useCallback, useMemo } from 'react';
import ReactDOM from 'react-dom';
import { AnimatePresence, motion } from 'framer-motion';
import { Icon } from '@iconify/react';
import { useSearchParams } from 'react-router-dom';
import { securityFindingMatchesResource } from '../components/Cost/resourceSecurityDeepLink';
import { useAccounts } from '../contexts/AccountContext';
import { useSecurityData } from '../hooks/useSecurityData';
import {
  SecurityFindingsTab,
  SecurityProwlerBanner,
  IdentityAccessTab,
} from '../components/Security';
import { AriaTabGroup } from '../components/ui';
import { AlertsTabContent } from '../components/Alerts';
import { useTabState } from '../hooks/useTabState';

type ActiveTab = 'findings' | 'identity' | 'alerts';

const SecurityPage: React.FC = () => {
  const { selectedAccountIds } = useAccounts();
  const selectedAccountId = selectedAccountIds.length > 0 ? selectedAccountIds[0] : null;

  const {
    findings,
    isLoading,
    isRefreshing,
    error,
    prowlerStatus,
    loadFindings,
    resolveFinding,
    restoreFinding,
    resolvingKeys,
    lastResolved,
    dismissLastResolved,
  } = useSecurityData({ selectedAccountId });

  const [activeTab, setActiveTab] = useTabState<ActiveTab>('findings');

  /**
   * Optional deep-link focus from the Cost drawer's Security & Alerts tab: `?resource=<arn>`.
   *
   * <p>Additive and read-only — absent, the page behaves exactly as before. The match is
   * ARN-tolerant because `/security/findings` merges three sources: only the live projection sets
   * `resourceId` to the ARN, while Prowler and describe-based rows carry a resource NAME. So a
   * finding focuses when its `resourceId` equals the param, or the param (an ARN) ends with the
   * finding's shorter id as its `:`- or `/`-delimited tail.
   */
  const [params] = useSearchParams();
  const resource = params.get('resource');

  // The findings narrowed to the deep-linked resource, when one is present.
  const focusedFindings = useMemo(
    () => findings.filter((finding) => securityFindingMatchesResource(finding.resourceId, resource)),
    [findings, resource],
  );

  // Run Scan re-runs the account's checks; Retry repeats an ordinary load.
  const runScan = useCallback(() => { void loadFindings(true); }, [loadFindings]);
  const retry = useCallback(() => { void loadFindings(false); }, [loadFindings]);

  // Run Scan and Export sit on the Findings tab itself, as on the XamLens Misconfigurations page, so
  // this page registers no topbar actions. The Alerts tab still registers its own "Refresh Alerts"
  // (AlertsTabContent, gated by isActive) and clears it when it unmounts.
  const tabs = [
    { id: 'findings', label: 'Findings', icon: 'mdi:shield-search' },
    { id: 'identity', label: 'Identity & Access', icon: 'mdi:account-key-outline' },
    { id: 'alerts', label: 'Alerts', icon: 'mdi:bell-outline' },
  ];

  return (
    <main className="flex-1 flex flex-col min-h-screen bg-white">
      <SecurityProwlerBanner prowlerStatus={prowlerStatus} />
      {/* pb-24 keeps the pager clear of the floating chat-support widget (bottom-right). */}
      <div className="px-6 pt-4 pb-24 space-y-6">
        {!selectedAccountId && !isLoading && (
          <div className="text-center py-24 bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm">
            <div className="w-14 h-14 rounded-xl bg-indigo-50 border border-indigo-100 flex items-center justify-center mx-auto mb-4">
              <Icon icon="mdi:shield-half-full" width={28} color="#4f46e5" />
            </div>
            <h4 className="text-[15px] font-semibold text-[rgba(0,0,0,0.88)] mb-1">No Account Selected</h4>
            <p className="text-sm text-[rgba(0,0,0,0.5)]">Please select an account from the sidebar to view security findings.</p>
          </div>
        )}

        {/* Neutral variant: this page's colour budget is spent on severity, so an indigo
            accent here would read as a fifth status rather than as navigation. */}
        {selectedAccountId && (
          <AriaTabGroup
            variant="neutral"
            tabs={tabs}
            selectedKey={activeTab}
            onSelectionChange={(key) => setActiveTab(key as ActiveTab)}
          />
        )}

        {selectedAccountId && activeTab === 'identity' && (
          <motion.div
            key="identity"
            initial={{ opacity: 0, y: 8 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ duration: 0.18, ease: 'easeOut' }}
          >
            <IdentityAccessTab />
          </motion.div>
        )}

        {selectedAccountId && activeTab === 'alerts' && (
          <motion.div
            key="alerts"
            initial={{ opacity: 0, y: 8 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ duration: 0.18, ease: 'easeOut' }}
          >
            <AlertsTabContent isActive={activeTab === 'alerts'} />
          </motion.div>
        )}

        {selectedAccountId && activeTab === 'findings' && (
          <motion.div
            key="findings"
            initial={{ opacity: 0, y: 8 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ duration: 0.18, ease: 'easeOut' }}
          >
            {/* Keyed by account: search, filters and an open drawer belong to the account they were set on. */}
            <SecurityFindingsTab
              key={selectedAccountId}
              accountId={selectedAccountId}
              findings={focusedFindings}
              loading={isLoading}
              error={error}
              scanning={isRefreshing}
              onRunScan={runScan}
              onRetry={retry}
              onResolve={resolveFinding}
              resolvingKeys={resolvingKeys}
            />
          </motion.div>
        )}
      </div>

      {/*
        Undo, rather than a confirmation dialog before the fact. Marking a finding fixed is
        reversible and low-stakes — the server retires the claim the moment a scan disagrees — so
        a modal on every click would tax the common case to guard against a rare one. This also
        states the self-correcting behaviour plainly, since a user who thinks "fixed" means
        "hidden forever" would reasonably be reluctant to use it at all.

        Portalled above both finding drawers (z-50 and z-[60]), where Mark fixed is clicked, so Undo
        is never under them. Centred with motion's x rather than a translate class: motion writes the
        element's whole transform, which would drop the class.
      */}
      {ReactDOM.createPortal(
        <AnimatePresence>
          {lastResolved && (
            <motion.div
              key="resolved-toast"
              initial={{ opacity: 0, x: '-50%', y: 12 }}
              animate={{ opacity: 1, x: '-50%', y: 0 }}
              exit={{ opacity: 0, x: '-50%', y: 8 }}
              transition={{ duration: 0.2, ease: 'easeOut' }}
              role="status"
              aria-live="polite"
              className="fixed bottom-6 left-1/2 z-[70] flex items-center gap-3 px-4 py-2.5 rounded-lg bg-[#111111] text-white shadow-lg border border-[rgba(255,255,255,0.1)]"
            >
              <Icon icon="mdi:check-circle" width={16} className="text-emerald-400 shrink-0" />
              <div className="text-xs">
                <div className="font-semibold">Marked fixed</div>
                <div className="text-white/60">
                  It comes back if a scan finds it again.
                </div>
              </div>
              <button
                type="button"
                onClick={() => restoreFinding(lastResolved)}
                className="text-xs font-semibold text-white underline underline-offset-2 hover:text-emerald-300 focus:outline-none focus-visible:ring-2 focus-visible:ring-emerald-400 rounded px-1"
              >
                Undo
              </button>
              <button
                type="button"
                onClick={dismissLastResolved}
                aria-label="Dismiss"
                className="text-white/40 hover:text-white/80 focus:outline-none focus-visible:ring-2 focus-visible:ring-white/40 rounded"
              >
                <Icon icon="mdi:close" width={14} />
              </button>
            </motion.div>
          )}
        </AnimatePresence>,
        document.body,
      )}
    </main>
  );
};

export default SecurityPage;
