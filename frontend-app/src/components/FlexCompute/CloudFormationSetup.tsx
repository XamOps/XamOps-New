/**
 * CloudFormation Setup Component
 *
 * Guide users through deploying the CloudiDR IAM role via CloudFormation
 */

import React, { useState } from 'react';
import { AWS_REGIONS } from '../../types/flexCompute';

interface CloudFormationSetupProps {
  isOpen: boolean;
  onClose: () => void;
}

const CloudFormationSetup: React.FC<CloudFormationSetupProps> = ({ isOpen, onClose }) => {
  const [selectedRegion, setSelectedRegion] = useState('us-east-1');
  const [isGenerating, setIsGenerating] = useState(false);
  const [deployUrl, setDeployUrl] = useState<string | null>(null);

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
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 backdrop-blur-sm">
      <div className="bg-white rounded-2xl shadow-2xl max-w-lg w-full mx-4 overflow-hidden">
        {/* Header */}
        <div className="bg-gradient-to-r from-orange-500 to-amber-500 px-6 py-5 text-white">
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-3">
              <i className="fab fa-aws text-2xl"></i>
              <div>
                <h3 className="text-lg font-bold">Deploy CloudFormation Stack</h3>
                <p className="text-sm text-orange-100">Create an IAM role for CloudiDR access</p>
              </div>
            </div>
            <button onClick={onClose} className="text-white/80 hover:text-white transition-colors">
              <i className="fas fa-times text-lg"></i>
            </button>
          </div>
        </div>

        {/* Content */}
        <div className="p-6 space-y-5">
          {/* Steps */}
          <div className="space-y-4">
            <div className="flex items-start gap-3">
              <div className="w-7 h-7 bg-blue-100 text-blue-600 rounded-full flex items-center justify-center flex-shrink-0 text-sm font-bold mt-0.5">1</div>
              <div>
                <p className="text-sm font-medium text-gray-900">Select your AWS region</p>
                <p className="text-xs text-gray-500">Choose the region where you want to deploy the stack</p>
              </div>
            </div>

            <select
              value={selectedRegion}
              onChange={(e) => {
                setSelectedRegion(e.target.value);
                setDeployUrl(null);
              }}
              className="w-full px-4 py-2.5 border border-gray-300 rounded-lg text-sm focus:ring-2 focus:ring-blue-500 focus:border-blue-500 outline-none"
            >
              {AWS_REGIONS.map((region) => (
                <option key={region.value} value={region.value}>
                  {region.label} ({region.value})
                </option>
              ))}
            </select>

            <div className="flex items-start gap-3">
              <div className="w-7 h-7 bg-blue-100 text-blue-600 rounded-full flex items-center justify-center flex-shrink-0 text-sm font-bold mt-0.5">2</div>
              <div>
                <p className="text-sm font-medium text-gray-900">Generate deployment link</p>
                <p className="text-xs text-gray-500">This creates a Quick Create URL for the CloudFormation stack</p>
              </div>
            </div>

            <button
              onClick={handleGenerateUrl}
              disabled={isGenerating}
              className="w-full py-2.5 px-4 bg-blue-600 text-white text-sm font-medium rounded-lg hover:bg-blue-700 transition-colors disabled:opacity-50"
            >
              {isGenerating ? (
                <><i className="fas fa-spinner fa-spin mr-2"></i>Generating...</>
              ) : (
                <><i className="fas fa-link mr-2"></i>Generate Deploy URL</>
              )}
            </button>

            {deployUrl && (
              <>
                <div className="flex items-start gap-3">
                  <div className="w-7 h-7 bg-green-100 text-green-600 rounded-full flex items-center justify-center flex-shrink-0 text-sm font-bold mt-0.5">3</div>
                  <div>
                    <p className="text-sm font-medium text-gray-900">Deploy in AWS Console</p>
                    <p className="text-xs text-gray-500">Click below to open the AWS CloudFormation console</p>
                  </div>
                </div>

                <a
                  href={deployUrl}
                  target="_blank"
                  rel="noopener noreferrer"
                  className="block w-full py-3 px-4 bg-gradient-to-r from-orange-500 to-amber-500 text-white text-sm font-semibold rounded-lg hover:from-orange-600 hover:to-amber-600 transition-all text-center shadow-lg"
                >
                  <i className="fab fa-aws mr-2"></i>
                  Open AWS Console & Deploy
                  <i className="fas fa-external-link-alt ml-2 text-xs"></i>
                </a>
              </>
            )}
          </div>

          {/* Info */}
          <div className="bg-blue-50 border border-blue-200 rounded-xl p-4 text-xs text-blue-700">
            <i className="fas fa-info-circle mr-1.5"></i>
            This stack creates a read-only IAM role that allows CloudiDR to manage EC2 capacity on your behalf.
            You can delete the stack at any time from the AWS CloudFormation console.
          </div>
        </div>
      </div>
    </div>
  );
};

export default CloudFormationSetup;
