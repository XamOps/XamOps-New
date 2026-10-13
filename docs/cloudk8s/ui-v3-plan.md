# CloudK8s UI v3 foundation — implementation plan

Frontend-only foundation phase. No backend endpoints, no automation behaviour. Base branch is
`feat/cloudk8s-ui-revamp`; work happens on `feat/cloudk8s-ui-v3` in the worktree
`/Users/apple/Desktop/XamOps-New/.worktrees/cloudk8s-ui-v3` (frontend under `frontend-app/`). Leave
the branch unmerged and unpushed.

Source of truth, in order: the verbatim task, then `automation-v3/design-delta.md` and
`automation-v3/glossary-additions.md` and `automation-v3/mocks/INDEX.md` under
`.agents/tasks/cloudk8s-ui-revamp-2026-10-09/`. Where this plan and the design conflict, the design
wins.

## What exploration established (ground truth, read in the branch)

- **Tokens today** (`frontend-app/src/styles/k8sSemanticTokens.css`): all status/series values are
  greys; `--k8s-series-allocatable` and `--k8s-series-overhead` exist as separate greys (the design
  wants them aliased to `series-capacity`). FE-0 replaces the values and adds the new tokens plus a
  `.dark` block.
- **Tailwind** (`frontend-app/tailwind.config.js`): `darkMode: 'class'` is set; the semantic names
  (`status-*`, `series-*`) are mapped via `rgb(var(--k8s-…) / <alpha-value>)`. New names must be
  added: `series-capacity`, `money-saved`, `money-extra`, `feature-workload/node/hpa/vpa`.
- **The guard** (`frontend-app/src/test/designRules.test.ts`): bans chromatic hex/`rgb()`/Tailwind
  hue classes, gradients, emoji, Font Awesome, template-colour. `GUARDED_PATHS` lists the CloudK8s
  tree slice by slice; the token CSS is deliberately NOT guarded today. The design (§2.6) rewrites
  this: a reviewed `PALETTE` constant, `palette-matches-file`, `palette-contrast`, `channel-triple`,
  `token-scope`, widened gradient rule, and new fixtures.
- **Charting library**: the CloudK8s cluster pages draw with hand-rolled inline SVG today
  (`CapacityDonut.tsx`, `RequestBar.tsx`). `recharts@3.8.0` is installed and the design (§2.5)
  explicitly chooses recharts for `TimeSeriesChart`/`StackedArea`/`Bars`, and plain SVG for
  `Sparkline`/`MiniBar`/`TimeStrip`/`LanesTimeline`. Decision: follow the design — wrap recharts in
  the three area/bar components, keep the small primitives as plain SVG. `chartTokens.ts` is the only
  file that names series tokens for SVG; recharts series read the CSS variables.
- **Status components** (`frontend-app/src/components/CloudK8s/shared/status/`) and
  **charts** (`.../shared/charts/`) directories do NOT exist yet — FE-0/FE-1 create them. Today only
  `StatusMark.tsx` exists (a thin icon+label+token component) and the design keeps it as a wrapper
  over the new `StatusChip`.
- **Apply proof plumbing**: endpoint 3's reply type is `frontend-app/src/types/workloadRightsizing.ts`
  — `ApplyResult` (`submitted` / `writes-not-deployed` / `cluster-not-registered` / `refused` /
  `error`) carrying `ApplyOutcomeWire` with `terminal` (`APPLIED`/`DEFERRED`/`INFEASIBLE`/
  `STUCK_IN_PROGRESS`/`RESTART_DETECTED`/`DRY_RUN`/…) and `pods[]` of `ApplyPodProofWire`
  (uidBefore/After, restartCount before/after, observedBefore/After, ready). The stepper reads these.
  There is NO `pods[].revert` field in the branch type yet (that is S7/BE-11, out of scope); the
  deferred path must use the honest interim copy ("may still be applied later"), per design §5.0/§9.4.
- **The autoscaler apply UI** lives in `frontend-app/src/components/EksDetails/tabs/EksAutomationTab.tsx`,
  rendered by `AutomationSection.tsx` at sub `workload-autoscaling`. It currently uses Font Awesome
  (`fas fa-*`) icons and is NOT in `GUARDED_PATHS`. Re-skinning it to v3 means it will need bundled
  `mdi:*` icons and (once added to the guard) must pass the guard.
- **Capability model**: `useK8sCapabilities().can(actionId, ctx)` returns a `Capability`; gated
  controls use `GatedAction`/`GatedField`/`GatedSwitch`. Preserve every gate.
- **Demo mode**: `frontend-app/src/demo/demoRouter.ts` intercepts fetch; the vertical write path
  (`…/workloads/.../apply`) returns `DEMO_WRITE_REFUSED` (403), so no mutation reaches the network.
  Any new fixtures must keep that invariant.
- **Dark mode gap (flag)**: `frontend-app/src/contexts/ThemeContext.tsx` tracks `'light'|'dark'` in
  state/localStorage but does NOT add a `.dark` class to an ancestor element. Tailwind's class dark
  mode needs that class. The dark CloudK8s content the design requires will not render unless a
  `.dark` class is applied to the CloudK8s page subtree (or the document root). The implementer must
  wire this (preferably a scoped wrapper on the EKS details page driven by `useTheme`, so the rest of
  the app is unaffected per design open-decision §9.2) and must verify it, not assume it.
- **Build/test**: `npm run build` = `icons:bundle && tsc && vite build`; `npm run test` = `vitest run`.
  Baseline (setup.md): build GREEN, **1295 tests across 101 files**. Build touches
  `frontend-app/dist/index.html` and `frontend-app/public/version.json` and writes
  `tsconfig.tsbuildinfo` — restore the first two with `git restore`, never commit `dist/` or
  `tsbuildinfo`.
- No Playwright/Puppeteer in `package.json`. A globally-cached `playwright-core` exists (used by the
  mock `render.cjs`). The screenshot pass drives headless Chromium against a preview server; see
  verification.

Nothing below may be treated as already-done from reading code. Where a thing looks present (e.g.
`StatusMark`, the guard, demo refusal), it is listed as needs-verification during implementation.

---

## Phase A — FE-0: palette, tokens, guard, colour audit

- [ ] 1. Give the semantic tokens real values and add the new tokens, light and dark.
      Rewrite `frontend-app/src/styles/k8sSemanticTokens.css`: set `status-good/warn/bad/info/off`,
      `series-requested/used/suggested/limit`, and ADD `series-capacity`, `money-saved`,
      `money-extra`, `feature-workload/node/hpa/vpa` under `:root` (light) and a new `.dark` block
      (dark) — exact R G B triples from design-delta §2.2. Keep `series-allocatable` and
      `series-overhead` as aliases set to the capacity triple (removed in a later FEAT, out of scope
      here). Values are space-separated channels only — no hex, `rgb(`, `color-mix(`, gradient.
      Update the header comment to "The owner's palette, design-delta v3 §2".
      Files: `frontend-app/src/styles/k8sSemanticTokens.css`
      Verify: `npm run build` (tsc + vite) stays green; visually confirmed via the audit test in item 4.

- [ ] 2. Map the new token names in Tailwind.
      Add `series-capacity`, `money-saved`, `money-extra`, `feature-workload`, `feature-node`,
      `feature-hpa`, `feature-vpa` to `theme.extend.colors`, each `rgb(var(--k8s-…) / <alpha-value>)`.
      Keep every existing name.
      Files: `frontend-app/tailwind.config.js`
      Verify: `npm run build` green; a probe class like `bg-feature-workload` resolves (checked by the
      audit test in item 4).

- [ ] 3. Create the status components under `shared/status/`.
      Create `statusTones.ts` (glossary-additions §2 maps: status→tone+icon, result→tone+icon,
      feature-status→tone), `StatusChip.tsx` and `Delta.tsx`, `KpiCard.tsx` (left accent bar, tinted
      uppercase label with icon, number in ink), `FeatureMark.tsx` (feature tile + rule, hues from
      §2.2/§11), `NowBanner.tsx` + `nowSentence.ts` (pure `nowSentence(page, inputs)` returning
      clauses; design §4.1, glossary §3), `HowItWorks.tsx` (5-step Measure→Decide→Apply→Verify→Watch,
      static copy from glossary §4, current-state highlight), `StepIndicator.tsx` (per-workload
      lifecycle, glossary §5), `ApplyStepper.tsx` (reads endpoint-3 `ApplyOutcomeWire`+`pods[]`,
      glossary §6; deferred path uses the honest interim copy since no `revert` field exists on the
      branch), and `TermTip.tsx` ("What's this?": a `button` with `aria-describedby`, opens on
      hover+focus, closes on Escape, text from glossary §1). Every colour-bearing component takes
      `icon` and `label` as required props. Rewrite `StatusMark.tsx` as a thin wrapper over
      `StatusChip` (keep its exported API/states so existing callers compile).
      Files: `frontend-app/src/components/CloudK8s/shared/status/*.tsx|ts`,
      `frontend-app/src/components/CloudK8s/shared/StatusMark.tsx`
      Verify: `npx vitest run src/components/CloudK8s/shared` — new unit tests for `nowSentence`
      (every clause combination incl. today's two real applies), lifecycle derivation (glossary §5),
      `TermTip` keyboard/ARIA, and the stepper paths pass; `npm run build` green.

- [ ] 4. Rewrite the design-rule guard and implement the colour-audit test.
      In `frontend-app/src/test/designRules.test.ts`: add the exported `PALETTE_FILE` and `PALETTE`
      constant (exact table from design §2.6, incl. the two alias entries with their comments); drop
      the never-implemented single-accent exception; add rule `channel-triple` (unequal `--name: R G B`
      in a guarded file fails; equal-channel greys pass), rule `token-scope` (status/money classes
      only in `shared/status/`, `StatusMark.tsx`, and `SummaryPanel.tsx`; series only in
      `shared/charts/`, `CapacityDonut.tsx`, `RequestBar.tsx`, `SummaryPanel.tsx`; feature only in
      `FeatureMark.tsx` and `charts/LanesTimeline.tsx`; raw `var(--k8s-…)` only in
      `charts/chartTokens.ts` and the status components), widen the gradient rule to
      `radial-gradient(`/`conic-gradient(`/`repeating-*-gradient(` (SVG `<pattern>` stays allowed),
      and add tests `palette-matches-file` (parse `:root`+`.dark`, must equal `PALETTE` exactly, and
      reject any hex/`rgb(`/`color-mix(`/gradient in the file) and `palette-contrast` (WCAG ratio on
      card and, for text roles, on the chip tint; fail <4.5 text, <3 graphic). Add `GUARDED_PATHS`
      entries: `styles/k8sSemanticTokens.css`, `components/CloudK8s/shared/status/`,
      `components/CloudK8s/shared/charts/`. Add the new self-test fixtures from §2.6 item 8
      (both failing and passing). Then add
      `frontend-app/src/components/CloudK8s/shared/charts/__tests__/colourAudit.test.tsx` per §2.7:
      render every chart kind and status component in light and dark, assert every colour resolves to
      a token (or `currentColor`/`none`/`transparent`/`url(#…)` pattern/equal-channel grey), each
      series keeps one token+dash+fill, status is never colour-alone (icon + text present), and no
      feature accent appears inside a chart plot area except Activity source dots.
      Files: `frontend-app/src/test/designRules.test.ts`,
      `frontend-app/src/components/CloudK8s/shared/charts/__tests__/colourAudit.test.tsx`
      Verify: `npx vitest run src/test/designRules.test.ts` and `npx vitest run src/components/CloudK8s/shared/charts/__tests__/colourAudit.test.tsx` pass (the audit depends on the chart kit from Phase B — if run before Phase B lands, scope the audit to status components first, then extend; the FEAT ordering below sequences this correctly).

Design decisions recorded here: (a) recharts for area/bar charts, plain SVG for the small
primitives — matches design §2.5 and avoids re-inventing what recharts gives, while the SVG
primitives stay deterministic/seek-safe. (b) The deferred stepper shows interim copy, because the
`pods[].revert` proof field is a backend change out of scope. (c) Dark is applied via a scoped
`.dark` wrapper on the CloudK8s subtree driven by `useTheme`, not globally, matching design
open-decision §9.2 (dark stays CloudK8s-only for now).

---

## Phase B — the chart kit (`shared/charts/`)

- [ ] 5. Create `chartTokens.ts` and `ChartCard`/`ChartDataTable`.
      `chartTokens.ts`: series kind → token class, stroke width, dash array, fill opacity, pattern id
      (the only file naming series tokens for SVG). `ChartCard` (title, on-screen source line per
      glossary §10, and the empty/partial states from glossary §10) and `ChartDataTable` (accessible
      table of the plotted points).
      Files: `frontend-app/src/components/CloudK8s/shared/charts/chartTokens.ts`,
      `.../charts/ChartCard.tsx`, `.../charts/ChartDataTable.tsx`
      Verify: `npx vitest run src/components/CloudK8s/shared/charts` — unit tests pass.

- [ ] 6. Build the recharts-backed components: `TimeSeriesChart`, `StackedArea`, `Bars`.
      `TimeSeriesChart` (filled/stacked areas, bands for freed/ready/no-history, hatch via recharts
      `<defs>` `<pattern>`), `StackedArea` (stacks to a stated total), `Bars` in its 4 modes (grouped,
      before/after, capacity rows, day bars). Every series/area sets explicit `stroke`/`fill` from
      `chartTokens` — never a recharts default (`#8884d8`, `#82ca9d`, `#ffc658`). Greyscale chrome;
      colour only for series meaning. Charts read the CSS variables so they theme automatically; SVG
      patterns use `var(--card)` as background. Deterministic/seek-safe: no animation that depends on
      wall-clock; fixed inputs render identically.
      Files: `.../charts/TimeSeriesChart.tsx`, `.../charts/StackedArea.tsx`, `.../charts/Bars.tsx`
      Verify: `npx vitest run src/components/CloudK8s/shared/charts` passes; the colour audit (item 4)
      now covers these and stays green.

- [ ] 7. Build the plain-SVG primitives: `Sparkline`, `MiniBar`, `TimeStrip`, `LanesTimeline`.
      Deterministic inline SVG matching the hand-rolled style already in `CapacityDonut`. `MiniBar`
      for table cells (request/use/suggestion/money), `Sparkline` for 3-day per-row use, `TimeStrip`
      for time-at-maximum, `LanesTimeline` for the 7-day per-workload activity lanes (its Activity
      source dots are the only place a feature accent may enter a plot area).
      Files: `.../charts/Sparkline.tsx`, `.../charts/MiniBar.tsx`, `.../charts/TimeStrip.tsx`,
      `.../charts/LanesTimeline.tsx`
      Verify: `npx vitest run src/components/CloudK8s/shared/charts` passes; colour audit green.

---

## Phase C — re-skin the already-built pages (real data vs honest empty state)

Data map (which chart has a live backend here):
- **Live now**: endpoint 1 (vertical recommendations GET) and endpoint 3 (apply reply, incl. per-pod
  proof). Prometheus-through-service-proxy "now" usage via the existing dashboard.
- **NOT built (render from fixtures in demo; honest "not available yet" against live data; never
  fabricate series)**: BE-3 usage range / namespace / sparklines, BE-11 apply progress, BE-12 node
  room, BE-13 namespace cost, BE-5 Activity, BE-4 savings-over-time. Every chart whose source is one
  of these shows the glossary §10 empty state.

- [ ] 8. Re-skin Automation › Workload autoscaler › Workloads (list) — `EksAutomationTab.tsx` list view.
      Add the "Now" sentence (apply-overprovisioned-api-first clause only when node room is known —
      which it is not live, so that clause is absent against live data), the collapsed "How it works",
      KPI cards with status accents, the 3-day sparkline per row (honest empty when BE-3 sparklines
      absent), coloured status chips, mini-bars (request/use/suggestion/money), and a per-row
      lifecycle stage. Savings-by-workload and before/after bars render from fixtures in demo and show
      the honest empty/"$—" state against live data (no BE-4). Replace all `fas fa-*` icons with
      bundled `mdi:*`. Keep every capability gate and the demo write-refusal.
      Files: `frontend-app/src/components/EksDetails/tabs/EksAutomationTab.tsx` (+ small extracted
      components as needed under `sections/automation/`)
      Verify: `npx vitest run src/components/EksDetails` passes (update/extend
      `EksAutomationTab.test.tsx`); demo-mode test still proves no mutation reaches the network.

- [ ] 9. Re-skin Automation › Workload autoscaler › detail — the live apply stepper + What changed.
      Build the detail view driving `ApplyStepper` from endpoint 3's reply (`ApplyOutcomeWire` +
      `pods[]`): success, deferred (interim copy), infeasible/refused paths. Add the "What changed"
      per-pod card (restarts before→after, ready chip) and the per-workload lifecycle bar. Impact,
      cost-per-day and "did the new size take effect?" charts render from fixtures in demo and show
      honest empty states against live data (no BE-3 range). The confirmation dialog keeps its
      existing gates; no node-room "probably deferred" line live (no BE-12).
      Files: `frontend-app/src/components/EksDetails/tabs/EksAutomationTab.tsx` and extracted detail
      components under `sections/automation/`
      Verify: `npx vitest run src/components/EksDetails` passes; stepper tests cover success +
      deferred + refused; demo mutation-free test passes.

- [ ] 10. Re-skin Automation › Overview — `OverviewPanel.tsx`.
      Add the "Now" sentence, the 5-step "How it works" with per-step state, KPI accents, feature
      cards with feature accents (`FeatureMark`), and keep the existing Access block + capability
      table. The 7-day activity timeline, savings bullet and CPU-by-namespace bars render from
      fixtures in demo and show honest empty states against live data (no BE-5/BE-4/BE-3). "Features
      that are off" uses the existing `GatedAction` reasons.
      Files: `frontend-app/src/components/EksDetails/sections/automation/OverviewPanel.tsx`
      Verify: `npx vitest run src/components/EksDetails/sections/automation` passes.

- [ ] 11. Re-skin the cluster Overview › Summary — `SummaryPanel.tsx`.
      Add the "Now" sentence and status-accent cards; recolour the donuts to series tokens (already
      token-wired — confirm they pick up the new values); apply the KPI/table/chart colour treatment
      the owner asked for (filled areas, KPI status accent bars with numbers in ink, tinted status
      cells, coloured mini-bars). CPU/memory over-time stacked areas render from fixtures in demo and
      show honest empty states against live data (no BE-3 range). `SummaryPanel` is in the
      `token-scope` allowlist for status+series until the alias removal FEAT (out of scope).
      Files: `frontend-app/src/components/EksDetails/sections/overview/SummaryPanel.tsx`
      Verify: `npx vitest run src/components/EksDetails/sections/overview` passes.

- [ ] 12. Nodes and other already-present pages: palette/kit only.
      For any cluster page already on the branch that the task does not single out for new data
      (Nodes list, etc.), apply only the palette tokens / kit components (chips, KPI accents, series
      tokens). Do NOT build new data or new charts for them. Confirm which pages exist before editing;
      list each touched file in the FEAT findings.
      Files: `frontend-app/src/components/EksDetails/sections/nodes/NodeGroupsPanel.tsx` and any other
      already-present section panels that render status/series.
      Verify: `npx vitest run src/components/EksDetails` passes; guard green.

---

## Phase D — integration verification and the screenshot pass

- [ ] 13. Full build + full test suite + guard + audit, cross-page.
      Run `npm run build` (zero TS errors) and `npm run test` (count grows from the 1295 baseline;
      never shrinks). Confirm the guard (`designRules.test.ts`), `palette-matches-file`,
      `palette-contrast`, and `colourAudit` are all green. Confirm demo mode still issues no mutation.
      Restore `frontend-app/dist/index.html` and `frontend-app/public/version.json` with `git restore`;
      confirm `dist/` and `tsconfig.tsbuildinfo` are not staged.
      Verify: `npm run build && npm run test`; `git -C /Users/apple/Desktop/XamOps-New/.worktrees/cloudk8s-ui-v3 status` shows no `dist/`/`tsbuildinfo` and a clean `dist/index.html`+`version.json`.

- [ ] 14. Headless light + dark screenshot pass compared to the mocks.
      Serve a production preview (`vite preview` or `vite dev` on a self-chosen non-default port —
      never a shared port), drive headless Chromium (the globally-cached `playwright-core` the mock
      `render.cjs` uses; do not add a dependency) in demo mode for cluster `xamops-test-1`, capturing
      the four re-skinned pages in light and in dark (toggle the `.dark` wrapper via the theme), and
      compare against the v3 mocks (`10-…`, `11-…`, `13-…`, `01-…`, `02-…dark…`). Write the captures
      under `/Users/apple/Desktop/XamOps-New/.agents/tasks/cloudk8s-ui-v3-2026-10-12/`. Confirm the
      `.dark` wrapper is actually applied (the ThemeContext gap above) — a dark capture that looks
      identical to light means the class was never added. Stop the preview server and remove scratch
      state. Never export AWS credentials.
      Verify: both light and dark captures exist for all four pages and visibly match the mocks'
      colour treatment (status accents, filled areas, feature hues, dark surfaces).

---

## Verification summary (applies to every FEAT)

- Build: `npm run build` — zero TS errors.
- Tests: `npm run test` — total only grows from 1295.
- Guard + audit green: `designRules.test.ts` (incl. `palette-matches-file`, `palette-contrast`,
  `channel-triple`, `token-scope`), `colourAudit.test.tsx`.
- Demo mode issues no network mutation.
- Accessibility: colour never the only signal (icon + label everywhere), AA contrast (enforced by
  `palette-contrast`), keyboard/ARIA on `TermTip` and interactive controls.
- Housekeeping: restore `dist/index.html` + `public/version.json`; never commit `dist/` or
  `tsconfig.tsbuildinfo`; use own ports; stop scratch processes; never touch other worktrees or the
  main checkout; never export AWS credentials.

## Open gaps / assumptions

- Dark mode is not wired to a `.dark` class today; the plan assumes a scoped CloudK8s wrapper driven
  by `useTheme` (design §9.2). If the implementer finds a different existing mechanism, use it, but
  the dark screenshot pass must prove the dark tokens actually render.
- The deferred apply path uses interim copy because the S7/BE-11 `revert` proof is a backend change
  out of scope. If a `pods[].revert` field turns out to already exist on the branch, prefer it; else
  interim copy stands.
- Charts whose backend endpoint is unbuilt (BE-3/5/11/12/13, BE-4 extras) must show the glossary §10
  honest empty state against live data and only render series from fixtures in demo mode.
