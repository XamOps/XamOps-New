import React from 'react';
import { Icon } from '@iconify/react';
import type { CloudSitterPolicy } from '../../types/cloudsitter';
import { DialogRoot, DialogPanel } from '../ui';

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
          <button
            onClick={onClose}
            className="px-4 py-2 bg-[rgba(0,0,0,0.04)] hover:bg-[rgba(0,0,0,0.07)] text-[rgba(0,0,0,0.65)] rounded-lg font-medium transition-colors text-[13px]"
          >
            Close
          </button>
        }
      >
        <div className="overflow-y-auto max-h-[50vh]">
          {policies.length === 0 ? (
            <div className="text-center py-8">
              <div className="w-10 h-10 bg-[rgba(0,0,0,0.04)] rounded-full flex items-center justify-center mx-auto mb-3">
                <Icon icon="mdi:calendar-blank-outline" width={20} color="rgba(0,0,0,0.3)" />
              </div>
              <p className="text-[13px] text-[rgba(0,0,0,0.45)]">No policies created yet.</p>
            </div>
          ) : (
            <div className="space-y-2">
              {policies.map((p) => (
                <div
                  key={p.id}
                  className="flex justify-between items-center p-4 border border-[rgba(0,0,0,0.08)] rounded-xl hover:bg-[rgba(0,0,0,0.01)] transition-colors"
                >
                  <div>
                    <div className="font-semibold text-[rgba(0,0,0,0.88)] text-[13px]">{p.name}</div>
                    <div className="flex items-center gap-2 text-[11px] text-[rgba(0,0,0,0.4)] mt-0.5">
                      <span>{p.timeZone}</span>
                      {p.notificationsEnabled && (
                        <span className="text-emerald-600 flex items-center gap-1">
                          <Icon icon="mdi:bell-outline" width={10} /> Notifications On
                        </span>
                      )}
                    </div>
                  </div>
                  <div className="flex gap-1">
                    <button
                      onClick={() => onEdit(p)}
                      className="p-2 rounded-lg text-[rgba(0,0,0,0.4)] hover:text-indigo-600 hover:bg-indigo-50 transition-colors"
                      title="Edit"
                    >
                      <Icon icon="mdi:pencil-outline" width={14} />
                    </button>
                    <button
                      onClick={() => onDelete(p.id)}
                      className="p-2 rounded-lg text-[rgba(0,0,0,0.4)] hover:text-rose-600 hover:bg-rose-50 transition-colors"
                      title="Delete"
                    >
                      <Icon icon="mdi:trash-can-outline" width={14} />
                    </button>
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
