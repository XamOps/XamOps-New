import React, { useRef, useEffect } from 'react';
import { Icon } from '@iconify/react';
import { useAccounts } from '../contexts/AccountContext';
import { useAzureRightsizingData } from '../hooks/useAzureRightsizingData';
import {
  AzureRightsizingHeader,
  AzureRightsizingKPICards,
  AzureRightsizingTable,
} from '../components/AzureRightsizing';

const RevealSection: React.FC<{ children: React.ReactNode; delay?: number }> = ({ children, delay = 0 }) => {
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    const timer = setTimeout(() => {
      const observer = new IntersectionObserver(([entry]) => {
        if (entry.isIntersecting) { el.classList.add('is-visible'); observer.unobserve(el); }
      }, { threshold: 0.06 });
      observer.observe(el);
      return () => observer.disconnect();
    }, 60);
    return () => clearTimeout(timer);
  }, []);
  return <div ref={ref} className="section-reveal" style={delay ? { transitionDelay: `${delay}s` } : undefined}>{children}</div>;
};

const AzureRightsizingPage: React.FC = () => {
  const { selectedAccountIds } = useAccounts();
  const selectedAccountId = selectedAccountIds.length > 0 ? selectedAccountIds[0] : null;

  const { recommendations, status, isLoading, isRefreshing, loadRecommendations } =
    useAzureRightsizingData({ selectedAccountId });

  const handleRefresh = () => loadRecommendations(true);

  return (
    <main className="flex-1 px-6 pt-4 pb-6 space-y-5">
      <AzureRightsizingHeader selectedAccountId={selectedAccountId} isRefreshing={isRefreshing} onRefresh={handleRefresh} />

      {/* No account */}
      {!selectedAccountId && !isLoading && (
        <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-10 text-center">
          <div className="w-12 h-12 bg-[rgba(0,0,0,0.04)] rounded-full flex items-center justify-center mx-auto mb-3">
            <Icon icon="mdi:resize" width={22} color="rgba(0,0,0,0.3)" />
          </div>
          <p className="text-[14px] font-semibold text-[rgba(0,0,0,0.88)] mb-1">No Account Selected</p>
          <p className="text-[13px] text-[rgba(0,0,0,0.45)]">Select an Azure account from the sidebar to view rightsizing recommendations.</p>
        </div>
      )}

      {selectedAccountId && (
        <>
          <RevealSection>
            <AzureRightsizingKPICards recommendations={recommendations} isLoading={isLoading} />
          </RevealSection>
          <RevealSection delay={0.05}>
            <AzureRightsizingTable recommendations={recommendations} status={status} isLoading={isLoading} />
          </RevealSection>
        </>
      )}
    </main>
  );
};

export default AzureRightsizingPage;
