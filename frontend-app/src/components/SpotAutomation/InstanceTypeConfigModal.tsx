/**
 * Instance Type Configuration Modal Component
 *
 * Modal for configuring alternative instance types for Spot diversification
 */

import React, { useState, useEffect } from 'react';
import { Icon } from '@iconify/react';
import { InfoTooltip, AriaSlider, DialogRoot, DialogPanel } from '../ui';
import { RadioGroup, Radio } from 'react-aria-components';

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

  return (
    <DialogRoot isOpen={isOpen} onClose={handleClose} maxWidth="max-w-4xl">
      <DialogPanel
        title={
          <div className="flex items-center space-x-3">
            <div className="w-10 h-10 bg-gradient-to-br from-indigo-500 to-purple-600 rounded-xl flex items-center justify-center shadow-md">
              <Icon icon="mdi:server-outline" width={18} color="white" />
            </div>
            <div>
              <div className="text-[18px] font-bold text-[rgba(0,0,0,0.88)]">Configure Instance Types</div>
              <div className="text-[13px] text-[rgba(0,0,0,0.5)]">{asgName} ({region})</div>
            </div>
          </div>
        }
        onClose={handleClose}
        footer={
          <>
            <div className="text-[13px] text-[rgba(0,0,0,0.5)] mr-auto">
              <span className="font-semibold text-[rgba(0,0,0,0.88)]">{selectedTypes.size}</span> instance type{selectedTypes.size !== 1 ? 's' : ''} selected
            </div>
            <button
              type="button"
              onClick={handleClose}
              className="px-6 py-3 text-[rgba(0,0,0,0.7)] bg-[rgba(0,0,0,0.04)] border border-[rgba(0,0,0,0.08)] rounded-md hover:bg-[rgba(0,0,0,0.06)] font-semibold transition-all"
            >
              Cancel
            </button>
            <button
              type="button"
              onClick={handleSave}
              disabled={isSaving}
              className="px-6 py-3 bg-[rgba(0,0,0,0.88)] hover:bg-black text-white font-semibold rounded-md transition-all flex items-center disabled:opacity-50"
            >
              {isSaving ? (
                <><Icon icon="mdi:loading" width={16} className="animate-spin mr-2" />Saving...</>
              ) : (
                <><Icon icon="mdi:content-save-outline" width={16} className="mr-2" />Save Configuration</>
              )}
            </button>
          </>
        }
      >
        <div className="overflow-y-auto max-h-[60vh] space-y-6">
        <div className="p-4 bg-blue-50 border border-blue-200 rounded-lg">
          <div className="flex items-start space-x-2">
            <Icon icon="mdi:lightbulb-outline" width={16} className="text-blue-600 mt-0.5 flex-shrink-0" />
            <div className="text-[13px] text-blue-800">
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
        <div className="mb-6 border border-[rgba(0,0,0,0.12)] rounded-lg p-6 bg-gradient-to-br from-green-50 to-blue-50">
          <h4 className="font-bold text-[rgba(0,0,0,0.88)] mb-4 flex items-center text-[16px]">
            <Icon icon="mdi:tune" width={18} className="text-emerald-600 mr-2" />
            Spot/On-Demand Configuration
          </h4>

          {/* Radio Button Group */}
          <RadioGroup
            value={configMode}
            onChange={(v) => setConfigMode(v as 'percentage' | 'count')}
            aria-label="Spot configuration mode"
            className="space-y-4"
          >
            {/* % Spot Instances Option */}
            <Radio
              value="percentage"
              className={({ isFocusVisible }) =>
                `flex items-start space-x-3 outline-none ${isFocusVisible ? 'ring-2 ring-indigo-400 rounded-lg' : ''}`
              }
            >
              {({ isSelected }) => (
                <>
                  <div className={`mt-1 w-4 h-4 rounded-full border-2 flex items-center justify-center shrink-0 ${isSelected ? 'border-indigo-600 bg-indigo-600' : 'border-[rgba(0,0,0,0.15)]'}`}>
                    {isSelected && <div className="w-1.5 h-1.5 rounded-full bg-white" />}
                  </div>
                  <div className="flex-1">
                    <div className="flex items-center space-x-2 cursor-pointer">
                      <span className="font-semibold text-[rgba(0,0,0,0.7)]">% Spot instances</span>
                      <InfoTooltip placement="top">
                        Set the target percentage of Spot instances. AWS will maintain this ratio automatically.
                      </InfoTooltip>
                    </div>
                    {isSelected && (
                      <div className="mt-3">
                        <AriaSlider
                          label="Spot Percentage"
                          value={spotPercentage}
                          onChange={setSpotPercentage}
                          minValue={0}
                          maxValue={100}
                          step={1}
                          formatValue={(v) => `${v}%`}
                          fillColor="rgb(79 70 229)"
                        />
                      </div>
                    )}
                  </div>
                </>
              )}
            </Radio>

            {/* Specific On-Demand Count Option */}
            <Radio
              value="count"
              className={({ isFocusVisible }) =>
                `flex items-start space-x-3 outline-none ${isFocusVisible ? 'ring-2 ring-indigo-400 rounded-lg' : ''}`
              }
            >
              {({ isSelected }) => (
                <>
                  <div className={`mt-1 w-4 h-4 rounded-full border-2 flex items-center justify-center shrink-0 ${isSelected ? 'border-indigo-600 bg-indigo-600' : 'border-[rgba(0,0,0,0.15)]'}`}>
                    {isSelected && <div className="w-1.5 h-1.5 rounded-full bg-white" />}
                  </div>
                  <div className="flex-1">
                    <div className="flex items-center space-x-2 cursor-pointer">
                      <span className="font-semibold text-[rgba(0,0,0,0.7)]">Specific on-demand count</span>
                      <InfoTooltip placement="top">
                        Set a fixed number of On-Demand instances. All other instances will be Spot.
                      </InfoTooltip>
                    </div>
                    {isSelected && (
                      <div className="mt-3">
                        <input
                          type="number"
                          min="0"
                          value={onDemandCount}
                          onChange={(e) => setOnDemandCount(Math.max(0, Number(e.target.value)))}
                          placeholder="Enter count..."
                          className="w-full px-4 py-2 bg-[rgba(0,0,0,0.04)] border border-[rgba(0,0,0,0.08)] rounded-lg focus:outline-none focus:border-[rgba(0,0,0,0.2)]"
                        />
                        <p className="text-[12px] text-[rgba(0,0,0,0.5)] mt-1">
                          Number of On-Demand instances to maintain. Remaining will be Spot.
                        </p>
                      </div>
                    )}
                  </div>
                </>
              )}
            </Radio>
          </RadioGroup>
        </div>

        <div className="space-y-6">
          {Object.entries(INSTANCE_TYPE_FAMILIES).map(([family, types]) => (
            <div key={family} className="border border-[rgba(0,0,0,0.12)] rounded-lg p-4">
              <h4 className="font-semibold text-[rgba(0,0,0,0.7)] mb-3 flex items-center text-[13px]">
                <Icon icon="mdi:layers-outline" width={15} className="text-indigo-500 mr-2" />
                {family}
              </h4>
              <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 gap-2">
                {types.map((type) => (
                  <button
                    key={type}
                    type="button"
                    onClick={() => handleToggleType(type)}
                    className={`px-3 py-2 rounded-lg text-[13px] font-medium transition-all border ${
                      selectedTypes.has(type)
                        ? 'bg-indigo-600 text-white border-indigo-600 shadow-md'
                        : 'bg-white text-[rgba(0,0,0,0.7)] border-[rgba(0,0,0,0.12)] hover:border-indigo-400 hover:bg-indigo-50'
                    }`}
                  >
                    {type}
                  </button>
                ))}
              </div>
            </div>
          ))}

          {/* Custom Instance Type Input */}
          <div className="border border-[rgba(0,0,0,0.12)] rounded-lg p-4">
            <h4 className="font-semibold text-[rgba(0,0,0,0.7)] mb-3 flex items-center text-[13px]">
              <Icon icon="mdi:plus-circle-outline" width={15} className="text-emerald-500 mr-2" />
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
                className="flex-1 px-4 py-2 rounded-lg bg-[rgba(0,0,0,0.04)] border border-[rgba(0,0,0,0.08)] focus:outline-none focus:border-[rgba(0,0,0,0.2)] transition-all text-[13px]"
              />
              <button
                type="button"
                onClick={handleAddCustomType}
                className="px-4 py-2 bg-[rgba(0,0,0,0.88)] hover:bg-black text-white font-semibold rounded-md transition-all flex items-center gap-1.5 text-[13px]"
              >
                <Icon icon="mdi:plus" width={15} />
                Add
              </button>
            </div>
            {customTypes.length > 0 && (
              <div className="mt-3 flex flex-wrap gap-2">
                {customTypes.map((type) => (
                  <div
                    key={type}
                    className="inline-flex items-center px-3 py-1 bg-[rgba(0,0,0,0.06)] border border-[rgba(0,0,0,0.12)] rounded-lg text-[13px]"
                  >
                    <span className="mr-2">{type}</span>
                    <button
                      type="button"
                      onClick={() => handleRemoveCustomType(type)}
                      className="text-red-500 hover:text-red-700"
                    >
                      <Icon icon="mdi:close" width={13} />
                    </button>
                  </div>
                ))}
              </div>
            )}
          </div>
        </div>

        </div>
      </DialogPanel>
    </DialogRoot>
  );
};

export default InstanceTypeConfigModal;
