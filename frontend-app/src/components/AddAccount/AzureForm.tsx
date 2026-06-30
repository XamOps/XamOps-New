import React, { useState } from 'react';
import { Icon } from '@iconify/react';
import type { AzureDetails } from '../../types/addAccount';

interface AzureFormProps {
  azureDetails: AzureDetails;
  isLoading: boolean;
  onAzureDetailsChange: (details: Partial<AzureDetails>) => void;
  onSubmit: () => void;
  onCopyToClipboard: (text: string) => Promise<boolean>;
}

interface CopyState {
  [key: string]: boolean;
}

const commands = [
  { id: 'connect', label: 'Step 1 — Authenticate', code: 'Connect-AzAccount -UseDeviceAuthentication' },
  { id: 'context', label: 'Step 2 — Set Subscription', code: 'Set-AzContext -Subscription "YOUR_SUBSCRIPTION_ID"', highlight: '"YOUR_SUBSCRIPTION_ID"' },
  { id: 'download', label: 'Step 3 — Download Script', code: 'Invoke-WebRequest -Uri "https://xamops-azure-connector1.s3.ap-south-1.amazonaws.com/setup-xamops-connection1.ps" -OutFile "setup-xamops-connection.ps1"' },
  { id: 'source', label: 'Step 4 — Source Script', code: '. .\\setup-xamops-connection.ps1' },
  { id: 'setup', label: 'Step 5 — Run Setup', code: 'Setup-XamOpsConnection -SubscriptionId "YOUR_SUBSCRIPTION_ID" -ApplicationName "XamOps" -CreateSpCredential $true', highlight: '"YOUR_SUBSCRIPTION_ID"' },
];

const inputCls = 'w-full px-3.5 py-2.5 text-[13px] border border-[rgba(0,0,0,0.12)] rounded-lg bg-white focus:outline-none focus:ring-2 focus:ring-black/5 focus:border-[rgba(0,0,0,0.25)] transition-colors placeholder:text-[rgba(0,0,0,0.3)]';
const labelCls = 'block text-[13px] font-medium text-[rgba(0,0,0,0.7)] mb-1.5';
const hintCls = 'text-[11px] text-[rgba(0,0,0,0.4)] mt-1';

const AzureForm: React.FC<AzureFormProps> = ({
  azureDetails, isLoading, onAzureDetailsChange, onSubmit, onCopyToClipboard,
}) => {
  const [copiedStates, setCopiedStates] = useState<CopyState>({});

  const handleCopy = async (text: string, id: string) => {
    const success = await onCopyToClipboard(text);
    if (success) {
      setCopiedStates((prev) => ({ ...prev, [id]: true }));
      setTimeout(() => setCopiedStates((prev) => ({ ...prev, [id]: false })), 2000);
    }
  };

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    onSubmit();
  };

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex items-center gap-3">
        <div className="w-10 h-10 rounded-xl bg-[#0078D4]/10 border border-[#0078D4]/20 flex items-center justify-center">
          <Icon icon="mdi:microsoft-azure" width={22} className="text-[#0078D4]" />
        </div>
        <div>
          <h2 className="text-[16px] font-bold text-black">Connect Azure Subscription</h2>
          <p className="text-[12px] text-[rgba(0,0,0,0.45)]">Run the PowerShell setup script and paste the credentials below.</p>
        </div>
      </div>

      {/* PowerShell Commands */}
      <div>
        <div className="flex items-center justify-between mb-2">
          <p className={labelCls}>PowerShell Setup Commands</p>
          <a
            href="https://shell.azure.com/"
            target="_blank"
            rel="noopener noreferrer"
            className="text-[11px] font-medium text-[#0078D4] hover:underline flex items-center gap-1"
          >
            Open Azure Cloud Shell <Icon icon="mdi:open-in-new" width={12} />
          </a>
        </div>

        <div className="space-y-2">
          {commands.map((cmd) => {
            const isCopied = copiedStates[cmd.id];
            return (
              <div key={cmd.id} className="rounded-lg overflow-hidden border border-[rgba(0,0,0,0.08)]">
                <div className="flex items-center justify-between px-4 py-2 bg-[#1e1e1e]">
                  <span className="text-[11px] text-[rgba(255,255,255,0.5)] font-medium">{cmd.label}</span>
                  <button
                    type="button"
                    onClick={() => handleCopy(cmd.code, cmd.id)}
                    className={`flex items-center gap-1.5 text-[11px] font-medium px-2 py-1 rounded transition-colors ${
                      isCopied
                        ? 'text-emerald-400'
                        : 'text-[rgba(255,255,255,0.6)] hover:text-white'
                    }`}
                  >
                    {isCopied ? <><Icon icon="mdi:check" width={13} />Copied</> : <><Icon icon="mdi:content-copy" width={13} />Copy</>}
                  </button>
                </div>
                <div className="px-4 py-3 bg-[#111] font-mono text-[12px] text-[rgba(255,255,255,0.8)] leading-relaxed overflow-x-auto">
                  {cmd.highlight ? (
                    <>
                      {cmd.code.split(cmd.highlight)[0]}
                      <span className="text-amber-300 font-medium">{cmd.highlight}</span>
                      {cmd.code.split(cmd.highlight)[1]}
                    </>
                  ) : cmd.code}
                </div>
              </div>
            );
          })}
        </div>
        <p className={hintCls}>Select PowerShell in Cloud Shell. Replace YOUR_SUBSCRIPTION_ID with your actual subscription ID.</p>
      </div>

      {/* Credentials Form */}
      <form onSubmit={handleSubmit} className="space-y-5">
        <div>
          <label htmlFor="azureAccountName" className={labelCls}>Account Name</label>
          <input
            id="azureAccountName"
            type="text"
            placeholder="e.g. Azure Production Subscription"
            value={azureDetails.accountName}
            onChange={(e) => onAzureDetailsChange({ accountName: e.target.value })}
            className={inputCls}
            required
          />
          <p className={hintCls}>A friendly name for this Azure subscription</p>
        </div>

        <div>
          <label htmlFor="azureCredentialsJson" className={labelCls}>Azure Credentials (JSON)</label>
          <textarea
            id="azureCredentialsJson"
            placeholder={'{\n  "appId": "...",\n  "password": "...",\n  "tenant": "..."\n}'}
            value={azureDetails.azureCredentialsJson}
            onChange={(e) => onAzureDetailsChange({ azureCredentialsJson: e.target.value })}
            rows={8}
            required
            className={`${inputCls} resize-none font-mono`}
          />
          <p className={hintCls}>Paste the JSON credentials output from the PowerShell script</p>
        </div>

        {/* Submit */}
        <button
          type="submit"
          disabled={isLoading}
          className="w-full py-3 bg-[#0078D4] text-white text-[13px] font-semibold rounded-lg hover:bg-[#106EBE] transition-colors disabled:opacity-40 flex items-center justify-center gap-2 shadow-sm shadow-[#0078D4]/20"
        >
          {isLoading ? (
            <><div className="w-3.5 h-3.5 border-2 border-white border-t-transparent rounded-full animate-spin" />Connecting...</>
          ) : (
            <>Verify & Connect <Icon icon="mdi:check-circle-outline" width={16} /></>
          )}
        </button>
      </form>
    </div>
  );
};

export default AzureForm;
