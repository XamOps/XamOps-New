import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Icon } from '@iconify/react';
import { DevOpsScript } from '../../types/devopsInTheBox';
import { DialogRoot, DialogPanel } from '../ui';

/**
 * ScriptCustomizerModal — fill in a script's {{VARIABLE}} placeholders with your
 * own infrastructure details, see a live preview, then copy or download the
 * substituted script.
 *
 * It is fully self-contained and works offline: placeholders are parsed directly
 * from `script.code` on the client (no backend parameter metadata required).
 *
 * Requirements: REQ-2.2.2, REQ-2.2.3, REQ-2.2.5, REQ-2.2.6, REQ-2.5.3
 */

// ─── Placeholder parsing ──────────────────────────────────────────────────────

/** Matches `{{NAME}}` tokens (optional inner whitespace). NAME = [A-Za-z0-9_]+. */
export const PLACEHOLDER_REGEX = /\{\{\s*([A-Za-z0-9_]+)\s*\}\}/g;

/**
 * Extract the DISTINCT placeholder names from a script body, preserving the
 * order in which they first appear.
 */
export function parsePlaceholders(code: string): string[] {
  if (!code) return [];
  const seen = new Set<string>();
  const ordered: string[] = [];
  for (const match of code.matchAll(PLACEHOLDER_REGEX)) {
    const name = match[1];
    if (!seen.has(name)) {
      seen.add(name);
      ordered.push(name);
    }
  }
  return ordered;
}

/** `INSTANCE_TYPE` → `Instance Type`, `vpc_id` → `Vpc Id`. */
export function humanizePlaceholder(name: string): string {
  return name
    .split(/[_\s]+/)
    .filter(Boolean)
    .map((word) => word.charAt(0).toUpperCase() + word.slice(1).toLowerCase())
    .join(' ');
}

/**
 * Replace `{{NAME}}` tokens with the user-entered value. A blank/whitespace-only
 * value leaves the original token in place so the user can see what's left.
 */
export function substitutePlaceholders(code: string, values: Record<string, string>): string {
  if (!code) return '';
  return code.replace(PLACEHOLDER_REGEX, (token, name: string) => {
    const value = values[name];
    return value && value.trim() !== '' ? value : token;
  });
}

// ─── Preview segmentation (for highlighting unfilled tokens) ───────────────────

type PreviewSegment =
  | { kind: 'text'; text: string }
  | { kind: 'value'; text: string }
  | { kind: 'placeholder'; text: string };

/**
 * Split `code` into renderable segments: plain text, substituted values, and
 * still-unfilled `{{TOKEN}}` placeholders (which the UI highlights).
 */
export function buildPreviewSegments(code: string, values: Record<string, string>): PreviewSegment[] {
  if (!code) return [];
  const segments: PreviewSegment[] = [];
  let lastIndex = 0;
  // Use a fresh regex to avoid shared lastIndex state.
  const re = new RegExp(PLACEHOLDER_REGEX.source, 'g');
  let match: RegExpExecArray | null;
  while ((match = re.exec(code)) !== null) {
    if (match.index > lastIndex) {
      segments.push({ kind: 'text', text: code.slice(lastIndex, match.index) });
    }
    const name = match[1];
    const value = values[name];
    if (value && value.trim() !== '') {
      segments.push({ kind: 'value', text: value });
    } else {
      segments.push({ kind: 'placeholder', text: match[0] });
    }
    lastIndex = match.index + match[0].length;
  }
  if (lastIndex < code.length) {
    segments.push({ kind: 'text', text: code.slice(lastIndex) });
  }
  return segments;
}

// ─── File naming (mirrors DevOpsInTheBoxPage.getFileName + ansible) ────────────

export function getFileName(script: DevOpsScript): string {
  const name = (script.name || 'script').replace(/\s+/g, '-').toLowerCase();
  switch (script.category?.toLowerCase()) {
    case 'python':
      return `${name}.py`;
    case 'bash':
      return `${name}.sh`;
    case 'terraform':
      return 'main.tf';
    case 'ansible':
      return `${name}.yml`;
    default:
      return `${name}.txt`;
  }
}

// ─── Clipboard helper (modern API + execCommand fallback) ──────────────────────

async function copyToClipboard(text: string): Promise<boolean> {
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(text);
      return true;
    }
  } catch {
    // fall through to legacy path
  }
  try {
    const textarea = document.createElement('textarea');
    textarea.value = text;
    textarea.setAttribute('readonly', '');
    textarea.style.position = 'absolute';
    textarea.style.left = '-9999px';
    document.body.appendChild(textarea);
    textarea.select();
    const ok = document.execCommand('copy');
    document.body.removeChild(textarea);
    return ok;
  } catch {
    return false;
  }
}

// ─── Props ─────────────────────────────────────────────────────────────────────

export interface ScriptCustomizerModalProps {
  script: DevOpsScript;
  isOpen: boolean;
  onClose: () => void;
}

// ─── Component ───────────────────────────────────────────────────────────────────

const ScriptCustomizerModal: React.FC<ScriptCustomizerModalProps> = ({ script, isOpen, onClose }) => {
  const placeholders = useMemo(() => parsePlaceholders(script.code), [script.code]);

  const [values, setValues] = useState<Record<string, string>>({});
  const [copied, setCopied] = useState(false);
  const copyTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

  // Reset form whenever a different script is opened.
  useEffect(() => {
    setValues({});
    setCopied(false);
  }, [script.id, isOpen]);

  useEffect(() => () => {
    if (copyTimer.current) clearTimeout(copyTimer.current);
  }, []);

  const segments = useMemo(() => buildPreviewSegments(script.code, values), [script.code, values]);
  const substituted = useMemo(() => substitutePlaceholders(script.code, values), [script.code, values]);

  const unfilledCount = useMemo(
    () => placeholders.filter((name) => !(values[name] && values[name].trim() !== '')).length,
    [placeholders, values]
  );

  const fileName = useMemo(() => getFileName(script), [script]);

  const handleChange = useCallback((name: string, value: string) => {
    setValues((prev) => ({ ...prev, [name]: value }));
  }, []);

  const handleCopy = useCallback(async () => {
    const ok = await copyToClipboard(substituted);
    if (ok) {
      setCopied(true);
      if (copyTimer.current) clearTimeout(copyTimer.current);
      copyTimer.current = setTimeout(() => setCopied(false), 2000);
    }
  }, [substituted]);

  const handleDownload = useCallback(() => {
    const el = document.createElement('a');
    el.href = URL.createObjectURL(new Blob([substituted], { type: 'text/plain' }));
    el.download = fileName;
    document.body.appendChild(el);
    el.click();
    document.body.removeChild(el);
  }, [substituted, fileName]);

  // Ctrl/Cmd+C copies the substituted preview when the preview is focused,
  // unless the user is actively selecting text.
  const handlePreviewKeyDown = useCallback(
    (e: React.KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'c') {
        const selection = window.getSelection?.()?.toString() ?? '';
        if (selection.trim() === '') {
          e.preventDefault();
          void handleCopy();
        }
      }
    },
    [handleCopy]
  );

  return (
    <DialogRoot isOpen={isOpen} onClose={onClose} maxWidth="max-w-5xl">
      <DialogPanel
        onClose={onClose}
        title={
          <div className="flex items-center gap-3 min-w-0">
            <div className="w-9 h-9 bg-indigo-50 border border-indigo-200 rounded-lg flex items-center justify-center flex-shrink-0">
              <Icon icon="mdi:tune-variant" width={17} color="#4F46E5" />
            </div>
            <div className="min-w-0">
              <p className="text-[15px] font-bold text-[rgba(0,0,0,0.88)] truncate">Customize &amp; Copy</p>
              <p className="text-[12px] text-[rgba(0,0,0,0.45)] mt-0.5 truncate">{script.name}</p>
            </div>
          </div>
        }
        footer={
          <div className="flex items-center gap-3 w-full">
            <span className="text-[12px] text-[rgba(0,0,0,0.45)] mr-auto">
              {unfilledCount === 0 ? (
                <span className="flex items-center gap-1.5 text-emerald-600 font-semibold">
                  <Icon icon="mdi:check-circle-outline" width={14} />
                  All variables filled
                </span>
              ) : (
                <span className="flex items-center gap-1.5">
                  <Icon icon="mdi:alert-circle-outline" width={14} color="#D97706" />
                  {unfilledCount} variable{unfilledCount === 1 ? '' : 's'} still using placeholder{unfilledCount === 1 ? '' : 's'}
                </span>
              )}
            </span>
            <button
              type="button"
              onClick={handleDownload}
              className="flex items-center gap-2 px-5 py-2.5 text-[13px] font-bold text-white bg-emerald-600 hover:bg-emerald-700 rounded-xl transition-colors outline-none focus-visible:ring-2 focus-visible:ring-emerald-400"
            >
              <Icon icon="mdi:download-outline" width={14} />
              Download
            </button>
            <button
              type="button"
              onClick={onClose}
              className="px-5 py-2.5 text-[13px] font-semibold text-[rgba(0,0,0,0.65)] bg-white border border-[rgba(0,0,0,0.12)] rounded-xl hover:bg-[rgba(0,0,0,0.02)] transition-colors outline-none focus-visible:ring-2 focus-visible:ring-indigo-400"
            >
              Close
            </button>
          </div>
        }
      >
        <div className="grid grid-cols-1 lg:grid-cols-2 gap-5">
          {/* LEFT: form */}
          <div>
            <h3 className="text-[12px] font-bold text-[rgba(0,0,0,0.55)] uppercase tracking-[0.07em] mb-3">
              Your Infrastructure Details
            </h3>
            {placeholders.length === 0 ? (
              <div className="bg-[rgba(0,0,0,0.02)] border border-[rgba(0,0,0,0.06)] rounded-xl p-5 text-[13px] text-[rgba(0,0,0,0.5)]">
                This script has no customizable variables. You can copy or download it as-is.
              </div>
            ) : (
              <form className="space-y-4" aria-label="Script variables">
                {placeholders.map((name) => {
                  const inputId = `param-${name}`;
                  return (
                    <div key={name}>
                      <label
                        htmlFor={inputId}
                        className="block text-[12px] font-semibold text-[rgba(0,0,0,0.7)] mb-1.5"
                      >
                        {humanizePlaceholder(name)}
                        <span className="ml-1.5 font-mono text-[10px] text-[rgba(0,0,0,0.35)]">{`{{${name}}}`}</span>
                      </label>
                      <input
                        id={inputId}
                        type="text"
                        value={values[name] ?? ''}
                        onChange={(e) => handleChange(name, e.target.value)}
                        placeholder={`Enter ${humanizePlaceholder(name)}…`}
                        aria-label={humanizePlaceholder(name)}
                        className="w-full px-3.5 py-2.5 border border-[rgba(0,0,0,0.12)] rounded-lg text-[13px] text-[rgba(0,0,0,0.88)] focus:ring-2 focus:ring-indigo-400 focus:border-indigo-400 outline-none bg-white placeholder-[rgba(0,0,0,0.3)]"
                      />
                    </div>
                  );
                })}
              </form>
            )}
          </div>

          {/* RIGHT: live preview */}
          <div>
            <h3 className="text-[12px] font-bold text-[rgba(0,0,0,0.55)] uppercase tracking-[0.07em] mb-3">
              Live Preview
            </h3>
            <div
              className="rounded-xl overflow-hidden border border-[rgba(255,255,255,0.06)]"
              style={{ background: '#1a1d27' }}
            >
              <div className="flex items-center justify-between px-4 py-3 border-b border-[rgba(255,255,255,0.07)]">
                <span className="text-[12px] font-mono text-[rgba(255,255,255,0.5)] truncate">{fileName}</span>
                <button
                  type="button"
                  onClick={handleCopy}
                  aria-label="Copy customized script to clipboard"
                  className={`flex items-center gap-1.5 text-[12px] font-bold px-3 py-1.5 rounded-lg transition-colors outline-none focus-visible:ring-2 focus-visible:ring-indigo-400 ${
                    copied
                      ? 'bg-emerald-500 text-white'
                      : 'bg-indigo-600 hover:bg-indigo-700 text-white'
                  }`}
                >
                  <Icon icon={copied ? 'mdi:check' : 'mdi:content-copy'} width={14} />
                  {copied ? 'Copied!' : 'Copy Script'}
                </button>
              </div>
              <div
                role="region"
                aria-label="Customized script preview"
                tabIndex={0}
                onKeyDown={handlePreviewKeyDown}
                className="p-4 overflow-auto max-h-[52vh] outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-indigo-400"
              >
                <pre className="text-[13px] font-mono whitespace-pre-wrap break-words" style={{ color: '#4ade80' }}>
                  {segments.map((seg, i) => {
                    if (seg.kind === 'placeholder') {
                      return (
                        <span
                          key={i}
                          data-testid="unfilled-placeholder"
                          title="Unfilled variable"
                          className="rounded px-1"
                          style={{ background: 'rgba(245,158,11,0.18)', color: '#fbbf24' }}
                        >
                          {seg.text}
                        </span>
                      );
                    }
                    return <span key={i}>{seg.text}</span>;
                  })}
                </pre>
              </div>
            </div>
            <p className="text-[11px] text-[rgba(0,0,0,0.4)] mt-2 flex items-center gap-1.5">
              <Icon icon="mdi:keyboard-outline" width={13} />
              Tip: focus the preview and press Ctrl/Cmd + C to copy.
            </p>
          </div>
        </div>
      </DialogPanel>
    </DialogRoot>
  );
};

export default ScriptCustomizerModal;
