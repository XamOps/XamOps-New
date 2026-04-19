/**
 * Manage Policies Modal Component
 *
 * Modal for viewing, editing, and deleting policies
 */

import React from 'react';
import type { CloudSitterPolicy } from '../../types/cloudsitter';
import { DialogRoot, DialogPanel, TooltipWrapper } from '../ui';

interface ManagePoliciesModalProps {
  isOpen: boolean;
  policies: CloudSitterPolicy[];
  onClose: () => void;
  onEdit: (policy: CloudSitterPolicy) => void;
  onDelete: (id: string) => void;
}

const ManagePoliciesModal: React.FC<ManagePoliciesModalProps> = ({
  isOpen,
  policies,
  onClose,
  onEdit,
  onDelete,
}) => {
  return (
    <DialogRoot isOpen={isOpen} onClose={onClose} maxWidth="max-w-xl">
      <DialogPanel
        title="Manage Policies"
        onClose={onClose}
        footer={
          <button onClick={onClose} className="px-4 py-2 bg-gray-100 hover:bg-gray-200 text-gray-700 rounded-lg font-medium transition-colors">Close</button>
        }
      >
        <div className="overflow-y-auto max-h-[50vh]">
          {policies.length === 0 ? (
            <div className="text-center py-8 text-gray-500">No policies created yet.</div>
          ) : (
            <div className="space-y-3">
              {policies.map((p) => (
                <div key={p.id} className="flex justify-between items-center p-4 border rounded-lg hover:bg-gray-50 transition-colors group">
                  <div>
                    <div className="font-bold text-gray-800">{p.name}</div>
                    <div className="text-xs text-gray-500 flex gap-2">
                      <span>{p.timeZone}</span>
                      {p.notificationsEnabled && <span className="text-green-600">• Notifications On</span>}
                    </div>
                  </div>
                  <div className="flex gap-2">
                    <TooltipWrapper tip="Edit Policy">
                      <button
                        onClick={() => onEdit(p)}
                        className="text-gray-400 hover:text-indigo-600 p-2 rounded-full hover:bg-indigo-50 transition-colors focus-visible:ring-2 focus-visible:ring-indigo-500 outline-none"
                        aria-label="Edit policy"
                      >
                        <i className="fas fa-pencil-alt"></i>
                      </button>
                    </TooltipWrapper>
                    <TooltipWrapper tip="Delete Policy">
                      <button
                        onClick={() => onDelete(p.id)}
                        className="text-gray-400 hover:text-red-600 p-2 rounded-full hover:bg-red-50 transition-colors focus-visible:ring-2 focus-visible:ring-red-500 outline-none"
                        aria-label="Delete policy"
                      >
                        <i className="fas fa-trash"></i>
                      </button>
                    </TooltipWrapper>
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>
      </DialogPanel>
    </DialogRoot>
  );
};

export default ManagePoliciesModal;
