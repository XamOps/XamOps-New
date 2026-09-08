import React from 'react';
import { Icon } from '@iconify/react';
import { roleNameFor } from '../../hooks/useAddAccount';
import CurSourceSelector, { type CurSourceSelection } from './CurSourceSelector';
import type { AccessType, AutoConnectStatus, VerificationDetails } from '../../types/addAccount';

interface AWSFormProps {
  accountName: string;
  accessType: AccessType;
  isLoading: boolean;
  isVerifying: boolean;
  autoConnectStatus: AutoConnectStatus;
  autoConnected: boolean;
  verificationDetails: VerificationDetails;
  verificationError: string;
  onAccountNameChange: (name: string) => void;
  onAccessTypeChange: (type: AccessType) => void;
  onVerificationDetailsChange: (details: Partial<VerificationDetails>) => void;
  onLaunchStack: () => void;
  onVerifyAccount: () => void;
  onOpenPolicyModal: () => void;
  curSource: CurSourceSelection;
  onCurSourceChange: (selection: CurSourceSelection) => void;
}

const inputCls = 'w-full px-3.5 py-2.5 text-[13px] border border-[rgba(0,0,0,0.12)] rounded-lg bg-white focus:outline-none focus:ring-2 focus:ring-black/5 focus:border-[rgba(0,0,0,0.25)] transition-colors placeholder:text-[rgba(0,0,0,0.3)]';
const labelCls = 'block text-[13px] font-medium text-[rgba(0,0,0,0.7)] mb-1.5';
const hintCls = 'text-[11px] text-[rgba(0,0,0,0.4)] mt-1';

const AWSForm: React.FC<AWSFormProps> = ({
  accountName, accessType, isLoading, isVerifying, autoConnectStatus, autoConnected,
  verificationDetails, verificationError,
  onAccountNameChange, onAccessTypeChange, onVerificationDetailsChange,
  onLaunchStack, onVerifyAccount, onOpenPolicyModal,
  curSource, onCurSourceChange,
}) => {
  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    if (isVerifying) onVerifyAccount();
    else onLaunchStack();
  };

  const isListening = autoConnectStatus === 'listening' || autoConnectStatus === 'slow';
  const expectedRoleName = roleNameFor(accessType);

  // ── Success ──
  if (autoConnected) {
    return (
      <div className="max-w-lg mx-auto py-16 text-center">
        <div className="w-14 h-14 rounded-full bg-emerald-50 border border-emerald-200 flex items-center justify-center mx-auto mb-4">
          <Icon icon="mdi:check" width={28} className="text-emerald-600" />
        </div>
        <h2 className="text-[18px] font-bold text-black mb-2">Account Connected</h2>
        <p className="text-[13px] text-[rgba(0,0,0,0.5)] mb-6">
          {accountName} has been verified and connected via EventBridge.
        </p>
        <a href="/account-manager" className="inline-flex items-center gap-2 px-5 py-2.5 bg-black text-white text-[13px] font-medium rounded-lg hover:bg-[rgba(0,0,0,0.85)] transition-colors">
          Go to Accounts
          <Icon icon="mdi:arrow-right" width={16} />
        </a>
      </div>
    );
  }

  // ── Verify step ──
  if (isVerifying) {
    return (
      <div className="max-w-lg">
        <h2 className="text-[16px] font-bold text-black mb-1">Verify Connection</h2>
        <p className="text-[13px] text-[rgba(0,0,0,0.5)] mb-6">
          {isListening
            ? 'We\'ll connect this automatically when the stack finishes — or enter the details yourself, whichever comes first.'
            : 'Enter the details from your CloudFormation stack outputs.'}
        </p>

        {/* Status only — never a gate. The form below stays usable in every state, so a
            customer who doesn't want to wait for the stack never has to. */}
        {autoConnectStatus === 'listening' && (
          <div className="flex items-center gap-3 px-4 py-3 bg-[rgba(0,0,0,0.02)] border border-[rgba(0,0,0,0.06)] rounded-lg mb-6">
            <div className="w-4 h-4 border-2 border-black border-t-transparent rounded-full animate-spin" />
            <span className="text-[12px] font-medium text-[rgba(0,0,0,0.6)]">
              Waiting for the CloudFormation stack — usually 1–3 minutes.
            </span>
          </div>
        )}

        {autoConnectStatus === 'slow' && (
          <div className="flex items-start gap-2.5 px-4 py-3 bg-amber-50 border border-amber-200 rounded-lg mb-6">
            <Icon icon="mdi:clock-alert-outline" width={16} className="text-amber-600 flex-shrink-0 mt-0.5" />
            <div>
              <p className="text-[12px] font-medium text-amber-900">This is taking longer than usual</p>
              <p className="text-[11px] text-amber-800 mt-0.5">
                Still listening — if the stack completes, this page will connect on its own. If it
                rolled back, or you'd rather not wait, fill in the details below.
              </p>
            </div>
          </div>
        )}

        {autoConnectStatus === 'stopped' && (
          <div className="flex items-start gap-2.5 px-4 py-3 bg-[rgba(0,0,0,0.02)] border border-[rgba(0,0,0,0.08)] rounded-lg mb-6">
            <Icon icon="mdi:bell-off-outline" width={16} className="text-[rgba(0,0,0,0.4)] flex-shrink-0 mt-0.5" />
            <div>
              <p className="text-[12px] font-medium text-[rgba(0,0,0,0.7)]">Stopped waiting for the stack</p>
              <p className="text-[11px] text-[rgba(0,0,0,0.5)] mt-0.5">
                Check whether it reached CREATE_COMPLETE in CloudFormation, then enter the details
                from its Outputs tab below.
              </p>
            </div>
          </div>
        )}

        <form onSubmit={handleSubmit} className="space-y-5">
          <div>
            <label htmlFor="awsAccountId" className={labelCls}>AWS Account ID</label>
            <input
              id="awsAccountId"
              type="text"
              placeholder="123456789012"
              value={verificationDetails.awsAccountId}
              onChange={(e) => onVerificationDetailsChange({ awsAccountId: e.target.value })}
              className={inputCls}
              required
            />
            <p className={hintCls}>12-digit AWS account identifier</p>
          </div>

          <div>
            <label htmlFor="roleName" className={labelCls}>IAM Role Name</label>
            <input
              id="roleName"
              type="text"
              placeholder={expectedRoleName}
              value={verificationDetails.roleName}
              onChange={(e) => onVerificationDetailsChange({ roleName: e.target.value })}
              className={inputCls}
              required
            />
            <p className={hintCls}>
              The stack names this role <span className="font-medium">{expectedRoleName}</span>
            </p>
          </div>

          {verificationError && (
            <div className="flex items-center gap-2 px-3 py-2.5 bg-red-50 border border-red-100 rounded-lg">
              <Icon icon="mdi:alert-circle-outline" width={16} className="text-red-500 flex-shrink-0" />
              <span className="text-[12px] text-red-700">{verificationError}</span>
            </div>
          )}

          <button
            type="submit"
            disabled={isLoading}
            className="w-full py-2.5 bg-black text-white text-[13px] font-medium rounded-lg hover:bg-[rgba(0,0,0,0.85)] transition-colors disabled:opacity-50 flex items-center justify-center gap-2"
          >
            {isLoading ? (
              <><div className="w-3.5 h-3.5 border-2 border-white border-t-transparent rounded-full animate-spin" />Verifying...</>
            ) : 'Verify & Connect'}
          </button>
        </form>
      </div>
    );
  }

  // ── Initial form ──
  return (
    <div>
      {/* Header with provider icon */}
      <div className="flex items-center gap-3 mb-6">
        <div className="w-10 h-10 rounded-xl bg-[#FF9900]/10 border border-[#FF9900]/20 flex items-center justify-center">
          <Icon icon="mdi:aws" width={22} className="text-[#FF9900]" />
        </div>
        <div>
          <h2 className="text-[16px] font-bold text-black">Connect AWS Account</h2>
          <p className="text-[12px] text-[rgba(0,0,0,0.45)]">Set up a secure cross-account IAM role using CloudFormation.</p>
        </div>
      </div>

      <form onSubmit={handleSubmit} className="space-y-6">
        {/* Account Name */}
        <div>
          <label htmlFor="accountName" className={labelCls}>Account Name</label>
          <input
            id="accountName"
            type="text"
            placeholder="e.g. AWS Production"
            value={accountName}
            onChange={(e) => onAccountNameChange(e.target.value)}
            className={inputCls}
            required
          />
          <p className={hintCls}>A friendly name to identify this account in XamOps</p>
        </div>

        {/* Access Level */}
        <div>
          <p className={labelCls}>Access Level</p>
          <div className="space-y-2 mt-2">
            <label
              className={`flex items-start gap-3 p-4 rounded-lg border cursor-pointer transition-all ${
                accessType === 'read-only' ? 'border-[#FF9900] bg-[#FF9900]/[0.03] shadow-sm shadow-[#FF9900]/10' : 'border-[rgba(0,0,0,0.1)] hover:border-[rgba(0,0,0,0.2)]'
              }`}
            >
              <input
                type="radio"
                name="access-type"
                checked={accessType === 'read-only'}
                onChange={() => onAccessTypeChange('read-only')}
                className="accent-[#FF9900] mt-0.5"
              />
              <div className="flex-1">
                <div className="flex items-center gap-2">
                  <span className="text-[13px] font-medium text-black">Read-Only Access</span>
                  <Icon icon="mdi:shield-check-outline" width={14} className="text-emerald-500" />
                </div>
                <p className="text-[11px] text-[rgba(0,0,0,0.45)] mt-0.5">Costs, usage, and inventory visibility. No modifications.</p>
              </div>
            </label>

            <label
              className={`flex items-start gap-3 p-4 rounded-lg border cursor-pointer transition-all ${
                accessType === 'read-write' ? 'border-[#FF9900] bg-[#FF9900]/[0.03] shadow-sm shadow-[#FF9900]/10' : 'border-[rgba(0,0,0,0.1)] hover:border-[rgba(0,0,0,0.2)]'
              }`}
            >
              <input
                type="radio"
                name="access-type"
                checked={accessType === 'read-write'}
                onChange={() => onAccessTypeChange('read-write')}
                className="accent-[#FF9900] mt-0.5"
              />
              <div className="flex-1">
                <div className="flex items-center gap-2">
                  <span className="text-[13px] font-medium text-black">Read & Write Access</span>
                  <Icon icon="mdi:shield-edit-outline" width={14} className="text-amber-500" />
                </div>
                <p className="text-[11px] text-[rgba(0,0,0,0.45)] mt-0.5">Full visibility plus resource management and operational actions.</p>
              </div>
            </label>
          </div>
        </div>

        {/* Cost data source — decides the CurS3Bucket parameter on the stack below, so it
            has to be answered before launching rather than after connecting. */}
        <div className="pt-2 border-t border-[rgba(0,0,0,0.06)]">
          <CurSourceSelector value={curSource} onChange={onCurSourceChange} />
        </div>

        {/* IAM Policies */}
        <div className="flex items-center justify-between py-3 border-t border-b border-[rgba(0,0,0,0.06)]">
          <div className="flex items-center gap-2">
            <Icon icon="mdi:file-document-outline" width={16} className="text-[rgba(0,0,0,0.4)]" />
            <span className="text-[13px] text-[rgba(0,0,0,0.6)]">IAM Policies</span>
          </div>
          <button type="button" onClick={onOpenPolicyModal} className="text-[12px] font-medium text-[#FF9900] hover:underline flex items-center gap-1">
            View Permissions <Icon icon="mdi:open-in-new" width={12} />
          </button>
        </div>

        {/* Submit */}
        <button
          type="submit"
          disabled={isLoading || !accountName.trim()}
          className="w-full py-3 bg-[#FF9900] text-white text-[13px] font-semibold rounded-lg hover:bg-[#E88B00] transition-colors disabled:opacity-40 flex items-center justify-center gap-2 shadow-sm shadow-[#FF9900]/20"
        >
          {isLoading ? (
            <><div className="w-3.5 h-3.5 border-2 border-white border-t-transparent rounded-full animate-spin" />Generating...</>
          ) : (
            <>Launch CloudFormation Stack <Icon icon="mdi:open-in-new" width={14} /></>
          )}
        </button>
      </form>
    </div>
  );
};

export default AWSForm;
