/**
 * Manage Policies Modal Component
 *
 * Modal for viewing, editing, and deleting policies
 */

import React from 'react';
import type { CloudSitterPolicy } from '../../types/cloudsitter';

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
  if (!isOpen) return null;

  return (
    <div className="fixed inset-0 bg-gray-900/60 backdrop-blur-sm z-50 flex items-center justify-center transition-opacity">
      <div
        className="bg-white rounded-2xl shadow-2xl p-8 w-[600px] max-h-[80vh] flex flex-col transform transition-all scale-100"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex justify-between items-center mb-6 border-b pb-4">
          <h3 className="text-xl font-bold text-gray-800">Manage Policies</h3>
          <button
            onClick={onClose}
            className="text-gray-400 hover:text-gray-600 transition-colors"
          >
            <i className="fas fa-times text-lg"></i>
          </button>
        </div>

        <div className="overflow-y-auto flex-1">
          {policies.length === 0 ? (
            <div className="text-center py-8 text-gray-500">No policies created yet.</div>
          ) : (
            <div className="space-y-3">
              {policies.map((p) => (
                <div
                  key={p.id}
                  className="flex justify-between items-center p-4 border rounded-lg hover:bg-gray-50 transition-colors group"
                >
                  <div>
                    <div className="font-bold text-gray-800">{p.name}</div>
                    <div className="text-xs text-gray-500 flex gap-2">
                      <span>{p.timeZone}</span>
                      {p.notificationsEnabled && (
                        <span className="text-green-600">• Notifications On</span>
                      )}
                    </div>
                  </div>
                  <div className="flex gap-2">
                    <button
                      onClick={() => onEdit(p)}
                      className="text-gray-400 hover:text-indigo-600 p-2 rounded-full hover:bg-indigo-50 transition-colors"
                      title="Edit"
                    >
                      <i className="fas fa-pencil-alt"></i>
                    </button>
                    <button
                      onClick={() => onDelete(p.id)}
                      className="text-gray-400 hover:text-red-600 p-2 rounded-full hover:bg-red-50 transition-colors"
                      title="Delete"
                    >
                      <i className="fas fa-trash"></i>
                    </button>
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>

        <div className="mt-6 pt-4 border-t text-right">
          <button
            onClick={onClose}
            className="px-4 py-2 bg-gray-100 hover:bg-gray-200 text-gray-700 rounded-lg font-medium transition-colors"
          >
            Close
          </button>
        </div>
      </div>
    </div>
  );
};

export default ManagePoliciesModal;
