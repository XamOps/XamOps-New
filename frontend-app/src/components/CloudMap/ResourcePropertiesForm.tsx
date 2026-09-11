/**
 * ResourcePropertiesForm — configure the selected resource's Terraform attributes.
 *
 * This is what turns the design canvas from a picture into a specification.
 * Without it every generated resource carries catalog defaults, so every VPC gets
 * 10.0.0.0/16 and every database is a db.t3.micro.
 *
 * WHERE VALUES ARE STORED
 * Straight onto the Cytoscape node's data, under the attribute's own name. That
 * is deliberate and it is why this needed no schema change: the canvas already
 * serialises node data into `architecture_blueprints.graph_data` on save, and
 * CanvasDesignTranslator already reads attributes off node data by name. So
 * persistence and generation both come for free, and a design saved by an older
 * build simply has no attributes set and falls back to defaults.
 *
 * The form is generated from the catalog rather than hand-written per type, so
 * adding a resource type to the backend catalog makes it configurable here with
 * no frontend change.
 */

import React, { useCallback, useMemo, useState } from 'react';
import type { AttributeSpec, ResourceTypeSpec } from '../../hooks/useDesignCatalog';
import { CANVAS_TOKENS, type CanvasTheme } from './canvasTheme';

export interface ResourcePropertiesFormProps {
  spec: ResourceTypeSpec;
  /** Current values, read from the selected node's data. */
  values: Record<string, unknown>;
  onChange: (attributeName: string, value: unknown) => void;
  theme: CanvasTheme;
  /** Reference fields already satisfied on the canvas, for the summary below. */
  satisfiedReferences: Record<string, number>;
}

/** IPv4 CIDR, octets and prefix range-checked. Mirrors DesignValidator. */
function isValidCidr(value: string): boolean {
  const m = /^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})\/(\d{1,2})$/.exec(value.trim());
  if (!m) return false;
  for (let i = 1; i <= 4; i++) if (Number(m[i]) > 255) return false;
  return Number(m[5]) <= 32;
}

/**
 * Validate one value for display.
 *
 * Deliberately the same rules the backend enforces, not extra ones. A field the
 * form accepts but the backend rejects is confusing; a field the form rejects but
 * the backend accepts is worse, because it blocks legitimate input.
 */
function fieldError(attr: AttributeSpec, raw: unknown): string | null {
  const empty = raw === undefined || raw === null || (typeof raw === 'string' && raw.trim() === '');
  if (empty) {
    return attr.required && !attr.defaultValue ? 'Required' : null;
  }
  const text = String(raw);
  switch (attr.kind) {
    case 'CIDR':
      return isValidCidr(text) ? null : 'Not a valid CIDR block, e.g. 10.0.0.0/16';
    case 'NUMBER':
      return Number.isFinite(Number(text)) ? null : 'Must be a number';
    case 'ENUM':
      return attr.options.includes(text) ? null : `Must be one of ${attr.options.join(', ')}`;
    default:
      return null;
  }
}

/** Turn `map_public_ip_on_launch` into `Map public ip on launch`. */
function humanize(name: string): string {
  const words = name.replace(/_/g, ' ').trim();
  return words.charAt(0).toUpperCase() + words.slice(1);
}

// ─── Tag editor ──────────────────────────────────────────────────────────────

const TagsEditor: React.FC<{
  value: Record<string, string>;
  onChange: (next: Record<string, string>) => void;
  theme: CanvasTheme;
}> = ({ value, onChange, theme }) => {
  const t = CANVAS_TOKENS[theme];
  const entries = useMemo(() => Object.entries(value ?? {}), [value]);
  const [newKey, setNewKey] = useState('');
  const [newValue, setNewValue] = useState('');

  const add = () => {
    const key = newKey.trim();
    if (!key) return;
    onChange({ ...value, [key]: newValue });
    setNewKey('');
    setNewValue('');
  };

  return (
    <div className="rp-tags">
      {entries.map(([key, val]) => (
        <div className="rp-tag-row" key={key}>
          <input
            className="rp-input rp-tag-key"
            value={key}
            readOnly
            aria-label={`Tag key ${key}`}
            style={{ background: t.canvas, borderColor: t.hairline, color: t.inkMuted }}
          />
          <input
            className="rp-input"
            value={val}
            aria-label={`Value for tag ${key}`}
            onChange={(e) => onChange({ ...value, [key]: e.target.value })}
            style={{ background: t.canvas, borderColor: t.hairline, color: t.ink }}
          />
          <button
            type="button"
            className="rp-tag-remove"
            aria-label={`Remove tag ${key}`}
            onClick={() => {
              const next = { ...value };
              delete next[key];
              onChange(next);
            }}
            style={{ color: t.inkMuted }}
          >
            <i className="fas fa-times" aria-hidden="true" />
          </button>
        </div>
      ))}

      <div className="rp-tag-row">
        <input
          className="rp-input rp-tag-key"
          placeholder="Key"
          value={newKey}
          aria-label="New tag key"
          onChange={(e) => setNewKey(e.target.value)}
          onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); add(); } }}
          style={{ background: t.canvas, borderColor: t.hairline, color: t.ink }}
        />
        <input
          className="rp-input"
          placeholder="Value"
          value={newValue}
          aria-label="New tag value"
          onChange={(e) => setNewValue(e.target.value)}
          onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); add(); } }}
          style={{ background: t.canvas, borderColor: t.hairline, color: t.ink }}
        />
        <button
          type="button"
          className="rp-tag-remove"
          onClick={add}
          disabled={!newKey.trim()}
          aria-label="Add tag"
          style={{ color: newKey.trim() ? t.accent : t.inkMuted }}
        >
          <i className="fas fa-plus" aria-hidden="true" />
        </button>
      </div>
    </div>
  );
};

// ─── Form ────────────────────────────────────────────────────────────────────

const ResourcePropertiesForm: React.FC<ResourcePropertiesFormProps> = ({
  spec, values, onChange, theme, satisfiedReferences,
}) => {
  const t = CANVAS_TOKENS[theme];

  const inputStyle = {
    background: t.canvas,
    borderColor: t.hairline,
    color: t.ink,
  } as React.CSSProperties;

  const renderField = useCallback((attr: AttributeSpec) => {
    // BLOCK_LIST (security group rules) needs a repeating sub-form. Until that
    // exists, say so rather than rendering a text box that would corrupt the
    // structure the emitter expects.
    if (attr.kind === 'BLOCK_LIST') {
      const count = Array.isArray(values[attr.name]) ? (values[attr.name] as unknown[]).length : 0;
      return (
        <p className="rp-unsupported" style={{ color: t.inkMuted }}>
          {count > 0
            ? `${count} rule${count === 1 ? '' : 's'} defined. Editing rules here is not built yet.`
            : 'Rule editing is not built yet, so none will be generated.'}
        </p>
      );
    }

    if (attr.kind === 'TAGS') {
      const current = (values[attr.name] as Record<string, string>) ?? {};
      return (
        <TagsEditor
          value={current}
          theme={theme}
          onChange={(next) => onChange(attr.name, Object.keys(next).length ? next : undefined)}
        />
      );
    }

    if (attr.kind === 'BOOL') {
      // Default matters here: an unset boolean is not false, it is "whatever the
      // catalog default says", so the control has to show the effective value.
      const effective = values[attr.name] !== undefined
        ? values[attr.name] === true || values[attr.name] === 'true'
        : attr.defaultValue === 'true';
      return (
        <label className="rp-switch">
          <input
            type="checkbox"
            checked={effective}
            onChange={(e) => onChange(attr.name, e.target.checked)}
          />
          <span className="rp-switch-label" style={{ color: t.inkMuted }}>
            {effective ? 'Enabled' : 'Disabled'}
          </span>
        </label>
      );
    }

    if (attr.kind === 'ENUM') {
      return (
        <select
          className="rp-input"
          value={String(values[attr.name] ?? attr.defaultValue ?? '')}
          onChange={(e) => onChange(attr.name, e.target.value)}
          style={inputStyle}
        >
          {!attr.required && <option value="">Not set</option>}
          {attr.options.map((option) => (
            <option key={option} value={option}>{option}</option>
          ))}
        </select>
      );
    }

    const isNumber = attr.kind === 'NUMBER';
    return (
      <input
        className="rp-input"
        type={isNumber ? 'number' : 'text'}
        inputMode={isNumber ? 'numeric' : undefined}
        value={String(values[attr.name] ?? '')}
        // Placeholder carries the default, so an empty field reads as "will use
        // this" rather than "will be missing".
        placeholder={attr.defaultValue ?? (attr.required ? 'Required' : 'Not set')}
        onChange={(e) => {
          const raw = e.target.value;
          if (raw === '') {
            onChange(attr.name, undefined);
            return;
          }
          onChange(attr.name, isNumber ? Number(raw) : raw);
        }}
        style={inputStyle}
      />
    );
  }, [values, onChange, theme, t, inputStyle]);

  return (
    <div className="rp-form">
      <div className="rp-type" style={{ borderColor: t.hairline }}>
        <span className="rp-type-name" style={{ color: t.ink }}>{spec.displayName}</span>
        <code className="rp-type-tf" style={{ color: t.inkMuted }}>{spec.tfType}</code>
      </div>

      {spec.attributes.map((attr) => {
        const error = fieldError(attr, values[attr.name]);
        return (
          <div className="rp-field" key={attr.name}>
            <label className="rp-label" style={{ color: t.inkMuted }}>
              <span>{humanize(attr.name)}</span>
              {attr.required && <span className="rp-required" style={{ color: '#ef4444' }}>*</span>}
              {attr.promoteToVariable && (
                <span
                  className="rp-badge"
                  title="Emitted as a Terraform variable so it can be changed per environment"
                  style={{ background: t.accentSoft, color: t.accent }}
                >
                  variable
                </span>
              )}
            </label>

            {renderField(attr)}

            {error
              ? <p className="rp-error" style={{ color: '#ef4444' }}>{error}</p>
              : attr.description
                ? <p className="rp-help" style={{ color: t.inkMuted }}>{attr.description}</p>
                : null}
          </div>
        );
      })}

      {/* References come from the canvas, not from this form. Showing their state
          here explains why a required one is missing and what gesture fixes it. */}
      {spec.references.length > 0 && (
        <div className="rp-refs" style={{ borderColor: t.hairline }}>
          <p className="rp-refs-title" style={{ color: t.inkMuted }}>Connections</p>
          {spec.references.map((ref) => {
            const count = satisfiedReferences[ref.field] ?? 0;
            const missing = ref.required && count === 0;
            return (
              <div className="rp-ref" key={ref.field}>
                <span
                  className="rp-ref-dot"
                  style={{ background: count > 0 ? '#10b981' : missing ? '#ef4444' : t.inkMuted }}
                />
                <span className="rp-ref-field" style={{ color: t.ink }}>{ref.field}</span>
                <span className="rp-ref-hint" style={{ color: t.inkMuted }}>
                  {count > 0
                    ? `${count} connected`
                    : ref.via === 'CONTAINMENT'
                      ? `place inside a ${ref.targetTfType.replace(/^aws_/, '')}`
                      : `connect to a ${ref.targetTfType.replace(/^aws_/, '')}`}
                </span>
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
};

export default ResourcePropertiesForm;
