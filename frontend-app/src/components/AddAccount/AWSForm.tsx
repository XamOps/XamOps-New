import React from 'react';
import { motion } from 'framer-motion';
import { Tag, ShieldCheck, ShieldAlert, FileText, ExternalLink, CheckCircle2, AlertCircle, Loader2, Cpu, Zap } from 'lucide-react';
import { cn } from '../../lib/utils';
import { Button } from '../ui/button';
import { Input } from '../ui/input';
import { Label } from '../ui/label';
import { Card, CardContent } from '../ui/card';
import { Badge } from '../ui/badge';
import type { AccessType, VerificationDetails } from '../../types/addAccount';

interface AWSFormProps {
  accountName: string;
  accessType: AccessType;
  isLoading: boolean;
  isVerifying: boolean;
  isAutoConnecting: boolean;
  autoConnected: boolean;
  verificationDetails: VerificationDetails;
  verificationError: string;
  onAccountNameChange: (name: string) => void;
  onAccessTypeChange: (type: AccessType) => void;
  onVerificationDetailsChange: (details: Partial<VerificationDetails>) => void;
  onLaunchStack: () => void;
  onVerifyAccount: () => void;
  onOpenPolicyModal: () => void;
}

const AWSForm: React.FC<AWSFormProps> = ({
  accountName, accessType, isLoading, isVerifying, isAutoConnecting, autoConnected,
  verificationDetails, verificationError,
  onAccountNameChange, onAccessTypeChange, onVerificationDetailsChange,
  onLaunchStack, onVerifyAccount, onOpenPolicyModal,
}) => {
  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    if (isVerifying) onVerifyAccount();
    else onLaunchStack();
  };

  // ── Success screen ──────────────────────────────────────────────────────
  if (autoConnected) {
    return (
      <motion.div initial={{ opacity: 0, scale: 0.97 }} animate={{ opacity: 1, scale: 1 }} transition={{ duration: 0.4 }}>
        <Card className="shadow-sm border-emerald-200 overflow-hidden">
          <div className="h-1 bg-gradient-to-r from-emerald-400 to-teal-500" />
          <CardContent className="p-10 flex flex-col items-center text-center">
            {/* Floating dots */}
            <div className="absolute inset-0 pointer-events-none overflow-hidden rounded-2xl">
              {[
                { top: '12%', left: '8%', size: 10, color: '#10b981', delay: '0s' },
                { top: '20%', right: '12%', size: 7, color: '#818cf8', delay: '0.6s' },
                { bottom: '18%', left: '15%', size: 9, color: '#a855f7', delay: '1.1s' },
                { bottom: '25%', right: '8%', size: 6, color: '#10b981', delay: '1.7s' },
              ].map((dot, i) => (
                <div
                  key={i}
                  className="absolute rounded-full animate-bounce opacity-60"
                  style={{ width: dot.size, height: dot.size, background: dot.color, animationDelay: dot.delay, ...dot as any }}
                />
              ))}
            </div>

            <motion.div
              initial={{ scale: 0 }}
              animate={{ scale: 1 }}
              transition={{ type: 'spring', stiffness: 300, damping: 20, delay: 0.1 }}
              className="w-24 h-24 rounded-full bg-gradient-to-br from-emerald-400 to-teal-500 flex items-center justify-center mb-6 shadow-2xl shadow-emerald-500/40 ring-8 ring-emerald-100"
            >
              <CheckCircle2 className="w-12 h-12 text-white" strokeWidth={2} />
            </motion.div>

            <h2 className="text-2xl font-bold text-slate-900 mb-2 tracking-tight">Account Connected!</h2>

            <div className="flex items-center gap-2 px-4 py-2 bg-emerald-50 border border-emerald-200 rounded-full mb-4">
              <i className="fab fa-aws text-orange-500" />
              <span className="text-sm font-semibold text-emerald-800">{accountName}</span>
            </div>

            <p className="text-slate-500 text-sm max-w-sm leading-relaxed mb-8">
              Your AWS account has been automatically verified and connected to XamOps via EventBridge.
              You can now start monitoring your cloud resources.
            </p>

            <Button variant="success" size="lg" onClick={() => { window.location.href = '/account-manager'; }} className="gap-2">
              <Cpu className="w-4 h-4" />
              Go to Account Manager
            </Button>
          </CardContent>
        </Card>
      </motion.div>
    );
  }

  // ── Verifying screen ───────────────────────────────────────────────────
  if (isVerifying) {
    return (
      <motion.div initial={{ opacity: 0, y: 16 }} animate={{ opacity: 1, y: 0 }} transition={{ duration: 0.4 }}>
        <Card className="shadow-sm border-slate-200 overflow-hidden">
          <div className="h-1 bg-gradient-to-r from-indigo-500 to-violet-500" />
          <CardContent className="p-6">
            {/* Header */}
            <div className="flex items-start gap-4 mb-6">
              <div className="w-10 h-10 rounded-xl bg-gradient-to-br from-indigo-500 to-violet-500 text-white flex items-center justify-center flex-shrink-0 shadow-lg shadow-indigo-500/25 text-sm font-bold">4</div>
              <div>
                <h2 className="text-lg font-bold text-slate-900 tracking-tight">
                  {isAutoConnecting ? 'Connecting Your Account' : 'Verify Connection'}
                </h2>
                <p className="text-sm text-slate-500">
                  {isAutoConnecting ? 'EventBridge will auto-verify once your stack completes' : 'Enter the details from your CloudFormation stack outputs'}
                </p>
              </div>
            </div>

            {/* Auto-connecting banner */}
            {isAutoConnecting && (
              <div className="flex items-center gap-4 p-4 bg-indigo-50 border border-indigo-200 rounded-xl mb-5">
                <div className="w-11 h-11 rounded-full bg-gradient-to-br from-indigo-500 to-violet-500 flex items-center justify-center flex-shrink-0 shadow-md shadow-indigo-500/25">
                  <Loader2 className="w-5 h-5 text-white animate-spin" />
                </div>
                <div>
                  <p className="text-sm font-semibold text-indigo-900">Connecting automatically via EventBridge</p>
                  <p className="text-xs text-indigo-600 mt-0.5">Waiting for your CloudFormation stack to complete — usually 1–3 minutes. No action needed.</p>
                </div>
              </div>
            )}

            {/* Success notice */}
            <div className="flex items-start gap-3 p-4 bg-emerald-50 border border-emerald-200 rounded-xl mb-6">
              <CheckCircle2 className="w-5 h-5 text-emerald-500 flex-shrink-0 mt-0.5" />
              <div>
                <p className="text-sm font-semibold text-emerald-800">Stack Launched Successfully!</p>
                <p className="text-xs text-emerald-700 mt-0.5">
                  {isAutoConnecting
                    ? 'Your account will connect here automatically once the stack finishes.'
                    : 'Enter the AWS Account ID and Role Name from the stack outputs below.'}
                </p>
              </div>
            </div>

            {/* Manual verification form */}
            {!isAutoConnecting && (
              <form onSubmit={handleSubmit} className="space-y-5">
                <div className="space-y-2">
                  <Label htmlFor="awsAccountId" className="flex items-center gap-1.5">
                    <i className="fas fa-id-card text-indigo-400 text-xs" />
                    AWS Account ID
                  </Label>
                  <Input
                    id="awsAccountId"
                    placeholder="123456789012"
                    value={verificationDetails.awsAccountId}
                    onChange={(e) => onVerificationDetailsChange({ awsAccountId: e.target.value })}
                    required
                  />
                  <p className="text-xs text-slate-400">12-digit AWS account identifier</p>
                </div>

                <div className="space-y-2">
                  <Label htmlFor="roleName" className="flex items-center gap-1.5">
                    <i className="fas fa-user-shield text-indigo-400 text-xs" />
                    IAM Role Name
                  </Label>
                  <Input
                    id="roleName"
                    placeholder="XamOps-ReadOnly-Role"
                    value={verificationDetails.roleName}
                    onChange={(e) => onVerificationDetailsChange({ roleName: e.target.value })}
                    required
                  />
                  <p className="text-xs text-slate-400">Name of the IAM role created by the stack</p>
                </div>

                {verificationError && (
                  <div className="flex items-start gap-3 p-4 bg-red-50 border border-red-200 rounded-xl">
                    <AlertCircle className="w-4 h-4 text-red-500 flex-shrink-0 mt-0.5" />
                    <p className="text-sm text-red-700">{verificationError}</p>
                  </div>
                )}

                <div className="flex justify-end pt-2 border-t border-slate-100">
                  <Button type="submit" disabled={isLoading} className="gap-2">
                    {isLoading ? (
                      <><Loader2 className="w-4 h-4 animate-spin" />Verifying...</>
                    ) : (
                      <><CheckCircle2 className="w-4 h-4" />Verify &amp; Connect</>
                    )}
                  </Button>
                </div>
              </form>
            )}
          </CardContent>
        </Card>
      </motion.div>
    );
  }

  // ── Initial form ────────────────────────────────────────────────────────
  return (
    <motion.div initial={{ opacity: 0, y: 16 }} animate={{ opacity: 1, y: 0 }} transition={{ duration: 0.4 }}>
      <Card className="shadow-sm border-slate-200 overflow-hidden">
        <div className="h-1 bg-gradient-to-r from-orange-400 to-amber-400" />
        <CardContent className="p-6">
          {/* Header */}
          <div className="flex items-start gap-4 mb-6">
            <div className="w-10 h-10 rounded-xl bg-gradient-to-br from-orange-400 to-amber-400 text-white flex items-center justify-center flex-shrink-0 shadow-lg shadow-orange-400/25 text-sm font-bold">2</div>
            <div>
              <h2 className="text-lg font-bold text-slate-900 tracking-tight">Configure AWS Access</h2>
              <p className="text-sm text-slate-500">Set up a secure cross-account IAM role using CloudFormation</p>
            </div>
          </div>

          <form onSubmit={handleSubmit} className="space-y-6">
            {/* Account Name */}
            <div className="space-y-2">
              <Label htmlFor="accountName" className="flex items-center gap-1.5">
                <Tag className="w-3.5 h-3.5 text-indigo-400" />
                Account Name
              </Label>
              <Input
                id="accountName"
                placeholder="e.g. AWS Production"
                value={accountName}
                onChange={(e) => onAccountNameChange(e.target.value)}
                required
              />
              <p className="text-xs text-slate-400">A friendly name to identify this account in XamOps</p>
            </div>

            {/* Access Level */}
            <div className="space-y-3">
              <Label className="flex items-center gap-1.5">
                <ShieldCheck className="w-3.5 h-3.5 text-indigo-400" />
                Access Level
              </Label>

              {/* Read-only option */}
              <label
                htmlFor="read-only"
                className={cn(
                  'relative flex items-start gap-4 p-4 rounded-xl border-2 cursor-pointer transition-all duration-200 group',
                  accessType === 'read-only'
                    ? 'border-emerald-400 bg-emerald-50/50 shadow-sm'
                    : 'border-slate-200 bg-white hover:border-slate-300 hover:bg-slate-50'
                )}
              >
                {/* Left accent bar */}
                <div className={cn(
                  'absolute left-0 top-3 bottom-3 w-0.5 rounded-full transition-all duration-200',
                  accessType === 'read-only' ? 'bg-emerald-400' : 'bg-transparent'
                )} />

                <input
                  type="radio"
                  id="read-only"
                  name="access-type"
                  value="read-only"
                  checked={accessType === 'read-only'}
                  onChange={() => onAccessTypeChange('read-only')}
                  className="mt-1 accent-emerald-500"
                />
                <div className="flex-1 min-w-0">
                  <div className="flex items-center gap-2 mb-1">
                    <span className="text-sm font-semibold text-slate-800">Read-Only Access</span>
                    <Badge variant="success" className="text-[10px] py-0">Recommended</Badge>
                  </div>
                  <p className="text-xs text-slate-500 leading-relaxed">
                    Grants visibility into costs, usage, and inventory without modification rights
                  </p>
                </div>
                <ShieldCheck className={cn('w-4 h-4 flex-shrink-0 mt-1', accessType === 'read-only' ? 'text-emerald-500' : 'text-slate-300')} />
              </label>

              {/* Read-write option */}
              <label
                htmlFor="read-write"
                className={cn(
                  'relative flex items-start gap-4 p-4 rounded-xl border-2 cursor-pointer transition-all duration-200 group',
                  accessType === 'read-write'
                    ? 'border-amber-400 bg-amber-50/50 shadow-sm'
                    : 'border-slate-200 bg-white hover:border-slate-300 hover:bg-slate-50'
                )}
              >
                <div className={cn(
                  'absolute left-0 top-3 bottom-3 w-0.5 rounded-full transition-all duration-200',
                  accessType === 'read-write' ? 'bg-amber-400' : 'bg-transparent'
                )} />

                <input
                  type="radio"
                  id="read-write"
                  name="access-type"
                  value="read-write"
                  checked={accessType === 'read-write'}
                  onChange={() => onAccessTypeChange('read-write')}
                  className="mt-1 accent-amber-500"
                />
                <div className="flex-1 min-w-0">
                  <div className="flex items-center gap-2 mb-1">
                    <span className="text-sm font-semibold text-slate-800">Read &amp; Write Access</span>
                    <Badge variant="warning" className="text-[10px] py-0">Advanced</Badge>
                  </div>
                  <p className="text-xs text-slate-500 leading-relaxed">
                    Grants full visibility plus the ability to manage resources, configure auto-spotting, create users, and perform operational actions
                  </p>
                </div>
                <ShieldAlert className={cn('w-4 h-4 flex-shrink-0 mt-1', accessType === 'read-write' ? 'text-amber-500' : 'text-slate-300')} />
              </label>
            </div>

            {/* IAM Policies link */}
            <div className="flex items-center justify-between p-4 bg-indigo-50/60 border border-indigo-100 rounded-xl">
              <div className="flex items-center gap-2.5">
                <FileText className="w-4 h-4 text-indigo-500" />
                <span className="text-sm font-medium text-slate-700">IAM Policies</span>
              </div>
              <Button type="button" variant="ghost" size="sm" onClick={onOpenPolicyModal} className="gap-1.5 text-indigo-600 hover:text-indigo-700 hover:bg-indigo-100">
                <span>View Permissions</span>
                <ExternalLink className="w-3 h-3" />
              </Button>
            </div>

            {/* Submit */}
            <div className="flex justify-end pt-2 border-t border-slate-100">
              <Button type="submit" variant="aws" disabled={isLoading || !accountName.trim()} className="gap-2">
                {isLoading ? (
                  <><Loader2 className="w-4 h-4 animate-spin" />Generating...</>
                ) : (
                  <><Zap className="w-4 h-4" />Launch CloudFormation Stack<ExternalLink className="w-3.5 h-3.5" /></>
                )}
              </Button>
            </div>
          </form>
        </CardContent>
      </Card>
    </motion.div>
  );
};

export default AWSForm;
