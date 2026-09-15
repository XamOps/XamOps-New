import React, { useState, useMemo } from 'react';
import { Icon } from '@iconify/react';
import type { FlexComputeCapacity } from '../../types/flexCompute';
import ViewCapacityDetailModal from './ViewCapacityDetailModal';
import { AlertDialog } from '../ui';

interface FlexComputeTableProps {
  capacities: FlexComputeCapacity[];
  isLoading: boolean;
  onCancelCapacity: (cidrId: string) => Promise<boolean>;
  onExtendCapacity: (cidrId: string, datetime: string, timezone: string) => Promise<boolean>;
  onContinuePurchase?: (capacity: FlexComputeCapacity) => void;
  currentPage: number;
  pageSize: number;
  totalElements: number;
  totalPages: number;
  onPageChange: (page: number, pageSize: number, statuses: string[]) => void;
}

const STATUS_OPTIONS = [
  { value: 'ACTIVE',     label: 'Active',     cls: 'bg-emerald-50 text-emerald-600 border-emerald-100' },
  { value: 'COMPLETED',  label: 'Completed',  cls: 'bg-blue-50 text-blue-600 border-blue-100' },
  { value: 'EXPIRED',    label: 'Expired',    cls: 'bg-[rgba(0,0,0,0.04)] text-[rgba(0,0,0,0.45)] border-[rgba(0,0,0,0.08)]' },
  { value: 'CANCELED',   label: 'Cancelled',  cls: 'bg-rose-50 text-rose-600 border-rose-100' },
];

const STATUS_BADGE: Record<string, string> = {
  active:               'bg-emerald-50 text-emerald-600 border-emerald-100',
  running:              'bg-emerald-50 text-emerald-600 border-emerald-100',
  completed:            'bg-blue-50 text-blue-600 border-blue-100',
  scheduled:            'bg-blue-50 text-blue-600 border-blue-100',
  pending:              'bg-amber-50 text-amber-600 border-amber-100',
  ready_for_purchase:   'bg-amber-50 text-amber-600 border-amber-100',
  stopped:              'bg-[rgba(0,0,0,0.04)] text-[rgba(0,0,0,0.45)] border-[rgba(0,0,0,0.08)]',
  expired:              'bg-[rgba(0,0,0,0.04)] text-[rgba(0,0,0,0.45)] border-[rgba(0,0,0,0.08)]',
  expired_not_purchased:'bg-[rgba(0,0,0,0.04)] text-[rgba(0,0,0,0.45)] border-[rgba(0,0,0,0.08)]',
  terminated:           'bg-rose-50 text-rose-600 border-rose-100',
  canceled:             'bg-rose-50 text-rose-600 border-rose-100',
  cancelled:            'bg-rose-50 text-rose-600 border-rose-100',
};

const StatusBadge: React.FC<{ status: string }> = ({ status }) => {
  const key = (status || '').toLowerCase().replace(/ /g, '_');
  const cls = STATUS_BADGE[key] ?? 'bg-[rgba(0,0,0,0.04)] text-[rgba(0,0,0,0.45)] border-[rgba(0,0,0,0.08)]';
  return (
    <span
      title={status || 'N/A'}
      className={`inline-block max-w-full truncate align-middle px-2 py-0.5 rounded-full text-[11px] font-semibold border ${cls}`}
    >
      {status || 'N/A'}
    </span>
  );
};

const formatCurrency = (value: number): string =>
  new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD', minimumFractionDigits: 2 }).format(value || 0);

const PAGE_SIZE_OPTIONS = [5, 10, 20, 50];

const isReadyForPurchase = (cap: FlexComputeCapacity): boolean => {
  const status  = (cap.policyStatus || '').toLowerCase().replace(/ /g, '_');
  const opState = (cap.policyOperatingState || '').toLowerCase().replace(/ /g, '_');
  return status === 'ready_for_purchase' || opState === 'ready_for_purchase';
};

const FlexComputeTable: React.FC<FlexComputeTableProps> = ({
  capacities,
  isLoading,
  onCancelCapacity,
  onExtendCapacity,
  onContinuePurchase,
  currentPage,
  pageSize,
  totalElements,
  totalPages,
  onPageChange,
}) => {
  const [openDropdown, setOpenDropdown]   = useState<string | null>(null);
  const [cancelling, setCancelling]       = useState<string | null>(null);
  const [selectedCapacity, setSelectedCapacity] = useState<FlexComputeCapacity | null>(null);
  const [searchQuery, setSearchQuery]     = useState('');
  const [selectedStatuses, setSelectedStatuses] = useState<string[]>([]);
  const [confirmCancel, setConfirmCancel] = useState<{ cidrId: string; name: string } | null>(null);

  const handleCancel = (cidrId: string, capacityName: string) => {
    setConfirmCancel({ cidrId, name: capacityName });
    setOpenDropdown(null);
  };

  const handleConfirmCancel = async () => {
    if (!confirmCancel) return;
    setCancelling(confirmCancel.cidrId);
    setConfirmCancel(null);
    try { await onCancelCapacity(confirmCancel.cidrId); }
    finally { setCancelling(null); }
  };

  const toggleStatus = (status: string) => {
    const next = selectedStatuses.includes(status)
      ? selectedStatuses.filter((s) => s !== status)
      : [...selectedStatuses, status];
    setSelectedStatuses(next);
    onPageChange(1, pageSize, next);
  };

  const clearFilters = () => {
    setSelectedStatuses([]);
    setSearchQuery('');
    onPageChange(1, pageSize, []);
  };

  const filteredCapacities = useMemo(() => {
    if (!searchQuery.trim()) return capacities;
    const q = searchQuery.toLowerCase();
    return capacities.filter((cap) =>
      (cap.name || '').toLowerCase().includes(q) ||
      (cap.id || '').toLowerCase().includes(q) ||
      (cap.instanceType || '').toLowerCase().includes(q) ||
      (cap.accountName || '').toLowerCase().includes(q) ||
      (cap.regionZoneId || '').toLowerCase().includes(q)
    );
  }, [capacities, searchQuery]);

  const startItem = (currentPage - 1) * pageSize + 1;
  const endItem   = Math.min(currentPage * pageSize, totalElements);

  return (
    <>
      <AlertDialog
        isOpen={!!confirmCancel}
        title={`Cancel "${confirmCancel?.name}"?`}
        description="This action cannot be undone."
        confirmLabel="Cancel Capacity"
        cancelLabel="Keep"
        variant="danger"
        onConfirm={handleConfirmCancel}
        onCancel={() => setConfirmCancel(null)}
      />

      <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm overflow-hidden">
        {/* Toolbar */}
        <div className="px-5 py-3.5 border-b border-[rgba(0,0,0,0.06)] space-y-3">
          <div className="flex items-center justify-between">
            <h3 className="text-[14px] font-semibold text-[rgba(0,0,0,0.88)]">
              Scheduled Capacities
              <span className="ml-2 text-[12px] font-normal text-[rgba(0,0,0,0.4)]">({totalElements})</span>
            </h3>
            <select
              value={pageSize}
              onChange={(e) => onPageChange(1, parseInt(e.target.value), selectedStatuses)}
              className="px-3 py-1.5 border border-[rgba(0,0,0,0.1)] rounded-lg text-[12px] bg-white text-[rgba(0,0,0,0.65)] focus:outline-none focus:ring-2 focus:ring-indigo-300"
            >
              {PAGE_SIZE_OPTIONS.map((s) => <option key={s} value={s}>{s} per page</option>)}
            </select>
          </div>

          <div className="flex flex-col sm:flex-row items-start sm:items-center gap-3">
            {/* Search */}
            <div className="relative">
              <Icon icon="mdi:magnify" width={13} className="absolute left-2.5 top-1/2 -translate-y-1/2 text-[rgba(0,0,0,0.3)]" />
              <input
                type="text"
                value={searchQuery}
                onChange={(e) => setSearchQuery(e.target.value)}
                placeholder="Search by name, ID, instance type…"
                className="pl-7 pr-8 py-1.5 text-[12px] border border-[rgba(0,0,0,0.1)] rounded-lg focus:outline-none focus:ring-2 focus:ring-indigo-300 bg-white w-56 text-[rgba(0,0,0,0.88)] placeholder:text-[rgba(0,0,0,0.3)]"
              />
              {searchQuery && (
                <button onClick={() => setSearchQuery('')} className="absolute right-2.5 top-1/2 -translate-y-1/2 text-[rgba(0,0,0,0.3)] hover:text-[rgba(0,0,0,0.6)]">
                  <Icon icon="mdi:close" width={12} />
                </button>
              )}
            </div>

            {/* Status filter pills */}
            <div className="flex items-center gap-1.5 flex-wrap">
              <span className="text-[11px] text-[rgba(0,0,0,0.4)] font-medium">Status:</span>
              {STATUS_OPTIONS.map((opt) => {
                const isSelected = selectedStatuses.includes(opt.value);
                return (
                  <button
                    key={opt.value}
                    onClick={() => toggleStatus(opt.value)}
                    className={`px-2.5 py-0.5 rounded-full text-[11px] font-semibold border transition-colors ${
                      isSelected ? `${opt.cls}` : 'bg-[rgba(0,0,0,0.03)] text-[rgba(0,0,0,0.45)] border-[rgba(0,0,0,0.08)] hover:bg-[rgba(0,0,0,0.06)]'
                    }`}
                  >
                    {opt.label}
                  </button>
                );
              })}
              {(selectedStatuses.length > 0 || searchQuery) && (
                <button onClick={clearFilters} className="text-[11px] text-[rgba(0,0,0,0.4)] hover:text-[rgba(0,0,0,0.7)]">
                  Clear all
                </button>
              )}
            </div>
          </div>
        </div>

        {/* Loading */}
        {isLoading ? (
          <div className="p-5 space-y-3">
            {Array.from({ length: 5 }).map((_, i) => (
              <div key={i} className="animate-pulse flex gap-4">
                {[3,2,2,1,1,1,1,2,1].map((w, j) => (
                  <div key={j} className={`h-3 bg-[rgba(0,0,0,0.06)] rounded flex-${w === 3 ? '[3]' : w === 2 ? '[2]' : '[1]'} w-0`} style={{ flex: w }} />
                ))}
              </div>
            ))}
          </div>
        ) : filteredCapacities.length === 0 ? (
          <div className="p-10 text-center">
            <div className="w-12 h-12 bg-[rgba(0,0,0,0.04)] rounded-full flex items-center justify-center mx-auto mb-3">
              <Icon icon="mdi:magnify" width={22} color="rgba(0,0,0,0.3)" />
            </div>
            <p className="text-[14px] font-semibold text-[rgba(0,0,0,0.88)] mb-1">No Results Found</p>
            <p className="text-[13px] text-[rgba(0,0,0,0.45)]">
              {searchQuery ? 'Try a different search term or ' : ''}
              {selectedStatuses.length > 0 ? 'adjust your status filters.' : 'No capacity reservations yet.'}
            </p>
            {(searchQuery || selectedStatuses.length > 0) && (
              <button onClick={clearFilters} className="mt-3 text-[13px] text-indigo-600 hover:text-indigo-700 font-medium">
                Clear filters
              </button>
            )}
          </div>
        ) : (
          <>
            {/* Column headers */}
            <div className="grid grid-cols-12 gap-x-3 px-5 py-2 bg-[rgba(0,0,0,0.02)] text-[11px] font-semibold uppercase tracking-wide text-[rgba(0,0,0,0.4)] border-b border-[rgba(0,0,0,0.06)]">
              <div className="col-span-1 min-w-0 truncate">ID</div>
              <div className="col-span-2 min-w-0 truncate">Name</div>
              <div className="col-span-1 min-w-0 truncate">Account</div>
              <div className="col-span-1 min-w-0 truncate">Policy Status</div>
              <div className="col-span-2 min-w-0 truncate">Instance State</div>
              <div className="col-span-1 min-w-0 truncate">Type</div>
              <div className="col-span-1 min-w-0 truncate">Region / Zone</div>
              <div className="col-span-1 min-w-0 truncate">Est. Savings</div>
              <div className="col-span-1 min-w-0 truncate">Qty</div>
              <div className="col-span-1 min-w-0 text-center">Actions</div>
            </div>

            {filteredCapacities.map((cap) => (
              <div
                key={cap.id}
                className="grid grid-cols-12 gap-x-3 items-center px-5 py-3 border-t border-[rgba(0,0,0,0.04)] hover:bg-[rgba(0,0,0,0.01)] transition-colors text-[12px]"
              >
                <div className="col-span-1 min-w-0 font-mono text-[rgba(0,0,0,0.45)] truncate">{cap.id?.substring(0, 8)}…</div>
                <div className="col-span-2 min-w-0 font-medium text-[rgba(0,0,0,0.88)] truncate">{cap.name}</div>
                <div className="col-span-1 min-w-0 text-[rgba(0,0,0,0.55)] truncate">{cap.accountName}</div>
                <div className="col-span-1 min-w-0"><StatusBadge status={cap.policyStatus} /></div>
                <div className="col-span-2 min-w-0"><StatusBadge status={cap.instanceState} /></div>
                <div className="col-span-1 min-w-0 font-mono text-[rgba(0,0,0,0.65)] truncate">{cap.instanceType}</div>
                <div className="col-span-1 min-w-0 text-[rgba(0,0,0,0.55)] truncate">{cap.regionZoneId}</div>
                <div className="col-span-1 min-w-0 font-semibold text-emerald-600 truncate">{formatCurrency(cap.estimatedSavings)}</div>
                <div className="col-span-1 min-w-0 text-[rgba(0,0,0,0.65)] truncate">{cap.quantities}</div>
                <div className="col-span-1 min-w-0 flex justify-center relative">
                  <button
                    onClick={() => setOpenDropdown(openDropdown === cap.id ? null : cap.id)}
                    disabled={cancelling === cap.id}
                    className="p-1.5 rounded-lg text-[rgba(0,0,0,0.4)] hover:text-[rgba(0,0,0,0.7)] hover:bg-[rgba(0,0,0,0.04)] transition-colors"
                  >
                    {cancelling === cap.id
                      ? <Icon icon="mdi:loading" width={14} className="animate-spin" />
                      : <Icon icon="mdi:dots-vertical" width={14} />}
                  </button>

                  {openDropdown === cap.id && (
                    <>
                      <div className="fixed inset-0 z-10" onClick={() => setOpenDropdown(null)} />
                      <div className="absolute right-0 mt-8 w-48 bg-white rounded-xl shadow-lg border border-[rgba(0,0,0,0.08)] py-1 z-20">
                        <button
                          onClick={() => { setOpenDropdown(null); setSelectedCapacity(cap); }}
                          className="w-full text-left px-4 py-2 text-[12px] text-[rgba(0,0,0,0.65)] hover:bg-[rgba(0,0,0,0.03)] flex items-center gap-2"
                        >
                          <Icon icon="mdi:eye-outline" width={13} /> View Detail
                        </button>
                        {isReadyForPurchase(cap) && onContinuePurchase && (
                          <button
                            onClick={() => { setOpenDropdown(null); onContinuePurchase(cap); }}
                            className="w-full text-left px-4 py-2 text-[12px] text-emerald-700 hover:bg-emerald-50 flex items-center gap-2 font-medium"
                          >
                            <Icon icon="mdi:cart-outline" width={13} /> Continue Purchase
                          </button>
                        )}
                        <button
                          onClick={() => handleCancel(cap.id, cap.name)}
                          disabled={cancelling === cap.id}
                          className="w-full text-left px-4 py-2 text-[12px] text-rose-600 hover:bg-rose-50 flex items-center gap-2 disabled:opacity-50"
                        >
                          <Icon icon="mdi:close-circle-outline" width={13} /> Cancel
                        </button>
                      </div>
                    </>
                  )}
                </div>
              </div>
            ))}

            {/* Pagination */}
            <div className="px-5 py-3 border-t border-[rgba(0,0,0,0.06)] flex items-center justify-between bg-[rgba(0,0,0,0.01)]">
              <p className="text-[11px] text-[rgba(0,0,0,0.4)]">
                Showing <span className="font-medium">{startItem}</span>–<span className="font-medium">{endItem}</span> of <span className="font-medium">{totalElements}</span>
              </p>
              <div className="flex items-center gap-1.5">
                <button
                  onClick={() => onPageChange(1, pageSize, selectedStatuses)}
                  disabled={currentPage <= 1}
                  className="p-1.5 text-[rgba(0,0,0,0.45)] border border-[rgba(0,0,0,0.1)] rounded-lg hover:bg-[rgba(0,0,0,0.02)] disabled:opacity-40 text-[11px]"
                >
                  <Icon icon="mdi:chevron-double-left" width={13} />
                </button>
                <button
                  onClick={() => onPageChange(currentPage - 1, pageSize, selectedStatuses)}
                  disabled={currentPage <= 1}
                  className="flex items-center gap-1 px-2.5 py-1 text-[11px] border border-[rgba(0,0,0,0.1)] rounded-lg hover:bg-[rgba(0,0,0,0.02)] disabled:opacity-40 text-[rgba(0,0,0,0.55)]"
                >
                  <Icon icon="mdi:chevron-left" width={12} /> Prev
                </button>

                {Array.from({ length: Math.min(totalPages, 5) }, (_, i) => {
                  let pageNum: number;
                  if (totalPages <= 5)          pageNum = i + 1;
                  else if (currentPage <= 3)    pageNum = i + 1;
                  else if (currentPage >= totalPages - 2) pageNum = totalPages - 4 + i;
                  else                          pageNum = currentPage - 2 + i;
                  return (
                    <button
                      key={pageNum}
                      onClick={() => onPageChange(pageNum, pageSize, selectedStatuses)}
                      className={`w-8 h-8 text-[11px] rounded-lg transition-colors ${
                        pageNum === currentPage
                          ? 'bg-indigo-600 text-white font-semibold'
                          : 'border border-[rgba(0,0,0,0.1)] text-[rgba(0,0,0,0.55)] hover:bg-[rgba(0,0,0,0.02)]'
                      }`}
                    >
                      {pageNum}
                    </button>
                  );
                })}

                <button
                  onClick={() => onPageChange(currentPage + 1, pageSize, selectedStatuses)}
                  disabled={currentPage >= totalPages}
                  className="flex items-center gap-1 px-2.5 py-1 text-[11px] border border-[rgba(0,0,0,0.1)] rounded-lg hover:bg-[rgba(0,0,0,0.02)] disabled:opacity-40 text-[rgba(0,0,0,0.55)]"
                >
                  Next <Icon icon="mdi:chevron-right" width={12} />
                </button>
                <button
                  onClick={() => onPageChange(totalPages, pageSize, selectedStatuses)}
                  disabled={currentPage >= totalPages}
                  className="p-1.5 text-[rgba(0,0,0,0.45)] border border-[rgba(0,0,0,0.1)] rounded-lg hover:bg-[rgba(0,0,0,0.02)] disabled:opacity-40 text-[11px]"
                >
                  <Icon icon="mdi:chevron-double-right" width={13} />
                </button>
              </div>
            </div>
          </>
        )}
      </div>

      {selectedCapacity && (
        <ViewCapacityDetailModal
          capacity={selectedCapacity}
          onClose={() => setSelectedCapacity(null)}
          onExtend={onExtendCapacity}
        />
      )}
    </>
  );
};

export default FlexComputeTable;
