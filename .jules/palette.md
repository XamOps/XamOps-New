# Palette's Journal - UX & Accessibility Learnings

## 2026-06-25 - Tooltips and ARIA labels for Icon-only Buttons
**Learning:** Icon-only buttons in the global navigation (TopBar) provide no context for screen readers or users who don't recognize the icons. Standardizing on the `TooltipWrapper` component (built on `react-aria-components`) ensures both visual tooltips and accessible names.
**Action:** Always wrap icon-only buttons in `TooltipWrapper` and provide a descriptive `aria-label`.
