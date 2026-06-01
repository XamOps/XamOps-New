import React, { useState } from 'react';
import { Icon } from '@iconify/react';
import { AWS_REGIONS } from '../../types/flexCompute';

interface CloudFormationSetupProps {
  isOpen: boolean;
  onClose: () => void;
}

const CloudFormationSetup: React.FC<CloudFormationSetupProps> = ({ isOpen, onClose }) => {
  const [selectedRegion, setSelectedRegion] = useState('us-east-1');
  const [isGenerating, setIsGenerating]     = useState(false);
  const [deployUrl, setDeployUrl]           = useState<string | null>(null);

  const handleGenerateUrl = async () => {
    setIsGenerating(true);
    try {
      const response = await fetch(
        `/api/flexcompute/cloudformation/deploy-url?region=${selectedRegion}`,
        { credentials: 'include' }
      );
      if (response.ok) {
        const data = await response.json();
        setDeployUrl(data.deployUrl);
      }
    } catch (err) {
      console.error('[FlexCompute] Generate deploy URL failed:', err);
    } finally {
      setIsGenerating(false);
    }
  };

  if (!isOpen) return null;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 backdrop-blur-sm">
      <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-2xl shadow-sm max-w-lg w-full mx-4 overflow-hidden">
        {/* Header */}
        <div className="px-6 py-4 border-b border-[rgba(0,0,0,0.06)] bg-[rgba(0,0,0,0.01)] flex items-center justify-between">
          <div className="flex items-center gap-3">
            <div className="w-8 h-8 bg-[#FF9900]/10 rounded-lg flex items-center justify-center">
              <Icon icon="mdi:aws" width={18} color="#FF9900" />
            </div>
            <div>
              <h3 className="text-[14px] font-semibold text-[rgba(0,0,0,0.88)]">Deploy CloudFormation Stack</h3>
              <p className="text-[12px] text-[rgba(0,0,0,0.45)]">Create an IAM role for CloudiDR access</p>
            </div>
          </div>
          <button
            onClick={onClose}
            className="w-7 h-7 rounded-lg flex items-center justify-center text-[rgba(0,0,0,0.4)] hover:text-[rgba(0,0,0,0.88)] hover:bg-[rgba(0,0,0,0.04)] transition-colors"
          >
            <Icon icon="mdi:close" width={16} />
          </button>
        </div>

        {/* Content */}
        <div className="p-6 space-y-5">
          {/* Step 1 */}
          <div className="flex items-start gap-3">
            <div className="w-7 h-7 bg-indigo-50 text-indigo-600 rounded-full flex items-center justify-center flex-shrink-0 text-[12px] font-bold mt-0.5">1</div>
            <div>
              <p className="text-[13px] font-medium text-[rgba(0,0,0,0.88)]">Select your AWS region</p>
              <p className="text-[12px] text-[rgba(0,0,0,0.45)]">Choose the region where you want to deploy the stack</p>
            </div>
          </div>

          <select
            value={selectedRegion}
            onChange={(e) => { setSelectedRegion(e.target.value); setDeployUrl(null); }}
            className="w-full px-3 py-2.5 border border-[rgba(0,0,0,0.15)] rounded-lg text-[13px] text-[rgba(0,0,0,0.88)] focus:ring-2 focus:ring-indigo-400 focus:border-indigo-400 outline-none bg-white"
          >
            {AWS_REGIONS.map((region) => (
              <option key={region.value} value={region.value}>
                {region.label} ({region.value})
              </option>
            ))}
          </select>

          {/* Step 2 */}
          <div className="flex items-start gap-3">
            <div className="w-7 h-7 bg-indigo-50 text-indigo-600 rounded-full flex items-center justify-center flex-shrink-0 text-[12px] font-bold mt-0.5">2</div>
            <div>
              <p className="text-[13px] font-medium text-[rgba(0,0,0,0.88)]">Generate deployment link</p>
              <p className="text-[12px] text-[rgba(0,0,0,0.45)]">This creates a Quick Create URL for the CloudFormation stack</p>
            </div>
          </div>

          <button
            onClick={handleGenerateUrl}
            disabled={isGenerating}
            className="w-full flex items-center justify-center gap-2 py-2.5 px-4 bg-indigo-600 hover:bg-indigo-700 text-white text-[13px] font-semibold rounded-xl transition-colors disabled:opacity-50"
          >
            {isGenerating
              ? <><Icon icon="mdi:loading" width={14} className="animate-spin" />Generating...</>
              : <><Icon icon="mdi:link-variant" width={14} />Generate Deploy URL</>
            }
          </button>

          {deployUrl && (
            <>
              {/* Step 3 */}
              <div className="flex items-start gap-3">
                <div className="w-7 h-7 bg-emerald-50 text-emerald-600 rounded-full flex items-center justify-center flex-shrink-0 text-[12px] font-bold mt-0.5">3</div>
                <div>
                  <p className="text-[13px] font-medium text-[rgba(0,0,0,0.88)]">Deploy in AWS Console</p>
                  <p className="text-[12px] text-[rgba(0,0,0,0.45)]">Click below to open the AWS CloudFormation console</p>
                </div>
              </div>

              <a
                href={deployUrl}
                target="_blank"
                rel="noopener noreferrer"
                className="flex items-center justify-center gap-2 w-full py-3 px-4 bg-[#FF9900] hover:bg-[#e8890a] text-white text-[13px] font-semibold rounded-xl transition-colors"
              >
                <Icon icon="mdi:aws" width={16} />
                Open AWS Console & Deploy
                <Icon icon="mdi:open-in-new" width={13} />
              </a>
            </>
          )}

          {/* Info */}
          <div className="bg-indigo-50 border border-indigo-100 rounded-xl p-4 text-[12px] text-indigo-700 flex items-start gap-2">
            <Icon icon="mdi:information-outline" width={14} className="mt-0.5 flex-shrink-0" />
            This stack creates a read-only IAM role that allows XamOps to manage EC2 capacity on your behalf.
            You can delete the stack at any time from the AWS CloudFormation console.
          </div>
        </div>
      </div>
    </div>
  );
};

export default CloudFormationSetup;
