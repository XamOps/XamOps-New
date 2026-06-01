import React, { useState } from 'react';
import { Icon } from '@iconify/react';
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
    operatingCspRegionAz: { regionCode: string; regionType: string; availabilityZoneId: string };
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
  const [step, setStep]               = useState<WizardStep>(initialPricing ? 'review' : 'search');
  const [searchForm, setSearchForm]   = useState<SearchCapacityForm>(INITIAL_SEARCH_FORM);
  const [configureForm, setConfigureForm] = useState<ConfigureCapacityForm>(
    initialPricing ? { ...INITIAL_CONFIGURE_FORM, capacityId: initialPricing.capacityId } : INITIAL_CONFIGURE_FORM
  );
  const [pricing, setPricing]         = useState<CapacityPricing | null>(initialPricing || null);
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
    } else {
      setStep('search');
    }
  };

  const handleSearchNext = async () => {
    setIsReserving(true);
    try {
      const reservation = await reserveCapacity(searchForm);
      if (reservation && reservation.myPolicy) {
        setConfigureForm((prev) => ({ ...prev, capacityId: reservation.myPolicy.cidrId }));
        if (reservation.myPolicy.pricing) {
          const rp = reservation.myPolicy.pricing;
          const updatedPricing: CapacityPricing = {
            capacityId:    reservation.myPolicy.cidrId,
            capacityName:  searchForm.capacityName,
            instanceType:  reservation.myPolicy.policyInstance?.instanceType || searchForm.instanceType,
            region:        reservation.myPolicy.cspRegion?.regionCode || searchForm.region,
            zoneId:        reservation.myPolicy.cspRegion?.availabilityZoneId || searchForm.zoneId,
            quantity:      reservation.myPolicy.policyInstance?.qty || searchForm.quantity,
            platform:      reservation.myPolicy.policyInstance?.platformCode || searchForm.platform,
            tenancy:       reservation.myPolicy.policyInstance?.tenancyCode || searchForm.tenancy,
            ourPrice:      (rp.cspProviderPrice || 0) * 0.75,
            awsPrice:      rp.cspProviderPrice || 0,
            savingsPercent: 25,
            fees:          rp.creditCardProcessingFee || rp.bankAccountProcessingFee || 0,
            total:         (rp.cspProviderPrice || 0) * 0.75 + (rp.creditCardProcessingFee || rp.bankAccountProcessingFee || 0),
            startDate:     searchForm.startDate,
            endDate:       searchForm.endDate,
            status:        reservation.myPolicy.policyStatus || 'READY_FOR_PURCHASE',
            reservedAt:    Date.now(),
          };
          setPricing(updatedPricing);
        }
        setStep('configure');
      } else {
        gooeyToast.error('Failed to reserve capacity', { description: 'Please try again.' });
        setStep('search');
      }
    } catch {
      gooeyToast.error('Failed to reserve capacity', { description: 'Please try again.' });
      setStep('search');
    } finally {
      setIsReserving(false);
    }
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

  const stepLabels = [
    { key: 'search',    label: 'Search',    icon: 'mdi:magnify' },
    { key: 'configure', label: 'Configure', icon: 'mdi:cog-outline' },
    { key: 'review',    label: 'Review',    icon: 'mdi:check-circle-outline' },
  ];

  const getStepIndex = (): number => {
    if (step === 'search' || step === 'searching') return 0;
    if (step === 'configure') return 1;
    if (step === 'review') return 2;
    return 3;
  };

  return (
    <div className="space-y-5">
      {/* Progress Indicator */}
      {step !== 'complete' && (
        <div className="flex items-center justify-center gap-3">
          {stepLabels.map((s, index) => {
            const currentIndex = getStepIndex();
            const isCompleted  = index < currentIndex;
            const isCurrent    = index === currentIndex;
            return (
              <React.Fragment key={s.key}>
                {index > 0 && (
                  <div className={`h-0.5 w-10 ${isCompleted ? 'bg-indigo-500' : 'bg-[rgba(0,0,0,0.1)]'}`} />
                )}
                <div className="flex items-center gap-2">
                  <div className={`w-7 h-7 rounded-full flex items-center justify-center text-[11px] font-bold transition-all ${
                    isCompleted ? 'bg-indigo-600 text-white' :
                    isCurrent   ? 'bg-indigo-50 text-indigo-700 ring-2 ring-indigo-500' :
                                  'bg-[rgba(0,0,0,0.04)] text-[rgba(0,0,0,0.3)]'
                  }`}>
                    {isCompleted ? <Icon icon="mdi:check" width={13} /> : index + 1}
                  </div>
                  <span className={`text-[12px] font-medium ${isCurrent ? 'text-[rgba(0,0,0,0.88)]' : 'text-[rgba(0,0,0,0.4)]'}`}>
                    {s.label}
                  </span>
                </div>
              </React.Fragment>
            );
          })}
        </div>
      )}

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
          onBack={() => setStep('search')}
        />
      )}

      {step === 'configure' && (
        <WizardStep2Configure
          form={configureForm}
          onChange={(updates) => setConfigureForm((prev) => ({ ...prev, ...updates }))}
          onNext={() => setStep('review')}
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
        <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-2xl shadow-sm p-16 text-center">
          <div className="w-20 h-20 bg-emerald-500 rounded-full flex items-center justify-center mx-auto mb-6 shadow-lg">
            <Icon icon="mdi:check" color="#fff" width={36} />
          </div>
          <h3 className="text-[22px] font-bold text-[rgba(0,0,0,0.88)] mb-3">Purchase Complete!</h3>
          <p className="text-[13px] text-[rgba(0,0,0,0.45)] max-w-md mx-auto mb-8">
            Your capacity reservation has been successfully created. You can view it in the Dashboard tab.
          </p>
          <button
            onClick={handleReset}
            className="flex items-center gap-2 px-7 py-3 bg-indigo-600 hover:bg-indigo-700 text-white text-[13px] font-semibold rounded-xl transition-colors mx-auto"
          >
            <Icon icon="mdi:view-dashboard-outline" width={16} />
            Go to Dashboard
          </button>
        </div>
      )}
    </div>
  );
};

export default FlexComputeWizard;
