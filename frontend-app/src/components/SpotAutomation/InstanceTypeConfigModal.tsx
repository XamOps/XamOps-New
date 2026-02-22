/**
 * Instance Type Configuration Modal Component
 *
 * Modal for configuring alternative instance types for Spot diversification
 */

import React, { useState, useEffect } from 'react';

interface InstanceTypeConfigModalProps {
  isOpen: boolean;
  isSaving: boolean;
  asgName: string;
  region: string;
  currentTypes: string[];
  currentSpotPercentage?: number;
  currentOnDemandCount?: number;
  onClose: () => void;
  onSave: (config: {
    instanceTypes: string[];
    spotPercentage?: number;
    onDemandCount?: number;
  }) => void;
}

// Common instance type families grouped by type
const INSTANCE_TYPE_FAMILIES = {
  'T Family (Burstable)': ['t2.nano', 't2.micro', 't2.small', 't2.medium', 't2.large', 't2.xlarge', 't2.2xlarge', 't3.nano', 't3.micro', 't3.small', 't3.medium', 't3.large', 't3a.nano', 't3a.micro', 't3a.small', 't3a.medium', 't3a.large', 't4g.nano', 't4g.micro', 't4g.small', 't4g.medium', 't4g.large'],
  'M Family (General Purpose)': ['m4.large', 'm4.xlarge', 'm4.2xlarge', 'm4.4xlarge', 'm5.large', 'm5.xlarge', 'm5.2xlarge', 'm5.4xlarge', 'm5a.large', 'm5a.xlarge', 'm5a.2xlarge', 'm5a.4xlarge', 'm6i.large', 'm6i.xlarge', 'm6i.2xlarge', 'm6i.4xlarge'],
  'C Family (Compute Optimized)': ['c4.large', 'c4.xlarge', 'c4.2xlarge', 'c4.4xlarge', 'c5.large', 'c5.xlarge', 'c5.2xlarge', 'c5.4xlarge', 'c5a.large', 'c5a.xlarge', 'c5a.2xlarge', 'c5a.4xlarge', 'c6i.large', 'c6i.xlarge', 'c6i.2xlarge', 'c6i.4xlarge'],
  'R Family (Memory Optimized)': ['r4.large', 'r4.xlarge', 'r4.2xlarge', 'r4.4xlarge', 'r5.large', 'r5.xlarge', 'r5.2xlarge', 'r5.4xlarge', 'r5a.large', 'r5a.xlarge', 'r5a.2xlarge', 'r5a.4xlarge', 'r6i.large', 'r6i.xlarge', 'r6i.2xlarge', 'r6i.4xlarge'],
  'I Family (Storage Optimized)': ['i3.large', 'i3.xlarge', 'i3.2xlarge', 'i3.4xlarge', 'i3en.large', 'i3en.xlarge', 'i3en.2xlarge', 'i3en.3xlarge'],
};

const InstanceTypeConfigModal: React.FC<InstanceTypeConfigModalProps> = ({
  isOpen,
  isSaving,
  asgName,
  region,
  currentTypes,
  currentSpotPercentage = 75,
  currentOnDemandCount,
  onClose,
  onSave,
}) => {
  const [selectedTypes, setSelectedTypes] = useState<Set<string>>(new Set(currentTypes));
  const [customType, setCustomType] = useState('');
  const [configMode, setConfigMode] = useState<'percentage' | 'count'>('percentage');
  const [spotPercentage, setSpotPercentage] = useState(currentSpotPercentage);
  const [onDemandCount, setOnDemandCount] = useState<number>(currentOnDemandCount || 0);

  // Update state when props change (modal opens with new data)
  useEffect(() => {
    setSelectedTypes(new Set(currentTypes));
    setSpotPercentage(currentSpotPercentage);
    setOnDemandCount(currentOnDemandCount || 0);
    setConfigMode(currentOnDemandCount !== undefined && currentOnDemandCount >= 0 ? 'count' : 'percentage');
  }, [currentTypes, currentSpotPercentage, currentOnDemandCount]);

  const handleToggleType = (type: string) => {
    setSelectedTypes((prev) => {
      const next = new Set(prev);
      if (next.has(type)) {
        next.delete(type);
      } else {
        next.add(type);
      }
      return next;
    });
  };

  const handleAddCustomType = () => {
    const trimmed = customType.trim();
    if (trimmed && /^[a-z0-9]+\.[a-z0-9]+$/.test(trimmed)) {
      setSelectedTypes((prev) => new Set(prev).add(trimmed));
      setCustomType('');
    }
  };

  const handleRemoveCustomType = (type: string) => {
    setSelectedTypes((prev) => {
      const next = new Set(prev);
      next.delete(type);
      return next;
    });
  };

  const handleSave = () => {
    onSave({
      instanceTypes: Array.from(selectedTypes),
      spotPercentage: configMode === 'percentage' ? spotPercentage : undefined,
      onDemandCount: configMode === 'count' ? onDemandCount : undefined,
    });
  };

  const handleClose = () => {
    setSelectedTypes(new Set(currentTypes));
    setCustomType('');
    setSpotPercentage(currentSpotPercentage);
    setOnDemandCount(currentOnDemandCount || 0);
    setConfigMode(currentOnDemandCount !== undefined && currentOnDemandCount >= 0 ? 'count' : 'percentage');
    onClose();
  };

  // Get custom types that are not in predefined families
  const allPredefinedTypes = new Set(Object.values(INSTANCE_TYPE_FAMILIES).flat());
  const customTypes = Array.from(selectedTypes).filter((type) => !allPredefinedTypes.has(type));

  if (!isOpen) return null;

  return (
    <div
      className="fixed inset-0 flex items-center justify-center z-50 bg-black/50 backdrop-blur-sm"
      onClick={handleClose}
    >
      <div
        className="bg-white rounded-2xl p-8 w-full max-w-4xl mx-4 shadow-2xl border border-gray-200 max-h-[90vh] overflow-y-auto"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-center justify-between mb-6">
          <div className="flex items-center space-x-3">
            <div className="w-10 h-10 bg-gradient-to-br from-indigo-500 to-purple-600 rounded-xl flex items-center justify-center shadow-md">
              <i className="fas fa-server text-white"></i>
            </div>
            <div>
              <h3 className="text-xl font-bold text-gray-800">Configure Instance Types</h3>
              <p className="text-sm text-gray-600">
                {asgName} ({region})
              </p>
            </div>
          </div>
          <button
            onClick={handleClose}
            className="text-gray-400 hover:text-gray-600 transition-colors"
          >
            <i className="fas fa-times text-xl"></i>
          </button>
        </div>

        <div className="mb-6 p-4 bg-blue-50 border border-blue-200 rounded-lg">
          <div className="flex items-start space-x-2">
            <i className="fas fa-lightbulb text-blue-600 mt-1"></i>
            <div className="text-sm text-blue-800">
              <p className="font-semibold mb-1">Why configure backup instance types?</p>
              <p>
                Sometimes AWS runs out of your preferred instance type. By selecting backup options below,
                AWS will automatically switch to similar instance types to keep your application running smoothly
                and save you money. Think of it as having a backup plan!
              </p>
            </div>
          </div>
        </div>

        {/* Spot/On-Demand Configuration */}
        <div className="mb-6 border border-gray-200 rounded-lg p-6 bg-gradient-to-br from-green-50 to-blue-50">
          <h4 className="font-bold text-gray-800 mb-4 flex items-center text-lg">
            <i className="fas fa-sliders-h text-green-600 mr-2"></i>
            Spot/On-Demand Configuration
          </h4>

          {/* Radio Button Group */}
          <div className="space-y-4">
            {/* % Spot Instances Option */}
            <div className="flex items-start space-x-3">
              <input
                type="radio"
                id="percentage-mode"
                name="config-mode"
                checked={configMode === 'percentage'}
                onChange={() => setConfigMode('percentage')}
                className="mt-1 w-4 h-4 text-indigo-600"
              />
              <div className="flex-1">
                <label htmlFor="percentage-mode" className="flex items-center space-x-2 cursor-pointer">
                  <span className="font-semibold text-gray-700">% Spot instances</span>
                  <div className="group relative">
                    <i className="fas fa-info-circle text-gray-400 hover:text-gray-600"></i>
                    <div className="absolute left-0 bottom-full mb-2 hidden group-hover:block w-64 p-2 bg-gray-800 text-white text-xs rounded-lg shadow-lg z-10">
                      Set the target percentage of Spot instances. AWS will maintain this ratio automatically.
                    </div>
                  </div>
                </label>

                {configMode === 'percentage' && (
                  <div className="mt-3">
                    <div className="flex items-center space-x-4">
                      <input
                        type="range"
                        min="0"
                        max="100"
                        value={spotPercentage}
                        onChange={(e) => setSpotPercentage(Number(e.target.value))}
                        className="flex-1 h-2 bg-gray-200 rounded-lg appearance-none cursor-pointer accent-indigo-600"
                        style={{
                          background: `linear-gradient(to right, rgb(79 70 229) 0%, rgb(79 70 229) ${spotPercentage}%, rgb(229 231 235) ${spotPercentage}%, rgb(229 231 235) 100%)`
                        }}
                      />
                      <input
                        type="number"
                        min="0"
                        max="100"
                        value={spotPercentage}
                        onChange={(e) => setSpotPercentage(Math.min(100, Math.max(0, Number(e.target.value))))}
                        className="w-20 px-3 py-2 border border-gray-300 rounded-lg text-center font-semibold focus:border-indigo-500 focus:ring-2 focus:ring-indigo-200"
                      />
                    </div>
                    <div className="flex justify-between text-xs text-gray-500 mt-2">
                      <span>0% (All On-Demand)</span>
                      <span>100% (All Spot)</span>
                    </div>
                  </div>
                )}
              </div>
            </div>

            {/* Specific On-Demand Count Option */}
            <div className="flex items-start space-x-3">
              <input
                type="radio"
                id="count-mode"
                name="config-mode"
                checked={configMode === 'count'}
                onChange={() => setConfigMode('count')}
                className="mt-1 w-4 h-4 text-indigo-600"
              />
              <div className="flex-1">
                <label htmlFor="count-mode" className="flex items-center space-x-2 cursor-pointer">
                  <span className="font-semibold text-gray-700">Specific on-demand count</span>
                  <div className="group relative">
                    <i className="fas fa-info-circle text-gray-400 hover:text-gray-600"></i>
                    <div className="absolute left-0 bottom-full mb-2 hidden group-hover:block w-64 p-2 bg-gray-800 text-white text-xs rounded-lg shadow-lg z-10">
                      Set a fixed number of On-Demand instances. All other instances will be Spot.
                    </div>
                  </div>
                </label>

                {configMode === 'count' && (
                  <div className="mt-3">
                    <input
                      type="number"
                      min="0"
                      value={onDemandCount}
                      onChange={(e) => setOnDemandCount(Math.max(0, Number(e.target.value)))}
                      placeholder="Enter count..."
                      className="w-full px-4 py-2 border border-gray-300 rounded-lg focus:border-indigo-500 focus:ring-2 focus:ring-indigo-200"
                    />
                    <p className="text-xs text-gray-500 mt-1">
                      Number of On-Demand instances to maintain. Remaining will be Spot.
                    </p>
                  </div>
                )}
              </div>
            </div>
          </div>
        </div>

        <div className="space-y-6">
          {Object.entries(INSTANCE_TYPE_FAMILIES).map(([family, types]) => (
            <div key={family} className="border border-gray-200 rounded-lg p-4">
              <h4 className="font-semibold text-gray-700 mb-3 flex items-center">
                <i className="fas fa-layer-group text-indigo-500 mr-2"></i>
                {family}
              </h4>
              <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 gap-2">
                {types.map((type) => (
                  <button
                    key={type}
                    type="button"
                    onClick={() => handleToggleType(type)}
                    className={`px-3 py-2 rounded-lg text-sm font-medium transition-all border ${
                      selectedTypes.has(type)
                        ? 'bg-indigo-600 text-white border-indigo-600 shadow-md'
                        : 'bg-white text-gray-700 border-gray-300 hover:border-indigo-400 hover:bg-indigo-50'
                    }`}
                  >
                    {type}
                  </button>
                ))}
              </div>
            </div>
          ))}

          {/* Custom Instance Type Input */}
          <div className="border border-gray-200 rounded-lg p-4">
            <h4 className="font-semibold text-gray-700 mb-3 flex items-center">
              <i className="fas fa-plus-circle text-green-500 mr-2"></i>
              Custom Instance Type
            </h4>
            <div className="flex items-center space-x-2">
              <input
                type="text"
                value={customType}
                onChange={(e) => setCustomType(e.target.value)}
                onKeyPress={(e) => {
                  if (e.key === 'Enter') {
                    e.preventDefault();
                    handleAddCustomType();
                  }
                }}
                placeholder="e.g., m5n.large"
                className="flex-1 px-4 py-2 rounded-lg border border-gray-300 shadow-sm focus:border-indigo-500 focus:ring-2 focus:ring-indigo-200 transition-all text-sm"
              />
              <button
                type="button"
                onClick={handleAddCustomType}
                className="px-4 py-2 bg-green-600 text-white font-semibold rounded-lg shadow-md hover:bg-green-700 transition-all"
              >
                <i className="fas fa-plus mr-2"></i>
                Add
              </button>
            </div>
            {customTypes.length > 0 && (
              <div className="mt-3 flex flex-wrap gap-2">
                {customTypes.map((type) => (
                  <div
                    key={type}
                    className="inline-flex items-center px-3 py-1 bg-gray-100 border border-gray-300 rounded-lg text-sm"
                  >
                    <span className="mr-2">{type}</span>
                    <button
                      type="button"
                      onClick={() => handleRemoveCustomType(type)}
                      className="text-red-500 hover:text-red-700"
                    >
                      <i className="fas fa-times"></i>
                    </button>
                  </div>
                ))}
              </div>
            )}
          </div>
        </div>

        <div className="flex items-center justify-between mt-8 pt-6 border-t border-gray-200">
          <div className="text-sm text-gray-600">
            <span className="font-semibold">{selectedTypes.size}</span> instance type{selectedTypes.size !== 1 ? 's' : ''} selected
          </div>
          <div className="flex space-x-3">
            <button
              type="button"
              onClick={handleClose}
              className="px-6 py-3 bg-white border border-gray-200 text-gray-700 font-semibold rounded-lg hover:bg-gray-50 transition-all"
            >
              Cancel
            </button>
            <button
              type="button"
              onClick={handleSave}
              disabled={isSaving}
              className="px-6 py-3 bg-indigo-600 text-white font-semibold rounded-lg shadow-md hover:bg-indigo-700 transition-all flex items-center disabled:opacity-50"
            >
              {isSaving ? (
                <>
                  <div className="loading-spinner mr-2 border-2 border-white border-t-transparent rounded-full w-4 h-4 animate-spin"></div>
                  Saving...
                </>
              ) : (
                <>
                  <i className="fas fa-save mr-2"></i>
                  Save Configuration
                </>
              )}
            </button>
          </div>
        </div>
      </div>
    </div>
  );
};

export default InstanceTypeConfigModal;
