/**
 * FlexCompute Wizard Component
 *
 * Multi-step wizard container managing the capacity creation flow:
 * search -> searching -> configure -> review -> complete
 */

import React, { useState } from 'react';
import { gooeyToast } from 'goey-toast';
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

interface LinkedAccount {
  id: string;
  cspAccountId: string;
  alias: string;
  accountRegions?: Array<{
    id: number;
    operatingCspRegionAz: {
      regionCode: string;
      regionType: string;
      availabilityZoneId: string;
    };
  }>;
}

interface FlexComputeWizardProps {
  accounts: LinkedAccount[];
  searchCapacity: (form: SearchCapacityForm) => Promise<CapacityPricing | null>;
  reserveCapacity: (form: SearchCapacityForm) => Promise<any>;
  configureCapacity: (form: ConfigureCapacityForm) => Promise<boolean>;
  purchaseCapacity: (capacityId: string) => Promise<string | null>;
  renewCapacity: (cidrId: string, autoRenewalEnabled: boolean, frequency: '24hrs' | '1week' | '1month') => Promise<boolean>;
  isSearching: boolean;
  isPurchasing: boolean;
  onComplete: () => void;
  initialPricing?: CapacityPricing | null;
}

const FlexComputeWizard: React.FC<FlexComputeWizardProps> = ({
  accounts,
  searchCapacity,
  reserveCapacity,
  configureCapacity,
  purchaseCapacity,
  renewCapacity,
  isSearching,
  isPurchasing,
  onComplete,
  initialPricing,
}) => {
  const [step, setStep] = useState<WizardStep>(initialPricing ? 'review' : 'search');
  const [searchForm, setSearchForm] = useState<SearchCapacityForm>(INITIAL_SEARCH_FORM);
  const [configureForm, setConfigureForm] = useState<ConfigureCapacityForm>(
    initialPricing ? { ...INITIAL_CONFIGURE_FORM, capacityId: initialPricing.capacityId } : INITIAL_CONFIGURE_FORM
  );
  const [pricing, setPricing] = useState<CapacityPricing | null>(initialPricing || null);
  const [searchStatus, setSearchStatus] = useState<string | undefined>(undefined);
  const [isReserving, setIsReserving] = useState(false);
  const [purchaseError, setPurchaseError] = useState<string | null>(null);

  const handleSearch = async () => {
    setStep('searching');
    setSearchStatus(undefined);

    const result = await searchCapacity(searchForm);

    if (result) {
      setPricing(result);
      setConfigureForm((prev) => ({ ...prev, capacityId: result.capacityId }));
      setSearchStatus(result.status);
      // Stay on searching step - user will click Next to continue
    } else {
      setStep('search');
    }
  };

  const handleSearchNext = async () => {
    // Call Reserve API to create 15-minute temporary reservation
    setIsReserving(true);
    try {
      const reservation = await reserveCapacity(searchForm);
      if (reservation && reservation.myPolicy) {
        console.log('Reserve API response:', reservation);

        setConfigureForm((prev) => ({ ...prev, capacityId: reservation.myPolicy.cidrId }));

        // Update pricing with data from Reserve API response
        if (reservation.myPolicy.pricing) {
          const reservePricing = reservation.myPolicy.pricing;
          const updatedPricing: CapacityPricing = {
            capacityId: reservation.myPolicy.cidrId,
            capacityName: searchForm.capacityName,
            instanceType: reservation.myPolicy.policyInstance?.instanceType || searchForm.instanceType,
            region: reservation.myPolicy.cspRegion?.regionCode || searchForm.region,
            zoneId: reservation.myPolicy.cspRegion?.availabilityZoneId || searchForm.zoneId,
            quantity: reservation.myPolicy.policyInstance?.qty || searchForm.quantity,
            platform: reservation.myPolicy.policyInstance?.platformCode || searchForm.platform,
            tenancy: reservation.myPolicy.policyInstance?.tenancyCode || searchForm.tenancy,
            ourPrice: (reservePricing.cspProviderPrice || 0) * 0.75,
            awsPrice: reservePricing.cspProviderPrice || 0,
            savingsPercent: 25,
            fees: reservePricing.creditCardProcessingFee || reservePricing.bankAccountProcessingFee || 0,
            total: (reservePricing.cspProviderPrice || 0) * 0.75 + (reservePricing.creditCardProcessingFee || reservePricing.bankAccountProcessingFee || 0),
            startDate: searchForm.startDate,
            endDate: searchForm.endDate,
            status: reservation.myPolicy.policyStatus || 'READY_FOR_PURCHASE',
            reservedAt: Date.now(),
          };

          console.log('Updated pricing:', updatedPricing);
          setPricing(updatedPricing);
        }

        setStep('configure');
      } else {
        gooeyToast.error('Failed to reserve capacity', { description: 'Please try again.' });
        setStep('search');
      }
    } catch (error) {
      gooeyToast.error('Failed to reserve capacity', { description: 'Please try again.' });
      setStep('search');
    } finally {
      setIsReserving(false);
    }
  };

  const handleSearchBack = () => {
    setStep('search');
  };

  const handleConfigure = async () => {
    // Just advance to review — configure is applied after purchase
    setStep('review');
  };

  const handlePurchase = async () => {
    if (!pricing?.capacityId) return;

    if ((pricing.total ?? pricing.ourPrice ?? 0) < 1.00) {
      setPurchaseError('Purchase not allowed: minimum order value is $1.00.');
      return;
    }

    setPurchaseError(null);
    const errMsg = await purchaseCapacity(pricing.capacityId);
    if (errMsg === null) {
      await configureCapacity({ ...configureForm, capacityId: pricing.capacityId });
      if (searchForm.autoRenewal) {
        await renewCapacity(pricing.capacityId, true, searchForm.renewalFrequency);
      }
      setStep('complete');
    } else {
      setPurchaseError(errMsg);
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
                  <div className={`w-8 h-8 rounded-full flex items-center justify-center text-xs font-bold transition-all ${isCompleted
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

      {step === 'searching' && (
        <WizardSearchProgress
          status={searchStatus}
          isSearching={isSearching || isReserving}
          onNext={handleSearchNext}
          onBack={handleSearchBack}
        />
      )}

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
          configureForm={configureForm}
          onPurchase={handlePurchase}
          onBack={() => setStep('configure')}
          isPurchasing={isPurchasing}
          purchaseError={purchaseError}
          reservedAt={pricing.reservedAt}
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
