import React from 'react';
import { useNavigate } from 'react-router-dom';
import type { DspmStatus } from '../../../types/dspm';
import type { FilesTabSlug } from '../../../constants/xamlensTabs';
import DspmNotReady from '../DspmNotReady';
import EndpointFilesTab from './EndpointFilesTab';
import FilesTabStrip from './FilesTabStrip';
import SaasFilesTab from './SaasFilesTab';

interface Props {
  tab: FilesTabSlug;
  status: DspmStatus;
  onRetry: () => void;
}

/**
 * XamLens Files: the files Matters found, by source (SaaS, Endpoint). Read-only, with no header buttons,
 * no KPI cards and no Export. Each tab body is keyed by its slug, so a tab switch starts from fresh
 * search, filters and paging.
 */
const FilesPage: React.FC<Props> = ({ tab, status, onRetry }) => {
  const navigate = useNavigate();
  if (!status.ready) return <DspmNotReady status={status} onRetry={onRetry} />;
  return (
    <div className="space-y-5">
      <div>
        <h1 className="text-lg font-bold text-[rgba(0,0,0,0.88)]">Files</h1>
        <p className="mt-0.5 text-xs text-[rgba(0,0,0,0.5)]">Files discovered across your connected sources</p>
      </div>
      <FilesTabStrip active={tab} onSelect={slug => navigate(`/xamlens/files/${slug}`)} />
      {tab === 'saas' ? <SaasFilesTab key="saas" /> : <EndpointFilesTab key="endpoint" />}
    </div>
  );
};

export default FilesPage;
