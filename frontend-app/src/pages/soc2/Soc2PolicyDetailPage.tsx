import React, { useEffect, useMemo, useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { Icon } from '@iconify/react';
import { useTopbar } from '../../contexts/TopbarContext';
import { useSoc2ProgramData } from '../../hooks/useSoc2ProgramData';
import { publishSoc2Policy, setSoc2PolicyArchived } from '../../api/soc2Api';
import { Breadcrumbs, StatusPill, policyTone, CategoryBadge, Th, TableCard, Tabs, fmtDate } from './soc2Ui';

type PolicyTab = 'overview' | 'content' | 'mappings' | 'versions' | 'activity' | 'comments';

const MetaRow: React.FC<{ label: string; value: React.ReactNode }> = ({ label, value }) => (
  <div className="flex items-start justify-between py-2.5 border-b border-gray-100 last:border-0">
    <span className="text-[12px] text-gray-500">{label}</span>
    <span className="text-[13px] text-gray-800 text-right">{value}</span>
  </div>
);

const Soc2PolicyDetailPage: React.FC = () => {
  const { policyId = '' } = useParams<{ policyId: string }>();
  const navigate = useNavigate();
  const { setTitle } = useTopbar();
  const { policies, controls, isLoading, error, refetch } = useSoc2ProgramData();
  const [tab, setTab] = useState<PolicyTab>('overview');
  const [contentView, setContentView] = useState<'editor' | 'pdf'>('editor');
  const [busy, setBusy] = useState(false);

  useEffect(() => { setTitle('Program'); return () => setTitle(null); }, [setTitle]);

  const policy = policies.find((p) => p.id === policyId);

  // Controls this policy is linked to (derive the mapping from the control side).
  const mappings = useMemo(() => {
    if (!policy) return [];
    return controls
      .filter((c) => (c.policies || []).some((p) => p.id === policy.id))
      .map((c) => ({ id: c.id, name: c.name, requirement: c.requirements?.[0]?.identifier || c.category }));
  }, [controls, policy]);

  if (isLoading) return <div className="p-6 text-gray-500 text-sm">Loading policy…</div>;
  if (error) return <div className="p-6 text-sm text-red-600">Failed to load: {error.message}</div>;
  if (!policy) return <div className="p-6 text-sm text-gray-500">Policy not found.</div>;

  const isPublished = (policy.status || '').toLowerCase() === 'published';
  const isArchived = (policy.status || '').toLowerCase() === 'archived';
  const wordCount = policy.content ? policy.content.trim().split(/\s+/).filter(Boolean).length : 0;

  const doPublish = async () => { setBusy(true); try { await publishSoc2Policy(policy.id); await refetch(); } finally { setBusy(false); } };
  const doArchive = async () => { setBusy(true); try { await setSoc2PolicyArchived(policy.id, !isArchived); await refetch(); } finally { setBusy(false); } };

  return (
    <div className="p-6">
      <Breadcrumbs items={[{ label: 'Policies', to: '/soc2-program/policies' }, { label: policy.name }]} />

      <div className="flex items-start justify-between gap-4">
        <div className="flex items-center gap-3">
          <h1 className="text-2xl font-semibold text-gray-900">{policy.name}</h1>
          <StatusPill text={(policy.status || '').replace(/_/g, ' ')} tone={policyTone(policy.status)} />
        </div>
        <div className="flex items-center gap-2 shrink-0">
          {!isPublished && (
            <button onClick={doPublish} disabled={busy}
              className="px-3 py-1.5 text-[12px] font-medium rounded-md bg-[#1a1a1a] text-white hover:bg-black disabled:opacity-40">
              {busy ? 'Publishing…' : 'Publish'}
            </button>
          )}
          <button onClick={doArchive} disabled={busy}
            className="px-3 py-1.5 text-[12px] font-medium rounded-md border border-gray-300 bg-white text-gray-700 hover:bg-gray-50 disabled:opacity-40">
            {isArchived ? 'Unarchive' : 'Archive'}
          </button>
        </div>
      </div>

      <div className="mt-5">
        <Tabs
          tabs={[
            { id: 'overview', label: 'Overview' },
            { id: 'content', label: 'Content' },
            { id: 'mappings', label: 'Mappings', count: mappings.length },
            { id: 'versions', label: 'Versions' },
            { id: 'activity', label: 'Activity' },
            { id: 'comments', label: 'Comments' },
          ]}
          active={tab}
          onSelect={(id) => setTab(id as PolicyTab)}
        />
      </div>

      {tab === 'overview' && (
        <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
          <div className="border border-[rgba(0,0,0,0.10)] rounded-xl bg-white p-5">
            {policy.description && <p className="text-[13px] text-gray-600 mb-3">{policy.description}</p>}
            <MetaRow label="Status" value={<StatusPill text={(policy.status || '').replace(/_/g, ' ')} tone={policyTone(policy.status)} />} />
            <MetaRow label="Review cadence" value={<span className="capitalize">{policy.frequency || '—'}</span>} />
            <MetaRow label="Owning department" value={policy.department ? policy.department.toUpperCase() : '—'} />
            <MetaRow label="Employee sign-off" value={policy.requiresSignature ? 'Required' : 'Not required'} />
            <MetaRow label="Next review" value={fmtDate(policy.reviewDate)} />
            <MetaRow label="Last published" value={fmtDate(policy.lastPublishedAt)} />
            <MetaRow label="Last updated" value={fmtDate(policy.updatedAt)} />
          </div>
          <div className="border border-[rgba(0,0,0,0.10)] rounded-xl bg-white p-5">
            <div className="text-[12px] font-semibold text-gray-500 uppercase tracking-wide mb-2">Mapped controls</div>
            {mappings.length === 0
              ? <p className="text-[13px] text-gray-400">Not mapped to any control.</p>
              : <div className="flex flex-col gap-2">
                  {mappings.map((m) => (
                    <div key={m.id} className="flex items-center gap-2">
                      <CategoryBadge category={m.requirement} />
                      <span className="text-[13px] text-gray-700">{m.name}</span>
                    </div>
                  ))}
                </div>}
          </div>
        </div>
      )}

      {tab === 'content' && (
        <div className="border border-[rgba(0,0,0,0.10)] rounded-xl bg-white">
          <div className="flex items-center justify-between px-4 py-3 border-b border-gray-100">
            <div className="inline-flex rounded-lg border border-gray-200 overflow-hidden">
              {(['editor', 'pdf'] as const).map((v) => (
                <button key={v} onClick={() => setContentView(v)}
                  className={`px-3 py-1 text-[12px] font-medium ${contentView === v ? 'bg-gray-900 text-white' : 'bg-white text-gray-600 hover:bg-gray-50'}`}>
                  {v === 'editor' ? 'Editor View' : 'PDF View'}
                </button>
              ))}
            </div>
            <span className="text-[12px] text-gray-400">{wordCount} words</span>
          </div>
          <div className={contentView === 'pdf' ? 'p-8 bg-gray-50' : 'p-5'}>
            <div className={contentView === 'pdf' ? 'max-w-2xl mx-auto bg-white border border-gray-200 rounded-lg p-8 shadow-sm' : ''}>
              {policy.content
                ? <p className="text-[13px] text-gray-700 whitespace-pre-line leading-relaxed">{policy.content}</p>
                : <p className="text-[12px] text-gray-400">No document content.</p>}
            </div>
          </div>
        </div>
      )}

      {tab === 'mappings' && (
        <TableCard>
          <thead className="bg-gray-50 border-b border-gray-200">
            <tr><Th>Requirement</Th><Th>Control</Th></tr>
          </thead>
          <tbody>
            {mappings.map((m) => (
              <tr key={m.id} className="border-t border-gray-100">
                <td className="p-3 w-40"><CategoryBadge category={m.requirement} /></td>
                <td className="p-3 text-[13px] text-gray-700">{m.name}</td>
              </tr>
            ))}
            {mappings.length === 0 && (
              <tr><td colSpan={2} className="p-6 text-center text-[13px] text-gray-400">Not mapped to any control.</td></tr>
            )}
          </tbody>
        </TableCard>
      )}

      {tab === 'versions' && (
        <TableCard>
          <thead className="bg-gray-50 border-b border-gray-200">
            <tr><Th>Version</Th><Th>Status</Th><Th>Date</Th><Th>Author</Th></tr>
          </thead>
          <tbody>
            <tr className="border-t border-gray-100">
              <td className="p-3 text-[13px] text-gray-900 font-medium">v1</td>
              <td className="p-3"><StatusPill text={(policy.status || '').replace(/_/g, ' ')} tone={policyTone(policy.status)} /></td>
              <td className="p-3 text-[12px] text-gray-600">{fmtDate(policy.lastPublishedAt || policy.updatedAt)}</td>
              <td className="p-3 text-[12px] text-gray-600">Rohan Kapoor</td>
            </tr>
          </tbody>
        </TableCard>
      )}

      {tab === 'activity' && (
        <div className="border border-[rgba(0,0,0,0.10)] rounded-xl bg-white p-5">
          <ol className="relative border-l border-gray-200 ml-2">
            {isPublished && (
              <li className="mb-5 ml-4">
                <div className="absolute w-2.5 h-2.5 bg-emerald-500 rounded-full -left-[5px] mt-1.5" />
                <div className="text-[13px] text-gray-800">Published version 1</div>
                <div className="text-[11px] text-gray-400">{fmtDate(policy.lastPublishedAt)} · Rohan Kapoor</div>
              </li>
            )}
            <li className="ml-4">
              <div className="absolute w-2.5 h-2.5 bg-gray-300 rounded-full -left-[5px] mt-1.5" />
              <div className="text-[13px] text-gray-800">Policy created from template</div>
              <div className="text-[11px] text-gray-400">{fmtDate(policy.updatedAt)} · Rohan Kapoor</div>
            </li>
          </ol>
        </div>
      )}

      {tab === 'comments' && (
        <div className="border border-[rgba(0,0,0,0.10)] rounded-xl bg-white p-8 flex flex-col items-center justify-center text-center">
          <Icon icon="mdi:comment-outline" width={26} className="text-gray-300 mb-2" />
          <p className="text-[13px] text-gray-500">No comments yet.</p>
        </div>
      )}
    </div>
  );
};

export default Soc2PolicyDetailPage;
