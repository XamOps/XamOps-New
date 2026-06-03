import React, { useRef, useEffect } from 'react';
import { Icon } from '@iconify/react';
import { useAccounts } from '../contexts/AccountContext';
import { useGcpRightsizingData } from '../hooks/useGcpRightsizingData';

import {
  GcpRightsizingHeader,
  GcpRightsizingKPICards,
  GcpRightsizingTable,
} from '../components/GcpRightsizing';

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

const GcpRightsizingPage: React.FC = () => {
  const { selectedAccountIds } = useAccounts();
  const selectedAccountId = selectedAccountIds.length > 0 ? selectedAccountIds[0] : null;

  const { recommendations, isLoading, isRefreshing, loadRecommendations } = useGcpRightsizingData({
    selectedAccountId,
  });

  const handleRefresh = () => loadRecommendations(true);

  return (
    <main className="flex-1 px-6 pt-4 pb-6 space-y-5">
      <GcpRightsizingHeader isRefreshing={isRefreshing} onRefresh={handleRefresh} />

      {/* No account */}
      {!selectedAccountId && !isLoading && (
        <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-10 text-center">
          <div className="w-12 h-12 bg-[rgba(0,0,0,0.04)] rounded-full flex items-center justify-center mx-auto mb-3">
            <Icon icon="mdi:resize" width={22} color="rgba(0,0,0,0.3)" />
          </div>
          <p className="text-[14px] font-semibold text-[rgba(0,0,0,0.88)] mb-1">No Account Selected</p>
          <p className="text-[13px] text-[rgba(0,0,0,0.45)]">Select a GCP account from the sidebar to view rightsizing recommendations.</p>
        </div>
      )}

      {/* Loading skeletons */}
      {selectedAccountId && isLoading && recommendations.length === 0 && (
        <>
          <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
            {Array.from({ length: 4 }).map((_, i) => (
              <div key={i} className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-5 h-[110px] animate-pulse">
                <div className="h-2.5 w-24 bg-[rgba(0,0,0,0.06)] rounded mb-3" />
                <div className="h-7 w-28 bg-[rgba(0,0,0,0.06)] rounded mb-3" />
                <div className="h-2 w-16 bg-[rgba(0,0,0,0.04)] rounded" />
              </div>
            ))}
          </div>
          <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm overflow-hidden">
            {Array.from({ length: 5 }).map((_, i) => (
              <div key={i} className="px-5 py-4 border-t border-[rgba(0,0,0,0.04)] animate-pulse flex items-center gap-4">
                <div className="w-8 h-8 rounded-lg bg-[rgba(0,0,0,0.06)] flex-shrink-0" />
                <div className="flex-1 space-y-2">
                  <div className="h-2.5 w-40 bg-[rgba(0,0,0,0.06)] rounded" />
                  <div className="h-2 w-24 bg-[rgba(0,0,0,0.04)] rounded" />
                </div>
              </div>
            ))}
          </div>
        </>
      )}

      {selectedAccountId && !isLoading && (
        <>
          <RevealSection>
            <GcpRightsizingKPICards recommendations={recommendations} isLoading={isLoading} />
          </RevealSection>
          <RevealSection delay={0.05}>
            <GcpRightsizingTable recommendations={recommendations} isLoading={isLoading} onRefresh={handleRefresh} />
          </RevealSection>
        </>
      )}
    </main>
  );
};

export default GcpRightsizingPage;
