import React, { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  triggerInvestigation,
  listInvestigationsShared,
  findLatestInvestigation,
  invalidateInvestigationsCache,
  SreInvestigation,
  ResourceType,
  TriggerInvestigationRequest
} from '../hooks/useSreInvestigation';

interface Props {
  resourceType: ResourceType;
  resourceId: string;
  resourceName?: string;
  cloudAccountId?: number;
  alertPayload?: string;
  /**
   * The existing investigation for this resource, when the parent already knows it.
   *
   * Pass this from a list view that has fetched the investigations once. Supplying it skips the
   * lookup below entirely, which matters when this component is rendered per row.
   */
  investigation?: SreInvestigation | null;
  /** Set when `investigation` is authoritative, including when it is legitimately null. */
  investigationResolved?: boolean;
}

const SreInvestigateButton: React.FC<Props> = ({
  resourceType,
  resourceId,
  resourceName,
  cloudAccountId,
  alertPayload,
  investigation,
  investigationResolved = false,
}) => {
  const navigate = useNavigate();
  const [fetched, setFetched] = useState<SreInvestigation | null>(null);
  const [triggering, setTriggering] = useState(false);

  // The parent's answer wins when it has one. Only fall back to looking it up ourselves, and then
  // through the shared fetcher so N buttons still make one request rather than N.
  const existing = investigationResolved ? investigation ?? null : fetched;

  useEffect(() => {
    if (investigationResolved) {
      return;
    }
    let cancelled = false;
    listInvestigationsShared()
      .then(investigations => {
        if (!cancelled) {
          setFetched(findLatestInvestigation(investigations, resourceId, resourceType));
        }
      })
      .catch(() => {});
    return () => {
      cancelled = true;
    };
  }, [resourceId, resourceType, investigationResolved]);

  const handleClick = async () => {
    if (existing?.status === 'COMPLETED') {
      navigate(`/sre/investigations/${existing.id}`);
      return;
    }
    if (existing?.status === 'RUNNING' || existing?.status === 'PENDING') {
      navigate('/sre/investigations');
      return;
    }

    setTriggering(true);
    try {
      const req: TriggerInvestigationRequest = {
        resourceType,
        resourceId,
        resourceName,
        cloudAccountId,
        alertPayload,
      };
      const inv = await triggerInvestigation(req);
      // The shared list no longer reflects reality now that a new investigation exists.
      invalidateInvestigationsCache();
      setFetched(inv);
    } catch (e) {
      console.error('Failed to trigger investigation', e);
    } finally {
      setTriggering(false);
    }
  };

  const isRunning = existing?.status === 'PENDING' || existing?.status === 'RUNNING';
  const isCompleted = existing?.status === 'COMPLETED';

  if (isCompleted) {
    return (
      <button
        onClick={handleClick}
        title="View Investigation Report"
        aria-label={`View investigation report for ${resourceName || resourceId}`}
        className="w-8 h-8 rounded-lg flex items-center justify-center bg-green-100 text-green-600 hover:bg-green-200 transition-colors"
      >
        <i className="fas fa-check-circle text-sm" aria-hidden="true" />
      </button>
    );
  }

  if (isRunning) {
    return (
      <button
        onClick={handleClick}
        title="Investigation in progress…"
        aria-label={`Investigation in progress for ${resourceName || resourceId}`}
        className="w-8 h-8 rounded-lg flex items-center justify-center bg-yellow-100 text-yellow-600 cursor-pointer"
      >
        <i className="fas fa-spinner fa-spin text-sm" aria-hidden="true" />
      </button>
    );
  }

  return (
    <button
      onClick={handleClick}
      disabled={triggering}
      title="Investigate with AI"
      aria-label={`Investigate ${resourceName || resourceId} with AI`}
      className="w-8 h-8 rounded-lg flex items-center justify-center bg-indigo-600 text-white hover:bg-indigo-700 disabled:opacity-50 transition-colors"
    >
      {triggering
        ? <i className="fas fa-spinner fa-spin text-sm" aria-hidden="true" />
        : <i className="fas fa-search text-sm" aria-hidden="true" />
      }
    </button>
  );
};

export default SreInvestigateButton;
