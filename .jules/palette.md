# 🎨 Palette's Journal

## 2025-05-15 - Improving Core Navigation Accessibility
**Learning:** Core navigation elements like the Topbar and Sidebar often lack visible focus states and descriptive tooltips for icon-only buttons, which hinders keyboard navigation and screen reader support.
**Action:** Always ensure icon-only buttons have `aria-label` and are wrapped in `TooltipWrapper`. Apply `focus-visible` styles to all interactive elements to support keyboard users without cluttering the UI for mouse users.
