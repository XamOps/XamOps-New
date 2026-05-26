## 2025-05-22 - [Reusable UX Pattern: Local NavButton for Accessible Pagination]
**Learning:** In components with multiple similar icon buttons (like Pagination), refactoring them into a local sub-component within the same file allows for adding accessibility features (ARIA labels, Tooltips, focus states) across all instances while keeping the total change count under the 50-line constraint.
**Action:** Use local sub-components for repeated interactive elements to standardize accessibility and visual polish without bloating the PR size.
