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
        className="px-3 py-1.5 rounded text-xs font-semibold bg-green-900/50 text-green-400 border border-green-800 hover:bg-green-900 transition-colors whitespace-nowrap"
      >
        ✅ View Report
      </button>
    );
  }

  if (isRunning) {
    return (
      <button
        onClick={handleClick}
        className="px-3 py-1.5 rounded text-xs font-semibold bg-gray-800 text-yellow-400 border border-yellow-800 cursor-pointer whitespace-nowrap"
      >
        ⏳ Investigating…
      </button>
    );
  }

  return (
    <button
      onClick={handleClick}
      disabled={triggering}
      className="px-3 py-1.5 rounded text-xs font-semibold bg-blue-600 text-white hover:bg-blue-700 disabled:opacity-50 transition-colors whitespace-nowrap"
    >
      {triggering ? '…' : '🔍 Investigate'}
    </button>
  );
};

export default SreInvestigateButton;
