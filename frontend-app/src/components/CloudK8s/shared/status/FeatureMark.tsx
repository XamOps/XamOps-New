/**
 * A feature mark (design-delta §2.1, §11; glossary §11): one automation feature's accent used only
 * for wayfinding — its title tile, its title rule, and (via `rule`) a card's top rule. The accent is
 * never a status or a series colour. Each feature has one hue and one icon (§11); both are fixed
 * here so a page can only reach a feature accent through this component, paired with the feature's
 * name.
 *
 * This and charts/LanesTimeline.tsx are the only files the guard allows `*-feature-*` classes in.
 */
import { Icon } from '@iconify/react';

/** The four automation features (glossary §11). */
export type Feature = 'workload' | 'node' | 'hpa' | 'vpa';

interface FeatureStyle {
  icon: string;
  /** The feature's display name. */
  name: string;
  /** Tile tint + icon colour. */
  tile: string;
  text: string;
  /** The title rule / card top rule in the accent. */
  rule: string;
}

const FEATURES: Record<Feature, FeatureStyle> = {
  workload: {
    icon: 'mdi:arrow-expand-vertical',
    name: 'Workload autoscaler',
    tile: 'bg-feature-workload/[.14] dark:bg-feature-workload/[.22]',
    text: 'text-feature-workload',
    rule: 'bg-feature-workload',
  },
  node: {
    icon: 'mdi:server-network',
    name: 'Node autoscaler',
    tile: 'bg-feature-node/[.14] dark:bg-feature-node/[.22]',
    text: 'text-feature-node',
    rule: 'bg-feature-node',
  },
  hpa: {
    icon: 'mdi:arrow-expand-horizontal',
    name: 'HPA',
    tile: 'bg-feature-hpa/[.14] dark:bg-feature-hpa/[.22]',
    text: 'text-feature-hpa',
    rule: 'bg-feature-hpa',
  },
  vpa: {
    icon: 'mdi:compare-vertical',
    name: 'VPA',
    tile: 'bg-feature-vpa/[.14] dark:bg-feature-vpa/[.22]',
    text: 'text-feature-vpa',
    rule: 'bg-feature-vpa',
  },
};

/** The feature's display name, for callers that need the label text. */
export function featureName(feature: Feature): string {
  return FEATURES[feature].name;
}

/** The feature's accent icon id. */
export function featureIcon(feature: Feature): string {
  return FEATURES[feature].icon;
}

export interface FeatureMarkProps {
  feature: Feature;
  /** Override the title text; defaults to the feature's name. */
  title?: string;
  /** Draw the title rule (a thin accent line under the title). */
  rule?: boolean;
  className?: string;
}

/**
 * A feature's small accent dot, for wayfinding next to the feature's name (a filter chip, an
 * Activity row's source). Decorative: the caller always shows the name beside it, so the dot is
 * never the only signal.
 */
export function FeatureDot({ feature, className = '' }: { feature: Feature; className?: string }) {
  return <span className={`inline-block h-2 w-2 shrink-0 rounded-full ${FEATURES[feature].rule} ${className}`} aria-hidden="true" data-feature-dot={feature} data-k8s-kit="" />;
}

export function FeatureMark({ feature, title, rule = true, className = '' }: FeatureMarkProps) {
  const f = FEATURES[feature];
  return (
    <span className={`inline-flex flex-col gap-1 ${className}`} data-feature={feature} data-k8s-kit="">
      <span className="inline-flex items-center gap-2">
        <span className={`inline-flex h-6 w-6 shrink-0 items-center justify-center rounded-[6px] ${f.tile} ${f.text}`} aria-hidden="true">
          <Icon icon={f.icon} width={15} />
        </span>
        <span className="text-[13px] font-semibold text-[rgba(0,0,0,0.88)] dark:text-[rgba(255,255,255,0.92)]">
          {title ?? f.name}
        </span>
      </span>
      {rule && <span className={`h-0.5 w-10 rounded-full ${f.rule}`} aria-hidden="true" />}
    </span>
  );
}

export default FeatureMark;
