import React from 'react';
import type { ScanSchedule, ScanScheduleDetail } from '../../../types/dspmScans';
import { scanUrls } from '../../../hooks/useDspmScans';
import { useServerList } from '../../../hooks/useServerList';
import { formatListDate } from '../datastores/datastoreFormat';
import { NestedDrawerShell } from '../datastores/drawer/DrawerShell';
import { ErrorNote, LoadingNote } from '../datastores/drawer/TabParts';
import { prettifyCode, scheduleName, targetsText } from './scanFormat';
import { DetailField, DetailHeader, FrequencyCell, LastRunCell, TargetsList, scheduleSubtitle } from './ScanParts';

interface Props {
  /** The list row; its fields show until the detail arrives. */
  schedule: ScanSchedule & { id: string };
  /** The row that opened this layer, focused again when it closes. */
  returnFocus: HTMLElement;
  /** Must be stable (`useCallback`), as `NestedDrawerShell` requires. */
  onClose: () => void;
}

/**
 * "Schedule Details" (FR-SM-6; S2): the schedule's fields, its scope mode and scopes, and the targets the
 * route returns ("Showing N of M targets" when it returns fewer than the total). Read only.
 */
const ScheduleDetailDrawer: React.FC<Props> = ({ schedule: row, returnFocus, onClose }) => {
  const detail = useServerList<ScanScheduleDetail>(scanUrls.schedule(row.id));
  const schedule = detail.data?.schedule ?? row;
  const subtitle = scheduleSubtitle(schedule);
  return (
    <NestedDrawerShell label="Schedule Details" returnFocus={returnFocus} onClose={onClose} widthClass="lg:w-[40rem]">
      {({ closeButton, close }) => (
        <>
          <DetailHeader title="Schedule Details" subtitle={scheduleName(schedule.name)} closeButton={closeButton} onClose={close} />
          <div className="flex-1 space-y-4 overflow-y-auto px-5 py-4">
            {detail.error ? <ErrorNote error={detail.error} onRetry={detail.reload} subject="item" /> : (
              <>
                <div className="space-y-3 rounded-xl border border-[rgba(0,0,0,0.08)] bg-white p-4">
                  <DetailField label="Schedule Name">
                    {scheduleName(schedule.name)}
                    {subtitle && <span className="ml-2 text-[11px] text-[rgba(0,0,0,0.45)]">{subtitle}</span>}
                  </DetailField>
                  <DetailField label="Environment">{schedule.platformLabel ?? '-'}</DetailField>
                  <DetailField label="Coverage">{schedule.coverageLabel ?? '-'}</DetailField>
                  <DetailField label="Targets">{targetsText(schedule.totalDatastores, schedule.totalIsEstimate)}</DetailField>
                  <DetailField label="Frequency"><FrequencyCell schedule={schedule} /></DetailField>
                  <DetailField label="Scheduled Time">
                    {schedule.timing?.triggerTime != null ? formatListDate(schedule.timing.triggerTime) : '-'}
                  </DetailField>
                  <DetailField label="Scan Status"><LastRunCell schedule={schedule} /></DetailField>
                  {schedule.nextRunAt !== null && <DetailField label="Next Run">{formatListDate(schedule.nextRunAt)}</DetailField>}
                </div>
                {detail.data === null ? <LoadingNote /> : (
                  <>
                    <div className="space-y-3 rounded-xl border border-[rgba(0,0,0,0.08)] bg-white p-4">
                      <DetailField label="Scope Mode">{prettifyCode(detail.data.scopeMode) ?? '-'}</DetailField>
                      <DetailField label="Scopes">{detail.data.scopeNames.length > 0 ? detail.data.scopeNames.join(', ') : '-'}</DetailField>
                    </div>
                    <TargetsList targets={detail.data.targets} total={detail.data.targetsTotal} />
                  </>
                )}
              </>
            )}
          </div>
        </>
      )}
    </NestedDrawerShell>
  );
};

export default ScheduleDetailDrawer;
