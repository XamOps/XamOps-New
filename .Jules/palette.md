# Palette's Journal - UX and Accessibility Learnings

## 2026-07-17 - Topbar Icon Buttons Usability
**Learning:** Icon-only navigation buttons (like Help, Notifications, Missed Calls) can be inaccessible to screen readers and mouse hover without explicit accessible labels and tooltip components.
**Action:** Always wrap icon-only buttons with `TooltipWrapper` or provide descriptive ARIA labels to ensure visual clarity and compatibility with assistive technologies.
