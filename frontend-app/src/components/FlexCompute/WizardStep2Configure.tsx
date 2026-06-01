import React, { useState } from 'react';
import { Icon } from '@iconify/react';
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
        className="w-4 h-4 rounded-full border border-indigo-300 text-indigo-500 text-[10px] font-bold flex items-center justify-center hover:bg-indigo-50 transition-colors focus:outline-none"
        aria-label="Help"
      >
        ?
      </button>
      {visible && (
        <div className="absolute z-50 left-6 top-0 w-72 bg-[rgba(0,0,0,0.88)] text-white text-[11px] rounded-lg p-3 shadow-xl whitespace-pre-line leading-relaxed">
          {text}
          <span className="absolute -left-1.5 top-2 w-3 h-3 bg-[rgba(0,0,0,0.88)] rotate-45" />
        </div>
      )}
    </span>
  );
};

const inputCls = 'w-full px-3 py-2.5 border border-[rgba(0,0,0,0.15)] rounded-lg text-[13px] text-[rgba(0,0,0,0.88)] focus:ring-2 focus:ring-indigo-400 focus:border-indigo-400 outline-none bg-white';
const labelCls = 'block text-[12px] font-semibold text-[rgba(0,0,0,0.65)] mb-1.5';

const FIELD_ICONS: Record<string, string> = {
  vpcId:           'mdi:lan-connect',
  securityGroupId: 'mdi:shield-outline',
  sshKeyName:      'mdi:key-outline',
  ebsVolumeSize:   'mdi:harddisk',
  amiImageId:      'mdi:image-outline',
};

const WizardStep2Configure: React.FC<WizardStep2ConfigureProps> = ({
  form,
  onChange,
  onNext,
  onBack,
}) => {
  return (
    <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-2xl shadow-sm overflow-hidden">
      {/* Header */}
      <div className="px-6 py-4 border-b border-[rgba(0,0,0,0.06)] bg-[rgba(0,0,0,0.01)]">
        <div className="flex items-center gap-3">
          <div className="w-7 h-7 bg-indigo-600 rounded-lg flex items-center justify-center text-[12px] font-bold text-white">2</div>
          <div>
            <h3 className="text-[14px] font-semibold text-[rgba(0,0,0,0.88)]">Configure Capacity</h3>
            <p className="text-[12px] text-[rgba(0,0,0,0.45)]">Optional infrastructure settings (defaults will be used if left blank)</p>
          </div>
        </div>
      </div>

      {/* Form */}
      <div className="p-6 space-y-5">
        <div className="grid grid-cols-1 md:grid-cols-2 gap-5">
          <div>
            <label className={labelCls}>
              <Icon icon={FIELD_ICONS.vpcId} width={12} className="inline mr-1.5 text-[rgba(0,0,0,0.35)]" />
              VPC ID
              <TooltipIcon field="vpcId" />
            </label>
            <input
              type="text"
              value={form.vpcId}
              onChange={(e) => onChange({ vpcId: e.target.value })}
              placeholder="Default VPC"
              className={inputCls}
            />
          </div>
          <div>
            <label className={labelCls}>
              <Icon icon={FIELD_ICONS.securityGroupId} width={12} className="inline mr-1.5 text-[rgba(0,0,0,0.35)]" />
              Security Group ID
              <TooltipIcon field="securityGroupId" />
            </label>
            <input
              type="text"
              value={form.securityGroupId}
              onChange={(e) => onChange({ securityGroupId: e.target.value })}
              placeholder="Default Security Group"
              className={inputCls}
            />
          </div>
        </div>

        <div className="grid grid-cols-1 md:grid-cols-2 gap-5">
          <div>
            <label className={labelCls}>
              <Icon icon={FIELD_ICONS.sshKeyName} width={12} className="inline mr-1.5 text-[rgba(0,0,0,0.35)]" />
              SSH Key Name
              <TooltipIcon field="sshKeyName" />
            </label>
            <input
              type="text"
              value={form.sshKeyName}
              onChange={(e) => onChange({ sshKeyName: e.target.value })}
              placeholder="None"
              className={inputCls}
            />
          </div>
          <div>
            <label className={labelCls}>
              <Icon icon={FIELD_ICONS.ebsVolumeSize} width={12} className="inline mr-1.5 text-[rgba(0,0,0,0.35)]" />
              EBS Volume Size (GiB)
              <TooltipIcon field="ebsVolumeSize" />
            </label>
            <input
              type="number"
              value={form.ebsVolumeSize}
              onChange={(e) => onChange({ ebsVolumeSize: parseInt(e.target.value) || 10 })}
              min={1}
              max={16384}
              className={inputCls}
            />
          </div>
        </div>

        <div>
          <label className={labelCls}>
            <Icon icon={FIELD_ICONS.amiImageId} width={12} className="inline mr-1.5 text-[rgba(0,0,0,0.35)]" />
            AMI Image ID
            <TooltipIcon field="amiImageId" />
          </label>
          <input
            type="text"
            value={form.amiImageId}
            onChange={(e) => onChange({ amiImageId: e.target.value })}
            placeholder="Default AMI"
            className={inputCls}
          />
          <p className="text-[11px] text-[rgba(0,0,0,0.35)] mt-1">Leave blank to use the default AMI for your selected platform</p>
        </div>

        {/* Actions */}
        <div className="flex items-center justify-between pt-4 border-t border-[rgba(0,0,0,0.06)]">
          <button
            onClick={onBack}
            className="flex items-center gap-2 px-5 py-2.5 text-[13px] font-semibold text-[rgba(0,0,0,0.65)] bg-white border border-[rgba(0,0,0,0.12)] rounded-xl hover:bg-[rgba(0,0,0,0.02)] transition-colors"
          >
            <Icon icon="mdi:arrow-left" width={14} />
            Back
          </button>
          <button
            onClick={onNext}
            className="flex items-center gap-2 px-7 py-2.5 bg-indigo-600 hover:bg-indigo-700 text-white text-[13px] font-semibold rounded-xl transition-colors"
          >
            Next
            <Icon icon="mdi:arrow-right" width={14} />
          </button>
        </div>
      </div>
    </div>
  );
};

export default WizardStep2Configure;
