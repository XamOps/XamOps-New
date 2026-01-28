/**
 * Assign Policy Modal Component
 *
 * Modal for assigning a policy to an EC2 instance
 */

import React from 'react';
import type { EC2Resource, CloudSitterPolicy } from '../../types/cloudsitter';

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
  if (!isOpen) return null;

  return (
    <div className="fixed inset-0 bg-gray-900/60 backdrop-blur-sm z-50 flex items-center justify-center transition-opacity">
      <div
        className="bg-white rounded-2xl shadow-2xl p-8 w-[450px] transform transition-all scale-100"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex justify-between items-center mb-6">
          <h3 className="text-xl font-bold text-gray-800">Assign Policy</h3>
          <button
            onClick={onClose}
            className="text-gray-400 hover:text-gray-600 transition-colors"
          >
            <i className="fas fa-times text-lg"></i>
          </button>
        </div>

        <div className="bg-gray-50 p-4 rounded-lg mb-6 border border-gray-100">
          <p className="text-xs font-bold text-gray-500 uppercase tracking-wide mb-1">
            Target Resource
          </p>
          <div className="font-semibold text-gray-800 flex items-center gap-2">
            <i className="fas fa-cube text-indigo-500"></i>
            <span>{assignTarget?.name || assignTarget?.id}</span>
          </div>
          <div className="text-xs text-gray-500 mt-1 font-mono">{assignTarget?.id}</div>
        </div>

        <div className="mb-8">
          <label className="block text-sm font-bold text-gray-700 mb-2">Select Schedule</label>
          <div className="relative">
            <select
              value={selectedPolicyId}
              onChange={(e) => onPolicySelect(e.target.value)}
              className="w-full appearance-none border border-gray-300 rounded-lg py-3 px-4 focus:ring-2 focus:ring-indigo-500 focus:border-indigo-500 bg-white text-gray-700 font-medium"
            >
              <option value="" disabled>
                -- Choose a Policy --
              </option>
              {policies.map((p) => (
                <option key={p.id} value={p.id}>
                  {p.name}
                </option>
              ))}
            </select>
            <div className="pointer-events-none absolute inset-y-0 right-0 flex items-center px-4 text-gray-500">
              <i className="fas fa-chevron-down text-xs"></i>
            </div>
          </div>
          <div className="mt-2 text-right">
            <button
              onClick={onCreateNew}
              className="text-xs text-indigo-600 font-semibold hover:underline"
            >
              + Create new policy
            </button>
          </div>
        </div>

        <div className="flex gap-3">
          <button
            onClick={onSubmit}
            className="flex-1 bg-indigo-600 hover:bg-indigo-700 text-white font-bold py-3 rounded-xl shadow-lg shadow-indigo-200 transition-all transform hover:-translate-y-0.5"
          >
            Confirm Assignment
          </button>
        </div>
      </div>
    </div>
  );
};

export default AssignPolicyModal;
