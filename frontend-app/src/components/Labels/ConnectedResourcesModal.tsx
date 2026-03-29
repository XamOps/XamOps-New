import React, { useEffect, useState } from 'react';
import { createPortal } from 'react-dom';
import type { ResourceLabel, ResourceRef } from '../../types/resourceLabels';
import type { ConnectedResource } from '../../services/resourceLabelsApi';
import { fetchConnectedResources, assignLabel, removeLabel } from '../../services/resourceLabelsApi';
import type { CloudResource } from '../../types/cloudlist';
import LabelBadge from './LabelBadge';
import LabelAssignPopover from './LabelAssignPopover';

// ── Resource type icons / colors ─────────────────────────────────────────────

const TYPE_META: Record<string, { icon: string; bg: string; text: string }> = {
  'EBS Volume':   { icon: 'fa-hdd',               bg: 'bg-orange-100',  text: 'text-orange-600' },
  'Elastic IP':   { icon: 'fa-globe',              bg: 'bg-blue-100',    text: 'text-blue-600'   },
  'Load Balancer':{ icon: 'fa-balance-scale',      bg: 'bg-purple-100',  text: 'text-purple-600' },
  'EC2 Instance': { icon: 'fa-server',             bg: 'bg-indigo-100',  text: 'text-indigo-600' },
  'NAT Gateway':  { icon: 'fa-network-wired',      bg: 'bg-teal-100',    text: 'text-teal-600'   },
};
const DEFAULT_META = { icon: 'fa-cube', bg: 'bg-gray-100', text: 'text-gray-600' };

// ── Supported resource types ──────────────────────────────────────────────────
export const CONNECTED_SUPPORTED_TYPES = new Set(['EC2 Instance', 'Load Balancer']);

// ── Props ─────────────────────────────────────────────────────────────────────

interface Props {
  accountId: string;
  sourceResource: CloudResource;
  allLabels: ResourceLabel[];
  /** Current label map from parent CloudList (resourceId → labels[]) */
  labelMap: Record<string, ResourceLabel[]>;
  onLabelMapChange: (resourceId: string, updatedLabels: ResourceLabel[]) => void;
  onLabelCreated?: (label: ResourceLabel) => void;
  onClose: () => void;
}

// ── Component ─────────────────────────────────────────────────────────────────

const ConnectedResourcesModal: React.FC<Props> = ({
  accountId,
  sourceResource,
  allLabels,
  labelMap,
  onLabelMapChange,
  onLabelCreated,
  onClose,
}) => {
  const [resources, setResources] = useState<ConnectedResource[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [openPopoverId, setOpenPopoverId] = useState<string | null>(null);
  const [applyingAll, setApplyingAll] = useState(false);
  const [applyDone, setApplyDone] = useState(false);

  // Local label map for connected resources (kept separate from parent so we
  // can update immediately without a full page reload)
  const [localLabelMap, setLocalLabelMap] = useState<Record<string, ResourceLabel[]>>({});

  useEffect(() => {
    setLoading(true);
    setError(null);
    fetchConnectedResources(accountId, sourceResource.id, sourceResource.type, sourceResource.region)
      .then(list => {
        setResources(list);
        // Seed local label map from the response
        const initial: Record<string, ResourceLabel[]> = {};
        list.forEach(r => { initial[r.id] = r.labels as ResourceLabel[]; });
        setLocalLabelMap(initial);
      })
      .catch(err => setError(err?.message ?? 'Failed to load connected resources'))
      .finally(() => setLoading(false));
  }, [accountId, sourceResource.id, sourceResource.type, sourceResource.region]);

  const handleLabelChange = (resourceId: string, updatedLabels: ResourceLabel[]) => {
    setLocalLabelMap(prev => ({ ...prev, [resourceId]: updatedLabels }));
    onLabelMapChange(resourceId, updatedLabels);
  };

  const sourceLabels = labelMap[sourceResource.id] ?? [];

  /**
   * Sync parent labels to every connected resource:
   *   - ADD labels the parent has that the child is missing
   *   - REMOVE labels the child has that are no longer on the parent
   * Result: each connected resource ends up with exactly the parent's label set.
   */
  const syncParentLabels = async () => {
    setApplyingAll(true);
    const parentIds = new Set(sourceLabels.map(l => l.id));
    try {
      for (const connected of resources) {
        const currentLabels = localLabelMap[connected.id] ?? [];
        const currentIds = new Set(currentLabels.map(l => l.id));
        const ref: ResourceRef = {
          resourceId: connected.id,
          resourceType: connected.type,
          resourceName: connected.name ?? undefined,
          region: connected.region,
        };

        // Add labels present on parent but missing from child
        const toAdd = sourceLabels.filter(l => !currentIds.has(l.id));
        for (const label of toAdd) {
          await assignLabel({ accountId, labelId: label.id, resources: [ref] });
        }

        // Remove labels present on child but no longer on parent
        const toRemove = currentLabels.filter(l => !parentIds.has(l.id));
        for (const label of toRemove) {
          await removeLabel({ accountId, labelId: label.id, resources: [ref] });
        }

        if (toAdd.length > 0 || toRemove.length > 0) {
          const synced = sourceLabels.slice();
          setLocalLabelMap(prev => ({ ...prev, [connected.id]: synced }));
          onLabelMapChange(connected.id, synced);
        }
      }
      setApplyDone(true);
      setTimeout(() => setApplyDone(false), 2500);
    } finally {
      setApplyingAll(false);
    }
  };

  // Count connected resources that don't exactly match the parent's label set
  const outOfSyncCount = resources.filter(r => {
    const childIds = new Set((localLabelMap[r.id] ?? []).map(l => l.id));
    const parentIds = new Set(sourceLabels.map(l => l.id));
    return childIds.size !== parentIds.size ||
      sourceLabels.some(l => !childIds.has(l.id)) ||
      (localLabelMap[r.id] ?? []).some(l => !parentIds.has(l.id));
  }).length;

  // Lock body scroll while modal is open
  useEffect(() => {
    document.body.style.overflow = 'hidden';
    return () => { document.body.style.overflow = ''; };
  }, []);

  // Render via portal so position:fixed works correctly regardless of parent overflow/transform
  return createPortal(
    // Backdrop
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 backdrop-blur-sm"
      onClick={onClose}
    >
      {/* Modal panel */}
      <div
        className="relative bg-white rounded-2xl shadow-2xl w-full max-w-2xl mx-4 max-h-[85vh] flex flex-col"
        onClick={e => e.stopPropagation()}
      >
        {/* Header */}
        <div className="flex items-center justify-between px-6 py-4 bg-gradient-to-r from-indigo-600 to-violet-600 rounded-t-2xl">
          <div>
            <div className="flex items-center gap-2">
              <i className="fas fa-link text-indigo-200 text-sm" />
              <span className="text-white font-bold text-base">Connected Resources</span>
            </div>
            <p className="text-indigo-200 text-xs mt-0.5">
              <span className="font-semibold text-white">{sourceResource.name ?? sourceResource.id}</span>
              {' '}·{' '}{sourceResource.type}{' '}·{' '}{sourceResource.id}{' '}·{' '}{sourceResource.region}
            </p>
          </div>
          <button
            type="button"
            onClick={onClose}
            className="w-8 h-8 flex items-center justify-center rounded-full bg-white/10 hover:bg-white/20 text-white transition-colors"
          >
            <i className="fas fa-times text-sm" />
          </button>
        </div>

        {/* Body */}
        <div className="flex-1 overflow-y-auto px-6 py-4">
          {loading && (
            <div className="flex flex-col items-center justify-center py-16 gap-3">
              <i className="fas fa-spinner fa-spin text-indigo-500 text-2xl" />
              <p className="text-sm text-gray-500">Discovering connected resources…</p>
            </div>
          )}

          {!loading && error && (
            <div className="flex flex-col items-center justify-center py-12 gap-2 text-center">
              <i className="fas fa-exclamation-circle text-red-400 text-2xl" />
              <p className="text-sm text-red-600 font-medium">{error}</p>
            </div>
          )}

          {!loading && !error && resources.length === 0 && (
            <div className="flex flex-col items-center justify-center py-16 gap-2 text-center">
              <i className="fas fa-project-diagram text-gray-300 text-3xl" />
              <p className="text-sm text-gray-500 font-medium">No connected resources found</p>
              <p className="text-xs text-gray-400">
                No EBS volumes, Elastic IPs or Load Balancers were detected for this resource.
              </p>
            </div>
          )}

          {!loading && !error && resources.length > 0 && (
            <div className="space-y-3">
              {/* Summary bar */}
              <div className="flex items-center justify-between text-xs text-gray-500 pb-1 border-b border-gray-100">
                <span>
                  <span className="font-semibold text-gray-700">{resources.length}</span> connected resource{resources.length !== 1 ? 's' : ''} found
                  {outOfSyncCount > 0 && (
                    <span className="ml-2 text-amber-600 font-medium">· {outOfSyncCount} out of sync</span>
                  )}
                </span>
              </div>

              {resources.map(resource => {
                const meta = TYPE_META[resource.type] ?? DEFAULT_META;
                const resourceLabels = localLabelMap[resource.id] ?? [];
                const resourceRef: ResourceRef = {
                  resourceId: resource.id,
                  resourceType: resource.type,
                  resourceName: resource.name ?? undefined,
                  region: resource.region,
                };

                return (
                  <div
                    key={resource.id}
                    className="flex items-start gap-3 p-3 rounded-xl border border-gray-100 hover:border-indigo-100 hover:bg-indigo-50/30 transition-colors"
                  >
                    {/* Type icon */}
                    <div className={`w-9 h-9 flex-shrink-0 flex items-center justify-center rounded-lg ${meta.bg}`}>
                      <i className={`fas ${meta.icon} ${meta.text} text-sm`} />
                    </div>

                    {/* Info */}
                    <div className="flex-1 min-w-0">
                      <div className="flex items-center gap-2 flex-wrap">
                        <span className="font-semibold text-gray-800 text-sm truncate max-w-[200px]">
                          {resource.name ?? resource.id}
                        </span>
                        <span className="text-xs text-gray-400 font-mono truncate max-w-[160px]">{resource.id}</span>
                        <span className="text-xs bg-gray-100 text-gray-500 px-1.5 py-0.5 rounded-full">{resource.type}</span>
                      </div>

                      {/* Details chips */}
                      {resource.details && Object.keys(resource.details).length > 0 && (
                        <div className="flex items-center gap-1.5 mt-1 flex-wrap">
                          {Object.entries(resource.details).map(([k, v]) => v ? (
                            <span key={k} className="text-xs text-gray-500">
                              <span className="text-gray-400">{k}:</span> {v}
                            </span>
                          ) : null)}
                          <span className="text-xs text-gray-400">· {resource.region}</span>
                        </div>
                      )}

                      {/* Label chips + add button */}
                      <div className="flex items-center gap-1 mt-2 flex-wrap">
                        {resourceLabels.length === 0 ? (
                          <span className="text-xs text-gray-400 italic">No labels</span>
                        ) : (
                          resourceLabels.map(lbl => (
                            <LabelBadge
                              key={lbl.id}
                              label={lbl}
                              onRemove={() => {
                                const updated = resourceLabels.filter(l => l.id !== lbl.id);
                                handleLabelChange(resource.id, updated);
                              }}
                            />
                          ))
                        )}

                        {/* + label button */}
                        <div className="relative">
                          <button
                            type="button"
                            onClick={() => setOpenPopoverId(
                              openPopoverId === resource.id ? null : resource.id
                            )}
                            className="w-5 h-5 flex items-center justify-center rounded-full border border-gray-300 text-gray-400 hover:border-indigo-400 hover:text-indigo-500 text-xs transition-colors"
                            title="Assign label"
                          >
                            +
                          </button>
                          {openPopoverId === resource.id && (
                            <LabelAssignPopover
                              accountId={accountId}
                              resource={resourceRef}
                              assignedLabels={resourceLabels}
                              allLabels={allLabels}
                              onChanged={handleLabelChange}
                              onClose={() => setOpenPopoverId(null)}
                              onLabelCreated={onLabelCreated}
                            />
                          )}
                        </div>
                      </div>
                    </div>
                  </div>
                );
              })}
            </div>
          )}
        </div>

        {/* Footer */}
        {!loading && !error && resources.length > 0 && (
          <div className="px-6 py-4 border-t border-gray-100 bg-gray-50/60 rounded-b-2xl">
            <div className="flex items-center justify-between gap-4">
              <div className="text-xs text-gray-500">
                <span className="font-medium text-gray-700">Parent labels: </span>
                {sourceLabels.length === 0
                  ? <span className="italic text-gray-400">none</span>
                  : sourceLabels.map(l => (
                    <span
                      key={l.id}
                      className="inline-flex items-center gap-1 mr-1.5 px-2 py-0.5 rounded-full text-white text-xs font-medium"
                      style={{ backgroundColor: l.color }}
                    >
                      {l.name}
                    </span>
                  ))}
              </div>
              <button
                type="button"
                onClick={syncParentLabels}
                disabled={applyingAll || outOfSyncCount === 0}
                className="flex items-center gap-2 px-4 py-2 text-xs font-bold rounded-xl bg-indigo-600 text-white hover:bg-indigo-700 disabled:opacity-40 disabled:cursor-not-allowed transition-colors whitespace-nowrap"
              >
                {applyingAll ? (
                  <><i className="fas fa-spinner fa-spin" /> Syncing…</>
                ) : applyDone ? (
                  <><i className="fas fa-check" /> Synced!</>
                ) : (
                  <><i className="fas fa-sync-alt" /> Sync parent labels to all</>
                )}
              </button>
            </div>
          </div>
        )}
      </div>
    </div>,
    document.body
  );
};

export default ConnectedResourcesModal;
