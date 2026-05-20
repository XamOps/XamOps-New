# Palette's Journal - UX & Accessibility Learnings

This journal documents critical UX and accessibility patterns, insights, and constraints discovered within the XamOps project.

## Standard UX Patterns

- **Icon Buttons**: Must have an `aria-label` and be wrapped in a `TooltipWrapper` with matching text.
- **Focus States**: Use `focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500 focus-visible:ring-offset-1 dark:focus-visible:ring-offset-slate-900` for consistent keyboard navigation.
- **Theme Toggles**: Use `role="switch"` and `aria-checked` to communicate state to screen readers. Use CSS transforms (`translate-x`) for thumb animations.

## 2025-05-20 - Accidental Lockfile Commits
**Learning:** Running `pnpm install` in a subdirectory can generate a new `pnpm-lock.yaml` which, if committed, violates the 50-line PR limit and adds significant noise to the codebase.
**Action:** Always check `git status` or `git diff` before submitting to ensure only relevant source files are included. Use `git rm --cached` or `delete_file` if a lockfile was accidentally created.
