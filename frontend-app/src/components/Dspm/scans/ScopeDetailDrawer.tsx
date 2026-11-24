import React from 'react';
import type { ScanScope, ScanScopeDetail } from '../../../types/dspmScans';
import { scanUrls } from '../../../hooks/useDspmScans';
import { useServerList } from '../../../hooks/useServerList';
import { formatListDate } from '../datastores/datastoreFormat';
import { NestedDrawerShell } from '../datastores/drawer/DrawerShell';
import { ErrorNote, LoadingNote } from '../datastores/drawer/TabParts';
import { prettifyCode } from './scanFormat';
import { DetailField, DetailHeader, TargetsList } from './ScanParts';

interface Props {
  /** The list row; its fields show until the detail arrives. */
  scope: ScanScope & { id: string };
  returnFocus: HTMLElement;
  /** Must be stable (`useCallback`). */
  onClose: () => void;
}

/** A scan scope's detail layer (FR-SM-5; S7): name, environment, mode and its targets. Read only. */
const ScopeDetailDrawer: React.FC<Props> = ({ scope: row, returnFocus, onClose }) => {
  const detail = useServerList<ScanScopeDetail>(scanUrls.scope(row.id));
  const scope = detail.data?.scope ?? row;
  const title = scope.name ?? 'Scope';
  return (
    <NestedDrawerShell label={title} returnFocus={returnFocus} onClose={onClose} widthClass="lg:w-[40rem]">
      {({ closeButton, close }) => (
        <>
          <DetailHeader title={title} subtitle={prettifyCode(scope.scopeMode)} closeButton={closeButton} onClose={close} />
          <div className="flex-1 space-y-4 overflow-y-auto px-5 py-4">
            {detail.error ? <ErrorNote error={detail.error} onRetry={detail.reload} subject="item" /> : (
              <>
                <div className="space-y-3 rounded-xl border border-[rgba(0,0,0,0.08)] bg-white p-4">
                  <DetailField label="Environment">{scope.platformLabel ?? '-'}</DetailField>
                  <DetailField label="Mode">{prettifyCode(scope.scopeMode) ?? '-'}</DetailField>
                  <DetailField label="Targets">{scope.totalDatastores === null ? '-' : scope.totalDatastores.toLocaleString('en-US')}</DetailField>
                  <DetailField label="Last Modified">{scope.updatedAt === null ? '-' : formatListDate(scope.updatedAt)}</DetailField>
                </div>
                {detail.data === null
                  ? <LoadingNote />
                  : <TargetsList targets={detail.data.targets} total={detail.data.targetsTotal} />}
              </>
            )}
          </div>
        </>
      )}
    </NestedDrawerShell>
  );
};

export default ScopeDetailDrawer;
