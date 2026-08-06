import React, { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  triggerInvestigation,
  listInvestigations,
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
}

const SreInvestigateButton: React.FC<Props> = ({
  resourceType,
  resourceId,
  resourceName,
  cloudAccountId,
  alertPayload,
}) => {
  const navigate = useNavigate();
  const [existing, setExisting] = useState<SreInvestigation | null>(null);
  const [triggering, setTriggering] = useState(false);

  useEffect(() => {
    listInvestigations().then(investigations => {
      // Find most recent investigation for this resource
      const match = investigations
        .filter(i => i.resourceId === resourceId && i.resourceType === resourceType)
        .sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime())[0];
      setExisting(match || null);
    }).catch(() => {});
  }, [resourceId, resourceType]);

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
      setExisting(inv);
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
        aria-label="View Investigation Report"
        className="w-8 h-8 rounded-lg flex items-center justify-center bg-green-100 text-green-600 hover:bg-green-200 focus:outline-none focus-visible:ring-2 focus-visible:ring-green-500 focus-visible:ring-offset-2 transition-colors"
      >
        <i className="fas fa-check-circle text-sm" />
      </button>
    );
  }

  if (isRunning) {
    return (
      <button
        onClick={handleClick}
        title="Investigation in progress…"
        aria-label="Investigation in progress"
        className="w-8 h-8 rounded-lg flex items-center justify-center bg-yellow-100 text-yellow-600 cursor-pointer focus:outline-none focus-visible:ring-2 focus-visible:ring-yellow-500 focus-visible:ring-offset-2 transition-colors"
      >
        <i className="fas fa-spinner fa-spin text-sm" />
      </button>
    );
  }

  return (
    <button
      onClick={handleClick}
      disabled={triggering}
      title={triggering ? "Investigating with AI..." : "Investigate with AI"}
      aria-label={triggering ? "Investigating with AI..." : "Investigate with AI"}
      className="w-8 h-8 rounded-lg flex items-center justify-center bg-indigo-600 text-white hover:bg-indigo-700 disabled:opacity-50 focus:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500 focus-visible:ring-offset-2 transition-colors"
    >
      {triggering
        ? <i className="fas fa-spinner fa-spin text-sm" />
        : <i className="fas fa-search text-sm" />
      }
    </button>
  );
};

export default SreInvestigateButton;
