import React, { useEffect, useMemo, useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { useTopbar } from '../../contexts/TopbarContext';
import { useSoc2ProgramData } from '../../hooks/useSoc2ProgramData';
import {
  Breadcrumbs, CategoryBadge, ComplianceBar, StatusPill, Th, TableCard, Tabs, SearchInput,
  controlStatusPill, Soc2Skeleton,
} from './soc2Ui';

const Soc2FrameworkDetailPage: React.FC = () => {
  const { frameworkId = '' } = useParams<{ frameworkId: string }>();
  const navigate = useNavigate();
  const { setTitle } = useTopbar();
  const { frameworks, controls, isLoading, error } = useSoc2ProgramData();
  const [tab, setTab] = useState<'controls' | 'requirements' | 'history'>('controls');
  const [query, setQuery] = useState('');

  useEffect(() => { setTitle('Program'); return () => setTitle(null); }, [setTitle]);

  const framework = frameworks.find((f) => f.id === frameworkId);

  const filteredControls = useMemo(() => {
    const q = query.trim().toLowerCase();
    if (!q) return controls;
    return controls.filter((c) =>
      c.name.toLowerCase().includes(q) ||
      (c.requirements || []).some((r) => (r.identifier || '').toLowerCase().includes(q)));
  }, [controls, query]);

  const requirements = useMemo(() => {
    const map = new Map<string, string>();
    controls.forEach((c) => (c.requirements || []).forEach((r) => {
      if (r.identifier) map.set(r.identifier, r.name || '');
    }));
    return Array.from(map.entries())
      .map(([identifier, name]) => ({ identifier, name }))
      .sort((a, b) => a.identifier.localeCompare(b.identifier, undefined, { numeric: true }));
  }, [controls]);

  if (isLoading) return <Soc2Skeleton variant="detail" />;
  if (error) return <div className="p-6 text-sm text-red-600">Failed to load: {error.message}</div>;

  const total = framework?.controlsTotal ?? controls.length;
  const completed = framework?.controlsCompleted ?? controls.filter((c) => c.compliancePercent >= 100).length;
  const remaining = total - completed;
  const percent = framework?.compliancePercent ?? 0;

  return (
    <div className="p-6">
      <Breadcrumbs items={[
        { label: 'Frameworks', to: '/soc2-program/frameworks' },
        { label: framework?.name || 'SOC 2' },
      ]} />

      <div className="flex items-start justify-between gap-4 mb-1">
        <div>
          <h1 className="text-2xl font-semibold text-gray-900">{framework?.name || 'SOC 2'}</h1>
          <p className="text-[13px] text-gray-500 mt-0.5">{framework?.description || 'SOC 2 Type I & II'}</p>
        </div>
      </div>

      <div className="mt-4">
        <Tabs
          tabs={[
            { id: 'controls', label: 'Controls', count: total },
            { id: 'requirements', label: 'Requirements', count: requirements.length },
            { id: 'history', label: 'History' },
          ]}
          active={tab}
          onSelect={(id) => setTab(id as typeof tab)}
        />
      </div>

      {tab === 'controls' && (
        <>
          <div className="flex items-center gap-4 mb-2">
            <span className="px-2 py-0.5 rounded-full text-[11px] font-semibold bg-emerald-50 text-emerald-700 border border-emerald-200">
              {percent}% COMPLIANT
            </span>
            <span className="text-[12px] text-gray-500">{completed} completed</span>
            <span className="text-[12px] text-gray-500">{remaining} remaining</span>
            <span className="text-[12px] text-gray-500">{total} total controls</span>
          </div>
          <div className="w-full h-2 rounded-full bg-gray-100 overflow-hidden mb-4">
            <div className="h-full bg-emerald-500 rounded-full" style={{ width: `${percent}%`, transition: 'width 0.4s ease' }} />
          </div>

          <div className="mb-3"><SearchInput value={query} onChange={setQuery} placeholder="Search controls…" /></div>

          <TableCard>
            <thead className="bg-gray-50 border-b border-gray-200">
              <tr>
                <Th>Name</Th><Th>Requirement</Th><Th>Compliance</Th><Th>Status</Th>
                <Th>Policies</Th><Th>Tasks</Th><Th>Documents</Th>
              </tr>
            </thead>
            <tbody>
              {filteredControls.map((c) => {
                const pill = controlStatusPill(c.compliancePercent);
                const reqText = (c.requirements || []).map((r) => r.identifier).filter(Boolean).join(', ');
                return (
                  <tr
                    key={c.id}
                    onClick={() => navigate(`/soc2-program/frameworks/${frameworkId}/controls/${c.id}`)}
                    className="border-t border-gray-100 hover:bg-gray-50/60 cursor-pointer"
                  >
                    <td className="p-3 text-[13px] text-gray-900 font-medium">{c.name}</td>
                    <td className="p-3 text-[12px] text-gray-500 max-w-xs truncate" title={reqText}>{reqText || '—'}</td>
                    <td className="p-3"><ComplianceBar percent={c.compliancePercent} /></td>
                    <td className="p-3"><StatusPill text={pill.text} tone={pill.tone} /></td>
                    <td className="p-3 text-[12px] text-gray-600 tabular-nums">{c.policiesPublished}/{c.policiesTotal}</td>
                    <td className="p-3 text-[12px] text-gray-600 tabular-nums">{c.tasksDone}/{c.tasksTotal}</td>
                    <td className="p-3 text-[12px] text-gray-600 tabular-nums">{c.documentsCompleted}/{c.documentsTotal}</td>
                  </tr>
                );
              })}
              {filteredControls.length === 0 && (
                <tr><td colSpan={7} className="p-6 text-center text-[13px] text-gray-400">No controls match “{query}”.</td></tr>
              )}
            </tbody>
          </TableCard>
        </>
      )}

      {tab === 'requirements' && (
        <TableCard>
          <thead className="bg-gray-50 border-b border-gray-200">
            <tr><Th>Identifier</Th><Th>Requirement</Th></tr>
          </thead>
          <tbody>
            {requirements.map((r) => (
              <tr key={r.identifier} className="border-t border-gray-100">
                <td className="p-3 w-32"><CategoryBadge category={r.identifier} /></td>
                <td className="p-3 text-[13px] text-gray-700">{r.name || '—'}</td>
              </tr>
            ))}
            {requirements.length === 0 && (
              <tr><td colSpan={2} className="p-6 text-center text-[13px] text-gray-400">No requirements.</td></tr>
            )}
          </tbody>
        </TableCard>
      )}

      {tab === 'history' && (
        <div className="border border-[rgba(0,0,0,0.10)] rounded-xl bg-white p-6 text-[13px] text-gray-500">
          No framework changes recorded yet.
        </div>
      )}
    </div>
  );
};

export default Soc2FrameworkDetailPage;
