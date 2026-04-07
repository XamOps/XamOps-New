## 2025-05-15 - [Enhancing Topbar Discoverability]
**Learning:** Icon-only buttons in the top navigation bar (Notifications, Help) are often overlooked or ambiguous for new users. Adding tooltips alongside ARIA labels significantly improves both accessibility for screen readers and discoverability for mouse users without cluttering the UI.
**Action:** Always wrap icon-only buttons in `TooltipWrapper` and ensure `aria-label` is present to provide a consistent experience across different input methods.
