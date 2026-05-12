## 2025-05-12 - Component Refactoring for Accessibility
**Learning:** To implement comprehensive accessibility (ARIA labels, tooltips, focus states) while staying within line-count constraints, refactor repeating elements into local sub-components within the same file. This minimizes JSX duplication and diff noise.
**Action:** Use local sub-components like `NavButton` when enhancing multiple similar interactive elements for a11y.
