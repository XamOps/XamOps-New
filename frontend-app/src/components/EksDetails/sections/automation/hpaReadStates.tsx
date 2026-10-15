/**
 * The read states the HPA and VPA pages share (main's combined Pod autoscaler page, split).
 *
 * Both pages read the vertical rightsizer payload through `useHpaAutomation` (one `GET` per
 * cluster). Until that read answers with a view, each page shows the same loading, not-connected,
 * session, refusal and error states, worded for its own feature. Codes never appear as visible
 * text: an unknown refusal shows its plain label, the code only in a title.
 */
import type { FC } from 'react';
import { Icon } from '@iconify/react';
import type { HpaReadResult } from '../../../../types/hpaAutomation';
import type { CodeView } from '../../../../types/verticalRightsizer';
import { hpaCodeText } from './hpaCopy';

export const EmptyState: FC<{ icon: string; title: string; subtitle: string }> = ({ icon, title, subtitle }) => (
  <div className="flex flex-col items-center justify-center py-16 text-center">
    <div className="w-14 h-14 bg-[rgba(0,0,0,0.02)] border border-[rgba(0,0,0,0.06)] rounded-lg flex items-center justify-center mb-4 dark:bg-[rgba(255,255,255,0.04)] dark:border-[rgba(255,255,255,0.08)]">
      <Icon icon={icon} width={22} aria-hidden="true" className="text-[rgba(0,0,0,0.4)] dark:text-[rgba(255,255,255,0.4)]" />
    </div>
    <p className="text-[13px] font-semibold text-[rgba(0,0,0,0.88)] dark:text-[rgba(255,255,255,0.92)]">{title}</p>
    <p className="text-[12px] text-[rgba(0,0,0,0.5)] mt-1 max-w-[460px] dark:text-[rgba(255,255,255,0.5)]">{subtitle}</p>
  </div>
);

export interface GateCopy {
  icon: string;
  title: string;
  subtitle: string;
}

/** Which page is asking: the HPA page keeps main's wording; the VPA page names its own feature. */
export type HpaReadFeature = 'hpa' | 'vpa';

const NOT_READY_COPY: Record<HpaReadFeature, GateCopy> = {
  hpa: {
    icon: 'mdi:toggle-switch-off-outline',
    title: 'HPA automation is off in this environment',
    subtitle: "XamOps hasn't evaluated this cluster's HPAs, and nothing was read from the cluster.",
  },
  vpa: {
    icon: 'mdi:toggle-switch-off-outline',
    title: 'The VPA view is off in this environment',
    subtitle: "XamOps hasn't read this cluster's VPAs, and nothing was read from the cluster.",
  },
};

/** The cluster-level gates that leave a page with nothing to show, keyed by the backend gate. */
export const gateCopyFor = (feature: HpaReadFeature): Record<string, GateCopy> => ({
  CONTROLLER_NOT_READY: NOT_READY_COPY[feature],
  CLUSTER_ACCESS_NOT_CONFIGURED: {
    icon: 'mdi:key',
    title: "XamOps can't sign in to this cluster",
    subtitle: "Create an EKS access entry for this account's XamOps role. The cluster setup guide shows how.",
  },
  CLUSTER_UNREACHABLE: {
    icon: 'mdi:power-plug-outline',
    title: "XamOps can't reach this cluster's API server",
    subtitle: "Access is set up, but the endpoint didn't answer. That's expected for a private endpoint.",
  },
});

/** The caller's own write gates: they grey a control, but never explain an empty list. */
const CALLER_WRITE_GATES = new Set(['DEMO', 'NO_USER_PERMISSION', 'READ_ONLY_CONNECTION']);

/**
 * The copy for a page's empty list: the first cluster-level gate that explains it (XamOps couldn't
 * read the cluster, so "nothing found" would be untrue), else the page's own `nothing` copy.
 */
export const gateEmptyState = (gateReasons: CodeView[], feature: HpaReadFeature, nothing: GateCopy): GateCopy => {
  const copy = gateCopyFor(feature);
  for (const reason of gateReasons) {
    if (reason.kind === 'known' && !CALLER_WRITE_GATES.has(reason.code) && copy[reason.code]) {
      return copy[reason.code];
    }
  }
  return nothing;
};

const SUBJECT: Record<HpaReadFeature, { needs: string; failed: string }> = {
  hpa: { needs: 'HPA automation needs a cluster, an account and a region.', failed: "Couldn't load HPA automation" },
  vpa: { needs: 'The VPA view needs a cluster, an account and a region.', failed: "Couldn't load the VPA view" },
};

/**
 * What a page shows before it has a view: a skeleton while the first read runs, then the state the
 * read answered with. Render it whenever `result` is not `ok`.
 */
export const HpaReadFallback: FC<{
  feature: HpaReadFeature;
  loading: boolean;
  result: HpaReadResult | null;
  reload: () => void;
}> = ({ feature, loading, result, reload }) => {
  if (loading && !result) {
    return <div className="skeleton h-64 rounded-xl"></div>;
  }
  if (!result) {
    return <EmptyState icon="mdi:information-outline" title="Nothing requested yet" subtitle={SUBJECT[feature].needs} />;
  }
  if (result.kind === 'cluster-not-registered') {
    return <EmptyState icon="mdi:link-off" title="This cluster isn't connected to XamOps yet" subtitle="Install the XamOps agent from the cluster's setup guide, then reload." />;
  }
  if (result.kind === 'unauthenticated') {
    return <EmptyState icon="mdi:account-lock-outline" title="Your session has expired" subtitle="Sign in again to see automation." />;
  }
  if (result.kind === 'refused') {
    const copy = result.code.kind === 'known' ? gateCopyFor(feature)[result.code.code] : undefined;
    return <EmptyState icon={copy?.icon ?? 'mdi:cancel'} title={copy?.title ?? hpaCodeText(result.code)} subtitle={copy?.subtitle ?? result.message} />;
  }
  if (result.kind === 'error') {
    return (
      <div className="bg-white rounded-lg border border-[rgba(0,0,0,0.12)] p-6">
        <p className="text-[13px] font-semibold text-[rgba(0,0,0,0.88)]">{SUBJECT[feature].failed}</p>
        <p className="text-[12px] text-[rgba(0,0,0,0.5)] mt-1">{result.message}</p>
        <button
          type="button"
          onClick={reload}
          className="mt-4 px-4 py-2 text-xs font-bold rounded-xl border border-[rgba(0,0,0,0.12)] bg-white text-[rgba(0,0,0,0.6)] hover:bg-[rgba(0,0,0,0.03)] transition-all"
        >
          Retry
        </button>
      </div>
    );
  }
  return null;
};
