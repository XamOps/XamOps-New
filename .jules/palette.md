# Palette Journal 🎨

Critical UX and accessibility learnings.

Format:
## YYYY-MM-DD - [Title]
**Learning:** [UX/a11y insight]
**Action:** [How to apply next time]

## 2026-06-24 - Accessible Shared Controls in SuperAdminTopBar
**Learning:** Shared management components like `SuperAdminTopBar` often lack basic accessibility (ARIA labels, label-input associations) because they are treated as "internal tools". However, consistency in these areas is crucial for a professional feel and for power users who rely on screen readers or keyboard navigation. Using the `useId` hook is the safest way to ensure unique, accessible associations in components that might be rendered multiple times or in complex layouts. Icon-only buttons MUST have both a tooltip for visual discovery and a synchronized `aria-label` for screen readers.
**Action:** Always use `useId` for form controls and wrap icon-only actions in `TooltipWrapper` with matching `aria-label`.
