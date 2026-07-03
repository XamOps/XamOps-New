import React, { useState } from 'react';
import { Icon } from '@iconify/react';
import type { OnboardingAccount } from '../../types/xamsecure';

interface Props {
  accounts: OnboardingAccount[] | null;
  isLoading: boolean;
}

const STATUS_LABEL: Record<string, string> = {
  CONNECTED: 'Connected',
  PENDING: 'Pending',
  NOT_CONNECTED: 'Not connected',
};

const OnboardingTab: React.FC<Props> = ({ accounts, isLoading }) => {
  const [copiedId, setCopiedId] = useState<string | null>(null);
  const rows = accounts ?? [];

  const copy = async (acct: OnboardingAccount) => {
    if (!acct.sensorInstallCommand) return;
    await navigator.clipboard.writeText(acct.sensorInstallCommand);
    setCopiedId(acct.cloudAccountId);
    window.setTimeout(() => setCopiedId(null), 2000);
  };

  if (isLoading && rows.length === 0) {
    return <div className="text-center py-16 text-sm text-gray-400">Loading onboarding status...</div>;
  }

  return (
    <div className="space-y-4">
      {rows.map((a) => (
        <div key={a.cloudAccountId} className="bg-white border border-gray-200 rounded-xl shadow-sm p-5">
          <div className="flex items-center justify-between">
            <div>
              <p className="text-sm font-bold text-gray-900">{a.accountName}</p>
              <p className="text-[11px] text-gray-400 font-mono">{a.provider} · {a.cloudAccountId}</p>
            </div>
            <span className={`px-3 py-1 rounded-full text-[10px] font-bold ${
              a.status === 'CONNECTED' ? 'bg-gray-900 text-white'
              : a.status === 'PENDING' ? 'bg-gray-300 text-gray-800'
              : 'bg-gray-100 text-gray-500'}`}>
              {STATUS_LABEL[a.status] ?? a.status}
            </span>
          </div>
          {a.status !== 'CONNECTED' && !a.sensorInstallCommand && (
            <p className="mt-3 text-xs text-gray-500">
              Security monitoring is not yet enabled for this account. Contact your XamOps
              administrator to enable XamSecure coverage.
            </p>
          )}
          {a.sensorInstallCommand && (
            <div className="mt-4">
              <p className="text-[10px] font-semibold text-gray-500 uppercase tracking-wide mb-1.5">
                Sensor install command (run on your cluster)
              </p>
              <div className="flex items-start gap-2">
                <pre className="flex-1 bg-gray-50 border border-gray-200 rounded-lg p-3 text-[11px] text-gray-700 overflow-x-auto">
                  {a.sensorInstallCommand}
                </pre>
                <button onClick={() => copy(a)}
                        className="p-2 border border-gray-200 rounded-lg hover:bg-gray-50"
                        aria-label="Copy install command">
                  <Icon icon={copiedId === a.cloudAccountId ? 'mdi:check' : 'mdi:content-copy'} width={14} />
                </button>
              </div>
            </div>
          )}
        </div>
      ))}
      {rows.length === 0 && !isLoading && (
        <div className="text-center py-16 bg-white border border-gray-200 rounded-xl shadow-sm text-sm text-gray-400">
          No cloud accounts found. Add a cloud account to XamOps first.
        </div>
      )}
    </div>
  );
};

export default OnboardingTab;
