## 2026-07-07 - Accessible Pagination Controls
**Learning:** Icon-only navigation buttons (like First/Previous/Next/Last in Pagination) are inaccessible to screen readers and confusing for users without explicit labels. Using `aria-label` and wrapping them in `TooltipWrapper` provides both programmatic accessibility and visual guidance.
**Action:** Always add `aria-label` and `TooltipWrapper` to icon-only buttons in shared components.

## 2026-07-07 - Dependency Management in Frontend
**Learning:** Mismatched versions between `vite` and `vitest` (e.g., vitest 2.x with vite 5.x) can cause runtime errors during testing. Ensuring `vitest` is at a compatible version (like 1.6.1) resolves these issues.
**Action:** Check peer dependency compatibility when encountering "Cannot find module" or "Unknown file extension" errors in tests.
