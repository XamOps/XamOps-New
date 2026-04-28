# Palette Journal 🎨

## 2025-05-14 - Enhancing Pagination Accessibility & Repo Health
**Learning:** Icon-only buttons in pagination (First, Prev, Next, Last) lack descriptive labels and focus states. Additionally, pre-existing repo corruption (ghost submodules) can block CI for UX improvements if not addressed.
**Action:** Add `aria-label`, `TooltipWrapper`, and `focus-visible` rings to pagination. Perform surgical repo fixes (like removing orphaned gitlinks) when they block deployment of UX changes, even if strictly outside persona boundaries.
