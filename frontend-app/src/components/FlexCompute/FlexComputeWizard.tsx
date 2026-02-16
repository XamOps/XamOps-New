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
  isSearching: boolean;
  isPurchasing: boolean;
  onComplete: () => void;
}

const FlexComputeWizard: React.FC<FlexComputeWizardProps> = ({
  accounts,
  searchCapacity,
  reserveCapacity,
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
  const [searchStatus, setSearchStatus] = useState<string | undefined>(undefined);
  const [reservationData, setReservationData] = useState<any>(null); // Store the reserve response (cidrId, pricing, etc.)
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

        setReservationData(reservation);
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
            ourPrice: reservePricing.ourPrice || 0,
            awsPrice: reservePricing.cspProviderPrice || 0,
            savingsPercent: (reservePricing.savingsPercentage || 0) * 100, // Convert to percentage
            fees: reservePricing.creditCardProcessingFee || reservePricing.bankAccountProcessingFee || 0,
            total: (reservePricing.ourPrice || 0) + (reservePricing.creditCardProcessingFee || reservePricing.bankAccountProcessingFee || 0),
            startDate: searchForm.startDate,
            endDate: searchForm.endDate,
            status: reservation.myPolicy.policyStatus || 'READY_FOR_PURCHASE',
          };

          console.log('Updated pricing:', updatedPricing);
          setPricing(updatedPricing);
        }

        setStep('configure');
      } else {
        // Handle reservation failure
        alert('Failed to reserve capacity. Please try again.');
        setStep('search');
      }
    } catch (error) {
      console.error('Reserve failed:', error);
      alert('Failed to reserve capacity. Please try again.');
      setStep('search');
    } finally {
      setIsReserving(false);
    }
  };

  const handleSearchBack = () => {
    setStep('search');
  };

  const handleConfigure = async () => {
    // Configure is optional - only send if user provided actual AWS resource IDs
    // Skip if values are "default" or "none" (CloudiDR auto-populates these)
    console.log('=== handleConfigure called ===');
    console.log('configureForm:', JSON.stringify(configureForm, null, 2));

    const isRealValue = (value: string) => {
      if (!value || value.trim() === '') return false;
      const normalizedValue = value.trim().toLowerCase();
      return normalizedValue !== 'default' && normalizedValue !== 'none';
    };

    const hasConfig = isRealValue(configureForm.vpcId) ||
      isRealValue(configureForm.securityGroupId) ||
      isRealValue(configureForm.sshKeyName) ||
      isRealValue(configureForm.amiImageId) ||
      (configureForm.ebsVolumeSize && configureForm.ebsVolumeSize !== 10); // Default is 10GB

    console.log('hasConfig evaluation:', {
      vpcId: configureForm.vpcId,
      isRealVpc: isRealValue(configureForm.vpcId),
      securityGroupId: configureForm.securityGroupId,
      isRealSg: isRealValue(configureForm.securityGroupId),
      sshKeyName: configureForm.sshKeyName,
      isRealSsh: isRealValue(configureForm.sshKeyName),
      amiImageId: configureForm.amiImageId,
      isRealAmi: isRealValue(configureForm.amiImageId),
      ebsVolumeSize: configureForm.ebsVolumeSize,
      isNonDefaultEbs: (configureForm.ebsVolumeSize && configureForm.ebsVolumeSize !== 10),
      hasConfig: hasConfig
    });

    if (hasConfig) {
      console.log('✅ Calling configureCapacity API...');
      await configureCapacity(configureForm);
    } else {
      console.log('⏭️  Skipping Configure API - using CloudiDR defaults');
    }

    setStep('review');
  };

  const handlePurchase = async () => {
    if (!pricing?.capacityId) return;

    setPurchaseError(null);
    const errMsg = await purchaseCapacity(pricing.capacityId);
    if (errMsg === null) {
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
