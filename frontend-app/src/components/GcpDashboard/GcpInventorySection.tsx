import React from 'react';
import { useNavigate } from 'react-router-dom';
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
    icon: string; // Filename like 'compute-engine.png'
    type: string;
    accountIds: string[];
}

// Helper to get color/style based on service type
const getServiceStyle = (type: string) => {
    const typeLower = type.toLowerCase();

    if (typeLower.includes('compute') || typeLower.includes('instance')) return 'bg-blue-100 text-blue-600';
    if (typeLower.includes('storage') || typeLower.includes('bucket')) return 'bg-green-100 text-green-600';
    if (typeLower.includes('sql') || typeLower.includes('database')) return 'bg-indigo-100 text-indigo-600';
    if (typeLower.includes('kubernetes') || typeLower.includes('gke')) return 'bg-blue-50 text-blue-500'; // GKE Blue
    if (typeLower.includes('network') || typeLower.includes('vpc')) return 'bg-purple-100 text-purple-600';
    if (typeLower.includes('function')) return 'bg-orange-100 text-orange-600';
    if (typeLower.includes('dns')) return 'bg-yellow-100 text-yellow-600';

    return 'bg-gray-100 text-gray-600';
};

const ResourceCard: React.FC<ResourceCardProps> = ({ name, count, icon, type, accountIds }) => {
    const navigate = useNavigate();
    const styleClass = getServiceStyle(type);

    const handleClick = () => {
        navigate(`/gcp-cloudlist?service=${encodeURIComponent(type)}&accountIds=${accountIds.join(',')}`);
    };

    return (
        <button
            onClick={handleClick}
            onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') handleClick(); }}
            aria-label={`View ${name} resources`}
            className="group p-4 rounded-xl border border-gray-100 bg-white hover:border-blue-200 hover:shadow-lg hover:shadow-blue-500/10 cursor-pointer transition-all duration-300 flex items-center justify-between active:scale-[0.98] w-full text-left"
        >
            <div className="flex items-center space-x-3">
                <div className={`w-12 h-12 ${styleClass.split(' ')[0]} rounded-xl flex items-center justify-center shadow-inner group-hover:scale-110 transition-transform duration-300`}>
                    <img src={icon} alt={name} className="w-7 h-7 object-contain drop-shadow-sm" onError={(e) => (e.currentTarget.style.display = 'none')} />
                </div>
                <div>
                    <p className="text-xs font-black text-gray-400 uppercase tracking-widest leading-tight">{name}</p>
                    <div className="flex items-center mt-1">
                        <span className={`text-[10px] ${styleClass.split(' ')[1]} font-bold uppercase tracking-tight`}>Active Count</span>
                        <svg className={`w-2.5 h-2.5 ml-1 opacity-0 group-hover:opacity-100 transition-opacity ${styleClass.split(' ')[1]}`} fill="none" stroke="currentColor" viewBox="0 0 24 24">
                            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={3} d="M13 7l5 5m0 0l-5 5m5-5H6" />
                        </svg>
                    </div>
                </div>
            </div>
            <div className="text-right ml-4">
                <p className="text-3xl font-black text-gray-900 tracking-tight">{count}</p>
            </div>
        </button>
    );
};

const GcpInventorySection: React.FC<GcpInventorySectionProps> = ({ resources, totalResources, loading, accountIds }) => {
    return (
        <div className="lg:col-span-2 glass-card p-6 rounded-2xl border border-gray-200 shadow-xl shadow-blue-500/5 relative overflow-hidden h-full">
            <div className="flex items-center justify-between mb-8">
                <div className="flex items-center gap-3">
                    <div className="w-10 h-10 bg-blue-50 rounded-xl flex items-center justify-center border border-blue-100 shadow-sm">
                        <svg className="w-6 h-6 text-blue-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M19 11H5m14 0a2 2 0 012 2v6a2 2 0 012 2H5a2 2 0 01-2-2v-6a2 2 0 012-2m14 0V9a2 2 0 00-2-2M5 11V9a2 2 0 012-2m0 0V5a2 2 0 012-2h6a2 2 0 012 2v2M7 7h10" />
                        </svg>
                    </div>
                    <div>
                        <h3 className="text-xl font-black text-gray-900 tracking-tight uppercase px-1">Resource Inventory</h3>
                        <p className="text-[10px] text-gray-400 font-bold uppercase tracking-widest flex items-center gap-1.5 px-1 mt-0.5">
                            <span className="w-1.5 h-1.5 bg-blue-400 rounded-full animate-pulse"></span>&nbsp;Live Infrastructure
                        </p>
                    </div>
                </div>
                <div className="text-sm text-gray-500 bg-white/50 backdrop-blur-sm p-2 rounded-lg border border-gray-100 shadow-sm">
                    <span className="font-bold mr-1">{totalResources}</span>
                    <span className="text-xs">Total</span>
                </div>
            </div>

            {/* Inventory Skeleton */}
            {loading && (
                <div className="space-y-4 py-2 px-1">
                    {[1, 2, 3, 4, 5].map((i) => (
                        <div key={i} className="h-16 w-full skeleton rounded-xl"></div>
                    ))}
                </div>
            )}

            {/* Inventory Grid */}
            {!loading && (
                <div
                    className="flex flex-col gap-4 overflow-y-auto pb-2 custom-scrollbar fade-in px-1"
                    style={{ maxHeight: '420px' }}
                >
                    {resources.length > 0 ? (
                        resources.map((resource) => (
                            <ResourceCard key={resource.type} {...resource} accountIds={accountIds} />
                        ))
                    ) : (
                        <div className="col-span-full py-12 text-center">
                            <div className="w-16 h-16 bg-gray-50 rounded-full flex items-center justify-center mx-auto mb-4 border border-gray-100">
                                <svg className="w-8 h-8 text-gray-300" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                                    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M20 13V6a2 2 0 00-2-2H6a2 2 0 00-2 2v7m16 0v5a2 2 0 01-2 2H6a2 2 0 01-2-2v-5m16 0h-2.586a1 1 0 00-.707.293l-2.414 2.414a1 1 0 01-.707.293h-3.172a1 1 0 01-.707-.293l-2.414-2.414A1 1 0 006.586 13H4" />
                                </svg>
                            </div>
                            <p className="text-gray-500 font-bold text-xs uppercase tracking-widest">No Active Resources</p>
                            <p className="text-[10px] text-gray-400 mt-1">Found in this account</p>
                        </div>
                    )}
                </div>
            )}
        </div>
    );
};

export default GcpInventorySection;
