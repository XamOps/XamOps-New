/**
 * CloudMap Topbar Controls
 *
 * Controls for CloudMap that integrate into the main topbar
 * Includes mode switching, VPC selector, blueprints, and action buttons
 */

import React from 'react';
import { ALL_VPCS_VALUE } from '../../types/cloudmap';
import type { CloudMapMode } from '../../types/cloudmap';

interface VPC {
    id: string;
    name: string;
    region: string;
}

interface Blueprint {
    id: string;
    name: string;
}

interface CloudMapTopbarControlsProps {
    mode: CloudMapMode;
    onModeSwitch: (mode: CloudMapMode) => void;

    // Live mode props
    vpcs: VPC[];
    selectedVpcValue: string;
    onVpcChange: (e: React.ChangeEvent<HTMLSelectElement>) => void;
    loadingVpcs: boolean;
    onExportPng: () => void;

    // Design mode props
    blueprints: Blueprint[];
    selectedBlueprintId: string | null;
    onBlueprintChange: (e: React.ChangeEvent<HTMLSelectElement>) => void;
    onSaveDesign: () => void;
    onClearCanvas: () => void;
    savingBlueprint: boolean;
}

const CloudMapTopbarControls: React.FC<CloudMapTopbarControlsProps> = ({
    mode,
    onModeSwitch,
    vpcs,
    selectedVpcValue,
    onVpcChange,
    loadingVpcs,
    onExportPng,
    blueprints,
    selectedBlueprintId,
    onBlueprintChange,
    onSaveDesign,
    onClearCanvas,
    savingBlueprint,
}) => {
    return (
        <div className="flex items-center gap-2">
            {/* Mode Switcher */}
            <div className="flex bg-gray-100 p-0.5 rounded-lg">
                <button
                    onClick={() => onModeSwitch('live')}
                    className={`px-3 py-1.5 text-xs font-semibold rounded-md transition-all ${mode === 'live'
                            ? 'bg-white text-gray-900 shadow-sm'
                            : 'text-gray-500 hover:text-gray-700'
                        }`}
                >
                    <i className="fas fa-network-wired text-xs mr-1.5" />
                    Live View
                </button>
                <button
                    onClick={() => onModeSwitch('design')}
                    className={`px-3 py-1.5 text-xs font-semibold rounded-md transition-all ${mode === 'design'
                            ? 'bg-white text-gray-900 shadow-sm'
                            : 'text-gray-500 hover:text-gray-700'
                        }`}
                >
                    <i className="fas fa-pencil-ruler text-xs mr-1.5" />
                    Design Studio
                </button>
            </div>

            <div className="w-px h-5 bg-gray-200" />

            {/* Live Mode Controls */}
            {mode === 'live' && (
                <>
                    <div className="flex items-center gap-1.5 px-2.5 py-1 bg-emerald-50 rounded-md border border-emerald-200">
                        <div className="w-1.5 h-1.5 bg-emerald-500 rounded-full animate-pulse" />
                        <span className="text-xs font-semibold text-emerald-700">Live</span>
                    </div>

                    <select
                        id="vpc-selector"
                        value={selectedVpcValue}
                        onChange={onVpcChange}
                        className="px-2.5 py-1.5 text-xs font-medium bg-white border border-gray-200 rounded-lg focus:outline-none focus:ring-1 focus:ring-gray-400 text-gray-700"
                    >
                        <option value={ALL_VPCS_VALUE}>All VPCs</option>
                        {loadingVpcs && <option value="" disabled>Loading...</option>}
                        {!loadingVpcs && vpcs.map((vpc) => (
                            <option key={vpc.id} value={`${vpc.id}|${vpc.region}`}>
                                {vpc.name} ({vpc.id} - {vpc.region})
                            </option>
                        ))}
                    </select>

                    <button
                        onClick={onExportPng}
                        className="px-3 py-1.5 text-xs font-semibold bg-gray-900 text-white rounded-lg hover:bg-gray-800 transition-colors flex items-center gap-1.5"
                    >
                        <i className="fas fa-download text-[10px]" />
                        Export PNG
                    </button>
                </>
            )}

            {/* Design Mode Controls */}
            {mode === 'design' && (
                <>
                    <select
                        value={selectedBlueprintId || ''}
                        onChange={onBlueprintChange}
                        className="px-2.5 py-1.5 text-xs font-medium bg-white border border-gray-200 rounded-lg focus:outline-none focus:ring-1 focus:ring-gray-400 text-gray-700 max-w-[180px]"
                    >
                        <option value="">-- New Design --</option>
                        {blueprints.map((bp) => (
                            <option key={bp.id} value={bp.id}>
                                {bp.name}
                            </option>
                        ))}
                    </select>

                    <button
                        onClick={onSaveDesign}
                        disabled={savingBlueprint}
                        className="px-3 py-1.5 text-xs font-semibold bg-gray-900 text-white rounded-lg hover:bg-gray-800 transition-colors disabled:opacity-50 disabled:cursor-not-allowed flex items-center gap-1.5"
                    >
                        <i className="fas fa-save text-[10px]" />
                        {savingBlueprint ? 'Saving…' : 'Save'}
                    </button>

                    <button
                        onClick={onClearCanvas}
                        className="px-3 py-1.5 text-xs font-semibold text-red-600 border border-red-200 bg-transparent rounded-lg hover:bg-red-50 transition-colors flex items-center gap-1.5"
                    >
                        <i className="fas fa-trash text-[10px]" />
                        Clear
                    </button>
                </>
            )}
        </div>
    );
};

export default CloudMapTopbarControls;
