/**
 * ResourceInspector — the live-mode detail panel for a selected canvas node.
 *
 * Replaces an inline panel that rendered Object.entries(node) as a flat list of
 * identically-weighted rows in whatever order the backend happened to serialise
 * them, with hardcoded light-mode greys and no way to copy an identifier or
 * reach the resource in AWS.
 *
 * Three things drive the design:
 *
 *   1. PRIORITY. An operator opening this panel wants state, then placement,
 *      then configuration. FIELD_ORDER pins the fields worth reading first and
 *      everything unrecognised sorts after them, so a new backend detail key
 *      appears at the bottom instead of above the state.
 *   2. AGREEMENT WITH THE CANVAS. The icon well, the status tone and the type
 *      caption are the same vocabulary the node card uses (canvasTheme.ts), so
 *      selecting a node and reading the panel feel like one object.
 *   3. ACTIONS. Copy the identifier, open it in the AWS console. Both are the
 *      reason an operator is here, and neither previously existed.
 */

import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { AWS_ICONS, NODE_TYPE_DATA, TAILWIND_COLORS, type NodeData } from '../../types/cloudmap';
import { CANVAS_TOKENS, stateTone, type CanvasTheme, type StateTone } from './canvasTheme';

// ─── Field presentation ──────────────────────────────────────────────────────

/** Keys that are structural, already shown in the header, or Cytoscape noise. */
const HIDDEN_KEYS = new Set([
  'id', 'label', 'parent', 'type', 'source', 'target',
  'state', 'State', 'region', 'Region',
  'isContainer', 'containerType', 'customIcon', 'kind',
  // Icon paths are presentation. The canvas writes _iconUrl on palette drops and
  // `icon` on some node types, and without these the panel renders a row reading
  // "ICON  /icons/vpc.png" as though it were resource data.
  '_iconUrl', 'iconUrl', 'icon', 'iconVal',
  'fillColor', 'fillColor2', 'gradientDir', 'borderColor', 'borderWidth', 'borderDash',
  'nodeShape', 'nodeShadow', 'shadowBlur', 'shadowOffsetX', 'shadowOffsetY', 'shadowColor',
  'fontSize', 'fontBold', 'fontItalic', 'fontFamily', 'fontColor', 'textValign', 'textHalign',
  'edgeStyle', 'arrowType', 'edgeWidth', 'edgeDash', 'opacity', 'locked', 'tooltip',
  'pulse', 'flowAnimation', 'flowSpeed', 'sourceLabel', 'targetLabel', 'midArrowShape',
  'sourcePort', 'targetPort', 'taxiDir', 'rotation', 'flipH', 'flipV',
  'hyperlink', 'metadata', 'comments', 'controlPointDistance',
]);

/**
 * Reading order for the fields CloudListService actually emits. Anything absent
 * from this list still renders, just after everything here.
 */
const FIELD_ORDER = [
  'VPC ID', 'Subnet ID', 'Availability Zone',
  'Type', 'Instance Type', 'Size',
  'CIDR Block', 'Private IP', 'Public IP', 'Elastic IP', 'DNS Name', 'Endpoint',
  'Security Groups', 'IAM Role',
  'Attached to', 'Instance ID', 'Instances', 'Target Groups', 'Targets', 'Load Balancer',
  'Engine', 'Version', 'Image ID', 'Subnet Count', 'Is Default',
  'Launch Time', 'Created',
];

/** Values the backend uses to mean "nothing", which should not render as data. */
function isEmptyValue(value: unknown): boolean {
  if (value === null || value === undefined) return true;
  const s = String(value).trim();
  return s === '' || s === 'N/A' || s === 'null' || s === 'undefined' || s === '-';
}

/** Identifier-ish values get monospace; prose does not. */
function isIdentifier(key: string, value: string): boolean {
  if (/^(vpc|subnet|sg|i|vol|eni|eipalloc|igw|nat|rtb|acl|ami|snap)-[0-9a-f]{6,}$/i.test(value)) return true;
  if (/^arn:aws/i.test(value)) return true;
  if (/^\d{1,3}(\.\d{1,3}){3}(\/\d{1,2})?$/.test(value)) return true; // IPv4 / CIDR
  return /\b(id|cidr|ip|arn|group|role|endpoint|dns)\b/i.test(key);
}

const TONE_LABEL: Record<StateTone, string> = {
  healthy: 'Healthy',
  idle: 'Idle',
  warning: 'Transitioning',
  danger: 'Failed',
  unknown: 'State unknown',
};

const TONE_COLOR: Record<StateTone, string> = {
  healthy: '#10b981',
  idle: '#94a3b8',
  warning: '#f59e0b',
  danger: '#ef4444',
  unknown: '#64748b',
};

// ─── AWS console deep links ──────────────────────────────────────────────────

/**
 * Build a console URL for the resource, or null.
 *
 * Deliberately conservative: only patterns verified against the current console
 * are listed, and a missing region returns null. A link that lands on the wrong
 * page costs more trust than no link at all.
 */
function consoleUrl(type: string, id: string, region?: string): string | null {
  if (!id) return null;
  const r = region?.trim();

  // S3 is global in the console path.
  if (type === 'S3 Bucket') return `https://s3.console.aws.amazon.com/s3/buckets/${encodeURIComponent(id)}`;
  if (!r) return null;

  const base = `https://${r}.console.aws.amazon.com`;
  switch (type) {
    case 'EC2 Instance':
      return `${base}/ec2/home?region=${r}#InstanceDetails:instanceId=${encodeURIComponent(id)}`;
    case 'EBS Volume':
      return `${base}/ec2/home?region=${r}#VolumeDetails:volumeId=${encodeURIComponent(id)}`;
    case 'Security Group':
      return `${base}/ec2/home?region=${r}#SecurityGroup:groupId=${encodeURIComponent(id)}`;
    case 'VPC':
      return `${base}/vpcconsole/home?region=${r}#VpcDetails:VpcId=${encodeURIComponent(id)}`;
    case 'Subnet':
      return `${base}/vpcconsole/home?region=${r}#SubnetDetails:subnetId=${encodeURIComponent(id)}`;
    case 'Internet Gateway':
      return `${base}/vpcconsole/home?region=${r}#InternetGateway:internetGatewayId=${encodeURIComponent(id)}`;
    case 'NAT Gateway':
      return `${base}/vpcconsole/home?region=${r}#NatGatewayDetails:natGatewayId=${encodeURIComponent(id)}`;
    case 'RDS Instance':
      return `${base}/rds/home?region=${r}#database:id=${encodeURIComponent(id)}`;
    case 'Lambda Function':
      return `${base}/lambda/home?region=${r}#/functions/${encodeURIComponent(id)}`;
    case 'EKS Cluster':
      return `${base}/eks/home?region=${r}#/clusters/${encodeURIComponent(id)}`;
    case 'DynamoDB Table':
      return `${base}/dynamodbv2/home?region=${r}#table?name=${encodeURIComponent(id)}`;
    case 'SQS Queue':
      return `${base}/sqs/v2/home?region=${r}#/queues`;
    case 'SNS Topic':
      return `${base}/sns/v3/home?region=${r}#/topics`;
    case 'CloudWatch Log Group':
      return `${base}/cloudwatch/home?region=${r}#logsV2:log-groups`;
    default:
      return null;
  }
}

// ─── Copy button ─────────────────────────────────────────────────────────────

const CopyButton: React.FC<{ value: string; label: string; theme: CanvasTheme }> = ({ value, label, theme }) => {
  const [copied, setCopied] = useState(false);
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => () => { if (timer.current) clearTimeout(timer.current); }, []);

  const copy = useCallback(async () => {
    let ok = false;
    try {
      if (navigator.clipboard?.writeText) {
        await navigator.clipboard.writeText(value);
        ok = true;
      }
    } catch { /* fall through to the legacy path below */ }
    if (!ok) {
      try {
        const ta = document.createElement('textarea');
        ta.value = value;
        ta.setAttribute('readonly', '');
        ta.style.position = 'fixed';
        ta.style.left = '-9999px';
        document.body.appendChild(ta);
        ta.select();
        ok = document.execCommand('copy');
        document.body.removeChild(ta);
      } catch { ok = false; }
    }
    if (!ok) return;
    setCopied(true);
    if (timer.current) clearTimeout(timer.current);
    timer.current = setTimeout(() => setCopied(false), 1600);
  }, [value]);

  const t = CANVAS_TOKENS[theme];

  return (
    <button
      type="button"
      onClick={copy}
      title={copied ? 'Copied' : `Copy ${label}`}
      aria-label={copied ? `${label} copied` : `Copy ${label}`}
      className="ri-copy"
      style={{ color: copied ? t.accent : t.inkMuted }}
    >
      <i className={copied ? 'fas fa-check' : 'far fa-copy'} aria-hidden="true" />
    </button>
  );
};

// ─── Panel ───────────────────────────────────────────────────────────────────

export interface ResourceInspectorProps {
  node: NodeData | null;
  open: boolean;
  onClose: () => void;
  theme: CanvasTheme;
  /** Focus this resource's neighbourhood on the canvas. */
  onIsolate?: (nodeId: string) => void;
  /** True while a focus/isolate filter is active, so the control can toggle. */
  isolated?: boolean;
}

const ResourceInspector: React.FC<ResourceInspectorProps> = ({
  node, open, onClose, theme, onIsolate, isolated,
}) => {
  const closeRef = useRef<HTMLButtonElement>(null);

  // Esc closes. The panel is non-modal, so this listens at document level rather
  // than trapping focus — the canvas behind stays usable.
  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== 'Escape') return;
      const tag = (e.target as HTMLElement)?.tagName;
      if (tag === 'INPUT' || tag === 'TEXTAREA') return;
      onClose();
    };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [open, onClose]);

  const t = CANVAS_TOKENS[theme];

  const fields = useMemo(() => {
    if (!node) return [];
    const entries = Object.entries(node)
      .filter(([k, v]) => !HIDDEN_KEYS.has(k) && typeof v !== 'object' && !isEmptyValue(v))
      .map(([k, v]) => ({ key: k, value: String(v) }));

    const rank = (k: string) => {
      const i = FIELD_ORDER.indexOf(k);
      return i === -1 ? FIELD_ORDER.length : i;
    };
    entries.sort((a, b) => {
      const d = rank(a.key) - rank(b.key);
      return d !== 0 ? d : a.key.localeCompare(b.key);
    });
    return entries;
  }, [node]);

  if (!node) return null;

  const type = node.type || 'Resource';
  const typeInfo = NODE_TYPE_DATA[type];
  const palette = typeInfo ? TAILWIND_COLORS[typeInfo.color as keyof typeof TAILWIND_COLORS] : undefined;
  const hue = palette?.[500] ?? t.inkMuted;
  const iconSrc = AWS_ICONS[type];

  const rawState = (node as any).state ?? (node as any).State;
  const tone = stateTone(rawState);
  const region = (node as any).region ?? (node as any).Region;
  const href = consoleUrl(type, node.id, region);

  return (
    <aside
      className={`info-panel ri-panel ${open ? 'open' : ''}`}
      aria-label={`${type} details`}
      style={{
        background: t.card,
        borderColor: t.hairline,
        color: t.ink,
      }}
    >
      {/* ── Header ─────────────────────────────────────────────────────────── */}
      <header className="ri-header" style={{ borderColor: t.hairline }}>
        <div
          className="ri-well"
          style={{
            background: theme === 'light' ? (palette?.[100] ?? '#eef2f7') : 'rgba(255,255,255,0.05)',
            borderColor: hue,
          }}
        >
          {iconSrc
            ? <img src={iconSrc} alt="" aria-hidden="true" width={26} height={26} />
            : <span className="ri-well-glyph" style={{ color: hue }}>
                {type.split(/[\s-]+/).slice(0, 2).map((w) => w[0]?.toUpperCase() ?? '').join('')}
              </span>}
        </div>

        <div className="ri-heading">
          <h2 className="ri-name" style={{ color: t.ink }} title={node.label}>{node.label}</h2>
          <p className="ri-type" style={{ color: t.inkMuted }}>
            {type}
            {region ? <><span className="ri-sep" aria-hidden="true">·</span><span className="ri-region">{region}</span></> : null}
          </p>
        </div>

        <button
          ref={closeRef}
          type="button"
          onClick={onClose}
          className="ri-close"
          style={{ color: t.inkMuted }}
          aria-label="Close details"
        >
          <i className="fas fa-times" aria-hidden="true" />
        </button>
      </header>

      {/* ── Status + identifier ────────────────────────────────────────────── */}
      <div className="ri-status-row">
        <span
          className="ri-status"
          style={{
            color: TONE_COLOR[tone],
            background: `${TONE_COLOR[tone]}1f`,
            borderColor: `${TONE_COLOR[tone]}52`,
          }}
        >
          {tone === 'unknown'
            ? <span className="ri-dot ri-dot-hollow" style={{ borderColor: TONE_COLOR[tone] }} />
            : <span className="ri-dot" style={{ background: TONE_COLOR[tone] }} />}
          {rawState ? String(rawState) : TONE_LABEL.unknown}
        </span>
      </div>

      <div className="ri-id-row" style={{ background: t.canvas, borderColor: t.hairline }}>
        <code className="ri-id" style={{ color: t.inkMuted }} title={node.id}>{node.id}</code>
        <CopyButton value={node.id} label="resource ID" theme={theme} />
      </div>

      {/* ── Actions ────────────────────────────────────────────────────────── */}
      <div className="ri-actions">
        {onIsolate && (
          <button
            type="button"
            onClick={() => onIsolate(node.id)}
            className="ri-action"
            style={{
              background: isolated ? t.accentSoft : 'transparent',
              borderColor: isolated ? t.accent : t.hairline,
              color: isolated ? t.accent : t.ink,
            }}
          >
            <i className={isolated ? 'fas fa-compress-arrows-alt' : 'fas fa-crosshairs'} aria-hidden="true" />
            {isolated ? 'Show all' : 'Focus connections'}
          </button>
        )}
        {href && (
          <a
            href={href}
            target="_blank"
            rel="noopener noreferrer"
            className="ri-action"
            style={{ borderColor: t.hairline, color: t.ink }}
          >
            <i className="fas fa-arrow-up-right-from-square" aria-hidden="true" />
            AWS console
          </a>
        )}
      </div>

      {/* ── Fields ─────────────────────────────────────────────────────────── */}
      <div className="ri-fields">
        {fields.length === 0 ? (
          <p className="ri-no-fields" style={{ color: t.inkMuted }}>
            No additional attributes were returned for this resource.
          </p>
        ) : (
          <dl>
            {fields.map(({ key, value }) => {
              const mono = isIdentifier(key, value);
              const multi = value.includes(',') && value.length > 28;
              return (
                <div key={key} className="ri-field" style={{ borderColor: t.hairline }}>
                  <dt style={{ color: t.inkMuted }}>{key}</dt>
                  <dd style={{ color: t.ink }}>
                    {multi ? (
                      <span className="ri-chips">
                        {value.split(/\s*,\s*/).filter(Boolean).map((part) => (
                          <code
                            key={part}
                            className="ri-chip"
                            style={{ background: t.canvas, borderColor: t.hairline, color: t.ink }}
                          >
                            {part}
                          </code>
                        ))}
                      </span>
                    ) : (
                      <span className={mono ? 'ri-value ri-value-mono' : 'ri-value'}>{value}</span>
                    )}
                    {mono && !multi ? <CopyButton value={value} label={key} theme={theme} /> : null}
                  </dd>
                </div>
              );
            })}
          </dl>
        )}
      </div>
    </aside>
  );
};

export default ResourceInspector;
