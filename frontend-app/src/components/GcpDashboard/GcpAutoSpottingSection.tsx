import React, { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { fetchGcpAutoSpottingMigs } from '../../api/gcpDashboardApi';

interface GcpAutoSpottingSectionProps {
    accountId: string;
}

const GcpAutoSpottingSection: React.FC<GcpAutoSpottingSectionProps> = ({ accountId }) => {
    const navigate = useNavigate();
    const [migs, setMigs] = useState<any[]>([]);
    const [loading, setLoading] = useState(true);

    const loadData = async () => {
        try {
            setLoading(true);
            const data = await fetchGcpAutoSpottingMigs(accountId);
            setMigs(Array.isArray(data) ? data : []);
        } catch (error) {
            console.error('Failed to load MIGs', error);
        } finally {
            setLoading(false);
        }
    };

    useEffect(() => {
        if (accountId) {
            loadData();
        }
    }, [accountId]);

    const handleRowClick = (mig: any) => {
        navigate('/gcp-autospotting-manage', {
            state: {
                accountId,
                mig
            }
        });
    };

    if (loading) {
        return (
            <div className="glass-card p-6 animate-pulse">
                <div className="h-6 w-48 bg-gray-200 rounded mb-4"></div>
                <div className="space-y-3">
                    <div className="h-10 bg-gray-100 rounded"></div>
                    <div className="h-10 bg-gray-100 rounded"></div>
                    <div className="h-10 bg-gray-100 rounded"></div>
                </div>
            </div>
        );
    }

    return (
        <div className="glass-card p-6 rounded-2xl border border-gray-200 shadow-xl relative overflow-hidden bg-white/50 backdrop-blur-md hover:shadow-2xl hover:border-indigo-300 transition-all cursor-pointer group">
            <div className="flex items-center justify-between mb-6">
                <button
                    className="flex items-center gap-3 flex-1 text-left"
                    onClick={() => navigate('/gcp-autospotting')}
                    aria-label="View full GCP AutoSpotting page"
                >
                    <div className="w-10 h-10 bg-indigo-50 rounded-xl flex items-center justify-center border border-indigo-100 shadow-sm group-hover:bg-indigo-100 transition-colors">
                        <svg className="w-6 h-6 text-indigo-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M13 10V3L4 14h7v7l9-11h-7z" />
                        </svg>
                    </div>
                    <div className="flex-1">
                        <div className="flex items-center gap-2">
                            <h3 className="text-xl font-black text-gray-900 tracking-tight uppercase group-hover:text-indigo-600 transition-colors">GCP AutoSpotting (MIGs)</h3>
                            <svg className="w-4 h-4 text-indigo-400 opacity-0 group-hover:opacity-100 transition-opacity" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 5l7 7-7 7" />
                            </svg>
                        </div>
                        <p className="text-[10px] text-gray-400 font-bold uppercase tracking-widest mt-0.5">Manage Spot Provisioning for Managed Instance Groups</p>
                    </div>
                </button>
                <button
                    onClick={(e) => {
                        e.stopPropagation();
                        loadData();
                    }}
                    className="p-2 hover:bg-gray-100 rounded-full transition-colors"
                >
                    <svg className="w-5 h-5 text-gray-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15" />
                    </svg>
                </button>
            </div>

            <div className="overflow-x-auto">
                <table className="w-full">
                    <thead>
                        <tr className="text-left border-b border-gray-100">
                            <th className="pb-3 text-xs font-black text-gray-400 uppercase tracking-widest">Group Name</th>
                            <th className="pb-3 text-xs font-black text-gray-400 uppercase tracking-widest">Region/Zone</th>
                            <th className="pb-3 text-xs font-black text-gray-400 uppercase tracking-widest">Instances</th>
                            <th className="pb-3 text-xs font-black text-gray-400 uppercase tracking-widest text-right">Potential Savings</th>
                            <th className="pb-3 text-xs font-black text-gray-400 uppercase tracking-widest text-center">Status</th>
                        </tr>
                    </thead>
                    <tbody className="divide-y divide-gray-50">
                        {migs.map((mig) => (
                            <tr
                                key={mig.name}
                                onClick={(e) => {
                                    e.stopPropagation();
                                    handleRowClick(mig);
                                }}
                                className="hover:bg-indigo-50/30 transition-colors cursor-pointer"
                            >
                                <td className="py-4">
                                    <div className="font-bold text-gray-800">{mig.name}</div>
                                    <div className="text-[10px] text-gray-400 font-mono">Managed Instance Group</div>
                                </td>
                                <td className="py-4 text-sm text-gray-600 font-medium">{mig.region}</td>
                                <td className="py-4">
                                    <div className="flex items-center gap-2">
                                        <span className="text-xs font-bold text-gray-700">{mig.instanceCount}</span>
                                        {mig.spotInstanceCount > 0 && (
                                            <span className="bg-green-100 text-green-700 text-[10px] px-1.5 py-0.5 rounded font-bold border border-green-200">
                                                {mig.spotInstanceCount} SPOT
                                            </span>
                                        )}
                                    </div>
                                </td>
                                <td className="py-4 text-right">
                                    <div className="text-sm font-bold text-green-600">
                                        +₹{mig.potentialHourlySavings ? mig.potentialHourlySavings.toFixed(2) : '0.00'}/hr
                                    </div>
                                </td>
                                <td className="py-4 text-center">
                                    {mig.isEnabled ? (
                                        <span className="inline-flex items-center gap-1.5 px-3 py-1 bg-green-100 text-green-700 rounded-lg text-xs font-black border border-green-200">
                                            <svg className="w-3.5 h-3.5" fill="currentColor" viewBox="0 0 20 20">
                                                <path fillRule="evenodd" d="M16.707 5.293a1 1 0 010 1.414l-8 8a1 1 0 01-1.414 0l-4-4a1 1 0 011.414-1.414L8 12.586l7.293-7.293a1 1 0 011.414 0z" clipRule="evenodd" />
                                            </svg>
                                            ENABLED
                                        </span>
                                    ) : (
                                        <span className="inline-flex items-center px-3 py-1 bg-gray-100 text-gray-500 rounded-lg text-xs font-black border border-gray-200">
                                            DISABLED
                                        </span>
                                    )}
                                </td>
                            </tr>
                        ))}
                        {migs.length === 0 && (
                            <tr>
                                <td colSpan={5} className="py-12 text-center text-gray-400 italic font-medium">
                                    No Managed Instance Groups found in this project.
                                </td>
                            </tr>
                        )}
                    </tbody>
                </table>
            </div>
        </div>
    );
};

export default GcpAutoSpottingSection;
