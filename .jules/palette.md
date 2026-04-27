## 2025-05-15 - Standardizing Navigation Focus States
**Learning:** Inconsistent focus indicators across Sidebar and Topbar navigation elements reduce keyboard accessibility. Using a unified `focus-visible` ring pattern improves the experience for power users and meets WCAG standards.
**Action:** Apply `focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500 focus-visible:ring-offset-1 dark:focus-visible:ring-offset-slate-900` to all interactive navigation components (links, buttons, selectors).

## 2025-05-15 - Enhancing Icon-Only Buttons with Tooltips
**Learning:** Icon-only buttons in the Topbar (Notifications, Help) are ambiguous without text labels. While `aria-label` helps screen readers, tooltips provide necessary visual cues for all users.
**Action:** Wrap icon-only buttons in `TooltipWrapper` to provide hover/focus labels while maintaining a clean, minimalist UI.
