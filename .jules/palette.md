## 2026-07-07 - [Tooltip Consistency in Pagination]
**Learning:** Icon-only buttons in pagination (First, Previous, Next, Last) lack accessible labels and visual cues, which can be confusing for screen reader users and new mouse users. Using `TooltipWrapper` from the existing UI library is the preferred pattern to address this.
**Action:** Always apply `aria-label` and `TooltipWrapper` to icon-only navigation controls to ensure a high standard of accessibility and UX consistency across the application.
