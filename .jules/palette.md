## 2025-05-14 - [Topbar Tooltips]
**Learning:** Icon-only buttons in the Topbar (Notifications, Help) were missing tooltips and accessible labels, which hindered keyboard and screen reader navigation. Using the project's `TooltipWrapper` provides a consistent and accessible way to reveal the purpose of these actions.
**Action:** Always wrap icon-only buttons in `TooltipWrapper` and ensure they have descriptive `aria-label` or `title` attributes (preferring `aria-label` if the tooltip text is redundant).
