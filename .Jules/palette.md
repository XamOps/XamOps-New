# 🎨 Palette Journal - XamOps UX & Accessibility

This journal documents critical UX/accessibility learnings and patterns discovered during the enhancement of the XamOps interface.

## 2025-05-14 - [Topbar Accessibility Gaps]
**Learning:** Icon-only buttons in the global `Topbar` lacked both accessible labels and tooltips, making them difficult for screen reader users and potentially confusing for sighted users. Additionally, the theme toggle was missing semantic ARIA switch attributes.
**Action:** Wrap icon-only buttons in `TooltipWrapper` (which provides both tooltips and accessible triggers) and ensure all toggle interactions use `role="switch"` with appropriate `aria-checked` states.
