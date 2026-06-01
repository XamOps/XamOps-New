import React from 'react';
import { Icon } from '@iconify/react';
import type { EC2Resource, CloudSitterPolicy } from '../../types/cloudsitter';
import { DialogRoot, DialogPanel } from '../ui';

interface AssignPolicyModalProps {
  isOpen: boolean;
  assignTarget: EC2Resource | null;
  policies: CloudSitterPolicy[];
  selectedPolicyId: string;
  onClose: () => void;
  onPolicySelect: (policyId: string) => void;
  onCreateNew: () => void;
  onSubmit: () => void;
}

const AssignPolicyModal: React.FC<AssignPolicyModalProps> = ({
  isOpen,
  assignTarget,
  policies,
  selectedPolicyId,
  onClose,
  onPolicySelect,
  onCreateNew,
  onSubmit,
}) => {
  return (
    <DialogRoot isOpen={isOpen} onClose={onClose} maxWidth="max-w-sm">
      <DialogPanel
        title="Assign Policy"
        onClose={onClose}
        footer={
          <button
            onClick={onSubmit}
            className="flex-1 flex items-center justify-center gap-2 bg-indigo-600 hover:bg-indigo-700 text-white font-bold py-3 rounded-xl transition-colors text-[13px]"
          >
            <Icon icon="mdi:check" width={14} />
            Confirm Assignment
          </button>
        }
      >
        <div className="bg-[rgba(0,0,0,0.02)] p-4 rounded-lg mb-5 border border-[rgba(0,0,0,0.06)]">
          <p className="text-[11px] font-bold text-[rgba(0,0,0,0.4)] uppercase tracking-wide mb-1">Target Resource</p>
          <div className="font-semibold text-[rgba(0,0,0,0.88)] flex items-center gap-2 text-[13px]">
            <Icon icon="mdi:server" width={14} color="#6366F1" />
            <span>{assignTarget?.name || assignTarget?.id}</span>
          </div>
          <div className="text-[11px] text-[rgba(0,0,0,0.4)] mt-1 font-mono">{assignTarget?.id}</div>
        </div>

        <div className="mb-4">
          <label className="block text-[12px] font-semibold text-[rgba(0,0,0,0.65)] uppercase tracking-wide mb-2">Select Schedule</label>
          <div className="relative">
            <select
              value={selectedPolicyId}
              onChange={(e) => onPolicySelect(e.target.value)}
              className="w-full appearance-none border border-[rgba(0,0,0,0.12)] rounded-lg py-2.5 px-4 focus:ring-2 focus:ring-indigo-300 focus:border-indigo-400 bg-white text-[13px] text-[rgba(0,0,0,0.88)] font-medium outline-none"
            >
              <option value="" disabled>— Choose a Policy —</option>
              {policies.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
            </select>
            <Icon icon="mdi:chevron-down" width={14} className="pointer-events-none absolute right-3 top-1/2 -translate-y-1/2 text-[rgba(0,0,0,0.4)]" />
          </div>
          <div className="mt-2 text-right">
            <button onClick={onCreateNew} className="text-[12px] text-indigo-600 font-semibold hover:underline">
              + Create new policy
            </button>
          </div>
        </div>
      </DialogPanel>
    </DialogRoot>
  );
};

export default AssignPolicyModal;
