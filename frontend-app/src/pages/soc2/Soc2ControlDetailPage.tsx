import React, { useEffect, useMemo, useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { Icon } from '@iconify/react';
import { useTopbar } from '../../contexts/TopbarContext';
import { useSoc2ProgramData } from '../../hooks/useSoc2ProgramData';
import {
  Breadcrumbs, ComplianceBar, StatusPill, policyTone, Th, TableCard, Tabs, controlStatusPill,
} from './soc2Ui';

const taskStatusTone = (s: string | null) => {
  const v = (s || '').toLowerCase();
  if (v === 'done' || v === 'completed') return 'success' as const;
  if (v === 'in_progress' || v === 'in_review') return 'warning' as const;
  if (v === 'failed') return 'danger' as const;
  return 'neutral' as const;
};

const Soc2ControlDetailPage: React.FC = () => {
  const { frameworkId = '', controlId = '' } = useParams<{ frameworkId: string; controlId: string }>();
  const navigate = useNavigate();
  const { setTitle } = useTopbar();
  const { frameworks, controls, tasks, isLoading, error } = useSoc2ProgramData();
  const [tab, setTab] = useState<'policies' | 'tasks' | 'documents'>('policies');

  useEffect(() => { setTitle('Program'); return () => setTitle(null); }, [setTitle]);

  const framework = frameworks.find((f) => f.id === frameworkId);
  const control = controls.find((c) => c.id === controlId);

  // Enrich the control's tasks with owner/description from the full task list.
  const controlTasks = useMemo(() => {
    if (!control) return [];
    return (control.tasks || []).map((t) => {
      const full = tasks.find((x) => x.id === t.id);
      return { ...t, assigneeName: full?.assigneeName ?? null, description: full?.description ?? null };
    });
  }, [control, tasks]);

  if (isLoading) return <div className="p-6 text-gray-500 text-sm">Loading control…</div>;
  if (error) return <div className="p-6 text-sm text-red-600">Failed to load: {error.message}</div>;
  if (!control) return <div className="p-6 text-sm text-gray-500">Control not found.</div>;

  const reqId = control.requirements?.[0]?.identifier || control.category;
  const pill = controlStatusPill(control.compliancePercent);

  return (
    <div className="p-6">
      <Breadcrumbs items={[
        { label: 'Frameworks', to: '/soc2-program/frameworks' },
        { label: framework?.name || 'SOC 2', to: `/soc2-program/frameworks/${frameworkId}` },
        { label: reqId },
        { label: control.name },
      ]} />

      <div className="flex items-start justify-between gap-4">
        <div>
          <h1 className="text-2xl font-semibold text-gray-900">{control.name}</h1>
          {control.description && <p className="text-[13px] text-gray-500 mt-0.5 max-w-2xl">{control.description}</p>}
        </div>
        <div className="flex items-center gap-3 shrink-0">
          <StatusPill text={pill.text} tone={pill.tone} />
          <ComplianceBar percent={control.compliancePercent} />
        </div>
      </div>

      <div className="mt-5">
        <Tabs
          tabs={[
            { id: 'policies', label: 'Policies', count: control.policies?.length ?? 0 },
            { id: 'tasks', label: 'Tasks', count: controlTasks.length },
            { id: 'documents', label: 'Documents', count: control.documentsTotal },
          ]}
          active={tab}
          onSelect={(id) => setTab(id as typeof tab)}
        />
      </div>

      {tab === 'policies' && (
        <TableCard>
          <thead className="bg-gray-50 border-b border-gray-200">
            <tr><Th>Name</Th><Th>Status</Th></tr>
          </thead>
          <tbody>
            {(control.policies || []).map((p) => (
              <tr
                key={p.id}
                onClick={() => navigate(`/soc2-program/policies/${p.id}`)}
                className="border-t border-gray-100 hover:bg-gray-50/60 cursor-pointer"
              >
                <td className="p-3 text-[13px] text-gray-900 font-medium">{p.name}</td>
                <td className="p-3"><StatusPill text={(p.status || '').replace(/_/g, ' ')} tone={policyTone(p.status)} /></td>
              </tr>
            ))}
            {(control.policies || []).length === 0 && (
              <tr><td colSpan={2} className="p-6 text-center text-[13px] text-gray-400">No policies linked.</td></tr>
            )}
          </tbody>
        </TableCard>
      )}

      {tab === 'tasks' && (
        <TableCard>
          <thead className="bg-gray-50 border-b border-gray-200">
            <tr><Th>Task</Th><Th>Owner</Th><Th>Status</Th></tr>
          </thead>
          <tbody>
            {controlTasks.map((t) => (
              <tr key={t.id} className="border-t border-gray-100">
                <td className="p-3">
                  <div className="text-[13px] text-gray-900 font-medium">{t.title}</div>
                  {t.description && <div className="text-[11px] text-gray-400 max-w-xl truncate">{t.description}</div>}
                </td>
                <td className="p-3 text-[12px] text-gray-600">{t.assigneeName || <span className="text-gray-400 italic">Unassigned</span>}</td>
                <td className="p-3"><StatusPill text={(t.status || '').replace(/_/g, ' ')} tone={taskStatusTone(t.status)} /></td>
              </tr>
            ))}
            {controlTasks.length === 0 && (
              <tr><td colSpan={3} className="p-6 text-center text-[13px] text-gray-400">No tasks linked.</td></tr>
            )}
          </tbody>
        </TableCard>
      )}

      {tab === 'documents' && (
        <div className="border border-[rgba(0,0,0,0.10)] rounded-xl bg-white p-8 flex flex-col items-center justify-center text-center">
          <Icon icon="mdi:file-document-outline" width={28} className="text-gray-300 mb-2" />
          <p className="text-[13px] text-gray-500">No documents linked to this control.</p>
        </div>
      )}
    </div>
  );
};

export default Soc2ControlDetailPage;
