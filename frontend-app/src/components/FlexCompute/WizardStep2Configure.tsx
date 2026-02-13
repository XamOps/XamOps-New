/**
 * Wizard Step 2 - Configure Capacity
 *
 * Optional configuration: VPC, Security Group, SSH Key, EBS Volume, AMI
 */

import React from 'react';
import type { ConfigureCapacityForm } from '../../types/flexCompute';

interface WizardStep2ConfigureProps {
  form: ConfigureCapacityForm;
  onChange: (updates: Partial<ConfigureCapacityForm>) => void;
  onNext: () => void;
  onBack: () => void;
}

const WizardStep2Configure: React.FC<WizardStep2ConfigureProps> = ({
  form,
  onChange,
  onNext,
  onBack,
}) => {
  return (
    <div className="bg-white rounded-2xl shadow-sm border border-gray-200 overflow-hidden">
      {/* Header */}
      <div className="bg-gradient-to-r from-purple-600 to-violet-600 px-6 py-4 text-white">
        <div className="flex items-center gap-3">
          <div className="w-8 h-8 bg-white/20 rounded-lg flex items-center justify-center text-sm font-bold">2</div>
          <div>
            <h3 className="font-semibold">Configure Capacity</h3>
            <p className="text-xs text-purple-100">Optional infrastructure settings (defaults will be used if left blank)</p>
          </div>
        </div>
      </div>

      {/* Form */}
      <div className="p-6 space-y-5">
        <div className="grid grid-cols-1 md:grid-cols-2 gap-5">
          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1.5">
              <i className="fas fa-network-wired mr-1.5 text-gray-400"></i>
              VPC ID
            </label>
            <input
              type="text"
              value={form.vpcId}
              onChange={(e) => onChange({ vpcId: e.target.value })}
              placeholder="Default VPC"
              className="w-full px-4 py-2.5 border border-gray-300 rounded-lg text-sm focus:ring-2 focus:ring-purple-500 focus:border-purple-500 outline-none"
            />
          </div>
          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1.5">
              <i className="fas fa-shield-alt mr-1.5 text-gray-400"></i>
              Security Group ID
            </label>
            <input
              type="text"
              value={form.securityGroupId}
              onChange={(e) => onChange({ securityGroupId: e.target.value })}
              placeholder="Default Security Group"
              className="w-full px-4 py-2.5 border border-gray-300 rounded-lg text-sm focus:ring-2 focus:ring-purple-500 focus:border-purple-500 outline-none"
            />
          </div>
        </div>

        <div className="grid grid-cols-1 md:grid-cols-2 gap-5">
          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1.5">
              <i className="fas fa-key mr-1.5 text-gray-400"></i>
              SSH Key Name
            </label>
            <input
              type="text"
              value={form.sshKeyName}
              onChange={(e) => onChange({ sshKeyName: e.target.value })}
              placeholder="None"
              className="w-full px-4 py-2.5 border border-gray-300 rounded-lg text-sm focus:ring-2 focus:ring-purple-500 focus:border-purple-500 outline-none"
            />
          </div>
          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1.5">
              <i className="fas fa-hdd mr-1.5 text-gray-400"></i>
              EBS Volume Size (GiB)
            </label>
            <input
              type="number"
              value={form.ebsVolumeSize}
              onChange={(e) => onChange({ ebsVolumeSize: parseInt(e.target.value) || 10 })}
              min={1}
              max={16384}
              className="w-full px-4 py-2.5 border border-gray-300 rounded-lg text-sm focus:ring-2 focus:ring-purple-500 focus:border-purple-500 outline-none"
            />
          </div>
        </div>

        <div>
          <label className="block text-sm font-medium text-gray-700 mb-1.5">
            <i className="fas fa-image mr-1.5 text-gray-400"></i>
            AMI Image ID
          </label>
          <input
            type="text"
            value={form.amiImageId}
            onChange={(e) => onChange({ amiImageId: e.target.value })}
            placeholder="Default AMI"
            className="w-full px-4 py-2.5 border border-gray-300 rounded-lg text-sm focus:ring-2 focus:ring-purple-500 focus:border-purple-500 outline-none"
          />
          <p className="text-xs text-gray-400 mt-1">Leave blank to use the default AMI for your selected platform</p>
        </div>

        {/* Actions */}
        <div className="flex items-center justify-between pt-4 border-t border-gray-200">
          <button
            onClick={onBack}
            className="px-6 py-2.5 text-sm font-medium text-gray-700 bg-gray-100 rounded-lg hover:bg-gray-200 transition-colors"
          >
            <i className="fas fa-arrow-left mr-2"></i>
            Back
          </button>
          <button
            onClick={onNext}
            className="px-8 py-2.5 bg-gradient-to-r from-purple-600 to-violet-600 text-white text-sm font-semibold rounded-lg hover:from-purple-700 hover:to-violet-700 transition-all shadow-lg shadow-purple-500/25"
          >
            Next
            <i className="fas fa-arrow-right ml-2"></i>
          </button>
        </div>
      </div>
    </div>
  );
};

export default WizardStep2Configure;
