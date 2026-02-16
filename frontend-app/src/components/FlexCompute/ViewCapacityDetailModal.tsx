/**
 * View Capacity Detail Modal
 *
 * Displays comprehensive capacity information matching the CloudiDR detail view
 */

import React, { useState } from 'react';
import type { FlexComputeCapacity } from '../../types/flexCompute';
import ExtendUsageModal from './ExtendUsageModal';

interface ViewCapacityDetailModalProps {
  capacity: FlexComputeCapacity | null;
  onClose: () => void;
  onExtend: (cidrId: string, endDate: string, endTime: string, utcOffset: string) => Promise<boolean>;
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

const getActiveStep = (instanceState?: string): number => {
  const state = (instanceState || '').toUpperCase().replace(/ /g, '_');
  const idx = CAPACITY_TRACKER_STEPS.findIndex((s) =>
    s.states.some((st) => state.includes(st))
  );
  return idx >= 0 ? idx : 0;
};

const ViewCapacityDetailModal: React.FC<ViewCapacityDetailModalProps> = ({ capacity, onClose, onExtend }) => {
  const [showExtendModal, setShowExtendModal] = useState(false);

  if (!capacity) return null;

  const activeStep = getActiveStep(capacity.instanceState);

  const Row: React.FC<{ label: string; value: React.ReactNode }> = ({ label, value }) => (
    <div className="flex items-center justify-between py-3 border-b border-gray-100 last:border-0">
      <span className="text-sm text-gray-500">{label}:</span>
      <span className="text-sm font-medium text-gray-900 text-right max-w-xs">{value || '-'}</span>
    </div>
  );

  return (
    <div className="fixed inset-0 z-50 overflow-y-auto">
      {/* Backdrop */}
      <div className="fixed inset-0 bg-black/50" onClick={onClose} />

      <div className="relative min-h-screen flex items-start justify-center p-4 pt-8">
        <div className="relative bg-white rounded-2xl shadow-2xl w-full max-w-6xl">
          {/* Header */}
          <div className="sticky top-0 bg-white border-b border-gray-200 px-6 py-4 flex items-center justify-between rounded-t-2xl z-10">
            <div className="flex items-center gap-3">
              <div className="w-9 h-9 bg-blue-100 rounded-lg flex items-center justify-center">
                <i className="fas fa-server text-blue-600 text-sm"></i>
              </div>
              <h2 className="text-lg font-bold text-gray-900">{capacity.name}</h2>
            </div>
            <div className="flex items-center gap-2">
              <button className="px-4 py-2 text-sm text-gray-700 bg-gray-100 rounded-lg hover:bg-gray-200 transition-colors flex items-center gap-2">
                <i className="fas fa-sync-alt text-xs"></i> Refresh
              </button>
              <button
                onClick={() => setShowExtendModal(true)}
                className="px-4 py-2 text-sm text-white bg-purple-600 rounded-lg hover:bg-purple-700 transition-colors flex items-center gap-2"
              >
                <i className="fas fa-clock text-xs"></i> Extend Usage
              </button>
              <button
                onClick={onClose}
                className="w-8 h-8 flex items-center justify-center text-gray-400 hover:text-gray-700 rounded-lg hover:bg-gray-100 transition-colors"
              >
                <i className="fas fa-times"></i>
              </button>
            </div>
          </div>

          {/* Body — two-column layout */}
          <div className="p-6 grid grid-cols-1 lg:grid-cols-2 gap-6">

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
                  <Row label="Platform" value={capacity.platform} />
                  <Row label="Total Capacity" value={capacity.quantities} />
                  <Row label="Tenancy" value={capacity.tenancy} />
                </div>
              </div>

              {/* Configuration */}
              <div className="rounded-xl border border-gray-200 overflow-hidden">
                <div className="bg-gray-50 px-5 py-3 border-b border-gray-200">
                  <h3 className="text-sm font-semibold text-gray-700 uppercase tracking-wide">Configuration</h3>
                </div>
                <div className="px-5 py-1">
                  <Row label="VPC ID" value={<span className="font-mono text-xs">{capacity.vpc || 'default'}</span>} />
                  <Row label="Security Groups ID" value={<span className="font-mono text-xs">{capacity.securityGroups || 'none'}</span>} />
                  <Row label="SSH Pair Key Name" value={<span className="font-mono text-xs">{capacity.sshPairKey || 'none'}</span>} />
                  <Row label="AMI Image ID" value={<span className="font-mono text-xs">{capacity.amiImage || 'default'}</span>} />
                  <Row label="EBS Volume Size (GiB)" value={capacity.ebsVolumeSize ? `${capacity.ebsVolumeSize} GiB` : '10 GiB'} />
                  <Row label="Start Time" value={formatDateTime(capacity.startDate)} />
                  <Row label="End time" value={formatDateTime(capacity.endDate)} />
                </div>
              </div>

              {/* Activity */}
              <div className="rounded-xl border border-gray-200 overflow-hidden">
                <div className="bg-gray-50 px-5 py-3 border-b border-gray-200">
                  <h3 className="text-sm font-semibold text-gray-700 uppercase tracking-wide">Activity</h3>
                </div>
                <div className="p-5">
                  <table className="w-full">
                    <thead>
                      <tr className="border-b border-gray-200">
                        <th className="text-left text-xs font-semibold text-gray-500 pb-3 pr-4">Created Date</th>
                        <th className="text-left text-xs font-semibold text-gray-500 pb-3">Message</th>
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-gray-100">
                      <tr>
                        <td className="py-3 pr-4 text-xs text-gray-700 whitespace-nowrap">
                          {formatDateTime(capacity.createdDate || capacity.startDate)}
                        </td>
                        <td className="py-3 text-xs text-gray-600">
                          Policy purchased successfully at a cost of {formatCurrency(capacity.ourPrice)} with savings of {formatCurrency(capacity.totalSavings)}.
                        </td>
                      </tr>
                      <tr>
                        <td className="py-3 pr-4 text-xs text-gray-700 whitespace-nowrap">
                          {formatDateTime(capacity.createdDate || capacity.startDate)}
                        </td>
                        <td className="py-3 text-xs text-gray-600">Capacity is available; policy is ready for purchase.</td>
                      </tr>
                      <tr>
                        <td className="py-3 pr-4 text-xs text-gray-700 whitespace-nowrap">
                          {formatDateTime(capacity.createdDate || capacity.startDate)}
                        </td>
                        <td className="py-3 text-xs text-gray-600">Policy created successfully.</td>
                      </tr>
                    </tbody>
                  </table>
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
                  <h3 className="text-sm font-semibold text-gray-700 uppercase tracking-wide">Cost and savings</h3>
                </div>
                <div className="px-5 py-1">
                  <Row label="Our price" value={formatCurrency(capacity.ourPrice || capacity.totalCost)} />
                  <Row label="AWS price" value={formatCurrency(capacity.awsPrice || (capacity.totalCost + capacity.totalSavings))} />
                  <Row label="Your savings" value={
                    <span className="font-bold text-blue-600">{formatCurrency(capacity.totalSavings)}</span>
                  } />
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
                  {CAPACITY_TRACKER_STEPS.map((step, index) => {
                    const isActive = index === activeStep;
                    const isCompleted = index < activeStep;

                    return (
                      <div key={index} className="flex gap-4">
                        {/* Timeline line + circle */}
                        <div className="flex flex-col items-center">
                          <div className={`w-9 h-9 rounded-full flex items-center justify-center text-sm font-bold flex-shrink-0 ${
                            isCompleted
                              ? 'bg-blue-600 text-white'
                              : isActive
                              ? 'bg-blue-600 text-white'
                              : 'bg-gray-200 text-gray-500'
                          }`}>
                            {isCompleted ? <i className="fas fa-check text-xs"></i> : index + 1}
                          </div>
                          {index < CAPACITY_TRACKER_STEPS.length - 1 && (
                            <div className={`w-0.5 h-8 my-1 ${isCompleted ? 'bg-blue-500' : 'bg-gray-200'}`} />
                          )}
                        </div>

                        {/* Step label */}
                        <div className="pt-2 pb-4">
                          <p className={`text-sm font-medium ${isActive ? 'text-blue-600' : isCompleted ? 'text-gray-700' : 'text-gray-400'}`}>
                            {step.label}
                          </p>
                          <p className="text-xs text-gray-400">STEP {index + 1}</p>
                        </div>
                      </div>
                    );
                  })}
                </div>
              </div>

            </div>
          </div>
        </div>
      </div>

      {/* Extend Usage Modal (stacked on top) */}
      {showExtendModal && (
        <ExtendUsageModal
          capacity={capacity}
          onExtend={onExtend}
          onClose={() => setShowExtendModal(false)}
        />
      )}
    </div>
  );
};

export default ViewCapacityDetailModal;
