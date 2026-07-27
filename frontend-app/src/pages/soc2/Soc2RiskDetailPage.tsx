import React, { useEffect, useState } from 'react';
import { useParams } from 'react-router-dom';
import { Icon } from '@iconify/react';
import { useTopbar } from '../../contexts/TopbarContext';
import { useSoc2ProgramData } from '../../hooks/useSoc2ProgramData';
import {
  Breadcrumbs, StatusPill, Tabs, RiskMatrix, TreatmentReduction,
  likelihoodLabel, impactLabel,
} from './soc2Ui';

type RTab = 'overview' | 'treatment' | 'matrix' | 'tasks' | 'comments' | 'activity' | 'settings';

const riskStatusTone = (s: string | null) => {
  const v = (s || '').toLowerCase();
  if (v.includes('mitigat') || v.includes('closed') || v.includes('accept')) return 'success' as const;
  return 'warning' as const;
};

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

const Soc2RiskDetailPage: React.FC = () => {
  const { riskId = '' } = useParams<{ riskId: string }>();
  const { setTitle } = useTopbar();
  const { risks, isLoading, error } = useSoc2ProgramData();
  const [tab, setTab] = useState<RTab>('overview');

  useEffect(() => { setTitle('Program'); return () => setTitle(null); }, [setTitle]);

  const risk = risks.find((r) => r.id === riskId);
  if (isLoading) return <div className="p-6 text-gray-500 text-sm">Loading risk…</div>;
  if (error) return <div className="p-6 text-sm text-red-600">Failed to load: {error.message}</div>;
  if (!risk) return <div className="p-6 text-sm text-gray-500">Risk not found.</div>;

  return (
    <div className="p-6">
      <Breadcrumbs items={[{ label: 'Risks', to: '/soc2-program/risks' }, { label: risk.title || 'Risk' }]} />
      <h1 className="text-2xl font-semibold text-gray-900">{risk.title || 'Risk'}</h1>
      {risk.description && <p className="text-[13px] text-gray-500 mt-1 max-w-2xl">{risk.description}</p>}

      <div className="mt-5">
        <Tabs
          tabs={[
            { id: 'overview', label: 'Overview' }, { id: 'treatment', label: 'Treatment Plan' },
            { id: 'matrix', label: 'Risk Matrix' }, { id: 'tasks', label: 'Tasks' },
            { id: 'comments', label: 'Comments' }, { id: 'activity', label: 'Activity' },
            { id: 'settings', label: 'Settings' },
          ]}
          active={tab}
          onSelect={(id) => setTab(id as RTab)}
        />
      </div>

      {tab === 'overview' && (
        <div className="border border-[rgba(0,0,0,0.10)] rounded-xl bg-white p-5 max-w-2xl">
          <div className="text-[13px] font-semibold text-gray-900 mb-4">Risk Settings</div>
          <div className="grid grid-cols-2 gap-4">
            <Field label="Assignee" value={risk.owner || 'Unassigned'} />
            <Field label="Status" value={<StatusPill text={(risk.status || '').replace(/_/g, ' ')} tone={riskStatusTone(risk.status)} />} />
            <Field label="Category" value={(risk.category || '—').replace(/_/g, ' ')} />
            <Field label="Department" value={(risk.department || '—').toUpperCase()} />
          </div>
        </div>
      )}

      {tab === 'treatment' && (
        <div className="grid grid-cols-1 lg:grid-cols-2 gap-5">
          <div className="border border-[rgba(0,0,0,0.10)] rounded-xl bg-white p-5">
            <TreatmentReduction from={risk.riskScore} to={risk.riskScore} />
            <div className="grid grid-cols-3 gap-3 mt-5">
              <div><div className="text-[11px] text-gray-400">Likelihood</div><div className="text-[13px] text-gray-800">{likelihoodLabel(risk.likelihood)}</div></div>
              <div><div className="text-[11px] text-gray-400">Impact</div><div className="text-[13px] text-gray-800">{impactLabel(risk.impact)}</div></div>
              <div><div className="text-[11px] text-gray-400">Task completion</div><div className="text-[13px] text-emerald-600 font-semibold">0%</div></div>
            </div>
          </div>
          <div className="border border-[rgba(0,0,0,0.10)] rounded-xl bg-white p-5">
            <div className="text-[11px] text-gray-400 uppercase tracking-wide mb-3">5×5 Risk Matrix</div>
            <RiskMatrix likelihood={risk.likelihood} impact={risk.impact} />
          </div>
        </div>
      )}

      {tab === 'matrix' && (
        <div className="border border-[rgba(0,0,0,0.10)] rounded-xl bg-white p-5 max-w-md">
          <div className="text-[13px] font-semibold text-gray-900 mb-4">Risk Matrix</div>
          <RiskMatrix likelihood={risk.likelihood} impact={risk.impact} />
        </div>
      )}

      {tab === 'tasks' && <EmptyTab icon="mdi:clipboard-check-outline" text="No mitigation tasks linked to this risk." />}
      {tab === 'comments' && <EmptyTab icon="mdi:comment-outline" text="No comments yet." />}
      {tab === 'activity' && <EmptyTab icon="mdi:history" text="No activity recorded yet." />}
      {tab === 'settings' && (
        <div className="border border-[rgba(0,0,0,0.10)] rounded-xl bg-white p-5 max-w-2xl">
          <div className="grid grid-cols-2 gap-4">
            <Field label="Category" value={(risk.category || '—').replace(/_/g, ' ')} />
            <Field label="Department" value={(risk.department || '—').toUpperCase()} />
          </div>
        </div>
      )}
    </div>
  );
};

export default Soc2RiskDetailPage;
