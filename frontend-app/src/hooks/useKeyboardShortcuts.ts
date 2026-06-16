import { useEffect, useCallback, type RefObject } from 'react';

// ─── Types ────────────────────────────────────────────────────────────────────

interface UseKeyboardShortcutsOptions {
  /** Callback for Ctrl+N / Cmd+N — create new job */
  onNewJob?: () => void;
  /** Ref to search input for Ctrl+K / Cmd+K — focus search */
  searchInputRef?: RefObject<HTMLInputElement | null>;
  /** Callback for Escape — close modals */
  onEscape?: () => void;
  /** Whether shortcuts are enabled (default: true) */
  enabled?: boolean;
}

// ─── Hook ─────────────────────────────────────────────────────────────────────

/**
 * Registers global keyboard shortcuts for the MediaOps page:
 * - Ctrl+N / Cmd+N → new job
 * - Ctrl+K / Cmd+K → focus search input
 * - Escape → close modals
 */
export function useKeyboardShortcuts({
  onNewJob,
  searchInputRef,
  onEscape,
  enabled = true,
}: UseKeyboardShortcutsOptions) {
  const handleKeyDown = useCallback(
    (e: KeyboardEvent) => {
      if (!enabled) return;

      const isModifier = e.ctrlKey || e.metaKey;

      // Ctrl+N / Cmd+N → new job
      if (isModifier && e.key.toLowerCase() === 'n') {
        e.preventDefault();
        onNewJob?.();
        return;
      }

      // Ctrl+K / Cmd+K → focus search
      if (isModifier && e.key.toLowerCase() === 'k') {
        e.preventDefault();
        searchInputRef?.current?.focus();
        return;
      }

      // Escape → close modals
      if (e.key === 'Escape') {
        e.preventDefault();
        onEscape?.();
        return;
      }
    },
    [enabled, onNewJob, searchInputRef, onEscape]
  );

  useEffect(() => {
    if (!enabled) return;

    document.addEventListener('keydown', handleKeyDown);
    return () => {
      document.removeEventListener('keydown', handleKeyDown);
    };
  }, [enabled, handleKeyDown]);
}

export default useKeyboardShortcuts;
