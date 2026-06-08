# Palette's Journal

## 2025-05-14 - [Accessibility Improvements in SuperAdminTopBar]
**Learning:** Icon-only buttons (like the Reset button) and form controls without explicit label associations are common accessibility gaps. Using `useId` for stable IDs and `TooltipWrapper` for accessible hints significantly improves the experience for keyboard and screen reader users. Also, in this repo, running `pnpm install` in subdirectories generates large lockfiles that must be excluded from PRs to respect the 50-line limit.
**Action:** Always link labels to inputs using `htmlFor` and `id`, and wrap icon-only buttons in `TooltipWrapper` with corresponding `aria-label`. Ensure no environment-generated artifacts like `pnpm-lock.yaml` are included in the final submission.
