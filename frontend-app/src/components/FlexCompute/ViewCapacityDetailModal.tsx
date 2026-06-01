import React, { useEffect, useState } from 'react';
import { Icon } from '@iconify/react';
import type { FlexComputeCapacity } from '../../types/flexCompute';
import { DialogRoot, DialogPanel } from '../ui';
import ExtendUsageModal from './ExtendUsageModal';

interface ViewCapacityDetailModalProps {
  capacity: FlexComputeCapacity | null;
  onClose: () => void;
  onExtend: (cidrId: string, datetime: string, timezone: string) => Promise<boolean>;
}

const formatCurrency = (value?: number): string =>
  new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD', minimumFractionDigits: 2 }).format(value || 0);

const formatDateTime = (dateStr?: string): string => {
  if (!dateStr) return '-';
  try {
    return new Date(dateStr).toLocaleString('en-US', {
      day: 'numeric', month: 'long', year: 'numeric',
      hour: '2-digit', minute: '2-digit', timeZoneName: 'short',
    });
  } catch {
    return dateStr;
  }
};

const StatusBadge: React.FC<{ status: string; variant: 'green' | 'blue' | 'amber' | 'red' | 'gray' }> = ({ status, variant }) => {
  const cls = {
    green: 'bg-emerald-50 text-emerald-700 border-emerald-100',
    blue:  'bg-blue-50 text-blue-700 border-blue-100',
    amber: 'bg-amber-50 text-amber-700 border-amber-100',
    red:   'bg-red-50 text-red-700 border-red-100',
    gray:  'bg-[rgba(0,0,0,0.04)] text-[rgba(0,0,0,0.45)] border-[rgba(0,0,0,0.08)]',
  }[variant];
  return (
    <span className={`inline-block px-2.5 py-0.5 text-[11px] font-semibold rounded-full border ${cls}`}>
      {status || '-'}
    </span>
  );
};

const CAPACITY_TRACKER_STEPS = [
  { label: 'Awaiting scheduled start time',       states: ['QUEUED_FOR_START'] },
  { label: 'Instance allocation initiated',        states: ['ALLOCATING'] },
  { label: 'Started sharing capacity',             states: ['SHARING'] },
  { label: 'Allocated capacity in customer account', states: ['ALLOCATED'] },
  { label: 'De allocation initiated',              states: ['DEALLOCATING'] },
  { label: 'Capacity sharing stopped',             states: ['STOPPED'] },
  { label: 'Completed',                            states: ['COMPLETED', 'EXPIRED', 'CANCELED'] },
];

const isFailed = (instanceState?: string): boolean => {
  const s = (instanceState || '').toUpperCase();
  return s.includes('FAILED') || s.includes('ERROR');
};

const getActiveStep = (instanceState?: string): number => {
  const state = (instanceState || '').toUpperCase().replace(/ /g, '_');
  const idx = CAPACITY_TRACKER_STEPS.findIndex((s) => s.states.some((st) => state.includes(st)));
  return idx >= 0 ? idx : 0;
};

const SectionHeader: React.FC<{ title: string }> = ({ title }) => (
  <div className="px-5 py-3 border-b border-[rgba(0,0,0,0.06)] bg-[rgba(0,0,0,0.01)]">
    <h3 className="text-[11px] font-semibold uppercase tracking-[0.09em] text-[rgba(0,0,0,0.45)]">{title}</h3>
  </div>
);

const Row: React.FC<{ label: string; value: React.ReactNode }> = ({ label, value }) => (
  <div className="flex items-center justify-between py-3 border-b border-[rgba(0,0,0,0.04)] last:border-0">
    <span className="text-[12px] text-[rgba(0,0,0,0.45)]">{label}</span>
    <span className="text-[13px] font-medium text-[rgba(0,0,0,0.88)] text-right max-w-xs truncate">{value || '-'}</span>
  </div>
);

const ViewCapacityDetailModal: React.FC<ViewCapacityDetailModalProps> = ({ capacity, onClose, onExtend }) => {
  const [showExtendModal, setShowExtendModal] = useState(false);
  const [activityLog, setActivityLog] = useState<{ createdDate: string; message: string }[]>([]);

  useEffect(() => {
    if (!capacity?.id) return;
    setActivityLog(capacity.activityLog || []);
    fetch(`/api/flexcompute/capacity/${capacity.id}`, { credentials: 'include' })
      .then((r) => r.ok ? r.json() : null)
      .then((data) => { if (data?.activityLog) setActivityLog(data.activityLog); })
      .catch(() => {});
  }, [capacity?.id]);

  if (!capacity) return null;

  const activeStep = getActiveStep(capacity.instanceState);
  const savingsPercentDisplay = capacity.savingsPercent != null
    ? (capacity.savingsPercent > 1 ? capacity.savingsPercent : capacity.savingsPercent * 100)
    : null;

  const isNotPurchased = capacity.instanceState?.toUpperCase().includes('NOT_PURCHASED') ||
    capacity.policyOperatingState?.toUpperCase().includes('NOT_PURCHASED');

  return (
    <>
      <DialogRoot isOpen={!!capacity} onClose={onClose} maxWidth="max-w-6xl">
        <DialogPanel
          title={
            <div className="flex items-center gap-3">
              <div className="w-9 h-9 bg-indigo-50 rounded-lg flex items-center justify-center flex-shrink-0">
                <Icon icon="mdi:server-outline" width={18} color="#6366F1" />
              </div>
              <div className="text-[16px] font-bold text-[rgba(0,0,0,0.88)] truncate">{capacity.name}</div>
            </div>
          }
          onClose={onClose}
          footer={
            <div className="flex items-center gap-2">
              {!isNotPurchased && !capacity.autoRenewal && (
                <button
                  onClick={() => setShowExtendModal(true)}
                  className="flex items-center gap-2 px-4 py-2 text-[13px] font-semibold text-white bg-indigo-600 rounded-lg hover:bg-indigo-700 transition-colors"
                >
                  <Icon icon="mdi:clock-plus-outline" width={14} />
                  Extend Usage
                </button>
              )}
              <button
                onClick={onClose}
                className="px-4 py-2 text-[13px] font-semibold text-[rgba(0,0,0,0.65)] bg-white border border-[rgba(0,0,0,0.12)] rounded-lg hover:bg-[rgba(0,0,0,0.02)] transition-colors"
              >
                Close
              </button>
            </div>
          }
        >
          <div className="overflow-y-auto max-h-[65vh]">
            <div className="grid grid-cols-1 lg:grid-cols-2 gap-5">

              {/* LEFT COLUMN */}
              <div className="space-y-5">

                {/* Description */}
                <div className="rounded-xl border border-[rgba(0,0,0,0.08)] overflow-hidden">
                  <SectionHeader title="Description" />
                  <div className="px-5 py-1">
                    <Row label="ID" value={<span className="font-mono text-[11px]">{capacity.id}</span>} />
                    <Row label="Account Name" value={capacity.accountName} />
                    <Row label="Policy Status" value={<StatusBadge status={capacity.policyStatus} variant="green" />} />
                    <Row label="Instance State" value={<StatusBadge status={capacity.instanceState} variant="blue" />} />
                    <Row label="Instance Type" value={<span className="font-mono">{capacity.instanceType}</span>} />
                    <Row label="vCPU" value={capacity.vcpu} />
                    <Row label="Memory" value={capacity.memory ? `${capacity.memory} GB` : '-'} />
                    <Row label="Platform" value={capacity.platform || 'Linux/UNIX'} />
                    <Row label="Total Capacity" value={capacity.quantities} />
                    <Row label="Tenancy" value={capacity.tenancy || 'Shared'} />
                  </div>
                </div>

                {/* Configuration */}
                <div className="rounded-xl border border-[rgba(0,0,0,0.08)] overflow-hidden">
                  <SectionHeader title="Configuration" />
                  <div className="px-5 py-1">
                    <Row label="VPC ID" value={<span className="font-mono text-[11px]">{capacity.vpc || 'default'}</span>} />
                    <Row label="Security Groups" value={<span className="font-mono text-[11px]">{capacity.securityGroups || 'default'}</span>} />
                    <Row label="SSH Pair Key" value={<span className="font-mono text-[11px]">{capacity.sshPairKey || 'none'}</span>} />
                    <Row label="AMI Image ID" value={<span className="font-mono text-[11px]">{capacity.amiImage || 'default'}</span>} />
                    <Row label="EBS Volume Size" value={capacity.ebsVolumeSize ? `${capacity.ebsVolumeSize} GiB` : '10 GiB'} />
                    <Row label="Start Time" value={formatDateTime(capacity.startDate)} />
                    <Row label="End Time" value={formatDateTime(capacity.endDate)} />
                  </div>
                </div>

                {/* Capacity Usage */}
                {!isNotPurchased && (
                  <div className="rounded-xl border border-[rgba(0,0,0,0.08)] overflow-hidden">
                    <SectionHeader title="Capacity Usage" />
                    <div className="p-5 space-y-3">
                      <div className="flex items-center justify-between text-[13px]">
                        <span className="text-[rgba(0,0,0,0.55)]">Time Consumed</span>
                        <span className="font-bold text-[rgba(0,0,0,0.88)]">
                          {capacity.consumptionPercent != null ? `${capacity.consumptionPercent.toFixed(1)}%` : '0.0%'}
                        </span>
                      </div>
                      <div className="w-full h-2.5 bg-[rgba(0,0,0,0.06)] rounded-full overflow-hidden">
                        <div
                          className={`h-full rounded-full transition-all duration-500 ${
                            (capacity.consumptionPercent ?? 0) >= 90 ? 'bg-red-500'
                            : (capacity.consumptionPercent ?? 0) >= 80 ? 'bg-orange-500'
                            : (capacity.consumptionPercent ?? 0) >= 60 ? 'bg-amber-500'
                            : 'bg-indigo-500'
                          }`}
                          style={{ width: `${capacity.consumptionPercent ?? 0}%` }}
                        />
                      </div>
                      <div className="grid grid-cols-2 gap-4 pt-1">
                        <div>
                          <p className="text-[11px] text-[rgba(0,0,0,0.4)]">Used Hours</p>
                          <p className="text-[13px] font-semibold text-[rgba(0,0,0,0.88)]">
                            {((capacity.totalUsageHours || 0) - (capacity.remainingHours || 0)).toFixed(1)} hrs
                          </p>
                        </div>
                        <div>
                          <p className="text-[11px] text-[rgba(0,0,0,0.4)]">Remaining Hours</p>
                          <p className="text-[13px] font-semibold text-[rgba(0,0,0,0.88)]">
                            {(capacity.remainingHours || 0).toFixed(1)} hrs
                          </p>
                        </div>
                      </div>
                      {(capacity.consumptionPercent ?? 0) >= 80 && (
                        <div className={`p-3 rounded-lg flex items-start gap-2 ${
                          (capacity.consumptionPercent ?? 0) >= 90
                            ? 'bg-red-50 border border-red-200'
                            : 'bg-orange-50 border border-orange-200'
                        }`}>
                          <Icon
                            icon="mdi:alert-outline"
                            width={15}
                            className="mt-0.5 flex-shrink-0"
                            color={(capacity.consumptionPercent ?? 0) >= 90 ? '#DC2626' : '#EA580C'}
                          />
                          <div>
                            <p className={`text-[12px] font-medium ${(capacity.consumptionPercent ?? 0) >= 90 ? 'text-red-800' : 'text-orange-800'}`}>
                              {(capacity.consumptionPercent ?? 0) >= 90 ? 'Critical: Capacity almost depleted' : 'Warning: Capacity running low'}
                            </p>
                            <p className={`text-[11px] mt-0.5 ${(capacity.consumptionPercent ?? 0) >= 90 ? 'text-red-700' : 'text-orange-700'}`}>
                              {capacity.autoRenewal
                                ? 'Auto-renewal is enabled and will activate soon.'
                                : 'Consider extending capacity or enabling auto-renewal.'}
                            </p>
                          </div>
                        </div>
                      )}
                    </div>
                  </div>
                )}

                {/* Activity */}
                <div className="rounded-xl border border-[rgba(0,0,0,0.08)] overflow-hidden">
                  <SectionHeader title="Activity" />
                  <div className="p-5">
                    {isNotPurchased ? (
                      <p className="text-[13px] text-[rgba(0,0,0,0.4)] text-center py-2">
                        This policy was not purchased. No activity history available.
                      </p>
                    ) : (
                      <div className="space-y-3">
                        {[
                          { date: capacity.createdDate || capacity.startDate, msg: `Policy purchased successfully at a cost of ${formatCurrency(capacity.ourPrice || capacity.totalCost)}${capacity.totalSavings ? ` with savings of ${formatCurrency(capacity.totalSavings)}.` : '.'}` },
                          { date: capacity.createdDate || capacity.startDate, msg: 'Capacity is available; policy is ready for use.' },
                          { date: capacity.createdDate || capacity.startDate, msg: 'Policy created successfully.' },
                        ].map((entry, i) => (
                          <div key={i} className="flex gap-3">
                            <span className="text-[11px] text-[rgba(0,0,0,0.35)] whitespace-nowrap flex-shrink-0">{formatDateTime(entry.date)}</span>
                            <span className="text-[12px] text-[rgba(0,0,0,0.65)]">{entry.msg}</span>
                          </div>
                        ))}
                      </div>
                    )}
                  </div>
                </div>
              </div>

              {/* RIGHT COLUMN */}
              <div className="space-y-5">

                {/* Auto-Renewal */}
                <div className="rounded-xl border border-[rgba(0,0,0,0.08)] overflow-hidden">
                  <SectionHeader title="Auto-Renewal Settings" />
                  <div className="px-5 py-1">
                    <Row label="Auto-renewal enabled" value={capacity.autoRenewal ? 'Yes' : 'No'} />
                    <Row label="Frequency of renewal" value={capacity.frequency || '-'} />
                  </div>
                </div>

                {/* Cost and Savings */}
                <div className="rounded-xl border border-[rgba(0,0,0,0.08)] overflow-hidden">
                  <SectionHeader title="Cost and Savings" />
                  <div className="px-5 py-1">
                    <Row label="Our price" value={formatCurrency(capacity.ourPrice || capacity.totalCost)} />
                    <Row label="AWS price" value={formatCurrency(capacity.awsPrice || (capacity.totalCost + capacity.totalSavings))} />
                    <Row label="Your savings" value={<span className="font-bold text-emerald-600">{formatCurrency(capacity.totalSavings)}</span>} />
                    <Row label="Savings %" value={savingsPercentDisplay != null ? `${savingsPercentDisplay.toFixed(1)}%` : '-'} />
                  </div>
                </div>

                {/* Region/Zone */}
                <div className="rounded-xl border border-[rgba(0,0,0,0.08)] overflow-hidden">
                  <SectionHeader title="Region / Zone ID" />
                  <div className="px-5 py-1">
                    <Row label="Region Code" value={capacity.region || capacity.regionZoneId?.split('/')[0]?.trim()} />
                    <Row label="Zone ID" value={capacity.zoneId || capacity.regionZoneId?.split('/')[1]?.trim()} />
                  </div>
                </div>

                {/* Capacity Tracker */}
                <div className="rounded-xl border border-[rgba(0,0,0,0.08)] overflow-hidden">
                  <SectionHeader title="Capacity Tracker" />
                  <div className="p-5">
                    {isNotPurchased ? (
                      <div className="text-center py-8">
                        <div className="w-12 h-12 bg-[rgba(0,0,0,0.04)] rounded-full flex items-center justify-center mx-auto mb-3">
                          <Icon icon="mdi:cancel" width={22} color="rgba(0,0,0,0.2)" />
                        </div>
                        <p className="text-[13px] font-medium text-[rgba(0,0,0,0.45)]">No capacity tracking available</p>
                        <p className="text-[12px] text-[rgba(0,0,0,0.3)] mt-1">This policy was not purchased</p>
                      </div>
                    ) : (
                      <>
                        {isFailed(capacity.instanceState) && (
                          <div className="mb-4 flex items-start gap-3 bg-red-50 border border-red-200 rounded-lg px-4 py-3">
                            <Icon icon="mdi:close-circle-outline" width={16} color="#DC2626" className="mt-0.5 flex-shrink-0" />
                            <div>
                              <p className="text-[13px] font-semibold text-red-700">Capacity failed to start</p>
                              <p className="text-[12px] text-red-600 mt-0.5">
                                State: {capacity.instanceState}. Please check your VPC, subnet, and security group settings for the selected availability zone, then try scheduling again.
                              </p>
                            </div>
                          </div>
                        )}
                        {CAPACITY_TRACKER_STEPS.map((step, index) => {
                          const isActive    = index === activeStep;
                          const isCompleted = index < activeStep;
                          const isFail      = isActive && isFailed(capacity.instanceState);

                          return (
                            <div key={index} className="flex gap-4">
                              <div className="flex flex-col items-center">
                                <div className={`w-8 h-8 rounded-full flex items-center justify-center text-[12px] font-bold flex-shrink-0 ${
                                  isFail      ? 'bg-red-500 text-white'
                                  : isCompleted ? 'bg-indigo-600 text-white'
                                  : isActive    ? 'bg-indigo-600 text-white'
                                  : 'bg-[rgba(0,0,0,0.06)] text-[rgba(0,0,0,0.35)]'
                                }`}>
                                  {isFail
                                    ? <Icon icon="mdi:close" width={13} />
                                    : isCompleted
                                    ? <Icon icon="mdi:check" width={13} />
                                    : index + 1}
                                </div>
                                {index < CAPACITY_TRACKER_STEPS.length - 1 && (
                                  <div className={`w-0.5 h-7 my-1 ${isCompleted ? 'bg-indigo-400' : 'bg-[rgba(0,0,0,0.08)]'}`} />
                                )}
                              </div>
                              <div className="pt-1.5 pb-4 flex-1">
                                <p className={`text-[13px] font-medium ${
                                  isFail      ? 'text-red-600'
                                  : isActive    ? 'text-indigo-600'
                                  : isCompleted ? 'text-[rgba(0,0,0,0.65)]'
                                  : 'text-[rgba(0,0,0,0.3)]'
                                }`}>
                                  {step.label}
                                </p>
                                <p className="text-[11px] text-[rgba(0,0,0,0.3)]">STEP {index + 1}</p>
                              </div>
                            </div>
                          );
                        })}
                      </>
                    )}
                  </div>
                </div>
              </div>
            </div>

            {/* Activity Log */}
            {activityLog.length > 0 && (
              <div className="mt-5 rounded-xl border border-[rgba(0,0,0,0.08)] overflow-hidden">
                <SectionHeader title="Activity Log" />
                <div className="divide-y divide-[rgba(0,0,0,0.04)]">
                  {activityLog.map((entry, i) => (
                    <div key={i} className="flex items-start gap-4 px-5 py-3">
                      <span className="text-[11px] text-[rgba(0,0,0,0.35)] whitespace-nowrap pt-0.5 w-44 flex-shrink-0">
                        {entry.createdDate
                          ? new Date(entry.createdDate).toLocaleString('en-US', { dateStyle: 'medium', timeStyle: 'short', timeZoneName: 'short' })
                          : '-'}
                      </span>
                      <span className={`text-[13px] ${
                        entry.message?.toLowerCase().includes('fail') || entry.message?.toLowerCase().includes('error')
                          ? 'text-red-600 font-medium'
                          : 'text-[rgba(0,0,0,0.65)]'
                      }`}>
                        {entry.message}
                      </span>
                    </div>
                  ))}
                </div>
              </div>
            )}
          </div>
        </DialogPanel>
      </DialogRoot>

      {showExtendModal && (
        <ExtendUsageModal
          capacity={capacity}
          onExtend={onExtend}
          onClose={() => setShowExtendModal(false)}
        />
      )}
    </>
  );
};

export default ViewCapacityDetailModal;
