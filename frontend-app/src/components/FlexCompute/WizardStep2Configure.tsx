/**
 * Wizard Step 2 - Configure Capacity
 *
 * Optional configuration: VPC, Security Group, SSH Key, EBS Volume, AMI
 */

import React, { useState } from 'react';
import type { ConfigureCapacityForm } from '../../types/flexCompute';

interface WizardStep2ConfigureProps {
  form: ConfigureCapacityForm;
  onChange: (updates: Partial<ConfigureCapacityForm>) => void;
  onNext: () => void;
  onBack: () => void;
}

const tooltips: Record<string, string> = {
  vpcId: `1. Open the AWS Management Console and navigate to EC2.\n2. In the left navigation pane, select VPC (or Your VPCs).\n3. Locate the VPC you want to use and copy the VPC ID (e.g., vpc-12345abc).`,
  securityGroupId: `1. Open the AWS Management Console and navigate to EC2.\n2. In the left navigation pane, select Security Groups.\n3. Choose the relevant security group and copy the Security Group ID (e.g., sg-012345abc).`,
  sshKeyName: `1. Open the AWS Management Console and navigate to EC2.\n2. In the left navigation pane, select Key Pairs.\n3. Copy the Key Pair Name (not the file name), e.g., my-ec2-keypair.`,
  ebsVolumeSize: `1. Specify the size of the EBS volume in gigabytes (GB).\nDefault AMI Image - /aws/service/ami-amazon-linux-latest/amzn2-ami-hvm-x86_64-gp2`,
  amiImageId: `1. Open the AWS Management Console and navigate to EC2.\n2. In the left navigation pane, select AMIs (under Images).\n3. Copy the AMI ID (e.g., ami-123456abc).`,
};

const TooltipIcon: React.FC<{ field: string }> = ({ field }) => {
  const [visible, setVisible] = useState(false);
  const text = tooltips[field];
  if (!text) return null;

  return (
    <span className="relative inline-block ml-1.5 align-middle">
      <button
        type="button"
        onMouseEnter={() => setVisible(true)}
        onMouseLeave={() => setVisible(false)}
        onFocus={() => setVisible(true)}
        onBlur={() => setVisible(false)}
        className="w-4 h-4 rounded-full border border-blue-400 text-blue-500 text-xs font-bold flex items-center justify-center hover:bg-blue-50 transition-colors focus:outline-none"
        aria-label="Help"
      >
        ?
      </button>
      {visible && (
        <div className="absolute z-50 left-6 top-0 w-72 bg-gray-900 text-white text-xs rounded-lg p-3 shadow-xl whitespace-pre-line leading-relaxed">
          {text}
          {/* Arrow */}
          <span className="absolute -left-1.5 top-2 w-3 h-3 bg-gray-900 rotate-45"></span>
        </div>
      )}
    </span>
  );
};

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
              <TooltipIcon field="vpcId" />
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
              <TooltipIcon field="securityGroupId" />
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
              <TooltipIcon field="sshKeyName" />
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
              <TooltipIcon field="ebsVolumeSize" />
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
            <TooltipIcon field="amiImageId" />
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
