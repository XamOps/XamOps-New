import React from 'react';
import { useNavigate } from 'react-router-dom';
import { Icon } from '@iconify/react';
import type { GcpResourceType } from '../../types/gcpDashboard';

interface GcpInventorySectionProps {
    resources: GcpResourceType[];
    totalResources: number;
    loading: boolean;
    accountIds: string[];
}

interface ResourceCardProps {
    name: string;
    count: number;
    type: string;
    accountIds: string[];
}

// Map GCP service type → { iconify icon name, color }
const getIconMeta = (type: string): { iconName: string; iconColor: string } => {
    const t = type.toLowerCase();
    if (t.includes('compute') || t.includes('gce') || t.includes('instance'))
        return { iconName: 'mdi:server', iconColor: '#4285F4' };
    if (t.includes('storage') || t.includes('bucket'))
        return { iconName: 'mdi:bucket', iconColor: '#34A853' };
    if (t.includes('sql') || t.includes('cloud sql'))
        return { iconName: 'mdi:database', iconColor: '#527FFF' };
    if (t.includes('kubernetes') || t.includes('gke'))
        return { iconName: 'simple-icons:kubernetes', iconColor: '#326CE5' };
    if (t.includes('vpc') || t.includes('network'))
        return { iconName: 'mdi:network', iconColor: '#8C4FFF' };
    if (t.includes('dns'))
        return { iconName: 'mdi:dns', iconColor: '#FBBC05' };
    if (t.includes('load') || t.includes('balancer'))
        return { iconName: 'mdi:scale-balance', iconColor: '#4285F4' };
    if (t.includes('nat') || t.includes('router'))
        return { iconName: 'mdi:router-network', iconColor: '#34A853' };
    if (t.includes('artifact') || t.includes('registry'))
        return { iconName: 'mdi:package-variant-closed', iconColor: '#4285F4' };
    if (t.includes('kms') || t.includes('key'))
        return { iconName: 'mdi:key-variant', iconColor: '#EA4335' };
    if (t.includes('function'))
        return { iconName: 'mdi:function-variant', iconColor: '#FF9900' };
    if (t.includes('build'))
        return { iconName: 'mdi:hammer-wrench', iconColor: '#4285F4' };
    if (t.includes('secret'))
        return { iconName: 'mdi:shield-lock', iconColor: '#EA4335' };
    if (t.includes('armor'))
        return { iconName: 'mdi:shield-check', iconColor: '#34A853' };
    return { iconName: 'simple-icons:googlecloud', iconColor: '#4285F4' };
};

const ResourceCard: React.FC<ResourceCardProps & { animIndex: number }> = ({ name, count, type, accountIds, animIndex }) => {
    const navigate = useNavigate();
    const { iconName, iconColor } = getIconMeta(type);

    return (
        <button
            onClick={() => navigate(`/gcp-cloudlist?service=${encodeURIComponent(type)}&accountIds=${accountIds.join(',')}`)}
            className="w-full flex items-center justify-between px-3 py-2.5 hover:bg-[rgba(0,0,0,0.025)] transition-colors text-left group"
            style={{ animation: `fadeInUp 0.4s cubic-bezier(0.22, 1, 0.36, 1) ${animIndex * 0.055}s both` }}
        >
            <div className="flex items-center gap-3">
                <div className="w-9 h-9 rounded-lg flex items-center justify-center flex-shrink-0" style={{ backgroundColor: `${iconColor}14` }}>
                    <Icon icon={iconName} width={20} height={20} color={iconColor} />
                </div>
                <div>
                    <p className="text-[14px] font-normal text-[rgba(0,0,0,0.88)] leading-5">{name}</p>
                    <p className="text-[10px] text-[rgba(0,0,0,0.35)] uppercase tracking-wide leading-tight">Active Count</p>
                </div>
            </div>
            <span
                className="text-[24px] font-extrabold text-[rgba(0,0,0,0.88)] tabular-nums flex-shrink-0"
                style={{ fontFamily: "'Barlow Condensed', 'Inter', sans-serif", fontVariantNumeric: 'tabular-nums', letterSpacing: '-0.01em' }}
            >
                {count}
            </span>
        </button>
    );
};

const GcpInventorySection: React.FC<GcpInventorySectionProps> = ({ resources, loading, accountIds }) => {
    return (
        <div className="lg:col-span-2 bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm flex flex-col overflow-hidden h-full widget-panel-hover">
            {/* Header — identical to AWS */}
            <div className="flex items-center justify-between px-5 pt-5 pb-4 flex-shrink-0">
                <div>
                    <p className="text-[15px] font-semibold text-[rgba(0,0,0,0.88)]">Resource Inventory</p>
                    <p className="text-[11px] text-[rgba(0,0,0,0.4)] flex items-center gap-1.5 mt-0.5">
                        <span className="w-1.5 h-1.5 rounded-full bg-[#1BE68B] animate-pulse inline-block"></span>
                        Live Infrastructure
                    </p>
                </div>
                <div className="w-7 h-7 flex items-center justify-center rounded-md border border-[rgba(0,0,0,0.06)]">
                    <svg className={`w-3.5 h-3.5 text-[rgba(0,0,0,0.3)] ${loading ? 'animate-spin' : ''}`} fill="none" stroke="currentColor" viewBox="0 0 24 24">
                        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15" />
                    </svg>
                </div>
            </div>

            {/* List */}
            {loading ? (
                <div className="px-5 pb-5 space-y-2">
                    {[1,2,3,4,5].map(i => (
                        <div key={i} className="h-[46px] bg-[rgba(0,0,0,0.04)] rounded-lg animate-pulse"></div>
                    ))}
                </div>
            ) : (
                <div className="flex-1 overflow-y-auto inventory-hide-scrollbar" style={{ scrollbarWidth: 'none' }}>
                    {resources.filter(r => r.count > 0).length > 0 ? (
                        resources.filter(r => r.count > 0).map((resource, idx) => (
                            <ResourceCard key={resource.type} name={resource.name} count={resource.count} type={resource.type} accountIds={accountIds} animIndex={idx} />
                        ))
                    ) : (
                        <div className="py-12 text-center">
                            <p className="text-[13px] text-[rgba(0,0,0,0.35)]">No active resources found</p>
                        </div>
                    )}
                </div>
            )}
        </div>
    );
};

export default GcpInventorySection;
