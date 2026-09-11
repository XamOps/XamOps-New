/**
 * TerraformPanel — shows the Terraform generated from the current design.
 *
 * Read-only by design. The canvas is the source of truth and the HCL is an
 * artifact of it, so there is no editing here: letting someone hand-edit
 * generated code and reflecting it back onto the diagram is the hardest problem
 * in this space, and attempting it early produces a model that fights its own
 * output.
 *
 * The panel's real job beyond display is telling the truth about coverage. A
 * design canvas is a general drawing tool, so a diagram legitimately contains
 * shapes that are not cloud resources. Rather than failing, the backend reports
 * what it skipped and why, and this surfaces that — so nobody is left wondering
 * why part of their picture is missing from the output.
 */

import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type { GeneratedTerraform } from '../../hooks/useTerraformPreview';
import { CANVAS_TOKENS, type CanvasTheme } from './canvasTheme';
import { tokenizeHcl, HCL_COLORS } from './hclHighlight';

export interface TerraformPanelProps {
  result: GeneratedTerraform & { loading: boolean; error: string | null };
  theme: CanvasTheme;
  designName: string;
  onClose: () => void;
  /** Select the offending card when a finding is clicked. */
  onFocusNode?: (nodeId: string) => void;
}

/** Reading order: providers, variables, main, outputs. */
const FILE_ORDER = ['providers.tf', 'variables.tf', 'main.tf', 'outputs.tf'];

function orderFiles(files: Record<string, string>): string[] {
  const names = Object.keys(files);
  return names.sort((a, b) => {
    const ia = FILE_ORDER.indexOf(a);
    const ib = FILE_ORDER.indexOf(b);
    if (ia !== -1 && ib !== -1) return ia - ib;
    if (ia !== -1) return -1;
    if (ib !== -1) return 1;
    return a.localeCompare(b);
  });
}

function slugify(value: string): string {
  return (value || 'design').trim().toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '') || 'design';
}

const TerraformPanel: React.FC<TerraformPanelProps> = ({
  result, theme, designName, onClose, onFocusNode,
}) => {
  const t = CANVAS_TOKENS[theme];
  const colors = HCL_COLORS[theme];

  const files = useMemo(() => orderFiles(result.files), [result.files]);
  const [active, setActive] = useState<string>(files[0] ?? 'main.tf');
  const [copied, setCopied] = useState(false);
  const copyTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

  // main.tf is what people want to read; default to it once it appears.
  useEffect(() => {
    if (files.length === 0) return;
    if (!files.includes(active)) setActive(files.includes('main.tf') ? 'main.tf' : files[0]);
  }, [files, active]);

  useEffect(() => () => { if (copyTimer.current) clearTimeout(copyTimer.current); }, []);

  const source = result.files[active] ?? '';
  const lines = useMemo(() => (source ? tokenizeHcl(source) : []), [source]);

  const errors = result.findings.filter((f) => f.severity === 'ERROR');
  const warnings = result.findings.filter((f) => f.severity === 'WARNING');

  const copy = useCallback(async () => {
    if (!source) return;
    let ok = false;
    try {
      if (navigator.clipboard?.writeText) {
        await navigator.clipboard.writeText(source);
        ok = true;
      }
    } catch { /* fall through */ }
    if (!ok) {
      try {
        const ta = document.createElement('textarea');
        ta.value = source;
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
    if (copyTimer.current) clearTimeout(copyTimer.current);
    copyTimer.current = setTimeout(() => setCopied(false), 1600);
  }, [source]);

  /**
   * Download every file. Terraform expects a directory of .tf files, so the
   * filenames are preserved rather than concatenated into one blob.
   */
  const downloadAll = useCallback(() => {
    const prefix = slugify(designName);
    files.forEach((file, index) => {
      // Staggered because browsers drop rapid successive programmatic downloads.
      setTimeout(() => {
        const link = document.createElement('a');
        link.href = URL.createObjectURL(new Blob([result.files[file]], { type: 'text/plain' }));
        link.download = `${prefix}-${file}`;
        document.body.appendChild(link);
        link.click();
        document.body.removeChild(link);
        URL.revokeObjectURL(link.href);
      }, index * 140);
    });
  }, [files, result.files, designName]);

  return (
    <aside
      className="tf-panel"
      aria-label="Generated Terraform"
      style={{ background: t.card, borderColor: t.hairline, color: t.ink }}
    >
      <header className="tf-header" style={{ borderColor: t.hairline }}>
        <div className="tf-title-group">
          <span className="tf-title" style={{ color: t.ink }}>Terraform</span>
          <span className="tf-subtitle" style={{ color: t.inkMuted }}>
            {result.loading
              ? 'Generating…'
              : `${result.resourceCount} resource${result.resourceCount === 1 ? '' : 's'}`}
          </span>
        </div>
        <button type="button" onClick={onClose} className="tf-close" style={{ color: t.inkMuted }} aria-label="Close Terraform panel">
          <i className="fas fa-times" aria-hidden="true" />
        </button>
      </header>

      {result.error && (
        <div className="tf-banner" style={{ background: 'rgba(239,68,68,0.12)', borderColor: 'rgba(239,68,68,0.4)', color: theme === 'dark' ? '#fca5a5' : '#b91c1c' }}>
          <i className="fas fa-triangle-exclamation" aria-hidden="true" />
          <span>{result.error}</span>
        </div>
      )}

      {/* Errors block generation entirely: half-valid Terraform looks applyable
          and is not. So explain what to fix instead of showing partial code. */}
      {!result.error && errors.length > 0 && (
        <div className="tf-blocked">
          <p className="tf-blocked-title" style={{ color: t.ink }}>
            {errors.length} thing{errors.length === 1 ? '' : 's'} to fix before this can generate
          </p>
          <ul className="tf-findings">
            {errors.map((f, i) => (
              <li key={i}>
                <button
                  type="button"
                  className="tf-finding"
                  style={{ borderColor: t.hairline, color: t.ink }}
                  onClick={() => f.nodeId && onFocusNode?.(f.nodeId)}
                  disabled={!f.nodeId}
                >
                  <span className="tf-dot" style={{ background: '#ef4444' }} />
                  <span>{f.message}</span>
                  {f.nodeId && <i className="fas fa-arrow-right tf-finding-go" aria-hidden="true" />}
                </button>
              </li>
            ))}
          </ul>
        </div>
      )}

      {!result.error && errors.length === 0 && files.length > 0 && (
        <>
          <div className="tf-tabs" role="tablist" style={{ borderColor: t.hairline }}>
            {files.map((file) => (
              <button
                key={file}
                role="tab"
                aria-selected={file === active}
                onClick={() => setActive(file)}
                className={`tf-tab${file === active ? ' active' : ''}`}
                style={{
                  color: file === active ? t.ink : t.inkMuted,
                  borderBottomColor: file === active ? t.accent : 'transparent',
                }}
              >
                {file}
              </button>
            ))}
          </div>

          <div className="tf-code-wrap" style={{ background: t.canvas }}>
            <pre className="tf-code" tabIndex={0}>
              <code>
                {lines.map((tokens, lineIndex) => (
                  <span className="tf-line" key={lineIndex}>
                    <span className="tf-gutter" style={{ color: t.inkMuted, borderColor: t.hairline }}>
                      {lineIndex + 1}
                    </span>
                    <span className="tf-line-body">
                      {tokens.map((token, tokenIndex) => (
                        <span key={tokenIndex} style={{ color: colors[token.kind] }}>{token.text}</span>
                      ))}
                    </span>
                  </span>
                ))}
              </code>
            </pre>
          </div>

          <footer className="tf-actions" style={{ borderColor: t.hairline }}>
            <button type="button" onClick={copy} className="tf-action" style={{ background: t.accent, color: theme === 'dark' ? '#04120b' : '#ffffff' }}>
              <i className={copied ? 'fas fa-check' : 'far fa-copy'} aria-hidden="true" />
              {copied ? 'Copied' : `Copy ${active}`}
            </button>
            <button type="button" onClick={downloadAll} className="tf-action tf-action-secondary" style={{ borderColor: t.hairline, color: t.ink }}>
              <i className="fas fa-download" aria-hidden="true" />
              Download all
            </button>
          </footer>
        </>
      )}

      {/* Coverage. A drawing tool holds shapes that are not resources, and saying
          so plainly is better than an output that quietly omits them. */}
      {(result.skipped.length > 0 || warnings.length > 0 || result.notes.length > 0) && (
        <details className="tf-coverage" style={{ borderColor: t.hairline }}>
          <summary style={{ color: t.inkMuted }}>
            {result.skipped.length > 0
              ? `${result.skipped.length} shape${result.skipped.length === 1 ? '' : 's'} not in the Terraform`
              : `${warnings.length + result.notes.length} note${warnings.length + result.notes.length === 1 ? '' : 's'}`}
          </summary>
          <div className="tf-coverage-body">
            {result.skipped.map((s) => (
              <button
                key={s.nodeId}
                type="button"
                className="tf-skip"
                style={{ color: t.inkMuted }}
                onClick={() => onFocusNode?.(s.nodeId)}
              >
                <strong style={{ color: t.ink }}>{s.label || s.canvasType || 'Shape'}</strong>
                <span>{s.reason}</span>
              </button>
            ))}
            {warnings.map((w, i) => (
              <p key={`w${i}`} className="tf-note" style={{ color: t.inkMuted }}>{w.message}</p>
            ))}
            {result.notes.map((n, i) => (
              <p key={`n${i}`} className="tf-note" style={{ color: t.inkMuted }}>{n}</p>
            ))}
          </div>
        </details>
      )}

      {!result.error && errors.length === 0 && files.length === 0 && !result.loading && (
        <div className="tf-empty" style={{ color: t.inkMuted }}>
          <i className="fas fa-diagram-project" aria-hidden="true" />
          <p>Add cloud resources to the canvas and their Terraform will appear here.</p>
        </div>
      )}
    </aside>
  );
};

export default TerraformPanel;
