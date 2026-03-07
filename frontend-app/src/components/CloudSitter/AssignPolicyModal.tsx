/**
 * Assign Policy Modal Component
 *
 * Modal for assigning a policy to an EC2 instance
 */

import React from 'react';
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
          <button onClick={onSubmit} className="flex-1 bg-indigo-600 hover:bg-indigo-700 text-white font-bold py-3 rounded-xl shadow-lg shadow-indigo-200 transition-all">
            Confirm Assignment
          </button>
        }
      >
        <div className="bg-gray-50 p-4 rounded-lg mb-6 border border-gray-100">
          <p className="text-xs font-bold text-gray-500 uppercase tracking-wide mb-1">Target Resource</p>
          <div className="font-semibold text-gray-800 flex items-center gap-2">
            <i className="fas fa-cube text-indigo-500"></i>
            <span>{assignTarget?.name || assignTarget?.id}</span>
          </div>
          <div className="text-xs text-gray-500 mt-1 font-mono">{assignTarget?.id}</div>
        </div>

        <div className="mb-4">
          <label className="block text-sm font-bold text-gray-700 mb-2">Select Schedule</label>
          <div className="relative">
            <select value={selectedPolicyId} onChange={(e) => onPolicySelect(e.target.value)} className="w-full appearance-none border border-gray-300 rounded-lg py-3 px-4 focus:ring-2 focus:ring-indigo-500 focus:border-indigo-500 bg-white text-gray-700 font-medium">
              <option value="" disabled>-- Choose a Policy --</option>
              {policies.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
            </select>
            <div className="pointer-events-none absolute inset-y-0 right-0 flex items-center px-4 text-gray-500">
              <i className="fas fa-chevron-down text-xs"></i>
            </div>
          </div>
          <div className="mt-2 text-right">
            <button onClick={onCreateNew} className="text-xs text-indigo-600 font-semibold hover:underline">+ Create new policy</button>
          </div>
        </div>
      </DialogPanel>
    </DialogRoot>
  );
};

export default AssignPolicyModal;
