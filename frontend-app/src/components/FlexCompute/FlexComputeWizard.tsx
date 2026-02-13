/**
 * FlexCompute Wizard Component
 *
 * Multi-step wizard container managing the capacity creation flow:
 * search -> searching -> configure -> review -> complete
 */

import React, { useState } from 'react';
import type {
  SearchCapacityForm,
  ConfigureCapacityForm,
  CapacityPricing,
  WizardStep,
} from '../../types/flexCompute';
import { INITIAL_SEARCH_FORM, INITIAL_CONFIGURE_FORM } from '../../types/flexCompute';
import WizardStep1Search from './WizardStep1Search';
import WizardSearchProgress from './WizardSearchProgress';
import WizardStep2Configure from './WizardStep2Configure';
import WizardStep3Review from './WizardStep3Review';

interface FlexComputeWizardProps {
  accounts: Array<{ id: string; name: string }>;
  searchCapacity: (form: SearchCapacityForm) => Promise<CapacityPricing | null>;
  configureCapacity: (form: ConfigureCapacityForm) => Promise<boolean>;
  purchaseCapacity: (capacityId: string) => Promise<boolean>;
  isSearching: boolean;
  isPurchasing: boolean;
  onComplete: () => void;
}

const FlexComputeWizard: React.FC<FlexComputeWizardProps> = ({
  accounts,
  searchCapacity,
  configureCapacity,
  purchaseCapacity,
  isSearching,
  isPurchasing,
  onComplete,
}) => {
  const [step, setStep] = useState<WizardStep>('search');
  const [searchForm, setSearchForm] = useState<SearchCapacityForm>(INITIAL_SEARCH_FORM);
  const [configureForm, setConfigureForm] = useState<ConfigureCapacityForm>(INITIAL_CONFIGURE_FORM);
  const [pricing, setPricing] = useState<CapacityPricing | null>(null);

  const handleSearch = async () => {
    setStep('searching');

    const result = await searchCapacity(searchForm);

    if (result) {
      setPricing(result);
      setConfigureForm((prev) => ({ ...prev, capacityId: result.capacityId }));
      setStep('configure');
    } else {
      setStep('search');
    }
  };

  const handleConfigure = async () => {
    // Configure is optional - send config if any fields are filled
    const hasConfig = configureForm.vpcId || configureForm.securityGroupId ||
      configureForm.sshKeyName || configureForm.amiImageId;

    if (hasConfig) {
      await configureCapacity(configureForm);
    }

    setStep('review');
  };

  const handlePurchase = async () => {
    if (!pricing?.capacityId) return;

    const success = await purchaseCapacity(pricing.capacityId);
    if (success) {
      setStep('complete');
    }
  };

  const handleReset = () => {
    setStep('search');
    setSearchForm(INITIAL_SEARCH_FORM);
    setConfigureForm(INITIAL_CONFIGURE_FORM);
    setPricing(null);
    onComplete();
  };

  // Step progress indicator
  const stepLabels = [
    { key: 'search', label: 'Search', icon: 'fas fa-search' },
    { key: 'configure', label: 'Configure', icon: 'fas fa-cog' },
    { key: 'review', label: 'Review', icon: 'fas fa-check-circle' },
  ];

  const getStepIndex = (): number => {
    if (step === 'search' || step === 'searching') return 0;
    if (step === 'configure') return 1;
    if (step === 'review') return 2;
    return 3;
  };

  return (
    <div className="space-y-6">
      {/* Progress Indicator */}
      {step !== 'complete' && (
        <div className="flex items-center justify-center gap-4">
          {stepLabels.map((s, index) => {
            const currentIndex = getStepIndex();
            const isCompleted = index < currentIndex;
            const isCurrent = index === currentIndex;

            return (
              <React.Fragment key={s.key}>
                {index > 0 && (
                  <div className={`h-0.5 w-12 ${isCompleted ? 'bg-blue-500' : 'bg-gray-200'}`}></div>
                )}
                <div className="flex items-center gap-2">
                  <div className={`w-8 h-8 rounded-full flex items-center justify-center text-xs font-bold transition-all ${
                    isCompleted
                      ? 'bg-blue-500 text-white'
                      : isCurrent
                      ? 'bg-blue-100 text-blue-700 ring-2 ring-blue-500'
                      : 'bg-gray-100 text-gray-400'
                  }`}>
                    {isCompleted ? <i className="fas fa-check"></i> : index + 1}
                  </div>
                  <span className={`text-sm font-medium ${isCurrent ? 'text-gray-900' : 'text-gray-500'}`}>
                    {s.label}
                  </span>
                </div>
              </React.Fragment>
            );
          })}
        </div>
      )}

      {/* Step Content */}
      {step === 'search' && (
        <WizardStep1Search
          form={searchForm}
          onChange={(updates) => setSearchForm((prev) => ({ ...prev, ...updates }))}
          onSearch={handleSearch}
          isSearching={isSearching}
          accounts={accounts}
        />
      )}

      {step === 'searching' && <WizardSearchProgress />}

      {step === 'configure' && (
        <WizardStep2Configure
          form={configureForm}
          onChange={(updates) => setConfigureForm((prev) => ({ ...prev, ...updates }))}
          onNext={handleConfigure}
          onBack={() => setStep('search')}
        />
      )}

      {step === 'review' && pricing && (
        <WizardStep3Review
          pricing={pricing}
          searchForm={searchForm}
          onPurchase={handlePurchase}
          onBack={() => setStep('configure')}
          isPurchasing={isPurchasing}
        />
      )}

      {step === 'complete' && (
        <div className="bg-white rounded-2xl shadow-sm border border-gray-200 p-16 text-center">
          <div className="w-24 h-24 bg-gradient-to-br from-green-400 to-emerald-500 rounded-full flex items-center justify-center mx-auto mb-6 shadow-lg">
            <i className="fas fa-check text-white text-4xl"></i>
          </div>
          <h3 className="text-2xl font-bold text-gray-900 mb-3">Purchase Complete!</h3>
          <p className="text-gray-500 max-w-md mx-auto mb-8">
            Your capacity reservation has been successfully created. You can view it in the Dashboard tab.
          </p>
          <button
            onClick={handleReset}
            className="px-8 py-3 bg-gradient-to-r from-blue-600 to-indigo-600 text-white font-semibold rounded-xl hover:from-blue-700 hover:to-indigo-700 transition-all shadow-lg"
          >
            <i className="fas fa-tachometer-alt mr-2"></i>
            Go to Dashboard
          </button>
        </div>
      )}
    </div>
  );
};

export default FlexComputeWizard;
