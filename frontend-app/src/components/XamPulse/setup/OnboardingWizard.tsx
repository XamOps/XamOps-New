import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { useMutation } from '@tanstack/react-query';
import { xampulseSetupApi } from '../../../api/xampulseSetupApi';
import { useBuckets, useInstanceCandidates } from '../../../hooks/xampulse/useXamPulseQueries';
import { useIsXpAdmin } from '../shell/useXamTabAccess';
import type { XpAccountRef, XpBucketCandidate, XpRetention, XpSetupPlanResponse, XpTargetMode } from '../../../types/xampulse';
import { Card, ErrorState, SkeletonBlock } from '../ui/ui';
import { TrustPanel } from './TrustPanel';
import { ChecklistStream } from './ChecklistStream';
import {
  DEFAULT_RETENTION, defaultRegions, recommendedBucketName, stackChunks, STACK_CHUNK, toPlanRequest, validateRetention, validateWizard, type WizardState,
} from './setupLogic';
import { formatUsd } from '../../../utils/xampulse/format';

const PRESETS: { name: string; r: XpRetention }[] = [
  { name: 'Standard', r: DEFAULT_RETENTION },
  { name: 'Lower cost', r: { hotDays: 7, iaAfterDays: 30, glacierIrAfterDays: 90, expireAfterDays: 365 } },
  { name: 'Long retention', r: { hotDays: 30, iaAfterDays: 60, glacierIrAfterDays: 120, expireAfterDays: 730 } },
];

export const SnippetBlock: React.FC<{ title: string; text: string }> = ({ title, text }) => {
  const [done, setDone] = useState(false);
  return (
    <div>
      <div className="xp-row"><strong>{title}</strong><span className="xp-spacer" />
        <button type="button" className="xp-btn" onClick={() => { void navigator.clipboard.writeText(text).then(() => { setDone(true); window.setTimeout(() => setDone(false), 1500); }).catch(() => setDone(false)); }}>{done ? 'Copied' : 'Copy'}</button></div>
      <pre className="xp-code" tabIndex={0} aria-label={title}>{text}</pre>
    </div>
  );
};

const BucketPicker: React.FC<{ buckets: XpBucketCandidate[]; value: string; onChange: (b: XpBucketCandidate) => void }> = ({ buckets, value, onChange }) => (
  <div role="radiogroup" aria-label="Existing buckets" style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
    {buckets.map((b) => {
      const blocked = b.issues.some((i) => i.level === 'BLOCK');
      return (
        <label key={b.name} className="xp-lane" style={{ display: 'block', opacity: blocked ? 0.7 : 1 }}>
          <div className="xp-row">
            <input type="radio" name="xp-bucket" checked={value === b.name} disabled={blocked} onChange={() => onChange(b)} />
            <strong>{b.name}</strong><span className="xp-muted">{b.region}</span>
            {b.recommended && <span className="xp-pill xp-pill--ok">Recommended</span>}
            <span className={`xp-pill xp-pill--${b.encryption === 'NONE' ? 'warn' : 'ok'}`}>{b.encryption === 'NONE' ? 'Not encrypted' : `Encrypted ${b.encryption === 'SSE_KMS' ? '(KMS)' : ''}`}</span>
            {b.publicAccessBlocked === false && <span className="xp-pill xp-pill--crit">Public access allowed</span>}
            {b.versioning && <span className="xp-pill xp-pill--info">Versioned</span>}
          </div>
          {b.issues.map((i) => <div key={i.code} className={i.level === 'BLOCK' ? 'xp-err' : 'xp-muted'} style={{ fontSize: 12.5, marginLeft: 26 }}>{i.level === 'BLOCK' ? 'Cannot use: ' : 'Note: '}{i.message}</div>)}
        </label>
      );
    })}
  </div>
);

export const OnboardingWizard: React.FC<{ account: XpAccountRef; onPlanned: (p: XpSetupPlanResponse) => void; onConnected: () => void }> = ({ account, onPlanned, onConnected }) => {
  const admin = useIsXpAdmin();
  const candidates = useInstanceCandidates(account.cloudAccountId);
  const [bucketMode, setBucketMode] = useState<'CREATE' | 'EXISTING'>('CREATE');
  const buckets = useBuckets(account.cloudAccountId, admin && bucketMode === 'EXISTING');
  const [state, setState] = useState<WizardState | null>(null);
  const [plan, setPlan] = useState<XpSetupPlanResponse | null>(null);

  useEffect(() => {
    if (!candidates.data || state) return;
    const regions = defaultRegions(candidates.data.items);
    const first = Object.keys(regions)[0] ?? 'us-east-1';
    setState({
      awsAccountId: account.awsAccountId, bucketMode: 'CREATE', bucketName: recommendedBucketName(account.awsAccountId, first), bucketRegion: first, kmsMode: 'CREATE', kmsArn: '',
      homeRegion: first, regions, retention: DEFAULT_RETENTION, docker: false,
    });
  }, [candidates.data, state, account.awsAccountId]);

  const planMut = useMutation({ mutationFn: (s: WizardState) => xampulseSetupApi.plan(account.cloudAccountId, toPlanRequest(s)), onSuccess: (p) => { setPlan(p); onPlanned(p); } });
  const onConnectedStable = useCallback(onConnected, [onConnected]);

  const errors = useMemo(() => (state ? validateWizard(state, buckets.data ?? []) : []), [state, buckets.data]);
  const retErr = useMemo(() => (state ? validateRetention(state.retention) : {}), [state]);

  if (candidates.isLoading || !state) return <SkeletonBlock height={420} label="Finding your instances" />;
  if (candidates.error) return <ErrorState error={candidates.error} onRetry={() => { void candidates.refetch(); }} />;

  const set = (patch: Partial<WizardState>) => setState({ ...state, ...patch });
  const setRegion = (region: string, patch: Partial<WizardState['regions'][string]>) => set({ regions: { ...state.regions, [region]: { ...state.regions[region], ...patch } } });
  const items = candidates.data?.items ?? [];
  const regionNames = Object.keys(state.regions);
  const selectedCount = regionNames.reduce((n, r) => n + state.regions[r].ids.length, 0);
  const regionsWithTargets = regionNames.filter((r) => state.regions[r].mode !== 'INSTANCES' || state.regions[r].ids.length > 0).length;
  const setBucketRegion = (region: string) => set({ bucketRegion: region, ...(state.bucketMode === 'CREATE' ? { bucketName: recommendedBucketName(account.awsAccountId, region) } : {}) });

  return (
    <div className="xp-grid xp-grid--main">
      <div className="xp-steps">
        <Card title="1. Choose the servers to watch" id="xp-w1">
          {items.length === 0 ? <p className="xp-muted">No EC2 instances were found in this account.</p> : regionNames.map((region) => {
            const r = state.regions[region];
            const inRegion = items.filter((i) => i.region === region);
            return (
              <fieldset key={region} style={{ border: '1px solid var(--xp-border)', borderRadius: 10, marginBottom: 12, padding: 12 }}>
                <legend style={{ padding: '0 6px', fontWeight: 700 }}>{region} <span className="xp-muted">({inRegion.length} instances)</span></legend>
                <div className="xp-seg" role="group" aria-label={`Targeting for ${region}`} style={{ marginBottom: 8 }}>
                  {([['INSTANCES', 'Pick instances'], ['ALL_MANAGED', 'All managed'], ['TAG', 'By tag']] as [XpTargetMode, string][]).map(([m, l]) => <button key={m} type="button" aria-pressed={r.mode === m} onClick={() => setRegion(region, { mode: m })}>{l}</button>)}
                </div>
                {r.mode === 'INSTANCES' && (() => {
                  const selectableIds = inRegion.filter((i) => i.ssmManaged === true).map((i) => i.instanceId);
                  const selectedSelectable = selectableIds.filter((id) => r.ids.includes(id)).length;
                  const allSelected = selectableIds.length > 0 && selectedSelectable === selectableIds.length;
                  const noneSelected = selectedSelectable === 0;
                  return (
                  <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 6 }}>
                    <button type="button" className="xp-btn xp-btn--ghost" disabled={allSelected || selectableIds.length === 0}
                      onClick={() => setRegion(region, { ids: Array.from(new Set([...r.ids, ...selectableIds])) })}>Select all</button>
                    <button type="button" className="xp-btn xp-btn--ghost" disabled={noneSelected}
                      onClick={() => setRegion(region, { ids: r.ids.filter((id) => !selectableIds.includes(id)) })}>Deselect all</button>
                    <span className="xp-muted" style={{ fontSize: 12 }}>{selectedSelectable} of {selectableIds.length} selectable selected</span>
                  </div>
                  );
                })()}
                {r.mode === 'INSTANCES' && (
                  <ul style={{ listStyle: 'none', margin: 0, padding: 0, display: 'grid', gap: 4 }}>
                    {inRegion.map((i) => {
                      const ok = i.ssmManaged === true;
                      return (
                        <li key={i.instanceId}>
                          <label className="xp-row" style={{ opacity: ok ? 1 : 0.65 }}>
                            <input type="checkbox" disabled={!ok} checked={r.ids.includes(i.instanceId)} onChange={(e) => setRegion(region, { ids: e.target.checked ? [...r.ids, i.instanceId] : r.ids.filter((x) => x !== i.instanceId) })} />
                            <strong>{i.name || i.instanceId}</strong><span className="xp-muted xp-mono" style={{ fontSize: 12 }}>{i.instanceId}</span>
                            <span className={`xp-pill xp-pill--${i.state === 'running' ? 'ok' : 'nodata'}`}>{i.state}</span>
                            {!ok && <span className="xp-muted" style={{ fontSize: 12 }}>{i.ssmManaged === false ? 'Needs the SSM agent to install automatically' : 'SSM status unknown'}</span>}
                          </label>
                        </li>
                      );
                    })}
                  </ul>
                )}
                {r.mode === 'ALL_MANAGED' && (
                  <label className="xp-row"><input type="checkbox" checked={r.confirmAll} onChange={(e) => setRegion(region, { confirmAll: e.target.checked })} />
                    <span>I understand every SSM-managed instance in {region}, including ones launched later, will send data to XamPulse.</span></label>
                )}
                {r.mode === 'TAG' && (
                  <div className="xp-row">
                    <div className="xp-field"><label htmlFor={`tk-${region}`}>Tag key</label><input id={`tk-${region}`} className="xp-input" value={r.tagKey} onChange={(e) => setRegion(region, { tagKey: e.target.value })} /></div>
                    <div className="xp-field"><label htmlFor={`tv-${region}`}>Tag value (optional)</label><input id={`tv-${region}`} className="xp-input" value={r.tagValue} onChange={(e) => setRegion(region, { tagValue: e.target.value })} /></div>
                  </div>
                )}
                {r.mode === 'INSTANCES' && r.ids.length > STACK_CHUNK && <p className="xp-muted" style={{ fontSize: 12, marginBottom: 0 }}>{r.ids.length} instances will be split into {stackChunks(r.ids.length)} groups of up to {STACK_CHUNK} in the CloudFormation template.</p>}
              </fieldset>
            );
          })}
          <p className="xp-muted" style={{ marginBottom: 0 }}>{selectedCount} instance(s) selected across {regionsWithTargets} region(s). Each region needs its own AWS approval, so {regionsWithTargets <= 1 ? '1 region means 1 extra click' : `${regionsWithTargets} regions means ${regionsWithTargets} extra clicks`} after the first one.</p>
        </Card>

        <Card title="2. Where should older logs be stored?" id="xp-w2">
          <div className="xp-seg" role="group" aria-label="Bucket choice" style={{ marginBottom: 10 }}>
            <button type="button" aria-pressed={state.bucketMode === 'CREATE'} onClick={() => { setBucketMode('CREATE'); set({ bucketMode: 'CREATE', bucketName: recommendedBucketName(account.awsAccountId, state.bucketRegion), kmsMode: 'CREATE' }); }}>Create one for me (recommended)</button>
            <button type="button" aria-pressed={state.bucketMode === 'EXISTING'} onClick={() => { setBucketMode('EXISTING'); set({ bucketMode: 'EXISTING', bucketName: '', kmsMode: 'AWS_MANAGED' }); }}>Use an existing bucket</button>
          </div>
          <div className="xp-field" style={{ maxWidth: 260, marginBottom: 10 }}>
            <label htmlFor="w-region">Bucket region</label>
            <select id="w-region" className="xp-select" value={state.bucketRegion} onChange={(e) => setBucketRegion(e.target.value)}>{Array.from(new Set([...regionNames, state.bucketRegion])).map((r) => <option key={r}>{r}</option>)}</select>
          </div>
          {state.bucketMode === 'CREATE' ? (
            <p style={{ margin: 0 }}>XamPulse will create <span className="xp-mono">{state.bucketName}</span> in your account, encrypted, private and versioned, with lifecycle rules for the retention below.</p>
          ) : !admin ? <p className="xp-muted">Listing your buckets needs an administrator.</p> : buckets.isLoading ? <SkeletonBlock height={140} label="Listing buckets" /> : buckets.error ? <ErrorState error={buckets.error} compact onRetry={() => { void buckets.refetch(); }} /> : (
            <BucketPicker buckets={buckets.data ?? []} value={state.bucketName} onChange={(b) => set({ bucketName: b.name, bucketRegion: b.region })} />
          )}
          <div className="xp-field" style={{ marginTop: 12, maxWidth: 360 }}>
            <label htmlFor="w-kms">Encryption key</label>
            <select id="w-kms" className="xp-select" value={state.kmsMode} onChange={(e) => set({ kmsMode: e.target.value as WizardState['kmsMode'] })}>
              <option value="CREATE">Create a new KMS key</option><option value="CUSTOMER_KEY">Use my KMS key</option>{state.bucketMode === 'EXISTING' && <option value="AWS_MANAGED">Use the bucket default (AWS managed)</option>}
            </select>
            {state.kmsMode === 'CUSTOMER_KEY' && <input aria-label="KMS key ARN" className="xp-input xp-input--mono" placeholder="arn:aws:kms:us-east-1:123456789012:key/..." value={state.kmsArn} onChange={(e) => set({ kmsArn: e.target.value })} />}
          </div>
        </Card>

        <Card title="3. How long should logs be kept?" id="xp-w3">
          <div className="xp-row" style={{ marginBottom: 10 }} role="group" aria-label="Retention presets">
            {PRESETS.map((p) => <button key={p.name} type="button" className="xp-btn" aria-pressed={JSON.stringify(state.retention) === JSON.stringify(p.r)} onClick={() => set({ retention: p.r })}>{p.name}</button>)}
          </div>
          <div className="xp-grid xp-grid--kpi">
            {([['hotDays', 'Fast search (days)'], ['iaAfterDays', 'Infrequent access after'], ['glacierIrAfterDays', 'Glacier after'], ['expireAfterDays', 'Delete after']] as [keyof XpRetention, string][]).map(([k, label]) => (
              <div className="xp-field" key={k}>
                <label htmlFor={`ret-${k}`}>{label}</label>
                <input id={`ret-${k}`} type="number" className="xp-input" value={state.retention[k]} aria-invalid={retErr[k] ? true : undefined} aria-describedby={retErr[k] ? `ret-${k}-e` : undefined} onChange={(e) => set({ retention: { ...state.retention, [k]: Number(e.target.value) } })} />
                {retErr[k] && <span id={`ret-${k}-e`} className="xp-err">{retErr[k]}</span>}
              </div>
            ))}
          </div>
          <label className="xp-row" style={{ marginTop: 10 }}><input type="checkbox" checked={state.docker} onChange={(e) => set({ docker: e.target.checked })} />Also collect Docker container logs and metrics</label>
        </Card>

        <Card id="xp-w4">
          {errors.length > 0 && <ul className="xp-err" aria-live="polite" style={{ marginTop: 0 }}>{errors.map((e) => <li key={e}>{e}</li>)}</ul>}
          {!admin && <p className="xp-banner xp-banner--warn">Only administrators can deploy. You can review everything here and ask an admin to press the button.</p>}
          <button type="button" className="xp-btn xp-btn--primary xp-btn--lg" disabled={!admin || errors.length > 0 || planMut.isPending} onClick={() => planMut.mutate(state)}>{planMut.isPending ? 'Preparing your plan' : 'Deploy to my AWS account'}</button>
          <p className="xp-muted" style={{ marginBottom: 0 }}>Nothing is created until you approve it in the AWS console. You will see exactly what will be created and what it costs first.</p>
          {planMut.error && <div style={{ marginTop: 12 }}><ErrorState error={planMut.error} compact /></div>}
        </Card>

        {plan && (
          <>
            <Card title="Review and approve in AWS" id="xp-plan">
              {plan.warnings.map((w) => <div key={w} className="xp-banner xp-banner--warn" style={{ marginBottom: 8 }}>{w}</div>)}
              <p style={{ marginTop: 0 }}>Estimated cost in your AWS account: <strong>{formatUsd(plan.preview.customerCostEstimate.s3Usd + plan.preview.customerCostEstimate.kmsUsd)}</strong> per month (S3 {formatUsd(plan.preview.customerCostEstimate.s3Usd)}, KMS {formatUsd(plan.preview.customerCostEstimate.kmsUsd)}). {plan.preview.customerCostEstimate.notes.join(' ')}</p>
              <p>{plan.stacks.length <= 1 ? 'One approval is needed.' : `${plan.stacks.length} approvals are needed, one per stack.`}</p>
              <div className="xp-row">
                {plan.stacks.map((s) => (
                  <button key={s.stackName} type="button" className="xp-btn xp-btn--primary" onClick={() => { window.open(s.quickCreateUrl, '_blank', 'noopener,noreferrer'); }}>Open AWS: {s.kind === 'GLOBAL' ? 'main stack' : s.region}</button>
                ))}
              </div>
              <details style={{ marginTop: 12 }}>
                <summary style={{ cursor: 'pointer', fontWeight: 650 }}>What will be created and which permissions are used</summary>
                <ul>{plan.preview.resources.map((r) => <li key={r}>{r}</li>)}</ul>
                <p className="xp-muted">Permissions: <span className="xp-mono">{plan.preview.iamActions.join(', ')}</span></p>
                <p className="xp-muted">Connection role gains: <span className="xp-mono">{plan.preview.connectionRoleGrants.join(', ')}</span></p>
                <p className="xp-muted">Template checksum (SHA-256): <span className="xp-mono">{plan.preview.templateSha256}</span></p>
                {plan.preview.customerSnippets && (
                  <div className="xp-grid">
                    <p style={{ margin: 0 }}>You use your own bucket, so add these to it yourself:</p>
                    <SnippetBlock title="Bucket policy" text={plan.preview.customerSnippets.bucketPolicy} />
                    {plan.preview.customerSnippets.kmsGrant && <SnippetBlock title="KMS key grant" text={plan.preview.customerSnippets.kmsGrant} />}
                    <SnippetBlock title="Lifecycle rules" text={plan.preview.customerSnippets.lifecycleJson} />
                  </div>
                )}
              </details>
            </Card>
            <ChecklistStream accountId={account.cloudAccountId} onConnected={onConnectedStable} />
          </>
        )}
      </div>
      <div style={{ position: 'sticky', top: 120, alignSelf: 'start' }}><TrustPanel accountId={account.cloudAccountId} compact /></div>
    </div>
  );
};
