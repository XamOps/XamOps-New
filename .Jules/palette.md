# Palette Journal

## 2026-05-19 - Standardizing Icon-Only Buttons
**Learning:** Icon-only buttons without explicit labels are inaccessible to screen readers and often rely on inconsistent native `title` attributes for tooltips.
**Action:** Use `TooltipWrapper` from the UI library for consistent, accessible tooltips and always provide a matching `aria-label` that exactly matches the tooltip text.
