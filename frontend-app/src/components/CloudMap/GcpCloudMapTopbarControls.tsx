/**
 * GCP CloudMap Topbar Controls
 * 
 * Controls for GCP CloudMap that integrate into the main topbar
 * Includes VPC selector and export button
 */

import React from 'react';

interface VPC {
    id: string;
    name: string;
}

interface GcpCloudMapTopbarControlsProps {
    vpcs: VPC[];
    selectedVpcValue: string;
    onVpcChange: (e: React.ChangeEvent<HTMLSelectElement>) => void;
    loadingVpcs: boolean;
    onExportPng: () => void;
    cyRef: React.RefObject<any>;
}

const GcpCloudMapTopbarControls: React.FC<GcpCloudMapTopbarControlsProps> = ({
    vpcs,
    selectedVpcValue,
    onVpcChange,
    loadingVpcs,
    onExportPng,
    cyRef,
}) => {
    return (
        <div className="flex items-center gap-3">
            {/* Live Indicator */}
            <div className="flex items-center gap-2 px-3 py-1.5 bg-green-50 rounded-lg border border-green-100">
                <div className="w-2 h-2 bg-green-500 rounded-full animate-pulse" />
                <span className="text-xs font-semibold text-green-700">Live Topology</span>
            </div>

            {/* VPC Selector */}
            <select
                id="vpc-selector"
                value={selectedVpcValue}
                onChange={onVpcChange}
                className="px-3 py-1.5 text-xs font-medium bg-white border border-gray-200 rounded-lg focus:outline-none focus:ring-2 focus:ring-indigo-500 focus:border-transparent"
                disabled={loadingVpcs}
            >
                {loadingVpcs && <option value="">Loading VPCs...</option>}
                {!loadingVpcs && (
                    <>
                        <option value="">🌐 Global & Unattached</option>
                        {vpcs.map((vpc) => (
                            <option key={vpc.id} value={vpc.id}>
                                {vpc.name} ({vpc.id})
                            </option>
                        ))}
                    </>
                )}
            </select>

            {/* Export Button */}
            <button
                onClick={onExportPng}
                disabled={!cyRef.current}
                className="px-3 py-1.5 text-xs font-semibold bg-indigo-500 text-white rounded-lg hover:bg-indigo-600 transition-colors disabled:opacity-50 disabled:cursor-not-allowed flex items-center gap-1.5"
            >
                <i className="fas fa-download" />
                Export PNG
            </button>
        </div>
    );
};

export default GcpCloudMapTopbarControls;
