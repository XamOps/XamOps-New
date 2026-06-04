import React, { useState, useRef, useEffect, useLayoutEffect } from 'react';
import { Icon } from '@iconify/react';
import type { WastedResource } from '../../types/waste';

interface WasteResourceGroupProps {
  serviceType: string;
  resources: WastedResource[];
  isOpen: boolean;
  onToggle: () => void;
  canDelete: boolean;
  onDelete: (resource: WastedResource) => void;
}

const SERVICE_ICONS: Record<string, string> = {
  'EC2 Instance':        'mdi:server',
  'EBS Volume':          '/icons/aws-ebs-volume.svg',
  'RDS Instance':        'mdi:database',
  'Lambda':              '/icons/aws-lambda-function.svg',
  'S3 Bucket':           'mdi:bucket',
  'Load Balancer':       '/icons/aws-alb.svg',
  'Auto Scaling Group':  '/icons/aws-ec2-autoscaling.svg',
  'VPC':                 'mdi:lan',
  'Security Group':      'mdi:shield-lock-outline',
  'Route 53 Zone':       '/icons/aws-route53-zone.svg',
  'ElastiCache Cluster': 'mdi:lightning-bolt',
  'DynamoDB Table':      'mdi:table',
  'ECR Repository':      'mdi:package-variant-closed',
  'CloudTrail':          'mdi:clipboard-text-clock',
  'Certificate Manager': '/icons/aws-certificate-manager.svg',
  'CloudWatch Log Group':'/icons/aws-cloudwatch-logs.svg',
  'SNS Topic':           'mdi:bell-outline',
  'EventBridge':         '/icons/aws-eventbridge.svg',
  'SQS Queue':           'mdi:tray-full',
  'Elastic IP':          'mdi:ip-network',
  'Snapshot':            '/icons/ebs-snapshot.svg',
  'AMI':                 'mdi:disc',
  'ENI':                 'mdi:ethernet',
  'EKS Cluster':         'mdi:kubernetes',
  'ECS Cluster':         'mdi:layers-triple',
  'DB Snapshot':         '/icons/ebs-snapshot.svg',
  'Log Group':           '/icons/aws-cloudwatch-logs.svg',
  'EFS File System':     '/icons/aws-efs.svg',
};

const WasteResourceGroup: React.FC<WasteResourceGroupProps> = ({
  serviceType,
  resources,
  isOpen,
  onToggle,
  canDelete,
  onDelete,
}) => {
  const [expandedResources, setExpandedResources] = useState<Set<string>>(new Set());
  const panelRef = useRef<HTMLDivElement>(null);
  // Increments each time the accordion opens — forces row remount to replay stagger
  const [rowKey, setRowKey] = useState(0);

  // Set initial hidden state before first paint
  useLayoutEffect(() => {
    const panel = panelRef.current;
    if (!panel) return;
    panel.style.maxHeight = '0px';
    panel.style.overflow = 'hidden';
    panel.style.opacity = '0';
  }, []);

  useEffect(() => {
    const panel = panelRef.current;
    if (!panel) return;

    // Cancel any in-flight transition so rapid toggling doesn't glitch
    panel.style.transition = 'none';

    if (isOpen) {
      // Measure natural height with no constraint
      panel.style.overflow = 'hidden';
      panel.style.maxHeight = 'none';
      const h = panel.scrollHeight;
      panel.style.maxHeight = '0px';
      panel.style.opacity = '0';
      panel.getBoundingClientRect(); // force reflow
      panel.style.transition = 'max-height 0.32s cubic-bezier(0.4, 0, 0.2, 1), opacity 0.22s ease';
      panel.style.maxHeight = `${h}px`;
      panel.style.opacity = '1';
      setRowKey(k => k + 1);

      // After animation completes, remove max-height so inner row expansions aren't clipped
      const t = setTimeout(() => {
        if (panelRef.current) {
          panelRef.current.style.maxHeight = 'none';
          panelRef.current.style.overflow = 'visible';
        }
      }, 360);
      return () => clearTimeout(t);
    } else {
      // Capture current height (panel may be fully open with maxHeight: none)
      const h = panel.scrollHeight;
      panel.style.overflow = 'hidden';
      panel.style.maxHeight = `${h}px`;
      panel.style.opacity = '1';
      panel.getBoundingClientRect(); // force reflow
      panel.style.transition = 'max-height 0.28s cubic-bezier(0.4, 0, 0.2, 1), opacity 0.2s ease';
      panel.style.maxHeight = '0px';
      panel.style.opacity = '0';
    }
  }, [isOpen]);

  const totalSavings = resources.reduce((sum, r) => sum + (r.monthlySavings || 0), 0);
  const icon = SERVICE_ICONS[serviceType] || 'mdi:cube-outline';

  const toggleResource = (id: string) => {
    setExpandedResources(prev => {
      const next = new Set(prev);
      next.has(id) ? next.delete(id) : next.add(id);
      return next;
    });
  };

  return (
    <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm overflow-hidden widget-panel-hover">
      {/* Group header */}
      <div
        onClick={onToggle}
        className="flex items-center justify-between px-5 py-4 cursor-pointer hover:bg-[rgba(0,0,0,0.01)] transition-colors"
      >
        <div className="flex items-center gap-3">
          <svg
            className={`w-3.5 h-3.5 text-[rgba(0,0,0,0.3)] flex-shrink-0 transition-transform duration-200 ${isOpen ? 'rotate-90' : ''}`}
            fill="none" stroke="currentColor" viewBox="0 0 24 24"
          >
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 5l7 7-7 7" />
          </svg>
          <div className="w-8 h-8 rounded-lg flex items-center justify-center border border-[rgba(0,0,0,0.08)]" style={{ backgroundColor: '#DC262608' }}>
            {icon.startsWith('/')
              ? <img src={icon} width={16} height={16} style={{ objectFit: 'contain' }} />
              : <Icon icon={icon} width={16} color="#DC2626" />}
          </div>
          <div>
            <p className="text-[14px] font-semibold text-[rgba(0,0,0,0.88)]">{serviceType}</p>
            <p className="text-[11px] text-[rgba(0,0,0,0.4)]">{resources.length} resource{resources.length !== 1 ? 's' : ''}</p>
          </div>
        </div>
        <div className="flex items-center gap-3">
          <span className="text-[12px] font-semibold text-rose-600">
            ${totalSavings.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}/mo
          </span>
          <span className="text-[11px] font-semibold px-2 py-0.5 rounded-full bg-rose-50 text-rose-600 border border-rose-100">
            {resources.length}
          </span>
        </div>
      </div>

      {/* Resource panel — always rendered; animated via direct DOM manipulation */}
      <div ref={panelRef} className="border-t border-[rgba(0,0,0,0.06)]">
        {/* Table header */}
        <div className="grid grid-cols-12 px-5 py-2 bg-[rgba(0,0,0,0.02)] text-[11px] font-semibold uppercase tracking-wide text-[rgba(0,0,0,0.4)]">
          <div className="col-span-5">Resource</div>
          <div className="col-span-2">Region</div>
          <div className="col-span-2">Monthly Savings</div>
          <div className="col-span-3 text-right">Actions</div>
        </div>

        {resources.map((resource, index) => {
          const isExpanded = expandedResources.has(resource.resourceId);
          return (
            // rowKey forces remount on each open so stagger animations replay
            <div
              key={`${resource.resourceId}-${rowKey}`}
              className="border-t border-[rgba(0,0,0,0.04)]"
              style={{ animation: `fadeInUp 0.3s ease ${0.05 + index * 0.04}s both` }}
            >
              {/* Row */}
              <div
                onClick={() => toggleResource(resource.resourceId)}
                className="grid grid-cols-12 items-center px-5 py-3.5 cursor-pointer hover:bg-[rgba(0,0,0,0.01)] transition-colors text-[13px]"
              >
                <div className="col-span-5 flex items-center gap-2.5">
                  <svg
                    className={`w-3 h-3 text-[rgba(0,0,0,0.25)] flex-shrink-0 transition-transform duration-150 ${isExpanded ? 'rotate-90' : ''}`}
                    fill="none" stroke="currentColor" viewBox="0 0 24 24"
                  >
                    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 5l7 7-7 7" />
                  </svg>
                  <div className="min-w-0">
                    <p className="font-semibold text-[rgba(0,0,0,0.88)] truncate">{resource.resourceName || 'Untitled'}</p>
                    <p className="font-mono text-[11px] text-[rgba(0,0,0,0.4)] truncate">{resource.resourceId}</p>
                  </div>
                </div>
                <div className="col-span-2 text-[rgba(0,0,0,0.6)] font-medium">{resource.region}</div>
                <div className="col-span-2 font-semibold text-rose-600">
                  ${(resource.monthlySavings || 0).toFixed(2)}
                </div>
                <div className="col-span-3 flex justify-end gap-2" onClick={e => e.stopPropagation()}>
                  <button
                    onClick={() => onDelete(resource)}
                    disabled={!canDelete}
                    title={canDelete ? 'Delete resource' : 'Read-only access'}
                    className="flex items-center gap-1 px-2.5 py-1.5 rounded-lg text-[11px] font-semibold transition-colors disabled:opacity-40 disabled:cursor-not-allowed text-rose-600 border border-rose-200 hover:bg-rose-50"
                  >
                    <Icon icon="mdi:trash-can-outline" width={13} />
                    Delete
                  </button>
                  <button
                    disabled
                    title="Archive (Coming Soon)"
                    className="flex items-center gap-1 px-2.5 py-1.5 rounded-lg text-[11px] font-semibold border border-[rgba(0,0,0,0.1)] text-[rgba(0,0,0,0.4)] opacity-50 cursor-not-allowed"
                  >
                    <Icon icon="mdi:archive-outline" width={13} />
                    Archive
                  </button>
                </div>
              </div>

              {/* Expanded details */}
              {isExpanded && (
                <div className="px-14 py-4 bg-[rgba(0,0,0,0.015)] border-t border-[rgba(0,0,0,0.04)]">
                  <p className="text-[12px] font-semibold uppercase tracking-wide text-[rgba(0,0,0,0.4)] mb-3">Waste Details</p>
                  <div className="grid grid-cols-2 gap-x-8 gap-y-2 text-[13px]">
                    <div className="flex gap-2">
                      <span className="font-semibold text-[rgba(0,0,0,0.5)] min-w-[90px]">Reason:</span>
                      <span className="text-[rgba(0,0,0,0.75)]">{resource.reason}</span>
                    </div>
                    <div className="flex gap-2">
                      <span className="font-semibold text-[rgba(0,0,0,0.5)] min-w-[90px]">Monthly Cost:</span>
                      <span className="font-semibold text-rose-600">${(resource.monthlySavings || 0).toFixed(2)}</span>
                    </div>
                    <div className="flex gap-2">
                      <span className="font-semibold text-[rgba(0,0,0,0.5)] min-w-[90px]">Region:</span>
                      <span className="text-[rgba(0,0,0,0.75)]">{resource.region}</span>
                    </div>
                    <div className="flex gap-2">
                      <span className="font-semibold text-[rgba(0,0,0,0.5)] min-w-[90px]">Resource Type:</span>
                      <span className="text-[rgba(0,0,0,0.75)]">{resource.resourceType}</span>
                    </div>
                  </div>
                </div>
              )}
            </div>
          );
        })}
      </div>
    </div>
  );
};

export default WasteResourceGroup;
