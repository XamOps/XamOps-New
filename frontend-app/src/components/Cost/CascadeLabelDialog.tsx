import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { createPortal } from 'react-dom';
import { Icon } from '@iconify/react';
import {
  bulkAssignLabel,
  fetchConnectedResources,
  type ConnectedResource,
} from '../../services/resourceLabelsApi';
import type { ResourceLabel, ResourceRef } from '../../types/resourceLabels';
import type { ResourceCost } from '../../api/curInsightsApi';
import LabelBadge from '../Labels/LabelBadge';
import { connectedLookupFor } from './connectedLookup';

/**
 * After a label is put on one resource in the cost view, this asks whether to carry it to the
 * resources connected to it — and lets the reader shape that set before applying.
 *
 * <h2>Why a second step rather than an automatic cascade</h2>
 * The server labels exactly what it is told to; there is no silent fan-out. A label on an EC2
 * instance is often meant for the volumes, addresses and balancer that only exist to serve it, but
 * "often" is not "always" — a shared load balancer in front of two environments must not inherit
 * one of them. So the spread is offered, pre-ticked, and editable, rather than done behind the
 * reader's back.
 *
 * <h2>What it does and does not touch</h2>
 * The source resource is already labelled by the time this opens — the per-row control did that.
 * "Just this one" keeps that assignment and labels nothing else. Dismissing the dialog (the close
 * button, Escape or a click outside) is a cancel: the caller takes the label back off the source,
 * because a reader who closes a question without answering it has not agreed to anything.
 *
 * <p>The connected set comes from {@code GET /connected-resources}, which reads live AWS
 * relationships (a volume's attachment, a balancer's targets) rather than a stored graph. The
 * reader can untick any of them, and add others by hand from the resources the panel already
 * lists for this service — discovery covers infrastructure neighbours, not everything a person
 * might consider related.
 */

interface AddableResource {
  resourceId: string;
  name: string;
  type: string;
  region: string;
}

interface CascadeLabelDialogProps {
  accountId: string;
  /** The resource the label was just put on. Its connected resources are discovered from this. */
  source: ResourceCost;
  /** The label to spread. Already on the source. */
  label: ResourceLabel;
  /**
   * The other resources this service lists, offered as hand-picked additions beyond what
   * discovery finds. The source itself is excluded by the caller.
   */
  candidates: ResourceCost[];
  /** resourceId → labels already on it, so a resource that already carries this label is shown as such. */
  labelMap: Record<string, ResourceLabel[]>;
  /** Called after a successful apply, with the ids that were labelled, so the cache can be updated. */
  onApplied: (resourceIds: string[], label: ResourceLabel) => void;
  /** Done: after "Just this one" or a successful apply. The source keeps the label. */
  onClose: () => void;
  /** Dismissed without choosing. The caller removes the label from the source again. */
  onCancel: () => void;
}

const refFor = (r: { resourceId: string; type: string; name: string; region: string }): ResourceRef => ({
  resourceId: r.resourceId,
  resourceType: r.type,
  resourceName: r.name || undefined,
  region: r.region === 'Global' ? undefined : r.region,
});

const CascadeLabelDialog: React.FC<CascadeLabelDialogProps> = ({
  accountId,
  source,
  label,
  candidates,
  labelMap,
  onApplied,
  onClose,
  onCancel,
}) => {
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [connected, setConnected] = useState<AddableResource[]>([]);
  /** Ids ticked for the spread — connected ones default on, hand-added ones join as on. */
  const [chosen, setChosen] = useState<Set<string>>(() => new Set());
  /** Resources added by hand from this service, kept apart so they list under their own heading. */
  const [added, setAdded] = useState<AddableResource[]>([]);
  const [addOpen, setAddOpen] = useState(false);
  const [addSearch, setAddSearch] = useState('');
  const [applying, setApplying] = useState(false);

  useEffect(() => {
    document.body.style.overflow = 'hidden';
    return () => {
      document.body.style.overflow = '';
    };
  }, []);

  // Not while an apply is in flight: undoing the source then would leave its neighbours labelled
  // and the resource they were labelled for not.
  const dismiss = useCallback(() => {
    if (!applying) onCancel();
  }, [applying, onCancel]);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') dismiss();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [dismiss]);

  // The endpoint's type and id form, derived from the report's id. Null when discovery has no
  // branch for this kind of resource.
  const lookup = useMemo(() => connectedLookupFor(source.resourceId), [source.resourceId]);

  useEffect(() => {
    let live = true;
    if (!lookup) {
      setLoading(false);
      setConnected([]);
      return undefined;
    }
    setLoading(true);
    setError(null);
    const region = lookup.region ?? (source.region === 'Global' ? 'us-east-1' : source.region);
    fetchConnectedResources(accountId, lookup.id, lookup.type, region)
      .then((list: ConnectedResource[]) => {
        if (!live) return;
        const mapped = list
          // A connected resource with no id cannot be labelled, and the source should never list
          // itself as its own neighbour.
          .filter((c) => c.id && c.id !== source.resourceId && c.id !== lookup.id)
          .map((c) => ({
            resourceId: c.id,
            name: c.name ?? c.id,
            type: c.type,
            region: c.region,
          }));
        setConnected(mapped);
        // Pre-tick every discovered resource that does not already carry this label.
        setChosen(
          new Set(
            mapped
              .filter((m) => !(labelMap[m.resourceId] ?? []).some((l) => l.id === label.id))
              .map((m) => m.resourceId),
          ),
        );
      })
      .catch((e) => {
        if (live) setError(e instanceof Error ? e.message : 'Could not load connected resources.');
      })
      .finally(() => {
        if (live) setLoading(false);
      });
    return () => {
      live = false;
    };
  }, [accountId, lookup, source.resourceId, source.region, label.id, labelMap]);

  const toggle = (resourceId: string) =>
    setChosen((prev) => {
      const next = new Set(prev);
      if (next.has(resourceId)) next.delete(resourceId);
      else next.add(resourceId);
      return next;
    });

  // Candidates that are not the source, not already discovered as connected, and not already added.
  const addable = useMemo(() => {
    const taken = new Set<string>([source.resourceId, ...connected.map((c) => c.resourceId), ...added.map((a) => a.resourceId)]);
    const term = addSearch.trim().toLowerCase();
    return candidates
      .filter((c) => c.resourceId && !taken.has(c.resourceId))
      .filter(
        (c) =>
          term === '' ||
          c.resourceId.toLowerCase().includes(term) ||
          (c.name ?? '').toLowerCase().includes(term),
      )
      .slice(0, 50)
      .map((c) => ({ resourceId: c.resourceId, name: c.name || c.resourceId, type: c.service, region: c.region }));
  }, [candidates, connected, added, source.resourceId, addSearch]);

  const addResource = (r: AddableResource) => {
    setAdded((prev) => [...prev, r]);
    setChosen((prev) => new Set(prev).add(r.resourceId));
    setAddSearch('');
    setAddOpen(false);
  };

  const rows: AddableResource[] = useMemo(() => [...connected, ...added], [connected, added]);
  const chosenCount = rows.filter((r) => chosen.has(r.resourceId)).length;

  const apply = async () => {
    const targets = rows.filter((r) => chosen.has(r.resourceId));
    if (targets.length === 0) {
      onClose();
      return;
    }
    setApplying(true);
    setError(null);
    try {
      await bulkAssignLabel(accountId, label.id, targets.map(refFor), 'CASCADE');
      onApplied(
        targets.map((t) => t.resourceId),
        label,
      );
      onClose();
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Could not apply the label.');
      setApplying(false);
    }
  };

  const hasThisLabel = (resourceId: string) =>
    (labelMap[resourceId] ?? []).some((l) => l.id === label.id);

  return createPortal(
    <div
      className="fixed inset-0 z-[70] flex items-center justify-center bg-black/40 backdrop-blur-sm"
      onClick={dismiss}
    >
      <div
        role="dialog"
        aria-label="Apply label to connected resources"
        className="relative bg-white rounded-2xl shadow-2xl w-full max-w-xl mx-4 max-h-[85vh] flex flex-col"
        onClick={(e) => e.stopPropagation()}
      >
        {/* Header */}
        <div className="px-5 py-4 border-b border-[rgba(0,0,0,0.08)] flex items-start justify-between gap-4">
          <div className="min-w-0">
            <p className="text-[15px] font-bold text-black flex items-center gap-2">
              <Icon icon="mdi:source-branch" width={17} className="text-[#2563EB]" />
              Apply to connected resources?
            </p>
            <p className="text-[12px] text-[rgba(0,0,0,0.5)] mt-1 leading-relaxed">
              <LabelBadge label={label} /> is on{' '}
              <span className="font-medium text-black">{source.name || source.resourceId}</span>. Choose which
              of its connected resources should get it too.
            </p>
          </div>
          <button
            type="button"
            onClick={dismiss}
            disabled={applying}
            aria-label="Cancel and remove the label"
            title="Cancel and remove the label"
            className="w-8 h-8 flex-shrink-0 disabled:opacity-40 flex items-center justify-center rounded-lg border border-[rgba(0,0,0,0.1)] text-[rgba(0,0,0,0.4)] hover:text-black hover:bg-[rgba(0,0,0,0.04)] transition-colors"
          >
            <Icon icon="mdi:close" width={15} />
          </button>
        </div>

        {/* Body */}
        <div className="flex-1 overflow-y-auto px-5 py-4">
          {loading && (
            <div className="flex flex-col items-center justify-center py-12 gap-2">
              <Icon icon="mdi:loading" width={22} className="animate-spin text-[#2563EB]" />
              <p className="text-[12px] text-[rgba(0,0,0,0.5)]">Discovering connected resources…</p>
            </div>
          )}

          {!loading && error && (
            <p className="text-[12px] text-[#B91C1C] px-3 py-2 rounded-lg bg-[#FEF2F2] border border-[#FECACA] mb-3">
              {error}
            </p>
          )}

          {!loading && !error && rows.length === 0 && (
            <div className="text-center py-10">
              <Icon icon="mdi:vector-link" width={24} className="text-[rgba(0,0,0,0.2)] mx-auto mb-2" />
              <p className="text-[13px] text-[rgba(0,0,0,0.6)] font-medium">
                {lookup ? 'No connected resources found' : 'Connection discovery is not available for this resource type'}
              </p>
              <p className="text-[11.5px] text-[rgba(0,0,0,0.4)] mt-1 max-w-sm mx-auto">
                {lookup
                  ? `AWS reports nothing attached to this ${lookup.type}. You can still add resources by hand below, or keep the label on this one resource.`
                  : 'Discovery covers EC2 instances, EBS volumes, NAT gateways, load balancers, RDS, Lambda, EKS, ECS and Auto Scaling groups. Add resources by hand below, or keep the label on this one resource.'}
              </p>
            </div>
          )}

          {!loading && rows.length > 0 && (
            <>
              <div className="flex items-center justify-between mb-2">
                <span className="text-[11.5px] text-[rgba(0,0,0,0.5)]">
                  {rows.length} connected · {chosenCount} selected
                </span>
                <button
                  type="button"
                  onClick={() =>
                    setChosen((prev) =>
                      rows.every((r) => prev.has(r.resourceId))
                        ? new Set()
                        : new Set(rows.map((r) => r.resourceId)),
                    )
                  }
                  className="text-[11.5px] font-medium text-[#2563EB] hover:underline"
                >
                  {rows.every((r) => chosen.has(r.resourceId)) ? 'Clear all' : 'Select all'}
                </button>
              </div>
              <div className="space-y-1.5">
                {rows.map((r) => {
                  const already = hasThisLabel(r.resourceId);
                  return (
                    <label
                      key={r.resourceId}
                      className={`flex items-center gap-3 px-3 py-2 rounded-xl border cursor-pointer transition-colors ${
                        chosen.has(r.resourceId)
                          ? 'border-[rgba(37,99,235,0.4)] bg-[rgba(37,99,235,0.04)]'
                          : 'border-[rgba(0,0,0,0.1)] hover:border-[rgba(0,0,0,0.25)]'
                      }`}
                    >
                      <input
                        type="checkbox"
                        checked={chosen.has(r.resourceId)}
                        onChange={() => toggle(r.resourceId)}
                        className="w-4 h-4 accent-[#2563EB] flex-shrink-0"
                      />
                      <div className="flex-1 min-w-0">
                        <div className="flex items-center gap-2">
                          <span className="text-[12.5px] font-medium text-black truncate">{r.name}</span>
                          <span className="text-[10.5px] px-1.5 py-0.5 rounded bg-[rgba(0,0,0,0.05)] text-[rgba(0,0,0,0.6)] flex-shrink-0">
                            {r.type}
                          </span>
                          {already && (
                            <span className="text-[10.5px] text-[#166534] flex-shrink-0 inline-flex items-center gap-0.5">
                              <Icon icon="mdi:check" width={12} /> already labelled
                            </span>
                          )}
                        </div>
                        <span className="font-mono text-[10.5px] text-[rgba(0,0,0,0.4)] truncate block">
                          {r.resourceId} · {r.region}
                        </span>
                      </div>
                    </label>
                  );
                })}
              </div>
            </>
          )}

          {/* Add a resource by hand from this service. */}
          {!loading && (
            <div className="relative mt-3">
              <button
                type="button"
                onClick={() => setAddOpen((o) => !o)}
                className="inline-flex items-center gap-1.5 px-3 py-1.5 text-[12px] font-medium border border-[rgba(0,0,0,0.15)] rounded-lg hover:border-[rgba(0,0,0,0.35)] transition-colors"
              >
                <Icon icon="mdi:plus" width={14} />
                Add a resource
              </button>
              {addOpen && (
                <div className="absolute z-10 mt-1 w-full max-w-sm bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-lg py-1">
                  <div className="px-2 pb-1">
                    <input
                      autoFocus
                      type="text"
                      value={addSearch}
                      onChange={(e) => setAddSearch(e.target.value)}
                      placeholder="Search this service's resources…"
                      className="w-full px-2.5 py-1.5 text-[12px] border border-[rgba(0,0,0,0.12)] rounded-lg bg-[rgba(0,0,0,0.02)] focus:outline-none focus:ring-2 focus:ring-[#2563EB]/30"
                    />
                  </div>
                  <div className="max-h-48 overflow-y-auto">
                    {addable.length === 0 ? (
                      <p className="text-[12px] text-[rgba(0,0,0,0.4)] text-center py-3">
                        {addSearch ? 'No matching resources' : 'Nothing else to add'}
                      </p>
                    ) : (
                      addable.map((r) => (
                        <button
                          key={r.resourceId}
                          type="button"
                          onClick={() => addResource(r)}
                          className="w-full flex items-center gap-2 px-3 py-1.5 text-left hover:bg-[rgba(0,0,0,0.03)]"
                        >
                          <Icon icon="mdi:cube-outline" width={14} className="text-[rgba(0,0,0,0.4)] flex-shrink-0" />
                          <span className="min-w-0">
                            <span className="text-[12.5px] text-black truncate block">{r.name}</span>
                            <span className="font-mono text-[10.5px] text-[rgba(0,0,0,0.4)] truncate block">
                              {r.resourceId}
                            </span>
                          </span>
                        </button>
                      ))
                    )}
                  </div>
                </div>
              )}
            </div>
          )}
        </div>

        {/* Footer */}
        <div className="px-5 py-3 border-t border-[rgba(0,0,0,0.08)] flex items-center justify-end gap-2 bg-[rgba(0,0,0,0.015)]">
          <button
            type="button"
            onClick={onClose}
            disabled={applying}
            className="px-3 py-1.5 text-[12px] font-medium text-[rgba(0,0,0,0.55)] hover:text-black border border-[rgba(0,0,0,0.12)] rounded-lg transition-colors disabled:opacity-40"
          >
            Just this one
          </button>
          <button
            type="button"
            onClick={apply}
            disabled={applying || loading}
            className="inline-flex items-center gap-1.5 px-4 py-1.5 text-[12px] font-semibold rounded-lg bg-[#2563EB] text-white hover:bg-[#1D4ED8] transition-colors disabled:opacity-40 disabled:cursor-not-allowed"
          >
            <Icon
              icon={applying ? 'mdi:loading' : 'mdi:tag-plus-outline'}
              width={14}
              className={applying ? 'animate-spin' : ''}
            />
            {applying
              ? 'Applying…'
              : chosenCount > 0
                ? `Apply to ${chosenCount}`
                : 'Apply'}
          </button>
        </div>
      </div>
    </div>,
    document.body,
  );
};

export default CascadeLabelDialog;
