# Palette Journal

## 2025-05-15 - Topbar Accessibility & Tooltips
**Learning:** Icon-only buttons in the global navigation (Notifications, Help) were missing descriptive labels for screen readers and tooltips for sighted users. The theme toggle was a generic button, which didn't communicate its state as a switch.
**Action:** Use `TooltipWrapper` for all icon-only buttons in the Topbar and ensure `aria-label` is present. Implement the theme toggle with `role="switch"` and `aria-checked` to provide better semantic context.
