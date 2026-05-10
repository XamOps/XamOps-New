## 2024-05-10 - Accessible Pagination Patterns
**Learning:** Icon-only navigation buttons in pagination components often lack context for screen readers and visual hover feedback. Using a centralized `TooltipWrapper` combined with `aria-label` provides a consistent multi-modal accessible experience.
**Action:** Always wrap icon-only controls in `TooltipWrapper` and ensure `aria-label` matches the tooltip text exactly for consistent assistive technology announcements.

## 2024-05-10 - Standardized Focus States
**Learning:** A clear, high-contrast focus ring is essential for keyboard navigation, especially in complex dashboards with dark and light themes.
**Action:** Use the pattern `focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500 focus-visible:ring-offset-1 dark:focus-visible:ring-offset-slate-900` for interactive elements to ensure visibility across all themes.
