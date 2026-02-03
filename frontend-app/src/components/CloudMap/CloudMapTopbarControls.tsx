/**
 * CloudMap Topbar Controls
 * 
 * Controls for CloudMap that integrate into the main topbar
 * Includes mode switching, VPC selector, blueprints, and action buttons
 */

import React from 'react';
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
        <div className="flex items-center gap-3">
            {/* Mode Switcher */}
            <div className="flex bg-gray-100 p-1 rounded-xl shadow-sm">
                <button
                    onClick={() => onModeSwitch('live')}
                    className={`px-3 py-1.5 text-xs font-semibold rounded-lg transition-all ${mode === 'live'
                            ? 'bg-white text-indigo-600 shadow-sm'
                            : 'text-gray-600 hover:text-gray-900'
                        }`}
                >
                    <i className="fas fa-network-wired text-sm mr-1.5" />
                    Live View
                </button>
                <button
                    onClick={() => onModeSwitch('design')}
                    className={`px-3 py-1.5 text-xs font-semibold rounded-lg transition-all ${mode === 'design'
                            ? 'bg-white text-indigo-600 shadow-sm'
                            : 'text-gray-600 hover:text-gray-900'
                        }`}
                >
                    <i className="fas fa-pencil-ruler text-sm mr-1.5" />
                    Design Studio
                </button>
            </div>

            {/* Live Mode Controls */}
            {mode === 'live' && (
                <>
                    <div className="flex items-center gap-2 px-3 py-1.5 bg-green-50 rounded-lg border border-green-100">
                        <div className="w-2 h-2 bg-green-500 rounded-full animate-pulse" />
                        <span className="text-xs font-semibold text-green-700">Live</span>
                    </div>

                    <select
                        id="vpc-selector"
                        value={selectedVpcValue}
                        onChange={onVpcChange}
                        className="px-3 py-1.5 text-xs font-medium bg-white border border-gray-200 rounded-lg focus:outline-none focus:ring-2 focus:ring-indigo-500 focus:border-transparent"
                    >
                        {loadingVpcs && <option value="">Loading...</option>}
                        {!loadingVpcs && vpcs.length === 0 && <option value="">🌐 Global & S3</option>}
                        {!loadingVpcs && vpcs.length > 0 && (
                            <>
                                <option value="ALL">🏢 All VPCs</option>
                                {vpcs.map((vpc) => (
                                    <option key={vpc.id} value={`${vpc.id}|${vpc.region}`}>
                                        {vpc.name} ({vpc.id} - {vpc.region})
                                    </option>
                                ))}
                                <option value="">🌐 Global & S3</option>
                            </>
                        )}
                    </select>

                    <button
                        onClick={onExportPng}
                        className="px-3 py-1.5 text-xs font-semibold bg-indigo-500 text-white rounded-lg hover:bg-indigo-600 transition-colors flex items-center gap-1.5"
                    >
                        <i className="fas fa-download" />
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
                        className="px-3 py-1.5 text-xs font-medium bg-white border border-gray-200 rounded-lg focus:outline-none focus:ring-2 focus:ring-indigo-500 focus:border-transparent"
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
                        className="px-3 py-1.5 text-xs font-semibold bg-indigo-500 text-white rounded-lg hover:bg-indigo-600 transition-colors disabled:opacity-50 disabled:cursor-not-allowed flex items-center gap-1.5"
                    >
                        <i className="fas fa-save" />
                        {savingBlueprint ? 'Saving...' : 'Save'}
                    </button>

                    <button
                        onClick={onClearCanvas}
                        className="px-3 py-1.5 text-xs font-semibold bg-rose-500 text-white rounded-lg hover:bg-rose-600 transition-colors flex items-center gap-1.5"
                    >
                        <i className="fas fa-trash" />
                        Clear
                    </button>
                </>
            )}
        </div>
    );
};

export default CloudMapTopbarControls;
