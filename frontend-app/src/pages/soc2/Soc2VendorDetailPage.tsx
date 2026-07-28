import React, { useEffect, useState } from 'react';
import { useParams } from 'react-router-dom';
import { Icon } from '@iconify/react';
import { useTopbar } from '../../contexts/TopbarContext';
import { useSoc2ProgramData } from '../../hooks/useSoc2ProgramData';
import {
  Breadcrumbs, StatusPill, Tabs, RiskMatrix, TreatmentReduction, RiskScoreBadge,
  likelihoodLabel, impactLabel, Soc2Skeleton,
} from './soc2Ui';

type VTab = 'overview' | 'treatment' | 'matrix' | 'assessment' | 'tasks' | 'comments' | 'activity' | 'settings';

const vendorStatusLabel = (s: string | null) =>
  ({ in_progress: 'Researching', assessed: 'Assessed', not_assessed: 'Not Assessed' }[(s || '').toLowerCase()] || (s || '').replace(/_/g, ' '));

const Field: React.FC<{ label: string; value: React.ReactNode }> = ({ label, value }) => (
  <div>
    <div className="text-[11px] text-gray-500 mb-1">{label}</div>
    <div className="text-[13px] text-gray-800 border border-gray-200 rounded-lg px-3 py-2 bg-gray-50 capitalize">{value}</div>
  </div>
);

const EmptyTab: React.FC<{ icon: string; text: string }> = ({ icon, text }) => (
  <div className="border border-[rgba(0,0,0,0.10)] rounded-xl bg-white p-8 flex flex-col items-center justify-center text-center">
    <Icon icon={icon} width={26} className="text-gray-300 mb-2" />
    <p className="text-[13px] text-gray-500">{text}</p>
  </div>
);

const Soc2VendorDetailPage: React.FC = () => {
  const { vendorId = '' } = useParams<{ vendorId: string }>();
  const { setTitle } = useTopbar();
  const { vendors, isLoading, error } = useSoc2ProgramData();
  const [tab, setTab] = useState<VTab>('overview');

  useEffect(() => { setTitle('Program'); return () => setTitle(null); }, [setTitle]);

  const vendor = vendors.find((v) => v.id === vendorId);
  if (isLoading) return <Soc2Skeleton variant="detail" />;
  if (error) return <div className="p-6 text-sm text-red-600">Failed to load: {error.message}</div>;
  if (!vendor) return <div className="p-6 text-sm text-gray-500">Vendor not found.</div>;

  return (
    <div className="p-6">
      <Breadcrumbs items={[{ label: 'Vendors', to: '/soc2-program/vendors' }, { label: vendor.name }]} />
      <div className="flex items-center gap-3">
        <h1 className="text-2xl font-semibold text-gray-900">{vendor.name}</h1>
        <StatusPill text={vendorStatusLabel(vendor.status)} tone={(vendor.status || '') === 'assessed' ? 'success' : 'warning'} />
      </div>
      {vendor.description && <p className="text-[13px] text-gray-500 mt-1 max-w-2xl">{vendor.description}</p>}

      <div className="mt-5">
        <Tabs
          tabs={[
            { id: 'overview', label: 'Overview' }, { id: 'treatment', label: 'Treatment Plan' },
            { id: 'matrix', label: 'Risk Matrix' }, { id: 'assessment', label: 'Risk Assessment' },
            { id: 'tasks', label: 'Tasks' }, { id: 'comments', label: 'Comments' },
            { id: 'activity', label: 'Activity' }, { id: 'settings', label: 'Settings' },
          ]}
          active={tab}
          onSelect={(id) => setTab(id as VTab)}
        />
      </div>

      {tab === 'overview' && (
        <div className="border border-[rgba(0,0,0,0.10)] rounded-xl bg-white p-5 max-w-2xl">
          <div className="text-[13px] font-semibold text-gray-900 mb-4">Vendor Settings</div>
          <div className="grid grid-cols-2 gap-4">
            <Field label="Category" value={(vendor.category || '—').replace(/_/g, ' ')} />
            <Field label="Status" value={vendorStatusLabel(vendor.status)} />
            <Field label="Inherent Risk" value={<RiskScoreBadge score={vendor.inherentScore} />} />
            <Field label="Current Risk" value={<RiskScoreBadge score={vendor.residualScore ?? vendor.inherentScore} />} />
          </div>
        </div>
      )}

      {tab === 'treatment' && (
        <div className="grid grid-cols-1 lg:grid-cols-2 gap-5">
          <div className="border border-[rgba(0,0,0,0.10)] rounded-xl bg-white p-5">
            <TreatmentReduction from={vendor.inherentScore} to={vendor.residualScore ?? vendor.inherentScore} />
            <div className="grid grid-cols-3 gap-3 mt-5">
              <div><div className="text-[11px] text-gray-400">Likelihood</div><div className="text-[13px] text-gray-800">{likelihoodLabel(vendor.residualLikelihood ?? vendor.inherentLikelihood)}</div></div>
              <div><div className="text-[11px] text-gray-400">Impact</div><div className="text-[13px] text-gray-800">{impactLabel(vendor.residualImpact ?? vendor.inherentImpact)}</div></div>
              <div><div className="text-[11px] text-gray-400">Task completion</div><div className="text-[13px] text-emerald-600 font-semibold">0%</div></div>
            </div>
          </div>
          <div className="border border-[rgba(0,0,0,0.10)] rounded-xl bg-white p-5">
            <div className="text-[11px] text-gray-400 uppercase tracking-wide mb-3">5×5 Risk Matrix</div>
            <RiskMatrix likelihood={vendor.residualLikelihood ?? vendor.inherentLikelihood} impact={vendor.residualImpact ?? vendor.inherentImpact} />
          </div>
        </div>
      )}

      {tab === 'matrix' && (
        <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
          <div className="border border-[rgba(0,0,0,0.10)] rounded-xl bg-white p-5">
            <div className="text-[13px] font-semibold text-gray-900 mb-1">Inherent Risk</div>
            <p className="text-[11px] text-gray-400 mb-4">Risk before any controls are applied.</p>
            <RiskMatrix likelihood={vendor.inherentLikelihood} impact={vendor.inherentImpact} />
          </div>
          <div className="border border-[rgba(0,0,0,0.10)] rounded-xl bg-white p-5">
            <div className="text-[13px] font-semibold text-gray-900 mb-1">Residual Risk</div>
            <p className="text-[11px] text-gray-400 mb-4">Risk after the vendor's controls are applied.</p>
            <RiskMatrix likelihood={vendor.residualLikelihood} impact={vendor.residualImpact} />
          </div>
        </div>
      )}

      {tab === 'assessment' && <EmptyTab icon="mdi:clipboard-text-outline" text="No security questionnaire has been sent to this vendor yet." />}
      {tab === 'tasks' && <EmptyTab icon="mdi:clipboard-check-outline" text="No tasks linked to this vendor." />}
      {tab === 'comments' && <EmptyTab icon="mdi:comment-outline" text="No comments yet." />}
      {tab === 'activity' && <EmptyTab icon="mdi:history" text="No activity recorded yet." />}
      {tab === 'settings' && (
        <div className="border border-[rgba(0,0,0,0.10)] rounded-xl bg-white p-5 max-w-2xl">
          <div className="grid grid-cols-2 gap-4">
            <Field label="Category" value={(vendor.category || '—').replace(/_/g, ' ')} />
            <Field label="Status" value={vendorStatusLabel(vendor.status)} />
          </div>
        </div>
      )}
    </div>
  );
};

export default Soc2VendorDetailPage;
