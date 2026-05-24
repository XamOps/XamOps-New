## 2025-05-15 - Standardizing Topbar Accessibility

**Learning:** Interactive elements in a stable layout like the Topbar must have consistent focus indicators and accessible labels to provide a high-quality experience for keyboard and screen reader users. The use of `TooltipWrapper` for icon-only buttons is a reusable pattern that provides visual labels without cluttering the UI. Implementing theme toggle transitions with CSS transforms (`translate-x`) is more reliable and performant than adjusting positional properties.

**Action:** Always wrap icon-only buttons in `TooltipWrapper`, ensure `aria-label` is present for non-textual triggers, and use the standardized focus ring pattern: `focus-visible:ring-2 focus-visible:ring-indigo-500 focus-visible:ring-offset-1 dark:focus-visible:ring-offset-slate-900`.
