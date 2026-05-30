## 2025-05-15 - Refactoring Pagination for Accessibility
**Learning:** Icon-only navigation buttons in the `Pagination` component lacked accessible labels and tooltips, hindering screen reader and keyboard users. Using a dedicated `NavButton` sub-component to encapsulate `TooltipWrapper`, `aria-label`, and standardized focus rings ensures consistency and accessibility across the app.
**Action:** Always wrap icon-only buttons in `TooltipWrapper` and provide a descriptive `aria-label`. Use the standard focus ring pattern: `focus-visible:ring-2 focus-visible:ring-indigo-500 focus-visible:ring-offset-1`.

## 2025-05-15 - Micro-UX PR Constraints
**Learning:** Running `pnpm install` in the `frontend-app` directory generates a large `pnpm-lock.yaml` file that exceeds the 50-line PR limit.
**Action:** Explicitly exclude `pnpm-lock.yaml` from commits when performing micro-UX improvements to keep PRs focused and under the line limit.
