import React, { useState } from 'react';
import { Icon } from '@iconify/react';
import { DialogRoot, DialogPanel } from '../ui';

interface DeploymentModalProps {
  isOpen: boolean;
  onClose: () => void;
  onGenerateUrl: () => Promise<string>;
}

const DeploymentModal: React.FC<DeploymentModalProps> = ({ isOpen, onClose, onGenerateUrl }) => {
  const [generatedUrl, setGeneratedUrl] = useState('');
  const [isGenerating, setIsGenerating] = useState(false);
  const [error, setError]               = useState('');

  const handleGenerateUrl = async () => {
    setIsGenerating(true);
    setError('');
    setGeneratedUrl('');
    try {
      const url = await onGenerateUrl();
      setGeneratedUrl(url);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to generate URL');
    } finally {
      setIsGenerating(false);
    }
  };

  const handleClose = () => { setGeneratedUrl(''); setError(''); onClose(); };

  return (
    <DialogRoot isOpen={isOpen} onClose={handleClose} maxWidth="max-w-2xl">
      <DialogPanel
        title={
          <div className="flex items-center gap-2">
            <Icon icon="mdi:aws" width={18} color="#FF9900" />
            Deploy AutoSpotting to AWS
          </div>
        }
        onClose={handleClose}
        footer={
          <>
            <button
              onClick={handleClose}
              className="px-5 py-2.5 text-[13px] font-semibold text-[rgba(0,0,0,0.7)] bg-[rgba(0,0,0,0.04)] border border-[rgba(0,0,0,0.08)] rounded-md hover:bg-[rgba(0,0,0,0.06)] transition-colors"
            >
              Cancel
            </button>
            <button
              onClick={handleGenerateUrl}
              disabled={isGenerating}
              className="flex items-center gap-2 px-5 py-2.5 text-[13px] font-semibold text-white bg-[rgba(0,0,0,0.88)] hover:bg-black rounded-md transition-colors disabled:opacity-50"
            >
              <Icon icon={isGenerating ? 'mdi:loading' : 'mdi:cog-outline'} width={14} className={isGenerating ? 'animate-spin' : ''} />
              {isGenerating ? 'Generating...' : 'Generate URL'}
            </button>
            <button
              onClick={() => generatedUrl && window.open(generatedUrl, '_blank', 'noopener,noreferrer')}
              disabled={!generatedUrl}
              className="flex items-center gap-2 px-5 py-2.5 text-[13px] font-bold text-white bg-[#FF9900] hover:bg-[#e8890a] rounded-xl transition-colors disabled:opacity-50"
            >
              <Icon icon="mdi:aws" width={14} />
              Launch Stack
              <Icon icon="mdi:open-in-new" width={12} />
            </button>
          </>
        }
      >
        <div className="space-y-4">
          {/* Info */}
          <div className="p-4 bg-indigo-50 border border-indigo-100 rounded-xl flex items-start gap-3">
            <Icon icon="mdi:information-outline" width={16} color="#4F46E5" className="mt-0.5 flex-shrink-0" />
            <div className="text-[13px] text-[rgba(0,0,0,0.65)]">
              <p className="font-bold text-[rgba(0,0,0,0.88)] mb-2">What this deployment does:</p>
              <ul className="space-y-1 list-disc list-inside">
                <li>Creates an IAM role for AutoSpotting to manage your resources</li>
                <li>Sets up EventBridge rules to forward AutoScaling events to XamOps</li>
                <li>Automatically deploys to <strong>all AWS regions</strong> using StackSets</li>
                <li>Enables automated Spot instance management across your account</li>
              </ul>
            </div>
          </div>

          {/* Config */}
          <div className="p-4 bg-[rgba(0,0,0,0.02)] border border-[rgba(0,0,0,0.06)] rounded-xl">
            <h4 className="text-[13px] font-bold text-[rgba(0,0,0,0.88)] mb-3 flex items-center gap-2">
              <Icon icon="mdi:cog-outline" width={14} color="rgba(0,0,0,0.45)" />
              Deployment Configuration
            </h4>
            <div className="space-y-2 text-[13px] text-[rgba(0,0,0,0.65)]">
              {[
                { icon: 'mdi:earth', label: 'Multi-Region Deployment:', value: 'Enabled (All Regions)', valueColor: 'text-emerald-600 font-bold' },
                { icon: 'mdi:layers-outline', label: 'Stack Name:', value: 'XamOps-AutoSpotting-Integration', mono: true },
                { icon: 'mdi:office-building-outline', label: 'Main Account ID:', value: '982534352845', mono: true },
              ].map((row) => (
                <div key={row.label} className="flex items-center justify-between">
                  <span className="flex items-center gap-2">
                    <Icon icon={row.icon} width={13} color="rgba(0,0,0,0.35)" />
                    {row.label}
                  </span>
                  <span className={row.mono ? 'font-mono text-[11px] bg-[rgba(0,0,0,0.05)] px-2 py-0.5 rounded' : row.valueColor}>{row.value}</span>
                </div>
              ))}
            </div>
          </div>

          {/* Warning */}
          <div className="p-4 bg-amber-50 border border-amber-200 rounded-xl flex items-start gap-3">
            <Icon icon="mdi:alert-outline" width={16} color="#D97706" className="mt-0.5 flex-shrink-0" />
            <div className="text-[13px] text-[rgba(0,0,0,0.65)]">
              <p className="font-bold text-amber-800 mb-2">Before you proceed:</p>
              <ul className="space-y-1 list-disc list-inside">
                <li>Ensure you have <strong>AdministratorAccess</strong> in your AWS account</li>
                <li>The stack will create IAM roles and EventBridge rules</li>
                <li>You can customize regions later in the CloudFormation console if needed</li>
              </ul>
            </div>
          </div>

          {/* Generated URL */}
          {generatedUrl && (
            <div className="p-4 bg-emerald-50 border border-emerald-200 rounded-xl">
              <label className="block text-[13px] font-bold text-[rgba(0,0,0,0.88)] mb-2 flex items-center gap-2">
                <Icon icon="mdi:link-variant" width={14} color="#16A34A" />
                CloudFormation URL Generated
              </label>
              <div className="flex items-center gap-2">
                <input
                  type="text"
                  value={generatedUrl}
                  readOnly
                  className="flex-1 px-3 py-2 text-[11px] bg-white border border-emerald-200 rounded-lg font-mono text-[rgba(0,0,0,0.65)]"
                />
                <button
                  onClick={() => navigator.clipboard.writeText(generatedUrl)}
                  className="flex items-center gap-1.5 px-3 py-2 bg-emerald-600 hover:bg-emerald-700 text-white text-[12px] font-semibold rounded-lg transition-colors"
                >
                  <Icon icon="mdi:content-copy" width={13} />
                  Copy
                </button>
              </div>
            </div>
          )}

          {/* Error */}
          {error && (
            <div className="p-4 bg-red-50 border border-red-200 rounded-xl flex items-start gap-3">
              <Icon icon="mdi:alert-circle-outline" width={15} color="#DC2626" className="mt-0.5 flex-shrink-0" />
              <p className="text-[13px] text-red-700 font-medium">{error}</p>
            </div>
          )}
        </div>
      </DialogPanel>
    </DialogRoot>
  );
};

export default DeploymentModal;
