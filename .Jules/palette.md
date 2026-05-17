## 2025-05-14 - Accessible Pagination Pattern
**Learning:** Icon-only navigation buttons in pagination require explicit `aria-label` attributes and tooltip descriptions to be usable by screen readers and provide clarity to all users. A consistent focus-visible ring (e.g., `ring-indigo-500`) with theme-aware offsets ensures keyboard accessibility across light and dark modes.
**Action:** Encapsulate repeating navigation buttons into a local `NavButton` component that enforces `aria-label`, `TooltipWrapper`, and accessible focus states.
