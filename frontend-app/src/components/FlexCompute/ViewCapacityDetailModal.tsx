/**
 * View Capacity Detail Modal
 *
 * Displays comprehensive capacity information matching the CloudiDR detail view
 */

import React, { useEffect, useState } from 'react';
import type { FlexComputeCapacity } from '../../types/flexCompute';
import { DialogRoot, DialogPanel } from '../ui';
import ExtendUsageModal from './ExtendUsageModal';

interface ViewCapacityDetailModalProps {
  capacity: FlexComputeCapacity | null;
  onClose: () => void;
  onExtend: (cidrId: string, datetime: string, timezone: string) => Promise<boolean>;
}

const formatCurrency = (value?: number): string => {
  return new Intl.NumberFormat('en-US', {
    style: 'currency',
    currency: 'USD',
    minimumFractionDigits: 2,
  }).format(value || 0);
};

const formatDateTime = (dateStr?: string): string => {
  if (!dateStr) return '-';
  try {
    return new Date(dateStr).toLocaleString('en-US', {
      day: 'numeric',
      month: 'long',
      year: 'numeric',
      hour: '2-digit',
      minute: '2-digit',
      timeZoneName: 'short',
    });
  } catch {
    return dateStr;
  }
};

const StatusBadge: React.FC<{ status: string; color: 'green' | 'blue' | 'yellow' | 'red' | 'gray' }> = ({ status, color }) => {
  const colors = {
    green: 'bg-green-100 text-green-700',
    blue: 'bg-blue-100 text-blue-700',
    yellow: 'bg-yellow-100 text-yellow-800',
    red: 'bg-red-100 text-red-700',
    gray: 'bg-gray-100 text-gray-600',
  };
  return (
    <span className={`inline-block px-3 py-1 text-xs font-semibold rounded-full ${colors[color]}`}>
      {status || '-'}
    </span>
  );
};

const CAPACITY_TRACKER_STEPS = [
  { label: 'Awaiting scheduled start time', states: ['QUEUED_FOR_START'] },
  { label: 'Instance allocation initiated', states: ['ALLOCATING'] },
  { label: 'Started sharing capacity', states: ['SHARING'] },
  { label: 'Allocated capacity in customer account', states: ['ALLOCATED'] },
  { label: 'De allocation initiated', states: ['DEALLOCATING'] },
  { label: 'Capacity sharing stopped', states: ['STOPPED'] },
  { label: 'Completed', states: ['COMPLETED', 'EXPIRED', 'CANCELED'] },
];

const isFailed = (instanceState?: string): boolean => {
  const s = (instanceState || '').toUpperCase();
  return s.includes('FAILED') || s.includes('ERROR');
};

const getActiveStep = (instanceState?: string): number => {
  const state = (instanceState || '').toUpperCase().replace(/ /g, '_');
  const idx = CAPACITY_TRACKER_STEPS.findIndex((s) =>
    s.states.some((st) => state.includes(st))
  );
  return idx >= 0 ? idx : 0;
};

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

  // Debug: Log capacity data to console
  console.log('[ViewDetail] Capacity data:', capacity);

  const activeStep = getActiveStep(capacity.instanceState);

  // Fix savingsPercent if it's a decimal (0.15) instead of percentage (15)
  const savingsPercentDisplay = capacity.savingsPercent != null
    ? (capacity.savingsPercent > 1 ? capacity.savingsPercent : capacity.savingsPercent * 100)
    : null;

  const Row: React.FC<{ label: string; value: React.ReactNode }> = ({ label, value }) => (
    <div className="flex items-center justify-between py-3 border-b border-gray-100 last:border-0">
      <span className="text-sm text-gray-500">{label}:</span>
      <span className="text-sm font-medium text-gray-900 text-right max-w-xs truncate">{value || '-'}</span>
    </div>
  );

  return (
    <>
      <DialogRoot isOpen={!!capacity} onClose={onClose} maxWidth="max-w-6xl">
        <DialogPanel
          title={
            <div className="flex items-center gap-3">
              <div className="w-9 h-9 bg-blue-100 rounded-lg flex items-center justify-center flex-shrink-0">
                <i className="fas fa-server text-blue-600 text-sm"></i>
              </div>
              <div className="text-lg font-bold text-gray-900 truncate">{capacity.name}</div>
            </div>
          }
          onClose={onClose}
          footer={
            <div className="flex items-center gap-2">
              {!capacity.instanceState?.toUpperCase().includes('NOT_PURCHASED') &&
               !capacity.policyOperatingState?.toUpperCase().includes('NOT_PURCHASED') &&
               !capacity.autoRenewal && (
                <button onClick={() => setShowExtendModal(true)} className="px-4 py-2 text-sm text-white bg-purple-600 rounded-lg hover:bg-purple-700 transition-colors flex items-center gap-2">
                  <i className="fas fa-clock text-xs"></i> Extend Usage
                </button>
              )}
              <button onClick={onClose} className="px-4 py-2 text-sm text-gray-700 bg-gray-100 rounded-lg hover:bg-gray-200 transition-colors">Close</button>
            </div>
          }
        >
          <div className="overflow-y-auto max-h-[65vh]">
            <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">

              {/* ── LEFT COLUMN ── */}
              <div className="space-y-6">

                {/* Description */}
                <div className="rounded-xl border border-gray-200 overflow-hidden">
                  <div className="bg-gray-50 px-5 py-3 border-b border-gray-200">
                    <h3 className="text-sm font-semibold text-gray-700 uppercase tracking-wide">Description</h3>
                  </div>
                  <div className="px-5 py-1">
                    <Row label="ID" value={<span className="font-mono text-xs">{capacity.id}</span>} />
                    <Row label="Account Name" value={capacity.accountName} />
                    <Row label="Policy Status" value={
                      <StatusBadge status={capacity.policyStatus} color="green" />
                    } />
                    <Row label="Instance State" value={
                      <StatusBadge status={capacity.instanceState} color="blue" />
                    } />
                    <Row label="Instance Type" value={<span className="font-mono">{capacity.instanceType}</span>} />
                    <Row label="vCPU" value={capacity.vcpu} />
                    <Row label="Memory" value={capacity.memory ? `${capacity.memory} GB` : '-'} />
                    <Row label="Platform" value={capacity.platform || 'Linux/UNIX'} />
                    <Row label="Total Capacity" value={capacity.quantities} />
                    <Row label="Tenancy" value={capacity.tenancy || 'Shared'} />
                  </div>
                </div>

                {/* Configuration */}
                <div className="rounded-xl border border-gray-200 overflow-hidden">
                  <div className="bg-gray-50 px-5 py-3 border-b border-gray-200">
                    <h3 className="text-sm font-semibold text-gray-700 uppercase tracking-wide">Configuration</h3>
                  </div>
                  <div className="px-5 py-1">
                    <Row label="VPC ID" value={<span className="font-mono text-xs">{capacity.vpc || 'default'}</span>} />
                    <Row label="Security Groups" value={<span className="font-mono text-xs">{capacity.securityGroups || 'default'}</span>} />
                    <Row label="SSH Pair Key" value={<span className="font-mono text-xs">{capacity.sshPairKey || 'none'}</span>} />
                    <Row label="AMI Image ID" value={<span className="font-mono text-xs">{capacity.amiImage || 'default'}</span>} />
                    <Row label="EBS Volume Size" value={capacity.ebsVolumeSize ? `${capacity.ebsVolumeSize} GiB` : '10 GiB'} />
                    <Row label="Start Time" value={formatDateTime(capacity.startDate)} />
                    <Row label="End Time" value={formatDateTime(capacity.endDate)} />
                  </div>
                </div>

                {/* Capacity Usage - Only show for purchased policies */}
                {!capacity.instanceState?.toUpperCase().includes('NOT_PURCHASED') &&
                 !capacity.policyOperatingState?.toUpperCase().includes('NOT_PURCHASED') && (
                  <div className="rounded-xl border border-gray-200 overflow-hidden">
                    <div className="bg-gray-50 px-5 py-3 border-b border-gray-200">
                      <h3 className="text-sm font-semibold text-gray-700 uppercase tracking-wide">Capacity Usage</h3>
                    </div>
                    <div className="p-5">
                      <div className="space-y-3">
                        {/* Usage Statistics */}
                        <div className="flex items-center justify-between text-sm">
                          <span className="text-gray-600">Time Consumed</span>
                          <span className="font-bold text-gray-900">
                            {capacity.consumptionPercent != null ? `${capacity.consumptionPercent.toFixed(1)}%` : '0.0%'}
                          </span>
                        </div>

                        {/* Progress Bar */}
                        <div className="relative w-full h-3 bg-gray-100 rounded-full overflow-hidden">
                          <div
                            className={`absolute inset-y-0 left-0 rounded-full transition-all duration-500 ${
                              (capacity.consumptionPercent ?? 0) >= 90
                                ? 'bg-gradient-to-r from-red-500 to-red-600'
                                : (capacity.consumptionPercent ?? 0) >= 80
                                ? 'bg-gradient-to-r from-orange-500 to-orange-600'
                                : (capacity.consumptionPercent ?? 0) >= 60
                                ? 'bg-gradient-to-r from-yellow-500 to-yellow-600'
                                : 'bg-gradient-to-r from-blue-500 to-blue-600'
                            }`}
                            style={{ width: `${capacity.consumptionPercent ?? 0}%` }}
                          />
                        </div>

                        {/* Hours Breakdown */}
                        <div className="grid grid-cols-2 gap-4 pt-2">
                          <div className="flex flex-col">
                            <span className="text-xs text-gray-500">Used Hours</span>
                            <span className="text-sm font-semibold text-gray-900">
                              {((capacity.totalUsageHours || 0) - (capacity.remainingHours || 0)).toFixed(1)} hrs
                            </span>
                          </div>
                          <div className="flex flex-col">
                            <span className="text-xs text-gray-500">Remaining Hours</span>
                            <span className="text-sm font-semibold text-gray-900">
                              {(capacity.remainingHours || 0).toFixed(1)} hrs
                            </span>
                          </div>
                        </div>

                        {/* Alert for high consumption */}
                        {(capacity.consumptionPercent ?? 0) >= 80 && (
                          <div className={`mt-3 p-3 rounded-lg flex items-start gap-2 ${
                            (capacity.consumptionPercent ?? 0) >= 90
                              ? 'bg-red-50 border border-red-200'
                              : 'bg-orange-50 border border-orange-200'
                          }`}>
                            <i className={`fas fa-exclamation-triangle text-sm mt-0.5 ${
                              (capacity.consumptionPercent ?? 0) >= 90 ? 'text-red-600' : 'text-orange-600'
                            }`}></i>
                            <div className="flex-1">
                              <p className={`text-xs font-medium ${
                                (capacity.consumptionPercent ?? 0) >= 90 ? 'text-red-800' : 'text-orange-800'
                              }`}>
                                {(capacity.consumptionPercent ?? 0) >= 90
                                  ? 'Critical: Capacity almost depleted'
                                  : 'Warning: Capacity running low'}
                              </p>
                              <p className={`text-xs mt-1 ${
                                (capacity.consumptionPercent ?? 0) >= 90 ? 'text-red-700' : 'text-orange-700'
                              }`}>
                                {capacity.autoRenewal
                                  ? 'Auto-renewal is enabled and will activate soon.'
                                  : 'Consider extending capacity or enabling auto-renewal.'}
                              </p>
                            </div>
                          </div>
                        )}
                      </div>
                    </div>
                  </div>
                )}

                {/* Activity */}
                <div className="rounded-xl border border-gray-200 overflow-hidden">
                  <div className="bg-gray-50 px-5 py-3 border-b border-gray-200">
                    <h3 className="text-sm font-semibold text-gray-700 uppercase tracking-wide">Activity</h3>
                  </div>
                  <div className="p-5">
                    {/* Only show activity for purchased policies */}
                    {capacity.instanceState?.toUpperCase().includes('NOT_PURCHASED') ||
                     capacity.policyOperatingState?.toUpperCase().includes('NOT_PURCHASED') ? (
                      <div className="text-center py-4">
                        <p className="text-sm text-gray-500">
                          This policy was not purchased. No activity history available.
                        </p>
                      </div>
                    ) : (
                      <div className="space-y-3">
                        <div className="flex gap-3">
                          <span className="text-xs text-gray-500 whitespace-nowrap flex-shrink-0">
                            {formatDateTime(capacity.createdDate || capacity.startDate)}
                          </span>
                          <span className="text-xs text-gray-700">
                            Policy purchased successfully at a cost of {formatCurrency(capacity.ourPrice || capacity.totalCost)}
                            {capacity.totalSavings ? ` with savings of ${formatCurrency(capacity.totalSavings)}.` : '.'}
                          </span>
                        </div>
                        <div className="flex gap-3">
                          <span className="text-xs text-gray-500 whitespace-nowrap flex-shrink-0">
                            {formatDateTime(capacity.createdDate || capacity.startDate)}
                          </span>
                          <span className="text-xs text-gray-700">
                            Capacity is available; policy is ready for use.
                          </span>
                        </div>
                        <div className="flex gap-3">
                          <span className="text-xs text-gray-500 whitespace-nowrap flex-shrink-0">
                            {formatDateTime(capacity.createdDate || capacity.startDate)}
                          </span>
                          <span className="text-xs text-gray-700">
                            Policy created successfully.
                          </span>
                        </div>
                      </div>
                    )}
                  </div>
                </div>
              </div>

              {/* ── RIGHT COLUMN ── */}
              <div className="space-y-6">

                {/* Auto-Renewal Settings */}
                <div className="rounded-xl border border-gray-200 overflow-hidden">
                  <div className="bg-gray-50 px-5 py-3 border-b border-gray-200">
                    <h3 className="text-sm font-semibold text-gray-700 uppercase tracking-wide">Auto-Renewal Settings</h3>
                  </div>
                  <div className="px-5 py-1">
                    <Row label="Auto-renewal enabled" value={capacity.autoRenewal ? 'Yes' : 'No'} />
                    <Row label="Frequency of renewal" value={capacity.frequency || '-'} />
                  </div>
                </div>

                {/* Cost and Savings */}
                <div className="rounded-xl border border-gray-200 overflow-hidden">
                  <div className="bg-gray-50 px-5 py-3 border-b border-gray-200">
                    <h3 className="text-sm font-semibold text-gray-700 uppercase tracking-wide">Cost and Savings</h3>
                  </div>
                  <div className="px-5 py-1">
                    <Row label="Our price" value={formatCurrency(capacity.ourPrice || capacity.totalCost)} />
                    <Row label="AWS price" value={formatCurrency(capacity.awsPrice || (capacity.totalCost + capacity.totalSavings))} />
                    <Row label="Your savings" value={
                      <span className="font-bold text-green-600">{formatCurrency(capacity.totalSavings)}</span>
                    } />
                    <Row label="Savings %" value={savingsPercentDisplay != null ? `${savingsPercentDisplay.toFixed(1)}%` : '-'} />
                  </div>
                </div>

                {/* Region / Zone ID */}
                <div className="rounded-xl border border-gray-200 overflow-hidden">
                  <div className="bg-gray-50 px-5 py-3 border-b border-gray-200">
                    <h3 className="text-sm font-semibold text-gray-700 uppercase tracking-wide">Region/Zone ID</h3>
                  </div>
                  <div className="px-5 py-1">
                    <Row label="Region Code" value={capacity.region || capacity.regionZoneId?.split('/')[0]?.trim()} />
                    <Row label="Zone ID" value={capacity.zoneId || capacity.regionZoneId?.split('/')[1]?.trim()} />
                  </div>
                </div>

                {/* Capacity Tracker */}
                <div className="rounded-xl border border-gray-200 overflow-hidden">
                  <div className="bg-gray-50 px-5 py-3 border-b border-gray-200">
                    <h3 className="text-sm font-semibold text-gray-700 uppercase tracking-wide">Capacity Tracker</h3>
                  </div>
                  <div className="p-5">
                    {/* Only show capacity tracker for purchased policies */}
                    {capacity.instanceState?.toUpperCase().includes('NOT_PURCHASED') ||
                     capacity.policyOperatingState?.toUpperCase().includes('NOT_PURCHASED') ? (
                      <div className="text-center py-8">
                        <i className="fas fa-ban text-4xl text-gray-300 mb-3"></i>
                        <p className="text-sm text-gray-500 font-medium">
                          No capacity tracking available
                        </p>
                        <p className="text-xs text-gray-400 mt-1">
                          This policy was not purchased
                        </p>
                      </div>
                    ) : (
                      <>
                        {/* Failure banner */}
                        {isFailed(capacity.instanceState) && (
                          <div className="mb-4 flex items-start gap-3 bg-red-50 border border-red-200 rounded-lg px-4 py-3">
                            <i className="fas fa-times-circle text-red-500 mt-0.5 flex-shrink-0"></i>
                            <div>
                              <p className="text-sm font-semibold text-red-700">Capacity failed to start</p>
                              <p className="text-xs text-red-600 mt-0.5">
                                State: {capacity.instanceState}. Please check your VPC, subnet, and security group settings for the selected availability zone, then try scheduling again.
                              </p>
                            </div>
                          </div>
                        )}
                        {CAPACITY_TRACKER_STEPS.map((step, index) => {
                          const isActive = index === activeStep;
                          const isCompleted = index < activeStep;
                          const isFail = isActive && isFailed(capacity.instanceState);

                          return (
                            <div key={index} className="flex gap-4">
                              {/* Timeline line + circle */}
                              <div className="flex flex-col items-center">
                                <div className={`w-9 h-9 rounded-full flex items-center justify-center text-sm font-bold flex-shrink-0 ${
                                  isFail
                                    ? 'bg-red-500 text-white'
                                    : isCompleted
                                    ? 'bg-blue-600 text-white'
                                    : isActive
                                    ? 'bg-blue-600 text-white'
                                    : 'bg-gray-200 text-gray-500'
                                }`}>
                                  {isFail ? <i className="fas fa-times text-xs"></i> : isCompleted ? <i className="fas fa-check text-xs"></i> : index + 1}
                                </div>
                                {index < CAPACITY_TRACKER_STEPS.length - 1 && (
                                  <div className={`w-0.5 h-8 my-1 ${isCompleted ? 'bg-blue-500' : 'bg-gray-200'}`} />
                                )}
                              </div>

                              {/* Step label */}
                              <div className="pt-2 pb-4 flex-1 min-w-0">
                                <p className={`text-sm font-medium ${isFail ? 'text-red-600' : isActive ? 'text-blue-600' : isCompleted ? 'text-gray-700' : 'text-gray-400'}`}>
                                  {step.label}
                                </p>
                                <p className="text-xs text-gray-400">STEP {index + 1}</p>
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
              <div className="mt-6 rounded-xl border border-gray-200 overflow-hidden">
                <div className="bg-gray-50 px-5 py-3 border-b border-gray-200">
                  <h3 className="text-sm font-semibold text-gray-700 uppercase tracking-wide">Activity</h3>
                </div>
                <div className="divide-y divide-gray-100">
                  {activityLog.map((entry, i) => (
                    <div key={i} className="flex items-start gap-4 px-5 py-3">
                      <span className="text-xs text-gray-400 whitespace-nowrap pt-0.5 w-44 flex-shrink-0">
                        {entry.createdDate ? new Date(entry.createdDate).toLocaleString('en-US', { dateStyle: 'medium', timeStyle: 'short', timeZoneName: 'short' }) : '-'}
                      </span>
                      <span className={`text-sm ${entry.message?.toLowerCase().includes('fail') || entry.message?.toLowerCase().includes('error') ? 'text-red-600 font-medium' : 'text-gray-700'}`}>
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

      {/* Extend Usage Modal (stacked on top) */}
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
