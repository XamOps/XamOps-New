## 2026-03-19 - TopBar Accessibility and Icon Mapping

**Learning:** Icon-only buttons in navigation headers (TopBar) are common accessibility gaps. Using a reusable `TooltipWrapper` based on `react-aria-components` provides a consistent way to add both visual tooltips and ARIA labels. Additionally, build failures in the 'Cloud' category of the `IconPicker` were traced to an outdated icon export (`SiAmazon` vs `SiAmazonwebservices`).

**Action:** Always check icon-only buttons for ARIA labels and tooltips. Use `SiAmazonwebservices` instead of `SiAmazon` when working with `react-icons/si`. Ensure that any fix for a local build failure (like adding `quill`) is not accidentally committed to the repository.
