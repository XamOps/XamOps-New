# Palette's Journal - UX & Accessibility Learnings

## 2026-07-07 - Pagination Accessibility
**Learning:** Common UI components like Pagination often miss basic accessibility features like ARIA labels and tooltips for icon-only buttons, making them difficult for screen reader users and less intuitive for sighted users.
**Action:** Always wrap icon-only buttons in `TooltipWrapper` and provide descriptive `aria-label` attributes.
